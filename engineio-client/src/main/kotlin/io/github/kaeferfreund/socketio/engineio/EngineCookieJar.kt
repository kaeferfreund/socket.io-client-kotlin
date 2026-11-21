package io.github.kaeferfreund.socketio.engineio

import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
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
 * the WebSocket handshake of the same engine. Used on the protocol executor; a polling
 * request gets its own [copy], which the HTTP client's thread updates during redirects.
 *
 * Unlike the unbounded JavaScript jar, it keeps the limits browsers apply to
 * one site: at most 180 cookies (the oldest makes room for a new one) and at
 * most 4096 bytes of name and value per cookie (a larger one is ignored).
 *
 * The JavaScript jar ignores every attribute but the expiry, which is safe only
 * while all requests go to one server. Polling follows redirects to other origins,
 * so cookies the engine stores are scoped like in a browser (RFC 6265):
 * - by host: a cookie without `Domain` goes back to the host that set it only; with
 *   `Domain` (a leading dot is ignored) to that domain and its subdomains. A `Domain`
 *   the host does not belong to rejects the cookie, and so does one of a single label
 *   (`Domain=com`), a minimal public-suffix guard: there is no public suffix list, so
 *   `Domain=co.uk` set by a host under `co.uk` is accepted. Such a single label, or an
 *   IP address, equal to the host makes a host-only cookie. IP addresses never match a
 *   suffix. Hosts are compared in their canonical form (IDNA ASCII, compressed IPv6), as
 *   OkHttp writes them. Ports do not scope cookies;
 * - by path: when transports use different paths (`TransportOverrides.path`), a cookie
 *   set by one transport reaches another only if its `Path` matches that transport's
 *   path, compared on `/` boundaries of the percent-encoded path. On the host and path
 *   that set it, a cookie is always replayed, as in JavaScript. Transports scope cookies
 *   with the absolute path they request, also when the configured path is relative;
 * - by `Secure`: such a cookie is sent only over `https`/`wss`, and one set over plain
 *   `http` is ignored (RFC 6265bis). Loopback hosts (`localhost`, `127.0.0.0/8`, `::1`)
 *   count as secure, as in browsers. A response over plain `http` neither replaces nor
 *   deletes a `Secure` cookie of the same name whose domain and path overlap its own.
 *
 * A cookie replaces the one with the same name, domain and path. Of several cookies with the
 * same name, the one with the longer path is sent first (RFC 6265 5.4).
 */
public class EngineCookieJar(
    private val clock: () -> Instant = Instant::now,
) {
    /** A stored cookie and its scope; `null` scopes (the JavaScript API) match every request. */
    private class Entry(
        val cookie: EngineCookie,
        val scope: CookieScope?,
    )

    private val cookies = LinkedHashMap<String, Entry>()

    /**
     * Stores every parseable cookie; a later cookie replaces one with the same name.
     * A cookie whose name or value holds anything but printable ASCII is ignored:
     * RFC 6265 cookie octets are ASCII, and such a value could not be sent back in
     * a request header (Node's HTTP client fails the request instead). So is a
     * cookie larger than browsers accept; when the jar is full, expired cookies
     * and then the oldest one make room. A cookie that is already expired when it
     * arrives removes the stored cookie of its name and is not stored itself.
     *
     * Cookies stored here are not scoped, as in JavaScript: they go with every
     * request of the engine, and on a redirect only to the host of the first request.
     */
    public fun parseCookies(setCookieHeaders: List<String>): Unit = store(setCookieHeaders, null)

    /**
     * [parseCookies] for a response to [requestUrl], which scopes the cookies; nothing is stored for
     * a URL without a host. `Max-Age` counts from [receivedAt], when the response arrived.
     */
    internal fun parseCookies(
        setCookieHeaders: List<String>,
        requestUrl: String,
        receivedAt: Instant? = null,
    ) {
        val url = CookieUrl.parse(requestUrl) ?: return
        store(setCookieHeaders, url, receivedAt)
    }

    /** The jar's clock, to record when a response arrived. */
    internal fun now(): Instant = clock()

    private fun store(
        setCookieHeaders: List<String>,
        url: CookieUrl?,
        receivedAt: Instant? = null,
    ) {
        for (header in setCookieHeaders) {
            val now = receivedAt ?: clock()
            val cookie = EngineCookie.parse(header, now) ?: continue
            if (!cookie.name.isPrintableAscii() || !cookie.value.isPrintableAscii()) continue
            // Printable ASCII: one byte per character.
            if (cookie.name.length + cookie.value.length > CookieLimits.MAX_COOKIE_BYTES) continue
            val scope = if (url == null) null else CookieScope.of(header, url) ?: continue
            // RFC 6265bis 5.7: plain HTTP cannot replace or delete a Secure cookie that overlaps.
            if (url != null && scope != null && !url.secure && shadowsSecureCookie(cookie.name, scope)) continue
            val key = scope?.key(cookie.name) ?: cookie.name
            // A cookie that arrives expired (`Max-Age=0`, a past `Expires`) only deletes its
            // namesake, as in a browser; stored, it could make a full jar evict a live cookie.
            if (cookie.expires?.isAfter(now) == false) {
                cookies.remove(key)
                continue
            }
            if (key !in cookies && cookies.size >= CookieLimits.MAX_COOKIES) makeRoom()
            cookies[key] = Entry(cookie, scope)
        }
    }

    private fun shadowsSecureCookie(
        name: String,
        scope: CookieScope,
    ): Boolean =
        cookies.values.any { entry ->
            val existing = entry.scope
            existing != null && existing.secure && entry.cookie.name == name && existing.overlaps(scope)
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

    /**
     * The `Cookie` header for a request to [requestUrl]: the unexpired cookies whose scope
     * matches it. Unscoped cookies match when [unscopedHost] is `null` or the URL's host.
     */
    internal fun cookieHeader(
        requestUrl: String,
        unscopedHost: String? = null,
    ): String? {
        val url = CookieUrl.parse(requestUrl) ?: return null
        removeExpired()
        val matching = cookies.values.filter { entry -> entry.scope?.matches(url) ?: (unscopedHost == null || unscopedHost == url.host) }
        // RFC 6265 5.4: of cookies with the same name, the longer path goes first, since servers
        // read the first; otherwise the order of creation, as in JavaScript.
        val ordered =
            matching.groupBy { it.cookie.name }.values.flatMap { sameName ->
                if (sameName.size == 1) sameName else sameName.sortedByDescending { entry -> entry.scope?.path?.length ?: 0 }
            }
        return header(ordered.map { it.cookie })
    }

    /** A copy of the stored cookies, for a request that applies them on another thread. */
    internal fun copy(): EngineCookieJar = EngineCookieJar(clock).also { it.cookies.putAll(cookies) }

    private fun header(cookies: List<EngineCookie>): String? = cookies.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
}

/** The parts of a request URL cookies are scoped by. */
internal class CookieUrl private constructor(
    /** Normalized: canonical (IDNA ASCII, compressed IPv6), lowercase, without IPv6 brackets or a trailing dot. */
    val host: String,
    /** The percent-encoded path, `/` when empty. */
    val path: String,
    /** `https`/`wss`, or a loopback host, which browsers treat as a secure context. */
    val secure: Boolean,
) {
    companion object {
        /** Splits `scheme://[userinfo@]host[:port][path][?query][#fragment]`; `null` without a scheme or host. */
        fun parse(url: String): CookieUrl? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = url.substring(0, schemeEnd).lowercase()
            val authorityStart = schemeEnd + 3
            val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
            val authority = url.substring(authorityStart, authorityEnd).substringAfterLast('@')
            val rawHost =
                if (authority.startsWith('[')) {
                    authority.substringBefore(']') + "]"
                } else {
                    authority.substringBefore(':')
                }
            val host = normalizeHost(rawHost) ?: return null
            val pathEnd = url.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it < 0) url.length else it }
            val path = url.substring(authorityEnd, pathEnd).takeIf { it.startsWith('/') } ?: "/"
            return CookieUrl(host, path, scheme == "https" || scheme == "wss" || isLoopback(host))
        }

        /**
         * The host as OkHttp writes it in a URL, so hosts from the engine's URLs and from
         * redirect URLs compare equal: IDNA ASCII (`bücher.example` → `xn--bcher-kva.example`),
         * IPv6 compressed (RFC 5952), lowercase, without brackets or a trailing root dot;
         * `null` when empty or not a valid host.
         */
        fun normalizeHost(host: String): String? {
            var result = host
            if (result.startsWith('[') && result.endsWith(']')) result = result.substring(1, result.length - 1)
            if (':' in result) return canonicalIpv6(result)
            if (result.any { it.code > 0x7f }) {
                result =
                    try {
                        IDN.toASCII(result, IDN.ALLOW_UNASSIGNED)
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
            }
            result = result.lowercase(Locale.ROOT)
            if (result.length > 1 && result.endsWith('.')) result = result.dropLast(1)
            return result.takeIf { it.isNotEmpty() && it != "." }
        }

        /** An IPv6 literal in RFC 5952 form; an IPv4-mapped one as its IPv4 address, as OkHttp does. */
        private fun canonicalIpv6(literal: String): String? {
            // Only a literal: InetAddress resolves anything else through DNS.
            if (literal.isEmpty() || !literal.all { it in IPV6_LITERAL_CHARS }) return null
            val address =
                try {
                    InetAddress.getByName(literal)
                } catch (e: UnknownHostException) {
                    return null
                }
            if (address is Inet4Address) return address.hostAddress
            val bytes = address.address
            val groups = IntArray(8) { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }
            // The first longest run of at least two zero groups becomes `::`.
            var runStart = -1
            var runLength = 0
            var i = 0
            while (i < 8) {
                if (groups[i] != 0) {
                    i++
                    continue
                }
                val start = i
                while (i < 8 && groups[i] == 0) i++
                if (i - start > runLength && i - start >= 2) {
                    runStart = start
                    runLength = i - start
                }
            }
            val result = StringBuilder()
            i = 0
            while (i < 8) {
                if (i == runStart) {
                    result.append("::")
                    i += runLength
                    continue
                }
                if (result.isNotEmpty() && !result.endsWith(":")) result.append(':')
                result.append(Integer.toHexString(groups[i]))
                i++
            }
            return result.toString()
        }

        /**
         * RFC 6265 5.1.3: an IP address only matches itself, never a domain suffix. A host whose
         * last label is a number is an IPv4 address in the WHATWG URL standard (`1.2.3`, `0x7f.1`).
         */
        fun isIpAddress(host: String): Boolean {
            if (':' in host) return true
            val last = host.substringAfterLast('.')
            if (last.isEmpty()) return false
            if (last.startsWith("0x", ignoreCase = true)) return last.drop(2).all { it in HEX_DIGITS }
            return last.all { it in '0'..'9' }
        }

        private const val HEX_DIGITS = "0123456789abcdefABCDEF"
        private const val IPV6_LITERAL_CHARS = "$HEX_DIGITS:."

        private fun isLoopback(host: String): Boolean =
            host == "localhost" || host.endsWith(".localhost") || host == "::1" || (isIpAddress(host) && host.startsWith("127."))
    }
}

/** Where a stored cookie may be sent (RFC 6265 5.3), and where it was set. */
private class CookieScope(
    /** The host (host-only) or domain the cookie belongs to. */
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val secure: Boolean,
    private val setterHost: String,
    private val setterPath: String,
) {
    /** RFC 6265 5.3 step 11: name, domain and path identify a cookie. */
    fun key(name: String): String = "$name;$domain;$path"

    /** RFC 6265bis 5.7 step 16: either domain matches the other, and either path the other. */
    fun overlaps(other: CookieScope): Boolean =
        (domainMatches(domain, other.domain) || domainMatches(other.domain, domain)) &&
            (pathMatches(path, other.path) || pathMatches(other.path, path))

    fun matches(url: CookieUrl): Boolean {
        if (secure && !url.secure) return false
        val hostMatches = if (hostOnly) url.host == domain else domainMatches(url.host, domain)
        if (!hostMatches) return false
        // JavaScript ignores Path, so a cookie is always replayed where it was set.
        return (url.host == setterHost && url.path == setterPath) || pathMatches(url.path, path)
    }

    companion object {
        /** The scope of [setCookie] received for [url], or `null` when the cookie must be ignored. */
        fun of(
            setCookie: String,
            url: CookieUrl,
        ): CookieScope? {
            var domainAttribute: String? = null
            var pathAttribute: String? = null
            var secure = false
            // RFC 6265 5.2: attributes split on `;` with optional whitespace; the last of a name wins.
            for (attribute in setCookie.split(';').drop(1)) {
                val i = attribute.indexOf('=')
                val name = (if (i < 0) attribute else attribute.substring(0, i)).trim()
                val value = if (i < 0) "" else attribute.substring(i + 1).trim()
                when {
                    name.equals("Domain", ignoreCase = true) -> domainAttribute = value
                    name.equals("Path", ignoreCase = true) -> pathAttribute = value
                    name.equals("Secure", ignoreCase = true) -> secure = true
                }
            }
            // RFC 6265bis 5.7: a Secure cookie from an insecure origin is ignored.
            if (secure && !url.secure) return null
            // RFC 6265 5.2.3: an empty Domain attribute is ignored, a leading dot removed.
            val rawDomain = domainAttribute?.removePrefix(".")
            val domain: String
            val hostOnly: Boolean
            if (rawDomain.isNullOrEmpty()) {
                domain = url.host
                hostOnly = true
            } else {
                // A Domain that is not a host name (`..`, `[]`) rejects the cookie.
                domain = CookieUrl.normalizeHost(rawDomain) ?: return null
                // An IP address or a single label (`com`, `localhost`) is never a cookie domain;
                // equal to the host, it makes a host-only cookie (RFC 6265 5.3 steps 5 and 6).
                val invalidDomain = CookieUrl.isIpAddress(url.host) || CookieUrl.isIpAddress(domain) || '.' !in domain
                if (invalidDomain && domain != url.host) return null
                if (!invalidDomain && !domainMatches(url.host, domain)) return null
                hostOnly = invalidDomain
            }
            return CookieScope(
                domain = domain,
                hostOnly = hostOnly,
                path = pathAttribute?.takeIf { it.startsWith('/') } ?: defaultPath(url.path),
                secure = secure,
                setterHost = url.host,
                setterPath = url.path,
            )
        }

        /** RFC 6265 5.1.3 domain-match; an IP address matches only itself. */
        fun domainMatches(
            host: String,
            domain: String,
        ): Boolean = host == domain || (!CookieUrl.isIpAddress(host) && host.endsWith(".$domain"))

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

/**
 * The [EngineRedirectCookies] of one polling request: a copy of the jar taken when the request
 * starts, which the HTTP client's thread updates with what redirect responses set. The engine
 * applies those cookies to its own jar on the executor when the response arrives.
 */
internal class RequestCookies(
    private val firstUrl: String,
    /** `Cookie` values from `extraHeaders`: sent as written, only to the host of [firstUrl]. */
    private val explicit: List<String>,
    private val jar: EngineCookieJar?,
) : EngineRedirectCookies {
    private val firstHost = CookieUrl.parse(firstUrl)?.host

    /** What each redirect response set: its URL, its `Set-Cookie` values and when it arrived. */
    private val received = ArrayList<Triple<String, List<String>, Instant>>()

    /** The URL the response came from: the last redirect target, or [firstUrl]. */
    var finalUrl: String = firstUrl
        private set

    override fun followRedirect(
        responseUrl: String,
        setCookieHeaders: List<String>,
        nextUrl: String,
    ): List<String> {
        if (jar != null && setCookieHeaders.isNotEmpty()) {
            val copy = setCookieHeaders.toList()
            val now = jar.now()
            received += Triple(responseUrl, copy, now)
            jar.parseCookies(copy, responseUrl, now)
        }
        finalUrl = nextUrl
        val sameHost = firstHost != null && firstHost == CookieUrl.parse(nextUrl)?.host
        val headers = if (sameHost) explicit.toMutableList() else ArrayList()
        jar?.cookieHeader(nextUrl, unscopedHost = firstHost ?: "")?.let { headers += it }
        return headers
    }

    /**
     * On the executor, after the client called back: stores what the redirect responses set,
     * with `Max-Age` counted from when each arrived, not from now.
     */
    fun applyTo(target: EngineCookieJar) {
        for ((url, headers, receivedAt) in received) target.parseCookies(headers, url, receivedAt)
    }
}

/** The browser limits [EngineCookieJar] applies. */
internal object CookieLimits {
    /** Cookies per site that Chromium keeps; RFC 6265 asks for at least 50. */
    const val MAX_COOKIES: Int = 180

    /** Bytes of name and value per cookie that browsers accept (RFC 6265 asks for at least 4096). */
    const val MAX_COOKIE_BYTES: Int = 4096
}
