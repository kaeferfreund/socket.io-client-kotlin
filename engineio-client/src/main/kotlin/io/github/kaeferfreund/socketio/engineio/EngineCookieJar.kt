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
         * `parse()` of `engine.io-client`'s Node cookie jar, with the attribute syntax of RFC 6265
         * 5.2: the cookie-pair and every attribute end at a `;` and are trimmed, and attribute names
         * are case-insensitive. Takes the first `name=value`, strips surrounding quotes from the
         * value and reads `Expires` and `Max-Age` (the later attribute wins, as in JavaScript).
         * Returns `null` for input without a name. The Node jar splits on `"; "` only and reads
         * `Expires` and `Max-Age` only in that spelling.
         */
        public fun parse(
            setCookie: String,
            now: Instant = Instant.now(),
        ): EngineCookie? {
            val parts = setCookie.split(';')
            val i = parts[0].indexOf('=')
            if (i == -1) return null
            val name = parts[0].substring(0, i).trim()
            if (name.isEmpty()) return null
            var value = parts[0].substring(i + 1).trim()
            if (value.startsWith('"')) value = value.substring(1, maxOf(1, value.length - 1))
            var expires: Instant? = null
            for (j in 1 until parts.size) {
                val eq = parts[j].indexOf('=')
                if (eq < 0) continue
                val attribute = parts[j].substring(eq + 1).trim()
                when (parts[j].substring(0, eq).trim().lowercase(Locale.ROOT)) {
                    "expires" -> expires = parseExpires(attribute)
                    "max-age" -> expires = attribute.toLongOrNull()?.let { maxAge -> plusSecondsOrNull(now, maxAge) }
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
 * Tells the cookie jar which cookie domains are public suffixes (`com`, `co.uk`, `github.io`):
 * names under which anyone can register a domain, so no cookie may name one as its `Domain`.
 * `socketio-okhttp` answers from OkHttp's copy of the public suffix list; see
 * [EngineClients.publicSuffixes].
 */
public fun interface EnginePublicSuffixes {
    /** Whether [domain] (lowercase IDNA ASCII, without a trailing dot) is a public suffix. */
    public fun isPublicSuffix(domain: String): Boolean
}

/**
 * The per-connection cookie jar `engine.io-client` creates when `withCredentials` is enabled
 * outside a browser. Cookies from every `Set-Cookie` response header are replayed on later
 * polling requests and on the WebSocket handshake of the same engine.
 *
 * The JavaScript jar is unbounded and ignores every attribute but the expiry, which is safe only
 * while all requests go to one server. Polling follows redirects to other origins, so this jar
 * follows RFC 6265 (and RFC 6265bis where noted) instead of the Node jar for the cookies the
 * engine stores:
 * - **Domain.** Without `Domain` a cookie goes back only to the host that set it (host-only);
 *   with it (a leading dot is ignored) to that domain and its subdomains. A `Domain` the host
 *   does not belong to rejects the cookie, and so does a public suffix ([EnginePublicSuffixes];
 *   without one, only single labels such as `com` count): equal to the host, either makes a
 *   host-only cookie instead. On an IP address host only `Domain` equal to that address is
 *   accepted, also host-only, and IP addresses never match a suffix. Hosts are compared in
 *   canonical form: IDNA ASCII, lowercase, no trailing dot, IPv4 as four decimal octets
 *   (`127.1` is `127.0.0.1`), IPv6 compressed. Ports do not scope cookies.
 * - **Path.** A cookie goes only to request paths its `Path` path-matches on a `/` boundary of the
 *   percent-encoded path; without `Path`, the directory of the path that set it. There is no
 *   exception for the URL that set it. Transports scope cookies with the absolute path they
 *   request, also when the configured path is relative.
 * - **Secure.** Such a cookie goes only to secure contexts: `https`/`wss`, and the loopback hosts
 *   `localhost`, `127.0.0.0/8` and `::1`, as in browsers. One set from elsewhere is ignored, and a
 *   response from a non-secure context neither replaces nor deletes a live `Secure` cookie it
 *   would shadow (RFC 6265bis).
 * - **Prefixes** (RFC 6265bis, compared case-insensitively). A `__Secure-` cookie needs `Secure`;
 *   a `__Host-` cookie needs `Secure`, no `Domain` and `Path=/`. Others are ignored.
 * - **Identity and order.** A cookie replaces the one with the same name, domain, host-only flag
 *   and path (RFC 6265bis): a `Domain` cookie never replaces a host-only one, nor the other way
 *   round. The `Cookie` header lists longer paths first, and cookies of equal path length in the
 *   order they were created; a replaced cookie keeps its place (RFC 6265 5.4).
 *
 * Unlike the JavaScript jar it also keeps the limits browsers apply: at most 4096 bytes of name
 * and value per cookie (a larger one is ignored), at most 180 cookies per cookie domain and 3000
 * in all. When a domain is full, an expired cookie and then the oldest cookie of that domain make
 * room; when the jar is full, the oldest cookie of the domain holding the most. So one host cannot
 * evict the cookies of another.
 *
 * Thread-safe: HTTP client threads store and read cookies while they follow a polling request's
 * redirects (see [EngineRedirectCookies]), so each response's cookies are stored the moment it
 * arrives and each hop reads the current cookies. Every method holds the jar's own lock, which is
 * never held while calling out of the jar.
 */
public class EngineCookieJar internal constructor(
    private val clock: () -> Instant,
    private val publicSuffixes: EnginePublicSuffixes?,
) {
    /** A jar without a public suffix list, which refuses only single-label cookie domains. */
    @JvmOverloads
    public constructor(clock: () -> Instant = Instant::now) : this(clock, null)

    /** A stored cookie and its scope; `null` scopes (the JavaScript API) match every request. */
    private class Entry(
        val cookie: EngineCookie,
        val scope: CookieScope?,
    ) {
        /** The cookie domain the per-domain limit counts it for; unscoped cookies share one. */
        val limitDomain: String get() = scope?.domain.orEmpty()
    }

    private val lock = Any()

    /** In creation order: a replaced cookie keeps its place. Guarded by [lock]. */
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
    public fun parseCookies(setCookieHeaders: List<String>): Unit = synchronized(lock) { store(setCookieHeaders, null) }

    /** [parseCookies] for a response to [requestUrl], which scopes the cookies; nothing is stored for a URL without a host. */
    internal fun parseCookies(
        setCookieHeaders: List<String>,
        requestUrl: String,
    ) {
        if (setCookieHeaders.isEmpty()) return
        val url = CookieUrl.parse(requestUrl) ?: return
        synchronized(lock) { store(setCookieHeaders, url) }
    }

    private fun store(
        setCookieHeaders: List<String>,
        url: CookieUrl?,
    ) {
        for (header in setCookieHeaders) {
            val now = clock()
            val cookie = EngineCookie.parse(header, now) ?: continue
            if (!cookie.name.isPrintableAscii() || !cookie.value.isPrintableAscii()) continue
            // Printable ASCII: one byte per character.
            if (cookie.name.length + cookie.value.length > CookieLimits.MAX_COOKIE_BYTES) continue
            val scope = if (url == null) null else CookieScope.of(header, cookie.name, url, publicSuffixes) ?: continue
            // RFC 6265bis 5.7: a non-secure response cannot replace or delete a live Secure cookie it shadows.
            if (url != null && scope != null && !url.secure && shadowsSecureCookie(cookie.name, scope, now)) continue
            val key = scope?.key(cookie.name) ?: cookie.name
            // A cookie that arrives expired (`Max-Age=0`, a past `Expires`) only deletes its
            // namesake, as in a browser; stored, it could make a full jar evict a live cookie.
            if (cookie.expires?.isAfter(now) == false) {
                cookies.remove(key)
                continue
            }
            if (key !in cookies) makeRoom(scope?.domain.orEmpty())
            cookies[key] = Entry(cookie, scope)
        }
    }

    private fun shadowsSecureCookie(
        name: String,
        scope: CookieScope,
        now: Instant,
    ): Boolean =
        cookies.values.any { entry ->
            val existing = entry.scope
            existing != null &&
                existing.secure &&
                entry.cookie.name == name &&
                entry.cookie.expires?.isAfter(now) != false &&
                scope.shadows(existing)
        }

    /** Frees a place for a new cookie of [domain]; see the class documentation. */
    private fun makeRoom(domain: String) {
        if (count(domain) >= CookieLimits.MAX_COOKIES_PER_DOMAIN) {
            removeExpired()
            if (count(domain) >= CookieLimits.MAX_COOKIES_PER_DOMAIN) removeOldest(domain)
        }
        if (cookies.size >= CookieLimits.MAX_COOKIES) {
            removeExpired()
            if (cookies.size >= CookieLimits.MAX_COOKIES) {
                removeOldest(cookies.values.groupingBy { it.limitDomain }.eachCount().maxBy { it.value }.key)
            }
        }
    }

    private fun count(domain: String): Int = cookies.values.count { it.limitDomain == domain }

    private fun removeOldest(domain: String) {
        cookies.remove(cookies.entries.first { it.value.limitDomain == domain }.key)
    }

    private fun removeExpired() {
        val now = clock()
        cookies.values.removeAll { entry -> entry.cookie.expires?.isBefore(now) == true }
    }

    private fun String.isPrintableAscii(): Boolean = all { it in ' '..'~' }

    /** The unexpired cookies, dropping expired ones. */
    public val current: List<EngineCookie>
        get() =
            synchronized(lock) {
                removeExpired()
                cookies.values.map { it.cookie }
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
        val matching =
            synchronized(lock) {
                removeExpired()
                cookies.values.filter { entry -> entry.scope?.matches(url) ?: (unscopedHost == null || unscopedHost == url.host) }
            }
        // RFC 6265 5.4 step 2: longer paths first, equal lengths in creation order (a stable sort of
        // the jar's order). Unscoped cookies, which match every path, count as `Path=/`.
        return header(matching.sortedByDescending { it.scope?.path?.length ?: 1 }.map { it.cookie })
    }

    private fun header(cookies: List<EngineCookie>): String? = cookies.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
}

/** The parts of a request URL cookies are scoped by. */
internal class CookieUrl private constructor(
    /** In [EngineHosts.canonical] form. */
    val host: String,
    /** The percent-encoded path, `/` when empty. */
    val path: String,
    /** `https`/`wss`, or a loopback host, which browsers treat as a secure context. */
    val secure: Boolean,
) {
    companion object {
        /** Splits `scheme://[userinfo@]host[:port][path][?query][#fragment]`; `null` without a scheme or a valid host. */
        fun parse(url: String): CookieUrl? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = url.substring(0, schemeEnd).lowercase(Locale.ROOT)
            val authorityStart = schemeEnd + 3
            val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
            val authority = url.substring(authorityStart, authorityEnd).substringAfterLast('@')
            val rawHost =
                if (authority.startsWith('[')) {
                    // An IPv6 literal: a `[` without its `]`, or anything but a port after it, is no host.
                    val close = authority.indexOf(']')
                    if (close < 0 || (close + 1 < authority.length && authority[close + 1] != ':')) return null
                    authority.substring(0, close + 1)
                } else {
                    authority.substringBefore(':')
                }
            val host = EngineHosts.canonical(rawHost) ?: return null
            val pathEnd = url.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it < 0) url.length else it }
            val path = url.substring(authorityEnd, pathEnd).takeIf { it.startsWith('/') } ?: "/"
            return CookieUrl(host, path, scheme == "https" || scheme == "wss" || EngineHosts.isLoopback(host))
        }
    }
}

/** Where a stored cookie may be sent (RFC 6265 5.3). */
private class CookieScope(
    /** The host (host-only) or domain the cookie belongs to. */
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val secure: Boolean,
) {
    /** RFC 6265bis 5.7: name, domain, host-only flag and path identify a stored cookie. */
    fun key(name: String): String = "$name;$domain;$hostOnly;$path"

    /**
     * RFC 6265bis 5.7, for a cookie set from a non-secure context: whether this new cookie would
     * shadow the [existing] Secure cookie of its name, because either cookie's domain
     * domain-matches the other's and this cookie's path path-matches the existing cookie's path.
     */
    fun shadows(existing: CookieScope): Boolean =
        (domainMatches(domain, existing.domain) || domainMatches(existing.domain, domain)) && pathMatches(path, existing.path)

    /** RFC 6265 5.4 step 1: host (host-only or domain-match), path-match and `Secure`. */
    fun matches(url: CookieUrl): Boolean {
        if (secure && !url.secure) return false
        val hostMatches = if (hostOnly) url.host == domain else domainMatches(url.host, domain)
        return hostMatches && pathMatches(url.path, path)
    }

    companion object {
        /** The scope of the cookie [name] in [setCookie] received for [url], or `null` when the cookie must be ignored. */
        fun of(
            setCookie: String,
            name: String,
            url: CookieUrl,
            publicSuffixes: EnginePublicSuffixes?,
        ): CookieScope? {
            var domainAttribute: String? = null
            var pathAttribute: String? = null
            var secure = false
            // RFC 6265 5.2: attributes end at `;` and are trimmed, names are case-insensitive, and
            // the last attribute of a name wins.
            for (attribute in setCookie.split(';').drop(1)) {
                val i = attribute.indexOf('=')
                val attributeName = (if (i < 0) attribute else attribute.substring(0, i)).trim()
                val value = if (i < 0) "" else attribute.substring(i + 1).trim()
                when {
                    // RFC 6265 5.2.3: an empty Domain is ignored; a leading dot is removed.
                    attributeName.equals("Domain", ignoreCase = true) -> if (value.isNotEmpty()) domainAttribute = value.removePrefix(".")

                    attributeName.equals("Path", ignoreCase = true) -> pathAttribute = value

                    attributeName.equals("Secure", ignoreCase = true) -> secure = true
                }
            }
            // RFC 6265bis 5.7: a Secure cookie from a non-secure context is ignored.
            if (secure && !url.secure) return null
            if (!prefixAllows(name, secure, domainAttribute, pathAttribute)) return null
            val domain: String
            val hostOnly: Boolean
            val rawDomain = domainAttribute
            if (rawDomain.isNullOrEmpty()) {
                domain = url.host
                hostOnly = true
            } else {
                // A Domain that is not a host name (`..`, `[]`) rejects the cookie.
                domain = EngineHosts.canonical(rawDomain) ?: return null
                // RFC 6265 5.3 steps 5 and 6: an IP address or a public suffix is no cookie domain;
                // equal to the host, it makes a host-only cookie.
                val notADomain =
                    EngineHosts.isIpAddress(url.host) || EngineHosts.isIpAddress(domain) || isPublicSuffix(domain, publicSuffixes)
                if (notADomain && domain != url.host) return null
                if (!notADomain && !domainMatches(url.host, domain)) return null
                hostOnly = notADomain
            }
            return CookieScope(
                domain = domain,
                hostOnly = hostOnly,
                path = pathAttribute?.takeIf { it.startsWith('/') } ?: defaultPath(url.path),
                secure = secure,
            )
        }

        /**
         * RFC 6265bis cookie prefixes, compared case-insensitively: `__Secure-` needs `Secure`;
         * `__Host-` needs `Secure`, no `Domain` attribute and `Path=/`.
         */
        private fun prefixAllows(
            name: String,
            secure: Boolean,
            domainAttribute: String?,
            pathAttribute: String?,
        ): Boolean =
            when {
                name.startsWith("__Secure-", ignoreCase = true) -> secure
                name.startsWith("__Host-", ignoreCase = true) -> secure && domainAttribute == null && pathAttribute == "/"
                else -> true
            }

        /**
         * A single label (`com`, `localhost`) always counts; beyond that [publicSuffixes] decides.
         * One that fails counts every domain as a public suffix, so no cookie widens its scope.
         */
        private fun isPublicSuffix(
            domain: String,
            publicSuffixes: EnginePublicSuffixes?,
        ): Boolean =
            '.' !in domain ||
                try {
                    publicSuffixes?.isPublicSuffix(domain) == true
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    // The check runs on HTTP client threads; an application implementation that fails must not escape there.
                    true
                }

        /** RFC 6265 5.1.3 domain-match; an IP address matches only itself. */
        fun domainMatches(
            host: String,
            domain: String,
        ): Boolean = host == domain || (!EngineHosts.isIpAddress(host) && host.endsWith(".$domain"))

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
 * The [EngineRedirectCookies] of one polling request. The HTTP client's thread stores what each
 * redirect response sets in the engine's live jar the moment it arrives, and reads the `Cookie`
 * header of the next hop from that jar, so concurrent requests of the engine never replay an
 * outdated copy.
 */
internal class RequestCookies(
    firstUrl: String,
    /**
     * The `Cookie` entries of `extraHeaders` as one value, or `null` without one: on the host of
     * [firstUrl] (any scheme or port) it is the whole `Cookie` header, never sent elsewhere.
     */
    private val explicit: String?,
    private val jar: EngineCookieJar?,
) : EngineRedirectCookies {
    private val firstHost = CookieUrl.parse(firstUrl)?.host

    /** The URL the response came from: the last redirect target, or the first URL. */
    var finalUrl: String = firstUrl
        private set

    override fun followRedirect(
        responseUrl: String,
        setCookieHeaders: List<String>,
        nextUrl: String,
    ): List<String> {
        jar?.parseCookies(setCookieHeaders.toList(), responseUrl)
        finalUrl = nextUrl
        val sameHost = firstHost != null && firstHost == CookieUrl.parse(nextUrl)?.host
        if (sameHost && explicit != null) return listOf(explicit)
        return listOfNotNull(jar?.cookieHeader(nextUrl, unscopedHost = firstHost.orEmpty()))
    }

    override fun redirectRefused(
        responseUrl: String,
        setCookieHeaders: List<String>,
    ) {
        jar?.parseCookies(setCookieHeaders.toList(), responseUrl)
    }
}

/** The browser limits [EngineCookieJar] applies. */
internal object CookieLimits {
    /** Cookies per cookie domain, as Chromium keeps per site; RFC 6265 asks for at least 50. */
    const val MAX_COOKIES_PER_DOMAIN: Int = 180

    /** Cookies in all, as Chromium keeps; RFC 6265 asks for at least 3000. */
    const val MAX_COOKIES: Int = 3000

    /** Bytes of name and value per cookie that browsers accept (RFC 6265 asks for at least 4096). */
    const val MAX_COOKIE_BYTES: Int = 4096
}
