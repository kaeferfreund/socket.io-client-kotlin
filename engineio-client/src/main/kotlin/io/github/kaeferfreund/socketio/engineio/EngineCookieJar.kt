package io.github.kaeferfreund.socketio.engineio

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** One cookie as `engine.io-client` keeps it: name, value and optional expiry. */
public class EngineCookie(
    public val name: String,
    public val value: String,
    public val expires: Instant? = null,
) {
    override fun equals(other: Any?): Boolean = other is EngineCookie && other.name == name && other.value == value && other.expires == expires

    override fun hashCode(): Int = (name.hashCode() * 31 + value.hashCode()) * 31 + (expires?.hashCode() ?: 0)

    /** Name and expiry; the value is redacted, since it is usually a credential. */
    override fun toString(): String = "EngineCookie(name=$name, value=***, expires=$expires)"

    public companion object {
        private val EXPIRES_FORMATS =
            listOf(
                DateTimeFormatter.RFC_1123_DATE_TIME,
                DateTimeFormatter.ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz", Locale.US),
                DateTimeFormatter.ofPattern("EEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
                DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US),
            )

        /**
         * `parse()` of `engine.io-client`'s Node cookie jar: splits on `"; "`,
         * takes the first `name=value`, strips surrounding quotes from the
         * value and reads `Expires` and `Max-Age` (the later attribute wins).
         * Returns `null` for input without a name.
         */
        public fun parse(
            setCookie: String,
            now: Instant = Instant.now(),
        ): EngineCookie? {
            val parts = setCookie.split("; ")
            val i = parts[0].indexOf('=')
            if (i == -1) return null
            val name = parts[0].substring(0, i).trim()
            if (name.isEmpty()) return null
            var value = parts[0].substring(i + 1).trim()
            if (value.startsWith('"')) value = value.substring(1, maxOf(1, value.length - 1))
            var expires: Instant? = null
            for (j in 1 until parts.size) {
                val sub = parts[j].split("=")
                if (sub.size != 2) continue
                val attribute = sub[1].trim()
                when (sub[0].trim()) {
                    "Expires" -> expires = parseExpires(attribute)
                    "Max-Age" -> expires = attribute.toLongOrNull()?.let { maxAge -> plusSecondsOrNull(now, maxAge) }
                }
            }
            return EngineCookie(name, value, expires)
        }

        /** `setUTCSeconds` beyond the date range gives an Invalid Date in JavaScript, which never expires. */
        private fun plusSecondsOrNull(
            now: Instant,
            seconds: Long,
        ): Instant? =
            try {
                now.plusSeconds(seconds)
            } catch (e: java.time.DateTimeException) {
                null
            } catch (e: ArithmeticException) {
                null
            }

        private fun parseExpires(text: String): Instant? {
            for (format in EXPIRES_FORMATS) {
                try {
                    return ZonedDateTime.parse(text, format).toInstant()
                } catch (e: DateTimeParseException) {
                    // try the next format
                }
            }
            // `new Date(invalid)` never compares as expired in JavaScript.
            return null
        }
    }
}

/**
 * The per-connection cookie jar `engine.io-client` creates when
 * `withCredentials` is enabled outside a browser. Cookies from every
 * `Set-Cookie` response header are replayed on later polling requests and on
 * the WebSocket handshake of the same engine. Used only on the protocol executor.
 *
 * Unlike the unbounded JavaScript jar, it keeps the limits browsers apply to
 * one site: at most 180 cookies (the oldest makes room for a new one) and at
 * most 4096 bytes of name and value per cookie (a larger one is ignored).
 *
 * The JavaScript jar also ignores `Path`. When transports use different paths
 * (`TransportOverrides.path`), a cookie set by one transport reaches another
 * only if its `Path` matches that transport's path as in a browser (RFC 6265);
 * on the path that set it, it is always replayed.
 */
public class EngineCookieJar(
    private val clock: () -> Instant = Instant::now,
) {
    /** A stored cookie and the path scope it was set with; `null` paths match every request. */
    private class Entry(
        val cookie: EngineCookie,
        val requestPath: String?,
        val path: String?,
    ) {
        fun matches(destination: String): Boolean =
            requestPath == null || destination == requestPath || pathMatches(destination, checkNotNull(path))
    }

    private val cookies = LinkedHashMap<String, Entry>()

    /**
     * Stores every parseable cookie; a later cookie replaces one with the same name.
     * A cookie whose name or value holds anything but printable ASCII is ignored:
     * RFC 6265 cookie octets are ASCII, and such a value could not be sent back in
     * a request header (Node's HTTP client fails the request instead). So is a
     * cookie larger than browsers accept; when the jar is full, expired cookies
     * and then the oldest one make room.
     */
    public fun parseCookies(setCookieHeaders: List<String>): Unit = parseCookies(setCookieHeaders, null)

    /** [parseCookies] for a response to a request for [requestPath], which scopes the cookies. */
    internal fun parseCookies(
        setCookieHeaders: List<String>,
        requestPath: String?,
    ) {
        for (header in setCookieHeaders) {
            val cookie = EngineCookie.parse(header, clock()) ?: continue
            if (!cookie.name.isPrintableAscii() || !cookie.value.isPrintableAscii()) continue
            // Printable ASCII: one byte per character.
            if (cookie.name.length + cookie.value.length > CookieLimits.MAX_COOKIE_BYTES) continue
            if (cookie.name !in cookies && cookies.size >= CookieLimits.MAX_COOKIES) makeRoom()
            cookies[cookie.name] = Entry(cookie, requestPath, requestPath?.let { cookiePath(header, it) })
        }
    }

    private fun makeRoom() {
        removeExpired()
        if (cookies.size >= CookieLimits.MAX_COOKIES) cookies.remove(cookies.keys.first())
    }

    private fun removeExpired() {
        val now = clock()
        cookies.values.removeAll { entry -> entry.cookie.expires?.isBefore(now) == true }
    }

    private fun String.isPrintableAscii(): Boolean = all { it in ' '..'~' }

    /** The unexpired cookies, dropping expired ones. */
    public val current: List<EngineCookie>
        get() {
            removeExpired()
            return cookies.values.map { it.cookie }
        }

    /** The `Cookie` request header value, or `null` when the jar is empty. */
    public fun cookieHeader(): String? = header(current)

    /** The `Cookie` header for a request to [requestPath]: the unexpired cookies whose scope matches it. */
    internal fun cookieHeader(requestPath: String): String? {
        removeExpired()
        return header(cookies.values.filter { it.matches(requestPath) }.map { it.cookie })
    }

    private fun header(cookies: List<EngineCookie>): String? = cookies.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }

    private companion object {
        /**
         * The cookie's path (RFC 6265 5.2.4): the last `Path` attribute when it starts
         * with `/`, otherwise the default path of [requestPath]. Attributes are split
         * on `;` with optional whitespace (RFC 6265 5.2), so `;Path=/x` counts too; the
         * JavaScript parser of name, value and expiry splits on `"; "` only.
         */
        fun cookiePath(
            setCookie: String,
            requestPath: String,
        ): String {
            var path: String? = null
            for (attribute in setCookie.split(';').drop(1)) {
                val i = attribute.indexOf('=')
                if (i >= 0 && attribute.substring(0, i).trim().equals("Path", ignoreCase = true)) path = attribute.substring(i + 1).trim()
            }
            return path?.takeIf { it.startsWith('/') } ?: defaultPath(requestPath)
        }

        /** RFC 6265 5.1.4 default-path: the request path up to its last `/`. */
        private fun defaultPath(requestPath: String): String {
            if (!requestPath.startsWith('/')) return "/"
            val last = requestPath.lastIndexOf('/')
            return if (last == 0) "/" else requestPath.substring(0, last)
        }

        /** RFC 6265 5.1.4 path-match. */
        fun pathMatches(
            requestPath: String,
            cookiePath: String,
        ): Boolean =
            requestPath == cookiePath ||
                (requestPath.startsWith(cookiePath) && (cookiePath.endsWith('/') || requestPath[cookiePath.length] == '/'))
    }
}

/** The browser limits [EngineCookieJar] applies. */
internal object CookieLimits {
    /** Cookies per site that Chromium keeps; RFC 6265 asks for at least 50. */
    const val MAX_COOKIES: Int = 180

    /** Bytes of name and value per cookie that browsers accept (RFC 6265 asks for at least 4096). */
    const val MAX_COOKIE_BYTES: Int = 4096
}
