package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOParser
import kotlin.time.Duration

/** Base class of engine failures, carrying the JavaScript error message. */
public open class EngineIOException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A transport failure, `TransportError` in `engine.io-client`.
 *
 * [message] is the JavaScript reason (`"xhr poll error"`, `"xhr post error"`,
 * `"websocket error"`, …). [statusCode] is the HTTP status when the server
 * answered with an error, [responseBody] its body.
 */
public class TransportException(
    reason: String,
    /** HTTP status of a failed polling request or WebSocket handshake. */
    public val statusCode: Int? = null,
    /** Body of a failed polling response. */
    public val responseBody: String? = null,
    cause: Throwable? = null,
) : EngineIOException(reason, cause) {
    /** Always `"TransportError"`, like `err.type` in JavaScript. */
    public val type: String get() = "TransportError"

    /** `err.description` in JavaScript: the HTTP status, or the underlying error. */
    public val description: Any? get() = statusCode ?: cause
}

/** The server sent an Engine.IO `error` packet, or a packet could not be decoded. */
public class EngineServerException(
    /** `err.code` in JavaScript: the packet data, `"parser error"` for undecodable input. */
    public val code: String?,
) : EngineIOException("server error")

/** Details of a transport close, `CloseDetails` in `engine.io-client`. */
public class CloseDetails(
    /** For example `"websocket connection closed"` or `"transport closed by the server"`. */
    public val description: String,
    /** WebSocket close code, when a close frame was received. */
    public val code: Int? = null,
    /** WebSocket close reason. */
    public val reason: String? = null,
) {
    override fun toString(): String = "CloseDetails(description=$description, code=$code, reason=$reason)"
}

/** Transport lifecycle, `TransportState` in `engine.io-client`. */
public enum class TransportState { OPENING, OPEN, CLOSED, PAUSING, PAUSED }

/** Events a transport reports to its engine. */
public sealed class TransportEvent {
    public object Open : TransportEvent()

    public class Error(
        public val error: TransportException,
    ) : TransportEvent()

    public class Packet(
        public val packet: EngineIOPacket,
    ) : TransportEvent()

    public class Close(
        public val details: CloseDetails?,
    ) : TransportEvent()

    public object Poll : TransportEvent()

    public object PollComplete : TransportEvent()

    public object Drain : TransportEvent()
}

/** Everything a transport needs to connect, resolved by the engine per transport. */
public class TransportOptions(
    public val hostname: String,
    public val secure: Boolean,
    /** Port as a string, `"80"`/`"443"` when implicit. */
    public val port: String,
    /** Normalized path including the trailing slash rule. */
    public val path: String,
    /** Query parameters; the engine adds `EIO`, `transport` and `sid`. */
    public val query: Map<String, String>,
    public val timestampRequests: Boolean?,
    public val timestampParam: String,
    public val forceBase64: Boolean,
    public val extraHeaders: Map<String, String>,
    public val requestTimeout: Duration?,
    public val withCredentials: Boolean,
    public val protocols: List<String>,
    public val perMessageDeflateThreshold: Int?,
    public val maxPollingResponseBytes: Long,
    public val executor: ProtocolExecutor,
    public val cookieJar: EngineCookieJar?,
    public val httpClient: EngineHttpClient?,
    public val webSocketClient: EngineWebSocketClient?,
    public val logger: SocketLogger,
)

/**
 * A transport, the counterpart of `Transport` in `engine.io-client`.
 *
 * Subclass it to plug in a custom transport through
 * [EngineOptions.transportFactories]. Every method runs on the protocol
 * executor; implementations must hop there (see [TransportOptions.executor])
 * before calling the protected `on…` methods from I/O callbacks.
 */
public abstract class EngineTransport(
    protected val options: TransportOptions,
) {
    /** Creates a transport for one engine connection attempt. */
    public fun interface Factory {
        public fun create(options: TransportOptions): EngineTransport
    }

    /** `"polling"`, `"websocket"` or a custom name. */
    public abstract val name: String

    /** Query parameters sent with every request of this transport. The engine sets `sid` after the handshake. */
    public val query: MutableMap<String, String> = LinkedHashMap(options.query)

    /** Whether [send] may be called now. */
    public var writable: Boolean = false
        protected set

    /** Current lifecycle state. */
    public var readyState: TransportState? = null
        protected set

    /** Whether binary frames are sent as binary (`false` with `forceBase64`). */
    protected open val supportsBinary: Boolean = !options.forceBase64

    /** Events for the owning engine. */
    @InternalSocketIOApi
    public val events: EventEmitter<TransportEvent> = EventEmitter()

    protected val executor: ProtocolExecutor get() = options.executor

    /** Starts connecting. */
    public fun open() {
        readyState = TransportState.OPENING
        doOpen()
    }

    /**
     * Closes the transport if it is opening or open. A transport that is
     * pausing for an upgrade is only marked closed, so it releases what it
     * still has pending.
     */
    public fun close() {
        when (readyState) {
            TransportState.OPENING, TransportState.OPEN -> {
                doClose()
                onClose(null)
            }

            // JavaScript ignores close() here, which leaves the poll a pausing transport waits
            // for running after the engine closed in the middle of an upgrade.
            TransportState.PAUSING -> onClose(null)

            else -> Unit
        }
    }

    /** Sends packets when open; otherwise they are discarded, as in JavaScript. */
    public fun send(packets: List<EngineIOPacket>) {
        if (readyState == TransportState.OPEN) write(packets)
    }

    /** Pauses the transport before an upgrade; only polling needs to. */
    public open fun pause(onPause: () -> Unit) {}

    protected fun onError(
        reason: String,
        statusCode: Int? = null,
        responseBody: String? = null,
        cause: Throwable? = null,
    ) {
        events.emit(TransportEvent.Error(TransportException(reason, statusCode, responseBody, cause)))
    }

    protected open fun onOpen() {
        readyState = TransportState.OPEN
        writable = true
        events.emit(TransportEvent.Open)
    }

    protected open fun onData(data: EngineIOData) {
        onPacket(EngineIOParser.decodePacket(data))
    }

    protected fun onPacket(packet: EngineIOPacket) {
        events.emit(TransportEvent.Packet(packet))
    }

    protected open fun onClose(details: CloseDetails?) {
        readyState = TransportState.CLOSED
        events.emit(TransportEvent.Close(details))
    }

    /** Builds `schema://host[:port]path?query`, `createUri` in JavaScript. */
    protected fun createUri(
        schema: String,
        query: Map<String, String>,
    ): String {
        val host = if (options.hostname.contains(':')) "[${options.hostname}]" else options.hostname
        val portNumber = options.port.toIntOrNull()
        val port =
            if (options.port.isNotEmpty() && ((options.secure && portNumber != 443) || (!options.secure && portNumber != 80))) {
                ":" + options.port
            } else {
                ""
            }
        // Deliberate deviation from JavaScript: a path without leading slash ("evil.example/x",
        // "@evil.example/x") would extend or replace the host, so it is made absolute here.
        val path = if (options.path.isEmpty() || options.path.startsWith('/')) options.path else "/" + options.path
        val encoded = EngineUri.encodeQuery(query)
        return schema + "://" + host + port + path + (if (encoded.isNotEmpty()) "?$encoded" else "")
    }

    /** Header list with the configured extra headers and the cookie jar. */
    protected fun requestHeaders(): MutableList<Pair<String, String>> {
        val headers = options.extraHeaders.entries.mapTo(ArrayList()) { it.key to it.value }
        options.cookieJar?.cookieHeader(options.path)?.let { headers.add("cookie" to it) }
        return headers
    }

    /** The URL of the next request: [createUri] with this transport's scheme and query. */
    public abstract fun uri(): String

    protected abstract fun doOpen()

    protected abstract fun doClose()

    protected abstract fun write(packets: List<EngineIOPacket>)
}
