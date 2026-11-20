package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A closed polling transport ignores the outcome of POSTs it sent before closing. */
@OptIn(InternalSocketIOApi::class)
class PollingTransportCloseTest {
    /** HTTP stack that can answer a request even after it was cancelled, as a racing response does. */
    private class RacingHttpClient : EngineHttpClient {
        class Call(
            val request: EngineHttpRequest,
            val callback: EngineHttpCallback,
        ) {
            var cancelled = false
        }

        val calls = ArrayList<Call>()

        override fun execute(
            request: EngineHttpRequest,
            callback: EngineHttpCallback,
        ): Cancellable {
            val call = Call(request, callback)
            calls += call
            return Cancellable {
                call.cancelled = true
                // OkHttp reports a cancelled call as a failure.
                callback.onFailure(java.io.IOException("Canceled"))
            }
        }

        fun last(method: String): Call = calls.last { it.request.method == method }
    }

    private class Setup(
        val test: TestScope,
        val http: RacingHttpClient,
        val executor: ProtocolExecutor,
        val transport: PollingTransport,
    ) {
        val events = ArrayList<TransportEvent>()

        fun run(block: () -> Unit) {
            executor.execute(block)
            test.runCurrent()
        }
    }

    private fun TestScope.openTransport(): Setup {
        val http = RacingHttpClient()
        val executor = ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
        val transport = PollingTransport(options(http, executor))
        val setup = Setup(this, http, executor, transport)
        transport.events.on(TransportEvent::class.java) { setup.events += it }
        setup.run { transport.open() }
        setup.run { http.last("GET").callback.onResponse(EngineHttpResponse(200, HANDSHAKE, emptyList())) }
        assertEquals(TransportState.OPEN, transport.readyState)
        return setup
    }

    private fun options(
        http: EngineHttpClient,
        executor: ProtocolExecutor,
    ) = TransportOptions(
        hostname = "localhost",
        secure = false,
        port = "80",
        path = "/engine.io/",
        query = mapOf("EIO" to "4"),
        timestampRequests = null,
        timestampParam = "t",
        forceBase64 = false,
        extraHeaders = emptyMap(),
        requestTimeout = null,
        withCredentials = false,
        protocols = emptyList(),
        perMessageDeflateThreshold = null,
        maxPollingResponseBytes = Long.MAX_VALUE,
        executor = executor,
        cookieJar = null,
        httpClient = http,
        webSocketClient = null,
        logger = SocketLogger.NONE,
    )

    @Test
    fun aPostSucceedingAfterACloseDuringThePauseLeavesTheTransportClosed() =
        runTest {
            val s = openTransport()
            s.run { s.transport.send(listOf(EngineIOPacket(EngineIOPacketType.MESSAGE, "hi"))) }
            val post = s.http.last("POST")
            var paused = false
            s.run { s.transport.pause { paused = true } }
            assertEquals(TransportState.PAUSING, s.transport.readyState)
            // The poll completes, so the pause only waits for the write.
            s.run { s.http.last("GET").callback.onResponse(EngineHttpResponse(200, "6", emptyList())) }
            s.run { s.transport.close() }
            assertEquals(TransportState.CLOSED, s.transport.readyState)
            s.events.clear()
            s.run { post.callback.onResponse(EngineHttpResponse(200, "ok", emptyList())) }
            assertEquals(TransportState.CLOSED, s.transport.readyState)
            assertTrue(!paused, "a closed transport finished its pause")
            assertEquals(emptyList<TransportEvent>(), s.events)
            // Only this test's listener is left: the pause no longer waits for a drain.
            assertEquals(1, s.transport.events.listenerCount(TransportEvent::class.java), "pause listeners outlived the close")
            s.executor.shutdown()
        }

    @Test
    fun aPostCancelledByTheCloseReportsNoError() =
        runTest {
            val s = openTransport()
            s.run { s.transport.send(listOf(EngineIOPacket(EngineIOPacketType.MESSAGE, "hi"))) }
            val post = s.http.last("POST")
            s.events.clear()
            s.run { s.transport.close() }
            assertTrue(post.cancelled)
            assertEquals(TransportState.CLOSED, s.transport.readyState)
            assertEquals(emptyList<TransportEvent.Error>(), s.events.filterIsInstance<TransportEvent.Error>())
            s.executor.shutdown()
        }

    private companion object {
        const val HANDSHAKE = "0{\"sid\":\"s\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000,\"maxPayload\":1000000}"
    }
}
