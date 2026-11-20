package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.engineio.on
import io.github.kaeferfreund.socketio.parser.SocketIOJson
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * A namespace connection multiplexed over a [SocketManager]: a port of
 * `Socket` in `socket.io-client`.
 *
 * Emitting, listening and connecting may be done from any thread. Emitted
 * arguments are converted with [SocketIOValue.of] on the calling thread, so
 * later changes to the caller's objects never leak into the packet, and an
 * unsupported argument fails the call immediately.
 */
public class Socket internal constructor(
    /** The manager this socket belongs to (`socket.io`). */
    public val manager: SocketManager,
    /** The namespace, for example `"/"` or `"/chat"`. */
    public val namespace: String,
    public val options: SocketOptions,
) {
    private val executor get() = manager.executor

    // ---- Protocol state: only touched on the executor. ------------------------------------------

    private var connectedState = false
    private var idState: String? = null
    private var pid: String? = null
    private var lastOffset: String? = null
    private var recoveredState = false
    private val receiveBuffer = ArrayList<IncomingEvent>()
    private var receiveBufferBytes = 0L
    private val sendBuffer = ArrayList<BufferedPacket>()
    private var sendBufferBytes = 0L
    private val queue = ArrayList<QueuedPacket>()
    private var queueBytes = 0L
    private var queueSeq = 0L
    private var ids = 0L
    private val acks = LinkedHashMap<Long, AckEntry>()
    private var subs: List<Cancellable>? = null
    private var connectGeneration = 0
    private var providerJob: Job? = null

    /**
     * CONNECTs this socket queued on the current connection that are not answered yet. Usually
     * one; more when connect() runs again before the answer (as in JavaScript, each is answered).
     */
    private var pendingConnects = 0

    /** Counts ended namespace sessions; an acknowledgement belongs to the session it was received in. */
    private var sessionEpoch = 0L

    /** `true` while subscribed to the manager, i.e. connected or trying to (`socket.active`). Executor only. */
    internal val isActive: Boolean get() = subs != null

    private class BufferedPacket(
        val packet: SocketIOPacket,
        val compress: Boolean,
        val bytes: Long,
        val event: OutgoingEvent,
    )

    private class QueuedPacket(
        val id: Long,
        val name: String,
        val args: List<SocketIOValue>,
        val flags: EmitFlags,
        val bytes: Long,
        val ack: ((Throwable?, List<SocketIOValue>) -> Unit)?,
    ) {
        var tryCount = 0
        var pending = false
        var callback: (Throwable?, List<SocketIOValue>) -> Unit = { _, _ -> }
    }

    /** An acknowledgement awaiting its response. [withError] callbacks get err-first semantics, as in JavaScript. */
    private class AckEntry(
        val withError: Boolean,
        val callback: (Throwable?, List<SocketIOValue>) -> Unit,
        var timer: Cancellable? = null,
    ) {
        /** Whether its packet went to the transport; only then can an answer be genuine. */
        var sent = false
    }

    // ---- Listeners (thread-safe, not protocol state). -------------------------------------------

    private class NamedListener(
        val event: String,
        val listener: EventListener,
        val once: Boolean,
    )

    private val namedListeners = CopyOnWriteArrayList<NamedListener>()
    private val anyListeners = CopyOnWriteArrayList<AnyListener>()
    private val anyOutgoingListeners = CopyOnWriteArrayList<AnyListener>()
    private val lifecycle = CallbackListeners<SocketEvent>()

    // ---- Published views. -----------------------------------------------------------------------

    private val stateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())

    /** The connection state; updated on the protocol executor. */
    public val state: StateFlow<ConnectionState> = stateFlow.asStateFlow()

    private val eventFlow = eventFlow<SocketEvent>(manager.options)

    /** Lifecycle events and received events as a hot stream. */
    public val events: SharedFlow<SocketEvent> = eventFlow.asSharedFlow()

    private val pendingFlow = MutableStateFlow(0)

    /** Emits waiting in the send buffer or the retry queue. */
    public val pendingEmits: StateFlow<Int> = pendingFlow.asStateFlow()

    private val pendingAcksFlow = MutableStateFlow(0)

    /** Acknowledgements this socket is waiting for. */
    public val pendingAcknowledgements: StateFlow<Int> = pendingAcksFlow.asStateFlow()

    @Volatile private var activeView = false

    /** The namespace session id, `null` while disconnected (`socket.id`). */
    public val id: String? get() = (stateFlow.value as? ConnectionState.Connected)?.id

    /** Whether the namespace is connected (`socket.connected`). */
    public val connected: Boolean get() = stateFlow.value is ConnectionState.Connected

    /** `!connected` (`socket.disconnected`). */
    public val disconnected: Boolean get() = !connected

    /** Whether the last connection was recovered by connection state recovery (`socket.recovered`). */
    public val recovered: Boolean get() = (stateFlow.value as? ConnectionState.Connected)?.recovered ?: false

    /**
     * Whether the socket is subscribed to the manager and will (re)connect by
     * itself (`socket.active`). `false` after [disconnect], a server
     * disconnect or a middleware refusal.
     */
    public val active: Boolean get() = activeView

    init {
        // JavaScript: `if (this.io._autoConnect) this.open()` — done by the manager after registration.
    }

    // ---- Connecting -----------------------------------------------------------------------------

    /** Connects the namespace (`socket.connect()`). */
    public fun connect(): Socket {
        executor.execute { connectOnExecutor() }
        return this
    }

    /** Alias of [connect] (`socket.open()`). */
    public fun open(): Socket = connect()

    /**
     * Disconnects the namespace (`socket.disconnect()`). The connection itself
     * closes when no other namespace uses it. There is no automatic
     * reconnection until [connect] is called again.
     */
    public fun disconnect(): Socket {
        executor.execute { disconnectOnExecutor() }
        return this
    }

    /** Alias of [disconnect] (`socket.close()`). */
    public fun close(): Socket = disconnect()

    /**
     * Forgets the connection-state-recovery session (`pid` and offset), so the
     * next CONNECT starts a fresh session — for example after the signed-in
     * user changed.
     *
     * Everything the previous session left waiting is dropped too, so none of
     * it reaches the next one: emits in the send buffer and the retry queue
     * (including a retry waiting for its acknowledgement) and events received
     * before a CONNECT. Their acknowledgements fail with
     * [SocketDisconnectedException], as on [SocketManager.close]. Emits made
     * after this call are kept. The current connection stays up, and
     * acknowledgements of other emits it already sent are still answered.
     */
    public fun clearRecoveryState(): Socket {
        executor.execute {
            pid = null
            lastOffset = null
            dropPending()
        }
        return this
    }

    /** Drops what is waiting to be sent or delivered; see [clearRecoveryState]. */
    private fun dropPending() {
        val error = SocketDisconnectedException()
        receiveBuffer.clear()
        receiveBufferBytes = 0
        val queued = ArrayList(queue)
        for (packet in queued) packet.callback = { _, _ -> }
        queue.clear()
        queueBytes = 0
        val inFlight = ArrayList(inFlightAckIds.values)
        inFlightAckIds.clear()
        val buffered = ArrayList(sendBuffer)
        sendBuffer.clear()
        sendBufferBytes = 0
        val dropped = ArrayList<AckEntry>()
        for (id in buffered.mapNotNull { it.packet.id } + inFlight) {
            val entry = acks.remove(id) ?: continue
            entry.timer?.cancel()
            dropped.add(entry)
        }
        updatePending()
        for (item in buffered) intercept { it.onDropped(item.event, error) }
        // A retry's own entry only forwards to its (now silenced) queue callback; the queued ack is failed below.
        for (entry in dropped) if (entry.withError) entry.callback(error, emptyList())
        for (packet in queued) packet.ack?.invoke(error, emptyList())
    }

    internal fun connectOnExecutor() {
        if (connectedState) return
        subEvents()
        if (!manager.reconnecting) manager.openOnExecutor(null)
        if (manager.readyState == SocketManager.ManagerState.OPEN) onopen()
    }

    private fun subEvents() {
        if (subs != null) return
        val events = manager.internalEvents
        subs =
            listOf(
                events.on<ManagerEvent.Open, ManagerEvent> { onopen() },
                events.on<ManagerEvent.Packet, ManagerEvent> { onpacket(it.packet) },
                events.on<ManagerEvent.Error, ManagerEvent> { onerror(it.error) },
                events.on<ManagerEvent.Close, ManagerEvent> { onclose(it.reason, it.details) },
                events.on<ManagerEvent.ReconnectAttempt, ManagerEvent> { if (!connectedState) stateFlow.value = ConnectionState.Connecting },
            )
        activeView = true
        if (!connectedState) stateFlow.value = ConnectionState.Connecting
    }

    internal fun disconnectOnExecutor() {
        if (connectedState) packet(SocketIOPacket(SocketIOPacketType.DISCONNECT, namespace), compress = true)
        destroy()
        if (connectedState) {
            onclose(DisconnectReason.IO_CLIENT_DISCONNECT, null)
        } else {
            stateFlow.value = ConnectionState.Disconnected(DisconnectReason.IO_CLIENT_DISCONNECT)
        }
    }

    private fun destroy() {
        subs?.forEach(Cancellable::cancel)
        subs = null
        activeView = false
        pendingConnects = 0
        endSession()
        connectGeneration++
        providerJob?.cancel()
        providerJob = null
        manager.destroy()
    }

    private fun onopen() {
        val provider = options.authProvider
        if (provider == null) {
            sendConnectPacket(options.auth)
            return
        }
        val generation = ++connectGeneration
        val attempt = manager.currentAttempt()
        // A provider still running for an earlier attempt is not needed any more.
        providerJob?.cancel()
        providerJob =
            manager.executor.scope.launch {
                val payload =
                    try {
                        Result.success(provider.provide(attempt))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Only the cancellation of this attempt stops here; the provider's own timeout
                        // (withTimeout inside it) is a failure of the provider like any other.
                        ensureActive()
                        Result.failure(e)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        Result.failure(e)
                    }
                // Resumed on the executor; drop the result if the socket moved on meanwhile.
                if (generation != connectGeneration || !isActive || manager.readyState != SocketManager.ManagerState.OPEN) return@launch
                payload.fold(
                    onSuccess = { sendConnectPacket(it) },
                    onFailure = { emitConnectError(AuthProviderException(it)) },
                )
            }
    }

    private fun sendConnectPacket(auth: Any?) {
        val data: SocketIOValue.Object? =
            try {
                // Only the type: a token passed as a plain string must not end up in connect_error messages and logs.
                auth?.let { SocketIOValue.of(it) as? SocketIOValue.Object ?: error("auth must be an object, got ${it::class.java.name}") }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                emitConnectError(AuthProviderException(e))
                return
            }
        val payload =
            if (pid != null) {
                val merged = LinkedHashMap<String, SocketIOValue>()
                merged["pid"] = SocketIOValue.Text(pid!!)
                merged["offset"] = lastOffset?.let { SocketIOValue.Text(it) } ?: SocketIOValue.Null
                data?.fields?.let(merged::putAll)
                SocketIOValue.Object(merged)
            } else {
                data
            }
        packet(SocketIOPacket(SocketIOPacketType.CONNECT, namespace, payload), compress = true)
        pendingConnects++
    }

    private fun onerror(error: Throwable) {
        if (!connectedState) emitConnectError(error)
    }

    private fun emitConnectError(error: Throwable) {
        dispatchLifecycle(SocketEvent.ConnectError(error))
    }

    private fun onclose(
        reason: DisconnectReason,
        details: DisconnectDetails?,
    ) {
        val wasConnected = connectedState
        connectedState = false
        pendingConnects = 0
        endSession()
        idState = null
        stateFlow.value = ConnectionState.Disconnected(reason, details)
        // JavaScript also emits "disconnect" for a socket that never connected (its
        // CONNECT was still pending); here every disconnect follows a connect.
        if (wasConnected) dispatchLifecycle(SocketEvent.Disconnected(reason, details))
        clearAcks()
    }

    /**
     * The namespace session ended: acknowledgements received in it are no longer answered, and
     * events that arrived before its CONNECT are dropped. JavaScript keeps both, so an answer could
     * reach the next session — which numbers its own requests from 0 again — and a stale event
     * could be delivered after the next CONNECT, possibly of another user.
     */
    private fun endSession() {
        sessionEpoch++
        receiveBuffer.clear()
        receiveBufferBytes = 0
    }

    private fun clearAcks() {
        val buffered = sendBuffer.mapNotNullTo(HashSet()) { it.packet.id }
        val iterator = acks.entries.iterator()
        val failed = ArrayList<AckEntry>()
        while (iterator.hasNext()) {
            val (id, entry) = iterator.next()
            if (id in buffered) continue
            iterator.remove()
            entry.timer?.cancel()
            if (entry.withError) failed.add(entry)
        }
        updatePending()
        for (entry in failed) entry.callback(SocketDisconnectedException(), emptyList())
    }

    /**
     * The manager was closed for good: acknowledgements that can no longer arrive
     * fail (err-first ones, as on a disconnection), and buffered or queued emits
     * are dropped.
     */
    internal fun failPendingOnClose() {
        val error = SocketDisconnectedException()
        val queued = ArrayList(queue)
        for (packet in queued) packet.callback = { _, _ -> }
        queue.clear()
        queueBytes = 0
        inFlightAckIds.clear()
        val buffered = ArrayList(sendBuffer)
        sendBuffer.clear()
        sendBufferBytes = 0
        val pending = ArrayList(acks.values)
        acks.clear()
        updatePending()
        for (item in buffered) intercept { it.onDropped(item.event, error) }
        for (entry in pending) {
            entry.timer?.cancel()
            if (entry.withError) entry.callback(error, emptyList())
        }
        for (packet in queued) packet.ack?.invoke(error, emptyList())
    }

    private fun onpacket(packet: SocketIOPacket) {
        if (packet.nsp != namespace) return
        when (packet.type) {
            SocketIOPacketType.CONNECT -> {
                // JavaScript accepts any CONNECT. Here only the answer to the CONNECT this socket
                // sent on this connection counts: a peer must not connect the namespace (and
                // release the send buffer) while the auth provider is still running or after it
                // failed, nor connect it more often than it asked. An empty (v2.x) CONNECT is such
                // an answer too.
                if (pendingConnects == 0) return ignoreUnrequested("CONNECT")
                pendingConnects--
                val data = packet.data as? SocketIOValue.Object
                val sid = data?.get("sid")?.string
                if (sid != null && sid.isNotEmpty()) {
                    onconnect(sid, data["pid"]?.string)
                } else {
                    emitConnectError(
                        SocketConnectException(
                            "It seems you are trying to reach a Socket.IO server in v2.x with a v3.x client, but they are not compatible " +
                                "(more information here: https://socket.io/docs/v3/migrating-from-2-x-to-3-0/)",
                        ),
                    )
                }
            }

            SocketIOPacketType.EVENT, SocketIOPacketType.BINARY_EVENT -> onevent(packet)

            SocketIOPacketType.ACK, SocketIOPacketType.BINARY_ACK -> onack(packet)

            SocketIOPacketType.DISCONNECT -> ondisconnect()

            SocketIOPacketType.CONNECT_ERROR -> {
                // A server sends CONNECT_ERROR only to refuse a CONNECT. JavaScript accepts it at any
                // time; on a connected socket it destroys the socket but leaves it marked as connected,
                // so connect() does nothing any more and acknowledgements never fail.
                if (pendingConnects == 0) return ignoreUnrequested("CONNECT_ERROR")
                destroy()
                val data = packet.data
                val error =
                    when (data) {
                        is SocketIOValue.Object -> SocketConnectException(data["message"]?.string.orEmpty(), data["data"])
                        is SocketIOValue.Text -> SocketConnectException(data.value)
                        else -> SocketConnectException("")
                    }
                error.isServerRefusal = true
                stateFlow.value = ConnectionState.Disconnected()
                emitConnectError(error)
            }
        }
    }

    private fun ignoreUnrequested(type: String) {
        val logger = manager.options.logger
        if (logger.isLoggable(LogLevel.DEBUG)) logger.log(LogLevel.DEBUG, "socket", "ignored an unrequested $type for $namespace")
    }

    private fun onevent(packet: SocketIOPacket) {
        val items = (packet.data as? SocketIOValue.Array)?.items ?: return
        val first = items.firstOrNull() ?: return
        val name = first.string ?: (first as? SocketIOValue.Number)?.let { SocketIOJson.stringify(it) } ?: return
        val ack = packet.id?.let { ackFor(it) }
        val event = IncomingEvent(name, items.drop(1), ack)
        if (connectedState) {
            emitEvent(event)
        } else {
            val limits = manager.options.bufferLimits
            // Every retained event and argument costs memory, even when empty.
            val bytes = RETAINED_EVENT_BYTES + estimateBytes(items, slotBytes = RETAINED_SLOT_BYTES)
            if (receiveBuffer.size + 1 > limits.maxReceiveBufferPackets || receiveBufferBytes + bytes > limits.maxReceiveBufferBytes) {
                val error =
                    if (receiveBuffer.size + 1 > limits.maxReceiveBufferPackets) {
                        SocketBufferLimitException(
                            SocketBufferLimitException.Buffer.RECEIVE_BUFFER,
                            limits.maxReceiveBufferPackets.toLong(),
                            receiveBuffer.size + 1L,
                            false,
                        )
                    } else {
                        SocketBufferLimitException(
                            SocketBufferLimitException.Buffer.RECEIVE_BUFFER,
                            limits.maxReceiveBufferBytes,
                            receiveBufferBytes + bytes,
                            true,
                        )
                    }
                manager.options.logger.log(LogLevel.WARN, "socket", "receive buffer limit reached; closing", error)
                manager.engineErrorClose(error)
                return
            }
            receiveBuffer.add(event)
            receiveBufferBytes += bytes
        }
    }

    private fun emitEvent(event: IncomingEvent) {
        for (listener in anyListeners) manager.deliver { listener.onAny(event.name, event.args) }
        for (entry in namedListeners) {
            if (entry.event != event.name) continue
            if (entry.once && !namedListeners.remove(entry)) continue
            manager.deliver { entry.listener.onEvent(event) }
        }
        eventFlow.tryEmit(SocketEvent.Received(event))
        if (pid != null && event.ack == null) {
            (event.args.lastOrNull() as? SocketIOValue.Text)?.let { lastOffset = it.value }
        }
    }

    private fun ackFor(id: Long): Acknowledgement {
        val epoch = sessionEpoch
        return object : Acknowledgement {
            private val sent = AtomicBoolean(false)

            override val isSent: Boolean get() = sent.get()

            override fun send(vararg args: Any?) {
                if (!sent.compareAndSet(false, true)) return
                val data = SocketIOValue.Array(args.map { SocketIOValue.of(it) })
                executor.execute {
                    // Not on a later session (see endSession); JavaScript sends it on whatever connection is current.
                    if (epoch == sessionEpoch) packet(SocketIOPacket(SocketIOPacketType.ACK, namespace, data, id), compress = true)
                }
            }
        }
    }

    private fun onack(packet: SocketIOPacket) {
        val id = packet.id ?: return
        // JavaScript accepts an answer for any registered id. An emit still in the send buffer, or
        // a volatile one that was dropped, never reached the server, so its "answer" is forged:
        // ignore it, and keep the callback for the real answer or the timeout.
        val entry = acks[id]?.takeIf { it.sent } ?: return
        acks.remove(id)
        entry.timer?.cancel()
        updatePending()
        val args = (packet.data as? SocketIOValue.Array)?.items ?: emptyList()
        entry.callback(null, args)
    }

    private fun onconnect(
        id: String,
        newPid: String?,
    ) {
        idState = id
        recoveredState = newPid != null && pid == newPid
        pid = newPid
        connectedState = true
        stateFlow.value = ConnectionState.Connected(id, recoveredState)
        emitBuffered()
        drainQueue(force = true)
        dispatchLifecycle(SocketEvent.Connected(id, recoveredState))
    }

    private fun emitBuffered() {
        val received = ArrayList(receiveBuffer)
        receiveBuffer.clear()
        receiveBufferBytes = 0
        received.forEach(::emitEvent)
        val buffered = ArrayList(sendBuffer)
        sendBuffer.clear()
        sendBufferBytes = 0
        updatePending()
        for (item in buffered) {
            notifyOutgoingListeners(item.event)
            item.packet.id?.let { acks[it]?.sent = true }
            packet(item.packet, item.compress)
            intercept { it.onSent(item.event) }
        }
    }

    private fun ondisconnect() {
        destroy()
        onclose(DisconnectReason.IO_SERVER_DISCONNECT, null)
    }

    private fun packet(
        packet: SocketIOPacket,
        compress: Boolean,
    ) {
        manager.packet(packet, compress)
    }

    // ---- Emitting -------------------------------------------------------------------------------

    /** Emits [event] with [args] (`socket.emit(event, ...args)`). */
    public fun emit(
        event: String,
        vararg args: Any?,
    ): Socket {
        SocketEmitter(this, EmitFlags.NONE).emit(event, *args)
        return this
    }

    /**
     * Emits [event] and calls [ack] with the server's acknowledgement. The
     * default [SocketOptions.ackTimeout] applies; see [AckCallback] for the
     * failure cases.
     */
    public fun emit(
        event: String,
        vararg args: Any?,
        ack: AckCallback,
    ): Socket {
        SocketEmitter(this, EmitFlags.NONE).emit(event, *args, ack = ack)
        return this
    }

    /**
     * Emits [event] and suspends until the acknowledgement arrives
     * (`socket.emitWithAck()`), returning all acknowledgement arguments.
     *
     * Throws [AckTimeoutException] after the timeout ([timeout] or
     * [SocketOptions.ackTimeout]), [SocketDisconnectedException] when the
     * socket disconnects first. Cancelling the coroutine withdraws the
     * acknowledgement and, if it was not sent yet, the packet.
     */
    public suspend fun emitWithAck(
        event: String,
        vararg args: Any?,
    ): List<SocketIOValue> = SocketEmitter(this, EmitFlags.NONE).emitWithAck(event, *args)

    /** `socket.send(...args)`: emits a `"message"` event. */
    public fun send(vararg args: Any?): Socket = emit("message", *args)

    /** Emits with an acknowledgement timeout (`socket.timeout(ms)`). */
    public fun timeout(timeout: Duration): SocketEmitter = SocketEmitter(this, EmitFlags.NONE.copy(timeout = timeout))

    /** Emits volatile: discarded instead of buffered when the transport is not writable (`socket.volatile`). */
    public val volatile: SocketEmitter get() = SocketEmitter(this, EmitFlags.NONE.copy(volatile = true))

    /** Emits with the compression flag (`socket.compress(false)`). */
    public fun compress(compress: Boolean): SocketEmitter = SocketEmitter(this, EmitFlags.NONE.copy(compress = compress))

    /** A copy of the send buffer: emits made while disconnected. */
    public suspend fun sendBufferSnapshot(): List<OutgoingEvent> = onExecutor { sendBuffer.map { it.event } }

    /** A copy of the receive buffer: events received before CONNECT completed. */
    public suspend fun receiveBufferSnapshot(): List<IncomingEvent> = onExecutor { ArrayList(receiveBuffer) }

    private suspend fun <T> onExecutor(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            executor.execute(
                { continuation.resume(block()) },
                onRejected = { continuation.resumeWithException(IllegalStateException("the manager is closed")) },
            )
        }

    internal fun emitFromEmitter(
        name: String,
        args: List<SocketIOValue>,
        flags: EmitFlags,
        ack: ((Throwable?, List<SocketIOValue>) -> Unit)?,
        withError: Boolean,
        handle: AckHandle?,
    ) {
        executor.execute(
            {
                if (handle?.cancelled == true) return@execute
                emitOnExecutor(name, args, flags, ack, withError, handle)
            },
            // The manager was closed: nothing can be sent or acknowledged any more.
            onRejected = { if (ack != null && withError) ack(SocketDisconnectedException(), emptyList()) },
        )
    }

    /** `Socket.emit` after argument validation. */
    private fun emitOnExecutor(
        name: String,
        args: List<SocketIOValue>,
        flags: EmitFlags,
        ack: ((Throwable?, List<SocketIOValue>) -> Unit)?,
        withError: Boolean,
        handle: AckHandle?,
    ) {
        if (options.retries > 0 && !flags.fromQueue && !flags.volatile) {
            addToQueue(name, args, flags, ack, handle)
            return
        }
        val data = SocketIOValue.Array(listOf(SocketIOValue.Text(name)) + args)
        val outgoing = OutgoingEvent(name, args)
        var id: Long? = null
        if (ack != null) {
            id = ids++
            registerAckCallback(id, ack, withError, flags.timeout, outgoing)
            handle?.bind(this, id)
        }
        val packet = SocketIOPacket(SocketIOPacketType.EVENT, namespace, data, id)
        val engine = manager.engine
        val isTransportWritable = engine?.transport?.writable == true
        val isConnected = connectedState && !(engine?.hasPingExpired() ?: false)
        val discard = flags.volatile && !isTransportWritable
        when {
            discard -> intercept { it.onDropped(outgoing, null) }

            isConnected -> {
                notifyOutgoingListeners(outgoing)
                id?.let { acks[it]?.sent = true }
                packet(packet, flags.compress)
                intercept { it.onSent(outgoing) }
            }

            else -> {
                val limits = manager.options.bufferLimits
                val bytes = estimateBytes(data.items)
                val error =
                    when {
                        sendBuffer.size + 1 > limits.maxSendBufferPackets ->
                            SocketBufferLimitException(
                                SocketBufferLimitException.Buffer.SEND_BUFFER,
                                limits.maxSendBufferPackets.toLong(),
                                sendBuffer.size + 1L,
                                false,
                            )

                        sendBufferBytes + bytes > limits.maxSendBufferBytes ->
                            SocketBufferLimitException(SocketBufferLimitException.Buffer.SEND_BUFFER, limits.maxSendBufferBytes, sendBufferBytes + bytes, true)

                        else -> null
                    }
                if (error != null) {
                    rejectEmit(id, outgoing, error)
                    return
                }
                sendBuffer.add(BufferedPacket(packet, flags.compress, bytes, outgoing))
                sendBufferBytes += bytes
                updatePending()
                intercept { it.onBuffered(outgoing) }
            }
        }
    }

    private fun rejectEmit(
        id: Long?,
        outgoing: OutgoingEvent,
        error: SocketBufferLimitException,
    ) {
        intercept { it.onDropped(outgoing, error) }
        // Not a connection error: the manager reports it, sockets do not turn it into connect_error.
        manager.emitToApplication(ManagerEvent.Error(error))
        val entry = id?.let { acks.remove(it) } ?: return
        entry.timer?.cancel()
        updatePending()
        entry.callback(error, emptyList())
    }

    private fun registerAckCallback(
        id: Long,
        rawAck: (Throwable?, List<SocketIOValue>) -> Unit,
        withError: Boolean,
        flagTimeout: Duration?,
        outgoing: OutgoingEvent,
    ) {
        val tracer = manager.options.tracer
        val ack: (Throwable?, List<SocketIOValue>) -> Unit =
            if (tracer == null) {
                rawAck
            } else {
                val name = "socket.io ack ${outgoing.name}"
                val cookie = System.identityHashCode(outgoing)
                tracer.beginAsyncSection(name, cookie)
                val traced: (Throwable?, List<SocketIOValue>) -> Unit = { error, args ->
                    tracer.endAsyncSection(name, cookie)
                    rawAck(error, args)
                }
                traced
            }
        val timeout = flagTimeout ?: options.ackTimeout
        if (timeout == null) {
            acks[id] = AckEntry(withError, ack)
            updatePending()
            return
        }
        val entry = AckEntry(true, ack)
        entry.timer =
            executor.schedule(timeout) {
                if (acks.remove(id) == null) return@schedule
                updatePending()
                val removed = sendBuffer.removeAll { it.packet.id == id }
                if (removed) {
                    recomputeSendBufferBytes()
                    updatePending()
                    intercept { it.onDropped(outgoing, AckTimeoutException()) }
                }
                ack(AckTimeoutException(), emptyList())
            }
        acks[id] = entry
        updatePending()
    }

    /** Withdraws an acknowledgement (and its packet, if still buffered) for a cancelled `emitWithAck`. */
    internal fun withdrawAck(id: Long) {
        val entry = acks.remove(id) ?: return
        entry.timer?.cancel()
        if (sendBuffer.removeAll { it.packet.id == id }) recomputeSendBufferBytes()
        updatePending()
    }

    private fun recomputeSendBufferBytes() {
        sendBufferBytes = sendBuffer.sumOf { it.bytes }
    }

    private fun addToQueue(
        name: String,
        args: List<SocketIOValue>,
        flags: EmitFlags,
        ack: ((Throwable?, List<SocketIOValue>) -> Unit)?,
        handle: AckHandle?,
    ) {
        val limits = manager.options.bufferLimits
        val bytes = estimateBytes(args) + name.length
        val error =
            when {
                queue.size + 1 > limits.maxRetryQueuePackets ->
                    SocketBufferLimitException(SocketBufferLimitException.Buffer.RETRY_QUEUE, limits.maxRetryQueuePackets.toLong(), queue.size + 1L, false)

                queueBytes + bytes > limits.maxRetryQueueBytes ->
                    SocketBufferLimitException(SocketBufferLimitException.Buffer.RETRY_QUEUE, limits.maxRetryQueueBytes, queueBytes + bytes, true)

                else -> null
            }
        if (error != null) {
            intercept { it.onDropped(OutgoingEvent(name, args), error) }
            manager.emitToApplication(ManagerEvent.Error(error))
            ack?.invoke(error, emptyList())
            return
        }
        val packet = QueuedPacket(queueSeq++, name, args, flags.copy(fromQueue = true), bytes, ack)
        packet.callback = { err, responseArgs ->
            inFlightAckIds.remove(packet.id)
            if (queue.firstOrNull() === packet) {
                if (err != null) {
                    if (packet.tryCount > options.retries) {
                        queue.removeAt(0)
                        queueBytes -= packet.bytes
                        updatePending()
                        ack?.invoke(err, emptyList())
                    }
                } else {
                    queue.removeAt(0)
                    queueBytes -= packet.bytes
                    updatePending()
                    ack?.invoke(null, responseArgs)
                }
                packet.pending = false
                drainQueue()
            }
        }
        queue.add(packet)
        queueBytes += bytes
        handle?.bindQueued(this, packet.id)
        updatePending()
        drainQueue()
    }

    /** Removes a queued emit whose `emitWithAck` was cancelled; an in-flight try's acknowledgement is withdrawn too. */
    internal fun withdrawQueued(queueId: Long) {
        val index = queue.indexOfFirst { it.id == queueId }
        if (index < 0) return
        val packet = queue.removeAt(index)
        queueBytes -= packet.bytes
        if (packet.pending) {
            // Ignore the acknowledgement of the try already sent.
            packet.callback = { _, _ -> }
            inFlightAckIds.remove(queueId)?.let { withdrawAck(it) }
        }
        updatePending()
        if (index == 0) drainQueue()
    }

    /** Ack id of the try currently in flight, by queue id. */
    private val inFlightAckIds = HashMap<Long, Long>()

    private fun drainQueue(force: Boolean = false) {
        if (!connectedState || queue.isEmpty()) return
        val packet = queue[0]
        if (packet.pending && !force) return
        packet.pending = true
        packet.tryCount++
        inFlightAckIds[packet.id] = ids
        // The queue always reads the acknowledgement err-first; JavaScript only does so when a
        // timeout applies, which makes the first ack argument an "error" without ackTimeout.
        emitOnExecutor(packet.name, packet.args, packet.flags, { error, args -> packet.callback(error, args) }, withError = true, handle = null)
    }

    /** Calls the outgoing interceptor, isolating its exceptions like any listener's. */
    private inline fun intercept(block: (OutgoingInterceptor) -> Unit) {
        val interceptor = options.outgoingInterceptor ?: return
        try {
            block(interceptor)
        } catch (
            // Errors too, as for listeners: application code must never break the protocol.
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            if (e is VirtualMachineError && e !is StackOverflowError) throw e
            manager.reportListenerError(e)
        }
    }

    private fun notifyOutgoingListeners(event: OutgoingEvent) {
        for (listener in anyOutgoingListeners) manager.deliver { listener.onAny(event.name, event.args) }
    }

    private fun updatePending() {
        pendingFlow.value = sendBuffer.size + queue.size
        pendingAcksFlow.value = acks.size
    }

    private fun dispatchLifecycle(event: SocketEvent) {
        val named: Pair<String, List<SocketIOValue>>? =
            when (event) {
                is SocketEvent.Connected -> "connect" to emptyList()

                is SocketEvent.ConnectError ->
                    "connect_error" to
                        listOf(
                            SocketIOValue.objectOf(
                                "message" to event.error.message.orEmpty(),
                                "data" to (event.error as? SocketConnectException)?.data,
                            ),
                        )

                is SocketEvent.Disconnected -> "disconnect" to listOf(SocketIOValue.Text(event.reason.wireValue))

                is SocketEvent.Received -> null
            }
        lifecycle.dispatch(event, manager)
        if (named != null) {
            val incoming = IncomingEvent(named.first, named.second, null)
            for (entry in namedListeners) {
                if (entry.event != named.first) continue
                if (entry.once && !namedListeners.remove(entry)) continue
                manager.deliver { entry.listener.onEvent(incoming) }
            }
        }
        eventFlow.tryEmit(event)
    }

    // ---- Listening ------------------------------------------------------------------------------

    /**
     * Calls [listener] for every [event] from the server. The reserved names
     * `connect`, `connect_error` and `disconnect` receive the lifecycle
     * events, as in JavaScript; prefer [onConnect], [onConnectError] and
     * [onDisconnect] for typed access.
     */
    public fun on(
        event: String,
        listener: EventListener,
    ): Subscription {
        val entry = NamedListener(event, listener, false)
        namedListeners.add(entry)
        return Subscription { namedListeners.remove(entry) }
    }

    /** Like [on], but only for the next event. */
    public fun once(
        event: String,
        listener: EventListener,
    ): Subscription {
        val entry = NamedListener(event, listener, true)
        namedListeners.add(entry)
        return Subscription { namedListeners.remove(entry) }
    }

    /** Removes [listener] from [event], or every listener of [event] when [listener] is `null` (`socket.off()`). */
    public fun off(
        event: String,
        listener: EventListener? = null,
    ): Socket {
        if (listener == null) {
            namedListeners.removeAll { it.event == event }
        } else {
            // Retry until one entry is removed: a concurrent off() may take the entry we found.
            while (true) {
                val entry = namedListeners.firstOrNull { it.event == event && it.listener === listener } ?: break
                if (namedListeners.remove(entry)) break
            }
        }
        return this
    }

    /** Removes every named listener (`socket.removeAllListeners()`). */
    public fun removeAllListeners(): Socket {
        namedListeners.clear()
        return this
    }

    /** The listeners of [event] (`socket.listeners()`). */
    public fun listeners(event: String): List<EventListener> = namedListeners.filter { it.event == event }.map { it.listener }

    /** Whether [event] has listeners (`socket.hasListeners()`). */
    public fun hasListeners(event: String): Boolean = namedListeners.any { it.event == event }

    /** Calls [listener] when the namespace connects. */
    public fun onConnect(listener: () -> Unit): Subscription = lifecycle.add(SocketEvent.Connected::class.java, false) { listener() }

    /** Calls [listener] with every connection error. */
    public fun onConnectError(listener: (Throwable) -> Unit): Subscription = lifecycle.add(SocketEvent.ConnectError::class.java, false) { listener(it.error) }

    /** Calls [listener] with the reason and details of every disconnection. */
    public fun onDisconnect(listener: (DisconnectReason, DisconnectDetails?) -> Unit): Subscription =
        lifecycle.add(SocketEvent.Disconnected::class.java, false) { listener(it.reason, it.details) }

    /** Adds a listener for every incoming event (`socket.onAny()`). */
    public fun onAny(listener: AnyListener): Socket {
        anyListeners.add(listener)
        return this
    }

    /** Adds a listener for every incoming event before the existing ones (`socket.prependAny()`). */
    public fun prependAny(listener: AnyListener): Socket {
        anyListeners.add(0, listener)
        return this
    }

    /** Removes one catch-all listener, or all of them when [listener] is `null` (`socket.offAny()`). */
    public fun offAny(listener: AnyListener? = null): Socket {
        if (listener == null) anyListeners.clear() else anyListeners.remove(listener)
        return this
    }

    /** The catch-all listeners (`socket.listenersAny()`). */
    public fun listenersAny(): List<AnyListener> = anyListeners.toList()

    /** Adds a listener for every outgoing event (`socket.onAnyOutgoing()`). */
    public fun onAnyOutgoing(listener: AnyListener): Socket {
        anyOutgoingListeners.add(listener)
        return this
    }

    /** Adds an outgoing listener before the existing ones (`socket.prependAnyOutgoing()`). */
    public fun prependAnyOutgoing(listener: AnyListener): Socket {
        anyOutgoingListeners.add(0, listener)
        return this
    }

    /** Removes one outgoing listener, or all of them (`socket.offAnyOutgoing()`). */
    public fun offAnyOutgoing(listener: AnyListener? = null): Socket {
        if (listener == null) anyOutgoingListeners.clear() else anyOutgoingListeners.remove(listener)
        return this
    }

    /** The outgoing listeners (`socket.listenersAnyOutgoing()`). */
    public fun listenersAnyOutgoing(): List<AnyListener> = anyOutgoingListeners.toList()

    /** A cold view of [events] for one event name. */
    public fun flow(event: String): Flow<IncomingEvent> =
        events.filterIsInstance<SocketEvent.Received>().map { it.event }.filter { it.name == event }

    override fun toString(): String = "Socket(namespace=$namespace, state=${stateFlow.value})"

    internal companion object {
        /** Fixed cost of one event in the receive buffer. */
        private const val RETAINED_EVENT_BYTES = 16L

        /** Fixed cost of one text or binary argument in the receive buffer; other values cost 8 bytes already. */
        private const val RETAINED_SLOT_BYTES = 8L

        /** Estimated retained bytes of [values]; [slotBytes] is added for every text and binary value. */
        fun estimateBytes(
            values: List<SocketIOValue>,
            slotBytes: Long = 0,
        ): Long {
            var total = 0L
            val stack = ArrayDeque(values)
            while (stack.isNotEmpty()) {
                total +=
                    when (val value = stack.removeLast()) {
                        is SocketIOValue.Text -> slotBytes + value.value.length.toLong() * 2

                        is SocketIOValue.Binary -> slotBytes + value.size.toLong()

                        is SocketIOValue.Array -> {
                            stack.addAll(value.items)
                            8
                        }

                        is SocketIOValue.Object -> {
                            stack.addAll(value.fields.values)
                            8L + value.fields.keys.sumOf { it.length.toLong() * 2 }
                        }

                        else -> 8
                    }
            }
            return total
        }
    }
}

/** Per-emit flags, `Flags` in JavaScript. Immutable, so a flagged emitter can be kept and reused. */
internal data class EmitFlags(
    val compress: Boolean = true,
    val volatile: Boolean = false,
    val timeout: Duration? = null,
    val fromQueue: Boolean = false,
) {
    companion object {
        val NONE = EmitFlags()
    }
}

/** Links a suspended `emitWithAck` to its registration for cancellation. */
internal class AckHandle {
    @Volatile var cancelled = false
    private var socket: Socket? = null
    private var id: Long? = null
    private var queueId: Long? = null

    /** Executor only. */
    fun bind(
        socket: Socket,
        id: Long,
    ) {
        this.socket = socket
        this.id = id
    }

    /** Executor only: the emit went to the retry queue. */
    fun bindQueued(
        socket: Socket,
        queueId: Long,
    ) {
        this.socket = socket
        this.queueId = queueId
    }

    /** Executor only. */
    fun withdraw() {
        queueId?.let { socket?.withdrawQueued(it) }
        id?.let { socket?.withdrawAck(it) }
    }
}

/**
 * An emitter with flags: `socket.timeout(…)`, `socket.volatile` and
 * `socket.compress(…)`. Flags combine, and the emitter can be reused.
 */
public class SocketEmitter internal constructor(
    private val socket: Socket,
    private val flags: EmitFlags,
) {
    /** Adds an acknowledgement timeout. */
    public fun timeout(timeout: Duration): SocketEmitter = SocketEmitter(socket, flags.copy(timeout = timeout))

    /** Makes the emit volatile. */
    public val volatile: SocketEmitter get() = SocketEmitter(socket, flags.copy(volatile = true))

    /** Sets the compression flag. */
    public fun compress(compress: Boolean): SocketEmitter = SocketEmitter(socket, flags.copy(compress = compress))

    /** Emits without acknowledgement. */
    public fun emit(
        event: String,
        vararg args: Any?,
    ): SocketEmitter {
        val converted = validate(event, args)
        socket.emitFromEmitter(event, converted, flags, null, withError = false, handle = null)
        return this
    }

    /** Emits and calls [ack] with the acknowledgement or its failure. */
    public fun emit(
        event: String,
        vararg args: Any?,
        ack: AckCallback,
    ): SocketEmitter {
        val converted = validate(event, args)
        val manager = socket.manager
        val callback = { error: Throwable?, response: List<SocketIOValue> ->
            val result = if (error != null) Result.failure(error) else Result.success(response)
            manager.deliver { ack.onAck(result) }
        }
        socket.emitFromEmitter(event, converted, flags, callback, withError = flags.timeout != null, handle = null)
        return this
    }

    /** Emits and suspends until acknowledged; see [Socket.emitWithAck]. */
    public suspend fun emitWithAck(
        event: String,
        vararg args: Any?,
    ): List<SocketIOValue> {
        val converted = validate(event, args)
        val handle = AckHandle()
        return suspendCancellableCoroutine { continuation: CancellableContinuation<List<SocketIOValue>> ->
            continuation.invokeOnCancellation {
                handle.cancelled = true
                socket.manager.executor.execute { handle.withdraw() }
            }
            val callback = { error: Throwable?, response: List<SocketIOValue> ->
                if (error != null) continuation.resumeWithException(error) else continuation.resume(response)
            }
            socket.emitFromEmitter(event, converted, flags, callback, withError = true, handle = handle)
        }
    }

    private fun validate(
        event: String,
        args: Array<out Any?>,
    ): List<SocketIOValue> {
        require(event !in SocketIOPacket.RESERVED_EVENTS) { "\"$event\" is a reserved event name" }
        return args.map { SocketIOValue.of(it) }
    }
}
