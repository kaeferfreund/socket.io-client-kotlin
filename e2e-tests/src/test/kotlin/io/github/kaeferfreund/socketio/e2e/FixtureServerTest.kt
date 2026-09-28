package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.testing.FixtureServer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * [FixtureServer.close] stops every process the fixture started, also when
 * `node` is a version-manager shim that runs the server as its child.
 */
class FixtureServerTest {
    @Test
    fun closeStopsTheServerBehindAShim() {
        val before = ProcessHandle.current().descendants().map { it.pid() }.toList().toSet()
        val server = FixtureServer.start(fixturesDir, "shim.mjs")
        val started = ProcessHandle.current().descendants().filter { it.pid() !in before }.toList()
        try {
            // The shim and the server at least; a real shim on PATH adds its own process.
            assertTrue(started.size >= 2, "expected the shim and its server, got $started")
            server.close()
            started.forEach { it.onExit().get(5, TimeUnit.SECONDS) }
        } finally {
            started.forEach { it.destroyForcibly() }
        }
    }
}
