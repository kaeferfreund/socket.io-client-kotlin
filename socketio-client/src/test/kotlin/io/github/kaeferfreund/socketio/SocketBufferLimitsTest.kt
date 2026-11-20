package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [SocketBufferLimits]: explicit, never silent, never evicting. */
class SocketBufferLimitsTest {
    @Test
    fun theSendBufferByteLimitRejectsTheNewEmit() =
        runClientTest {
            val h = clientHarness()
            val errors = ArrayList<Throwable>()
            val socket =
                h.manager(setup = { on<ManagerEvent.Error> { errors += it.error } }) {
                    autoConnect = false
                    bufferLimits = SocketBufferLimits(maxSendBufferBytes = 100)
                }.socket()
            var rejected: Throwable? = null
            socket.emit("small", "x")
            socket.emit("large", "y".repeat(200)) { rejected = it.exceptionOrNull() }
            h.settle()
            val error = rejected as SocketBufferLimitException
            assertEquals(SocketBufferLimitException.Buffer.SEND_BUFFER, error.buffer)
            assertTrue(error.measuringBytes)
            assertEquals(error, errors.single())
            assertEquals(1, socket.pendingEmits.value)
        }

    @Test
    fun aRejectedEmitIsNotAConnectErrorOfAnySocket() =
        runClientTest {
            val h = clientHarness()
            val manager =
                h.manager {
                    autoConnect = false
                    bufferLimits = SocketBufferLimits(maxSendBufferPackets = 1)
                }
            val connectErrors = ArrayList<Throwable>()
            val managerErrors = ArrayList<Throwable>()
            manager.on<ManagerEvent.Error> { managerErrors += it.error }
            val chat = manager.socket("/chat") { onConnectError { connectErrors += it } }
            manager.socket("/news") { onConnectError { connectErrors += it } }.connect()
            chat.connect()
            chat.emit("a")
            chat.emit("b")
            h.settle()
            assertTrue(managerErrors.any { it is SocketBufferLimitException }, "$managerErrors")
            assertTrue(connectErrors.none { it is SocketBufferLimitException }, "$connectErrors")
            h.close()
        }

    @Test
    fun theRetryQueueLimitRejectsTheNewEmit() =
        runClientTest {
            val h = clientHarness()
            val socket =
                h.manager {
                    autoConnect = false
                    bufferLimits = SocketBufferLimits(maxRetryQueuePackets = 1)
                }.socket(options = SocketOptions { retries = 2 })
            var rejected: Throwable? = null
            socket.emit("first")
            socket.emit("second") { rejected = it.exceptionOrNull() }
            h.settle()
            assertEquals(SocketBufferLimitException.Buffer.RETRY_QUEUE, (rejected as SocketBufferLimitException).buffer)
            assertEquals(1, socket.pendingEmits.value)
        }

    @Test
    fun anOverfullReceiveBufferClosesTheConnection() =
        runClientTest {
            // Events that arrive before the namespace CONNECT is processed are buffered.
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        session.onMessage = { data ->
                            if ((data as io.github.kaeferfreund.socketio.engineio.parser.EngineIOData.Text).value.startsWith("0")) {
                                repeat(3) { session.send("2[\"early\",$it]") }
                                session.send("0{\"sid\":\"s\"}")
                            }
                        }
                    }
                }
            val reasons = ArrayList<DisconnectReason>()
            val manager =
                h.manager(setup = { on<ManagerEvent.Close> { reasons += it.reason } }) {
                    bufferLimits = SocketBufferLimits(maxReceiveBufferPackets = 2)
                    reconnection = false
                }
            manager.socket()
            h.settle()
            assertEquals(listOf(DisconnectReason.TRANSPORT_ERROR), reasons)
        }

    @Test
    fun aBinaryPacketMissingAttachmentsPastTheDeadlineIsAParseError() =
        runClientTest {
            val h = clientHarness()
            val reasons = ArrayList<DisconnectReason>()
            val manager =
                h.manager(setup = { on<ManagerEvent.Close> { reasons += it.reason } }) {
                    bufferLimits = SocketBufferLimits(binaryReconstructionTimeout = 1.seconds)
                    reconnection = false
                }
            manager.socket()
            h.settle()
            h.server.engine.sessions.values.single().send("51-[\"bin\",{\"_placeholder\":true,\"num\":0}]")
            h.settle()
            advanceTimeBy(999.milliseconds)
            assertTrue(reasons.isEmpty())
            advanceTimeBy(2.milliseconds)
            assertEquals(listOf(DisconnectReason.PARSE_ERROR), reasons)
        }

    @Test
    fun receiveBufferedEventsAreDeliveredAfterConnect() =
        runClientTest {
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        session.onMessage = { data ->
                            if ((data as io.github.kaeferfreund.socketio.engineio.parser.EngineIOData.Text).value.startsWith("0")) {
                                session.send("2[\"early\",1]")
                                session.send("0{\"sid\":\"s\"}")
                            }
                        }
                    }
                }
            val order = ArrayList<String>()
            h.manager().socket {
                on("early") { order += "early:" + (if (connected) "connected" else "not connected") }
                onConnect { order += "connect" }
            }
            h.settle()
            // JavaScript: buffered events are emitted before "connect", with `connected` already true.
            assertEquals(listOf("early:connected", "connect"), order)
            SocketIOPacket(SocketIOPacketType.EVENT, "/", SocketIOValue.arrayOf("x")).hashCode()
        }

    @Test
    fun emptyEventsStillCountAgainstTheReceiveBufferByteLimit() =
        runClientTest {
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        session.onMessage = { data ->
                            if ((data as EngineIOData.Text).value.startsWith("0")) repeat(100) { session.send("2[\"\",\"\"]") }
                        }
                    }
                }
            val errors = ArrayList<Throwable>()
            val manager =
                h.manager(setup = { on<ManagerEvent.Error> { errors += it.error } }) {
                    bufferLimits = SocketBufferLimits(maxReceiveBufferBytes = 64)
                    reconnection = false
                }
            manager.socket()
            h.settle()
            // Events already decoded from the same payload may overflow again after the close.
            val error = errors.first() as SocketBufferLimitException
            assertEquals(SocketBufferLimitException.Buffer.RECEIVE_BUFFER, error.buffer)
            assertTrue(error.measuringBytes)
        }

    @Test
    fun eventsBufferedOnAClosedConnectionAreNotDeliveredOnTheNextOne() =
        runClientTest {
            var connections = 0
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        val n = ++connections
                        session.onMessage = { data ->
                            if ((data as EngineIOData.Text).value.startsWith("0")) {
                                // The first connection only sends an event and never answers the CONNECT.
                                if (n == 1) session.send("2[\"stale\"]") else session.send("0{\"sid\":\"s$n\"}")
                            }
                        }
                    }
                }
            val received = ArrayList<String>()
            val socket = h.manager { reconnectionDelay = 1.seconds }.socket { onAny { name, _ -> received += name } }
            h.settle()
            assertEquals(listOf("stale"), socket.receiveBufferSnapshot().map { it.name })
            h.server.engine.sessions.values
                .last()
                .kill()
            advanceTimeBy(2.seconds)
            assertTrue(socket.connected)
            assertEquals(emptyList<String>(), received)
        }

    @Test
    fun rejectsInvalidLimits() {
        assertThrows<IllegalArgumentException> { SocketBufferLimits(maxSendBufferPackets = 0) }
        assertThrows<IllegalArgumentException> { SocketBufferLimits(maxRetryQueueBytes = -1) }
        assertThrows<IllegalArgumentException> { SocketBufferLimits(binaryReconstructionTimeout = kotlin.time.Duration.ZERO) }
    }
}
