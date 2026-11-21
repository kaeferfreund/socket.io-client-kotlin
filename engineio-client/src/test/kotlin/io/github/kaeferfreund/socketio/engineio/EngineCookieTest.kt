package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** Ports of `cookie parsing` and `withCredentials` in `test/node.js`, plus `extraHeaders`. */
class EngineCookieTest {
    // JS-180
    @Test
    fun parsesASimpleSetCookieHeader() {
        assertEquals(EngineCookie("foo", "bar"), EngineCookie.parse("foo=bar"))
    }

    // JS-181, deliberately RFC 6265 5.3 instead of the Node jar: Max-Age takes precedence over
    // Expires in either order (the Node jar lets the later attribute win).
    @Test
    fun parsesAComplexSetCookieHeader() {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        val cookie =
            EngineCookie.parse(
                "foo=bar; Max-Age=1000; Domain=.example.com; Path=/; Expires=Tue, 01 Jul 2025 10:01:11 GMT; HttpOnly; Secure; SameSite=strict",
                now,
            )
        assertEquals(EngineCookie("foo", "bar", now.plusSeconds(1000)), cookie)
        assertEquals(
            EngineCookie("foo", "bar", now.plusSeconds(1000)),
            EngineCookie.parse("foo=bar; Expires=Tue, 01 Jul 2025 10:01:11 GMT; Max-Age=1000", now),
        )
        // An invalid Max-Age is ignored, so Expires applies.
        assertEquals(
            EngineCookie("foo", "bar", Instant.parse("2025-07-01T10:01:11Z")),
            EngineCookie.parse("foo=bar; Max-Age=soon; Expires=Tue, 01 Jul 2025 10:01:11 GMT", now),
        )
    }

    // JS-182
    @Test
    fun parsesAWeirdButValidCookie() {
        assertEquals(
            EngineCookie("foo", "bar=bar&foo=foo&John=Doe&Doe=John"),
            EngineCookie.parse("foo=bar=bar&foo=foo&John=Doe&Doe=John; Domain=.example.com; Path=/; HttpOnly; Secure"),
        )
    }

    @Test
    fun ignoresInvalidCookiesAndExpiresOldOnes() {
        assertNull(EngineCookie.parse("novalue"))
        assertNull(EngineCookie.parse("=value"))
        assertEquals("q", EngineCookie.parse("a=\"q\"")!!.value)
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val jar = EngineCookieJar { now }
        jar.parseCookies(listOf("1=1; Max-Age=86400", "2=2; Path=/", "3=3; Max-Age=0", "4=4; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        now = now.plusMillis(1)
        assertEquals("1=1; 2=2", jar.cookieHeader())
        now = now.plusSeconds(86400)
        assertEquals("2=2", jar.cookieHeader())
    }

    @Test
    fun aMaxAgeOutsideTheDateRangeNeverExpires() {
        for (maxAge in listOf("99999999999999999", "9223372036854775807", "-99999999999999999")) {
            assertEquals(EngineCookie("a", "b"), EngineCookie.parse("a=b; Max-Age=$maxAge"))
        }
        val jar = EngineCookieJar()
        jar.parseCookies(listOf("a=b; Max-Age=9223372036854775807"))
        assertEquals("a=b", jar.cookieHeader())
    }

    @Test
    fun ignoresCookiesThatCannotBeSentBackInAHeader() {
        val jar = EngineCookieJar()
        jar.parseCookies(listOf("a=café", "b=x\u0001y", "cé=1", "ok=1"))
        assertEquals("ok=1", jar.cookieHeader())
    }

    // JS-178 (unit counterpart; the E2E suite repeats it against Node)
    @Test
    fun sendsCookiesWithWithCredentials() =
        runTest {
            val h = engineHarness()
            h.server.handshakeHeaders = listOf("Set-Cookie" to "1=1; Max-Age=86400", "Set-Cookie" to "2=2; Path=/; HttpOnly")
            h.engine(EngineOptions(transports = listOf("polling"), withCredentials = true))
            h.settle()
            val later = h.server.requests.drop(1)
            assertEquals(listOf("1=1; 2=2"), later.map { it.header("cookie") }.distinct())
            h.close()
        }

    // JS-179
    @Test
    fun doesNotSendCookiesWithoutWithCredentials() =
        runTest {
            val h = engineHarness()
            h.server.handshakeHeaders = listOf("Set-Cookie" to "1=1")
            h.engine(EngineOptions(transports = listOf("polling"), withCredentials = false))
            h.settle()
            assertEquals(listOf<String?>(null), h.server.requests.map { it.header("cookie") }.distinct())
            h.close()
        }

    // JS-219, JS-220, JS-223
    @Test
    fun setsExtraHeadersOnPollingAndWebSocketRequests() =
        runTest {
            val h = engineHarness()
            val headers =
                mapOf(
                    "X-Custom-Header-For-My-Project" to "my-secret-access-token",
                    "Cookie" to "user_session=NI2JlCKF90aE0sJZD9ZzujtdsUqNYSBYxzlTsvdSUe35ZzdtVRGqYFr0kdGxbfc5gUOkR9RGp20GVKza",
                )
            h.engine(EngineOptions(extraHeaders = headers))
            h.settle()
            val polling = h.server.requests.first { it.method == "GET" }
            val websocket = h.server.requests.first { it.method == "UPGRADE" }
            for (request in listOf(polling, websocket)) {
                for ((name, value) in headers) assertEquals(value, request.header(name), "$name on ${request.method}")
            }
            assertEquals("*/*", polling.header("Accept"))
            h.close()
        }

    @Test
    fun postsAreSentAsUtf8Text() =
        runTest {
            val h = engineHarness()
            val engine = h.engine(EngineOptions(upgrade = false))
            h.settle()
            h.executor.execute { engine.send("x") }
            h.settle()
            val post = h.server.requests.first { it.method == "POST" }
            assertEquals("text/plain;charset=UTF-8", post.header("Content-type"))
            assertEquals("*/*", post.header("Accept"))
            h.close()
        }
}
