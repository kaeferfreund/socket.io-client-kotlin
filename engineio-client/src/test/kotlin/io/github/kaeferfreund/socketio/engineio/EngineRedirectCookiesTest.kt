package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * The cookies a polling request carries across redirects: the HTTP client asks the request's
 * [EngineRedirectCookies] for every hop, and what redirect responses set reaches the jar.
 */
class EngineRedirectCookiesTest {
    private val handshake = """0{"sid":"s","upgrades":[],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}"""

    private class Setup(
        val test: TestScope,
        val http: ScriptedHttpClient,
        val executor: ProtocolExecutor,
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
        return Setup(this, http, executor)
    }

    private fun ScriptedHttpClient.Pending.redirect(
        to: String,
        setCookies: List<String> = emptyList(),
        from: String = request.url,
    ): List<String> = checkNotNull(request.redirectCookies) { "no redirect cookies" }.followRedirect(from, setCookies, to)

    @Test
    fun theFirstRequestIsUnchangedAndCarriesTheRedirectCookies() =
        runTest {
            val s = setup(options = EngineOptions(transports = listOf("polling"), withCredentials = true, extraHeaders = mapOf("Cookie" to "app=1")))
            s.poll().respond(EngineHttpResponse(200, handshake, listOf("set-cookie" to "io=s; Path=/")))
            s.settle()
            val poll = s.poll()
            assertEquals(listOf("app=1", "io=s"), poll.request.headers.filter { it.first.equals("cookie", true) }.map { it.second })
            assertNotNull(poll.request.redirectCookies)
            s.executor.shutdown()
        }

    @Test
    fun aRedirectGetsTheCookiesThatMatchItsTarget() =
        runTest {
            val s = setup(options = EngineOptions(transports = listOf("polling"), withCredentials = true, extraHeaders = mapOf("Cookie" to "app=1")))
            s.poll().respond(
                EngineHttpResponse(
                    200,
                    handshake,
                    listOf(
                        "set-cookie" to "host=1; Path=/",
                        "set-cookie" to "domain=2; Domain=example.test; Path=/",
                        "set-cookie" to "scoped=3; Path=/engine.io",
                    ),
                ),
            )
            s.settle()
            val poll = s.poll()
            // Same host, another port and scheme: the explicit header and every jar cookie.
            assertEquals(listOf("app=1", "host=1; domain=2; scoped=3"), poll.redirect("http://node1.example.test:9090/engine.io/?x=1"))
            // A sibling host: only the domain cookie.
            assertEquals(listOf("domain=2"), poll.redirect("http://node3.example.test/engine.io/"))
            // A foreign host: nothing.
            assertEquals(emptyList<String>(), poll.redirect("http://evil.test/engine.io/"))
            // Back on the first host, everything again, with the path rules for the new path.
            assertEquals(listOf("app=1", "host=1; domain=2"), poll.redirect("http://node1.example.test/other/"))
            s.executor.shutdown()
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
