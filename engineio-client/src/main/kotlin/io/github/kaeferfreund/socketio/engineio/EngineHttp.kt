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
     *
     * A client that follows no redirects needs nothing else. One that follows them itself must
     * call [EngineRedirectCookies.followRedirect] of [EngineHttpRequest.redirectCookies] for every
     * hop (and [EngineRedirectCookies.redirectRefused] for a redirect it refuses): the engine
     * stores each redirect response's cookies for the URL that sent it and learns only this way
     * which URL the final response came from. It should not repeat a POST body on another host,
     * nor turn a POST into a GET: the body carries the engine's packets.
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
    public val maxResponseBytes: Long,
    /**
     * The cookies of this request across the redirects the client follows; see
     * [EngineRedirectCookies]. The engine sets it on every polling request. `null` for a request
     * built without one: a client that follows redirects then sends its `Cookie` headers only to
     * the host of [url].
     */
    public val redirectCookies: EngineRedirectCookies?,
) {
    /** A request without [redirectCookies], as before they existed. */
    public constructor(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: String?,
        timeout: Duration?,
        maxResponseBytes: Long = Long.MAX_VALUE,
    ) : this(method, url, headers, body, timeout, maxResponseBytes, null)

    /** Method and URL, with query values and user info redacted and without fragment: they may carry the session id or tokens. */
    override fun toString(): String = "EngineHttpRequest($method ${redactUrl(url)})"
}

/**
 * The cookies of one polling request across the redirects its [EngineHttpClient] follows.
 *
 * The first request carries the engine's `Cookie` header in [EngineHttpRequest.headers]. A client
 * that follows a redirect calls [followRedirect] before it sends the follow-up request, and sends
 * that request with the returned `Cookie` header value instead of the one it carried so far. The
 * engine answers from its cookie jar by RFC 6265 rules (domain, host-only, path, `Secure`,
 * expiry) for the new URL. A `Cookie` entry of `extraHeaders` is the whole `Cookie` header on the
 * host of the first request (any scheme or port) and is never sent to another host. A client
 * that follows no redirects never calls it; one that follows them must, see
 * [EngineHttpClient.execute].
 *
 * Called on the client's thread, one call at a time, before the request's callback runs. The
 * cookies a redirect response sets are stored in the engine's jar during the call, so they are
 * kept when a later hop fails.
 */
public fun interface EngineRedirectCookies {
    /**
     * The redirect response for [responseUrl] carried [setCookieHeaders] (the values of its
     * `Set-Cookie` headers) and the client follows it to [nextUrl]. Stores those cookies for
     * [responseUrl] and returns the `Cookie` header values for the request to [nextUrl]: at most
     * one, and an empty list means the request carries no `Cookie` header.
     */
    public fun followRedirect(
        responseUrl: String,
        setCookieHeaders: List<String>,
        nextUrl: String,
    ): List<String>

    /**
     * The client refuses to follow the redirect response for [responseUrl] and fails the request
     * instead. Stores the cookies of its [setCookieHeaders] for [responseUrl], as a browser does
     * for a redirect it blocks. A refused redirect the client returns as the response needs no
     * call: the engine stores the final response's cookies itself.
     */
    public fun redirectRefused(
        responseUrl: String,
        setCookieHeaders: List<String>,
    ) {
        // Nothing to store by default.
    }
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
    /** The URL, with query values and user info redacted and without fragment: they may carry the session id or tokens. */
    override fun toString(): String = "EngineWebSocketRequest(${redactUrl(url)})"
}

private const val REDACTED = "***"

/**
 * [url] for logs: user info and query values are replaced and the fragment is dropped, since
 * any of them may carry the session id or a token. The names of `name=value` parameters stay; a
 * parameter without `=` is replaced whole, as it may be a bare token. The engine decodes such a
 * parameter of its URI into a name with the value `undefined`, as JavaScript does, and sends it
 * as `name=undefined`, so in its request URLs that name stays visible.
 */
internal fun redactUrl(url: String): String {
    val authorityStart = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
    val at = url.lastIndexOf('@', authorityEnd - 1)
    val withoutUserInfo = if (at >= authorityStart) url.substring(0, authorityStart) + REDACTED + url.substring(at) else url
    val withoutFragment = withoutUserInfo.substringBefore('#')
    val queryStart = withoutFragment.indexOf('?')
    if (queryStart < 0) return withoutFragment
    val query =
        withoutFragment.substring(queryStart + 1).split('&').joinToString("&") { parameter ->
            val eq = parameter.indexOf('=')
            when {
                parameter.isEmpty() -> parameter
                eq < 0 -> REDACTED
                else -> parameter.substring(0, eq + 1) + REDACTED
            }
        }
    return withoutFragment.substring(0, queryStart + 1) + query
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
