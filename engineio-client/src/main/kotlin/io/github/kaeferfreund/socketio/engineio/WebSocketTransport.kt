package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOParser
import kotlin.time.Duration.Companion.milliseconds

/**
 * WebSocket transport, a port of `BaseWS`/`WS` in `engine.io-client`, on
 * top of an [EngineWebSocketClient].
 *
 * Callbacks from the connection are ignored once this transport closed, so a
 * late frame of a replaced connection can never reach the engine.
 */
public class WebSocketTransport(
    options: TransportOptions,
) : EngineTransport(options) {
    override val name: String get() = NAME

    private val client: EngineWebSocketClient =
        requireNotNull(options.webSocketClient) {
            "The websocket transport needs an EngineWebSocketClient; add the socketio-okhttp module or configure one"
        }

    private var connection: EngineWebSocketConnection? = null
    private val outgoing = ArrayDeque<EngineIOPacket>()
    private var generation = 0

    override fun doOpen() {
        val current = ++generation
        val request =
            EngineWebSocketRequest(
                url = uri(),
                headers = requestHeaders(),
                protocols = options.protocols,
                compressionThreshold = options.perMessageDeflateThreshold,
            )
        val jar = options.cookieJar
        connection =
            try {
                client.connect(
                    request,
                    object : EngineWebSocketListener {
                        private fun deliver(block: () -> Unit) {
                            executor.execute { if (current == generation) block() }
                        }

                        override fun onOpen(responseHeaders: List<Pair<String, String>>) =
                            deliver {
                                jar?.parseCookies(responseHeaders.filter { it.first.equals("set-cookie", true) }.map { it.second }, options.path)
                                onOpen()
                            }

                        override fun onMessage(text: String) = deliver { onData(EngineIOData.Text(text)) }

                        override fun onMessage(bytes: ByteArray) = deliver { onData(EngineIOData.Binary.wrap(bytes)) }

                        override fun onClosed(
                            code: Int,
                            reason: String,
                        ) = deliver { onClose(CloseDetails("websocket connection closed", code, reason)) }

                        override fun onFailure(
                            error: Throwable,
                            httpStatus: Int?,
                        ) = deliver {
                            // A browser reports an error event followed by a close event; the
                            // engine reacts to the error first (transport error).
                            onError("websocket error", statusCode = httpStatus, cause = error)
                            if (readyState != TransportState.CLOSED) {
                                onClose(CloseDetails("websocket connection closed", code = 1006, reason = error.message.orEmpty()))
                            }
                        }
                    },
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                // Deferred: the engine subscribes to this transport only after open() returns.
                executor.post { if (current == generation) onError("websocket error", cause = e) }
                null
            }
    }

    override fun write(packets: List<EngineIOPacket>) {
        writable = false
        outgoing.addAll(packets)
        pump()
    }

    /**
     * Hands queued packets to the connection. JavaScript passes every packet to
     * `ws.send` at once; OkHttp, unlike browsers and Node, closes the socket when its
     * queue would exceed [EngineWebSocketConnection.maxQueuedBytes], so packets that
     * do not fit wait until the queue has drained. `drain` (writable again) follows
     * once the last packet is handed over, on the next tick as in JavaScript.
     */
    private fun pump() {
        val ws = connection ?: return
        while (outgoing.isNotEmpty()) {
            val packet = outgoing.first()
            val data = EngineIOParser.encodePacket(packet, supportsBinary)
            val size = byteSize(data)
            if (size > ws.maxQueuedBytes) {
                outgoing.clear()
                onError(
                    "websocket error",
                    cause = EngineIOException("a message of $size bytes exceeds the WebSocket client's limit of ${ws.maxQueuedBytes} bytes"),
                )
                return
            }
            if (ws.queuedBytes > 0 && ws.queuedBytes + size > ws.maxQueuedBytes) {
                val current = generation
                executor.schedule(QUEUE_RETRY) { if (current == generation) pump() }
                return
            }
            outgoing.removeFirst()
            doWrite(packet, data)
        }
        // nextTick: the drain comes after the caller's synchronous code.
        val current = generation
        executor.post {
            if (current != generation) return@post
            writable = true
            events.emit(TransportEvent.Drain)
        }
    }

    private fun byteSize(data: EngineIOData): Long =
        when (data) {
            is EngineIOData.Text -> EngineUri.utf8Length(data.value)
            is EngineIOData.Binary -> data.size.toLong()
        }

    private fun doWrite(
        packet: EngineIOPacket,
        data: EngineIOData,
    ) {
        val ws = connection ?: return
        var compress = packet.options.compress
        val threshold = options.perMessageDeflateThreshold
        if (threshold != null) {
            if (byteSize(data) < threshold) compress = false
        }
        val accepted =
            when (data) {
                is EngineIOData.Text -> ws.send(data.value, compress)
                is EngineIOData.Binary -> ws.send(data.bytes, compress)
            }
        // JavaScript swallows a send on a closing socket ("websocket closed before onclose event");
        // the close callback that follows reports the failure.
        if (!accepted) options.logger.debug("websocket") { "send refused; waiting for the close event" }
    }

    override fun doClose() {
        generation++
        outgoing.clear()
        // A socket still connecting is aborted, as close() does in browsers and in Node's ws;
        // OkHttp's close() would only queue a close frame behind a handshake that may never end.
        if (readyState == TransportState.OPENING) connection?.cancel() else connection?.close(1000, null)
        connection = null
    }

    override fun uri(): String {
        val schema = if (options.secure) "wss" else "ws"
        if (options.timestampRequests == true) query[options.timestampParam] = EngineUri.randomString()
        if (!supportsBinary) query["b64"] = "1"
        return createUri(schema, query)
    }

    public companion object {
        public const val NAME: String = "websocket"

        /** How often a write waiting for the connection's queue checks it again. */
        private val QUEUE_RETRY = 5.milliseconds

        /** Factory for [EngineOptions.transportFactories]. */
        public val FACTORY: Factory = Factory { WebSocketTransport(it) }
    }
}
