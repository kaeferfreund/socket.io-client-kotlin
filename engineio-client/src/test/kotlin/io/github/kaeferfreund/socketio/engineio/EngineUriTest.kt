package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Hardening of [EngineUri.parse] beyond the JavaScript `parseuri` cases (ported in [EngineClientApiTest]). */
class EngineUriTest {
    @Test
    fun bracketsInTheQueryDoNotMakeAnIpv6Host() {
        val parsed = EngineUri.parse("https://chat.example.com/?ids[]=1")
        assertEquals("https", parsed.protocol)
        assertEquals("chat.example.com", parsed.host)
        assertEquals("chat.example.com", parsed.authority)
        assertEquals("", parsed.port)
        assertEquals("ids[]=1", parsed.query)
        assertFalse(parsed.ipv6)
    }

    @Test
    fun bracketsInTheQueryWithoutPathDoNotMakeAnIpv6Host() {
        val parsed = EngineUri.parse("https://chat.example.com:8443?ids[]=1&x=[a:b]")
        assertEquals("chat.example.com", parsed.host)
        assertEquals("8443", parsed.port)
        assertEquals("ids[]=1&x=[a:b]", parsed.query)
        assertFalse(parsed.ipv6)
    }

    @Test
    fun bracketsInThePathDoNotMakeAnIpv6Host() {
        val parsed = EngineUri.parse("http://example.com:3000/a[1:2]/b")
        assertEquals("example.com", parsed.host)
        assertEquals("3000", parsed.port)
        assertEquals("/a[1:2]/b", parsed.path)
        assertFalse(parsed.ipv6)
    }

    @Test
    fun bracketsInTheFragmentDoNotMakeAnIpv6Host() {
        val parsed = EngineUri.parse("wss://example.com#[x]")
        assertEquals("wss", parsed.protocol)
        assertEquals("example.com", parsed.host)
        assertEquals("[x]", parsed.anchor)
        assertFalse(parsed.ipv6)
    }

    @Test
    fun reversedBracketsOutsideTheAuthorityAreNotAnError() {
        val parsed = EngineUri.parse("https://example.com/?a=]&b=[")
        assertEquals("example.com", parsed.host)
        assertEquals("a=]&b=[", parsed.query)
    }

    @Test
    fun bracketsWithoutSchemeOutsideTheAuthorityDoNotMakeAnIpv6Host() {
        val parsed = EngineUri.parse("example.com/?ids[]=1")
        assertEquals("example.com", parsed.host)
        assertEquals("ids[]=1", parsed.query)
        assertFalse(parsed.ipv6)
    }

    @Test
    fun bracketsInTheUserInfoDoNotMakeAnIpv6Host() {
        val parsed = EngineUri.parse("http://u[x]@example.com/")
        assertEquals("example.com", parsed.host)
        assertEquals("u[x]", parsed.user)
        assertFalse(parsed.ipv6)
    }

    @Test
    fun ipv6HostsStillParse() {
        val bare = EngineUri.parse("[::1]")
        assertEquals("::1", bare.host)
        assertEquals("", bare.port)
        assertTrue(bare.ipv6)

        val withPort = EngineUri.parse("[::1]:3000")
        assertEquals("::1", withPort.host)
        assertEquals("3000", withPort.port)
        assertTrue(withPort.ipv6)

        val withQuery = EngineUri.parse("http://[fe80::1]/path?x[]=1")
        assertEquals("http", withQuery.protocol)
        assertEquals("fe80::1", withQuery.host)
        assertEquals("fe80::1", withQuery.authority)
        assertEquals("/path", withQuery.path)
        assertEquals("x[]=1", withQuery.query)
        assertTrue(withQuery.ipv6)

        val withAll = EngineUri.parse("https://[2001:db8::7]:8443/a[1]?b[]=2#[c]")
        assertEquals("2001:db8::7", withAll.host)
        assertEquals("8443", withAll.port)
        assertEquals("/a[1]", withAll.path)
        assertEquals("b[]=2", withAll.query)
        assertEquals("[c]", withAll.anchor)
        assertTrue(withAll.ipv6)
    }

    @Test
    fun theEngineConnectsToTheHostWhenTheQueryHasBrackets() =
        runTest {
            val engine = newEngine("https://chat.example.com/?ids[]=1")
            assertEquals("chat.example.com", engine.hostname)
            assertEquals("443", engine.port)
        }

    @Test
    fun mixedCaseSchemesAreRecognisedAndLowercased() {
        val schemes = listOf("HTTPS" to "https", "Http" to "http", "WsS" to "wss", "WS" to "ws")
        for ((scheme, protocol) in schemes) {
            val uri = "$scheme://example.com"
            val parsed = EngineUri.parse(uri)
            assertEquals(protocol, parsed.protocol, uri)
            assertEquals("example.com", parsed.host, uri)
            assertEquals("", parsed.port, uri)
        }
        assertTrue(EngineUri.parse("HTTPS://example.com").secure)
        val ipv6 = EngineUri.parse("HTTPS://[::1]:8443/?a[]=1")
        assertEquals("https", ipv6.protocol)
        assertEquals("::1", ipv6.host)
        assertEquals("8443", ipv6.port)
    }

    @Test
    fun theEngineUsesTlsForAMixedCaseHttpsScheme() =
        runTest {
            val engine = newEngine("HTTPS://example.com")
            assertEquals("example.com", engine.hostname)
            assertEquals("443", engine.port)
        }

    // The host must be the one a WHATWG/OkHttp parser sees, or an allowlist check on HttpUrl can be bypassed.
    @Test
    fun aBackslashEndsTheAuthorityAsInWhatwgUrls() {
        val parsed = EngineUri.parse("https://good.example\\@evil.example/")
        assertEquals("https", parsed.protocol)
        assertEquals("good.example", parsed.host)
        assertEquals("", parsed.userInfo)
        assertEquals("", parsed.port)

        val withPort = EngineUri.parse("https://good.example:8443\\@evil.example/")
        assertEquals("good.example", withPort.host)
        assertEquals("8443", withPort.port)

        val ipv6 = EngineUri.parse("https://[::1]\\@evil.example/?a[]=1")
        assertEquals("::1", ipv6.host)
        assertTrue(ipv6.ipv6)

        assertEquals("good.example", EngineUri.parse("good.example\\@evil.example").host)
    }

    @Test
    fun theHostFollowsTheLastAtSignOfTheAuthority() {
        val colons = EngineUri.parse("https://evil.example:x:y@good.example/chat")
        assertEquals("good.example", colons.host)
        assertEquals("evil.example:x:y", colons.userInfo)
        assertEquals("evil.example", colons.user)
        assertEquals("x:y", colons.password)
        assertEquals("/chat", colons.path)

        val ats = EngineUri.parse("wss://a@b@good.example:8443")
        assertEquals("good.example", ats.host)
        assertEquals("8443", ats.port)
        assertEquals("a@b", ats.user)

        val both = EngineUri.parse("http://a@b:c@d@good.example/")
        assertEquals("good.example", both.host)
        assertEquals("a@b", both.user)
        assertEquals("c@d", both.password)

        val ipv6 = EngineUri.parse("http://u:p:q@[::1]:3000/")
        assertEquals("::1", ipv6.host)
        assertEquals("3000", ipv6.port)
        assertEquals("p:q", ipv6.password)
    }

    @Test
    fun backslashesAndAtSignsAfterTheAuthorityAreUnchanged() {
        val parsed = EngineUri.parse("https://good.example/a\\b?c=\\@evil.example&d=e@f#\\g")
        assertEquals("good.example", parsed.host)
        assertEquals("", parsed.userInfo)
        assertEquals("/a\\b", parsed.path)
        assertEquals("c=\\@evil.example&d=e@f", parsed.query)
        assertEquals("\\g", parsed.anchor)
    }

    @Test
    fun theEngineConnectsToTheHostAWhatwgParserSees() =
        runTest {
            assertEquals("good.example", newEngine("https://good.example\\@evil.example/").hostname)
            assertEquals("good.example", newEngine("https://evil.example:x:y@good.example/").hostname)
        }

    // OkHttp's HttpUrl and WHATWG URLs skip every `/` and `\\` after `http:`; parseuri took `http:///good.example`
    // as an empty host (so the engine connected to localhost) and `http:/good.example` as the host `http`.
    @Test
    fun slashesAndBackslashesAfterTheSchemeAreSkippedAsInWhatwgUrls() {
        for (uri in listOf("http:///good.example", "http:/good.example", "http:good.example", "http:\\good.example", "http://\\/good.example")) {
            val parsed = EngineUri.parse(uri)
            assertEquals("http", parsed.protocol, uri)
            assertEquals("good.example", parsed.host, uri)
            assertEquals("", parsed.port, uri)
        }
        val secure = EngineUri.parse("https:////good.example:8443/chat?a=1")
        assertEquals("https", secure.protocol)
        assertTrue(secure.secure)
        assertEquals("good.example", secure.host)
        assertEquals("8443", secure.port)
        assertEquals("/chat", secure.path)
        assertEquals("a=1", secure.query)

        assertEquals("good.example", EngineUri.parse("https://\\good.example").host)
        assertEquals("good.example", EngineUri.parse("HTTPS:\\\\good.example").host)
        assertEquals("wss", EngineUri.parse("wss:/good.example").protocol)
        assertEquals("good.example", EngineUri.parse("http:/user:pw@good.example").host)

        val ipv6 = EngineUri.parse("http:///[::1]:3000/x?a[]=1")
        assertEquals("::1", ipv6.host)
        assertTrue(ipv6.ipv6)
        assertEquals("3000", ipv6.port)
    }

    // An `@` after a `\\`, `?` or `#` is not in the authority, so the scheme stays a scheme, as in HttpUrl.
    @Test
    fun anAtSignAfterTheAuthorityDoesNotTurnTheSchemeIntoUserInfo() {
        val userInfo = EngineUri.parse("http:\\\\user@good.example")
        assertEquals("http", userInfo.protocol)
        assertEquals("user", userInfo.user)
        assertEquals("good.example", userInfo.host)
        for (uri in listOf(
            "http:\\\\good.example\\@evil.example/",
            "http:good.example?a=@b",
            "http:good.example#a@b",
            "http:\\good.example?token=a@b",
        )) {
            val parsed = EngineUri.parse(uri)
            assertEquals("http", parsed.protocol, uri)
            assertEquals("good.example", parsed.host, uri)
            assertEquals("", parsed.userInfo, uri)
        }
    }

    @Test
    fun aSchemeNameFollowedByAPortOrUserInfoIsStillAHost() {
        val port = EngineUri.parse("http:8080")
        assertEquals("", port.protocol)
        assertEquals("http", port.host)
        assertEquals("8080", port.port)
        assertEquals("http", EngineUri.parse("http:8080/chat").host)
        assertEquals("https", EngineUri.parse("https:").host)

        val userInfo = EngineUri.parse("http:pass@good.example")
        assertEquals("", userInfo.protocol)
        assertEquals("http", userInfo.user)
        assertEquals("good.example", userInfo.host)
    }

    @Test
    fun theEngineConnectsToTheHostAfterExtraSlashes() =
        runTest {
            val engine = newEngine("https://\\good.example")
            assertEquals("good.example", engine.hostname)
            assertEquals("443", engine.port)
            assertEquals("good.example", newEngine("http:///good.example").hostname)
            assertEquals("80", newEngine("http:///good.example").port)
        }

    @Test
    fun aUrlWithoutHostStillFallsBackToLocalhostAsInJavaScript() =
        runTest {
            assertEquals("localhost", newEngine("http://").hostname)
            assertEquals("localhost", newEngine("https:///").hostname)
            assertEquals("443", newEngine("https:///").port)
        }

    @Test
    fun aRelativePathCannotChangeTheAuthority() =
        runTest {
            for (path in listOf("evil.example/socket.io", "@evil.example/socket.io", ".evil.example/socket.io")) {
                val url = firstPollingUrl(EngineOptions(path = path))
                assertEquals("good.example", java.net.URI(url).host, url)
                assertTrue(url.startsWith("https://good.example/$path/?EIO=4&transport=polling"), url)
            }
        }

    @Test
    fun aRelativeTransportPathCannotChangeTheAuthority() =
        runTest {
            val url = firstPollingUrl(EngineOptions(transportOptions = mapOf("polling" to TransportOverrides(path = "@evil.example/x"))))
            assertEquals("good.example", java.net.URI(url).host, url)
            assertTrue(url.startsWith("https://good.example/@evil.example/x/?EIO=4"), url)
        }

    @Test
    fun absoluteAndEmptyPathsAreUnchanged() =
        runTest {
            assertTrue(firstPollingUrl(EngineOptions()).startsWith("https://good.example/engine.io/?EIO=4"))
            assertTrue(firstPollingUrl(EngineOptions(path = "", addTrailingSlash = false)).startsWith("https://good.example?EIO=4"))
            assertTrue(firstPollingUrl(EngineOptions(path = "/", addTrailingSlash = true)).startsWith("https://good.example/?EIO=4"))
        }

    private fun TestScope.firstPollingUrl(options: EngineOptions): String {
        val requests = ArrayList<String>()
        val capture =
            object : EngineHttpClient {
                override fun execute(
                    request: EngineHttpRequest,
                    callback: EngineHttpCallback,
                ): Cancellable {
                    requests.add(request.url)
                    return Cancellable.NONE
                }
            }
        val executor = ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
        val engine =
            EngineSocket(
                "https://good.example",
                options.copy(transports = listOf("polling"), clients = EngineClients(capture, null)),
                executor,
            )
        executor.execute { engine.open() }
        testScheduler.runCurrent()
        executor.shutdown()
        return requests.single()
    }

    @Test
    fun toStringRedactsQueryValues() {
        val printed = EngineUri.parse("https://chat.example.com/socket.io/?token=secret&EIO=4").toString()
        assertEquals(
            "Parsed(protocol=https, host=chat.example.com, port=, path=/socket.io/, query=token=***&EIO=***)",
            printed,
        )
    }

    private fun TestScope.newEngine(uri: String): EngineSocket =
        EngineSocket(uri, EngineOptions(), ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource))
}
