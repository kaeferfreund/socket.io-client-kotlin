package io.github.kaeferfreund.socketio.engineio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The engine cookie jar follows RFC 6265 (and RFC 6265bis where noted) instead of the Node jar:
 * domain, host-only, path, Secure, prefixes, identity, order, limits and expiry.
 */
class EngineCookieScopeTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun jar(
        setBy: String,
        vararg setCookies: String,
    ) = EngineCookieJar { now }.apply { parseCookies(setCookies.toList(), setBy) }

    private fun EngineCookieJar.names() = current.map { it.name }

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
        assertEquals("c=3", mapped.cookieHeader("http://[::ffff:7f00:1]/"))
        assertEquals("d=4", jar("http://[2001:DB8:0:0:1:0:0:1]/", "d=4").cookieHeader("http://[2001:db8::1:0:0:1]/"))
        // OkHttp keeps a trailing root dot and short IPv4 forms; the jar compares the host they name.
        assertEquals("e=5", jar("http://node1.example.test./", "e=5").cookieHeader("http://node1.example.test/"))
        assertEquals("f=6", jar("http://127.1/", "f=6").cookieHeader("http://127.0.0.1/"))
    }

    // The WHATWG URL standard's IPv4 parser decides what an address is; nothing is resolved.
    @Test
    fun ipv4FormsAreOneAddressAndNamesEndingInANumberStayNames() {
        val loopback = jar("http://127.1/", "a=1")
        for (form in listOf("127.0.0.1", "0x7f.0.0.1", "0177.0.0.1", "2130706433", "127.0.1", "127.0.0.1.")) {
            assertEquals("a=1", loopback.cookieHeader("http://$form/"), form)
        }
        assertNull(loopback.cookieHeader("http://127.0.0.2/"))
        // An address never matches a suffix, also when the Domain names a shorter form of one.
        assertEquals(emptyList<String>(), jar("http://1.2.3/", "a=1; Domain=2.3").names())
        assertEquals(emptyList<String>(), jar("http://a.0x7f/", "b=2; Domain=0x7f").names())
        // These end in something number-like but are names, whose Domain cookies reach subdomains.
        for (name in listOf("foo.123", "app.0x1", "foo.0x", "127.evil.0", "1.2.3.4.5")) {
            val jar = jar("http://$name/", "c=3; Domain=$name")
            assertEquals("c=3", jar.cookieHeader("http://sub.$name/"), name)
        }
    }

    // IPv6 literals are parsed without InetAddress, so no lookup can happen; an invalid one is no host.
    @Test
    fun ipv6LiteralsAreParsedWithoutLookupsAndInvalidOnesAreNoHost() {
        val jar = jar("http://[1:0:0:0:0:0:0:8]/", "a=1")
        assertEquals("a=1", jar.cookieHeader("http://[1::8]/"))
        for (invalid in listOf("[1::2::3]", "[1:2:3:4:5:6:7:8:9]", "[fe80::1%25eth0]", "[::1", "[::1]x", "[::1.2.3]", "[localhost]", "[]")) {
            assertNull(jar("http://$invalid/", "b=2").current.firstOrNull { it.name == "b" }, invalid)
            assertNull(jar.cookieHeader("http://$invalid/"), invalid)
        }
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

    // RFC 6265 5.4 step 2 for every cookie, not only namesakes: longer paths first, then creation
    // order, which a replaced cookie keeps. The Node jar sends them in creation order.
    @Test
    fun allCookiesAreSentLongerPathFirstThenInCreationOrder() {
        val jar = EngineCookieJar { now }
        jar.parseCookies(listOf("root=1; Path=/", "deep=2; Path=/socket.io/x", "mid=3; Path=/socket.io", "root2=4; Path=/"), "http://h.example/socket.io/x")
        assertEquals("deep=2; mid=3; root=1; root2=4", jar.cookieHeader("http://h.example/socket.io/x/"))
        // A replaced cookie keeps its creation time; a deleted and recreated one does not.
        jar.parseCookies(listOf("root=5; Path=/"), "http://h.example/")
        assertEquals("root=5; root2=4", jar.cookieHeader("http://h.example/"))
        jar.parseCookies(listOf("root=; Max-Age=0; Path=/", "root=6; Path=/"), "http://h.example/")
        assertEquals("root2=4; root=6", jar.cookieHeader("http://h.example/"))
    }

    // RFC 6265bis 5.7: the host-only flag is part of a cookie's identity, so a sibling host's
    // Domain cookie cannot replace the cookie a host set for itself, nor the other way round.
    @Test
    fun aDomainCookieNeverReplacesAHostOnlyCookie() {
        val jar = EngineCookieJar { now }
        jar.parseCookies(listOf("sid=mine; Path=/"), "http://example.test/")
        jar.parseCookies(listOf("sid=sibling; Domain=example.test; Path=/"), "http://evil.example.test/")
        assertEquals("sid=mine; sid=sibling", jar.cookieHeader("http://example.test/"))
        assertEquals("sid=sibling", jar.cookieHeader("http://node.example.test/"))
        // Deleting the Domain cookie leaves the host-only one alone.
        jar.parseCookies(listOf("sid=; Max-Age=0; Domain=example.test; Path=/"), "http://evil.example.test/")
        assertEquals("sid=mine", jar.cookieHeader("http://example.test/"))
        jar.parseCookies(listOf("sid=again; Domain=example.test; Path=/"), "http://example.test/")
        jar.parseCookies(listOf("sid=mine2; Path=/"), "http://example.test/")
        assertEquals("sid=mine2; sid=again", jar.cookieHeader("http://example.test/"))
    }

    // RFC 6265bis cookie prefixes, compared case-insensitively.
    @Test
    fun cookiePrefixesNeedTheirAttributes() {
        val jar =
            jar(
                "https://node1.example.test/socket.io/",
                "__Secure-ok=1; Secure",
                "__Secure-plain=2",
                "__SECURE-upper=3",
                "__Host-ok=4; Secure; Path=/",
                "__host-lower=5; Path=/",
                "__Host-domain=6; Secure; Path=/; Domain=node1.example.test",
                "__Host-path=7; Secure; Path=/socket.io",
                "__Host-nopath=8; Secure",
                "__Hostile=9",
            )
        assertEquals(listOf("__Secure-ok", "__Host-ok", "__Hostile"), jar.names())
        // From plain HTTP, Secure itself is refused, so no prefixed cookie gets in.
        assertEquals(emptyList<String>(), jar("http://node1.example.test/", "__Secure-a=1; Secure", "__Host-b=2; Secure; Path=/").names())
    }

    // RFC 6265 5.2: attributes end at any `;`, are trimmed, and their names are case-insensitive,
    // Expires and Max-Age included. The Node jar splits on "; " and knows one spelling only.
    @Test
    fun attributesSplitOnSemicolonsAndTheirNamesIgnoreCase() {
        assertEquals(EngineCookie("a", "1", now.plusSeconds(10)), EngineCookie.parse("a=1;max-age=10", now))
        assertEquals(EngineCookie("b", "2", Instant.parse("2027-01-01T00:00:00Z")), EngineCookie.parse("b = 2 ;EXPIRES=Fri, 01 Jan 2027 00:00:00 GMT", now))
        assertEquals(EngineCookie("c", "3"), EngineCookie.parse("c=3;HttpOnly", now))
        val jar = jar("https://h.example/a/", "tight=1;PATH=/x;SECURE;DOMAIN=h.example", "gone=1;MAX-AGE=0")
        assertEquals("tight=1", jar.cookieHeader("https://sub.h.example/x/"))
        assertNull(jar.cookieHeader("http://sub.h.example/x/"))
        assertEquals(listOf("tight"), jar.names())
    }

    // RFC 6265 5.3 step 5 with a public suffix list (OkHttp's, in socketio-okhttp): such a Domain
    // is rejected, or makes a host-only cookie on the host it names.
    @Test
    fun aPublicSuffixDomainIsRejectedOrHostOnly() {
        val suffixes = EnginePublicSuffixes { it in setOf("co.uk", "github.io") }
        val shop = EngineCookieJar({ now }, suffixes).apply { parseCookies(listOf("a=1; Domain=co.uk", "b=2; Domain=shop.co.uk"), "https://shop.co.uk/") }
        assertEquals(listOf("b"), shop.names())
        assertEquals("b=2", shop.cookieHeader("https://www.shop.co.uk/"))
        val pages = EngineCookieJar({ now }, suffixes).apply { parseCookies(listOf("c=3; Domain=github.io"), "https://github.io/") }
        assertEquals("c=3", pages.cookieHeader("https://github.io/"))
        assertNull(pages.cookieHeader("https://evil.github.io/"))
        // A list that fails counts every domain as a public suffix: no cookie widens its scope.
        val failing = EngineCookieJar({ now }, EnginePublicSuffixes { error("no list") })
        failing.parseCookies(listOf("d=4; Domain=example.test", "e=5; Domain=node1.example.test"), "https://node1.example.test/")
        assertEquals(listOf("e"), failing.names())
        assertNull(failing.cookieHeader("https://sub.node1.example.test/"))
    }

    // Per cookie domain and in all, as browsers limit per site: one host cannot evict another's cookies.
    @Test
    fun oneDomainCannotEvictTheCookiesOfAnother() {
        val jar = EngineCookieJar { now }
        jar.parseCookies(List(5) { "origin$it=1; Path=/" }, "http://node1.example.test/")
        jar.parseCookies(List(CookieLimits.MAX_COOKIES_PER_DOMAIN + 20) { "x$it=1; Path=/" }, "http://evil.test/")
        assertEquals(CookieLimits.MAX_COOKIES_PER_DOMAIN, jar.current.count { it.name.startsWith("x") })
        assertEquals("x20", jar.current.first { it.name.startsWith("x") }.name)
        // Many domains together fill the jar; the domain holding the most gives way.
        for (domain in 0 until CookieLimits.MAX_COOKIES / CookieLimits.MAX_COOKIES_PER_DOMAIN + 1) {
            jar.parseCookies(List(CookieLimits.MAX_COOKIES_PER_DOMAIN) { "y$it=1; Path=/" }, "http://d$domain.evil.test/")
        }
        assertEquals(CookieLimits.MAX_COOKIES, jar.current.size)
        assertEquals("origin0=1; origin1=1; origin2=1; origin3=1; origin4=1", jar.cookieHeader("http://node1.example.test/"))
    }

    // RFC 6265bis 5.7: plain HTTP must not overwrite or delete what HTTPS set as Secure, when its
    // domain overlaps and its path path-matches the Secure cookie's path.
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
        // A path the Secure cookie's path does not cover is not shadowed: `/` does not path-match `/socket.io`.
        jar.parseCookies(listOf("deep=secure; Secure; Path=/socket.io"), "https://node1.example.test/socket.io/")
        jar.parseCookies(listOf("deep=plain; Path=/"), "http://node1.example.test/")
        assertEquals("other=1; deep=plain", jar.cookieHeader("http://node1.example.test/socket.io/"))
    }

    // Only a live Secure cookie is protected: once expired, plain HTTP may set the name again.
    @Test
    fun anExpiredSecureCookieDoesNotBlockPlainHttp() {
        var clock = now
        val jar = EngineCookieJar { clock }
        jar.parseCookies(listOf("sid=real; Secure; Path=/; Max-Age=10"), "https://node1.example.test/")
        clock = now.plusSeconds(11)
        jar.parseCookies(listOf("sid=plain; Path=/"), "http://node1.example.test/")
        assertEquals("sid=plain", jar.cookieHeader("http://node1.example.test/"))
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
        for (host in listOf("localhost", "LOCALHOST.", "127.0.0.1", "127.1.2.3", "127.1", "0x7f.0.0.1", "[::1]", "[0:0:0:0:0:0:0:1]")) {
            val jar = jar("http://$host:3000/", "s=1; Secure")
            assertEquals("s=1", jar.cookieHeader("http://$host:3000/"), host)
        }
        // Names under localhost, and names that only look like loopback addresses, are not secure.
        for (host in listOf("a.localhost", "127.evil.test", "128.0.0.1", "[::2]")) {
            assertEquals(emptyList<String>(), jar("http://$host:3000/", "s=1; Secure").names(), host)
        }
    }

    @Test
    fun pathsMatchOnASlashBoundaryOfTheEncodedPath() {
        val jar = jar("http://h.example/a/", "a=1; Path=/a", "slash=2; Path=/a/")
        // Longer path first (RFC 6265 5.4), not creation order as in the Node jar.
        assertEquals("slash=2; a=1", jar.cookieHeader("http://h.example/a/"))
        assertEquals("a=1", jar.cookieHeader("http://h.example/a"))
        assertEquals("slash=2; a=1", jar.cookieHeader("http://h.example/a/b"))
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

    // Strictly RFC 6265: the Node jar's replay on the URL that set a cookie, whatever its Path, is gone.
    @Test
    fun aCookieGoesOnlyWhereItsPathMatchesAlsoWhereItWasSet() {
        val jar = jar("http://node1.example.test/socket.io/", "elsewhere=1; Path=/admin; Domain=example.test")
        assertNull(jar.cookieHeader("http://node1.example.test/socket.io/"))
        assertNull(jar.cookieHeader("http://node2.example.test/socket.io/"))
        assertEquals("elsewhere=1", jar.cookieHeader("http://node1.example.test/admin/"))
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
