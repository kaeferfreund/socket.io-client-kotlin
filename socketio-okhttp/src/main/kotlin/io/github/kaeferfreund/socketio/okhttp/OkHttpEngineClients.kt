package io.github.kaeferfreund.socketio.okhttp

import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpClient
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketClient
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketConnection
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketListener
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketRequest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit

/** A polling response exceeded [EngineHttpRequest.maxResponseBytes]. */
public class ResponseTooLargeException(
    public val limit: Long,
) : IOException("polling response exceeds the configured limit of $limit bytes")

/**
 * [EngineClients] backed by OkHttp: long-polling requests and WebSockets.
 *
 * The [client] is used as given, with these adjustments the protocol needs
 * (applied to a derived client that shares the connection pool):
 * - no read timeout, since a long poll legitimately waits a heartbeat
 *   interval and the engine's heartbeat detects dead connections;
 * - redirects between HTTPS and HTTP are never followed (`followSslRedirects
 *   = false`), so a redirect cannot downgrade the connection, over any hop;
 * - a polling request follows a redirect only within its origin (scheme, host
 *   and port): OkHttp would otherwise send the extra headers, cookies and a
 *   POST body on to another host. The WebSocket handshake follows no redirect,
 *   like browsers and Node's `ws`;
 * - at least 64 concurrent requests per host, so several managers polling
 *   the same server cannot starve each other's POSTs.
 *
 * Configure TLS, proxies, cookies, DNS or the socket factory on [client]
 * (see [TlsPolicy] and the Android module).
 */
public class OkHttpEngineClients(
    client: OkHttpClient = defaultClient,
) : EngineHttpClient,
    EngineWebSocketClient {
    public val client: OkHttpClient =
        client
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .followSslRedirects(false)
            .addNetworkInterceptor(SameOriginRedirects)
            .dispatcher(
                client.dispatcher.takeIf { it.maxRequestsPerHost >= 64 }
                    ?: Dispatcher(client.dispatcher.executorService).apply {
                        maxRequests = maxOf(client.dispatcher.maxRequests, 128)
                        maxRequestsPerHost = 64
                    },
            ).build()

    /** Both halves as [EngineClients]. */
    public val clients: EngineClients get() = EngineClients(this, this)

    override fun execute(
        request: EngineHttpRequest,
        callback: EngineHttpCallback,
    ): Cancellable {
        val okRequest =
            try {
                val url = request.url.toHttpUrl()
                val builder = Request.Builder().url(url).tag(Origin::class.java, Origin.of(url))
                for ((name, value) in request.headers) builder.addHeader(name, value)
                if (request.method == "POST") {
                    builder.post((request.body ?: "").toRequestBody(TEXT_PLAIN))
                } else {
                    builder.get()
                }
                builder.build()
            } catch (e: IllegalArgumentException) {
                // A URL or header value OkHttp rejects (for example a non-ASCII character) fails
                // this request like a network error, on another thread as the contract requires.
                failLater { callback.onFailure(IOException("invalid request: ${e.message}", e)) }
                return Cancellable.NONE
            }
        val call = client.newCall(okRequest)
        request.timeout?.let { call.timeout().timeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    callback.onFailure(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val result =
                        try {
                            response.use { Result.success(readResponse(it, request.maxResponseBytes)) }
                        } catch (e: IOException) {
                            Result.failure(e)
                        }
                    result.fold(callback::onResponse, callback::onFailure)
                }
            },
        )
        return Cancellable { call.cancel() }
    }

    private fun readResponse(
        response: Response,
        limit: Long,
    ): EngineHttpResponse {
        val body = response.body
        if (limit != Long.MAX_VALUE && body.contentLength() > limit) throw ResponseTooLargeException(limit)
        val source = body.source()
        val buffer = Buffer()
        while (source.read(buffer, 8192) != -1L) {
            if (buffer.size > limit) throw ResponseTooLargeException(limit)
        }
        val headers = response.headers.map { (name, value) -> name.lowercase() to value }
        return EngineHttpResponse(response.code, buffer.readUtf8(), headers)
    }

    override fun connect(
        request: EngineWebSocketRequest,
        listener: EngineWebSocketListener,
    ): EngineWebSocketConnection {
        val builder = Request.Builder()
        try {
            builder.url(request.url)
            for ((name, value) in request.headers) builder.addHeader(name, value)
            if (request.protocols.isNotEmpty()) builder.header("Sec-WebSocket-Protocol", request.protocols.joinToString(", "))
        } catch (e: IllegalArgumentException) {
            failLater { listener.onFailure(IOException("invalid request: ${e.message}", e), null) }
            return ClosedConnection
        }
        val wsBuilder = client.newBuilder().followRedirects(false)
        val threshold = request.compressionThreshold
        if (threshold != null) {
            wsBuilder.minWebSocketMessageToCompress(threshold.toLong())
        } else {
            // perMessageDeflate disabled: do not even offer the extension. OkHttp skips network
            // interceptors for WebSocket upgrades, so an application interceptor removes the header.
            wsBuilder.minWebSocketMessageToCompress(Long.MAX_VALUE)
            wsBuilder.addInterceptor(NoWebSocketExtensions)
        }
        val wsClient = wsBuilder.build()
        val socket =
            wsClient.newWebSocket(
                builder.build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        listener.onOpen(response.headers.map { (name, value) -> name.lowercase() to value })
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) = listener.onMessage(text)

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) = listener.onMessage(bytes.toByteArray())

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        // Complete the closing handshake; onClosed follows.
                        webSocket.close(code.takeIf { it in 1000..4999 && it != 1004 && it != 1005 && it != 1006 } ?: 1000, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) = listener.onClosed(code, reason)

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        response?.close()
                        listener.onFailure(t, response?.code)
                    }
                },
            )
        return OkHttpWebSocketConnection(socket)
    }

    /** Scheme, host and port of the URL a request was made for. */
    private class Origin private constructor(
        val scheme: String,
        val host: String,
        val port: Int,
    ) {
        fun matches(url: HttpUrl): Boolean = url.scheme == scheme && url.host == host && url.port == port

        companion object {
            fun of(url: HttpUrl): Origin = Origin(url.scheme, url.host, url.port)
        }
    }

    /** Fails a redirect to another origin; network interceptors see every hop of a call. */
    private object SameOriginRedirects : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val origin = request.tag(Origin::class.java)
            if (origin != null && !origin.matches(request.url)) {
                throw ProtocolException("redirect to another origin refused: ${request.url.redact()}")
            }
            return chain.proceed(request)
        }
    }

    /**
     * Offers no WebSocket extension and fails a handshake whose response accepts one anyway
     * (RFC 6455, section 4.1): OkHttp would otherwise inflate permessage-deflate messages the
     * client never asked for.
     */
    private object NoWebSocketExtensions : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request().newBuilder().removeHeader(EXTENSIONS).build())
            if (response.code == HTTP_SWITCHING_PROTOCOLS && response.headers(EXTENSIONS).any { it.isNotBlank() }) {
                // Drop the upgraded connection: it must neither be used nor return to the pool.
                chain.call().cancel()
                response.close()
                throw ProtocolException("the server accepted a WebSocket extension that was not offered")
            }
            return response
        }

        private const val EXTENSIONS = "Sec-WebSocket-Extensions"
        private const val HTTP_SWITCHING_PROTOCOLS = 101
    }

    /** Runs [report] on OkHttp's dispatcher threads, never inside the caller's stack. */
    private fun failLater(report: () -> Unit) {
        try {
            client.dispatcher.executorService.execute(report)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // The application shut the dispatcher down; nothing else will run on it.
            Thread(report, "socket.io request failure").start()
        }
    }

    /** The connection handed out when a WebSocket request could not be built. */
    private object ClosedConnection : EngineWebSocketConnection {
        override fun send(
            text: String,
            compress: Boolean,
        ): Boolean = false

        override fun send(
            bytes: ByteArray,
            compress: Boolean,
        ): Boolean = false

        override val queuedBytes: Long get() = 0

        override fun close(
            code: Int,
            reason: String?,
        ) = Unit

        override fun cancel() = Unit
    }

    private class OkHttpWebSocketConnection(
        private val socket: WebSocket,
    ) : EngineWebSocketConnection {
        // OkHttp negotiates permessage-deflate and compresses by size only; the
        // per-message flag cannot be forwarded (see Documentation/Guides/Configuration.md).
        override fun send(
            text: String,
            compress: Boolean,
        ): Boolean = socket.send(text)

        override fun send(
            bytes: ByteArray,
            compress: Boolean,
        ): Boolean = socket.send(bytes.toByteString())

        override val queuedBytes: Long get() = socket.queueSize()

        // OkHttp's RealWebSocket closes the socket (1001) when a send would push its
        // queue past 16 MiB; the transport paces its writes below this.
        override val maxQueuedBytes: Long get() = OKHTTP_MAX_QUEUE_BYTES

        override fun close(
            code: Int,
            reason: String?,
        ) {
            socket.close(code, reason)
        }

        override fun cancel() {
            socket.cancel()
        }
    }

    public companion object {
        private val TEXT_PLAIN = "text/plain;charset=UTF-8".toMediaType()

        /** `RealWebSocket.MAX_QUEUE_SIZE`: OkHttp's limit for queued outgoing WebSocket bytes. */
        private const val OKHTTP_MAX_QUEUE_BYTES = 16L * 1024 * 1024

        /** The client used when none is given: OkHttp defaults plus the adjustments above. */
        public val defaultClient: OkHttpClient by lazy { OkHttpClient() }
    }
}

/** Registers [OkHttpEngineClients] as the default through `ServiceLoader`. */
public class OkHttpEngineClientsProvider : EngineClients.Provider {
    override fun create(): EngineClients = OkHttpEngineClients().clients
}
