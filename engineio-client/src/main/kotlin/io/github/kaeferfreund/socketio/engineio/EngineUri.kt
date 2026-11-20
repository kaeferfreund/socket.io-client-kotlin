package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import kotlin.math.ceil

/**
 * URI and query helpers with the exact behaviour of `engine.io-client`
 * (`contrib/parseuri.ts`, `contrib/parseqs.ts`, `util.ts`).
 */
public object EngineUri {
    /** Every part `parseuri` returns (its `parts` array plus `pathNames` and `queryKey`). */
    public class Parsed internal constructor(
        /** The input. */
        public val source: String,
        /** `http`, `https`, `ws`, `wss` or empty when the input had no scheme. */
        public val protocol: String,
        public val authority: String,
        public val userInfo: String,
        public val user: String,
        public val password: String,
        /** Host name; IPv6 addresses without brackets. */
        public val host: String,
        /** Port as written, or empty. */
        public val port: String,
        /** Path plus query and anchor. */
        public val relative: String,
        /** Path, possibly empty. */
        public val path: String,
        public val directory: String,
        public val file: String,
        /** Raw query string without `?`, or empty. */
        public val query: String,
        public val anchor: String,
        /** `true` when the host is a bracketed IPv6 literal. */
        public val ipv6: Boolean,
    ) {
        /** `true` for `https` and `wss`. */
        public val secure: Boolean get() = protocol == "https" || protocol == "wss"

        /** Non-empty path segments, `pathNames` in JavaScript. */
        public val pathNames: List<String>
            get() {
                val names = path.replace(Regex("/{2,9}"), "/").split("/").toMutableList()
                if (path.startsWith("/") || path.isEmpty()) names.removeAt(0)
                if (path.endsWith("/") && names.isNotEmpty()) names.removeAt(names.size - 1)
                return names
            }

        /** Query parameters without decoding, `queryKey` in JavaScript. */
        public val queryKey: Map<String, String>
            get() {
                val data = LinkedHashMap<String, String>()
                Regex("(?:^|&)([^&=]*)=?([^&]*)").findAll(query).forEach { match ->
                    val key = match.groupValues[1]
                    if (key.isNotEmpty()) data[key] = match.groupValues[2]
                }
                return data
            }

        override fun toString(): String = "Parsed(protocol=$protocol, host=$host, port=$port, path=$path, query=$query)"
    }

    // Deliberate deviation from JavaScript: the scheme matches case-insensitively and is lowercased,
    // so `HTTPS://host` selects TLS instead of becoming the host name `HTTPS` (RFC 3986, section 3.1).
    // Also unlike JavaScript, the authority ends at `\` and the host follows its last `@`, as in WHATWG
    // URLs and OkHttp's HttpUrl: `https://good.example\@evil.example` and `https://evil.example:x:y@good.example`
    // both name `good.example`, so an allowlist check with HttpUrl matches the host connected to.
    private val PARSE_URI =
        Regex(
            "^(?:(?![^:@/?#]+:[^:@/]*@)((?i:http|https|ws|wss))://)?((?:(([^:/?#\\\\]*)(?::([^/?#\\\\]*))?)?@)?" +
                "((?:[a-f0-9]{0,4}:){2,7}[a-f0-9]{0,4}|[^:/?#\\\\]*)(?::(\\d*))?)(((/(?:[^?#](?![^?#/]*\\.[^?#/.]+(?:[?#]|$)))*/?)?" +
                "([^?#/]*))(?:\\?([^#]*))?(?:#(.*))?)",
        )

    /**
     * `parse()` of `engine.io-client`: a regex-based parser that accepts the
     * same relaxed inputs (missing scheme, IPv6 hosts in brackets or bare).
     */
    public fun parse(uri: String): Parsed {
        require(uri.length <= 8000) { "URI too long" }
        // Deliberate deviation from JavaScript: `parseuri` takes the first `[` and `]` anywhere in
        // the string as an IPv6 literal, so `https://chat.example.com/?ids[]=1` yields the host
        // `hat.example.co`. Only brackets around the host inside the authority count here.
        val literal = ipv6Literal(uri)
        if (literal != null) {
            val (b, e) = literal
            val str = uri.substring(0, b) + uri.substring(b, e).replace(':', ';') + uri.substring(e)
            match(uri, str, bracketed = true)?.let { return it }
        }
        return checkNotNull(match(uri, uri, bracketed = false))
    }

    /** Indices of `[` and `]` when both lie in the authority, before the first `/`, `?`, `#` or `\` after the scheme. */
    private fun ipv6Literal(uri: String): Pair<Int, Int>? {
        val start = SCHEME_PREFIX.find(uri)?.range?.last?.plus(1) ?: 0
        val end = uri.indexOfAny(AUTHORITY_END, start).let { if (it == -1) uri.length else it }
        val b = uri.indexOf('[', start)
        if (b == -1 || b >= end) return null
        val e = uri.indexOf(']', b)
        return if (e == -1 || e >= end) null else b to e
    }

    /**
     * Applies [PARSE_URI] to [str], which is [uri] with the colons of a
     * bracketed host replaced when [bracketed]. Returns `null` when
     * [bracketed] but the host group is not the bracketed literal.
     */
    private fun match(
        uri: String,
        str: String,
        bracketed: Boolean,
    ): Parsed? {
        val m = PARSE_URI.find(str) ?: error("unparseable URI")
        fun group(i: Int) = m.groups[i]?.value.orEmpty()
        var host = group(6)
        var authority = group(2)
        if (bracketed) {
            // Brackets in the user info, for example, are not an IPv6 host: parse without the substitution.
            if (host.length < 2 || !host.startsWith('[') || !host.endsWith(']')) return null
            host = host.substring(1, host.length - 1).replace(';', ':')
            authority = authority.replace("[", "").replace("]", "").replace(';', ':')
        }
        return Parsed(
            source = if (bracketed) uri else group(0),
            protocol = group(1).lowercase(java.util.Locale.ROOT),
            authority = authority,
            userInfo = group(3),
            user = group(4),
            password = group(5),
            host = host,
            port = group(7),
            relative = group(8),
            path = group(9),
            directory = group(10),
            file = group(11),
            query = group(12),
            anchor = group(13),
            ipv6 = bracketed,
        )
    }

    private val SCHEME_PREFIX = Regex("^(?:http|https|ws|wss)://", RegexOption.IGNORE_CASE)

    private val AUTHORITY_END = charArrayOf('/', '?', '#', '\\')

    /** JavaScript `encodeURIComponent`. */
    public fun encodeURIComponent(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val c = byte.toInt() and 0xff
            if (c < 0x80 && isUnreserved(c.toChar())) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
            }
        }
        return out.toString()
    }

    /** JavaScript `decodeURIComponent`, lenient on malformed escapes. */
    public fun decodeURIComponent(value: String): String =
        try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            value
        }

    private const val HEX = "0123456789ABCDEF"

    private fun isUnreserved(c: Char): Boolean = (c.isLetterOrDigit() && c.code < 128) || c in "-_.!~*'()"

    /** `parseqs.encode`: `key=value` pairs joined with `&`, both URI-component encoded, in insertion order. */
    public fun encodeQuery(query: Map<String, String>): String =
        query.entries.joinToString("&") { (key, value) -> encodeURIComponent(key) + "=" + encodeURIComponent(value) }

    /** `parseqs.decode`: splits on `&` and `=`; a key without value maps to `"undefined"` as in JavaScript. */
    public fun decodeQuery(query: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            val parts = pair.split('=')
            result[decodeURIComponent(parts[0])] = if (parts.size > 1) decodeURIComponent(parts[1]) else "undefined"
        }
        return result
    }

    private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

    /**
     * `randomString()`: part of the current time in base 36 plus three random
     * base-36 characters, used as the cache-busting timestamp parameter.
     */
    public fun randomString(nowMillis: Long = System.currentTimeMillis()): String {
        val time = java.lang.Long.toString(nowMillis, 36)
        val random = CharArray(3) { BASE36[java.util.concurrent.ThreadLocalRandom.current().nextInt(36)] }
        return time.substring(minOf(3, time.length)) + String(random)
    }

    /**
     * `byteLength()` of `engine.io-client`: UTF-8 length of text, and the
     * Base64-inflated length (×1.33, rounded up) of binary data.
     */
    public fun byteLength(data: EngineIOData): Long =
        when (data) {
            is EngineIOData.Text -> utf8Length(data.value)
            is EngineIOData.Binary -> ceil(data.size * 1.33).toLong()
        }

    /** UTF-8 length as `engine.io-client` computes it (a lone surrogate counts 3 bytes). */
    public fun utf8Length(text: String): Long {
        var length = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i].code
            length +=
                when {
                    c < 0x80 -> 1

                    c < 0x800 -> 2

                    c < 0xd800 || c >= 0xe000 -> 3

                    else -> {
                        i++
                        4
                    }
                }
            i++
        }
        return length
    }
}
