package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Acknowledgements in both directions, timeouts and cancellation, on virtual time. */
class SocketAckTest {
    @Test
    fun emitWithAckReturnsEveryAcknowledgementArgument() =
        runClientTest {
            val h = clientHarness { namespace("/").onConnection { c -> c.on("pair") { _, ack -> ack!!(listOf(1, "two")) } } }
            val socket = h.manager().socket()
            h.settle()
            val reply = async { socket.emitWithAck("pair") }
            h.settle()
            assertEquals(listOf(SocketIOValue.of(1), SocketIOValue.of("two")), reply.await())
            h.close()
        }

    @Test
    fun cancellingEmitWithAckWithdrawsTheAckAndTheBufferedPacket() =
        runClientTest {
            val h = clientHarness()
            val manager = h.manager { autoConnect = false }
            val socket = manager.socket()
            val call = async { socket.emitWithAck("echo", "a") }
            h.settle()
            assertEquals(1, socket.pendingEmits.value)
            assertEquals(1, socket.pendingAcknowledgements.value)
            call.cancel()
            h.settle()
            assertEquals(0, socket.pendingEmits.value)
            assertEquals(0, socket.pendingAcknowledgements.value)
            socket.connect()
            h.settle()
            assertTrue(h.server.received.none { it.type == SocketIOPacketType.EVENT })
            h.close()
        }

    @Test
    fun cancellingBeforeRegistrationNeverSendsThePacket() =
        runClientTest {
            val h = clientHarness()
            val socket = h.manager().socket()
            h.settle()
            // Cancelled before the protocol executor ran the emit: nothing may be sent or left waiting.
            val call = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { socket.emitWithAck("echo", "a") }
            call.cancel()
            h.settle()
            assertTrue(h.server.received.none { it.type == SocketIOPacketType.EVENT })
            assertEquals(0, socket.pendingAcknowledgements.value)
            h.close()
        }

    @Test
    fun aTimedOutBufferedEmitLeavesTheSendBuffer() =
        runClientTest {
            val h = clientHarness()
            val socket = h.manager { autoConnect = false }.socket()
            var result: Result<*>? = null
            socket.timeout(50.milliseconds).emit("event") { result = it }
            h.settle()
            assertEquals(1, socket.pendingEmits.value)
            advanceTimeBy(51.milliseconds)
            assertTrue(result!!.exceptionOrNull() is AckTimeoutException)
            assertEquals(0, socket.pendingEmits.value)
            assertTrue(socket.sendBufferSnapshot().isEmpty())
            h.close()
        }

    @Test
    fun plainAcknowledgementsSurviveADisconnectOnlyWhileBuffered() =
        runClientTest {
            val h = clientHarness()
            val socket = h.manager().socket()
            h.settle()
            var called = false
            h.onExecutor(socket.manager) {
                socket.emit("never-answered") { called = true }
                socket.disconnect()
            }
            assertEquals(0, socket.pendingAcknowledgements.value)
            assertTrue(!called)
            // Buffered while disconnected: kept, and acknowledged after reconnecting (JS-106).
            var value: String? = null
            socket.emit("echo", "a") { value = it.getOrThrow()[0].string }
            h.settle()
            assertEquals(1, socket.pendingAcknowledgements.value)
            socket.connect()
            h.settle()
            assertEquals("a", value)
            h.close()
        }

    @Test
    fun answersServerAcknowledgementRequestsOnce() =
        runClientTest {
            var answers = ArrayList<List<SocketIOValue>>()
            val h = clientHarness { namespace("/").onConnection { c -> c.emitWithAck("question", 1) { answers.add(it) } } }
            val socket =
                h.manager().socket {
                    on("question") { event ->
                        event.ack?.send("answer", event[0]!!.long)
                        event.ack?.send("ignored")
                    }
                }
            h.settle()
            assertEquals(listOf(listOf(SocketIOValue.of("answer"), SocketIOValue.of(1))), answers)
            socket.disconnect()
            h.close()
        }

    @Test
    fun anAcknowledgementForAnEmitThatWasNotSentYetIsIgnored() =
        runClientTest {
            val sent = ArrayList<String>()
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        session.onMessage = { data ->
                            val text = (data as EngineIOData.Text).value
                            sent += text
                            if (text.startsWith("0")) {
                                // A forged answer for the emit still waiting in the send buffer (ack id 0).
                                session.send("30[\"forged\"]")
                                session.send("0{\"sid\":\"s\"}")
                            }
                        }
                    }
                }
            val socket = h.manager().socket()
            var result: Result<List<SocketIOValue>>? = null
            socket.emit("question") { result = it }
            h.settle()
            assertTrue(socket.connected)
            assertNull(result)
            assertEquals(1, socket.pendingAcknowledgements.value)
            assertEquals(1, sent.count { it.startsWith("20[\"question\"") })
            h.close()
        }

    @Test
    fun anAcknowledgementOfAnEarlierConnectionIsNotSentOnTheNextOne() =
        runClientTest {
            val answers = ArrayList<List<SocketIOValue>>()
            val h = clientHarness { namespace("/").onConnection { c -> c.emitWithAck("question") { answers.add(it) } } }
            val pending = ArrayList<Acknowledgement>()
            val socket = h.manager { reconnectionDelay = 1.seconds }.socket { on("question") { pending += it.ack!! } }
            h.settle()
            h.server.engine.sessions.values
                .last()
                .kill()
            advanceTimeBy(2.seconds)
            assertTrue(socket.connected)
            assertEquals(2, pending.size)
            // The new server session numbers its acknowledgements from 0 again.
            pending[0].send("stale")
            h.settle()
            assertEquals(emptyList<List<SocketIOValue>>(), answers)
            pending[1].send("fresh")
            h.settle()
            assertEquals(listOf(listOf(SocketIOValue.of("fresh"))), answers)
            h.close()
        }

    @Test
    fun anUnknownAckIdIsIgnored() =
        runClientTest {
            val h =
                clientHarness {
                    namespace("/").onConnection { c ->
                        c.write(io.github.kaeferfreund.socketio.parser.SocketIOPacket(SocketIOPacketType.ACK, "/", SocketIOValue.arrayOf(), 99))
                    }
                }
            val socket = h.manager().socket()
            h.settle()
            assertTrue(socket.connected)
            h.close()
        }

    @Test
    fun theDefaultAckTimeoutAppliesToEmitWithAck() =
        runClientTest {
            val h = clientHarness { namespace("/").onConnection { c -> c.on("never") { _, _ -> } } }
            val socket = h.manager().socket(options = SocketOptions { ackTimeout = 1.seconds })
            h.settle()
            val call = async { runCatching { socket.emitWithAck("never") } }
            advanceTimeBy(1001.milliseconds)
            assertTrue(call.await().exceptionOrNull() is AckTimeoutException)
            h.close()
        }

    @Test
    fun rejectsReservedEventNamesAndUnsupportedArgumentsSynchronously() =
        runClientTest {
            val h = clientHarness()
            val socket = h.manager { autoConnect = false }.socket()
            for (name in listOf("connect", "connect_error", "disconnect", "disconnecting", "newListener", "removeListener")) {
                assertEquals("\"$name\" is a reserved event name", assertThrows<IllegalArgumentException> { socket.emit(name) }.message)
            }
            assertThrows<IllegalArgumentException> { socket.emit("x", Any()) }
            assertEquals(0, socket.pendingEmits.value)
            assertNull(socket.id)
            h.close()
        }
}
