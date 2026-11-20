package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.engineio.SocketLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ports of `packages/socket.io-client/test/url.ts` and the manager cache of `index.ts` (`lookup`). */
class SocketIOUrlTest {
    @AfterEach
    fun clearCache() = SocketIO.closeAll()

    // JS-122
    @Test
    fun worksWithoutAUrl() {
        val parsed = SocketIO.url(null, location = SocketIO.BaseLocation("https:", "woot.com", "4005"))
        assertEquals("woot.com", parsed.host)
        assertEquals("https", parsed.protocol)
        assertEquals("4005", parsed.port)
    }

    // JS-123
    @Test
    fun worksWithRelativePaths() {
        val parsed = SocketIO.url("/test", location = SocketIO.BaseLocation("https:", "woot.com", "3000"))
        assertEquals("woot.com", parsed.host)
        assertEquals("https", parsed.protocol)
        assertEquals("3000", parsed.port)
    }

    // JS-124
    @Test
    fun worksWithoutAProtocol() {
        val parsed = SocketIO.url("localhost:3000", location = SocketIO.BaseLocation("http:", "woot.com"))
        assertEquals("localhost", parsed.host)
        assertEquals("3000", parsed.port)
        assertEquals("http", parsed.protocol)
    }

    // JS-125
    @Test
    fun worksWithoutASchema() {
        val parsed = SocketIO.url("//localhost:3000", location = SocketIO.BaseLocation("http:", "woot.com"))
        assertEquals("localhost", parsed.host)
        assertEquals("3000", parsed.port)
        assertEquals("http", parsed.protocol)
    }

    // JS-126
    @Test
    fun forcesPortsForUniqueUrlIds() {
        val id1 = SocketIO.url("http://google.com:80/")
        val id2 = SocketIO.url("http://google.com/")
        val id3 = SocketIO.url("https://google.com/")
        val id4 = SocketIO.url("http://google.com/", "/test")
        assertEquals(id1.id, id2.id)
        assertNotEquals(id1.id, id3.id)
        assertNotEquals(id2.id, id3.id)
        assertNotEquals(id2.id, id4.id)
    }

    // JS-127
    @Test
    fun identifiesTheNamespace() {
        assertEquals("/woot", SocketIO.url("/woot", location = SocketIO.BaseLocation("http:", "woot.com")).path)
        assertEquals("/", SocketIO.url("http://google.com").path)
        assertEquals("/", SocketIO.url("http://google.com/").path)
    }

    // JS-128
    @Test
    fun worksWithIpv6() {
        val parsed = SocketIO.url("http://[::1]")
        assertEquals("http", parsed.protocol)
        assertEquals("::1", parsed.host)
        assertEquals("80", parsed.port)
        assertEquals("http://[::1]:80", parsed.id)
    }

    // JS-129
    @Test
    fun worksWithAnIpv6Location() {
        val parsed = SocketIO.url(null, location = SocketIO.BaseLocation("http:", "[::1]", ""))
        assertEquals("http", parsed.protocol)
        assertEquals("::1", parsed.host)
        assertEquals("80", parsed.port)
        assertEquals("http://[::1]:80", parsed.id)
    }

    // JS-130
    @Test
    fun worksWithACustomPath() {
        val parsed = SocketIO.url("https://woot.com/some-namespace", "/some-path")
        assertEquals("https://woot.com:443/some-path", parsed.id)
        assertEquals("/some-namespace", parsed.path)
    }

    @Test
    fun defaultsToHttpsWithoutALocation() {
        assertEquals("https", SocketIO.url("example.com").protocol)
        assertEquals("443", SocketIO.url("example.com").port)
    }

    @Test
    fun bracketsInTheQueryKeepTheHost() {
        val parsed = SocketIO.url("https://chat.example.com/?ids[]=1")
        assertEquals("chat.example.com", parsed.host)
        assertEquals("443", parsed.port)
        assertEquals("https://chat.example.com:443", parsed.id)
        assertEquals("ids[]=1", parsed.query)
    }

    @Test
    fun mixedCaseSchemesAreRecognised() {
        val parsed = SocketIO.url("HTTPS://example.com/chat")
        assertEquals("https", parsed.protocol)
        assertEquals("example.com", parsed.host)
        assertEquals("443", parsed.port)
        assertEquals("/chat", parsed.path)
        assertEquals("https://example.com:443", parsed.id)
        assertEquals("https://example.com/chat", parsed.source)
        assertEquals("ws", SocketIO.url("Ws://example.com", location = SocketIO.BaseLocation("https:", "woot.com")).protocol)
    }

    @Test
    fun theHostIsTheOneAWhatwgParserSees() {
        assertEquals("https://good.example:443", SocketIO.url("https://good.example\\@evil.example/").id)
        assertEquals("https://good.example:443", SocketIO.url("https://evil.example:x:y@good.example/").id)
    }

    @Test
    fun slashesAndBackslashesAfterTheSchemeAreSkippedAsInWhatwgUrls() {
        for (uri in listOf("http:///good.example/chat", "http:/good.example/chat", "http:good.example/chat", "HTTP:\\\\good.example/chat")) {
            val parsed = SocketIO.url(uri)
            assertEquals("http", parsed.protocol, uri)
            assertEquals("good.example", parsed.host, uri)
            assertEquals("80", parsed.port, uri)
            assertEquals("/chat", parsed.path, uri)
            assertEquals("http://good.example:80", parsed.id, uri)
            assertEquals("http://good.example/chat", parsed.source, uri)
        }
        assertEquals("wss://good.example:443", SocketIO.url("wss://\\good.example").id)
        assertEquals("https://good.example:443", SocketIO.url("http:pass@good.example").id)
        assertEquals("https://http:8080", SocketIO.url("http:8080").id)
        for (uri in listOf(
            "http:\\\\user@good.example",
            "http:\\\\good.example\\@evil.example/",
            "http:good.example?a=@b",
            "http:good.example#a@b",
            "http:\\good.example?token=a@b",
        )) {
            assertEquals("http://good.example:80", SocketIO.url(uri).id, uri)
        }
    }

    private fun lazyOptions(block: SocketManagerOptions.Builder.() -> Unit = {}) =
        SocketManagerOptions {
            autoConnect = false
            block()
        }

    // JS-004
    @Test
    fun twoSocketsForTheSameNamespaceGetTwoConnections() {
        val s1 = SocketIO.io("http://localhost:3210/", lazyOptions())
        val s2 = SocketIO.io("http://localhost:3210/", lazyOptions())
        assertNotSame(s1.manager, s2.manager)
    }

    // JS-005
    @Test
    fun differentQueryStringsOnTheSameNamespaceGetTwoConnections() {
        val s1 = SocketIO.io("http://localhost:3210/?woot", lazyOptions())
        val s2 = SocketIO.io("http://localhost:3210/", lazyOptions())
        assertNotSame(s1.manager, s2.manager)
        assertEquals(mapOf("woot" to "undefined"), s1.manager.options.engine.query)
    }

    // JS-006
    @Test
    fun differentPathsGetTwoConnections() {
        val s1 = SocketIO.io("http://localhost:3210/", lazyOptions { path = "/foo" })
        val s2 = SocketIO.io("http://localhost:3210/", lazyOptions { path = "/bar" })
        assertNotSame(s1.manager, s2.manager)
    }

    // JS-007
    @Test
    fun differentNamespacesShareOneConnection() {
        val options = lazyOptions()
        val s1 = SocketIO.io("http://localhost:3210/foo", options)
        val s2 = SocketIO.io("http://localhost:3210/bar", options)
        assertSame(s1.manager, s2.manager)
        assertEquals("/foo", s1.namespace)
        assertEquals("/bar", s2.namespace)
        val forced = SocketIO.io("http://localhost:3210/baz", lazyOptions { forceNew = true })
        assertNotSame(s1.manager, forced.manager)
        val unmultiplexed = SocketIO.io("http://localhost:3210/qux", lazyOptions { multiplex = false })
        assertNotSame(s1.manager, unmultiplexed.manager)
    }

    @Test
    fun reusingACachedManagerWithOtherOptionsLogsAWarning() {
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger =
            object : SocketLogger {
                override fun isLoggable(level: LogLevel) = true

                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    error: Throwable?,
                ) {
                    if (level == LogLevel.WARN) warnings += message
                }
            }
        val shared = lazyOptions { this.logger = logger }
        val first = SocketIO.io("http://localhost:3211/a", shared)
        val second = SocketIO.io("http://localhost:3211/b", shared)
        assertSame(first.manager, second.manager)
        assertEquals(emptyList<String>(), warnings)
        // Reuse is kept (JavaScript `lookup()`), but options of a later call are not applied: say so.
        val third = SocketIO.io("http://localhost:3211/c", lazyOptions { this.logger = logger })
        assertSame(first.manager, third.manager)
        assertEquals(1, warnings.size, "$warnings")
        assertTrue("http://localhost:3211" in warnings.single(), warnings.single())
        first.manager.close()
    }

    // A later call that passes neither options nor a query has nothing that could be ignored.
    @Test
    fun reusingACachedManagerWithoutOptionsOrQueryLogsNoWarning() {
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger =
            object : SocketLogger {
                override fun isLoggable(level: LogLevel) = true

                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    error: Throwable?,
                ) {
                    if (level == LogLevel.WARN) warnings += message
                }
            }
        // The creator's query came from its URL; the later call has none.
        val first = SocketIO.io("http://localhost:3213/?token=a", lazyOptions { this.logger = logger })
        val second = SocketIO.io("http://localhost:3213/admin")
        assertSame(first.manager, second.manager)
        // The creator passed its own options; the later call none.
        val third = SocketIO.io("http://localhost:3214/", lazyOptions { this.logger = logger })
        val fourth = SocketIO.io("http://localhost:3214/admin")
        assertSame(third.manager, fourth.manager)
        assertEquals(emptyList<String>(), warnings)
        // A query of the later call that the cached manager does not have is still reported.
        val fifth = SocketIO.io("http://localhost:3214/other?token=b")
        assertSame(third.manager, fifth.manager)
        assertEquals(1, warnings.size, "$warnings")
        first.manager.close()
        third.manager.close()
    }

    @Test
    fun aClosedCachedManagerIsReplaced() {
        val first = SocketIO.io("http://localhost:3212/a", lazyOptions())
        first.manager.close()
        val second = SocketIO.io("http://localhost:3212/a", lazyOptions())
        assertNotSame(first.manager, second.manager)
        second.manager.close()
    }
}
