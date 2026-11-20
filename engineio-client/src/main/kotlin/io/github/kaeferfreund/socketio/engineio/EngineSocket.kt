package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketOptions
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOParser
import io.github.kaeferfreund.socketio.parser.SocketIOJson
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Engine lifecycle, `readyState` of `engine.io-client`'s `Socket`. */
public enum class EngineState { OPENING, OPEN, CLOSING, CLOSED }

/**
 * An Engine.IO connection: a port of `Socket` (`SocketWithUpgrade`) in
 * `engine.io-client`.
 *
 * All methods must be called on [executor]; the Socket.IO manager guarantees
 * that. Listen with [events] (synchronous, on the executor).
 *
 * @param uri `http(s)://` or `ws(s)://` URL; only scheme, host, port and query are used.
 */
public class EngineSocket(
    uri: String?,
    public val options: EngineOptions,
    public val executor: ProtocolExecutor,
) {
    /** Session id from the handshake; `null` before and after the connection. */
    public var id: String? = null
        private set

    /** The active transport. */
    public var transport: EngineTransport? = null
        private set

    public var readyState: EngineState? = null
        private set

    /** Packets not yet handed to a transport. */
    public val writeBuffer: List<EngineIOPacket> get() = buffer

    private var buffer = ArrayList<EngineIOPacket>()

    /** Synchronous engine events. */
    @InternalSocketIOApi
    public val events: EventEmitter<EngineEvent> = EventEmitter()

    /** `true` while an upgrade is in progress. */
    public var upgrading: Boolean = false
        private set

    /** Remaining transport names; `tryAllTransports` removes failed ones. */
    public val transports: List<String> get() = transportNames

    private val transportNames = ArrayList(options.transports)
    private val secure: Boolean
    public val hostname: String
    public val port: String
    private val path: String
    private val query: Map<String, String>
    private val cookieJar: EngineCookieJar? = if (options.withCredentials) EngineCookieJar() else null
    private val clients: EngineClients? by lazy { options.clients ?: EngineClients.discover() }
    private val logger = options.logger

    private var prevBufferLen = 0
    private var pingInterval: Duration = Duration.ZERO
    private var pingTimeout: Duration = Duration.ZERO
    private var maxPayload: Long? = -1
    private var pingTimeoutTimer: Cancellable? = null

    /** `_pingTimeoutTime`: not armed (Infinity), armed, or already expired (0). */
    private var pingDeadline: ComparableTimeMark? = null
    private var pingDeadlineExpired = false
    private var upgrades: List<String> = emptyList()

    init {
        var hostname = ""
        var port = options.port.orEmpty()
        var secure = options.secure ?: false
        var query = LinkedHashMap(options.query)
        if (uri != null) {
            val parsed = EngineUri.parse(uri)
            hostname = parsed.host
            secure = parsed.secure
            port = parsed.port
            // As in JavaScript (`opts.query = parsedUri.query`), a query in the URL replaces the `query` option.
            if (parsed.query.isNotEmpty()) query = LinkedHashMap(EngineUri.decodeQuery(parsed.query))
        } else if (options.host != null) {
            hostname = EngineUri.parse(options.host).host
        }
        this.secure = secure
        if (hostname.isNotEmpty() && port.isEmpty()) port = if (secure) "443" else "80"
        this.hostname = hostname.ifEmpty { "localhost" }
        this.port = port.ifEmpty { if (secure) "443" else "80" }
        this.path = normalizePath(options.path, options.addTrailingSlash)
        this.query = query
    }

    /** Starts connecting. Called once by the owner right after subscribing to [events]. */
    public fun open() {
        if (transportNames.isEmpty()) {
            // Deferred like `setTimeout(…, 0)`, so listeners attached after open() see it.
            executor.post { events.emit(EngineEvent.Error(EngineIOException("No transports available"))) }
            return
        }
        val name =
            if (options.rememberUpgrade && priorWebsocketSuccess.get() && WebSocketTransport.NAME in transportNames) {
                WebSocketTransport.NAME
            } else {
                transportNames[0]
            }
        readyState = EngineState.OPENING
        val transport =
            try {
                createTransport(name).also { it.open() }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                // A missing HTTP stack or a failing custom transport is a connection error, never a crash.
                executor.post { onError(EngineIOException(e.message ?: "cannot open transport \"$name\"", e)) }
                return
            }
        setTransport(transport)
    }

    private fun createTransport(name: String): EngineTransport {
        val overrides = options.transportOptions[name]
        val query = LinkedHashMap(this.query)
        overrides?.query?.let(query::putAll)
        query["EIO"] = EngineIOParser.PROTOCOL.toString()
        query["transport"] = name
        id?.let { query["sid"] = it }
        val transportOptions =
            TransportOptions(
                hostname = hostname,
                secure = secure,
                port = port,
                path = overrides?.path?.let { normalizePath(it, options.addTrailingSlash) } ?: path,
                query = query,
                timestampRequests = overrides?.timestampRequests ?: options.timestampRequests,
                timestampParam = options.timestampParam,
                forceBase64 = overrides?.forceBase64 ?: options.forceBase64,
                extraHeaders = options.extraHeaders + (overrides?.extraHeaders ?: emptyMap()),
                requestTimeout = overrides?.requestTimeout ?: options.requestTimeout,
                withCredentials = options.withCredentials,
                protocols = options.protocols,
                perMessageDeflateThreshold = options.perMessageDeflateThreshold,
                maxPollingResponseBytes = options.maxPollingResponseBytes,
                executor = executor,
                cookieJar = cookieJar,
                httpClient = clients?.http,
                webSocketClient = clients?.webSocket,
                logger = logger,
            )
        val factory =
            options.transportFactories[name]
                ?: when (name) {
                    PollingTransport.NAME -> PollingTransport.FACTORY
                    WebSocketTransport.NAME -> WebSocketTransport.FACTORY
                    else -> throw IllegalArgumentException("Unknown transport \"$name\"")
                }
        return factory.create(transportOptions)
    }

    private val transportSubscriptions = ArrayList<Cancellable>()

    private fun setTransport(transport: EngineTransport) {
        clearTransportListeners()
        this.transport = transport
        transportSubscriptions +=
            listOf(
                transport.events.on<TransportEvent.Drain, TransportEvent> { onDrain() },
                transport.events.on<TransportEvent.Packet, TransportEvent> { onPacket(it.packet) },
                transport.events.on<TransportEvent.Error, TransportEvent> { onError(it.error) },
                transport.events.on<TransportEvent.Close, TransportEvent> { onClose(EngineCloseReason.TRANSPORT_CLOSE, it.details) },
            )
    }

    private fun clearTransportListeners() {
        transportSubscriptions.forEach(Cancellable::cancel)
        transportSubscriptions.clear()
    }

    private fun onOpen() {
        // A handshake that arrives after close() only lets the buffered packets go out before
        // the close. JavaScript reopens the engine here: it reports "open", starts upgrade
        // probes and extends the heartbeat deadline again on every ping, so a server that
        // never completes the pending write could keep a closed connection alive.
        if (readyState == EngineState.CLOSING) {
            flush()
            return
        }
        readyState = EngineState.OPEN
        priorWebsocketSuccess.set(transport?.name == WebSocketTransport.NAME)
        events.emit(EngineEvent.Open)
        flush()
        if (readyState == EngineState.OPEN && options.upgrade) {
            for (name in upgrades) probe(name)
        }
    }

    private fun onPacket(packet: EngineIOPacket) {
        if (readyState != EngineState.OPENING && readyState != EngineState.OPEN && readyState != EngineState.CLOSING) return
        observe(false, packet)
        events.emit(EngineEvent.PacketReceived(packet))
        events.emit(EngineEvent.Heartbeat)
        when (packet.type) {
            EngineIOPacketType.OPEN -> {
                // JavaScript treats every OPEN packet as a new handshake, so a server repeating it
                // starts another round of upgrade probes each time. Only the first one counts.
                if (id != null) return
                val handshake = parseHandshake(packet.text)
                if (handshake == null) {
                    onError(TransportException("invalid handshake"))
                } else {
                    onHandshake(handshake)
                }
            }

            EngineIOPacketType.PING -> {
                // One pong waiting for the transport answers every ping: JavaScript queues one per
                // ping, so pings during a stalled write would grow the buffer without bound.
                if (!hasUnsentPong()) sendPacket(EngineIOPacketType.PONG, null, EngineIOPacketOptions.DEFAULT, null)
                events.emit(EngineEvent.Ping)
                events.emit(EngineEvent.Pong)
                // A closing engine no longer answers pings (sendPacket drops the pong, as in
                // JavaScript), so the server times the session out by the current deadline.
                // JavaScript still extends that deadline, which lets a server that never
                // completes the pending write keep a closed connection polling forever.
                if (readyState != EngineState.CLOSING) resetPingTimeout()
            }

            EngineIOPacketType.ERROR -> onError(EngineServerException(packet.text))

            EngineIOPacketType.MESSAGE -> packet.data?.let { events.emit(EngineEvent.Message(it)) }

            else -> Unit
        }
    }

    /** Whether a pong is buffered that was not yet handed to the transport. */
    private fun hasUnsentPong(): Boolean = (prevBufferLen until buffer.size).any { buffer[it].type == EngineIOPacketType.PONG }

    private fun onHandshake(handshake: Handshake) {
        // Unlike JavaScript, a transport is probed at most once and never the one in use:
        // each offer opens a connection, and the server controls the list.
        upgrades = filterUpgrades(handshake.upgrades).distinct().filter { it != transport?.name }
        events.emit(EngineEvent.HandshakeReceived(handshake))
        id = handshake.sid
        transport?.query?.set("sid", handshake.sid)
        pingInterval = handshake.pingInterval
        pingTimeout = handshake.pingTimeout
        maxPayload = handshake.maxPayload
        onOpen()
        if (readyState == EngineState.CLOSED) return
        resetPingTimeout()
    }

    /** `_filterUpgrades`: the offered upgrades this client is configured to use. */
    public fun filterUpgrades(upgrades: List<String>): List<String> = upgrades.filter { it in transportNames }

    private fun observe(
        outgoing: Boolean,
        packet: EngineIOPacket,
    ) {
        val observer = options.packetObserver ?: return
        try {
            observer.onPacket(outgoing, packet)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            logger.log(LogLevel.ERROR, "engine", "the packet observer threw", e)
        }
    }

    private fun resetPingTimeout() {
        pingTimeoutTimer?.cancel()
        val delay = pingInterval + pingTimeout
        pingDeadline = executor.timeSource.markNow() + delay
        pingDeadlineExpired = false
        pingTimeoutTimer = executor.schedule(delay) { onClose(EngineCloseReason.PING_TIMEOUT, null) }
    }

    private fun onDrain() {
        val sent = minOf(prevBufferLen, buffer.size)
        buffer.subList(0, sent).clear()
        prevBufferLen = 0
        if (buffer.isEmpty()) events.emit(EngineEvent.Drain) else flush()
    }

    private fun flush() {
        val transport = transport ?: return
        if (readyState != EngineState.CLOSED && transport.writable && !upgrading && buffer.isNotEmpty()) {
            val packets = writablePackets(transport)
            transport.send(ArrayList(packets))
            prevBufferLen = packets.size
            events.emit(EngineEvent.Flush)
        }
    }

    /** `_getWritablePackets`: a polling batch stays under `maxPayload` unless a single packet exceeds it. */
    private fun writablePackets(transport: EngineTransport): List<EngineIOPacket> {
        val max = maxPayload
        val shouldCheck = max != null && max != 0L && transport.name == PollingTransport.NAME && buffer.size > 1
        if (!shouldCheck) return buffer
        var payloadSize = 1L
        for (i in buffer.indices) {
            buffer[i].data?.let { payloadSize += EngineUri.byteLength(it) }
            if (i > 0 && payloadSize > max) return buffer.subList(0, i)
            payloadSize += 2
        }
        return buffer
    }

    /**
     * `_hasPingExpired`: detects a heartbeat deadline that passed while timers
     * were throttled (a sleeping device). The first detection schedules one
     * `"ping timeout"` close; later calls just report `true`.
     */
    public fun hasPingExpired(): Boolean {
        if (pingDeadlineExpired) return true
        val deadline = pingDeadline ?: return false
        val expired = deadline.hasPassedNow() && deadline != executor.timeSource.markNow()
        if (expired) {
            pingDeadlineExpired = true
            executor.post { onClose(EngineCloseReason.PING_TIMEOUT, null) }
        }
        return expired
    }

    /** Sends a message (`send`/`write` in JavaScript); [onFlush] runs when it was handed to the transport. */
    public fun send(
        data: EngineIOData,
        options: EngineIOPacketOptions = EngineIOPacketOptions.DEFAULT,
        onFlush: (() -> Unit)? = null,
    ) {
        sendPacket(EngineIOPacketType.MESSAGE, data, options, onFlush)
    }

    /** Sends a text message. */
    public fun send(text: String): Unit = send(EngineIOData.Text(text))

    private fun sendPacket(
        type: EngineIOPacketType,
        data: EngineIOData?,
        options: EngineIOPacketOptions,
        onFlush: (() -> Unit)?,
    ) {
        if (readyState == EngineState.CLOSING || readyState == EngineState.CLOSED) return
        val packet = EngineIOPacket(type, data, options)
        observe(true, packet)
        events.emit(EngineEvent.PacketCreated(packet))
        buffer.add(packet)
        if (onFlush != null) events.once<EngineEvent.Flush, EngineEvent> { onFlush() }
        flush()
    }

    /**
     * Closes the connection. Buffered packets are flushed first and a running
     * upgrade is allowed to finish, as in JavaScript.
     */
    public fun close() {
        val close = {
            onClose(EngineCloseReason.FORCED_CLOSE, null)
            transport?.close()
        }
        var subscriptions = emptyList<Cancellable>()
        val cleanupAndClose = {
            subscriptions.forEach(Cancellable::cancel)
            close()
        }
        val waitForUpgrade = {
            subscriptions =
                listOf(
                    events.once<EngineEvent.Upgrade, EngineEvent> { cleanupAndClose() },
                    events.once<EngineEvent.UpgradeError, EngineEvent> { cleanupAndClose() },
                )
        }
        if (readyState == EngineState.OPENING || readyState == EngineState.OPEN) {
            readyState = EngineState.CLOSING
            if (buffer.isNotEmpty()) {
                events.once<EngineEvent.Drain, EngineEvent> { if (upgrading) waitForUpgrade() else close() }
            } else if (upgrading) {
                waitForUpgrade()
            } else {
                close()
            }
        }
    }

    /**
     * Closes at once with `"forced close"`, without waiting for buffered packets
     * or a running upgrade. For an owner that is about to stop the executor.
     */
    @InternalSocketIOApi
    public fun forceClose() {
        onClose(EngineCloseReason.FORCED_CLOSE, null)
    }

    /**
     * Closes immediately with `"transport close"`, the reaction of the
     * browser client to the `offline` event. The Android module calls it
     * when the bound network is lost.
     */
    public fun onNetworkLost() {
        onClose(EngineCloseReason.TRANSPORT_CLOSE, CloseDetails("network connection lost"))
    }

    private fun onError(error: EngineIOException) {
        priorWebsocketSuccess.set(false)
        if (options.tryAllTransports && transportNames.size > 1 && readyState == EngineState.OPENING) {
            // JavaScript only drops the failed transport's listeners. It can already be open here
            // (a handshake this client rejects arrives on an open polling transport) and would
            // keep polling without listeners, so it is closed as well.
            val failed = transport
            clearTransportListeners()
            transport = null
            failed?.close()
            transportNames.removeAt(0)
            open()
            return
        }
        events.emit(EngineEvent.Error(error))
        onClose(EngineCloseReason.TRANSPORT_ERROR, error)
    }

    private fun onClose(
        reason: String,
        description: Any?,
    ) {
        if (readyState != EngineState.OPENING && readyState != EngineState.OPEN && readyState != EngineState.CLOSING) return
        pingTimeoutTimer?.cancel()
        pingTimeoutTimer = null
        val transport = transport
        clearTransportListeners()
        transport?.close()
        readyState = EngineState.CLOSED
        id = null
        events.emit(EngineEvent.Close(reason, description))
        buffer = ArrayList()
        prevBufferLen = 0
    }

    private fun probe(name: String) {
        var transport: EngineTransport? =
            try {
                createTransport(name)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                events.emit(EngineEvent.UpgradeError(EngineIOException("probe error: ${e.message}", e), name))
                return
            }
        var failed = false

        // Whether this probe set `upgrading` and paused the current transport.
        var pausing = false
        priorWebsocketSuccess.set(false)
        val subscriptions = ArrayList<Cancellable>()
        val cleanup = {
            subscriptions.forEach(Cancellable::cancel)
            subscriptions.clear()
        }

        fun freezeTransport() {
            if (failed) return
            failed = true
            cleanup()
            transport?.close()
            transport = null
            if (pausing) {
                // JavaScript leaves `upgrading` set and the polling transport paused when the
                // probe fails after its pong: nothing is written or polled any more, and close()
                // waits for an upgrade event that already happened. The upgrade is undone instead.
                pausing = false
                upgrading = false
                if (readyState == EngineState.OPEN || readyState == EngineState.CLOSING) {
                    this.transport?.resume()
                    flush()
                }
            }
        }

        val onError = { reason: String ->
            val name0 = transport?.name ?: name
            freezeTransport()
            events.emit(EngineEvent.UpgradeError(EngineIOException("probe error: $reason"), name0))
        }

        val onTransportOpen = onTransportOpen@{
            if (failed) return@onTransportOpen
            val probeTransport = transport ?: return@onTransportOpen
            probeTransport.send(listOf(EngineIOPacket(EngineIOPacketType.PING, "probe")))
            subscriptions +=
                probeTransport.events.once<TransportEvent.Packet, TransportEvent> { event ->
                    if (failed) return@once
                    val msg = event.packet
                    if (msg.type == EngineIOPacketType.PONG && msg.text == "probe") {
                        upgrading = true
                        pausing = true
                        events.emit(EngineEvent.Upgrading(probeTransport))
                        if (transport == null) return@once
                        priorWebsocketSuccess.set(probeTransport.name == WebSocketTransport.NAME)
                        this.transport?.pause {
                            if (failed) return@pause
                            if (readyState == EngineState.CLOSED) return@pause
                            cleanup()
                            setTransport(probeTransport)
                            probeTransport.send(listOf(EngineIOPacket(EngineIOPacketType.UPGRADE)))
                            events.emit(EngineEvent.Upgrade(probeTransport))
                            transport = null
                            pausing = false
                            upgrading = false
                            flush()
                        }
                    } else {
                        // JavaScript leaves the failed probe transport open here; it is closed instead.
                        freezeTransport()
                        events.emit(EngineEvent.UpgradeError(EngineIOException("probe error"), probeTransport.name))
                    }
                }
        }

        val probeTransport = transport!!
        subscriptions +=
            listOf(
                probeTransport.events.once<TransportEvent.Open, TransportEvent> { onTransportOpen() },
                probeTransport.events.once<TransportEvent.Error, TransportEvent> { onError(it.error.message.orEmpty()) },
                probeTransport.events.once<TransportEvent.Close, TransportEvent> { onError("transport closed") },
                events.once<EngineEvent.Close, EngineEvent> { onError("socket closed") },
                events.once<EngineEvent.Upgrading, EngineEvent> { event ->
                    val current = transport
                    if (current != null && event.transport.name != current.name) freezeTransport()
                },
            )
        try {
            probeTransport.open()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            onError(e.message.orEmpty())
        }
    }

    private fun parseHandshake(text: String?): Handshake? {
        val json =
            try {
                SocketIOJson.parse(text ?: return null) as? SocketIOValue.Object
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
        val sid = json["sid"]?.string?.takeIf { it.isNotEmpty() } ?: return null
        val upgrades =
            when (val value = json["upgrades"]) {
                null -> emptyList()
                is SocketIOValue.Array -> value.items.map { it.string ?: return null }
                else -> return null
            }
        val interval = milliseconds(json["pingInterval"]) ?: return null
        val timeout = milliseconds(json["pingTimeout"]) ?: return null
        if (interval > options.handshakeLimits.maxPingInterval || timeout > options.handshakeLimits.maxPingTimeout) return null
        val maxPayload =
            when (val value = json["maxPayload"]) {
                null -> null
                is SocketIOValue.Number -> value.double?.takeIf { it >= 0 && it.isFinite() }?.toLong() ?: return null
                else -> return null
            }
        return Handshake(sid, upgrades, interval, timeout, maxPayload)
    }

    private fun milliseconds(value: SocketIOValue?): Duration? {
        val number = value?.double ?: return null
        if (number.isNaN() || number < 0) return null
        return if (number.isInfinite() || number > 1e15) Duration.INFINITE else number.milliseconds
    }

    public companion object {
        /** `Socket.protocol`. */
        public const val PROTOCOL: Int = EngineIOParser.PROTOCOL

        /**
         * `priorWebsocketSuccess`: shared by every engine in the process, like
         * the static property in JavaScript. Read by `rememberUpgrade`.
         */
        public val priorWebsocketSuccess: AtomicBoolean = AtomicBoolean(false)

        internal fun normalizePath(
            path: String,
            addTrailingSlash: Boolean,
        ): String = path.removeSuffix("/") + if (addTrailingSlash) "/" else ""
    }
}
