package io.github.kaeferfreund.socketio.engineio

import kotlin.time.Duration

/**
 * The HTTP requests the long-polling transport needs. The Socket.IO client
 * core has no HTTP stack of its own; the `socketio-okhttp` module provides the
 * implementation, and tests use fakes.
 */
public interface EngineHttpClient {
    /**
     * Starts [request]. Exactly one callback method runs, on any thread,
     * unless the returned handle is cancelled first (then none needs to run).
     */
    public fun execute(
        request: EngineHttpRequest,
        callback: EngineHttpCallback,
    ): Cancellable
}

/** One long-polling request. */
public class EngineHttpRequest(
    /** `GET` or `POST`. */
    public val method: String,
    public val url: String,
    /** Header names and values; a name may repeat. */
    public val headers: List<Pair<String, String>>,
    /** UTF-8 text body of a POST, else `null`. */
    public val body: String?,
    /** Whole-request timeout; `null` means none. */
    public val timeout: Duration?,
    /** Maximum response body bytes to accept; larger bodies fail the request. */
    public val maxResponseBytes: Long = Long.MAX_VALUE,
) {
    /** Method and URL, with query values and user info redacted: they may carry the session id or tokens. */
    override fun toString(): String = "EngineHttpRequest($method ${redactUrl(url)})"
}

/** A completed HTTP exchange. */
public class EngineHttpResponse(
    public val status: Int,
    /** Response body decoded as UTF-8. */
    public val body: String,
    /** Header names (lowercase) and values. */
    public val headers: List<Pair<String, String>> = emptyList(),
) {
    /** All values of header [name], case-insensitively. */
    public fun headerValues(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    override fun toString(): String = "EngineHttpResponse(status=$status, ${body.length} chars)"
}

/** Outcome of an [EngineHttpClient] request. */
public interface EngineHttpCallback {
    /** The server answered, with any status code. */
    public fun onResponse(response: EngineHttpResponse)

    /** No response: connection failure, timeout, TLS failure or an exceeded body limit. */
    public fun onFailure(error: Throwable)
}

/** The WebSocket connections the WebSocket transport needs, provided by `socketio-okhttp`. */
public interface EngineWebSocketClient {
    /** Opens a WebSocket; [listener] methods may run on any thread. */
    public fun connect(
        request: EngineWebSocketRequest,
        listener: EngineWebSocketListener,
    ): EngineWebSocketConnection
}

/** The WebSocket handshake request. */
public class EngineWebSocketRequest(
    /** A `ws://` or `wss://` URL. */
    public val url: String,
    public val headers: List<Pair<String, String>>,
    /** Offered `Sec-WebSocket-Protocol` values. */
    public val protocols: List<String>,
    /**
     * `perMessageDeflate.threshold`: messages below this many bytes are sent
     * uncompressed; `null` disables compression.
     */
    public val compressionThreshold: Int?,
) {
    /** The URL, with query values and user info redacted: they may carry the session id or tokens. */
    override fun toString(): String = "EngineWebSocketRequest(${redactUrl(url)})"
}

private const val REDACTED = "***"

/** [url] for logs: query parameter names stay, their values and any user info are replaced. */
internal fun redactUrl(url: String): String {
    val authorityStart = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
    val at = url.lastIndexOf('@', authorityEnd - 1)
    val withoutUserInfo = if (at >= authorityStart) url.substring(0, authorityStart) + REDACTED + url.substring(at) else url
    val queryStart = withoutUserInfo.indexOf('?')
    if (queryStart < 0) return withoutUserInfo
    val query =
        withoutUserInfo.substring(queryStart + 1).substringBefore('#').split('&').joinToString("&") { parameter ->
            val eq = parameter.indexOf('=')
            if (eq < 0) parameter else parameter.substring(0, eq + 1) + REDACTED
        }
    return withoutUserInfo.substring(0, queryStart + 1) + query
}

/** An open (or opening) WebSocket. Every method may be called from any thread. */
public interface EngineWebSocketConnection {
    /** Queues a text frame; `false` when the socket can no longer send. */
    public fun send(
        text: String,
        compress: Boolean,
    ): Boolean

    /** Queues a binary frame; `false` when the socket can no longer send. */
    public fun send(
        bytes: ByteArray,
        compress: Boolean,
    ): Boolean

    /** Bytes queued but not yet written to the network. */
    public val queuedBytes: Long

    /**
     * The most bytes the connection can hold queued; sending beyond it fails the
     * connection. OkHttp closes a WebSocket at 16 MiB, a limit browsers and Node do
     * not have, so the WebSocket transport paces its writes to stay below it.
     */
    public val maxQueuedBytes: Long get() = Long.MAX_VALUE

    /** Starts the closing handshake. */
    public fun close(
        code: Int,
        reason: String?,
    )

    /** Drops the connection immediately. */
    public fun cancel()
}

/** WebSocket callbacks. */
public interface EngineWebSocketListener {
    /** The handshake succeeded. [responseHeaders] may carry `Set-Cookie`. */
    public fun onOpen(responseHeaders: List<Pair<String, String>>)

    public fun onMessage(text: String)

    public fun onMessage(bytes: ByteArray)

    /** The connection is closed; [code] and [reason] come from the close frame. */
    public fun onClosed(
        code: Int,
        reason: String,
    )

    /**
     * The connection failed. [httpStatus] is the handshake response status
     * when the server answered without upgrading.
     */
    public fun onFailure(
        error: Throwable,
        httpStatus: Int?,
    )
}
