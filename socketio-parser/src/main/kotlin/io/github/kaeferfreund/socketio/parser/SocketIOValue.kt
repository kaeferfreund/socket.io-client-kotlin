package io.github.kaeferfreund.socketio.parser

import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.IdentityHashMap

/**
 * A JSON value that may also contain binary data: the payload model of the
 * Socket.IO protocol.
 *
 * Values are immutable and safe to share between threads. Every operation on
 * them (equality, hashing, [toString], encoding) is iterative, so arbitrarily
 * deep values received from a peer can never overflow the stack.
 *
 * Numbers follow JavaScript, where there is only one number type: an integral
 * value is stored as a [Long] when it fits, everything else as a [Double].
 * `1`, `1L` and `1.0` are the same value.
 *
 * Object keys keep the order JavaScript gives them: array-index-like keys
 * (`"0"`, `"1"`, …) first in ascending numeric order, then all other keys in
 * insertion order.
 */
public sealed class SocketIOValue {
    /** Hash computed once at construction, bottom-up, so hashing never recurses. */
    internal abstract val cachedHash: Int

    /** JSON `null`. */
    public object Null : SocketIOValue() {
        override val cachedHash: Int = 0

        override fun toString(): String = "null"
    }

    /** JSON `true` or `false`. */
    public class Bool private constructor(
        public val value: Boolean,
    ) : SocketIOValue() {
        override val cachedHash: Int = if (value) 1231 else 1237

        override fun toString(): String = value.toString()

        public companion object {
            public val TRUE: Bool = Bool(true)
            public val FALSE: Bool = Bool(false)

            /** The shared instance for [value]. */
            public fun of(value: Boolean): Bool = if (value) TRUE else FALSE
        }
    }

    /**
     * A JSON number. [value] is a [Long] for integral values in the `Long`
     * range and a [Double] otherwise (including `NaN` and infinities, which
     * JSON encodes as `null`, like `JSON.stringify`).
     */
    public class Number private constructor(
        public val value: kotlin.Number,
    ) : SocketIOValue() {
        override val cachedHash: Int = value.hashCode()

        /** `true` when [value] is a [Long]. */
        public val isIntegral: Boolean get() = value is Long

        override fun toString(): String = SocketIOJson.formatNumber(this)

        public companion object {
            /** A number from a [Long]. */
            public fun of(value: Long): Number = Number(value)

            /** A number from an [Int]. */
            public fun of(value: Int): Number = Number(value.toLong())

            /**
             * A number from a [Double]. Integral doubles that fit a `Long`
             * exactly become a `Long`; `-0.0` becomes `0`, as JSON writes it.
             */
            public fun of(value: Double): Number {
                if (value == Math.rint(value) && !value.isInfinite() && value >= -9.223372036854776E18 && value < 9.223372036854776E18) {
                    val asLong = value.toLong()
                    if (asLong.toDouble() == value) return Number(asLong)
                }
                return Number(value)
            }
        }
    }

    /** A JSON string. */
    public class Text(
        public val value: String,
    ) : SocketIOValue() {
        override val cachedHash: Int = value.hashCode()

        override fun toString(): String = SocketIOJson.stringify(this)
    }

    /**
     * Binary data, sent as a Socket.IO attachment. The bytes are copied on
     * construction and on every read of [bytes].
     */
    public class Binary private constructor(
        private val content: ByteArray,
        @Suppress("UNUSED_PARAMETER") owned: Unit,
    ) : SocketIOValue() {
        public constructor(bytes: ByteArray) : this(bytes.copyOf(), Unit)

        override val cachedHash: Int = content.contentHashCode()

        /** A copy of the bytes. */
        override val bytes: ByteArray get() = content.copyOf()

        /** Number of bytes. */
        public val size: Int get() = content.size

        internal fun unsafeBytes(): ByteArray = content

        override fun toString(): String = "<Binary $size bytes>"

        public companion object {
            /** Wraps [bytes] without copying; the caller must not modify the array afterwards. */
            public fun wrap(bytes: ByteArray): Binary = Binary(bytes, Unit)
        }
    }

    /** A JSON array. */
    public class Array(
        items: List<SocketIOValue>,
    ) : SocketIOValue() {
        /** The elements, in order. */
        public val items: List<SocketIOValue> = java.util.Collections.unmodifiableList(ArrayList(items))

        override val cachedHash: Int = this.items.fold(1) { acc, item -> 31 * acc + item.cachedHash }

        /** Number of elements. */
        public val size: Int get() = items.size

        /** The element at [index], or `null` when out of range. */
        public operator fun get(index: Int): SocketIOValue? = items.getOrNull(index)

        override fun toString(): String = SocketIOJson.stringify(this)
    }

    /** A JSON object. */
    public class Object(
        fields: Map<String, SocketIOValue>,
    ) : SocketIOValue() {
        /** The members in JavaScript property order. */
        public val fields: Map<String, SocketIOValue> = java.util.Collections.unmodifiableMap(javaScriptOrder(fields))

        // Order-independent, like Map equality.
        override val cachedHash: Int = this.fields.entries.fold(0) { acc, (key, value) -> acc + (key.hashCode() xor value.cachedHash) }

        /** Number of members. */
        public val size: Int get() = fields.size

        /** The member [key], or `null` when absent. */
        public operator fun get(key: String): SocketIOValue? = fields[key]

        override fun toString(): String = SocketIOJson.stringify(this)
    }

    final override fun hashCode(): Int = cachedHash

    final override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SocketIOValue) return false
        return structurallyEqual(this, other)
    }

    // ---- Typed accessors -------------------------------------------------

    /** The string of a [Text], else `null`. */
    public val string: String? get() = (this as? Text)?.value

    /** The boolean of a [Bool], else `null`. */
    public val boolean: Boolean? get() = (this as? Bool)?.value

    /** The number as `Long` when it is integral, else `null`. */
    public val long: Long? get() = ((this as? Number)?.value as? Long)

    /** The number as `Int` when it is integral and fits, else `null`. */
    public val int: Int? get() = long?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** Any number as `Double`, else `null`. */
    public val double: Double? get() = (this as? Number)?.value?.toDouble()

    /** A copy of the bytes of a [Binary], else `null`. */
    public open val bytes: ByteArray? get() = null

    /** The members of an [Object], else `null`. */
    public val obj: Map<String, SocketIOValue>? get() = (this as? Object)?.fields

    /** The elements of an [Array], else `null`. */
    public val array: List<SocketIOValue>? get() = (this as? Array)?.items

    /** `true` for [Null]. */
    public val isNull: Boolean get() = this === Null

    /** `true` when this value or anything inside it is [Binary]. */
    public val containsBinary: Boolean get() = hasBinary(this)

    /**
     * This value converted to plain Kotlin types: `null`, `Boolean`,
     * `Long`/`Double`, `String`, `ByteArray`, `List<Any?>` and
     * `Map<String, Any?>`. Binary data is copied, once per [Binary] instance:
     * where the same instance occurs several times (a received attachment
     * referenced by several placeholders), every occurrence is the same
     * `ByteArray`, as JavaScript hands out the same `Buffer`.
     */
    public fun toKotlin(): Any? = toKotlinValue(this)

    public companion object {
        /** `true` */
        public val TRUE: SocketIOValue = Bool.TRUE

        /** `false` */
        public val FALSE: SocketIOValue = Bool.FALSE

        /** An empty object. */
        public val EMPTY_OBJECT: Object = Object(emptyMap())

        /** An empty array. */
        public val EMPTY_ARRAY: Array = Array(emptyList())

        /**
         * Converts a Kotlin/Java value into a [SocketIOValue]:
         *
         * | Input | Result |
         * | --- | --- |
         * | `null`, `Unit` | [Null] |
         * | [SocketIOValue] | itself |
         * | `Boolean` | [Bool] |
         * | `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `BigInteger`, `BigDecimal`, other `Number` | [Number] |
         * | `CharSequence`, `Char`, `Enum` (its name) | [Text] |
         * | `ByteArray`, `ByteBuffer` (remaining bytes) | [Binary] |
         * | `Iterable`, `Array`, primitive arrays, `Sequence` | [Array] |
         * | `Map` (keys via `toString()`) | [Object] |
         * | `Date`, `Instant` | [Text] in the `toISOString()` format, as `JSON.stringify` writes a JavaScript `Date` |
         *
         * Other types are rejected with [IllegalArgumentException]; convert
         * them first (the `socketio-serialization` module does this for
         * `@Serializable` classes). Cyclic input is rejected like
         * `JSON.stringify` rejects circular structures.
         */
        @JvmStatic
        public fun of(value: Any?): SocketIOValue = ValueConverter.convert(value)

        /** An [Object] from pairs, converting each value with [of]. */
        @JvmStatic
        public fun objectOf(vararg pairs: Pair<String, Any?>): Object {
            val map = LinkedHashMap<String, SocketIOValue>(pairs.size)
            for ((key, value) in pairs) map[key] = of(value)
            return Object(map)
        }

        /** An [Array] from values, converting each with [of]. */
        @JvmStatic
        public fun arrayOf(vararg values: Any?): Array = Array(values.map { of(it) })
    }
}

/** Shorthand for [SocketIOValue.objectOf]. */
public fun socketIOObject(vararg pairs: Pair<String, Any?>): SocketIOValue.Object = SocketIOValue.objectOf(*pairs)

/** Shorthand for [SocketIOValue.arrayOf]. */
public fun socketIOArray(vararg values: Any?): SocketIOValue.Array = SocketIOValue.arrayOf(*values)

/** `true` for keys JavaScript treats as array indices: canonical integers 0..2^32-2. */
internal fun isArrayIndexKey(key: String): Boolean {
    val length = key.length
    if (length == 0 || length > 10) return false
    if (key[0] == '0') return length == 1
    var value = 0L
    for (c in key) {
        if (c !in '0'..'9') return false
        value = value * 10 + (c - '0')
    }
    return value <= 4294967294L
}

private fun javaScriptOrder(fields: Map<String, SocketIOValue>): LinkedHashMap<String, SocketIOValue> {
    if (fields.keys.none(::isArrayIndexKey)) return LinkedHashMap(fields)
    val result = LinkedHashMap<String, SocketIOValue>(fields.size)
    fields.keys
        .filter(::isArrayIndexKey)
        .sortedBy { it.toLong() }
        .forEach { result[it] = fields.getValue(it) }
    for ((key, value) in fields) if (!isArrayIndexKey(key)) result[key] = value
    return result
}

// One iterative comparison for all seven value kinds, to stay stack-safe.
@Suppress("CyclomaticComplexMethod")
private fun structurallyEqual(
    a: SocketIOValue,
    b: SocketIOValue,
): Boolean {
    val stack = ArrayDeque<Pair<SocketIOValue, SocketIOValue>>()
    stack.addLast(a to b)
    while (stack.isNotEmpty()) {
        val (x, y) = stack.removeLast()
        if (x === y) continue
        if (x.cachedHash != y.cachedHash) return false
        when (x) {
            is SocketIOValue.Null -> if (y !== SocketIOValue.Null) return false

            is SocketIOValue.Bool -> if (y !is SocketIOValue.Bool || x.value != y.value) return false

            is SocketIOValue.Number -> if (y !is SocketIOValue.Number || x.value != y.value) return false

            is SocketIOValue.Text -> if (y !is SocketIOValue.Text || x.value != y.value) return false

            is SocketIOValue.Binary -> if (y !is SocketIOValue.Binary || !x.unsafeBytes().contentEquals(y.unsafeBytes())) return false

            is SocketIOValue.Array -> {
                if (y !is SocketIOValue.Array || x.size != y.size) return false
                for (i in 0 until x.size) stack.addLast(x.items[i] to y.items[i])
            }

            is SocketIOValue.Object -> {
                if (y !is SocketIOValue.Object || x.size != y.size) return false
                for ((key, value) in x.fields) {
                    val other = y.fields[key] ?: return false
                    stack.addLast(value to other)
                }
            }
        }
    }
    return true
}

internal fun hasBinary(value: SocketIOValue): Boolean {
    val stack = ArrayDeque<SocketIOValue>()
    stack.addLast(value)
    while (stack.isNotEmpty()) {
        when (val v = stack.removeLast()) {
            is SocketIOValue.Binary -> return true
            is SocketIOValue.Array -> v.items.forEach(stack::addLast)
            is SocketIOValue.Object -> v.fields.values.forEach(stack::addLast)
            else -> Unit
        }
    }
    return false
}

/**
 * Converts to plain Kotlin: `null`, `Boolean`, `Long`/`Double`, `String`,
 * `ByteArray`, `List<Any?>` and `Map<String, Any?>` (insertion-ordered).
 *
 * Each [SocketIOValue.Binary] instance is copied once per conversion; every
 * further occurrence of the same instance gets that same array. The decoder
 * resolves all placeholders naming one attachment to one instance, so a peer
 * cannot multiply an attachment by repeating its placeholder, and the result
 * shares the array the way JavaScript shares the one `Buffer`.
 */
private fun toKotlinValue(root: SocketIOValue): Any? {
    val copies = IdentityHashMap<SocketIOValue.Binary, ByteArray>()

    fun leaf(value: SocketIOValue): Any? =
        when (value) {
            is SocketIOValue.Null -> null
            is SocketIOValue.Bool -> value.value
            is SocketIOValue.Number -> value.value
            is SocketIOValue.Text -> value.value
            is SocketIOValue.Binary -> copies.getOrPut(value) { value.bytes }
            is SocketIOValue.Array -> ArrayList<Any?>(value.size)
            is SocketIOValue.Object -> LinkedHashMap<String, Any?>(value.size)
        }
    val result = leaf(root)
    val stack = ArrayDeque<Pair<SocketIOValue, Any?>>()
    stack.addLast(root to result)
    while (stack.isNotEmpty()) {
        val (source, target) = stack.removeLast()
        when (source) {
            is SocketIOValue.Array -> {
                @Suppress("UNCHECKED_CAST")
                val list = target as MutableList<Any?>
                for (item in source.items) {
                    val converted = leaf(item)
                    list.add(converted)
                    if (item is SocketIOValue.Array || item is SocketIOValue.Object) stack.addLast(item to converted)
                }
            }

            is SocketIOValue.Object -> {
                @Suppress("UNCHECKED_CAST")
                val map = target as MutableMap<String, Any?>
                for ((key, item) in source.fields) {
                    val converted = leaf(item)
                    map[key] = converted
                    if (item is SocketIOValue.Array || item is SocketIOValue.Object) stack.addLast(item to converted)
                }
            }

            else -> Unit
        }
    }
    return result
}

/** Iterative Any? → SocketIOValue conversion with cycle detection. */
internal object ValueConverter {
    private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private class Frame(
        val source: Any,
        val iterator: Iterator<Any?>,
        val keys: Iterator<Any?>?,
        val items: ArrayList<SocketIOValue>?,
        val fields: LinkedHashMap<String, SocketIOValue>?,
    ) {
        var pendingKey: String? = null
    }

    fun convert(root: Any?): SocketIOValue {
        leafOrNull(root)?.let { return it }
        val inProgress = IdentityHashMap<Any, Unit>()
        val stack = ArrayDeque<Frame>()
        var result: SocketIOValue? = null

        fun open(container: Any): Frame {
            require(inProgress.put(container, Unit) == null) { "Converting circular structure to a Socket.IO value" }
            return when {
                container is Map<*, *> -> {
                    val entries = container.entries.toList()
                    Frame(container, entries.map { it.value }.iterator(), entries.map { it.key }.iterator(), null, LinkedHashMap(entries.size))
                }

                OrgJson.isObject(container) -> {
                    val keys = OrgJson.keys(container)
                    Frame(container, keys.map { OrgJson.get(container, it) }.iterator(), keys.iterator(), null, LinkedHashMap(keys.size))
                }

                OrgJson.isArray(container) -> Frame(container, OrgJson.elements(container).iterator(), null, ArrayList(), null)

                else -> Frame(container, iterate(container), null, ArrayList(), null)
            }
        }

        stack.addLast(open(root!!))
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.iterator.hasNext()) {
                val next = frame.iterator.next()
                val key = if (frame.keys != null) keyString(frame.keys.next()) else null
                val leaf = leafOrNull(next)
                if (leaf != null) {
                    if (frame.fields != null) frame.fields[key!!] = leaf else frame.items!!.add(leaf)
                } else {
                    frame.pendingKey = key
                    stack.addLast(open(next!!))
                }
                continue
            }
            stack.removeLast()
            inProgress.remove(frame.source)
            val built: SocketIOValue = if (frame.fields != null) SocketIOValue.Object(frame.fields) else SocketIOValue.Array(frame.items!!)
            val parent = stack.lastOrNull()
            if (parent == null) {
                result = built
            } else if (parent.fields != null) {
                parent.fields[parent.pendingKey!!] = built
            } else {
                parent.items!!.add(built)
            }
        }
        return result!!
    }

    private fun keyString(key: Any?): String =
        when (key) {
            null -> "null"
            is String -> key
            else -> key.toString()
        }

    /** Converts scalars; returns `null` for containers. */

    // One branch per supported input type, the conversion table of SocketIOValue.of.

    @Suppress("CyclomaticComplexMethod")
    private fun leafOrNull(value: Any?): SocketIOValue? =
        when (value) {
            null, Unit -> SocketIOValue.Null

            is SocketIOValue -> value

            is Boolean -> SocketIOValue.Bool.of(value)

            is Long -> SocketIOValue.Number.of(value)

            is Int -> SocketIOValue.Number.of(value)

            is Short -> SocketIOValue.Number.of(value.toInt())

            is Byte -> SocketIOValue.Number.of(value.toInt())

            is Double -> SocketIOValue.Number.of(value)

            is Float -> SocketIOValue.Number.of(value.toString().toDouble())

            is java.math.BigInteger ->
                if (value.bitLength() < 64) SocketIOValue.Number.of(value.toLong()) else SocketIOValue.Number.of(value.toDouble())

            is java.math.BigDecimal -> SocketIOValue.Number.of(value.toDouble())

            is kotlin.Number -> SocketIOValue.Number.of(value.toDouble())

            is CharSequence -> SocketIOValue.Text(value.toString())

            is Char -> SocketIOValue.Text(value.toString())

            is Enum<*> -> SocketIOValue.Text(value.name)

            is ByteArray -> SocketIOValue.Binary(value)

            is ByteBuffer -> {
                val copy = ByteArray(value.remaining())
                value.duplicate().get(copy)
                SocketIOValue.Binary.wrap(copy)
            }

            is Date -> SocketIOValue.Text(ISO_INSTANT.format(value.toInstant()))

            is Instant -> SocketIOValue.Text(ISO_INSTANT.format(value))

            is Map<*, *>, is Iterable<*>, is kotlin.Array<*>, is Sequence<*>,
            is IntArray, is LongArray, is ShortArray, is DoubleArray, is FloatArray, is BooleanArray, is CharArray,
            -> null

            else ->
                when {
                    OrgJson.isNull(value) -> SocketIOValue.Null

                    OrgJson.isObject(value) || OrgJson.isArray(value) -> null

                    else -> throw IllegalArgumentException(
                        "Cannot convert ${value::class.java.name} to a Socket.IO value; convert it to a Map, List or SocketIOValue first",
                    )
                }
        }

    private fun iterate(container: Any): Iterator<Any?> =
        when (container) {
            is Iterable<*> -> container.toList().iterator()
            is kotlin.Array<*> -> container.iterator()
            is Sequence<*> -> container.toList().iterator()
            is IntArray -> container.iterator()
            is LongArray -> container.iterator()
            is ShortArray -> container.iterator()
            is DoubleArray -> container.iterator()
            is FloatArray -> container.iterator()
            is BooleanArray -> container.iterator()
            is CharArray -> container.map { it.toString() }.iterator()
            else -> error("not a container: ${container::class.java.name}")
        }
}

/**
 * `org.json` support without a compile-time dependency: Android ships
 * `org.json` in the framework, and apps migrating from socket.io-client-java
 * pass `JSONObject`/`JSONArray` payloads. Binary values inside them stay
 * binary, as with the Java client.
 */
internal object OrgJson {
    private val objectClass: Class<*>? = load("org.json.JSONObject")
    private val arrayClass: Class<*>? = load("org.json.JSONArray")
    private val nullValue: Any? = objectClass?.let { runCatching { it.getField("NULL").get(null) }.getOrNull() }

    private fun load(name: String): Class<*>? =
        try {
            Class.forName(name, false, OrgJson::class.java.classLoader)
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: LinkageError) {
            null
        }

    fun isObject(value: Any): Boolean = objectClass?.isInstance(value) == true

    fun isArray(value: Any): Boolean = arrayClass?.isInstance(value) == true

    fun isNull(value: Any): Boolean = nullValue != null && value === nullValue

    fun keys(value: Any): List<String> {
        val iterator = value.javaClass.getMethod("keys").invoke(value) as Iterator<*>
        return iterator.asSequence().map { it.toString() }.toList()
    }

    fun get(
        value: Any,
        key: String,
    ): Any? = value.javaClass.getMethod("opt", String::class.java).invoke(value, key)

    fun elements(value: Any): List<Any?> {
        val length = value.javaClass.getMethod("length").invoke(value) as Int
        val opt = value.javaClass.getMethod("opt", Int::class.javaPrimitiveType)
        return List(length) { opt.invoke(value, it) }
    }
}
