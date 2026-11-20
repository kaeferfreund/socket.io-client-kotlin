package io.github.kaeferfreund.socketio.parser

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData

/**
 * A packet the decoder refused. The [message] is the exact text
 * `socket.io-parser` throws for the same input (for example
 * `"invalid payload"` or `"Illegal attachments"`), or a description of the
 * [SocketParserOptions] limit that was exceeded.
 */
public class SocketIOParseException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Limits for one incoming Socket.IO packet.
 *
 * The defaults are the JavaScript defaults: at most 10 attachments
 * (`maxAttachments` of the JavaScript `Decoder`) and nothing else bounded.
 * Lowering a limit is an opt-in hardening; a packet exceeding it fails to
 * decode, which closes the connection with `"parse error"`.
 */
public class SocketParserOptions(
    /** Maximum binary attachments one packet may declare. */
    public val maxAttachments: Int = 10,
    /** Maximum total attachment bytes of one binary packet. */
    public val maxBinaryPacketBytes: Long = Long.MAX_VALUE,
    /** Maximum UTF-8 bytes of one text packet (header plus JSON). */
    public val maxTextPacketBytes: Long = Long.MAX_VALUE,
    /** Maximum JSON array/object nesting. Parsing is iterative, so this is a resource bound, not a crash guard. */
    public val maxNestingDepth: Int = Int.MAX_VALUE,
    /** Optional `JSON.parse` reviver applied to every decoded payload, see [SocketIOJson.parse]. */
    public val reviver: ((key: String, value: SocketIOValue) -> SocketIOValue?)? = null,
) {
    init {
        require(maxAttachments > 0) { "maxAttachments must be positive" }
        require(maxBinaryPacketBytes > 0) { "maxBinaryPacketBytes must be positive" }
        require(maxTextPacketBytes > 0) { "maxTextPacketBytes must be positive" }
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
    }

    public companion object {
        /** The JavaScript-equal defaults. */
        public val DEFAULT: SocketParserOptions = SocketParserOptions()
    }
}

/**
 * Encoder for outgoing packets, `Encoder` in `socket.io-parser`.
 *
 * EVENT and ACK packets containing [SocketIOValue.Binary] anywhere become
 * BINARY_EVENT/BINARY_ACK: the header text with `{"_placeholder":true,"num":n}`
 * markers, followed by one binary frame per attachment.
 *
 * As in JavaScript, nothing is escaped: the namespace is written verbatim
 * before a `,`, so it must not contain `,` or U+001E, and an application
 * object shaped like a placeholder inside a binary packet is read as one by
 * the receiver. Do not build namespaces or such objects from untrusted input.
 */
public class SocketIOEncoder {
    /** Encodes [packet]; the first element is the text header, the rest are attachments. */
    public fun encode(packet: SocketIOPacket): List<EngineIOData> {
        if ((packet.type == SocketIOPacketType.EVENT || packet.type == SocketIOPacketType.ACK) && packet.data != null && hasBinary(packet.data)) {
            val buffers = ArrayList<SocketIOValue.Binary>()
            val data =
                rewrite(packet.data) { node ->
                    if (node is SocketIOValue.Binary) {
                        socketIOObject("_placeholder" to true, "num" to buffers.size).also { buffers.add(node) }
                    } else {
                        null
                    }
                }
            val type = if (packet.type == SocketIOPacketType.EVENT) SocketIOPacketType.BINARY_EVENT else SocketIOPacketType.BINARY_ACK
            val header = encodeAsString(type, packet.nsp, packet.id, data, attachments = buffers.size)
            return listOf<EngineIOData>(EngineIOData.Text(header)) + buffers.map { EngineIOData.Binary.wrap(it.unsafeBytes()) }
        }
        val data = packet.data?.let { if (hasBinary(it)) nodeBufferJson(it) else it }
        val attachments = if (packet.type == SocketIOPacketType.BINARY_EVENT || packet.type == SocketIOPacketType.BINARY_ACK) 0 else null
        return listOf(EngineIOData.Text(encodeAsString(packet.type, packet.nsp, packet.id, data, attachments)))
    }

    private fun encodeAsString(
        type: SocketIOPacketType,
        nsp: String,
        id: Long?,
        data: SocketIOValue?,
        attachments: Int?,
    ): String {
        val out = StringBuilder()
        out.append(type.code)
        if (attachments != null) out.append(attachments).append('-')
        if (nsp.isNotEmpty() && nsp != "/") out.append(nsp).append(',')
        if (id != null) out.append(id)
        if (data != null) out.append(SocketIOJson.stringify(data, binaryAsPlaceholderText = false))
        return out.toString()
    }

    /** Outside EVENT/ACK, binary is written the way Node's `Buffer.toJSON()` writes it. */
    private fun nodeBufferJson(value: SocketIOValue): SocketIOValue =
        rewrite(value) { node ->
            if (node is SocketIOValue.Binary) {
                socketIOObject("type" to "Buffer", "data" to node.unsafeBytes().map { it.toInt() and 0xff })
            } else {
                null
            }
        }
}

/**
 * Stateful decoder for incoming packets, `Decoder` in `socket.io-parser`.
 *
 * Feed every Engine.IO message to [add]. A text message yields its packet at
 * once, unless it is a binary packet header; then `null` is returned until
 * all attachments arrived. Decoding failures throw [SocketIOParseException];
 * the connection must then be closed with `"parse error"` and the decoder
 * reset with [destroy], as `Manager` does in JavaScript.
 *
 * Not thread-safe: use one decoder per connection from one thread.
 */
public class SocketIODecoder(
    public val options: SocketParserOptions = SocketParserOptions.DEFAULT,
) {
    private var pending: PendingBinaryPacket? = null

    private class PendingBinaryPacket(
        val type: SocketIOPacketType,
        val nsp: String,
        val id: Long?,
        val data: SocketIOValue?,
        val attachments: Int,
    ) {
        // Not pre-sized: the count comes from the peer; memory grows with what actually arrives.
        val buffers = ArrayList<SocketIOValue.Binary>()
        var bytes = 0L
    }

    /** `true` while a binary packet waits for attachments. */
    public val isReconstructing: Boolean get() = pending != null

    /** Number of attachments still expected by the pending binary packet. */
    public val missingAttachments: Int get() = pending?.let { it.attachments - it.buffers.size } ?: 0

    /** Decodes one Engine.IO message. */
    public fun add(data: EngineIOData): SocketIOPacket? =
        when (data) {
            is EngineIOData.Text -> add(data.value)
            is EngineIOData.Binary -> addAttachment(data.size) { data.bytes }
        }

    /** Decodes one text message. */
    public fun add(text: String): SocketIOPacket? {
        if (pending != null) throw SocketIOParseException("got plaintext data when reconstructing a packet")
        if (options.maxTextPacketBytes != Long.MAX_VALUE && utf8Length(text) > options.maxTextPacketBytes) {
            throw SocketIOParseException("text packet exceeds maxTextPacketBytes (${options.maxTextPacketBytes})")
        }
        val decoded = decodeString(text)
        if (decoded.type == SocketIOPacketType.BINARY_EVENT || decoded.type == SocketIOPacketType.BINARY_ACK) {
            val type = if (decoded.type == SocketIOPacketType.BINARY_EVENT) SocketIOPacketType.EVENT else SocketIOPacketType.ACK
            pending = PendingBinaryPacket(type, decoded.nsp, decoded.id, decoded.data, decoded.attachments)
            return null
        }
        return SocketIOPacket(decoded.type, decoded.nsp, decoded.data, decoded.id)
    }

    /** Decodes one binary attachment. The bytes are copied. */
    public fun add(bytes: ByteArray): SocketIOPacket? = addAttachment(bytes.size) { bytes.copyOf() }

    /** Checks the decoder state and the byte budget on [size] first, so rejected input is never copied. */
    private inline fun addAttachment(
        size: Int,
        copy: () -> ByteArray,
    ): SocketIOPacket? {
        val reconstruction = pending ?: throw SocketIOParseException("got binary data when not reconstructing a packet")
        reconstruction.bytes += size
        if (reconstruction.bytes > options.maxBinaryPacketBytes) {
            throw SocketIOParseException("binary packet exceeds maxBinaryPacketBytes (${options.maxBinaryPacketBytes})")
        }
        reconstruction.buffers.add(SocketIOValue.Binary.wrap(copy()))
        if (reconstruction.buffers.size < reconstruction.attachments) return null
        val buffers = reconstruction.buffers
        val data =
            reconstruction.data?.let { payload ->
                rewrite(payload) { node -> placeholderTarget(node, buffers) }
            }
        pending = null
        return SocketIOPacket(reconstruction.type, reconstruction.nsp, data, reconstruction.id)
    }

    /** Drops a partially received binary packet (`Decoder.destroy()`). */
    public fun destroy() {
        pending = null
    }

    /** Returns the attachment a placeholder refers to, or `null` for any other node. */
    private fun placeholderTarget(
        node: SocketIOValue,
        buffers: List<SocketIOValue.Binary>,
    ): SocketIOValue? {
        if (node !is SocketIOValue.Object || node["_placeholder"] != SocketIOValue.Bool.TRUE) return null
        val num = node["num"] as? SocketIOValue.Number ?: throw SocketIOParseException("illegal attachments")
        val index = num.value.toDouble()
        if (index < 0 || index >= buffers.size) throw SocketIOParseException("illegal attachments")
        // JavaScript indexes with any number in range; a fractional one yields undefined.
        // That cannot be represented, so it is rejected (a documented malformed-input difference).
        val integral = num.value as? Long ?: throw SocketIOParseException("illegal attachments")
        return buffers[integral.toInt()]
    }

    private class Decoded(
        val type: SocketIOPacketType,
        val nsp: String,
        val id: Long?,
        val data: SocketIOValue?,
        val attachments: Int,
    )

    /** A line-by-line port of `Decoder.decodeString`, including its JavaScript coercions. */
    // A line-by-line port of Decoder.decodeString; splitting it would hide the correspondence.
    @Suppress("CyclomaticComplexMethod")
    private fun decodeString(str: String): Decoded {
        var i = 0
        val typeNumber = SocketIOJson.javaScriptNumber(charAt(str, 0))
        val type =
            if (typeNumber.isNaN() || typeNumber != Math.floor(typeNumber)) null else SocketIOPacketType.fromCode(typeNumber.toInt())
        if (type == null) throw SocketIOParseException("unknown packet type ${SocketIOJson.formatDouble(typeNumber).let { if (it == "null") "NaN" else it }}")

        var attachments = 0
        if (type == SocketIOPacketType.BINARY_EVENT || type == SocketIOPacketType.BINARY_ACK) {
            val start = i + 1
            while (charAt(str, ++i) != "-" && i != str.length) {
                // scan to the attachment separator
            }
            val buf = str.substring(start, minOf(i, str.length))
            val n = SocketIOJson.javaScriptNumber(buf)
            if (n.isNaN() || charAt(str, i) != "-") throw SocketIOParseException("Illegal attachments")
            if (n.isInfinite() || n != Math.floor(n) || n < 1) {
                throw SocketIOParseException("Illegal attachments")
            } else if (n > options.maxAttachments) {
                throw SocketIOParseException("too many attachments")
            }
            attachments = n.toInt()
        }

        val nsp: String
        if (charAt(str, i + 1) == "/") {
            val start = i + 1
            while (true) {
                ++i
                if (charAt(str, i) == ",") break
                if (i == str.length) break
            }
            nsp = str.substring(start, i)
        } else {
            nsp = "/"
        }

        var id: Long? = null
        val next = charAt(str, i + 1)
        if (next != "" && !SocketIOJson.javaScriptNumber(next).isNaN()) {
            val start = i + 1
            while (true) {
                ++i
                val c = charAt(str, i)
                if (SocketIOJson.javaScriptNumber(c).isNaN()) {
                    --i
                    break
                }
                if (i == str.length) break
            }
            val value = SocketIOJson.javaScriptNumber(str.substring(start, minOf(i + 1, str.length)))
            // JavaScript would carry NaN or an imprecise float as the id; neither can be acknowledged
            // reliably, so such ids are rejected (a documented malformed-input difference).
            if (value.isNaN() || value != Math.floor(value) || kotlin.math.abs(value) > MAX_SAFE_INTEGER) {
                throw SocketIOParseException("invalid ack id")
            }
            id = value.toLong()
        }

        var data: SocketIOValue? = null
        if (charAt(str, ++i) != "") {
            val payload =
                try {
                    SocketIOJson.parse(str.substring(i), options.maxNestingDepth, options.reviver)
                } catch (e: SocketIOJsonException) {
                    if (e.message?.startsWith("maximum nesting depth") == true) {
                        throw SocketIOParseException("payload exceeds maxNestingDepth (${options.maxNestingDepth})")
                    }
                    null
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    // `tryParse` also turns a throwing reviver into an invalid payload.
                    null
                }
            if (payload != null && isPayloadValid(type, payload)) {
                data = payload
            } else {
                throw SocketIOParseException("invalid payload")
            }
        }
        return Decoded(type, nsp, id, data, attachments)
    }

    private fun isPayloadValid(
        type: SocketIOPacketType,
        payload: SocketIOValue,
    ): Boolean =
        when (type) {
            SocketIOPacketType.CONNECT -> payload is SocketIOValue.Object
            SocketIOPacketType.DISCONNECT -> false
            SocketIOPacketType.CONNECT_ERROR -> payload is SocketIOValue.Text || payload is SocketIOValue.Object
            SocketIOPacketType.EVENT, SocketIOPacketType.BINARY_EVENT -> SocketIOPacket.isEventPayloadValid(payload)
            SocketIOPacketType.ACK, SocketIOPacketType.BINARY_ACK -> payload is SocketIOValue.Array
        }

    private companion object {
        const val MAX_SAFE_INTEGER = 9007199254740991.0

        /** `String.prototype.charAt`: the empty string outside the bounds. */
        fun charAt(
            str: String,
            index: Int,
        ): String = if (index in str.indices) str[index].toString() else ""
    }
}

internal fun utf8Length(text: String): Long {
    var length = 0L
    var i = 0
    while (i < text.length) {
        val c = text[i]
        length +=
            when {
                c.code < 0x80 -> 1

                c.code < 0x800 -> 2

                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }

                else -> 3
            }
        i++
    }
    return length
}

/**
 * Rebuilds [root] bottom-up without recursion. [replace] is consulted for
 * every node in JavaScript traversal order (arrays by index, objects by key
 * order, parents before children); a non-null result replaces the node and
 * its subtree is not visited.
 */
internal fun rewrite(
    root: SocketIOValue,
    replace: (SocketIOValue) -> SocketIOValue?,
): SocketIOValue {
    replace(root)?.let { return it }
    if (root !is SocketIOValue.Array && root !is SocketIOValue.Object) return root

    class Frame(
        val value: SocketIOValue,
    ) {
        val keys: List<String>? = (value as? SocketIOValue.Object)?.fields?.keys?.toList()
        val children: List<SocketIOValue> =
            when (value) {
                is SocketIOValue.Array -> value.items
                is SocketIOValue.Object -> value.fields.values.toList()
                else -> emptyList()
            }
        var index = 0
        val rebuilt = ArrayList<SocketIOValue>(children.size)
    }
    val stack = ArrayDeque<Frame>()
    stack.addLast(Frame(root))
    var result: SocketIOValue = root
    while (stack.isNotEmpty()) {
        val frame = stack.last()
        if (frame.index < frame.children.size) {
            val child = frame.children[frame.index++]
            val replaced = replace(child)
            when {
                replaced != null -> frame.rebuilt.add(replaced)
                child is SocketIOValue.Array || child is SocketIOValue.Object -> stack.addLast(Frame(child))
                else -> frame.rebuilt.add(child)
            }
            continue
        }
        stack.removeLast()
        val built: SocketIOValue =
            if (frame.keys != null) {
                val map = LinkedHashMap<String, SocketIOValue>(frame.keys.size)
                frame.keys.forEachIndexed { i, key -> map[key] = frame.rebuilt[i] }
                SocketIOValue.Object(map)
            } else {
                SocketIOValue.Array(frame.rebuilt)
            }
        val parent = stack.lastOrNull()
        if (parent == null) result = built else parent.rebuilt.add(built)
    }
    return result
}
