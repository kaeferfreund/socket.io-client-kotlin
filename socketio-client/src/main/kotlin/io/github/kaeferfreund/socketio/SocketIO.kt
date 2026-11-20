package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.EngineUri
import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * The JavaScript-style entry point: `io(url)` returns a namespace socket
 * and shares one [SocketManager] per server (`multiplex`), exactly like the
 * `lookup()` function of `socket.io-client`.
 *
 * ```kotlin
 * val chat = SocketIO.io("https://example.com/chat")
 * val admin = SocketIO.io("https://example.com/admin") // same connection
 * ```
 *
 * A shared manager keeps the [SocketManagerOptions] of the call that created
 * it: TLS settings, headers, query, limits and every other connection option
 * of a later call are not applied (see [io]). Explicit [SocketManager]s remain
 * the recommended style when an app needs control over the connection's
 * lifetime or its security settings, or talks to the server as more than one
 * user.
 */
public object SocketIO {
    /** The Socket.IO protocol revision (`protocol`). */
    public const val PROTOCOL: Int = SocketIOPacket.PROTOCOL

    /** A cached manager with the options and query of the call that created it. */
    private class Cached(
        val manager: SocketManager,
        val options: SocketManagerOptions,
        val query: Map<String, String>,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * The socket for the namespace in [uri]'s path. A cached manager for the
     * same scheme, host, port and request path is reused unless `forceNew`
     * is set, `multiplex` is off, or the cached manager already has that
     * namespace (JavaScript opens a new connection then).
     *
     * A reused manager keeps the options of the call that created it; [options]
     * of this call, and the query in [uri], are then ignored, as in
     * JavaScript. When they are not the creator's, a warning is logged to the
     * loggers of both. Pass `forceNew = true` (or use an explicit
     * [SocketManager]) for a connection with its own TLS settings, headers or
     * credentials. A cached manager stays cached until it is closed, with
     * [SocketManager.close] or [closeAll].
     *
     * Query parameters in [uri] become the manager's `query` when none were
     * configured.
     */
    @JvmStatic
    @JvmOverloads
    public fun io(
        uri: String,
        options: SocketManagerOptions = SocketManagerOptions.DEFAULT,
        socketOptions: SocketOptions = SocketOptions.DEFAULT,
        setup: (Socket.() -> Unit)? = null,
    ): Socket {
        val parsed = url(uri, options.engine.path)
        var effective = options
        if (parsed.query.isNotEmpty() && options.engine.query.isEmpty()) {
            effective = options.newBuilder().apply { query = EngineUri.decodeQuery(parsed.query) }.build()
        }
        if (options.forceNew || !options.multiplex) return SocketManager(parsed.source, effective).socket(parsed.path, socketOptions, setup)
        // A manager closed by the application is replaced, never handed out again.
        val cached =
            cache.compute(parsed.id) { _, existing ->
                if (existing == null || existing.manager.isClosed) {
                    Cached(SocketManager(parsed.source, effective), options, effective.engine.query)
                } else {
                    existing
                }
            }!!
        // Claiming the namespace is atomic, so of concurrent lookups for one namespace only one
        // gets the cached manager; JavaScript, single-threaded, cannot interleave check and creation.
        // The others open a new connection, as a later lookup does when the namespace is taken.
        val socket =
            cached.manager.socket(parsed.path, socketOptions, setup, reuse = false)
                ?: return SocketManager(parsed.source, effective).socket(parsed.path, socketOptions, setup)
        if (options !== cached.options || effective.engine.query != cached.query) warnIgnoredOptions(parsed.id, options, cached.manager)
        return socket
    }

    private fun warnIgnoredOptions(
        id: String,
        options: SocketManagerOptions,
        manager: SocketManager,
    ) {
        val message =
            "SocketIO.io() reuses the connection to $id created by an earlier call; the options and query of this call are " +
                "not applied. Use forceNew = true or an explicit SocketManager for a connection with its own settings."
        for (logger in listOf(options.logger, manager.options.logger).distinct()) {
            if (logger.isLoggable(LogLevel.WARN)) logger.log(LogLevel.WARN, "manager", message)
        }
    }

    /** Alias of [io] (`connect`). */
    @JvmStatic
    @JvmOverloads
    public fun connect(
        uri: String,
        options: SocketManagerOptions = SocketManagerOptions.DEFAULT,
        socketOptions: SocketOptions = SocketOptions.DEFAULT,
        setup: (Socket.() -> Unit)? = null,
    ): Socket = io(uri, options, socketOptions, setup)

    /** Drops [manager] from the cache; called when it is closed. */
    internal fun forget(manager: SocketManager) {
        cache.values.removeIf { it.manager === manager }
    }

    /** Closes and forgets every cached manager. */
    @JvmStatic
    public fun closeAll() {
        val managers = cache.values.map { it.manager }
        cache.clear()
        managers.forEach(SocketManager::close)
    }

    /** The base URL relative and scheme-less input is resolved against, `location` in a browser. */
    public class BaseLocation(
        /** For example `"https:"`, as `location.protocol`. */
        public val protocol: String,
        /** Host name, IPv6 in brackets, as `location.hostname`. */
        public val hostname: String,
        /** Port as a string; empty for the default. */
        public val port: String = "",
    ) {
        /** `hostname:port`, as `location.host`. */
        public val host: String get() = if (port.isEmpty()) hostname else "$hostname:$port"
    }

    /** The result of [url]. */
    public class ParsedUrl internal constructor(
        /** The absolute URL. */
        public val source: String,
        /** `http`, `https`, `ws` or `wss`. */
        public val protocol: String,
        /** Host; IPv6 without brackets. */
        public val host: String,
        /** Port, defaulted from the scheme. */
        public val port: String,
        /** The path, which names the namespace; `/` when empty. */
        public val path: String,
        /** Raw query string without `?`. */
        public val query: String,
        /** `protocol://host:port` plus the request path: the manager cache key. */
        public val id: String,
        /** `protocol://host[:port]`. */
        public val href: String,
    )

    /**
     * `url()` of `socket.io-client`: resolves [uri] against [location],
     * defaults the port from the scheme and computes the cache [ParsedUrl.id].
     * Without a location, scheme-less input defaults to `https`, as outside
     * a browser in JavaScript.
     */
    @JvmStatic
    @JvmOverloads
    public fun url(
        uri: String?,
        path: String = "",
        location: BaseLocation? = null,
    ): ParsedUrl {
        var value = uri ?: requireNotNull(location) { "a URL is required without a base location" }.let { it.protocol + "//" + it.host }
        if (value.startsWith("/")) {
            value =
                if (value.startsWith("//")) {
                    (location?.protocol ?: "https:") + value
                } else {
                    requireNotNull(location) { "a relative URL needs a base location" }.host + value
                }
        }
        // Unlike JavaScript, the scheme matches case-insensitively and is lowercased (RFC 3986, section 3.1):
        // `HTTPS://host` would otherwise get a second scheme prefixed and resolve to the host `HTTPS`.
        // The scheme is the one EngineUri.parse finds, which also skips extra `/` and `\` after it as OkHttp's
        // HttpUrl does, so `http:/host` and `http:///host` become `http://host` instead of `https://http:/host`.
        val scheme = EngineUri.parse(value).protocol
        value =
            if (scheme.isEmpty()) {
                (location?.protocol ?: "https:") + "//" + value
            } else {
                scheme + "://" + value.substring(scheme.length + 1).trimStart('/', '\\')
            }
        val parsed = EngineUri.parse(value)
        var port = parsed.port
        if (port.isEmpty()) {
            if (parsed.protocol == "http" || parsed.protocol == "ws") {
                port = "80"
            } else if (parsed.protocol == "https" || parsed.protocol == "wss") {
                port = "443"
            }
        }
        val host = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
        val id = parsed.protocol + "://" + host + ":" + port + path
        val href = parsed.protocol + "://" + host + (if (location != null && location.port == port) "" else ":$port")
        return ParsedUrl(value, parsed.protocol, parsed.host, port, parsed.path.ifEmpty { "/" }, parsed.query, id, href)
    }
}
