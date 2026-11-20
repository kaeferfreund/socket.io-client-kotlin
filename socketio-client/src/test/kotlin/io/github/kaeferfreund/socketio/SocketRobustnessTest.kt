package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketClient
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketConnection
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketListener
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketRequest
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** Regressions for review findings: cancellation, recovery reset, isolation and misconfiguration. */
class SocketRobustnessTest {
    /**
     * JavaScript handles a decoded packet in a microtask (`nextTick`), before the
     * next transport event. A CONNECT answer followed at once by the close of the
     * connection must therefore connect and then disconnect, not connect a socket
     * of a closed manager.
     */
    @Test
    fun aPacketDecodedBeforeACloseIsHandledBeforeTheClose() =
        runTest {
            var captured: EngineWebSocketListener? = null
            val webSocket =
                object : EngineWebSocketClient {
                    override fun connect(
                        request: EngineWebSocketRequest,
                        listener: EngineWebSocketListener,
                    ): EngineWebSocketConnection {
                        captured = listener
                        return SilentConnection
                    }
                }
            val manager =
                SocketManager(
                    "http://fake.test",
                    SocketManagerOptions {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        timeSource = testScheduler.timeSource
                        transports = listOf(Transport.WEBSOCKET)
                        reconnection = false
                        clients = EngineClients(null, webSocket)
                    },
                )
            val events = ArrayList<String>()
            val socket =
                manager.socket("/") {
                    onConnect { events += "connect" }
                    onDisconnect { reason, _ -> events += "disconnect ${reason.wireValue}" }
                }
            runCurrent()
            val listener = captured!!
            listener.onOpen(emptyList())
            listener.onMessage("0{\"sid\":\"e\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000}")
            runCurrent()
            // Two transport events queued back to back, as OkHttp delivers them.
            listener.onMessage("40{\"sid\":\"s\"}")
            listener.onClosed(1000, "")
            runCurrent()
            assertEquals(listOf("connect", "disconnect transport close"), events)
            assertTrue(!socket.connected)
            manager.close()
            runCurrent()
        }

    private object SilentConnection : EngineWebSocketConnection {
        override fun send(
            text: String,
            compress: Boolean,
        ) = true

        override fun send(
            bytes: ByteArray,
            compress: Boolean,
        ) = true

        override val queuedBytes: Long get() = 0

        override fun close(
            code: Int,
            reason: String?,
        ) = Unit

        override fun cancel() = Unit
    }

    @Test
    fun cancellingEmitWithAckRemovesTheEmitFromTheRetryQueue() =
        runClientTest {
            val h =
                clientHarness {
                    namespace("/").onConnection { c ->
                        c.on("never") { _, _ -> }
                        c.on("echo") { args, ack -> ack?.invoke(args) }
                    }
                }
            val socket = h.manager().socket(options = SocketOptions { retries = 3 })
            h.settle()
            val first = async { socket.emitWithAck("never", 1) }
            h.settle()
            var second: String? = null
            socket.emit("echo", "next") { second = it.getOrThrow()[0].string }
            h.settle()
            assertEquals(2, socket.pendingEmits.value)
            first.cancel()
            h.settle()
            // The cancelled head left the queue, so the next emit was sent and acknowledged.
            assertEquals("next", second)
            assertEquals(0, socket.pendingEmits.value)
            assertEquals(0, socket.pendingAcknowledgements.value)
            assertEquals(1, h.server.received.count { it.type == SocketIOPacketType.EVENT && it.data?.array?.get(0)?.string == "never" })
        }

    @Test
    fun clearRecoveryStateStartsAFreshSession() =
        runClientTest {
            val h = clientHarness(recovery = true)
            val socket = h.manager { reconnectionDelay = 1.seconds }.socket()
            h.settle()
            val id = socket.id
            socket.clearRecoveryState()
            h.server.engine.sessions.values.last().kill()
            advanceTimeBy(2.seconds)
            assertTrue(socket.connected)
            assertTrue(!socket.recovered)
            assertTrue(id != socket.id)
            val connect = h.server.received.filter { it.type == SocketIOPacketType.CONNECT }.last()
            assertEquals(null, connect.data)
        }

    @Test
    fun clearRecoveryStateDropsTheEmitsThePreviousUserLeftInTheSendBuffer() =
        runClientTest {
            var token = "alice"
            val h = clientHarness()
            val socket = h.manager().socket(options = SocketOptions { authProvider = AuthProvider { mapOf("token" to token) } })
            h.settle()
            assertTrue(socket.connected)
            socket.disconnect()
            val alice = async { runCatching { socket.emitWithAck("echo", "alice-data") } }
            socket.emit("echo", "alice-plain")
            h.settle()
            assertEquals(2, socket.pendingEmits.value)
            // The documented user switch.
            socket.clearRecoveryState()
            token = "bob"
            socket.connect()
            socket.emit("echo", "bob-data")
            h.settle()
            assertTrue(socket.connected)
            assertTrue(alice.await().exceptionOrNull() is SocketDisconnectedException)
            val sent = h.server.received.filter { it.type == SocketIOPacketType.EVENT }.map { it.data?.array?.get(1)?.string }
            assertEquals(listOf("bob-data"), sent)
            assertEquals(0, socket.pendingEmits.value)
            assertEquals(0, socket.pendingAcknowledgements.value)
        }

    @Test
    fun clearRecoveryStateDropsThePreviousUsersRetryQueue() =
        runClientTest {
            val h = clientHarness { namespace("/").onConnection { c -> c.on("never") { _, _ -> } } }
            val socket = h.manager().socket(options = SocketOptions { retries = 2 })
            h.settle()
            val alice = async { runCatching { socket.emitWithAck("never", "alice-retry") } }
            h.settle()
            assertEquals(1, h.server.received.count { it.type == SocketIOPacketType.EVENT })
            socket.disconnect()
            h.settle()
            assertEquals(1, socket.pendingEmits.value)
            socket.clearRecoveryState()
            socket.connect()
            h.settle()
            assertTrue(socket.connected)
            assertTrue(alice.await().exceptionOrNull() is SocketDisconnectedException)
            assertEquals(1, h.server.received.count { it.type == SocketIOPacketType.EVENT })
            assertEquals(0, socket.pendingEmits.value)
            assertEquals(0, socket.pendingAcknowledgements.value)
        }

    @Test
    fun aMissingHttpStackIsAConnectErrorNotACrash() =
        runClientTest {
            val h = clientHarness()
            val errors = ArrayList<Throwable>()
            val manager = h.manager { clients = EngineClients(null, null) }
            manager.socket { onConnectError { errors += it } }
            h.settle()
            assertTrue(errors.single().message!!.contains("EngineHttpClient"), "${errors.single()}")
        }

    @Test
    fun aThrowingInterceptorIsReportedAndDoesNotStopTheEmit() =
        runClientTest {
            val h = clientHarness()
            val reported = ArrayList<Throwable>()
            val interceptor =
                object : OutgoingInterceptor {
                    override fun onSent(event: OutgoingEvent): Unit = error("interceptor bug")
                }
            val socket = h.manager { listenerErrorHandler = { reported += it } }.socket(options = SocketOptions { outgoingInterceptor = interceptor })
            h.settle()
            var acknowledged = false
            socket.emit("echo", 1) { acknowledged = true }
            h.settle()
            assertTrue(acknowledged)
            assertEquals("interceptor bug", reported.single().message)
        }

    @Test
    fun theSetupBlockRunsOnlyWhenTheSocketIsCreated() =
        runClientTest {
            val h = clientHarness()
            val manager = h.manager()
            var runs = 0
            val first = manager.socket("/") { runs++ }
            val second = manager.socket("/") { runs++ }
            h.settle()
            assertTrue(first === second)
            assertEquals(1, runs)
        }

    @Test
    fun aBoundedEventFlowRejectsInvalidCapacities() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { SocketManagerOptions { eventFlowCapacity = 0 } }
        assertEquals(16, SocketManagerOptions { eventFlowCapacity = 16 }.eventFlowCapacity)
    }
}
