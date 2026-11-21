package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOParser
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP long-polling, a port of `Polling` and `BaseXHR`/`Request` in
 * `engine.io-client`, on top of an [EngineHttpClient].
 *
 * One GET is outstanding at a time; POSTs never overlap because the engine
 * only writes while the transport is [writable]. Differences from JavaScript,
 * all toward robustness: a response that arrives after the transport closed
 * is ignored, the outstanding GET and POSTs are cancelled on close (the close
 * POST is still sent, with [TransportOptions.requestTimeout] or at most
 * 10 seconds), and a response body may be bounded by
 * [TransportOptions.maxPollingResponseBytes].
 */
public class PollingTransport(
    options: TransportOptions,
) : EngineTransport(options) {
    override val name: String get() = NAME

    private val http: EngineHttpClient =
        requireNotNull(options.httpClient) {
            "The polling transport needs an EngineHttpClient; add the socketio-okhttp module or configure one"
        }

    private var polling = false
    private var pollRequest: Cancellable? = null

    /** POSTs still waiting for their response; cancelled when the transport closes. */
    private val pendingWrites = LinkedHashSet<PendingWrite>()

    private class PendingWrite {
        var request: Cancellable = Cancellable.NONE
    }

    /** Generation of the current open cycle; stale callbacks compare against it. */
    private var closedGeneration = 0

    override fun doOpen() {
        poll()
    }

    /** Listeners of a pause that waits for the pending poll or write. */
    private val pauseWaits = ArrayList<Cancellable>()

    override fun pause(onPause: () -> Unit) {
        readyState = TransportState.PAUSING
        val pause = {
            pauseWaits.clear()
            readyState = TransportState.PAUSED
            onPause()
        }
        if (polling || !writable) {
            var total = 0
            if (polling) {
                total++
                pauseWaits +=
                    events.once<TransportEvent.PollComplete, TransportEvent> {
                        if (--total == 0) pause()
                    }
            }
            if (!writable) {
                total++
                pauseWaits +=
                    events.once<TransportEvent.Drain, TransportEvent> {
                        if (--total == 0) pause()
                    }
            }
        } else {
            pause()
        }
    }

    // JavaScript cannot undo a pause: after a failed upgrade probe it stays paused and stops
    // polling, so the connection stalls until the heartbeat deadline.
    override fun resume() {
        if (readyState != TransportState.PAUSING && readyState != TransportState.PAUSED) return
        pauseWaits.forEach(Cancellable::cancel)
        pauseWaits.clear()
        readyState = TransportState.OPEN
        // A poll still in flight polls again when it completes; a write in flight drains as usual.
        if (!polling) poll()
    }

    private fun poll() {
        polling = true
        doPoll()
        events.emit(TransportEvent.Poll)
    }

    /** Handles one polling response body (`onData` in JavaScript). */
    private fun onPayload(data: String) {
        // EngineIOParser.decodePayload, one packet at a time: a body of many tiny packets is
        // never expanded into packet objects all at once (JavaScript decodes the whole list).
        var start = 0
        while (true) {
            val end = data.indexOf(EngineIOParser.SEPARATOR, start).let { if (it < 0) data.length else it }
            val packet = EngineIOParser.decodePacket(data.substring(start, end))
            onPayloadPacket(packet)
            if (packet.type == EngineIOPacketType.ERROR || end == data.length) break
            start = end + 1
        }
        if (readyState != TransportState.CLOSED) {
            polling = false
            events.emit(TransportEvent.PollComplete)
            if (readyState == TransportState.OPEN) poll()
        }
    }

    private fun onPayloadPacket(packet: EngineIOPacket) {
        if (readyState == TransportState.OPENING && packet.type == EngineIOPacketType.OPEN) onOpen()
        if (packet.type == EngineIOPacketType.CLOSE) {
            onClose(CloseDetails("transport closed by the server"))
            // JavaScript's forEach ignores the callback's `return false`
            // and hands later packets to listeners that no longer exist.
            return
        }
        onPacket(packet)
    }

    override fun doClose() {
        val close = { writeClosePacket() }
        if (readyState == TransportState.OPEN) {
            close()
        } else {
            // The handshake may still be pending: send the close packet once it opens.
            events.once<TransportEvent.Open, TransportEvent> { close() }
        }
    }

    override fun onClose(details: CloseDetails?) {
        closedGeneration++
        // A pause still waiting for its poll or write never completes on a closed transport.
        pauseWaits.forEach(Cancellable::cancel)
        pauseWaits.clear()
        pollRequest?.cancel()
        pollRequest = null
        // JavaScript lets pending writes run on; a server that never answers them would
        // keep requests of a closed connection open.
        val writes = pendingWrites.toList()
        pendingWrites.clear()
        writes.forEach { it.request.cancel() }
        super.onClose(details)
    }

    override fun write(packets: List<EngineIOPacket>) {
        writable = false
        val body = EngineIOParser.encodePayload(packets)
        doWrite(body) {
            writable = true
            events.emit(TransportEvent.Drain)
        }
    }

    override fun uri(): String {
        val schema = if (options.secure) "https" else "http"
        if (options.timestampRequests != false) query[options.timestampParam] = EngineUri.randomString()
        if (!supportsBinary && !query.containsKey("sid")) query["b64"] = "1"
        return createUri(schema, query)
    }

    private fun doPoll() {
        val generation = closedGeneration
        pollRequest =
            request("GET", null, options.requestTimeout) { result ->
                if (generation != closedGeneration) return@request
                pollRequest = null
                result.fold(
                    onSuccess = { onPayload(it) },
                    onFailure = { reportFailure("xhr poll error", it) },
                )
            }
    }

    private fun doWrite(
        body: String,
        onSuccess: () -> Unit,
    ) {
        val generation = closedGeneration
        val pending = PendingWrite()
        pendingWrites += pending
        pending.request =
            request("POST", body, options.requestTimeout) { result ->
                // Like a poll, a write of a closed transport is cancelled and its outcome ignored:
                // neither a drain nor the cancellation's error reaches the engine.
                if (generation != closedGeneration) return@request
                pendingWrites -= pending
                result.fold(
                    onSuccess = { onSuccess() },
                    onFailure = { reportFailure("xhr post error", it) },
                )
            }
    }

    /**
     * Sends the close packet. Its POST outlives the transport on purpose, so the
     * server learns about the close, but not indefinitely: without a configured
     * [TransportOptions.requestTimeout] it is bounded by [CLOSE_REQUEST_TIMEOUT].
     */
    private fun writeClosePacket() {
        writable = false
        val body = EngineIOParser.encodePayload(listOf(EngineIOPacket(EngineIOPacketType.CLOSE)))
        request("POST", body, options.requestTimeout ?: CLOSE_REQUEST_TIMEOUT) { result ->
            result.fold(
                onSuccess = {
                    writable = true
                    events.emit(TransportEvent.Drain)
                },
                onFailure = { reportFailure("xhr post error", it) },
            )
        }
    }

    private fun reportFailure(
        reason: String,
        error: Throwable,
    ) {
        if (error is HttpStatusException) {
            onError(reason, statusCode = error.status, responseBody = error.body)
        } else {
            onError(reason, cause = error)
        }
    }

    private class HttpStatusException(
        val status: Int,
        val body: String,
    ) : RuntimeException("HTTP $status")

    private fun request(
        method: String,
        body: String?,
        timeout: Duration?,
        onResult: (Result<String>) -> Unit,
    ): Cancellable {
        val headers = requestHeaders()
        if (method == "POST") headers.add("Content-type" to "text/plain;charset=UTF-8")
        headers.add("Accept" to "*/*")
        val url = uri()
        val jar = options.cookieJar
        // Deliberate difference from JavaScript, whose Node client sends the same headers to every
        // redirect target: the HTTP client rebuilds the cookies of each redirect from the live jar by
        // RFC 6265 rules (see EngineRedirectCookies).
        val redirectCookies = RequestCookies(url, explicitCookie, jar)
        val request =
            EngineHttpRequest(
                method = method,
                url = url,
                headers = headers,
                body = body,
                timeout = timeout,
                maxResponseBytes = options.maxPollingResponseBytes,
                redirectCookies = redirectCookies,
            )
        val callback =
            object : EngineHttpCallback {
                override fun onResponse(response: EngineHttpResponse) {
                    // Stored the moment the response arrives (the jar is thread-safe), for the URL that
                    // sent it: the last redirect target. Redirect responses stored theirs on the way.
                    jar?.parseCookies(response.headerValues("set-cookie"), redirectCookies.finalUrl)
                    executor.execute {
                        // 1223 is IE's mangled 204; kept for fidelity with the JavaScript check.
                        if (response.status == 200 || response.status == 1223) {
                            onResult(Result.success(response.body))
                        } else {
                            onResult(Result.failure(HttpStatusException(response.status, response.body)))
                        }
                    }
                }

                override fun onFailure(error: Throwable) {
                    executor.execute { onResult(Result.failure(error)) }
                }
            }
        return try {
            http.execute(request, callback)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // A request that cannot even be started (a header or URL the HTTP stack rejects) is a
            // request error, reported on the next tick like `Request._create` does in JavaScript.
            executor.post { onResult(Result.failure(e)) }
            Cancellable.NONE
        }
    }

    public companion object {
        public const val NAME: String = "polling"

        /** Longest wait for the response to the close packet when no request timeout is configured. */
        private val CLOSE_REQUEST_TIMEOUT = 10.seconds

        /** Factory for [EngineOptions.transportFactories]. */
        public val FACTORY: Factory = Factory { PollingTransport(it) }
    }
}
