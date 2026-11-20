package io.github.kaeferfreund.socketio.engineio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** RFC 6265 scoping of the engine cookie jar: domain, host-only, path, Secure and expiry. */
class EngineCookieScopeTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun jar(
        setBy: String,
        vararg setCookies: String,
    ) = EngineCookieJar { now }.apply { parseCookies(setCookies.toList(), setBy) }

    @Test
    fun aCookieWithoutDomainIsHostOnly() {
        val jar = jar("http://node1.example.test/socket.io/", "a=1; Path=/")
        assertEquals("a=1", jar.cookieHeader("http://node1.example.test/socket.io/"))
        assertEquals("a=1", jar.cookieHeader("http://NODE1.example.test./socket.io/"))
        assertNull(jar.cookieHeader("http://node2.example.test/socket.io/"))
        assertNull(jar.cookieHeader("http://sub.node1.example.test/socket.io/"))
        assertNull(jar.cookieHeader("http://example.test/socket.io/"))
    }

    @Test
    fun aDomainCookieReachesTheDomainAndItsSubdomains() {
        val jar = jar("http://node1.example.test/socket.io/", "a=1; Domain=.Example.test; Path=/", "b=2; domain=example.test; Path=/")
        for (host in listOf("node1.example.test", "node3.example.test", "example.test", "deep.node3.example.test")) {
            assertEquals("a=1; b=2", jar.cookieHeader("http://$host/socket.io/"), host)
        }
        assertNull(jar.cookieHeader("http://badexample.test/socket.io/"))
        assertNull(jar.cookieHeader("http://example.test.evil.test/socket.io/"))
    }

    @Test
    fun aDomainAttributeThatDoesNotMatchTheHostRejectsTheCookie() {
        val jar =
            jar(
                "http://node1.example.test/",
                "foreign=1; Domain=evil.test",
                "child=2; Domain=sub.node1.example.test",
                "sibling=3; Domain=node2.example.test",
                "tld=4; Domain=test",
                "suffix=5; Domain=e.test",
                "ok=6; Domain=node1.example.test",
            )
        assertEquals(listOf("ok"), jar.current.map { it.name })
    }

    @Test
    fun aSingleLabelDomainAttributeIsHostOnlyOnThatHostAndRejectedElsewhere() {
        val local = jar("http://localhost/", "a=1; Domain=localhost")
        assertEquals("a=1", local.cookieHeader("http://localhost/"))
        assertNull(local.cookieHeader("http://sub.localhost/"))
        val elsewhere = jar("http://node1.example/", "b=2; Domain=example", "c=3; Domain=.example")
        assertEquals(emptyList<String>(), elsewhere.current.map { it.name })
    }

    @Test
    fun anIpLiteralIsNeverSuffixMatched() {
        val jar = jar("http://127.0.0.1/", "host=1", "same=2; Domain=127.0.0.1", "parent=3; Domain=0.0.1")
        assertEquals(listOf("host", "same"), jar.current.map { it.name })
        assertEquals("host=1; same=2", jar.cookieHeader("http://127.0.0.1/"))
        assertNull(jar.cookieHeader("http://1.127.0.0.1/"))
        assertNull(jar.cookieHeader("http://10.0.0.1/"))
        val v6 = jar("http://[::1]:8080/", "a=1", "b=2; Domain=::1")
        assertEquals("a=1; b=2", v6.cookieHeader("http://[::1]:9090/"))
    }

    @Test
    fun cookiesAreNotPortScoped() {
        val jar = jar("http://node1.example.test:8080/", "a=1")
        assertEquals("a=1", jar.cookieHeader("http://node1.example.test:9090/"))
        assertEquals("a=1", jar.cookieHeader("https://node1.example.test/"))
    }

    @Test
    fun aSecureCookieIsSentOnlyOverTls() {
        val jar = jar("https://node1.example.test/", "s=1; Secure", "p=2")
        assertEquals("s=1; p=2", jar.cookieHeader("https://node1.example.test/"))
        assertEquals("s=1; p=2", jar.cookieHeader("wss://node1.example.test/"))
        assertEquals("p=2", jar.cookieHeader("http://node1.example.test/"))
        assertEquals("p=2", jar.cookieHeader("ws://node1.example.test/"))
    }

    @Test
    fun aSecureCookieFromPlainHttpIsIgnored() {
        val jar = jar("http://node1.example.test/", "s=1; Secure", "p=2")
        assertEquals("p=2", jar.cookieHeader("https://node1.example.test/"))
    }

    // Browsers treat loopback origins as secure contexts; the upstream cookie test relies on it.
    @Test
    fun loopbackHostsCountAsSecureLikeInABrowser() {
        for (host in listOf("localhost", "a.localhost", "127.0.0.1", "127.1.2.3", "[::1]")) {
            val jar = jar("http://$host:3000/", "s=1; Secure")
            assertEquals("s=1", jar.cookieHeader("http://$host:3000/"), host)
        }
    }

    @Test
    fun pathsMatchOnASlashBoundaryOfTheEncodedPath() {
        val jar = jar("http://h.example/a/", "a=1; Path=/a", "slash=2; Path=/a/")
        assertEquals("a=1; slash=2", jar.cookieHeader("http://h.example/a/"))
        assertEquals("a=1", jar.cookieHeader("http://h.example/a"))
        assertEquals("a=1; slash=2", jar.cookieHeader("http://h.example/a/b"))
        assertNull(jar.cookieHeader("http://h.example/ab"))
        assertNull(jar.cookieHeader("http://h.example/a%2Fb/"))
        assertNull(jar.cookieHeader("http://h.example/A/"))
    }

    @Test
    fun expiredCookiesAreNotSent() {
        var clock = now
        val jar = EngineCookieJar { clock }
        jar.parseCookies(listOf("short=1; Max-Age=10", "long=2; Max-Age=1000"), "http://h.example/")
        assertEquals("short=1; long=2", jar.cookieHeader("http://h.example/"))
        clock = now.plusSeconds(11)
        assertEquals("long=2", jar.cookieHeader("http://h.example/"))
    }

    // RFC 6265 5.3 step 11: a cookie replaces the one with the same name, domain and path only.
    @Test
    fun cookiesOfTheSameNameFromDifferentHostsCoexist() {
        val jar = EngineCookieJar { now }
        jar.parseCookies(listOf("io=origin; Path=/"), "http://node1.example.test/")
        jar.parseCookies(listOf("io=foreign; Path=/"), "http://evil.test/")
        assertEquals("io=origin", jar.cookieHeader("http://node1.example.test/"))
        assertEquals("io=foreign", jar.cookieHeader("http://evil.test/"))
        // An expired cookie deletes only its own namesake.
        jar.parseCookies(listOf("io=; Max-Age=0; Path=/"), "http://evil.test/")
        assertEquals("io=origin", jar.cookieHeader("http://node1.example.test/"))
        assertNull(jar.cookieHeader("http://evil.test/"))
    }

    @Test
    fun aCookieIsReplayedWhereItWasSetWhateverItsPathButOnlyOnThatHost() {
        val jar = jar("http://node1.example.test/socket.io/", "elsewhere=1; Path=/admin; Domain=example.test")
        assertEquals("elsewhere=1", jar.cookieHeader("http://node1.example.test/socket.io/"))
        assertNull(jar.cookieHeader("http://node2.example.test/socket.io/"))
        assertEquals("elsewhere=1", jar.cookieHeader("http://node2.example.test/admin/"))
    }

    @Test
    fun anUnparseableRequestUrlStoresAndSendsNothing() {
        val jar = jar("not a url", "a=1")
        assertEquals(emptyList<EngineCookie>(), jar.current)
        jar.parseCookies(listOf("b=2"), "http://h.example/")
        assertNull(jar.cookieHeader("relative/path"))
    }
}
