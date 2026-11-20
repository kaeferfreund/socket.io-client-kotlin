package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Socket behaviour the upstream suites do not reach: server-side disconnects, error shapes, byte limits and API aliases. */
class SocketApiTest {
    @Test
    fun aServerDisconnectEndsTheSessionWithoutReconnecting() =
        runClientTest {
            val h = clientHarness()
            val reasons = ArrayList<DisconnectReason>()
            val manager = h.manager()
            val socket = manager.socket("/") { onDisconnect { reason, _ -> reasons += reason } }
            h.settle()
            h.server.namespace("/").clients.single().disconnect()
            h.settle()
            assertEquals(listOf(DisconnectReason.IO_SERVER_DISCONNECT), reasons)
            assertFalse(DisconnectReason.IO_SERVER_DISCONNECT.reconnectsAutomatically)
            assertFalse(socket.active)
            assertTrue(socket.disconnected)
            advanceTimeBy(60.seconds)
            assertFalse(socket.connected)
            // The application decides to come back.
            socket.open()
            h.settle()
            assertTrue(socket.connected)
            h.close()
        }

    // A server sends CONNECT_ERROR (and the empty v2.x CONNECT) only as the answer to a CONNECT.
    // An unrequested one used to destroy a connected socket while it still counted as connected,
    // so connect() did nothing any more.
    @Test
    fun anUnrequestedConnectErrorOrEmptyConnectOnAConnectedSocketIsIgnored() =
        runClientTest {
            val h = clientHarness()
            val errors = ArrayList<Throwable>()
            val socket = h.manager().socket("/") { onConnectError { errors += it } }
            h.settle()
            assertTrue(socket.connected)
            val id = socket.id
            val session = h.server.engine.sessions.values.single()
            session.send("4{\"message\":\"denied\"}")
            session.send("0")
            h.settle()
            assertEquals(emptyList<Throwable>(), errors)
            assertTrue(socket.connected)
            assertTrue(socket.active)
            assertEquals(id, socket.id)
            h.close()
        }

    // A string CONNECT_ERROR payload is the message. As in socket.io-parser, `null` is not a
    // valid CONNECT_ERROR payload (isObject(null) is false): it is a parse error, not a refusal.
    @Test
    fun aTextConnectErrorIsTheMessageAndANullOneIsAParseError() =
        runClientTest {
            val payloads = ArrayDeque(listOf("4\"not allowed\"", "4null"))
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        val reply = payloads.removeFirst()
                        session.onMessage = { data -> if ((data as EngineIOData.Text).value.startsWith("0")) session.send(reply) }
                    }
                }
            val errors = ArrayList<SocketConnectException>()
            val closes = ArrayList<DisconnectReason>()
            val manager = h.manager(setup = { on<ManagerEvent.Close> { closes += it.reason } }) { reconnection = false }
            val socket = manager.socket("/") { onConnectError { errors += it as SocketConnectException } }
            h.settle()
            assertEquals(listOf("not allowed"), errors.map { it.message })
            assertTrue(errors.single().isServerRefusal)
            assertNull(errors.single().data)
            manager.disconnect()
            h.settle()
            closes.clear()
            socket.connect()
            h.settle()
            assertEquals(1, errors.size)
            assertEquals(listOf(DisconnectReason.PARSE_ERROR), closes)
            h.close()
        }

    @Test
    fun theReceiveBufferByteLimitClosesTheConnection() =
        runClientTest {
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        session.onMessage = { data ->
                            if ((data as EngineIOData.Text).value.startsWith("0")) {
                                session.send("2[\"early\",[\"${"x".repeat(100)}\"]]")
                                session.send("0{\"sid\":\"s\"}")
                            }
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
            val error = errors.single() as SocketBufferLimitException
            assertEquals(SocketBufferLimitException.Buffer.RECEIVE_BUFFER, error.buffer)
            assertTrue(error.measuringBytes)
            assertEquals(64, error.limit)
            h.close()
        }

    @Test
    fun theRetryQueueByteLimitRejectsTheNewEmit() =
        runClientTest {
            val h = clientHarness()
            val socket =
                h.manager {
                    autoConnect = false
                    bufferLimits = SocketBufferLimits(maxRetryQueueBytes = 100)
                }.socket(options = SocketOptions { retries = 2 })
            var rejected: Throwable? = null
            socket.emit("small", "a")
            socket.emit("large", listOf(mapOf("text" to "y".repeat(100)))) { rejected = it.exceptionOrNull() }
            h.settle()
            val error = rejected as SocketBufferLimitException
            assertEquals(SocketBufferLimitException.Buffer.RETRY_QUEUE, error.buffer)
            assertTrue(error.measuringBytes)
            assertEquals(1, socket.pendingEmits.value)
            h.close()
        }

    @Test
    fun anIncomingAcknowledgementReportsWhetherItWasSent() =
        runClientTest {
            val h = clientHarness()
            val acks = ArrayList<Acknowledgement>()
            val socket = h.manager().socket("/") { on("question") { event -> acks += event.ack!! } }
            h.settle()
            val answers = ArrayList<List<SocketIOValue>>()
            h.server.namespace("/").clients.single().emitWithAck("question", 1) { answers += it }
            h.settle()
            val ack = acks.single()
            assertFalse(ack.isSent)
            ack.send("answer")
            ack.send("ignored second answer")
            h.settle()
            assertTrue(ack.isSent)
            assertEquals(listOf(listOf(SocketIOValue.Text("answer"))), answers)
            // socket.io-client-java#567: a string that looks like JSON stays a string.
            h.server.namespace("/").clients.single().emitWithAck("question", 2) { answers += it }
            h.settle()
            acks.last().send("{\"data\":[]}")
            h.settle()
            assertEquals(listOf(SocketIOValue.Text("{\"data\":[]}")), answers.last())
            assertTrue(socket.connected)
            h.close()
        }

    @Test
    fun sendIsAMessageEventAndOpenIsConnect() =
        runClientTest {
            val h = clientHarness()
            val socket = h.manager { autoConnect = false }.socket("/")
            assertTrue(socket.disconnected)
            socket.open()
            h.settle()
            assertTrue(socket.connected)
            assertFalse(socket.disconnected)
            socket.send("hello", 2)
            h.settle()
            val (name, args) = h.server.namespace("/").clients.single().events.single()
            assertEquals("message", name)
            assertEquals(listOf(SocketIOValue.Text("hello"), SocketIOValue.of(2)), args)
            assertTrue(socket.toString().startsWith("Socket(namespace=/, state=Connected"), socket.toString())
            h.close()
        }

    @Test
    fun removingAllListenersSilencesEveryEvent() =
        runClientTest {
            val h = clientHarness()
            val seen = ArrayList<String>()
            val socket =
                h.manager().socket("/") {
                    on("hi") { seen += "named" }
                    onAny { name, _ -> seen += "any $name" }
                    onAnyOutgoing { name, _ -> seen += "out $name" }
                }
            h.settle()
            socket.emit("hi")
            h.settle()
            assertEquals(listOf("out hi", "any hi", "named"), seen)
            seen.clear()
            socket.removeAllListeners().offAny().offAnyOutgoing()
            assertTrue(socket.listenersAny().isEmpty())
            assertTrue(socket.listenersAnyOutgoing().isEmpty())
            socket.emit("hi")
            h.settle()
            assertTrue(seen.isEmpty())
            h.close()
        }

    @Test
    fun theReceiveBufferSnapshotShowsEventsWaitingForConnect() =
        runClientTest {
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        session.onMessage = { data ->
                            if ((data as EngineIOData.Text).value.startsWith("0")) session.send("2[\"early\",1]")
                        }
                    }
                }
            val socket = h.manager().socket("/")
            h.settle()
            assertFalse(socket.connected)
            val waiting = socket.receiveBufferSnapshot()
            assertEquals(listOf("early"), waiting.map { it.name })
            h.close()
        }

    @Test
    fun emitterFlagsCombine() =
        runClientTest {
            val h = clientHarness()
            h.server.namespace("/").onConnection { client -> client.on("silent") { _, _ -> } }
            val socket = h.manager().socket("/")
            h.settle()
            val results = ArrayList<Result<List<SocketIOValue>>>()
            socket.compress(false).timeout(100.milliseconds).emit("silent") { results += it }
            socket.timeout(10.seconds).timeout(100.milliseconds).emit("silent") { results += it }
            socket.volatile.compress(false).volatile.emit("hi")
            h.settle()
            advanceTimeBy(101.milliseconds)
            assertEquals(2, results.size)
            assertTrue(results.all { it.exceptionOrNull() is AckTimeoutException })
            h.close()
        }

    @Test
    fun anInterceptorMayImplementOnlyWhatItNeeds() =
        runClientTest {
            val h = clientHarness()
            val sent = ArrayList<String>()
            val quiet = object : OutgoingInterceptor {}
            val counting =
                object : OutgoingInterceptor {
                    override fun onSent(event: OutgoingEvent) {
                        sent += event.name
                    }
                }
            val manager = h.manager { autoConnect = false }
            val a = manager.socket("/a", SocketOptions { outgoingInterceptor = quiet })
            val b = manager.socket("/b", SocketOptions { outgoingInterceptor = counting })
            h.server.namespace("/a")
            h.server.namespace("/b")
            a.emit("buffered")
            b.emit("buffered")
            a.volatile.emit("dropped")
            a.connect()
            b.connect()
            h.settle()
            assertEquals(listOf("buffered"), sent)
            h.close()
        }

    @Test
    fun connectIsAnAliasOfIo() {
        val options = SocketManagerOptions { autoConnect = false }
        val socket = SocketIO.connect("http://alias.test/chat", options)
        try {
            assertEquals("/chat", socket.namespace)
            assertInstanceOf(SocketManager::class.java, socket.manager)
            assertNull(socket.id)
        } finally {
            SocketIO.closeAll()
        }
    }

    // The troubleshooting guide tells users to read the HTTP status of a failed poll (for
    // example a proxy's 502 or an expired session's 400) from the disconnect details.
    @Test
    fun aFailedPollReportsItsHttpStatusInTheDisconnectDetails() =
        runClientTest {
            val h = clientHarness()
            val details = ArrayList<Pair<DisconnectReason, DisconnectDetails?>>()
            val manager =
                h.manager {
                    transports = listOf(Transport.POLLING)
                    reconnection = false
                }
            manager.socket("/") { onDisconnect { reason, detail -> details += reason to detail } }
            h.settle()
            h.server.engine.sessions.values.single().failPendingPoll(502, "bad gateway")
            h.settle()
            val (reason, detail) = details.single()
            assertEquals(DisconnectReason.TRANSPORT_ERROR, reason)
            assertEquals(502, detail!!.statusCode)
            assertEquals("xhr poll error", detail.description)
            h.close()
        }
}
