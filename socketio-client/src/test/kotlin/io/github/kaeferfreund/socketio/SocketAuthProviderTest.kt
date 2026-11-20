package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import io.github.kaeferfreund.socketio.testing.FakeSocketIOServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The dynamic `auth` provider: evaluated for every CONNECT, failures and stale results. */
class SocketAuthProviderTest {
    @Test
    fun theProviderRunsBeforeEveryConnectIncludingReconnects() =
        runClientTest {
            var token = "first"
            val attempts = ArrayList<Int>()
            val h =
                clientHarness {
                    namespace("/").use { handshake -> if (handshake.auth?.get("token")?.string == "expired") FakeSocketIOServer.Refusal("expired") else null }
                }
            val socket =
                h.manager { reconnectionDelay = 1.seconds }.socket(
                    options =
                    SocketOptions {
                        authProvider =
                            AuthProvider { attempt ->
                                attempts += attempt
                                mapOf("token" to token)
                            }
                    },
                )
            h.settle()
            assertTrue(socket.connected)
            token = "second"
            h.server.engine.sessions.values.single().kill()
            h.settle()
            advanceTimeBy(2.seconds)
            assertTrue(socket.connected)
            val tokens = h.server.received.filter { it.type == SocketIOPacketType.CONNECT }.map { it.data?.obj?.get("token")?.string }
            assertEquals(listOf("first", "second"), tokens)
            assertEquals(listOf(0, 1), attempts)
            h.close()
        }

    @Test
    fun aProviderThatTimesOutFailsTheAttemptWithConnectError() =
        runClientTest {
            val h = clientHarness()
            val errors = ArrayList<Throwable>()
            val socket =
                h.manager().socket(options = SocketOptions { authProvider = AuthProvider { withTimeout(1.seconds) { awaitCancellation() } } }) {
                    onConnectError { errors += it }
                }
            h.settle()
            advanceTimeBy(2.seconds)
            h.settle()
            assertTrue(!socket.connected)
            val error = errors.single() as AuthProviderException
            assertTrue(error.cause is TimeoutCancellationException)
            h.close()
        }

    @Test
    fun disconnectingCancelsAProviderThatIsStillRunning() =
        runClientTest {
            val h = clientHarness()
            var cancelled = false
            val socket =
                h.manager().socket(
                    options =
                    SocketOptions {
                        authProvider =
                            AuthProvider {
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelled = true
                                }
                            }
                    },
                )
            h.settle()
            assertTrue(!cancelled)
            socket.disconnect()
            h.settle()
            assertTrue(cancelled)
            h.close()
        }

    @Test
    fun aThrowingProviderFailsTheAttemptWithConnectError() =
        runClientTest {
            val h = clientHarness()
            val errors = ArrayList<Throwable>()
            val socket =
                h.manager().socket(options = SocketOptions { authProvider = AuthProvider { error("no token") } }) {
                    onConnectError { errors += it }
                }
            h.settle()
            assertTrue(!socket.connected)
            val error = errors.single() as AuthProviderException
            assertEquals("no token", error.cause!!.message)
            assertTrue(h.server.received.none { it.type == SocketIOPacketType.CONNECT })
            h.close()
        }

    @Test
    fun aResultArrivingAfterDisconnectIsDropped() =
        runClientTest {
            val h = clientHarness()
            val release = CompletableDeferred<Unit>()
            val socket =
                h.manager().socket(
                    options =
                    SocketOptions {
                        authProvider =
                            AuthProvider {
                                release.await()
                                mapOf("token" to "late")
                            }
                    },
                )
            h.settle()
            socket.disconnect()
            h.settle()
            release.complete(Unit)
            h.settle()
            assertTrue(h.server.received.none { it.type == SocketIOPacketType.CONNECT })
            h.close()
        }

    @Test
    fun aSuspendingProviderDelaysOnlyItsOwnNamespace() =
        runClientTest {
            val h = clientHarness { namespace("/fast") }
            val manager = h.manager()
            val slow =
                manager.socket(
                    options =
                    SocketOptions {
                        authProvider =
                            AuthProvider {
                                delay(500.milliseconds)
                                mapOf("slow" to true)
                            }
                    },
                )
            val fast = manager.socket("/fast")
            h.settle()
            assertTrue(fast.connected)
            assertTrue(!slow.connected)
            advanceTimeBy(501.milliseconds)
            assertTrue(slow.connected)
            h.close()
        }

    @Test
    fun aStaticAuthMustBeAnObject() =
        runClientTest {
            val h = clientHarness()
            val errors = ArrayList<Throwable>()
            h.manager().socket(options = SocketOptions { auth = listOf(1) }) { onConnectError { errors += it } }
            h.settle()
            assertTrue(errors.single() is AuthProviderException)
            h.close()
        }

    @Test
    fun aConnectAnswerBeforeTheProviderSentConnectIsIgnored() =
        runClientTest {
            val sent = ArrayList<String>()
            val h =
                clientHarness {
                    engine.onConnection = { session ->
                        // Unsolicited: the client has not sent its CONNECT yet.
                        session.send("0{\"sid\":\"unsolicited\"}")
                        session.onMessage = { data ->
                            val text = (data as EngineIOData.Text).value
                            sent += text
                            if (text.startsWith("0")) session.send("0{\"sid\":\"answer\"}")
                        }
                    }
                }
            val release = CompletableDeferred<Unit>()
            var connects = 0
            val socket =
                h.manager().socket(
                    options =
                    SocketOptions {
                        authProvider =
                            AuthProvider {
                                release.await()
                                mapOf("token" to "t")
                            }
                    },
                ) { onConnect { connects++ } }
            socket.emit("buffered")
            h.settle()
            assertTrue(!socket.connected)
            assertEquals(0, connects)
            assertTrue(sent.none { it.startsWith("2") }, "$sent")
            release.complete(Unit)
            h.settle()
            assertTrue(socket.connected)
            assertEquals("answer", socket.id)
            assertEquals(1, connects)
            assertEquals(1, sent.count { it.startsWith("2[\"buffered\"") })
            h.close()
        }
}
