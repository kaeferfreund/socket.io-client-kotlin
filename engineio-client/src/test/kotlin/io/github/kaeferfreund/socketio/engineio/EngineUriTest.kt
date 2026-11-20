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

    private fun TestScope.newEngine(uri: String): EngineSocket =
        EngineSocket(uri, EngineOptions(), ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource))
}
