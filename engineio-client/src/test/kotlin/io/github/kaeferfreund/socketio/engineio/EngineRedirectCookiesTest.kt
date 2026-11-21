package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The cookies a polling request carries across redirects: the HTTP client asks the request's
 * [EngineRedirectCookies] for every hop, and what redirect responses set reaches the engine's live
 * jar the moment it arrives.
 */
class EngineRedirectCookiesTest {
    private val handshake = """0{"sid":"s","upgrades":[],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}"""

    private class Setup(
        val test: TestScope,
        val http: ScriptedHttpClient,
        val executor: ProtocolExecutor,
        val engine: EngineSocket,
    ) {
        fun settle() = test.runCurrent()

        fun poll(): ScriptedHttpClient.Pending = http.pending("GET").single()
    }

    private fun TestScope.setup(
        uri: String = "http://node1.example.test:8080",
        options: EngineOptions = EngineOptions(transports = listOf("polling"), withCredentials = true),
    ): Setup {
        val http = ScriptedHttpClient()
        val executor = ProtocolExecutor(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), testScheduler.timeSource)
        val engine = EngineSocket(uri, options.copy(clients = EngineClients(http, ScriptedWebSocketClient())), executor)
        executor.execute { engine.open() }
        runCurrent()
        return Setup(this, http, executor, engine)
    }

    private fun ScriptedHttpClient.Pending.redirect(
        to: String,
        setCookies: List<String> = emptyList(),
        from: String = request.url,
    ): List<String> = checkNotNull(request.redirectCookies) { "no redirect cookies" }.followRedirect(from, setCookies, to)

    private fun cookieHeaders(request: EngineHttpRequest) = request.headers.filter { it.first.equals("cookie", true) }.map { it.second }

    // RFC 6265 5.4: one Cookie header. An explicit one from extraHeaders is the whole header on the
    // configured host; the Node jar would replace it with the jar's cookies instead.
    @Test
    fun anExplicitCookieIsTheWholeCookieHeaderOnTheConfiguredHost() =
        runTest {
            val headers = mapOf("Cookie" to "app=1", "cookie" to "app=2")
            val s = setup(options = EngineOptions(transports = listOf("polling"), withCredentials = true, extraHeaders = headers))
            assertEquals(listOf("app=1; app=2"), cookieHeaders(s.poll().request))
            s.poll().respond(EngineHttpResponse(200, handshake, listOf("set-cookie" to "io=s; Path=/")))
            s.settle()
            val poll = s.poll()
            assertEquals(listOf("app=1; app=2"), cookieHeaders(poll.request))
            assertNotNull(poll.request.redirectCookies)
            s.executor.shutdown()
        }

    @Test
    fun aRedirectGetsTheCookiesThatMatchItsTarget() =
        runTest {
            val setCookies =
                listOf(
                    "set-cookie" to "host=1; Path=/",
                    "set-cookie" to "domain=2; Domain=example.test; Path=/",
                    "set-cookie" to "scoped=3; Path=/engine.io",
                )
            val s = setup()
            s.poll().respond(EngineHttpResponse(200, handshake, setCookies))
            s.settle()
            val poll = s.poll()
            // Same host, another port and scheme: every jar cookie, the longer path first.
            assertEquals(listOf("scoped=3; host=1; domain=2"), poll.redirect("http://node1.example.test:9090/engine.io/?x=1"))
            // A sibling host: only the domain cookie.
            assertEquals(listOf("domain=2"), poll.redirect("http://node3.example.test/engine.io/"))
            // A foreign host: nothing.
            assertEquals(emptyList<String>(), poll.redirect("http://evil.test/engine.io/"))
            // Back on the first host, with the path rules for the new path.
            assertEquals(listOf("host=1; domain=2"), poll.redirect("http://node1.example.test/other/"))
            s.executor.shutdown()
            // With an explicit Cookie header, it replaces the jar's cookies on the first host only.
            val explicit = setup(options = EngineOptions(transports = listOf("polling"), withCredentials = true, extraHeaders = mapOf("Cookie" to "app=1")))
            explicit.poll().respond(EngineHttpResponse(200, handshake, setCookies))
            explicit.settle()
            val next = explicit.poll()
            assertEquals(listOf("app=1"), next.redirect("https://node1.example.test:9443/engine.io/"))
            assertEquals(listOf("domain=2"), next.redirect("http://node3.example.test/engine.io/"))
            assertEquals(listOf("app=1"), next.redirect("http://node1.example.test/other/"))
            explicit.executor.shutdown()
        }

    @Test
    fun cookiesSetByARedirectResponseApplyToTheNextHopAndReachTheJar() =
        runTest {
            val s = setup()
            s.poll().respond(EngineHttpResponse(200, handshake))
            s.settle()
            val poll = s.poll()
            val next =
                poll.redirect(
                    "http://node2.example.test/engine.io/",
                    setCookies = listOf("lb=node2; Domain=example.test; Path=/", "mine=1; Path=/"),
                    from = "http://node1.example.test:8080/engine.io/",
                )
            assertEquals(listOf("lb=node2"), next)
            // The final response comes from node2; its host-only cookie belongs to node2.
            poll.respond(EngineHttpResponse(200, "6", listOf("set-cookie" to "n2=1; Path=/")))
            s.settle()
            val later = s.poll()
            assertEquals("lb=node2; mine=1", later.request.header("cookie"))
            assertEquals(listOf("lb=node2; n2=1"), later.redirect("http://node2.example.test/engine.io/"))
            s.executor.shutdown()
        }

    // A long poll can answer long after the redirect: the redirect's cookies are stored in the
    // engine's jar when the redirect arrives, and Max-Age counts from then.
    @Test
    fun aRedirectCookieExpiresCountedFromItsArrival() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        var clock = start
        val jar = EngineCookieJar { clock }
        val cookies = RequestCookies("http://a.example/socket.io/", null, jar)
        assertEquals(listOf("lb=1"), cookies.followRedirect("http://a.example/socket.io/", listOf("lb=1; Max-Age=10; Path=/"), "http://a.example/next/"))
        assertEquals("lb=1", jar.cookieHeader("http://a.example/"))
        clock = start.plusSeconds(9)
        assertEquals("lb=1", jar.cookieHeader("http://a.example/"))
        clock = start.plusSeconds(11)
        assertNull(jar.cookieHeader("http://a.example/"))
    }

    // OkHttp reports `http://xn--bcher-kva.example/...` for an engine URL `http://bücher.example`,
    // keeps a trailing root dot and writes IPv6 compressed; all of them name the first host.
    @Test
    fun aSameHostRedirectKeepsTheCookiesWhateverSpellingTheHostHas() {
        val jar = EngineCookieJar()
        jar.parseCookies(listOf("io=sticky; Path=/"), "http://bücher.example/socket.io/")
        val cookies = RequestCookies("http://bücher.example/socket.io/", null, jar)
        assertEquals(
            listOf("io=sticky"),
            cookies.followRedirect("http://xn--bcher-kva.example/socket.io/", emptyList(), "http://xn--bcher-kva.example./socket.io/?moved=1"),
        )
        val explicit = RequestCookies("http://bücher.example/socket.io/", "app=1", jar)
        assertEquals(listOf("app=1"), explicit.followRedirect("http://bücher.example/socket.io/", emptyList(), "http://XN--BCHER-KVA.example/socket.io/"))
        val spellings =
            listOf(
                "http://[0:0:0:0:0:0:0:1]:3000/" to "http://[::1]:3000/?moved=1",
                "http://127.1:3000/" to "http://127.0.0.1:3000/",
            )
        for ((first, next) in spellings) {
            assertEquals(listOf("app=1"), RequestCookies(first, "app=1", null).followRedirect(first, emptyList(), next), first)
        }
    }

    // A long poll that redirects while a POST of the same engine completes: the poll's follow-up
    // reads the live jar, and its redirect cookies are not replayed over the POST's newer ones
    // when the poll ends.
    @Test
    fun aRedirectedLongPollNeverReplaysCookiesOverNewerOnes() =
        runTest {
            val s = setup()
            s.poll().respond(EngineHttpResponse(200, handshake))
            s.settle()
            val poll = s.poll()
            assertEquals(listOf("x=old; y=1"), poll.redirect("http://node1.example.test:8080/engine.io/?moved=1", listOf("x=old; Path=/", "y=1; Path=/")))
            s.executor.execute { s.engine.send("hello") }
            s.settle()
            s.http.pending("POST").single().respond(
                EngineHttpResponse(
                    200,
                    "ok",
                    listOf(
                        "set-cookie" to "x=new; Path=/",
                        "set-cookie" to "y=; Max-Age=0; Path=/",
                    ),
                ),
            )
            s.settle()
            assertEquals(listOf("x=new"), poll.redirect("http://node1.example.test:8080/engine.io/?moved=2"))
            poll.respond(EngineHttpResponse(200, "6"))
            s.settle()
            assertEquals("x=new", s.poll().request.header("cookie"))
            s.executor.shutdown()
        }

    // A redirect the client refuses still delivered its cookies, as a browser stores them.
    @Test
    fun aRefusedRedirectStillStoresItsCookies() {
        val jar = EngineCookieJar()
        val cookies = RequestCookies("https://a.example/socket.io/", null, jar)
        cookies.redirectRefused("https://a.example/socket.io/", listOf("lb=1; Path=/"))
        assertEquals("lb=1", jar.cookieHeader("https://a.example/socket.io/"))
    }

    @Test
    fun withoutWithCredentialsOnlyTheExplicitHeaderFollowsAndOnlyToTheSameHost() =
        runTest {
            val s = setup(options = EngineOptions(transports = listOf("polling"), extraHeaders = mapOf("cookie" to "app=1")))
            s.poll().respond(EngineHttpResponse(200, handshake, listOf("set-cookie" to "io=s")))
            s.settle()
            val poll = s.poll()
            assertEquals(listOf("app=1"), poll.redirect("https://node1.example.test:9443/engine.io/", setCookies = listOf("x=1")))
            assertEquals(emptyList<String>(), poll.redirect("http://node2.example.test/engine.io/"))
            s.executor.shutdown()
        }
}
