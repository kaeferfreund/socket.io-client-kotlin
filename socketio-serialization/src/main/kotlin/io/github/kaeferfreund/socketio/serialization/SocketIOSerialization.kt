package io.github.kaeferfreund.socketio.serialization

import io.github.kaeferfreund.socketio.AckCallback
import io.github.kaeferfreund.socketio.IncomingEvent
import io.github.kaeferfreund.socketio.Socket
import io.github.kaeferfreund.socketio.SocketEmitter
import io.github.kaeferfreund.socketio.Subscription
import io.github.kaeferfreund.socketio.parser.SocketIOJson
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * `kotlinx.serialization` support: `@Serializable` classes in and out of
 * Socket.IO values, through the JSON tree of the given [Json] instance
 * (its naming strategy, defaults and `ignoreUnknownKeys` apply).
 *
 * Binary attachments have no JSON form: keep `ByteArray` payloads as
 * separate emit arguments (or as [SocketIOValue.Binary]) rather than inside
 * serializable classes.
 */
public object SocketIOSerialization {
    /** The instance used when none is given: lenient about unknown keys, as servers evolve. */
    public val DefaultJson: Json = Json { ignoreUnknownKeys = true }

    /** Converts a JSON tree to a Socket.IO value; deep nesting cannot overflow the stack. */
    public fun fromJsonElement(element: JsonElement): SocketIOValue = fromElement(element)

    /**
     * Converts a Socket.IO value to a JSON tree; binary data is rejected. The parser
     * accepts any nesting, so this runs on the heap ([DeepRecursiveFunction], as
     * kotlinx.serialization's own tree reader does): a deeply nested payload received
     * from a server cannot overflow the stack.
     */
    public fun toJsonElement(value: SocketIOValue): JsonElement = toElement(value)

    private val fromElement =
        DeepRecursiveFunction<JsonElement, SocketIOValue> { element ->
            when (element) {
                is JsonNull -> SocketIOValue.Null
                is JsonPrimitive -> primitive(element)
                is JsonArray -> SocketIOValue.Array(element.map { callRecursive(it) })
                is JsonObject -> SocketIOValue.Object(element.mapValues { callRecursive(it.value) })
            }
        }

    private val toElement =
        DeepRecursiveFunction<SocketIOValue, JsonElement> { value ->
            when (value) {
                is SocketIOValue.Null -> JsonNull
                is SocketIOValue.Bool -> JsonPrimitive(value.value)
                is SocketIOValue.Number -> JsonPrimitive(value.value)
                is SocketIOValue.Text -> JsonPrimitive(value.value)
                is SocketIOValue.Binary -> throw IllegalArgumentException("binary data cannot be decoded with kotlinx.serialization")
                is SocketIOValue.Array -> JsonArray(value.items.map { callRecursive(it) })
                is SocketIOValue.Object -> JsonObject(value.fields.mapValues { callRecursive(it.value) })
            }
        }

    private fun primitive(element: JsonPrimitive): SocketIOValue =
        when {
            element.isString -> SocketIOValue.Text(element.content)

            element.content == "true" -> SocketIOValue.TRUE

            element.content == "false" -> SocketIOValue.FALSE

            // Json { allowSpecialFloatingPointValues = true } writes these; JSON.stringify turns them into null.
            element.content == "NaN" -> SocketIOValue.Number.of(Double.NaN)

            element.content == "Infinity" -> SocketIOValue.Number.of(Double.POSITIVE_INFINITY)

            element.content == "-Infinity" -> SocketIOValue.Number.of(Double.NEGATIVE_INFINITY)

            else -> SocketIOJson.parse(element.content)
        }

    /** Encodes [value] with [serializer]. */
    public fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = DefaultJson,
    ): SocketIOValue = fromJsonElement(json.encodeToJsonElement(serializer, value))

    /** Decodes [value] with [deserializer]. */
    public fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        value: SocketIOValue,
        json: Json = DefaultJson,
    ): T = json.decodeFromJsonElement(deserializer, toJsonElement(value))
}

/** Encodes a `@Serializable` value. */
public inline fun <reified T> T.encodeToSocketIOValue(json: Json = SocketIOSerialization.DefaultJson): SocketIOValue =
    SocketIOSerialization.encode(serializer<T>(), this, json)

/** Decodes this value into a `@Serializable` type. */
public inline fun <reified T> SocketIOValue.decodeAs(json: Json = SocketIOSerialization.DefaultJson): T =
    SocketIOSerialization.decode(serializer<T>(), this, json)

/** Emits [event] with [value] encoded by `kotlinx.serialization`. */
public inline fun <reified T> Socket.emitSerializable(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): Socket = emit(event, value.encodeToSocketIOValue(json))

/** Emits [event] with [value] encoded by `kotlinx.serialization`, with this emitter's flags. */
public inline fun <reified T> SocketEmitter.emitSerializable(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): SocketEmitter = emit(event, value.encodeToSocketIOValue(json))

/** Like [Socket.emitSerializableWithAck], with this emitter's flags (for example a timeout). */
public suspend inline fun <reified T, reified R> SocketEmitter.emitSerializableWithAck(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): R = (emitWithAck(event, value.encodeToSocketIOValue(json)).firstOrNull() ?: SocketIOValue.Null).decodeAs(json)

/**
 * Emits [event] with [value] and decodes the first acknowledgement argument as
 * [R]; an acknowledgement without arguments decodes `null`, as JavaScript's
 * `emitWithAck` resolves with `undefined`.
 */
public suspend inline fun <reified T, reified R> Socket.emitSerializableWithAck(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): R = (emitWithAck(event, value.encodeToSocketIOValue(json)).firstOrNull() ?: SocketIOValue.Null).decodeAs(json)

/**
 * Calls [listener] with the first argument of every [event], decoded as [T].
 * A payload that does not decode is reported to [onError] (by default
 * rethrown, which the manager's listener error handler receives), including
 * one nested too deeply for a recursive [T] to decode on the stack, as a
 * [SerializationException] caused by the `StackOverflowError`.
 */
public inline fun <reified T> Socket.onSerializable(
    event: String,
    json: Json = SocketIOSerialization.DefaultJson,
    noinline onError: (IncomingEvent, Exception) -> Unit = { _, error -> throw error },
    crossinline listener: (T, IncomingEvent) -> Unit,
): Subscription =
    on(event) { incoming ->
        val decoded =
            try {
                (incoming.args.firstOrNull() ?: SocketIOValue.Null).decodeAs<T>(json)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                onError(incoming, e)
                return@on
            } catch (e: StackOverflowError) {
                // kotlinx.serialization decodes recursive classes on the stack, so a deep enough
                // payload overflows it. That payload does not decode; other VM errors still propagate.
                onError(incoming, SerializationException("payload nested too deeply to decode", e))
                return@on
            }
        listener(decoded, incoming)
    }

/** An [AckCallback] that decodes the first acknowledgement argument as [T]. */
public inline fun <reified T> serializableAck(
    json: Json = SocketIOSerialization.DefaultJson,
    crossinline callback: (Result<T>) -> Unit,
): AckCallback = AckCallback { result -> callback(result.mapCatching { args -> (args.firstOrNull() ?: SocketIOValue.Null).decodeAs<T>(json) }) }
