package io.github.kaeferfreund.socketio.engineio

import kotlin.time.Duration

/** Per-transport overrides, the useful subset of `transportOptions` in JavaScript. */
public class TransportOverrides(
    public val query: Map<String, String>? = null,
    public val extraHeaders: Map<String, String>? = null,
    public val requestTimeout: Duration? = null,
    public val timestampRequests: Boolean? = null,
    public val forceBase64: Boolean? = null,
    public val path: String? = null,
)

/** The HTTP and WebSocket stacks transports run on. */
public class EngineClients(
    public val http: EngineHttpClient?,
    public val webSocket: EngineWebSocketClient?,
    /**
     * The public suffix list of the `withCredentials` cookie jar: a cookie whose `Domain` is a
     * public suffix (`co.uk`) is rejected, or host-only when it names the host itself. `null`
     * refuses only single-label domains (`com`).
     */
    public val publicSuffixes: EnginePublicSuffixes?,
) {
    /**
     * [http] and [webSocket], with the public suffixes of whichever of them implements
     * [EnginePublicSuffixes] (`OkHttpEngineClients` does).
     */
    public constructor(
        http: EngineHttpClient?,
        webSocket: EngineWebSocketClient?,
    ) : this(http, webSocket, http as? EnginePublicSuffixes ?: webSocket as? EnginePublicSuffixes)

    /**
     * Supplies default [EngineClients] through `java.util.ServiceLoader`.
     * The `socketio-okhttp` module registers one, so adding it to the
     * classpath is enough to connect.
     */
    public fun interface Provider {
        public fun create(): EngineClients
    }

    public companion object {
        /** The clients of the first registered [Provider], or `null`. */
        public fun discover(): EngineClients? =
            java.util.ServiceLoader
                .load(Provider::class.java, Provider::class.java.classLoader)
                .firstOrNull()
                ?.create()
    }
}

/**
 * Options of an Engine.IO connection, `SocketOptions` of `engine.io-client`.
 * Every default is the JavaScript default.
 */
public class EngineOptions(
    /** Request path; `/engine.io` for a bare engine, the manager passes `/socket.io`. */
    public val path: String = "/engine.io",
    /** Extra query parameters on every request. */
    public val query: Map<String, String> = emptyMap(),
    /** Probe and upgrade to a better transport after connecting. */
    public val upgrade: Boolean = true,
    /** Send binary data as Base64 text. */
    public val forceBase64: Boolean = false,
    /** Name of the cache-busting query parameter. */
    public val timestampParam: String = "t",
    /**
     * Add the cache-busting parameter: `null` means the JavaScript default
     * (on for polling, off for WebSocket).
     */
    public val timestampRequests: Boolean? = null,
    /** Transport names in the order they are tried. */
    public val transports: List<String> = listOf(PollingTransport.NAME, WebSocketTransport.NAME),
    /** Try the next transport when the first fails to open (Socket.IO 4.8). */
    public val tryAllTransports: Boolean = false,
    /** Start with WebSocket when the previous connection upgraded to it successfully. */
    public val rememberUpgrade: Boolean = false,
    /** Timeout of each polling request; `null` for none (JavaScript default). */
    public val requestTimeout: Duration? = null,
    /** Overrides per transport name. */
    public val transportOptions: Map<String, TransportOverrides> = emptyMap(),
    /** Headers on every polling request and on the WebSocket handshake. */
    public val extraHeaders: Map<String, String> = emptyMap(),
    /** Keep and resend cookies within one connection. */
    public val withCredentials: Boolean = false,
    /** WebSocket sub-protocols to offer. */
    public val protocols: List<String> = emptyList(),
    /**
     * `perMessageDeflate.threshold`: frames below this size are not
     * compressed. `null` disables compression on the client side.
     */
    public val perMessageDeflateThreshold: Int? = 1024,
    /** Append a trailing slash to [path]. */
    public val addTrailingSlash: Boolean = true,
    /** Transport implementations by name; built-ins are used for names not listed. */
    public val transportFactories: Map<String, EngineTransport.Factory> = emptyMap(),
    /** HTTP/WebSocket stacks; discovered through [EngineClients.discover] when `null`. */
    public val clients: EngineClients? = null,
    /** Hardening: maximum bytes of one polling response body. */
    public val maxPollingResponseBytes: Long = Long.MAX_VALUE,
    /** Hardening: handshake values above these limits are rejected. */
    public val handshakeLimits: HandshakeLimits = HandshakeLimits(),
    public val logger: SocketLogger = SocketLogger.NONE,
    /** Sees every Engine.IO packet in both directions, on the protocol executor. */
    public val packetObserver: EnginePacketObserver? = null,
    /** Host (optionally `[ipv6]`) when no URI is given (`host`). */
    public val host: String? = null,
    /** Port when no URI is given (`port`); defaults from [secure]. */
    public val port: String? = null,
    /** Use TLS when no URI is given (`secure`). */
    public val secure: Boolean? = null,
) {
    init {
        require(requestTimeout == null || requestTimeout.isPositive()) { "requestTimeout must be positive" }
        require(maxPollingResponseBytes > 0) { "maxPollingResponseBytes must be positive" }
        require(perMessageDeflateThreshold == null || perMessageDeflateThreshold >= 0) { "perMessageDeflateThreshold must not be negative" }
    }

    /** A copy with some values replaced. */
    public fun copy(
        path: String = this.path,
        query: Map<String, String> = this.query,
        transports: List<String> = this.transports,
        extraHeaders: Map<String, String> = this.extraHeaders,
        clients: EngineClients? = this.clients,
        logger: SocketLogger = this.logger,
        packetObserver: EnginePacketObserver? = this.packetObserver,
    ): EngineOptions =
        EngineOptions(
            path, query, upgrade, forceBase64, timestampParam, timestampRequests, transports, tryAllTransports, rememberUpgrade,
            requestTimeout, transportOptions, extraHeaders, withCredentials, protocols, perMessageDeflateThreshold, addTrailingSlash,
            transportFactories, clients, maxPollingResponseBytes, handshakeLimits, logger, packetObserver, host, port, secure,
        )
}

/**
 * Bounds for the untrusted values of the server's handshake. The defaults
 * accept everything a real server sends; a value outside them fails the
 * connection with a transport error instead of arming absurd timers.
 */
public class HandshakeLimits(
    public val maxPingInterval: Duration = Duration.INFINITE,
    public val maxPingTimeout: Duration = Duration.INFINITE,
)

/**
 * Observes Engine.IO packets for debugging, logging or tests: the
 * counterpart of listening to `packetCreate` and `packet` on a JavaScript
 * engine. Called on the protocol executor; keep it fast.
 */
public fun interface EnginePacketObserver {
    /** [outgoing] is `true` for packets created by the client, `false` for packets received. */
    public fun onPacket(
        outgoing: Boolean,
        packet: io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket,
    )
}
