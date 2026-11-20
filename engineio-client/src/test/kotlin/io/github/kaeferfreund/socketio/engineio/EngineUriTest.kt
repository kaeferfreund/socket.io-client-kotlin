package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

    private fun TestScope.newEngine(uri: String): EngineSocket =
        EngineSocket(uri, EngineOptions(), ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource))
}
