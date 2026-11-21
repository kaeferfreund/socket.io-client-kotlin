package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A hostile or stalling server cannot multiply connections, keep a closed
 * engine alive or grow its buffers without bound.
 */
class EngineLifecycleHardeningTest {
    @BeforeEach
    fun resetPriorWebsocketSuccess() {
        EngineSocket.priorWebsocketSuccess.set(false)
    }

    private fun handshake(
        upgrades: List<String> = listOf("websocket"),
        sid: String = "s",
        pingInterval: Int = 25000,
        pingTimeout: Int = 20000,
    ): String {
        val list = upgrades.joinToString(",") { "\"$it\"" }
        return "0{\"sid\":\"$sid\",\"upgrades\":[$list],\"pingInterval\":$pingInterval,\"pingTimeout\":$pingTimeout,\"maxPayload\":1000000}"
    }

    private class Setup(
        val test: TestScope,
        val http: ScriptedHttpClient,
        val ws: ScriptedWebSocketClient,
        val executor: ProtocolExecutor,
        val engine: EngineSocket,
    ) {
        val closeReasons = ArrayList<String>()

        fun settle() = test.runCurrent()

        fun run(block: () -> Unit) {
            executor.execute(block)
            settle()
        }
    }

    private fun TestScope.setup(options: EngineOptions = EngineOptions()): Setup {
        val http = ScriptedHttpClient()
        val ws = ScriptedWebSocketClient()
        val executor = ProtocolExecutor(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), testScheduler.timeSource)
        val engine = EngineSocket("http://localhost", options.copy(clients = EngineClients(http, ws)), executor)
        val setup = Setup(this, http, ws, executor, engine)
        engine.events.on(EngineEvent.Close::class.java) { setup.closeReasons += it.reason }
        setup.run { engine.open() }
        return setup
    }

    // ---- Finding: upgrade probe fan-out ------------------------------------------------------

    @Test
    fun duplicateUpgradeOffersStartOneProbe() =
        runTest {
            val s = setup()
            s.http.answerPoll(handshake(upgrades = List(500) { "websocket" }))
            s.settle()
            assertEquals(1, s.ws.connections.size)
            s.executor.shutdown()
        }

    @Test
    fun theCurrentTransportIsNotProbedAgain() =
        runTest {
            val s = setup(EngineOptions(transports = listOf("polling")))
            s.http.answerPoll(handshake(upgrades = listOf("polling", "polling")))
            s.settle()
            // Only the regular long poll after the handshake, no probe polling transport.
            assertEquals(1, s.http.pending("GET").size)
            assertEquals(2, s.http.requests.count { it.request.method == "GET" })
            s.executor.shutdown()
        }

    @Test
    fun anOpenPacketAfterTheHandshakeIsIgnored() =
        runTest {
            val s = setup()
            s.http.answerPoll(handshake())
            s.settle()
            assertEquals(1, s.ws.connections.size)
            s.http.answerPoll(handshake(sid = "other") + "\u001e" + handshake(sid = "third"))
            s.settle()
            assertEquals(1, s.ws.connections.size)
            assertEquals("s", s.engine.id)
            assertEquals(EngineState.OPEN, s.engine.readyState)
            s.executor.shutdown()
        }

    @Test
    fun aHandshakeRepeatedInOnePayloadIsHandledOnce() =
        runTest {
            val s = setup()
            s.http.answerPoll(handshake() + "\u001e" + handshake(sid = "other"))
            s.settle()
            assertEquals(1, s.ws.connections.size)
            assertEquals("s", s.engine.id)
            s.executor.shutdown()
        }

    @Test
    fun aHandshakeAfterCloseDoesNotReopenTheEngine() =
        runTest {
            val s = setup()
            val opened = ArrayList<EngineEvent>()
            s.engine.events.on(EngineEvent.Open::class.java) { opened += it }
            s.run { s.engine.send("early") }
            s.run { s.engine.close() }
            assertEquals(EngineState.CLOSING, s.engine.readyState)
            s.http.answerPoll(handshake())
            s.settle()
            assertEquals(EngineState.CLOSING, s.engine.readyState)
            assertTrue(opened.isEmpty(), "a closed engine reported open")
            assertTrue(s.ws.connections.isEmpty(), "a closed engine probed an upgrade")
            // The packets buffered before close() are still sent, then the engine closes.
            val post = s.http.pending("POST").single()
            assertEquals("4early", post.request.body)
            post.respond(EngineHttpResponse(200, "ok"))
            s.settle()
            assertEquals(EngineState.CLOSED, s.engine.readyState)
            assertEquals(listOf("forced close"), s.closeReasons)
            s.executor.shutdown()
        }

    @Test
    fun aHandshakeAfterCloseCannotKeepAStalledEngineAlive() =
        runTest {
            val s = setup()
            s.run { s.engine.send("early") }
            s.run { s.engine.close() }
            s.http.answerPoll(handshake(pingInterval = 1000, pingTimeout = 500))
            s.settle()
            // The server pings every 100 ms but never answers the POST.
            repeat(40) {
                s.http.pending("GET").singleOrNull()?.respond(EngineHttpResponse(200, "2"))
                advanceTimeBy(100.milliseconds)
            }
            assertEquals(EngineState.CLOSED, s.engine.readyState)
            assertEquals(listOf("ping timeout"), s.closeReasons)
            s.executor.shutdown()
        }

    // ---- Finding: polling payload expansion ----------------------------------------------------

    @Test
    fun aPollingPayloadIsDispatchedInOrderUpToAnUndecodablePacket() =
        runTest {
            val s = setup(EngineOptions(upgrade = false))
            val messages = ArrayList<String>()
            s.engine.events.on(EngineEvent.Message::class.java) { messages += (it.data as EngineIOData.Text).value }
            s.http.answerPoll(handshake(upgrades = emptyList()) + "\u001e4a\u001ex\u001e4b")
            s.settle()
            assertEquals(listOf("a"), messages)
            assertEquals(listOf("transport error"), s.closeReasons)
            s.executor.shutdown()
        }

    @Test
    fun aClosePacketInsideAPollingPayloadClosesAfterTheEarlierPackets() =
        runTest {
            val s = setup(EngineOptions(upgrade = false))
            val messages = ArrayList<String>()
            s.engine.events.on(EngineEvent.Message::class.java) { messages += (it.data as EngineIOData.Text).value }
            s.http.answerPoll(handshake(upgrades = emptyList()) + "\u001e4a\u001e1\u001e4b")
            s.settle()
            assertEquals(listOf("a"), messages)
            assertEquals(listOf("transport close"), s.closeReasons)
            s.executor.shutdown()
        }

    // ---- Finding: closed engines keep polling --------------------------------------------------

    @Test
    fun aClosingEngineWithAStalledPostStopsAtTheHeartbeatDeadline() =
        runTest {
            val s = setup(EngineOptions(upgrade = false))
            s.http.answerPoll(handshake(upgrades = emptyList(), pingInterval = 1000, pingTimeout = 500))
            s.settle()
            s.run { s.engine.send("stalled") }
            val post = s.http.pending("POST").single()
            s.run { s.engine.close() }
            assertEquals(EngineState.CLOSING, s.engine.readyState)
            // The server pings every 100 ms but never answers the POST.
            repeat(40) {
                s.http.pending("GET").singleOrNull()?.respond(EngineHttpResponse(200, "2"))
                advanceTimeBy(100.milliseconds)
            }
            assertEquals(EngineState.CLOSED, s.engine.readyState)
            assertEquals(listOf("ping timeout"), s.closeReasons)
            assertTrue(post.cancelled, "the stalled POST was not cancelled")
            val unanswered = s.http.requests.filter { !it.answered && it.request.body != "1" }
            assertTrue(unanswered.isNotEmpty() && unanswered.all { it.cancelled }, "a request outlived the engine")
            val requestsAfterClose = s.http.requests.size
            advanceTimeBy(10.seconds)
            assertEquals(requestsAfterClose, s.http.requests.size)
            s.executor.shutdown()
        }

    @Test
    fun closingTheTransportCancelsPendingPostsButNotTheClosePacket() =
        runTest {
            val s = setup(EngineOptions(upgrade = false))
            s.http.answerPoll(handshake(upgrades = emptyList()))
            s.settle()
            s.run { s.engine.send("in flight") }
            val post = s.http.pending("POST").single()
            s.run { s.engine.onNetworkLost() }
            assertTrue(post.cancelled, "the pending POST outlived its transport")
            val close = s.http.requests.last { it.request.method == "POST" }
            assertEquals("1", close.request.body)
            assertFalse(close.cancelled)
            // A close packet is still sent after the transport closed, but not forever.
            assertEquals(10.seconds, close.request.timeout)
            s.executor.shutdown()
        }

    @Test
    fun theClosePacketUsesTheConfiguredRequestTimeout() =
        runTest {
            val s = setup(EngineOptions(upgrade = false, requestTimeout = 3.seconds))
            s.http.answerPoll(handshake(upgrades = emptyList()))
            s.settle()
            s.run { s.engine.close() }
            val close = s.http.requests.last { it.request.method == "POST" }
            assertEquals("1", close.request.body)
            assertEquals(3.seconds, close.request.timeout)
            s.executor.shutdown()
        }

    @Test
    fun anEngineClosedDuringTheUpgradePauseCancelsThePendingPoll() =
        runTest {
            val s = setup()
            s.http.answerPoll(handshake())
            s.settle()
            val poll = s.http.pending("GET").single()
            val probe = s.ws.connections.single()
            probe.listener.onOpen(emptyList())
            s.settle()
            assertEquals(listOf("2probe"), probe.sent)
            probe.listener.onMessage("3probe")
            s.settle()
            // The polling transport now waits for its pending poll before pausing.
            assertTrue(s.engine.upgrading)
            assertEquals(TransportState.PAUSING, s.engine.transport!!.readyState)
            s.run { s.engine.onNetworkLost() }
            assertEquals(EngineState.CLOSED, s.engine.readyState)
            assertTrue(poll.cancelled, "the poll of the pausing transport outlived the engine")
            s.executor.shutdown()
        }

    @Test
    fun aProbeThatFailsWhileThePollingTransportPausesLeavesPollingUsable() =
        runTest {
            val s = setup()
            s.http.answerPoll(handshake())
            s.settle()
            s.run { s.engine.send("before") }
            val write = s.http.pending("POST").single()
            val poll = s.http.pending("GET").single()
            val probe = s.ws.connections.single()
            probe.listener.onOpen(emptyList())
            s.settle()
            probe.listener.onMessage("3probe")
            s.settle()
            assertTrue(s.engine.upgrading)
            assertEquals(TransportState.PAUSING, s.engine.transport!!.readyState)
            // The server drops the probe right after answering it.
            probe.listener.onClosed(1000, "")
            s.settle()
            assertFalse(s.engine.upgrading, "a failed probe left the engine upgrading")
            val polling = s.engine.transport!!
            assertEquals(PollingTransport.NAME, polling.name)
            assertEquals(TransportState.OPEN, polling.readyState)
            // The poll and the write that were pending complete, and polling goes on.
            poll.respond(EngineHttpResponse(200, "4hello"))
            s.settle()
            assertEquals(1, s.http.pending("GET").size, "polling stopped after the failed probe")
            write.respond(EngineHttpResponse(200, "ok"))
            s.settle()
            s.run { s.engine.send("after") }
            assertEquals("4after", s.http.pending("POST").single().request.body)
            s.http.pending("POST").single().respond(EngineHttpResponse(200, "ok"))
            s.settle()
            // close() no longer waits for an upgrade that already failed.
            s.run { s.engine.close() }
            assertEquals(EngineState.CLOSED, s.engine.readyState)
            assertEquals(listOf("forced close"), s.closeReasons)
            s.executor.shutdown()
        }

    @Test
    fun aProbeThatFailsAfterThePausingPollCompletedResumesPolling() =
        runTest {
            val s = setup()
            s.http.answerPoll(handshake())
            s.settle()
            s.run { s.engine.send("before") }
            val write = s.http.pending("POST").single()
            val probe = s.ws.connections.single()
            probe.listener.onOpen(emptyList())
            s.settle()
            probe.listener.onMessage("3probe")
            s.settle()
            // The poll completes; the transport still waits for the write before pausing.
            s.http.answerPoll("6")
            s.settle()
            assertTrue(s.http.pending("GET").isEmpty())
            probe.listener.onFailure(IllegalStateException("reset"), null)
            s.settle()
            assertFalse(s.engine.upgrading)
            assertEquals(TransportState.OPEN, s.engine.transport!!.readyState)
            assertEquals(1, s.http.pending("GET").size, "polling did not resume after the failed probe")
            write.respond(EngineHttpResponse(200, "ok"))
            s.settle()
            assertEquals(TransportState.OPEN, s.engine.transport!!.readyState)
            assertEquals(EngineState.OPEN, s.engine.readyState)
            s.executor.shutdown()
        }

    // ---- Finding: heartbeat replies accumulate -------------------------------------------------

    @Test
    fun aStalledWriteKeepsAtMostOneUnsentPong() =
        runTest {
            val s = setup(EngineOptions(upgrade = false))
            s.http.answerPoll(handshake(upgrades = emptyList()))
            s.settle()
            s.run { s.engine.send("stalled") }
            val post = s.http.pending("POST").single()
            repeat(50) {
                s.http.answerPoll("2")
                s.settle()
            }
            assertEquals(1, s.engine.writeBuffer.count { it.type == EngineIOPacketType.PONG })
            assertEquals(EngineState.OPEN, s.engine.readyState)
            // Once the write completes, the pong goes out.
            post.respond(EngineHttpResponse(200, "ok"))
            s.settle()
            assertEquals("3", s.http.requests.last { it.request.method == "POST" }.request.body)
            s.executor.shutdown()
        }

    // ---- Finding: cookies -----------------------------------------------------------------------

    @Test
    fun aPathScopedCookieDoesNotReachAWebSocketOnAnotherPath() =
        runTest {
            val s =
                setup(
                    EngineOptions(
                        path = "/socket.io",
                        withCredentials = true,
                        transportOptions = mapOf("websocket" to TransportOverrides(path = "/other")),
                    ),
                )
            s.http.answerPoll(
                handshake(),
                headers = listOf("set-cookie" to "scoped=1; Path=/socket.io", "set-cookie" to "everywhere=2; Path=/", "set-cookie" to "implicit=3"),
            )
            s.settle()
            // RFC 6265 5.4: longer paths first (`/socket.io`), then in creation order.
            assertEquals("scoped=1; implicit=3; everywhere=2", s.http.pending("GET").single().request.header("cookie"))
            assertEquals("everywhere=2", s.ws.connections.single().request.headers.firstOrNull { it.first == "cookie" }?.second)
            s.executor.shutdown()
        }

    // RFC 6265 without the Node jar's habit: a cookie whose Path does not match is not sent back,
    // also not to the URL that set it.
    @Test
    fun aCookieIsNotReplayedOnThePathThatSetItWhenItsPathDoesNotMatch() =
        runTest {
            val s = setup(EngineOptions(path = "/socket.io", withCredentials = true))
            s.http.answerPoll(handshake(), headers = listOf("set-cookie" to "elsewhere=1; Path=/admin", "set-cookie" to "sticky=2"))
            s.settle()
            assertEquals("sticky=2", s.http.pending("GET").single().request.header("cookie"))
            assertEquals("sticky=2", s.ws.connections.single().request.headers.firstOrNull { it.first == "cookie" }?.second)
            s.executor.shutdown()
        }

    private fun Setup.webSocketCookie(): String? = ws.connections.single().request.headers.firstOrNull { it.first == "cookie" }?.second

    @Test
    fun aRelativeTransportPathScopesCookiesLikeTheAbsolutePathItRequests() =
        runTest {
            // "socket.io/" is requested as "/socket.io/", the path polling uses.
            val s =
                setup(
                    EngineOptions(
                        path = "/socket.io",
                        withCredentials = true,
                        transportOptions = mapOf("websocket" to TransportOverrides(path = "socket.io")),
                    ),
                )
            s.http.answerPoll(handshake(), headers = listOf("set-cookie" to "elsewhere=1; Path=/admin", "set-cookie" to "sticky=2"))
            s.settle()
            // `sticky` defaults to `/socket.io`, which the WebSocket's `/socket.io/` matches.
            assertEquals("sticky=2", s.webSocketCookie())
            s.executor.shutdown()
        }

    @Test
    fun aRootCookieFromARelativePathReachesAnotherRelativePath() =
        runTest {
            val s =
                setup(
                    EngineOptions(
                        path = "socket.io",
                        withCredentials = true,
                        transportOptions = mapOf("websocket" to TransportOverrides(path = "ws")),
                    ),
                )
            s.http.answerPoll(handshake(), headers = listOf("set-cookie" to "root=1; Path=/", "set-cookie" to "scoped=2; Path=/socket.io"))
            s.settle()
            assertEquals("root=1", s.webSocketCookie())
            s.executor.shutdown()
        }

    @Test
    fun aCookieWithoutPathFromARelativePathDefaultsToThatPath() =
        runTest {
            val s =
                setup(
                    EngineOptions(
                        path = "a/b",
                        withCredentials = true,
                        transportOptions = mapOf("websocket" to TransportOverrides(path = "/other")),
                    ),
                )
            s.http.answerPoll(handshake(), headers = listOf("set-cookie" to "implicit=1", "set-cookie" to "root=2; Path=/"))
            s.settle()
            // The default path is "/a/b", not "/".
            assertEquals("root=2", s.webSocketCookie())
            assertEquals("implicit=1; root=2", s.http.pending("GET").single().request.header("cookie"))
            s.executor.shutdown()
        }

    @Test
    fun cookiePathsMatchLikeInABrowser() {
        val jar = EngineCookieJar()
        jar.parseCookies(listOf("explicit=1; Path=/a", "implicit=2", "invalid=3; Path=x", "root=4; path=/"), "http://h.example/a/")
        assertEquals("explicit=1; implicit=2; invalid=3; root=4", jar.cookieHeader("http://h.example/a/"))
        assertEquals("explicit=1; implicit=2; invalid=3; root=4", jar.cookieHeader("http://h.example/a/b/"))
        assertEquals("explicit=1; implicit=2; invalid=3; root=4", jar.cookieHeader("http://h.example/a"))
        assertEquals("root=4", jar.cookieHeader("http://h.example/ab/"))
        assertEquals("root=4", jar.cookieHeader("http://h.example/"))
        // The public API is not scoped, as in JavaScript.
        assertEquals("explicit=1; implicit=2; invalid=3; root=4", jar.cookieHeader())
        jar.parseCookies(listOf("unscoped=5"))
        assertEquals("root=4; unscoped=5", jar.cookieHeader("http://h.example/elsewhere/"))
    }

    @Test
    fun aPathAttributeIsFoundWithoutASpaceAfterTheSemicolon() {
        val jar = EngineCookieJar()
        jar.parseCookies(listOf("tight=1; HttpOnly;Path=/a/sub", "spaced=2; Secure ;  Path=/a/sub"), "https://h.example/a/")
        assertEquals(null, jar.cookieHeader("https://h.example/a/"))
        assertEquals("tight=1; spaced=2", jar.cookieHeader("https://h.example/a/sub/"))
        // Without the attribute, the default path "/a" would match here.
        assertEquals(null, jar.cookieHeader("https://h.example/a/other/"))
    }

    @Test
    fun theCookieJarKeepsAtMostABrowserSizedNumberOfCookies() {
        val jar = EngineCookieJar()
        jar.parseCookies(List(1000) { "c$it=$it" })
        val names = jar.current.map { it.name }
        assertEquals(CookieLimits.MAX_COOKIES_PER_DOMAIN, names.size)
        // The oldest cookies make room; the newest are kept.
        assertEquals("c${1000 - CookieLimits.MAX_COOKIES_PER_DOMAIN}", names.first())
        assertEquals("c999", names.last())
        // Replacing a stored cookie never evicts another one.
        jar.parseCookies(listOf("c999=new"))
        assertEquals(CookieLimits.MAX_COOKIES_PER_DOMAIN, jar.current.size)
        assertEquals("new", jar.current.last().value)
    }

    @Test
    fun anExpiredCookieMakesRoomBeforeALiveOneIsEvicted() {
        var now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val jar = EngineCookieJar { now }
        jar.parseCookies(listOf("short=1; Max-Age=1"))
        jar.parseCookies(List(CookieLimits.MAX_COOKIES_PER_DOMAIN - 1) { "c$it=$it" })
        now = now.plusSeconds(2)
        jar.parseCookies(listOf("late=1"))
        val names = jar.current.map { it.name }
        assertEquals(CookieLimits.MAX_COOKIES_PER_DOMAIN, names.size)
        assertEquals("c0", names.first())
        assertEquals("late", names.last())
    }

    @Test
    fun anAlreadyExpiredCookieNeverEvictsALiveOne() {
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val jar = EngineCookieJar { now }
        jar.parseCookies(List(CookieLimits.MAX_COOKIES_PER_DOMAIN) { "c$it=$it" })
        jar.parseCookies(listOf("x=; Max-Age=-1", "y=; Max-Age=0", "z=; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        val names = jar.current.map { it.name }
        assertEquals(CookieLimits.MAX_COOKIES_PER_DOMAIN, names.size)
        assertEquals("c0", names.first())
        assertFalse(names.any { it in setOf("x", "y", "z") }, names.toString())
        // An expired cookie still deletes a stored one of the same name.
        jar.parseCookies(listOf("c5=; Max-Age=0"))
        assertEquals(CookieLimits.MAX_COOKIES_PER_DOMAIN - 1, jar.current.size)
        assertFalse(jar.current.any { it.name == "c5" })
    }

    @Test
    fun aCookieLargerThanBrowsersAcceptIsIgnored() {
        val jar = EngineCookieJar()
        val name = "big"
        jar.parseCookies(listOf("$name=" + "x".repeat(CookieLimits.MAX_COOKIE_BYTES - name.length + 1), "fits=" + "y".repeat(4091)))
        assertEquals(listOf("fits"), jar.current.map { it.name })
    }

    // ---- Finding: secrets in toString() ---------------------------------------------------------

    @Test
    fun requestsAndCookiesDoNotPrintSecrets() {
        val url = "https://example.com:8443/socket.io/?EIO=4&transport=polling&token=s3cr3t&sid=abc123"
        val http = EngineHttpRequest("GET", url, listOf("cookie" to "io=abc"), null, null).toString()
        val ws = EngineWebSocketRequest(url.replace("https", "wss"), emptyList(), emptyList(), null).toString()
        for (text in listOf(http, ws)) {
            assertFalse(text.contains("s3cr3t") || text.contains("abc123"), text)
            assertTrue(text.contains("example.com:8443/socket.io/") && text.contains("token=") && text.contains("sid="), text)
        }
        assertTrue(http.startsWith("EngineHttpRequest(GET https://"), http)
        val withUserInfo = EngineWebSocketRequest("wss://u:pw@h/p?a=1&b#frag", emptyList(), emptyList(), null)
        assertEquals("EngineWebSocketRequest(wss://***@h/p?a=***&***)", withUserInfo.toString())
        assertEquals("EngineHttpRequest(GET https://h/p)", EngineHttpRequest("GET", "https://h/p#access_token=xyz", emptyList(), null, null).toString())
        assertEquals("EngineHttpRequest(GET https://h/p?***)", EngineHttpRequest("GET", "https://h/p?access_token", emptyList(), null, null).toString())
        assertEquals("EngineHttpRequest(GET https://h/?***&a=***)", EngineHttpRequest("GET", "https://h/?eyJhbGc.x.y&a=1", emptyList(), null, null).toString())
        assertEquals("EngineHttpRequest(GET https://h/p?&a=***)", EngineHttpRequest("GET", "https://h/p?&a=1#t", emptyList(), null, null).toString())
        assertEquals("EngineHttpRequest(GET http://h/p/q@r)", EngineHttpRequest("GET", "http://h/p/q@r", emptyList(), null, null).toString())
        val cookie = EngineCookie("session", "s3cr3t").toString()
        assertFalse(cookie.contains("s3cr3t"), cookie)
        assertTrue(cookie.contains("session"), cookie)
    }
}

/** HTTP stack whose requests stay pending until the test answers them. */
internal class ScriptedHttpClient : EngineHttpClient {
    inner class Pending(
        val request: EngineHttpRequest,
        private val callback: EngineHttpCallback,
    ) {
        var cancelled = false
        var answered = false

        fun header(name: String): String? = request.header(name)

        fun respond(response: EngineHttpResponse) {
            check(!answered && !cancelled)
            answered = true
            callback.onResponse(response)
        }
    }

    val requests = ArrayList<Pending>()

    fun pending(method: String): List<Pending> = requests.filter { it.request.method == method && !it.answered && !it.cancelled }

    fun answerPoll(
        body: String,
        headers: List<Pair<String, String>> = emptyList(),
    ) {
        pending("GET").single().respond(EngineHttpResponse(200, body, headers))
    }

    override fun execute(
        request: EngineHttpRequest,
        callback: EngineHttpCallback,
    ): Cancellable {
        val pending = Pending(request, callback)
        requests += pending
        return Cancellable { pending.cancelled = true }
    }
}

internal fun EngineHttpRequest.header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

/** WebSocket stack whose connections only do what the test tells them. */
internal class ScriptedWebSocketClient : EngineWebSocketClient {
    class Connection(
        val request: EngineWebSocketRequest,
        val listener: EngineWebSocketListener,
    ) : EngineWebSocketConnection {
        val sent = ArrayList<String>()
        var closed = false

        override fun send(
            text: String,
            compress: Boolean,
        ): Boolean {
            sent += text
            return true
        }

        override fun send(
            bytes: ByteArray,
            compress: Boolean,
        ) = true

        override val queuedBytes: Long get() = 0

        override fun close(
            code: Int,
            reason: String?,
        ) {
            closed = true
        }

        override fun cancel() {
            closed = true
        }
    }

    val connections = ArrayList<Connection>()

    override fun connect(
        request: EngineWebSocketRequest,
        listener: EngineWebSocketListener,
    ): EngineWebSocketConnection = Connection(request, listener).also { connections += it }
}
