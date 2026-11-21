package io.github.kaeferfreund.socketio.okhttp

import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import io.github.kaeferfreund.socketio.engineio.EngineRedirectCookies
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ProtocolException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Polling redirects to other origins: followed without credentials that belong to the first
 * origin, with cookies rebuilt per hop, and refused where following would lose or forward a
 * POST body, downgrade TLS, lose certificate pinning or present a client certificate elsewhere.
 * Every test host resolves to the loopback MockWebServers.
 */
class CrossOriginRedirectTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val servers = ArrayList<MockWebServer>()
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("test CA").build()
    private val leaf =
        HeldCertificate.Builder()
            .signedBy(ca)
            .commonName("node1.example.test")
            .addSubjectAlternativeName("node1.example.test")
            .addSubjectAlternativeName("node2.example.test")
            .addSubjectAlternativeName("evil.test")
            .build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).addTrustedCertificate(ca.certificate).build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()

    /** Connections opened, by port. */
    private val connected = Collections.synchronizedList(ArrayList<Int>())

    @AfterEach
    fun stop() = servers.forEach { it.close() }

    private fun server(
        https: Boolean = false,
        requireClientAuth: Boolean = false,
    ): MockWebServer =
        MockWebServer().also {
            if (https) it.useHttps(serverCertificates.sslSocketFactory())
            if (requireClientAuth) it.requireClientAuth()
            it.start(loopback, 0)
            servers += it
        }

    private fun MockWebServer.at(
        host: String,
        path: String = "/socket.io/?EIO=4&transport=polling",
    ): String = url(path).newBuilder().host(host).build().toString()

    private fun base(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .dns { listOf(loopback) }
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
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
            )

    private fun clients(policy: TlsPolicy? = null): OkHttpEngineClients = OkHttpEngineClients((policy?.applyTo(base()) ?: base()).build())

    private fun redirect(
        code: Int,
        location: String,
        vararg setCookies: String,
    ): MockResponse = MockResponse.Builder().code(code).addHeader("Location", location).apply { setCookies.forEach { addHeader("Set-Cookie", it) } }.build()

    private fun ok(body: String = "ok") = MockResponse.Builder().body(body).build()

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

    private val credentials =
        listOf(
            "X-Api-Key" to "key",
            "Authorization" to "Bearer token",
            "Proxy-Authorization" to "Basic proxy",
            "Cookie" to "app=1",
            "Cookie" to "io=jar",
        )

    /** Records each redirect and answers with [answer] for the next URL. */
    private class RecordingCookies(
        val answer: (String) -> List<String> = { emptyList() },
    ) : EngineRedirectCookies {
        val calls = Collections.synchronizedList(ArrayList<Triple<String, List<String>, String>>())

        override fun followRedirect(
            responseUrl: String,
            setCookieHeaders: List<String>,
            nextUrl: String,
        ): List<String> {
            calls += Triple(responseUrl, setCookieHeaders, nextUrl)
            return answer(nextUrl)
        }
    }

    private fun get(
        url: String,
        headers: List<Pair<String, String>> = credentials,
        cookies: EngineRedirectCookies? = RecordingCookies(),
    ) = EngineHttpRequest("GET", url, headers, null, null, Long.MAX_VALUE, cookies)

    private fun MockWebServer.take(): RecordedRequest = checkNotNull(takeRequest(10, TimeUnit.SECONDS)) { "no request" }

    @Test
    fun aSameOriginRedirectKeepsTheHeadersAndAsksForTheCookies() {
        val a = server()
        val cookies = RecordingCookies { listOf("app=1", "io=jar; moved=1") }
        a.enqueue(redirect(302, "/socket.io/?moved=1", "moved=1"))
        a.enqueue(ok())
        assertEquals("ok", execute(clients(), get(a.at("node1.example.test"), cookies = cookies)).getOrThrow().body)
        a.take()
        val moved = a.take()
        assertEquals("Bearer token", moved.headers["Authorization"])
        assertEquals("Basic proxy", moved.headers["Proxy-Authorization"])
        assertEquals("key", moved.headers["X-Api-Key"])
        assertEquals(listOf("app=1", "io=jar; moved=1"), moved.headers.values("Cookie"))
        assertEquals(
            listOf(Triple(a.at("node1.example.test"), listOf("moved=1"), a.at("node1.example.test", "/socket.io/?moved=1"))),
            cookies.calls.toList(),
        )
    }

    @Test
    fun anotherPortOfTheSameHostKeepsCookiesButNotAuthorization() {
        val a = server()
        val b = server()
        a.enqueue(redirect(307, b.at("node1.example.test")))
        b.enqueue(ok())
        val cookies = RecordingCookies { listOf("app=1", "io=jar") }
        assertEquals("ok", execute(clients(), get(a.at("node1.example.test"), cookies = cookies)).getOrThrow().body)
        val moved = b.take()
        assertNull(moved.headers["Authorization"])
        assertNull(moved.headers["Proxy-Authorization"])
        assertEquals("key", moved.headers["X-Api-Key"])
        assertEquals(listOf("app=1", "io=jar"), moved.headers.values("Cookie"))
    }

    @Test
    fun aForeignHostGetsNoCredentialsButTheOtherHeaders() {
        val a = server()
        val b = server()
        a.enqueue(redirect(302, b.at("evil.test")))
        b.enqueue(ok("foreign"))
        assertEquals("foreign", execute(clients(), get(a.at("node1.example.test"))).getOrThrow().body)
        val moved = b.take()
        assertNull(moved.headers["Authorization"])
        assertNull(moved.headers["Proxy-Authorization"])
        assertEquals(emptyList<String>(), moved.headers.values("Cookie"))
        // Like Swift, curl and browsers: application headers follow redirects. Put tokens in
        // Authorization when they must not.
        assertEquals("key", moved.headers["X-Api-Key"])
    }

    @Test
    fun aSiblingHostGetsWhatTheCookiesAnswerForIt() {
        val a = server()
        val b = server()
        a.enqueue(redirect(302, b.at("node3.example.test"), "lb=b; Domain=example.test; Path=/"))
        b.enqueue(ok())
        val cookies = RecordingCookies { url -> if ("node3" in url) listOf("lb=b") else emptyList() }
        execute(clients(), get(a.at("node1.example.test"), cookies = cookies)).getOrThrow()
        assertEquals(listOf("lb=b"), b.take().headers.values("Cookie"))
        assertEquals(listOf("lb=b; Domain=example.test; Path=/"), cookies.calls.single().second)
    }

    // A request built without redirect cookies (a caller of its own) treats its Cookie headers like
    // an explicit header: sent to the same host on any port or scheme, never to another host.
    @Test
    fun withoutRedirectCookiesTheCookieHeaderFollowsOnlyToTheSameHost() {
        val a = server()
        val b = server()
        a.enqueue(redirect(302, b.at("node1.example.test")))
        b.enqueue(redirect(302, a.at("node2.example.test")))
        a.enqueue(ok())
        execute(clients(), get(a.at("node1.example.test"), cookies = null)).getOrThrow()
        a.take()
        assertEquals(listOf("app=1", "io=jar"), b.take().headers.values("Cookie"))
        assertEquals(emptyList<String>(), a.take().headers.values("Cookie"))
    }

    private fun post(url: String) =
        EngineHttpRequest(
            "POST",
            url,
            listOf("Content-type" to "text/plain;charset=UTF-8", "Authorization" to "Bearer token"),
            "40{\"token\":\"secret\"}",
            null,
            Long.MAX_VALUE,
            RecordingCookies(),
        )

    // The body carries the engine's packets, the CONNECT packet with the auth payload among them:
    // a 307/308 repeats it on the host of the server URL (any port or scheme), never elsewhere.
    @Test
    fun a307Or308RepeatsThePostOnlyOnTheFirstHost() {
        val a = server()
        val b = server()
        val foreign = server()
        for (code in listOf(307, 308)) {
            a.enqueue(redirect(code, b.at("node1.example.test")))
            b.enqueue(ok("posted"))
            assertEquals("posted", execute(clients(), post(a.at("node1.example.test"))).getOrThrow().body)
            val repeated = b.take()
            assertEquals("POST", repeated.method)
            assertEquals("40{\"token\":\"secret\"}", repeated.body!!.utf8())
            assertEquals("text/plain;charset=UTF-8", repeated.headers["Content-Type"])
            assertNull(repeated.headers["Authorization"])
            a.enqueue(redirect(code, foreign.at("node2.example.test")))
            val result = execute(clients(), post(a.at("node1.example.test")))
            assertTrue(result.exceptionOrNull() is ProtocolException, "$code: $result")
        }
        assertEquals(0, foreign.requestCount)
        assertTrue(foreign.port !in connected)
    }

    // OkHttp would continue a POST as a body-less GET: the packets would be lost while the write
    // reports success. The request fails instead, also on the same origin.
    @Test
    fun aPostIsNeverTurnedIntoAGet() {
        val a = server()
        val b = server()
        for (code in listOf(300, 301, 302, 303)) {
            a.enqueue(redirect(code, b.at("node1.example.test")))
            val result = execute(clients(), post(a.at("node1.example.test")))
            assertTrue(result.exceptionOrNull() is ProtocolException, "$code: $result")
            a.enqueue(redirect(code, "/socket.io/?moved=$code"))
            assertTrue(execute(clients(), post(a.at("node1.example.test"))).exceptionOrNull() is ProtocolException, "$code")
        }
        assertEquals(0, b.requestCount)
        assertEquals(8, a.requestCount)
    }

    @Test
    fun aGetFollowsEveryRedirectStatus() {
        val a = server()
        val b = server()
        for (code in listOf(300, 301, 302, 303, 307, 308)) {
            a.enqueue(redirect(code, b.at("node2.example.test")))
            b.enqueue(ok("$code"))
            assertEquals("$code", execute(clients(), get(a.at("node1.example.test"))).getOrThrow().body)
            assertEquals("GET", b.take().method)
        }
    }

    // Each hop starts from the previous one, so what was removed on the way out stays removed.
    @Test
    fun authorizationDoesNotComeBackWhenTheChainReturnsToTheFirstOrigin() {
        val a = server()
        val b = server()
        a.enqueue(redirect(302, b.at("evil.test")))
        b.enqueue(redirect(302, a.at("node1.example.test", "/socket.io/?back=1")))
        a.enqueue(ok())
        execute(clients(), get(a.at("node1.example.test"))).getOrThrow()
        assertEquals("Bearer token", a.take().headers["Authorization"])
        b.take()
        val back = a.take()
        assertNull(back.headers["Authorization"])
        assertNull(back.headers["Proxy-Authorization"])
        assertEquals("key", back.headers["X-Api-Key"])
    }

    // OkHttp writes Host only when the request has none, so one from extraHeaders would name the
    // old authority at the new host.
    @Test
    fun anExplicitHostHeaderStaysWithItsAuthority() {
        val a = server()
        val b = server()
        a.enqueue(redirect(302, "/socket.io/?moved=1"))
        a.enqueue(redirect(302, b.at("node2.example.test")))
        b.enqueue(ok())
        execute(clients(), get(a.at("node1.example.test"), listOf("Host" to "vhost.example.test"))).getOrThrow()
        assertEquals("vhost.example.test", a.take().headers["Host"])
        assertEquals("vhost.example.test", a.take().headers["Host"])
        assertEquals("node2.example.test:${b.port}", b.take().headers["Host"])
    }

    // A callback of the application's own that fails ends the request like a network error,
    // instead of escaping on OkHttp's thread and leaving the redirect response open.
    @Test
    fun aFailingRedirectCallbackFailsTheRequest() {
        val a = server()
        val b = server()
        val throwing = RecordingCookies { error("broken") }
        val invalid = RecordingCookies { listOf("a=1\nb") }
        for (cookies in listOf(throwing, invalid)) {
            a.enqueue(redirect(302, b.at("node2.example.test")))
            val result = execute(clients(), get(a.at("node1.example.test"), cookies = cookies))
            assertTrue(result.exceptionOrNull() is java.io.IOException, result.toString())
        }
        assertEquals(0, b.requestCount)
    }

    // Cancelling the request between hops sends no further hop.
    @Test
    fun cancellingDuringARedirectChainStopsIt() {
        val a = server()
        val b = server()
        val c = server()
        a.enqueue(redirect(302, b.at("node2.example.test")))
        b.enqueue(redirect(302, c.at("node3.example.test")).newBuilder().headersDelay(1, TimeUnit.SECONDS).build())
        val outcome = CompletableFuture<Result<EngineHttpResponse>>()
        val call =
            clients().execute(
                get(a.at("node1.example.test")),
                object : EngineHttpCallback {
                    override fun onResponse(response: EngineHttpResponse) {
                        outcome.complete(Result.success(response))
                    }

                    override fun onFailure(error: Throwable) {
                        outcome.complete(Result.failure(error))
                    }
                },
            )
        b.take()
        call.cancel()
        // OkHttp reports the cancellation once the interceptor chain has ended, so no hop follows.
        val result = outcome.get(10, TimeUnit.SECONDS)
        assertTrue(result.exceptionOrNull() is java.io.IOException, result.toString())
        assertEquals(0, c.requestCount)
        assertTrue(c.port !in connected)
    }

    @Test
    fun httpToHttpsIsFollowed() {
        val a = server()
        val b = server(https = true)
        a.enqueue(redirect(302, b.at("node2.example.test")))
        b.enqueue(ok("secure"))
        assertEquals("secure", execute(clients(), get(a.at("node1.example.test"))).getOrThrow().body)
    }

    // Compared with the current hop and with the first request: http -> https -> http is refused too.
    @Test
    fun httpsToHttpIsNeverFollowed() {
        val a = server()
        val b = server(https = true)
        val c = server()
        b.enqueue(redirect(302, c.at("node2.example.test")))
        assertEquals(302, execute(clients(), get(b.at("node1.example.test"))).getOrThrow().status)
        a.enqueue(redirect(302, b.at("node2.example.test")))
        b.enqueue(redirect(302, c.at("node1.example.test")))
        assertEquals(302, execute(clients(), get(a.at("node1.example.test"))).getOrThrow().status)
        assertEquals(0, c.requestCount)
    }

    @Test
    fun atMostTwentyFollowUpsAreMadeAcrossOrigins() {
        val a = server()
        val b = server()
        repeat(11) {
            a.enqueue(redirect(302, b.at("node2.example.test")))
            b.enqueue(redirect(302, a.at("node1.example.test")))
        }
        val result = execute(clients(), get(a.at("node1.example.test")))
        assertTrue(result.exceptionOrNull() is ProtocolException, result.toString())
        assertEquals(21, a.requestCount + b.requestCount)
    }

    private fun clientKeyManager() =
        HandshakeCertificates.Builder()
            .heldCertificate(HeldCertificate.Builder().signedBy(ca).commonName("client").build(), ca.certificate)
            .build()
            .keyManager

    // The identity is never offered to another origin: the redirect is refused before any DNS
    // lookup, connection or TLS handshake there.
    @Test
    fun withAClientCertificateARedirectToAnotherOriginIsRefused() {
        val a = server(https = true, requireClientAuth = true)
        val b = server(https = true, requireClientAuth = true)
        val clients = clients(TlsPolicy.systemDefault().withClientCertificate(clientKeyManager()))
        a.enqueue(redirect(302, a.at("node1.example.test", "/socket.io/?moved=1")))
        a.enqueue(ok("same origin"))
        assertEquals("same origin", execute(clients, get(a.at("node1.example.test"))).getOrThrow().body)
        for (target in listOf(b.at("node1.example.test"), a.at("node2.example.test"), b.at("evil.test"))) {
            a.enqueue(redirect(302, target))
            val result = execute(clients, get(a.at("node1.example.test")))
            assertTrue(result.exceptionOrNull() is ProtocolException, "$target: $result")
        }
        assertEquals(0, b.requestCount)
        assertEquals(setOf(a.port), connected.toSet())
    }

    @Test
    fun withPinsARedirectToAnUnpinnedHostIsRefused() {
        val a = server(https = true)
        val b = server(https = true)
        val c = server(https = true)
        val pin = CertificatePinner.pin(leaf.certificate)
        for (policy in listOf(TlsPolicy.pinned("node1.example.test", pin).withPins("node2.example.test", pin), TlsPolicy.pinned("*.example.test", pin))) {
            val clients = clients(policy)
            // The same host on another port, and another pinned host (also by a wildcard), keep working.
            a.enqueue(redirect(302, b.at("node1.example.test")))
            b.enqueue(redirect(302, a.at("node2.example.test")))
            a.enqueue(ok("pinned"))
            assertEquals("pinned", execute(clients, get(a.at("node1.example.test"))).getOrThrow().body)
            // Refused before any connection to the unpinned host.
            a.enqueue(redirect(302, c.at("evil.test")))
            val result = execute(clients, get(a.at("node1.example.test")))
            assertTrue(result.exceptionOrNull() is ProtocolException, result.toString())
        }
        assertEquals(0, c.requestCount)
        assertTrue(c.port !in connected)
    }

    // Pins elsewhere in a shared client do not restrict a server they do not cover.
    @Test
    fun pinsForOtherHostsDoNotRestrictAnUnpinnedServer() {
        val a = server()
        val b = server()
        val clients = clients(TlsPolicy.pinned("api.example.test", CertificatePinner.pin(leaf.certificate)))
        a.enqueue(redirect(302, b.at("node2.example.test")))
        b.enqueue(ok("followed"))
        assertEquals("followed", execute(clients, get(a.at("node1.example.test"))).getOrThrow().body)
    }

    // The real engine and its jar behind OkHttp: what each hop of a redirected poll carries.
    @Test
    fun engineCookiesFollowRedirectsByCookieRules() {
        val origin = server()
        val elsewhere = server()
        val otherPort = server()
        val targets =
            java.util.concurrent.LinkedBlockingQueue(
                listOf(elsewhere.at("node3.example.test"), elsewhere.at("evil.test"), otherPort.at("node1.example.test")),
            )
        origin.dispatcher =
            object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.url.queryParameter("sid") == null) {
                        MockResponse.Builder()
                            .body("""0{"sid":"s","upgrades":[],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}""")
                            .addHeader("Set-Cookie", "host=1; Path=/")
                            .addHeader("Set-Cookie", "dom=1; Domain=example.test; Path=/")
                            .build()
                    } else {
                        val target = targets.poll() ?: return MockResponse.Builder().code(400).build()
                        redirect(302, target.substringBefore('?') + "?" + request.url.encodedQuery, "lb=3; Domain=example.test; Path=/")
                    }
            }
        elsewhere.dispatcher =
            object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = ok("6")
            }
        otherPort.enqueue(MockResponse.Builder().code(400).build())
        val executor = io.github.kaeferfreund.socketio.engineio.ProtocolExecutor(kotlinx.coroutines.Dispatchers.Default)
        val options =
            io.github.kaeferfreund.socketio.engineio.EngineOptions(
                transports = listOf("polling"),
                withCredentials = true,
                extraHeaders = mapOf("Cookie" to "app=1", "Authorization" to "Bearer token", "X-Api-Key" to "key"),
                clients = clients().clients,
            )
        val engine = io.github.kaeferfreund.socketio.engineio.EngineSocket("http://node1.example.test:${origin.port}", options, executor)
        executor.execute { engine.open() }
        try {
            val sibling = elsewhere.take()
            val foreign = elsewhere.take()
            val samePort = otherPort.take()
            assertEquals("node3.example.test:${elsewhere.port}", sibling.headers["Host"])
            assertEquals(listOf("dom=1; lb=3"), sibling.headers.values("Cookie"))
            assertEquals("evil.test:${elsewhere.port}", foreign.headers["Host"])
            assertEquals(emptyList<String>(), foreign.headers.values("Cookie"))
            assertEquals(listOf("app=1", "host=1; dom=1; lb=3"), samePort.headers.values("Cookie"))
            for (request in listOf(sibling, foreign, samePort)) {
                assertNull(request.headers["Authorization"], request.toString())
                assertEquals("key", request.headers["X-Api-Key"], request.toString())
            }
        } finally {
            executor.execute { engine.close() }
            executor.post { executor.shutdown() }
        }
    }
}
