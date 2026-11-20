package io.github.kaeferfreund.socketio.serialization

import io.github.kaeferfreund.socketio.AckTimeoutException
import io.github.kaeferfreund.socketio.IncomingEvent
import io.github.kaeferfreund.socketio.Socket
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import io.github.kaeferfreund.socketio.testing.FakeSocketIOServer
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The emit, listener and acknowledgement helpers against an in-memory server on virtual time. */
class SocketSerializationTest {
    @Serializable
    data class ChatMessage(
        val author: String,
        val text: String,
        val sentAt: Long = 0,
    )

    @Serializable
    data class Receipt(
        val id: Long,
    )

    @Serializable
    data class Node(
        val next: Node? = null,
    )

    private class Harness(
        val server: FakeSocketIOServer,
        val manager: SocketManager,
        val socket: Socket,
        val listenerErrors: MutableList<Throwable>,
    )

    private fun TestScope.connected(setup: Socket.() -> Unit = {}): Harness {
        val server = FakeSocketIOServer(backgroundScope)
        server.namespace("/").onConnection { client ->
            client.on("echo") { args, ack -> ack?.invoke(args) }
            client.on("receipt") { _, ack -> ack?.invoke(listOf(mapOf("id" to 7))) }
            client.on("silent") { _, _ -> }
            client.on("empty") { _, ack -> ack?.invoke(emptyList()) }
        }
        val errors = ArrayList<Throwable>()
        val manager =
            SocketManager(
                "http://fake.test",
                SocketManagerOptions {
                    clients = server.clients
                    dispatcher = StandardTestDispatcher(testScheduler)
                    timeSource = testScheduler.timeSource
                    listenerErrorHandler = { errors += it }
                },
            )
        val socket = manager.socket("/") { setup() }
        runCurrent()
        assertTrue(socket.connected)
        return Harness(server, manager, socket, errors)
    }

    private fun serializationTest(block: suspend TestScope.(MutableList<SocketManager>) -> Unit) =
        runTest {
            val managers = ArrayList<SocketManager>()
            try {
                block(managers)
            } finally {
                managers.forEach(SocketManager::close)
                runCurrent()
            }
        }

    // JavaScript's emitWithAck resolves with undefined for an acknowledgement without arguments.
    @Test
    fun anAcknowledgementWithoutArgumentsDecodesAsNull() =
        serializationTest { managers ->
            val h = connected().also { managers += it.manager }
            val reply = async { h.socket.emitSerializableWithAck<String, Receipt?>("empty", "x") }
            runCurrent()
            assertEquals(null, reply.await())
        }

    @Test
    fun emitSerializableSendsTheEncodedObject() =
        serializationTest { managers ->
            val h = connected().also { managers += it.manager }
            h.socket.emitSerializable("chat", ChatMessage("ada", "Hello", 1_700_000_000_000))
            runCurrent()
            val (name, args) = h.server.namespace("/").clients.single().events.single()
            assertEquals("chat", name)
            assertEquals(SocketIOValue.of(mapOf("author" to "ada", "text" to "Hello", "sentAt" to 1_700_000_000_000L)), args.single())
        }

    @Test
    fun theGivenJsonInstanceIsUsed() =
        serializationTest { managers ->
            val h = connected().also { managers += it.manager }
            val withDefaults = Json { encodeDefaults = true }
            h.socket.emitSerializable("chat", ChatMessage("ada", "Hi"), withDefaults)
            runCurrent()
            val args = h.server.namespace("/").clients.single().events.single().second
            assertEquals(SocketIOValue.of(mapOf("author" to "ada", "text" to "Hi", "sentAt" to 0)), args.single())
        }

    @Test
    fun emitSerializableWithAckDecodesTheFirstAcknowledgementArgument() =
        serializationTest { managers ->
            val h = connected().also { managers += it.manager }
            val echoed: ChatMessage = h.socket.emitSerializableWithAck("echo", ChatMessage("ada", "round trip"))
            assertEquals(ChatMessage("ada", "round trip"), echoed)
            val receipt: Receipt = h.socket.emitSerializableWithAck("receipt", ChatMessage("ada", "x"))
            assertEquals(Receipt(7), receipt)
        }

    @Test
    fun anUndecodableAcknowledgementFailsTheCall() =
        serializationTest { managers ->
            val h = connected().also { managers += it.manager }
            assertThrows<SerializationException> {
                val wrong: Receipt = h.socket.emitSerializableWithAck("echo", ChatMessage("ada", "not a receipt"))
                println(wrong)
            }
            assertTrue(h.socket.connected)
        }

    @Test
    fun theEmitterVariantsKeepTheirFlags() =
        serializationTest { managers ->
            val h = connected().also { managers += it.manager }
            val receipt: Receipt = h.socket.timeout(1.seconds).emitSerializableWithAck("receipt", ChatMessage("ada", "x"))
            assertEquals(Receipt(7), receipt)
            assertThrows<AckTimeoutException> {
                val never: Receipt = h.socket.timeout(500.milliseconds).emitSerializableWithAck("silent", ChatMessage("ada", "x"))
                println(never)
            }
            h.socket.compress(false).emitSerializable("chat", ChatMessage("ada", "plain"))
            runCurrent()
            val events = h.server.namespace("/").clients.single().events
            // The default Json omits default values (sentAt = 0).
            assertEquals(SocketIOValue.of(mapOf("author" to "ada", "text" to "plain")), events.last { it.first == "chat" }.second.single())
        }

    @Test
    fun onSerializableDecodesTheFirstArgumentAndPassesTheEvent() =
        serializationTest { managers ->
            val received = ArrayList<Pair<ChatMessage, IncomingEvent>>()
            val h = connected { onSerializable<ChatMessage>("chat") { message, event -> received += message to event } }.also { managers += it.manager }
            h.server.namespace("/").emit("chat", mapOf("author" to "bob", "text" to "hey", "extra" to true), "second")
            runCurrent()
            val (message, event) = received.single()
            assertEquals(ChatMessage("bob", "hey"), message)
            assertEquals("chat", event.name)
            assertEquals("second", event.args[1].string)
        }

    @Test
    fun anUndecodablePayloadGoesToOnErrorAndLaterEventsStillArrive() =
        serializationTest { managers ->
            val received = ArrayList<ChatMessage>()
            val failures = ArrayList<Pair<IncomingEvent, Exception>>()
            val h =
                connected {
                    onSerializable<ChatMessage>("chat", onError = { event, error -> failures += event to error }) { message, _ -> received += message }
                }.also { managers += it.manager }
            val namespace = h.server.namespace("/")
            namespace.emit("chat", mapOf("author" to 1))
            namespace.emit("chat")
            namespace.emit("chat", mapOf("author" to "ok", "text" to "fine"))
            runCurrent()
            assertEquals(listOf(ChatMessage("ok", "fine")), received)
            assertEquals(2, failures.size)
            assertInstanceOf(SerializationException::class.java, failures[0].second)
            // A missing argument decodes from null, which a non-null class rejects.
            assertInstanceOf(SerializationException::class.java, failures[1].second)
            assertTrue(h.listenerErrors.isEmpty())
            assertTrue(h.socket.connected)
        }

    /**
     * kotlinx.serialization decodes recursive classes on the stack; a payload nested
     * deeper than the stack is a payload that does not decode, not a listener failure.
     */
    @Test
    fun aPayloadTooDeepToDecodeGoesToOnError() =
        serializationTest { managers ->
            val received = ArrayList<Node>()
            val failures = ArrayList<Exception>()
            val h =
                connected {
                    onSerializable<Node>("node", onError = { _, error -> failures += error }) { node, _ -> received += node }
                }.also { managers += it.manager }
            var deep: SocketIOValue = SocketIOValue.EMPTY_OBJECT
            repeat(200_000) { deep = SocketIOValue.Object(mapOf("next" to deep)) }
            val namespace = h.server.namespace("/")
            namespace.emit("node", deep)
            namespace.emit("node", mapOf("next" to emptyMap<String, Any>()))
            runCurrent()
            assertEquals(listOf(Node(Node())), received)
            val failure = assertInstanceOf(SerializationException::class.java, failures.single())
            assertInstanceOf(StackOverflowError::class.java, failure.cause)
            assertTrue(h.listenerErrors.isEmpty())
            assertTrue(h.socket.connected)
        }

    @Test
    fun theDefaultOnErrorReachesTheListenerErrorHandler() =
        serializationTest { managers ->
            val received = ArrayList<ChatMessage>()
            val h = connected { onSerializable<ChatMessage>("chat") { message, _ -> received += message } }.also { managers += it.manager }
            h.server.namespace("/").emit("chat", "not an object")
            h.server.namespace("/").emit("chat", mapOf("author" to "ok", "text" to "after"))
            runCurrent()
            assertInstanceOf(IllegalArgumentException::class.java, h.listenerErrors.single())
            assertEquals(listOf(ChatMessage("ok", "after")), received)
            assertTrue(h.socket.connected)
        }

    @Test
    fun theSubscriptionRemovesTheListener() =
        serializationTest { managers ->
            val received = ArrayList<ChatMessage>()
            lateinit var subscription: io.github.kaeferfreund.socketio.Subscription
            val h = connected { subscription = onSerializable<ChatMessage>("chat") { message, _ -> received += message } }.also { managers += it.manager }
            subscription.cancel()
            h.server.namespace("/").emit("chat", mapOf("author" to "a", "text" to "b"))
            runCurrent()
            assertTrue(received.isEmpty())
        }

    @Test
    fun serializableAckDeliversSuccessDecodeFailureAndTimeout() =
        serializationTest { managers ->
            val h = connected().also { managers += it.manager }
            val results = ArrayList<Result<Receipt>>()
            h.socket.emit("receipt", "x", ack = serializableAck<Receipt> { results += it })
            h.socket.emit("echo", "not a receipt", ack = serializableAck<Receipt> { results += it })
            h.socket.timeout(100.milliseconds).emit("silent", ack = serializableAck<Receipt> { results += it })
            runCurrent()
            advanceTimeBy(101.milliseconds)
            assertEquals(3, results.size)
            assertEquals(Receipt(7), results[0].getOrThrow())
            assertInstanceOf(SerializationException::class.java, results[1].exceptionOrNull())
            assertInstanceOf(AckTimeoutException::class.java, results[2].exceptionOrNull())
        }
}
