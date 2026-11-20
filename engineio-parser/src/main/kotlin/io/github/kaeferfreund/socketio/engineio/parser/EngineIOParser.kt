package io.github.kaeferfreund.socketio.engineio.parser

/**
 * Engine.IO protocol 4 codec, a port of `engine.io-parser`
 * (`packages/engine.io-parser/lib` at the pinned reference commit).
 *
 * The codec never throws on peer input: anything unreadable decodes to
 * [EngineIOPacket.PARSER_ERROR], as in JavaScript.
 */
public object EngineIOParser {
    /** The Engine.IO protocol revision, `protocol` in `engine.io-parser`. */
    public const val PROTOCOL: Int = 4

    /** ASCII record separator between packets of an HTTP long-polling payload. */
    public const val SEPARATOR: Char = '\u001e'

    /**
     * Encodes one packet for a transport (`encodePacket`).
     *
     * Binary data is returned as-is when [supportsBinary] is `true`; otherwise
     * it becomes `"b"` followed by standard, padded Base64. Text packets become
     * the type code followed by the text.
     */
    public fun encodePacket(
        packet: EngineIOPacket,
        supportsBinary: Boolean,
    ): EngineIOData {
        val data = packet.data
        if (data is EngineIOData.Binary) {
            return if (supportsBinary) data else EngineIOData.Text("b" + Base64Codec.encode(data.unsafeBytes()))
        }
        val code = requireNotNull(packet.type.code) { "the error packet cannot be encoded" }
        val text = (data as EngineIOData.Text?)?.value.orEmpty()
        return EngineIOData.Text(code + text)
    }

    /**
     * Encodes one packet into the bytes of a binary frame
     * (`encodePacketToBinary`): binary data unchanged, text as UTF-8.
     */
    public fun encodePacketToBinary(packet: EngineIOPacket): ByteArray =
        when (val encoded = encodePacket(packet, supportsBinary = true)) {
            is EngineIOData.Binary -> encoded.unsafeBytes().copyOf()
            is EngineIOData.Text -> encoded.value.encodeToByteArray()
        }

    /** Decodes a packet received as text or as a binary frame (`decodePacket`). */
    public fun decodePacket(encoded: EngineIOData): EngineIOPacket =
        when (encoded) {
            is EngineIOData.Text -> decodePacket(encoded.value)
            is EngineIOData.Binary -> EngineIOPacket(EngineIOPacketType.MESSAGE, encoded)
        }

    /** Decodes a binary frame: always a `message` packet with binary data. */
    public fun decodePacket(encoded: ByteArray): EngineIOPacket = EngineIOPacket(EngineIOPacketType.MESSAGE, EngineIOData.Binary(encoded))

    /**
     * Decodes a text-encoded packet.
     *
     * `"b…"` is a Base64 binary message, decoded as leniently as Node's
     * `Buffer.from(…, "base64")`. An unknown type character yields
     * [EngineIOPacket.PARSER_ERROR]. A packet without data after the type
     * character has `data == null`, like the JavaScript `{ type }` object.
     */
    public fun decodePacket(encoded: String): EngineIOPacket {
        if (encoded.isEmpty()) return EngineIOPacket.PARSER_ERROR
        val first = encoded[0]
        if (first == 'b') {
            return EngineIOPacket(EngineIOPacketType.MESSAGE, EngineIOData.Binary.wrap(Base64Codec.decodeLenient(encoded, 1)))
        }
        val type = EngineIOPacketType.fromCode(first) ?: return EngineIOPacket.PARSER_ERROR
        return if (encoded.length > 1) EngineIOPacket(type, EngineIOData.Text(encoded.substring(1))) else EngineIOPacket(type)
    }

    /**
     * Encodes packets into one HTTP long-polling payload (`encodePayload`).
     * Binary data is always Base64-encoded, as the text body requires.
     *
     * Like JavaScript, text is not checked for [SEPARATOR]: a text message
     * containing U+001E is split into two packets by the receiver. Socket.IO
     * payloads never contain it, because JSON escapes control characters; raw
     * Engine.IO text and Socket.IO namespaces built from untrusted input must
     * exclude it.
     */
    public fun encodePayload(packets: List<EngineIOPacket>): String =
        packets.joinToString(SEPARATOR.toString()) { packet ->
            (encodePacket(packet, supportsBinary = false) as EngineIOData.Text).value
        }

    /**
     * Decodes an HTTP long-polling payload (`decodePayload`). Decoding stops
     * after the first error packet, which is included in the result.
     */
    public fun decodePayload(payload: String): List<EngineIOPacket> {
        val packets = ArrayList<EngineIOPacket>()
        var start = 0
        while (true) {
            val end = payload.indexOf(SEPARATOR, start).let { if (it < 0) payload.length else it }
            val packet = decodePacket(payload.substring(start, end))
            packets.add(packet)
            if (packet.type == EngineIOPacketType.ERROR || end == payload.length) break
            start = end + 1
        }
        return packets
    }

    /**
     * Frames packets for a byte stream (`createPacketEncoderStream`, used by
     * WebTransport in JavaScript). Returns the header followed by the payload.
     * The header is one, three or nine bytes; its first bit marks binary data.
     */
    public fun encodeFrame(packet: EngineIOPacket): List<ByteArray> {
        val payload = encodePacketToBinary(packet)
        val length = payload.size
        val header: ByteArray =
            when {
                length < 126 -> byteArrayOf(length.toByte())

                length < 65536 -> byteArrayOf(126.toByte(), (length ushr 8).toByte(), length.toByte())

                else -> {
                    val value = length.toLong()
                    ByteArray(9).also { bytes ->
                        bytes[0] = 127.toByte()
                        for (i in 0 until 8) bytes[8 - i] = (value ushr (8 * i)).toByte()
                    }
                }
            }
        if (packet.data is EngineIOData.Binary) header[0] = (header[0].toInt() or 0x80).toByte()
        return listOf(header, payload)
    }
}

/**
 * Incremental decoder for the stream framing of [EngineIOParser.encodeFrame]
 * (`createPacketDecoderStream`). Feed chunks with [push]; decoded packets are
 * returned in order. After a framing error the decoder yields
 * [EngineIOPacket.PARSER_ERROR] once and then stays failed, since the stream
 * position is lost.
 */
public class EngineIOFrameDecoder(
    private val maxPayload: Long,
) {
    private enum class State { HEADER, LENGTH_16, LENGTH_64, PAYLOAD }

    private val chunks = ArrayDeque<ByteArray>()
    private var offset = 0
    private var available = 0L
    private var state = State.HEADER
    private var expectedLength = -1L
    private var isBinary = false
    private var failed = false

    /** Appends [chunk] and returns every packet that is now complete. */
    // A state machine ported from createPacketDecoderStream; kept in one piece to compare with the source.
    @Suppress("CyclomaticComplexMethod")
    public fun push(chunk: ByteArray): List<EngineIOPacket> {
        if (failed) return emptyList()
        if (chunk.isNotEmpty()) {
            chunks.addLast(chunk.copyOf())
            available += chunk.size
        }
        val output = ArrayList<EngineIOPacket>()
        while (true) {
            when (state) {
                State.HEADER -> {
                    if (available < 1) break
                    val header = take(1)[0].toInt() and 0xff
                    isBinary = header and 0x80 == 0x80
                    expectedLength = (header and 0x7f).toLong()
                    state =
                        when {
                            expectedLength < 126 -> State.PAYLOAD
                            expectedLength == 126L -> State.LENGTH_16
                            else -> State.LENGTH_64
                        }
                }

                State.LENGTH_16 -> {
                    if (available < 2) break
                    val bytes = take(2)
                    expectedLength = ((bytes[0].toLong() and 0xff) shl 8) or (bytes[1].toLong() and 0xff)
                    state = State.PAYLOAD
                }

                State.LENGTH_64 -> {
                    if (available < 8) break
                    val bytes = take(8)
                    var high = 0L
                    for (i in 0 until 4) high = (high shl 8) or (bytes[i].toLong() and 0xff)
                    var low = 0L
                    for (i in 4 until 8) low = (low shl 8) or (bytes[i].toLong() and 0xff)
                    // JavaScript numbers are exact only up to 2^53 - 1.
                    if (high > (1L shl 21) - 1) {
                        output.add(fail())
                        break
                    }
                    expectedLength = high * (1L shl 32) + low
                    state = State.PAYLOAD
                }

                State.PAYLOAD -> {
                    // A frame beyond the largest array cannot be held; JavaScript cannot allocate it either.
                    if (expectedLength > Int.MAX_VALUE) {
                        output.add(fail())
                        break
                    }
                    if (available < expectedLength) break
                    val data = take(expectedLength.toInt())
                    output.add(
                        if (isBinary) {
                            EngineIOParser.decodePacket(data)
                        } else {
                            EngineIOParser.decodePacket(data.decodeToString())
                        },
                    )
                    state = State.HEADER
                }
            }
            if (expectedLength == 0L || expectedLength > maxPayload) {
                output.add(fail())
                break
            }
        }
        return output
    }

    private fun fail(): EngineIOPacket {
        failed = true
        chunks.clear()
        available = 0
        return EngineIOPacket.PARSER_ERROR
    }

    private fun take(count: Int): ByteArray {
        val result = ByteArray(count)
        var written = 0
        while (written < count) {
            val head = chunks.first()
            val n = minOf(count - written, head.size - offset)
            head.copyInto(result, written, offset, offset + n)
            written += n
            offset += n
            if (offset == head.size) {
                chunks.removeFirst()
                offset = 0
            }
        }
        available -= count
        return result
    }
}

/** Base64 with Node-compatible lenient decoding. */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE =
        IntArray(128) { -1 }.also { table ->
            ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
            // Node also accepts the URL-safe alphabet.
            table['-'.code] = 62
            table['_'.code] = 63
        }

    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /**
     * Decodes like `Buffer.from(text, "base64")`: characters outside both
     * alphabets are skipped, decoding stops at the first `=`, and incomplete
     * trailing groups keep every complete byte.
     */
    fun decodeLenient(
        text: String,
        start: Int = 0,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxOf(0, (text.length - start) * 3 / 4))
        var buffer = 0
        var bits = 0
        for (i in start until text.length) {
            val c = text[i]
            if (c == '=') break
            val value = if (c.code < 128) DECODE[c.code] else -1
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
