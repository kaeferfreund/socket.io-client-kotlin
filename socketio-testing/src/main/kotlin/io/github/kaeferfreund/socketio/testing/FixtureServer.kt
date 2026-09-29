package io.github.kaeferfreund.socketio.testing

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a Node fixture server (from the repository's `fixtures` directory, or
 * your own directory with the same `READY port=… secret=…` convention) for
 * end-to-end tests on the JVM.
 *
 * ```kotlin
 * FixtureServer.start(fixturesDir, "server.js").use { server ->
 *     val manager = SocketManager(server.url)
 * }
 * ```
 */
public class FixtureServer private constructor(
    /** The port the server listens on (on `127.0.0.1`). */
    public val port: Int,
    /** The secret expected in the `X-Admin-Secret` header of admin routes. */
    public val secret: String,
    private val process: Process,
    private val output: StringBuffer,
    private val scheme: String,
) : AutoCloseable {
    /** `http://127.0.0.1:<port>` (or `https://localhost:<port>` for TLS fixtures). */
    public val url: String get() = if (scheme == "https") "https://localhost:$port" else "http://127.0.0.1:$port"

    /** Everything the server printed so far, for diagnostics. */
    public val log: String get() = output.toString()

    /** Calls an admin route and returns the status and body. */
    public fun admin(
        path: String,
        method: String = "POST",
        body: String? = null,
    ): Pair<Int, String> {
        // The admin client does not trust the per-run test certificates; TLS fixtures have no admin routes.
        check(scheme == "http") { "admin routes are available on plain HTTP fixtures only" }
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("X-Admin-Secret", secret)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to (stream?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    /** Stops the server and every process it started: SIGTERM, then SIGKILL after one second. */
    override fun close() {
        if (!process.isAlive) return
        terminate(process)
    }

    public companion object {
        private val installLock = Any()

        /**
         * Starts [script] in [fixturesDir] with [environment] and waits until
         * it prints its READY line.
         */
        public fun start(
            fixturesDir: File,
            script: String = "server.js",
            environment: Map<String, String> = emptyMap(),
            startupTimeout: Duration = 15.seconds,
            tls: Boolean = false,
        ): FixtureServer {
            ensureNodeModules(fixturesDir)
            // The watchdog ends the server when this JVM goes away, so a killed test run
            // cannot leave servers behind.
            val builder = ProcessBuilder("node", "--require", watchdog.absolutePath, script).directory(fixturesDir).redirectErrorStream(true)
            builder.environment().putAll(environment)
            val process = builder.start()
            val output = StringBuffer()
            val ready = java.util.concurrent.CompletableFuture<Pair<Int, String>>()
            Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (output.length < 256 * 1024) output.append(line).append('\n')
                        READY.find(line)?.let { ready.complete(it.groupValues[1].toInt() to it.groupValues[2]) }
                    }
                }
                ready.completeExceptionally(IllegalStateException("fixture exited before READY:\n$output"))
            }, "fixture-$script").apply { isDaemon = true }.start()
            val (port, secret) =
                try {
                    ready.get(startupTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    terminate(process)
                    throw IllegalStateException("fixture $script did not start in $startupTimeout:\n$output", e)
                } catch (e: java.util.concurrent.ExecutionException) {
                    terminate(process)
                    throw IllegalStateException(e.cause?.message ?: "fixture failed", e)
                }
            return FixtureServer(port, secret, process, output, if (tls) "https" else "http")
        }

        /** Runs `npm ci` once per lockfile content. */
        public fun ensureNodeModules(fixturesDir: File) {
            synchronized(installLock) {
                val lock = File(fixturesDir, "package-lock.json").readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(lock).joinToString("") { "%02x".format(it) }
                val marker = File(fixturesDir, "node_modules/.kotlin-fixture-lock")
                if (marker.isFile && marker.readText() == digest) return
                val process =
                    ProcessBuilder("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund")
                        .directory(fixturesDir)
                        .redirectErrorStream(true)
                        .start()
                val (finished, log) = awaitProcess(process, 180.seconds)
                check(finished && process.exitValue() == 0) { "npm ci failed${if (finished) "" else " (no result after 180 s)"}:\n$log" }
                marker.writeText(digest)
            }
        }

        /**
         * Waits up to [timeout] for [process] while its output is drained on another thread,
         * so a stalled process cannot block the caller past the timeout. Returns whether it
         * finished, and its output. A process still running afterwards is killed.
         */
        internal fun awaitProcess(
            process: Process,
            timeout: Duration,
        ): Pair<Boolean, String> {
            val output = StringBuffer()
            val reader =
                Thread({ process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }, "fixture-process-output")
                    .apply {
                        isDaemon = true
                        start()
                    }
            val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (!finished) terminate(process)
            reader.join(1_000)
            return finished to output.toString()
        }

        /**
         * Stops [process] and its descendants: SIGTERM, then SIGKILL to those still running
         * after one second. Behind a version-manager shim (Volta, asdf, mise) `node` and `npm`
         * are wrappers that run the real program as their child, which a signal to the
         * wrapper alone does not reach.
         */
        private fun terminate(process: Process) {
            val processes = process.descendants().toList() + process.toHandle()
            processes.forEach { it.destroy() }
            if (!awaitExit(processes)) {
                processes.forEach { it.destroyForcibly() }
                awaitExit(processes)
            }
        }

        private fun awaitExit(processes: List<ProcessHandle>): Boolean =
            try {
                CompletableFuture.allOf(*processes.map { it.onExit() }.toTypedArray()).get(1, TimeUnit.SECONDS)
                true
            } catch (_: TimeoutException) {
                false
            }

        private val READY = Regex("READY port=(\\d+) secret=(\\S+)")

        private val watchdog: File by lazy {
            File.createTempFile("socketio-fixture-watchdog", ".cjs").apply {
                deleteOnExit()
                writeText(
                    """
                    // Exit when the parent closes our stdin (it exited or was killed).
                    process.stdin.on("end", () => process.exit(0));
                    process.stdin.on("error", () => process.exit(0));
                    process.stdin.resume();
                    """.trimIndent(),
                )
            }
        }
    }
}
