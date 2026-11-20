package io.github.kaeferfreund.socketio.okhttp

import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpClient
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import io.github.kaeferfreund.socketio.engineio.EngineRedirectCookies
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketClient
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketConnection
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketListener
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketRequest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
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
 * - polling follows redirects itself instead of OkHttp, with OkHttp's method
 *   rules (301, 302 and 303 continue a POST as a GET without body, 307 and 308
 *   repeat it with its body) and at most 20 follow-ups, also to other origins:
 *   - from HTTPS to HTTP never, compared with the current hop and the first
 *     request; the redirect response is returned instead (`followSslRedirects
 *     = false` for everything else). HTTP to HTTPS is followed;
 *   - when the origin (scheme, host or port) changes, `Authorization` and
 *     `Proxy-Authorization` are removed;
 *   - the `Cookie` header is rebuilt for every hop from
 *     [EngineHttpRequest.redirectCookies] (the engine's jar by RFC 6265 rules,
 *     an explicit `Cookie` from `extraHeaders` only to the first host) after
 *     the `Set-Cookie` headers of the redirect response were stored. A request
 *     without it keeps its `Cookie` headers for the first host only. OkHttp
 *     alone removes `Authorization` but sends an application `Cookie` header to
 *     any host;
 *   - other headers, such as an API key in `extraHeaders`, follow, as in
 *     browsers, curl and the Swift client; send tokens as `Authorization` when
 *     they must stay with the server;
 *   - with a client certificate configured through [TlsPolicy], a redirect to
 *     another origin is refused, and with certificate pins, a redirect to
 *     another host no pin covers. Both are checked before the follow-up is
 *     sent, so no connection (and no TLS handshake presenting the certificate)
 *     is made there;
 * - the WebSocket handshake follows no redirect, like browsers and Node's `ws`;
 * - at least 64 concurrent requests per host, so several managers polling
 *   the same server cannot starve each other's POSTs.
 *
 * Configure TLS, proxies, cookies, DNS or the socket factory on [client]
 * (see [TlsPolicy] and the Android module).
 *
 * OkHttp reads (and, with permessage-deflate, inflates) each incoming WebSocket
 * message completely before [EngineWebSocketListener.onMessage] runs and has no
 * limit for it; [EngineHttpRequest.maxResponseBytes] bounds polling responses only.
 * Without compression ([EngineWebSocketRequest.compressionThreshold] `null`) the
 * extension is not offered, and a handshake response that enables it anyway fails.
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
            .dispatcher(
                client.dispatcher.takeIf { it.maxRequestsPerHost >= 64 }
                    ?: Dispatcher(client.dispatcher.executorService).apply {
                        maxRequests = maxOf(client.dispatcher.maxRequests, 128)
                        maxRequestsPerHost = 64
                    },
            ).build()

    /**
     * The client for polling requests: OkHttp's redirect following is replaced by
     * [PollingRedirects], which decides on a redirect before connecting to its target.
     */
    private val pollingClient: OkHttpClient =
        this.client
            .newBuilder()
            .followRedirects(false)
            .addInterceptor(
                PollingRedirects(
                    followRedirects = this.client.followRedirects,
                    clientCertificate = hasClientCertificate(this.client),
                    pinner = this.client.certificatePinner,
                ),
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
                val builder = Request.Builder().url(url).tag(PollingRequest::class.java, PollingRequest(request.redirectCookies))
                for ((name, value) in request.headers) addHeader(builder, name, value)
                if (request.method == "POST") {
                    builder.post((request.body ?: "").toRequestBody(TEXT_PLAIN))
                } else {
                    builder.get()
                }
                builder.build()
            } catch (e: IllegalArgumentException) {
                // A URL or header value OkHttp rejects (for example a non-ASCII character) fails
                // this request like a network error, on another thread as the contract requires.
                failLater { callback.onFailure(invalidRequest(e, request.url)) }
                return Cancellable.NONE
            }
        val call = pollingClient.newCall(okRequest)
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
            for ((name, value) in request.headers) addHeader(builder, name, value)
            if (request.protocols.isNotEmpty()) addHeader(builder, "Sec-WebSocket-Protocol", request.protocols.joinToString(", "), replace = true)
        } catch (e: IllegalArgumentException) {
            failLater { listener.onFailure(invalidRequest(e, request.url), null) }
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
                        if (NoWebSocketExtensions.refused(response)) {
                            listener.onFailure(NoWebSocketExtensions.failure(), null)
                        } else {
                            listener.onFailure(t, response?.code)
                        }
                    }
                },
            )
        return OkHttpWebSocketConnection(socket)
    }

    /**
     * Adds a header, or sets it when [replace]; OkHttp's error for a rejected value
     * names the header but not the value.
     */
    private fun addHeader(
        builder: Request.Builder,
        name: String,
        value: String,
        replace: Boolean = false,
    ) {
        try {
            if (replace) builder.header(name, value) else builder.addHeader(name, value)
        } catch (e: IllegalArgumentException) {
            throw redacted(e, value)
        }
    }

    /**
     * The failure for a request OkHttp cannot build. Applications log connection errors, so
     * neither the message nor its cause carries the URL's query or fragment (`sid`, tokens).
     */
    private fun invalidRequest(
        e: IllegalArgumentException,
        url: String,
    ): IOException {
        val secrets = listOf(url.substringAfter('?', "").substringBefore('#'), url.substringAfter('#', ""))
        val cause =
            redacted(e) { message ->
                // For a URL without a scheme OkHttp quotes its truncated start ("… no scheme was
                // found for ?toke..."), which may be the query and matches no secret in full.
                val quote = message.indexOf(NO_SCHEME_QUOTE)
                val text = if (quote < 0) message else message.substring(0, quote + NO_SCHEME_QUOTE.length) + REDACTED
                redact(text, secrets)
            }
        return IOException("invalid request: ${cause.message}", cause)
    }

    /** [e] itself, or a copy with the same stack trace whose message has [secrets] replaced. */
    private fun redacted(
        e: IllegalArgumentException,
        vararg secrets: String,
    ): IllegalArgumentException = redacted(e) { redact(it, secrets.asList()) }

    /** [e] itself, or a copy with the same stack trace whose message is [clean]ed. */
    private fun redacted(
        e: IllegalArgumentException,
        clean: (String) -> String,
    ): IllegalArgumentException {
        val message = e.message ?: return e
        val cleaned = clean(message)
        if (cleaned == message) return e
        return IllegalArgumentException(cleaned).also { it.stackTrace = e.stackTrace }
    }

    private fun redact(
        text: String,
        secrets: List<String>,
    ): String = secrets.filter { it.isNotEmpty() }.fold(text) { result, secret -> result.replace(secret, REDACTED) }

    /** Marks a polling request; [cookies] rebuild its `Cookie` header on redirects. */
    private class PollingRequest(
        val cookies: EngineRedirectCookies?,
    )

    /**
     * Follows the redirects of a polling request as OkHttp does (a 307/308 repeats the method
     * and body, other codes continue with a GET; at most [MAX_FOLLOW_UPS]), with the credential
     * rules of the class documentation. Decided here, before the follow-up is sent, a refused
     * redirect causes no DNS lookup, connection or TLS handshake at its target.
     *
     * Deliberate difference from JavaScript, whose Node client follows every redirect with the
     * same headers (browsers apply CORS): credentials stay with the origin they were meant for.
     */
    private class PollingRedirects(
        private val followRedirects: Boolean,
        private val clientCertificate: Boolean,
        private val pinner: CertificatePinner,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val first = chain.request()
            val polling = first.tag(PollingRequest::class.java)
            var response = chain.proceed(first)
            if (polling == null || !followRedirects) return response
            var followUps = 0
            while (true) {
                val target = redirectTarget(response, first.url) ?: return response
                val refusal = refusal(first.url, target)
                if (refusal != null) {
                    response.close()
                    throw refusal
                }
                if (++followUps > MAX_FOLLOW_UPS) {
                    response.close()
                    throw ProtocolException("Too many follow-up requests: $followUps")
                }
                val next = followUp(response, target, first, polling.cookies)
                response.close()
                response = chain.proceed(next)
            }
        }

        /**
         * Where [response] redirects to, or `null` when it is returned as is: not a redirect, no
         * usable `Location`, or a downgrade from HTTPS to HTTP after this hop or the first.
         */
        private fun redirectTarget(
            response: Response,
            firstUrl: HttpUrl,
        ): HttpUrl? {
            if (response.code !in REDIRECT_CODES) return null
            val location = response.header("Location") ?: return null
            val url = response.request.url.resolve(location) ?: return null
            if (!url.isHttps && (response.request.url.isHttps || firstUrl.isHttps)) return null
            return url
        }

        /** Why following to [target] is refused, or `null`. */
        private fun refusal(
            firstUrl: HttpUrl,
            target: HttpUrl,
        ): ProtocolException? {
            // A client certificate goes to the origin it was configured for only. OkHttp's socket
            // factory belongs to the client, not to a host, so the redirect is refused.
            if (clientCertificate && !sameOrigin(firstUrl, target)) {
                return ProtocolException("redirect to another origin refused while a client certificate is configured: ${target.redact()}")
            }
            // Without a pin for the new host, the connection would silently lose pinning.
            if (pinner.pins.isNotEmpty() && target.host != firstUrl.host && pinner.findMatchingPins(target.host).isEmpty()) {
                return ProtocolException("redirect to a host without certificate pins refused: ${target.redact()}")
            }
            return null
        }

        /** `RetryAndFollowUpInterceptor.buildRedirectRequest`, plus the header rules for [target]. */
        private fun followUp(
            response: Response,
            target: HttpUrl,
            first: Request,
            cookies: EngineRedirectCookies?,
        ): Request {
            val request = response.request
            val builder = request.newBuilder().url(target)
            if (request.method != "GET" && request.method != "HEAD") {
                if (response.code == HTTP_TEMP_REDIRECT || response.code == HTTP_PERM_REDIRECT) {
                    builder.method(request.method, request.body)
                } else {
                    builder.method("GET", null).removeHeader("Transfer-Encoding").removeHeader("Content-Length").removeHeader("Content-Type")
                }
            }
            if (!sameOrigin(request.url, target)) builder.removeHeader("Authorization").removeHeader("Proxy-Authorization")
            builder.removeHeader(COOKIE)
            val values =
                when {
                    cookies != null -> cookies.followRedirect(request.url.toString(), response.headers("Set-Cookie"), target.toString())
                    target.host == first.url.host -> first.headers(COOKIE)
                    else -> emptyList()
                }
            for (value in values) builder.addHeader(COOKIE, value)
            return builder.build()
        }

        private fun sameOrigin(
            a: HttpUrl,
            b: HttpUrl,
        ): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port

        private companion object {
            const val HTTP_TEMP_REDIRECT = 307
            const val HTTP_PERM_REDIRECT = 308
            const val COOKIE = "Cookie"

            /** `RetryAndFollowUpInterceptor.MAX_FOLLOW_UPS`. */
            const val MAX_FOLLOW_UPS = 20
            val REDIRECT_CODES = setOf(300, 301, 302, 303, HTTP_TEMP_REDIRECT, HTTP_PERM_REDIRECT)
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
            if (response.code != HTTP_SWITCHING_PROTOCOLS || response.headers(EXTENSIONS).none { it.isNotBlank() }) return response
            // Turn the upgrade into a failed one: OkHttp then fails the WebSocket itself and
            // releases the upgraded connection, which is neither used nor returned to the pool.
            // Throwing here instead would leave the connection allocated.
            return response
                .newBuilder()
                .request(response.request.newBuilder().tag(Refused::class.java, Refused).build())
                .code(HTTP_UPGRADE_REQUIRED)
                .message(MESSAGE)
                .build()
        }

        /** Whether [response] is a handshake this interceptor refused. */
        fun refused(response: Response?): Boolean = response?.request?.tag(Refused::class.java) != null

        /** The failure reported for a refused handshake, without OkHttp's synthetic status. */
        fun failure(): ProtocolException = ProtocolException(MESSAGE)

        /** Tags the request of a refused handshake; a server cannot forge it. */
        private object Refused

        private const val MESSAGE = "the server accepted a WebSocket extension that was not offered"
        private const val EXTENSIONS = "Sec-WebSocket-Extensions"
        private const val HTTP_SWITCHING_PROTOCOLS = 101
        private const val HTTP_UPGRADE_REQUIRED = 426
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

        private const val REDACTED = "<redacted>"

        /** Precedes the quoted start of a URL without a scheme in OkHttp's message. */
        private const val NO_SCHEME_QUOTE = "no scheme was found for "

        /** Whether [client] presents a client certificate configured through [TlsPolicy]. */
        private fun hasClientCertificate(client: OkHttpClient): Boolean =
            try {
                client.sslSocketFactory is ClientCertificateSocketFactory
            } catch (e: IllegalStateException) {
                // A cleartext-only client has no TLS socket factory.
                false
            }

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
