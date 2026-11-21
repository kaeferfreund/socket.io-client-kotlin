package io.github.kaeferfreund.socketio.engineio

import java.net.IDN
import java.util.Locale

/**
 * Hosts in one canonical form, so the cookie jar and the HTTP client's redirect rules compare a
 * host the same way whatever spelling its URL used. Nothing here resolves a name.
 */
@InternalSocketIOApi
public object EngineHosts {
    /**
     * [host] in canonical form, or `null` when it is empty or not a valid host:
     * - a name: IDNA ASCII (`bücher.example` → `xn--bcher-kva.example`), lowercase, without a
     *   trailing root dot;
     * - an IPv4 address as the WHATWG URL standard parses it, written as four decimal octets:
     *   `127.1`, `0x7f.0.0.1` and `2130706433` are `127.0.0.1`. A name whose last label merely
     *   looks like a number (`foo.123`, `app.0x1`) stays a name;
     * - an IPv6 literal, with or without brackets, compressed as RFC 5952 writes it and without
     *   brackets; an IPv4-mapped one (`::ffff:127.0.0.1`) as its IPv4 address, as OkHttp writes it.
     */
    public fun canonical(host: String): String? {
        if (host.startsWith('[') || host.endsWith(']')) {
            if (host.length < 2 || !host.startsWith('[') || !host.endsWith(']')) return null
            return ipv6(host.substring(1, host.length - 1))
        }
        if (':' in host) return ipv6(host)
        var result = host
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
        if (result.isEmpty() || result == ".") return null
        return ipv4(result) ?: result
    }

    /** Whether the canonical [host] is an IP address: four decimal octets, or IPv6. */
    internal fun isIpAddress(host: String): Boolean = ':' in host || ipv4(host) == host

    /**
     * Whether the canonical [host] is a loopback host browsers treat as a secure context:
     * `localhost`, `127.0.0.0/8` and `::1`. Names under `localhost` are not.
     */
    internal fun isLoopback(host: String): Boolean = host == "localhost" || host == "::1" || (host.startsWith("127.") && isIpAddress(host))

    /** The WHATWG IPv4 parser: one to four parts, the last filling the remaining bytes; `null` when [host] is no IPv4 address. */
    private fun ipv4(host: String): String? {
        val parts = host.split('.')
        if (parts.size > 4) return null
        val numbers = LongArray(parts.size)
        for ((i, part) in parts.withIndex()) numbers[i] = ipv4Number(part) ?: return null
        for (i in 0 until numbers.size - 1) if (numbers[i] > 255) return null
        if (numbers.last() >= 1L shl (8 * (5 - numbers.size))) return null
        var address = numbers.last()
        for (i in 0 until numbers.size - 1) address += numbers[i] shl (8 * (3 - i))
        return (3 downTo 0).joinToString(".") { ((address shr (8 * it)) and 0xff).toString() }
    }

    /** One IPv4 part: decimal, octal with a leading `0`, hexadecimal with `0x`; `null` when it is none, or exceeds 32 bits. */
    private fun ipv4Number(part: String): Long? {
        if (part.isEmpty()) return null
        var digits = part
        var radix = 10
        if (digits.length >= 2 && digits[0] == '0' && (digits[1] == 'x' || digits[1] == 'X')) {
            digits = digits.substring(2)
            radix = 16
        } else if (digits.length >= 2 && digits[0] == '0') {
            digits = digits.substring(1)
            radix = 8
        }
        var value = 0L
        for (c in digits) {
            val digit = hexDigit(c) ?: return null
            if (digit >= radix) return null
            value = value * radix + digit
            if (value > MAX_IPV4) return null
        }
        return value
    }

    /** The WHATWG IPv6 parser, written by RFC 5952; `null` when [literal] is no IPv6 address. */
    private fun ipv6(literal: String): String? {
        val groups = Ipv6Parser(literal).parse() ?: return null
        val mapped = (0 until 5).all { groups[it] == 0 } && groups[5] == 0xffff
        if (mapped) return listOf(groups[6] shr 8, groups[6] and 0xff, groups[7] shr 8, groups[7] and 0xff).joinToString(".")
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

    /** One run of the WHATWG IPv6 parser over [input]. */
    private class Ipv6Parser(
        private val input: String,
    ) {
        private val groups = IntArray(8)
        private var pointer = 0
        private var pieceIndex = 0
        private var compress = -1

        private val c: Char? get() = input.getOrNull(pointer)

        fun parse(): IntArray? {
            if (c == ':') {
                if (input.getOrNull(pointer + 1) != ':') return null
                pointer += 2
                compress = ++pieceIndex
            }
            while (c != null) {
                if (pieceIndex == 8) return null
                if (c == ':') {
                    if (compress != -1) return null
                    pointer++
                    compress = ++pieceIndex
                    continue
                }
                var value = 0
                var length = 0
                while (length < 4) {
                    val digit = c?.let(::hexDigit) ?: break
                    value = value * 16 + digit
                    pointer++
                    length++
                }
                if (c == '.') {
                    if (length == 0) return null
                    pointer -= length
                    if (pieceIndex > 6 || !embeddedIpv4()) return null
                    break
                }
                if (c == ':') {
                    pointer++
                    if (c == null) return null
                } else if (c != null) {
                    return null
                }
                groups[pieceIndex++] = value
            }
            if (compress != -1) {
                var swaps = pieceIndex - compress
                pieceIndex = 7
                while (pieceIndex != 0 && swaps > 0) {
                    val swapped = groups[compress + swaps - 1]
                    groups[compress + swaps - 1] = groups[pieceIndex]
                    groups[pieceIndex] = swapped
                    pieceIndex--
                    swaps--
                }
            } else if (pieceIndex != 8) {
                return null
            }
            return groups
        }

        /** The dotted-decimal IPv4 address that ends an IPv6 literal (`::ffff:127.0.0.1`). */
        private fun embeddedIpv4(): Boolean {
            var numbersSeen = 0
            while (c != null) {
                if (numbersSeen > 0) {
                    if (c == '.' && numbersSeen < 4) pointer++ else return false
                }
                if (c?.let { it in '0'..'9' } != true) return false
                var piece = -1
                while (true) {
                    val digit = c?.takeIf { it in '0'..'9' } ?: break
                    val number = digit - '0'
                    piece =
                        when (piece) {
                            -1 -> number
                            0 -> return false
                            else -> piece * 10 + number
                        }
                    if (piece > 255) return false
                    pointer++
                }
                groups[pieceIndex] = groups[pieceIndex] * 0x100 + piece
                numbersSeen++
                if (numbersSeen == 2 || numbersSeen == 4) pieceIndex++
            }
            return numbersSeen == 4
        }
    }

    /** An ASCII hexadecimal digit's value, else `null`. */
    private fun hexDigit(c: Char): Int? =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> null
        }

    private const val MAX_IPV4 = 0xffffffffL
}
