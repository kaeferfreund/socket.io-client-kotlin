package io.github.kaeferfreund.socketio.okhttp

import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketListener
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** The OkHttp adapter against MockWebServer: what reaches the wire and what comes back. */
class OkHttpEngineClientsTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun execute(
        clients: OkHttpEngineClients,
        request: EngineHttpRequest,
    ): Result<EngineHttpResponse> {
        val future = CompletableFuture<Result<EngineHttpResponse>>()
        clients.execute(
            request,
            object : EngineHttpCallback {
                override fun onResponse(response: EngineHttpResponse) {
                    future.complete(Result.success(response))
                }

                override fun onFailure(error: Throwable) {
                    future.complete(Result.failure(error))
                }
            },
        )
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun get(
        path: String = "/socket.io/?EIO=4",
        maxResponseBytes: Long = Long.MAX_VALUE,
        timeout: kotlin.time.Duration? = null,
    ) = EngineHttpRequest("GET", server.url(path).toString(), listOf("X-Custom" to "1", "Accept" to "*/*"), null, timeout, maxResponseBytes)

    @Test
    fun sendsHeadersAndBodiesAndReturnsTheResponse() {
        val clients = OkHttpEngineClients()
        server.enqueue(MockResponse.Builder().body("0{\"sid\":\"a\"}").addHeader("Set-Cookie", "a=b").build())
        val response = execute(clients, get()).getOrThrow()
        assertEquals(200, response.status)
        assertEquals("0{\"sid\":\"a\"}", response.body)
        assertEquals(listOf("a=b"), response.headerValues("set-cookie"))
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("1", recorded.headers["X-Custom"])

        server.enqueue(MockResponse.Builder().body("ok").build())
        val post =
            EngineHttpRequest("POST", server.url("/socket.io/?EIO=4&sid=a").toString(), listOf("Content-type" to "text/plain;charset=UTF-8"), "4héllo", null)
        assertEquals("ok", execute(clients, post).getOrThrow().body)
        val recordedPost = server.takeRequest()
        assertEquals("POST", recordedPost.method)
        assertEquals("4héllo", recordedPost.body!!.utf8())
        assertEquals("text/plain;charset=UTF-8", recordedPost.headers["Content-Type"])
    }

    @Test
    fun aRequestOkHttpCannotBuildFailsInsteadOfThrowing() {
        val clients = OkHttpEngineClients()
        val badHeader = EngineHttpRequest("GET", server.url("/socket.io/?EIO=4").toString(), listOf("X-Token" to "tést"), null, null)
        assertTrue(execute(clients, badHeader).exceptionOrNull() is java.io.IOException)
        val badUrl = EngineHttpRequest("GET", "http://exa mple.com/socket.io/", emptyList(), null, null)
        assertTrue(execute(clients, badUrl).exceptionOrNull() is java.io.IOException)
        assertEquals(0, server.requestCount)

        val failure = CompletableFuture<Throwable>()
        clients.connect(
            EngineWebSocketRequest(server.url("/socket.io/").toString().replace("http", "ws"), listOf("X-Token" to "tést"), emptyList(), null),
            object : EngineWebSocketListener {
                override fun onOpen(responseHeaders: List<Pair<String, String>>) = Unit

                override fun onMessage(text: String) = Unit

                override fun onMessage(bytes: ByteArray) = Unit

                override fun onClosed(
                    code: Int,
                    reason: String,
                ) = Unit

                override fun onFailure(
                    error: Throwable,
                    httpStatus: Int?,
                ) {
                    failure.complete(error)
                }
            },
        )
        assertTrue(failure.get(5, TimeUnit.SECONDS) is java.io.IOException)
    }

    // Applications log connect errors; a rejected header value or the URL query (tokens, sid)
    // must not end up in the message or in any cause of it.
    @Test
    fun anInvalidRequestReportsNeitherHeaderValuesNorTheQuery() {
        val clients = OkHttpEngineClients()
        val secret = "s3cret-tökén"
        val failures = mutableListOf<Throwable>()
        val badHeader = EngineHttpRequest("GET", server.url("/socket.io/?EIO=4&token=$secret").toString(), listOf("X-Token" to secret), null, null)
        failures += execute(clients, badHeader).exceptionOrNull()!!
        // Without a scheme, OkHttp's message quotes the start of the URL: here the query or fragment.
        val badUrls = listOf("?$secret=1", "#$secret")
        for (badUrl in badUrls) {
            val raw = runCatching { Request.Builder().url(badUrl) }.exceptionOrNull()!!
            assertTrue("s3cr" in raw.message!!, raw.message)
            failures += execute(clients, EngineHttpRequest("GET", badUrl, emptyList(), null, null)).exceptionOrNull()!!
            failures += webSocketFailure(EngineWebSocketRequest(badUrl, emptyList(), emptyList(), null)).first
        }
        val wsFailure = CompletableFuture<Throwable>()
        clients.connect(
            EngineWebSocketRequest(server.url("/socket.io/?token=$secret").toString().replace("http", "ws"), listOf("X-Token" to secret), emptyList(), null),
            object : EngineWebSocketListener {
                override fun onOpen(responseHeaders: List<Pair<String, String>>) = Unit

                override fun onMessage(text: String) = Unit

                override fun onMessage(bytes: ByteArray) = Unit

                override fun onClosed(
                    code: Int,
                    reason: String,
                ) = Unit

                override fun onFailure(
                    error: Throwable,
                    httpStatus: Int?,
                ) {
                    wsFailure.complete(error)
                }
            },
        )
        failures += wsFailure.get(5, TimeUnit.SECONDS)
        for (failure in failures) {
            assertTrue(failure is java.io.IOException)
            val messages = generateSequence(failure) { it.cause }.map { it.toString() }.toList()
            assertTrue(messages.none { "s3cr" in it }, "secret in $messages")
        }
        assertTrue("X-Token" in failures[0].message!!, failures[0].message)
        assertTrue("scheme" in failures[1].message!!, failures[1].message)
        assertEquals(0, server.requestCount)
    }

    // Tokens are often sent as a WebSocket subprotocol; OkHttp's error for a rejected one quotes the value.
    @Test
    fun anInvalidWebSocketProtocolIsNotReported() {
        val secret = "s3cret-tökén"
        val url = server.url("/socket.io/").toString().replace("http", "ws")
        val failure = webSocketFailure(EngineWebSocketRequest(url, emptyList(), listOf("token.$secret"), null))
        assertTrue(failure.first is java.io.IOException)
        val messages = generateSequence(failure.first) { it.cause }.map { it.toString() }.toList()
        assertTrue(messages.none { "s3cret" in it }, "secret in $messages")
        assertTrue("Sec-WebSocket-Protocol" in failure.first.message!!, failure.first.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun webSocketProtocolsReplaceAProtocolHeader() {
        server.enqueue(MockResponse.Builder().code(400).build())
        val request =
            EngineWebSocketRequest(
                server.url("/socket.io/").toString().replace("http", "ws"),
                listOf("Sec-WebSocket-Protocol" to "old"),
                listOf("a", "b"),
                null,
            )
        assertEquals(400, webSocketFailure(request).second)
        assertEquals(listOf("a, b"), server.takeRequest().headers.values("Sec-WebSocket-Protocol"))
    }

    private fun webSocketFailure(
        request: EngineWebSocketRequest,
        clients: OkHttpEngineClients = OkHttpEngineClients(),
    ): Pair<Throwable, Int?> {
        val failure = CompletableFuture<Pair<Throwable, Int?>>()
        clients.connect(
            request,
            object : EngineWebSocketListener {
                override fun onOpen(responseHeaders: List<Pair<String, String>>) = Unit

                override fun onMessage(text: String) = Unit

                override fun onMessage(bytes: ByteArray) = Unit

                override fun onClosed(
                    code: Int,
                    reason: String,
                ) = Unit

                override fun onFailure(
                    error: Throwable,
                    httpStatus: Int?,
                ) {
                    failure.complete(error to httpStatus)
                }
            },
        )
        return failure.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun reportsErrorStatusesAsResponses() {
        server.enqueue(MockResponse.Builder().code(400).body("{\"code\":1,\"message\":\"Session ID unknown\"}").build())
        val response = execute(OkHttpEngineClients(), get()).getOrThrow()
        assertEquals(400, response.status)
        assertEquals("{\"code\":1,\"message\":\"Session ID unknown\"}", response.body)
    }

    @Test
    fun boundsTheResponseBodyWhileReading() {
        val clients = OkHttpEngineClients()
        server.enqueue(MockResponse.Builder().body("x".repeat(100)).build())
        assertTrue(execute(clients, get(maxResponseBytes = 99)).exceptionOrNull() is ResponseTooLargeException)
        // A chunked body has no Content-Length: it is cut off as soon as it crosses the limit.
        server.enqueue(MockResponse.Builder().chunkedBody("y".repeat(50_000), 1000).build())
        assertTrue(execute(clients, get(maxResponseBytes = 10_000)).exceptionOrNull() is ResponseTooLargeException)
        server.enqueue(MockResponse.Builder().body("z".repeat(100)).build())
        assertEquals(100, execute(clients, get(maxResponseBytes = 100)).getOrThrow().body.length)
    }

    @Test
    fun appliesTheRequestTimeout() {
        server.enqueue(MockResponse.Builder().body("late").headersDelay(2, TimeUnit.SECONDS).build())
        val result = execute(OkHttpEngineClients(), get(timeout = 200.milliseconds))
        assertTrue(result.exceptionOrNull() is java.io.IOException, "$result")
    }

    @Test
    fun hasNoReadTimeoutSoLongPollsCanWait() {
        val clients = OkHttpEngineClients()
        assertEquals(0, clients.client.readTimeoutMillis)
        assertTrue(!clients.client.followSslRedirects)
        assertTrue(clients.client.dispatcher.maxRequestsPerHost >= 64)
    }

    /** A client that presents a client certificate (mutual TLS) when a server asks for one. */
    private fun withClientCertificate(base: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient {
        val identity = HandshakeCertificates.Builder().heldCertificate(HeldCertificate.Builder().commonName("client").build()).build()
        return TlsPolicy.systemDefault().withClientCertificate(identity.keyManager).applyTo(base).build()
    }

    // With a client certificate configured; without one, polling follows redirects to other
    // origins without credentials (CrossOriginRedirectTest).
    @Test
    fun followsRedirectsOnlyWithinTheOrigin() {
        val other = MockWebServer()
        other.start()
        try {
            val clients = OkHttpEngineClients(withClientCertificate())
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", server.url("/socket.io/?moved=1").toString()).build())
            server.enqueue(MockResponse.Builder().body("same origin").build())
            assertEquals("same origin", execute(clients, get()).getOrThrow().body)

            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", other.url("/collect").toString()).build())
            val post =
                EngineHttpRequest(
                    "POST",
                    server.url("/socket.io/?EIO=4&sid=a").toString(),
                    listOf("X-Api-Key" to "secret", "cookie" to "session=1"),
                    "40{\"token\":\"secret\"}",
                    null,
                )
            assertTrue(execute(clients, post).exceptionOrNull() is java.net.ProtocolException)
            assertEquals(0, other.requestCount, "the credentials reached another origin")
        } finally {
            other.close()
        }
    }

    // With a client certificate, the origin is checked before the redirect is followed: no DNS
    // lookup, TCP connection or TLS handshake (which could present the certificate) reaches the other origin.
    @Test
    fun aRedirectToAnotherOriginOpensNoConnectionToIt() {
        val other = MockWebServer()
        other.start()
        try {
            val connected = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val base =
                okhttp3.OkHttpClient.Builder()
                    .eventListener(
                        object : okhttp3.EventListener() {
                            override fun connectStart(
                                call: okhttp3.Call,
                                inetSocketAddress: java.net.InetSocketAddress,
                                proxy: java.net.Proxy,
                            ) {
                                connected += inetSocketAddress.port
                            }
                        },
                    ).build()
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/collect").toString()).build())
            assertTrue(execute(OkHttpEngineClients(withClientCertificate(base.newBuilder())), get()).exceptionOrNull() is java.net.ProtocolException)
            // Distinct ports: where "localhost" resolves to both ::1 and 127.0.0.1, OkHttp
            // tries each address of the first origin, so one origin can count twice.
            assertEquals(setOf(server.port), connected.toSet())
        } finally {
            other.close()
        }
    }

    @Test
    fun followsSameOriginRedirectsLikeOkHttp() {
        val clients = OkHttpEngineClients()
        // 303 turns a POST into a GET without body; 307 and 308 repeat it with its body.
        val post = EngineHttpRequest("POST", server.url("/socket.io/?EIO=4&sid=a").toString(), listOf("X-Api-Key" to "k"), "4hi", null)
        server.enqueue(MockResponse.Builder().code(303).addHeader("Location", "/socket.io/?moved=303").build())
        server.enqueue(MockResponse.Builder().body("got").build())
        assertEquals("got", execute(clients, post).getOrThrow().body)
        server.takeRequest()
        val redirected = server.takeRequest()
        assertEquals("GET", redirected.method)
        assertEquals("/socket.io/?moved=303", redirected.target)
        assertEquals("k", redirected.headers["X-Api-Key"])
        assertEquals(0L, redirected.bodySize)
        for (code in listOf(307, 308)) {
            server.enqueue(MockResponse.Builder().code(code).addHeader("Location", "/socket.io/?moved=$code").build())
            server.enqueue(MockResponse.Builder().body("posted").build())
            assertEquals("posted", execute(clients, post).getOrThrow().body)
            server.takeRequest()
            val repeated = server.takeRequest()
            assertEquals("POST", repeated.method)
            assertEquals("4hi", repeated.body!!.utf8())
        }
        // A base client that follows no redirects keeps doing so.
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/socket.io/?moved=1").build())
        val noRedirects = OkHttpEngineClients(okhttp3.OkHttpClient.Builder().followRedirects(false).build())
        assertEquals(302, execute(noRedirects, get()).getOrThrow().status)
        // A redirect loop ends like in OkHttp.
        repeat(21) { server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/socket.io/?loop=$it").build()) }
        assertTrue(execute(clients, get()).exceptionOrNull() is java.net.ProtocolException)
    }

    @Test
    fun theWebSocketHandshakeFollowsNoRedirect() {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/socket.io/").toString()).build())
            val failure = CompletableFuture<Int?>()
            OkHttpEngineClients().connect(
                EngineWebSocketRequest(server.url("/socket.io/").toString().replace("http", "ws"), listOf("X-Api-Key" to "secret"), emptyList(), null),
                object : EngineWebSocketListener {
                    override fun onOpen(responseHeaders: List<Pair<String, String>>) = Unit

                    override fun onMessage(text: String) = Unit

                    override fun onMessage(bytes: ByteArray) = Unit

                    override fun onClosed(
                        code: Int,
                        reason: String,
                    ) = Unit

                    override fun onFailure(
                        error: Throwable,
                        httpStatus: Int?,
                    ) {
                        failure.complete(httpStatus)
                    }
                },
            )
            assertEquals(302, failure.get(5, TimeUnit.SECONDS))
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun neverFollowsARedirectFromHttpsToHttp() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        val plain = MockWebServer()
        plain.start()
        try {
            plain.enqueue(MockResponse.Builder().body("downgraded").build())
            server.useHttps(serverCerts.sslSocketFactory())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", plain.url("/socket.io/").toString()).build())
            val clients =
                OkHttpEngineClients(
                    okhttp3.OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build(),
                )
            val url = server.url("/socket.io/").newBuilder().host("localhost").build().toString()
            val response = execute(clients, EngineHttpRequest("GET", url, emptyList(), null, null)).getOrThrow()
            assertEquals(302, response.status)
            assertEquals(0, plain.requestCount)
        } finally {
            plain.close()
        }
    }

    @Test
    fun exchangesWebSocketFramesAndReportsTheClose() {
        val serverSide = LinkedBlockingQueue<Any>()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("0{\"sid\":\"w\"}")
                            webSocket.send(byteArrayOf(1, 2, 3).toByteString())
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            serverSide.put(text)
                            if (text == "bye") webSocket.close(1000, "done")
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) {
                            serverSide.put(bytes.toByteArray())
                        }
                    },
                ).build(),
        )
        val events = LinkedBlockingQueue<Any>()
        val connection =
            OkHttpEngineClients().connect(
                EngineWebSocketRequest(
                    server.url("/socket.io/?EIO=4&transport=websocket").toString().replace("http", "ws"),
                    listOf("X-Ws" to "1"),
                    emptyList(),
                    1024,
                ),
                object : EngineWebSocketListener {
                    override fun onOpen(responseHeaders: List<Pair<String, String>>) {
                        events.put("open")
                    }

                    override fun onMessage(text: String) {
                        events.put(text)
                    }

                    override fun onMessage(bytes: ByteArray) {
                        events.put(bytes)
                    }

                    override fun onClosed(
                        code: Int,
                        reason: String,
                    ) {
                        events.put("closed:$code:$reason")
                    }

                    override fun onFailure(
                        error: Throwable,
                        httpStatus: Int?,
                    ) {
                        events.put("failure:$httpStatus")
                    }
                },
            )
        assertEquals("open", events.poll(5, TimeUnit.SECONDS))
        assertEquals("0{\"sid\":\"w\"}", events.poll(5, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(1, 2, 3), events.poll(5, TimeUnit.SECONDS) as ByteArray)
        assertTrue(connection.send("4hi", compress = true))
        assertTrue(connection.send(byteArrayOf(9), compress = false))
        assertEquals("4hi", serverSide.poll(5, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(9), serverSide.poll(5, TimeUnit.SECONDS) as ByteArray)
        connection.send("bye", compress = true)
        assertEquals("closed:1000:done", events.poll(5, TimeUnit.SECONDS))
        assertEquals("1", server.takeRequest().headers["X-Ws"])
    }

    @Test
    fun reportsTheHandshakeStatusOfAFailedWebSocket() {
        server.enqueue(MockResponse.Builder().code(400).body("{\"code\":1}").build())
        val failure = CompletableFuture<Int?>()
        OkHttpEngineClients().connect(
            EngineWebSocketRequest(server.url("/socket.io/").toString().replace("http", "ws"), emptyList(), emptyList(), null),
            object : EngineWebSocketListener {
                override fun onOpen(responseHeaders: List<Pair<String, String>>) = Unit

                override fun onMessage(text: String) = Unit

                override fun onMessage(bytes: ByteArray) = Unit

                override fun onClosed(
                    code: Int,
                    reason: String,
                ) = Unit

                override fun onFailure(
                    error: Throwable,
                    httpStatus: Int?,
                ) {
                    failure.complete(httpStatus)
                }
            },
        )
        assertEquals(400, failure.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun offersPerMessageDeflateOnlyWhenCompressionIsEnabled() {
        for (threshold in listOf(1024, null)) {
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
            val opened = CompletableFuture<Unit>()
            val connection =
                OkHttpEngineClients().connect(
                    EngineWebSocketRequest(server.url("/socket.io/").toString().replace("http", "ws"), emptyList(), emptyList(), threshold),
                    object : EngineWebSocketListener {
                        override fun onOpen(responseHeaders: List<Pair<String, String>>) {
                            opened.complete(Unit)
                        }

                        override fun onMessage(text: String) = Unit

                        override fun onMessage(bytes: ByteArray) = Unit

                        override fun onClosed(
                            code: Int,
                            reason: String,
                        ) = Unit

                        override fun onFailure(
                            error: Throwable,
                            httpStatus: Int?,
                        ) {
                            opened.completeExceptionally(error)
                        }
                    },
                )
            opened.get(5, TimeUnit.SECONDS)
            val offered = server.takeRequest().headers["Sec-WebSocket-Extensions"]
            if (threshold == null) assertNull(offered) else assertEquals("permessage-deflate", offered)
            connection.cancel()
        }
    }

    // RFC 6455 4.1: an extension the client did not offer fails the connection. With compression
    // disabled the client offers none, so a compressed message must never be inflated.
    @Test
    fun anUnrequestedPerMessageDeflateResponseFailsTheConnection() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Sec-WebSocket-Extensions", "permessage-deflate")
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("4" + "x".repeat(4096))
                        }
                    },
                ).build(),
        )
        val events = LinkedBlockingQueue<String>()
        OkHttpEngineClients().connect(
            EngineWebSocketRequest(server.url("/socket.io/").toString().replace("http", "ws"), emptyList(), emptyList(), null),
            object : EngineWebSocketListener {
                override fun onOpen(responseHeaders: List<Pair<String, String>>) {
                    events.put("open")
                }

                override fun onMessage(text: String) {
                    events.put("message")
                }

                override fun onMessage(bytes: ByteArray) {
                    events.put("message")
                }

                override fun onClosed(
                    code: Int,
                    reason: String,
                ) {
                    events.put("closed:$code")
                }

                override fun onFailure(
                    error: Throwable,
                    httpStatus: Int?,
                ) {
                    events.put("failure:${error.javaClass.simpleName}")
                }
            },
        )
        assertEquals("failure:ProtocolException", events.poll(5, TimeUnit.SECONDS))
        assertNull(server.takeRequest().headers["Sec-WebSocket-Extensions"])
    }

    // The refused upgrade must release its connection: a 101 response the client abandons
    // otherwise stays allocated in the pool, one leaked socket per refused handshake.
    @Test
    fun aRefusedExtensionReleasesTheConnection() {
        val clients = OkHttpEngineClients(OkHttpClient.Builder().connectionPool(ConnectionPool()).build())
        // OkHttp ends a refused upgrade after reporting it; the dispatcher goes idle once it has.
        val idle = LinkedBlockingQueue<Unit>()
        clients.client.dispatcher.idleCallback = Runnable { idle.put(Unit) }
        val url = server.url("/socket.io/").toString().replace("http", "ws")
        repeat(5) {
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Sec-WebSocket-Extensions", "permessage-deflate")
                    .webSocketUpgrade(object : WebSocketListener() {})
                    .build(),
            )
            val (error, status) = webSocketFailure(EngineWebSocketRequest(url, emptyList(), emptyList(), null), clients)
            assertTrue(error is java.net.ProtocolException, error.toString())
            assertTrue("the server accepted a WebSocket extension that was not offered" in error.message!!, error.message)
            assertNull(status)
            assertEquals(Unit, idle.poll(5, TimeUnit.SECONDS))
        }
        clients.client.connectionPool.evictAll()
        assertEquals(0, clients.client.connectionPool.connectionCount())
    }

    @Test
    fun isDiscoveredThroughTheServiceLoader() {
        val clients = EngineClients.discover()
        assertTrue(clients?.http is OkHttpEngineClients)
        assertTrue(clients?.webSocket is OkHttpEngineClients)
    }

    @Test
    fun aConnectionDroppedBeforeTheResponseIsAFailure() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        assertTrue(execute(OkHttpEngineClients(), get()).isFailure)
    }

    // The documented way to add a proxy, cookie jar or interceptor on the JVM: the base client
    // and every configure block apply to the transports' requests.
    @Test
    fun theOkHttpBuilderAppliesTheBaseClientAndItsCustomizations() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val base =
            okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seen += "base"
                    chain.proceed(chain.request())
                }.build()
        val options =
            io.github.kaeferfreund.socketio.SocketManagerOptions {
                okHttp {
                    client = base
                    configure {
                        addInterceptor { chain ->
                            seen += "configured"
                            chain.proceed(chain.request().newBuilder().header("X-Configured", "yes").build())
                        }
                    }
                }
            }
        server.enqueue(MockResponse.Builder().body("ok").build())
        val future = CompletableFuture<Result<EngineHttpResponse>>()
        options.engine.clients!!.http!!.execute(
            get(),
            object : EngineHttpCallback {
                override fun onResponse(response: EngineHttpResponse) {
                    future.complete(Result.success(response))
                }

                override fun onFailure(error: Throwable) {
                    future.complete(Result.failure(error))
                }
            },
        )
        assertEquals("ok", future.get(10, TimeUnit.SECONDS).getOrThrow().body)
        assertEquals(listOf("base", "configured"), seen)
        assertEquals("yes", server.takeRequest().headers["X-Configured"])
    }
}

/** JS-217, JS-218: the Node `agent` option's counterpart is a caller-supplied OkHttp client. */
class CustomOkHttpClientTest {
    // JS-217, JS-218
    @org.junit.jupiter.api.Test
    fun usesTheCallerSuppliedClientForPollingAndWebSocket() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        try {
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            val agent =
                okhttp3.OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        seen += chain.request().url.encodedPath
                        chain.proceed(chain.request())
                    }.build()
            val clients = OkHttpEngineClients(agent)
            server.enqueue(mockwebserver3.MockResponse.Builder().body("ok").build())
            val done = java.util.concurrent.CompletableFuture<Unit>()
            clients.execute(
                io.github.kaeferfreund.socketio.engineio.EngineHttpRequest("GET", server.url("/polling/").toString(), emptyList(), null, null),
                object : io.github.kaeferfreund.socketio.engineio.EngineHttpCallback {
                    override fun onResponse(response: io.github.kaeferfreund.socketio.engineio.EngineHttpResponse) {
                        done.complete(Unit)
                    }

                    override fun onFailure(error: Throwable) {
                        done.completeExceptionally(error)
                    }
                },
            )
            done.get(5, java.util.concurrent.TimeUnit.SECONDS)
            server.enqueue(mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {}).build())
            val opened = java.util.concurrent.CompletableFuture<Unit>()
            val ws =
                clients.connect(
                    io.github.kaeferfreund.socketio.engineio.EngineWebSocketRequest(
                        server.url("/websocket/").toString().replace("http", "ws"),
                        emptyList(),
                        emptyList(),
                        1024,
                    ),
                    object : io.github.kaeferfreund.socketio.engineio.EngineWebSocketListener {
                        override fun onOpen(responseHeaders: List<Pair<String, String>>) {
                            opened.complete(Unit)
                        }

                        override fun onMessage(text: String) = Unit

                        override fun onMessage(bytes: ByteArray) = Unit

                        override fun onClosed(
                            code: Int,
                            reason: String,
                        ) = Unit

                        override fun onFailure(
                            error: Throwable,
                            httpStatus: Int?,
                        ) {
                            opened.completeExceptionally(error)
                        }
                    },
                )
            opened.get(5, java.util.concurrent.TimeUnit.SECONDS)
            ws.cancel()
            org.junit.jupiter.api.Assertions.assertEquals(listOf("/polling/", "/websocket/"), seen.toList())
        } finally {
            server.close()
        }
    }
}
