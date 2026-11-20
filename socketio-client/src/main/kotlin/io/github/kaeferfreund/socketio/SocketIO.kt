package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.EngineUri
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import java.util.Locale
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
 * Explicit [SocketManager]s remain the recommended style when an app needs
 * control over the connection's lifetime.
 */
public object SocketIO {
    /** The Socket.IO protocol revision (`protocol`). */
    public const val PROTOCOL: Int = SocketIOPacket.PROTOCOL

    private val cache = ConcurrentHashMap<String, SocketManager>()

    /**
     * The socket for the namespace in [uri]'s path. A cached manager for the
     * same scheme, host, port and request path is reused unless `forceNew`
     * is set, `multiplex` is off, or the cached manager already has that
     * namespace (JavaScript opens a new connection then).
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
        val cached = cache[parsed.id]?.takeUnless { it.isClosed }
        val sameNamespace = cached != null && parsed.path in cached.sockets
        val newConnection = options.forceNew || !options.multiplex || sameNamespace
        var effective = options
        if (parsed.query.isNotEmpty() && options.engine.query.isEmpty()) {
            effective = options.newBuilder().apply { query = EngineUri.decodeQuery(parsed.query) }.build()
        }
        val manager =
            if (newConnection) {
                SocketManager(parsed.source, effective)
            } else {
                // A manager closed by the application is replaced, never handed out again.
                cache.compute(parsed.id) { _, existing ->
                    if (existing == null || existing.isClosed) SocketManager(parsed.source, effective) else existing
                }!!
            }
        return manager.socket(parsed.path, socketOptions, setup)
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
        cache.values.remove(manager)
    }

    /** Closes and forgets every cached manager. */
    @JvmStatic
    public fun closeAll() {
        val managers = cache.values.toList()
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
        val scheme = Regex("^(https?|wss?)://", RegexOption.IGNORE_CASE).find(value)
        value =
            if (scheme == null) {
                (location?.protocol ?: "https:") + "//" + value
            } else {
                scheme.value.lowercase(Locale.ROOT) + value.substring(scheme.value.length)
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
