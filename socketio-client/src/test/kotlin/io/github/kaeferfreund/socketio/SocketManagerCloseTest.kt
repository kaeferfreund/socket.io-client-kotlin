package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpClient
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineState
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** `close()` is final: nothing that waits on the manager may be left hanging, and nothing is cut short. */
class SocketManagerCloseTest {
    @Test
    fun closingFailsAcknowledgementsThatCanNoLongerArrive() =
        runClientTest {
            val h = clientHarness()
            val manager = h.manager { autoConnect = false }
            val socket = manager.socket("/", SocketOptions { ackTimeout = 5.seconds })
            val queued = manager.socket("/queued", SocketOptions { retries = 2 })
            val buffered = backgroundScope.async { runCatching { socket.emitWithAck("echo", 1) } }
            val retried = backgroundScope.async { runCatching { queued.emitWithAck("echo", 2) } }
            val callbacks = ArrayList<Result<List<SocketIOValue>>>()
            socket.timeout(5.seconds).emit("echo", 3) { callbacks += it }
            h.settle()
            assertEquals(2, socket.pendingEmits.value)
            manager.close()
            h.settle()
            assertTrue(buffered.await().exceptionOrNull() is SocketDisconnectedException)
            assertTrue(retried.await().exceptionOrNull() is SocketDisconnectedException)
            assertTrue(callbacks.single().exceptionOrNull() is SocketDisconnectedException)
            assertEquals(0, socket.pendingEmits.value)
            assertEquals(0, socket.pendingAcknowledgements.value)
        }

    @Test
    fun closingSendsTheLastPacketsBeforeTheExecutorStops() =
        runClientTest {
            val h = clientHarness(upgrades = emptyList())
            val manager = h.manager()
            val socket = manager.socket("/")
            h.settle()
            assertTrue(socket.connected)
            val session = h.server.engine.sessions.values.single()
            manager.close()
            h.settle()
            assertTrue(session.received.any { it.type == EngineIOPacketType.CLOSE }, "the engine close packet was never sent")
            assertFalse(session.isOpen)
            assertTrue(manager.executor.isShutdown)
        }

    @Test
    fun closingStopsWaitingForAStalledConnectionAfterTheGracePeriod() =
        runClientTest {
            val h = clientHarness(upgrades = emptyList())
            val manager = h.manager()
            manager.socket("/")
            h.settle()
            h.server.engine.stall = { it.method == "POST" }
            manager.close()
            h.settle()
            assertFalse(manager.executor.isShutdown)
            advanceTimeBy(SocketManager.CLOSE_GRACE)
            h.settle()
            assertTrue(manager.executor.isShutdown)
        }

    @Test
    fun aClosedEngineThatCannotFlushIsForceClosedAfterTheGracePeriod() =
        runClientTest {
            // A heartbeat this long gives the closing engine no deadline of its own.
            val h = clientHarness(upgrades = emptyList(), pingInterval = 10.minutes, pingTimeout = 10.minutes)
            val manager = h.manager { reconnection = false }
            val socket = manager.socket("/")
            h.settle()
            assertTrue(socket.connected)
            val engine = manager.engine!!
            h.server.engine.stall = { it.method == "POST" }
            socket.disconnect()
            h.settle()
            assertEquals(EngineState.CLOSING, engine.readyState)
            advanceTimeBy(SocketManager.CLOSE_GRACE - 1.milliseconds)
            h.settle()
            assertEquals(EngineState.CLOSING, engine.readyState)
            advanceTimeBy(1.milliseconds)
            h.settle()
            assertEquals(EngineState.CLOSED, engine.readyState)
            assertFalse(manager.executor.isShutdown)
        }

    @Test
    fun closingTheManagerAlsoEndsAnEarlierEngineThatIsStillClosing() =
        runClientTest {
            val h = clientHarness(upgrades = emptyList(), pingInterval = 10.minutes, pingTimeout = 10.minutes)
            val manager = h.manager()
            val socket = manager.socket("/")
            h.settle()
            val first = manager.engine!!
            val sid = first.id
            h.server.engine.stall = { it.method == "POST" && it.query["sid"] == sid }
            socket.disconnect()
            h.settle()
            assertEquals(EngineState.CLOSING, first.readyState)
            socket.connect()
            h.settle()
            assertTrue(socket.connected)
            assertNotSame(first, manager.engine)
            // The current engine closes at once; the earlier one still waits for its stalled write.
            manager.close()
            h.settle()
            advanceTimeBy(SocketManager.CLOSE_GRACE)
            h.settle()
            assertEquals(EngineState.CLOSED, first.readyState)
            assertTrue(manager.executor.isShutdown)
        }

    @Test
    fun callsAfterCloseFailInsteadOfWaitingForever() =
        runClientTest {
            val h = clientHarness()
            val manager = h.manager { autoConnect = false }
            val socket = manager.socket("/")
            manager.close()
            h.settle()
            assertTrue(runCatching { socket.sendBufferSnapshot() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { socket.emitWithAck("echo") }.exceptionOrNull() is SocketDisconnectedException)
            var opened: Throwable? = null
            manager.open { opened = it }
            h.settle()
            assertTrue(opened is IllegalStateException)

            val paused = h.manager { autoConnect = false }
            paused.pause()
            var result: Throwable? = null
            paused.open { result = it }
            h.settle()
            assertTrue(result is IllegalStateException, "open(callback) on a paused manager must report")
        }

    @Test
    fun callbacksQueuedWhileClosingStillArrive() {
        val gate = CountDownLatch(1)
        val callbacks = Executors.newSingleThreadExecutor()
        callbacks.execute { gate.await() }
        val closeSeen = CountDownLatch(1)
        val manager =
            SocketManager(
                "http://127.0.0.1:1",
                SocketManagerOptions {
                    autoConnect = false
                    reconnection = false
                    callbackDispatcher = callbacks.asCoroutineDispatcher()
                    // The handshake never answers, so the manager is still opening when it is closed.
                    clients = EngineClients(SilentHttp, null)
                },
            )
        try {
            manager.on<ManagerEvent.Close> { closeSeen.countDown() }
            manager.open()
            manager.close()
            val job = manager.executor.scope.coroutineContext[Job]!!
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!job.isCompleted && System.nanoTime() < deadline) Thread.onSpinWait()
            assertTrue(manager.executor.isShutdown)
            gate.countDown()
            assertTrue(closeSeen.await(10, TimeUnit.SECONDS), "the close listener queued during close() never ran")
        } finally {
            gate.countDown()
            callbacks.shutdown()
        }
    }

    /** Accepts every request and never answers. */
    private object SilentHttp : EngineHttpClient {
        override fun execute(
            request: EngineHttpRequest,
            callback: EngineHttpCallback,
        ): Cancellable = Cancellable.NONE
    }

    @Test
    fun aClosedManagerIsNeverHandedOutByIo() {
        val options =
            SocketManagerOptions {
                autoConnect = false
                clients = EngineClients(null, null)
            }
        try {
            val first = SocketIO.io("http://cache.test/a", options)
            first.manager.close()
            val second = SocketIO.io("http://cache.test/b", options)
            assertNotSame(first.manager, second.manager)
            assertFalse(second.manager.isClosed)
        } finally {
            SocketIO.closeAll()
        }
    }
}
