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

    // RFC 6265 5.3 step 6: only a missing Domain makes a cookie host-only; the apex that names
    // itself reaches its subdomains, as a load balancer redirecting to a node expects.
    @Test
    fun aDomainEqualToTheSettingHostReachesItsSubdomains() {
        val jar = jar("https://example.test/socket.io/", "lb=1; Domain=example.test; Path=/", "dot=2; Domain=.EXAMPLE.test; Path=/")
        for (host in listOf("example.test", "node.example.test", "deep.node.example.test")) {
            assertEquals("lb=1; dot=2", jar.cookieHeader("https://$host/socket.io/"), host)
        }
        assertNull(jar.cookieHeader("https://otherexample.test/socket.io/"))
    }

    @Test
    fun aDomainThatIsNoHostNameRejectsTheCookieAndALoneDotIsIgnored() {
        val jar = jar("http://node1.example.test/", "dots=1; Domain=..", "brackets=2; Domain=[]", "dot=3; Domain=.", "empty=4; Domain=")
        assertEquals(listOf("dot", "empty"), jar.current.map { it.name })
        assertNull(jar.cookieHeader("http://sub.node1.example.test/"))
    }

    // OkHttp writes redirect URLs with IDNA ASCII hosts and compressed IPv6; the jar compares the
    // same canonical form, whichever spelling the engine URL used.
    @Test
    fun hostsCompareInTheirCanonicalForm() {
        val idn = jar("http://Bücher.example/", "a=1")
        assertEquals("a=1", idn.cookieHeader("http://xn--bcher-kva.example/"))
        assertEquals("a=1", idn.cookieHeader("http://bücher.example/"))
        val v6 = jar("http://[0:0:0:0:0:0:0:1]:3000/", "b=2; Secure")
        assertEquals("b=2", v6.cookieHeader("http://[::1]:3000/"))
        val mapped = jar("http://[::ffff:127.0.0.1]/", "c=3")
        assertEquals("c=3", mapped.cookieHeader("http://127.0.0.1/"))
        assertEquals("d=4", jar("http://[2001:DB8:0:0:1:0:0:1]/", "d=4").cookieHeader("http://[2001:db8::1:0:0:1]/"))
    }

    // WHATWG: a host whose last label is a number is an IPv4 address, never a domain.
    @Test
    fun aHostEndingInANumberIsAnAddress() {
        assertEquals(emptyList<EngineCookie>(), jar("http://1.2.3/", "a=1; Domain=2.3").current)
        assertEquals(emptyList<EngineCookie>(), jar("http://a.0x7f/", "b=2; Domain=0x7f").current)
    }

    // RFC 6265 5.4: servers rely on the longer path coming first.
    @Test
    fun cookiesOfTheSameNameAreSentLongerPathFirst() {
        val jar = EngineCookieJar { now }
        jar.parseCookies(listOf("sid=global; Path=/"), "http://h.example/")
        jar.parseCookies(listOf("sid=socket; Path=/socket.io"), "http://h.example/socket.io/")
        assertEquals("sid=socket; sid=global", jar.cookieHeader("http://h.example/socket.io/"))
        assertEquals("sid=global", jar.cookieHeader("http://h.example/other/"))
    }

    // RFC 6265bis 5.7: plain HTTP must not overwrite or delete what HTTPS set as Secure.
    @Test
    fun plainHttpCannotReplaceOrDeleteASecureCookie() {
        val jar = EngineCookieJar { now }
        jar.parseCookies(listOf("sid=real; Secure; Path=/"), "https://node1.example.test/")
        jar.parseCookies(listOf("sid=forged; Path=/"), "http://node1.example.test/")
        jar.parseCookies(listOf("sid=forged; Domain=example.test; Path=/socket.io"), "http://node2.example.test/socket.io/")
        assertEquals("sid=real", jar.cookieHeader("https://node1.example.test/socket.io/"))
        jar.parseCookies(listOf("sid=; Max-Age=0; Path=/"), "http://node1.example.test/")
        assertEquals("sid=real", jar.cookieHeader("https://node1.example.test/"))
        // Another name, or a non-overlapping host, is not affected.
        jar.parseCookies(listOf("other=1; Path=/"), "http://node1.example.test/")
        jar.parseCookies(listOf("sid=elsewhere; Path=/"), "http://evil.test/")
        assertEquals("other=1", jar.cookieHeader("http://node1.example.test/"))
        assertEquals("sid=elsewhere", jar.cookieHeader("http://evil.test/"))
        // HTTPS still replaces it.
        jar.parseCookies(listOf("sid=new; Secure; Path=/"), "https://node1.example.test/")
        assertEquals("sid=new; other=1", jar.cookieHeader("https://node1.example.test/"))
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
