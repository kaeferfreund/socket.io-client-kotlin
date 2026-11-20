# Events and payloads

[Documentation index](../README.md) · [Project overview](../../README.md)

Examples assume a `socket` obtained from `manager.socket(...)`; see
[Getting started](GettingStarted.md). Payload types live in
`io.github.kaeferfreund.socketio.parser`.

## Receiving events

Register a listener with `on`. It receives an `IncomingEvent` with the event
`name`, its `args` (a `List<SocketIOValue>`) and an optional `ack`:

```kotlin
socket.on("chat message") { event ->
    val message = event[0]?.obj ?: return@on
    val text = message["text"]?.string ?: return@on
    println(text)
}
```

`event[i]` returns the argument at index `i`, or `null` when the server sent fewer
arguments.

Use `once` for a single invocation:

```kotlin
socket.once("ready") { println("Server is ready") }
```

`on` and `once` return a `Subscription`; cancelling it removes exactly that
listener:

```kotlin
val subscription = socket.on("message") { event -> println(event.args) }

subscription.cancel()
```

`off` removes listeners by event name, or one specific `EventListener` instance:

```kotlin
val listener = EventListener { event -> println(event.args) }
socket.on("message", listener)

socket.off("message", listener) // only this listener
socket.off("message")           // every listener of "message"
socket.removeAllListeners()     // every named listener
```

Register listeners in the `manager.socket("/") { ... }` setup block when they must
see events that can arrive right after connecting.

When the server emits with a callback, `event.ack` is present; answer with
`event.ack?.send(...)`. See
[acknowledging server requests](Acknowledgements.md#acknowledging-server-requests).

## Sending events

Send an event with any number of arguments:

```kotlin
socket.emit("message", "Hello from Kotlin")
socket.emit("updateProfile", mapOf("name" to "Ada", "active" to true))
socket.emit("move", 12, 34)
```

`socket.send(...)` is the `"message"` shortcut: `socket.send("Hello")` equals
`socket.emit("message", "Hello")`.

Emits made while the socket is not connected are buffered and sent in order once
the namespace connects. See [volatile events](#volatile-events) for the exception.

### Supported argument types

Each argument is converted with `SocketIOValue.of` **on the calling thread**:

| Kotlin/Java value | Sent as |
| --- | --- |
| `null`, `Unit` | `null` |
| `SocketIOValue` | itself |
| `Boolean` | boolean |
| `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `BigInteger`, `BigDecimal`, other `Number` | number; a `Float` keeps the decimal it was written as (`0.1f` → `0.1`) |
| `CharSequence`, `Char`, `Enum` (its `name`) | string |
| `ByteArray`, `ByteBuffer` (the remaining bytes) | binary attachment |
| `Iterable`, `Array`, primitive arrays, `Sequence` | array |
| `Map` (keys via `toString()`) | object |
| `java.util.Date`, `java.time.Instant` | string in `Date.prototype.toISOString()` form, e.g. `"2026-09-24T08:15:30.000Z"` |

Any other type throws `IllegalArgumentException` from `emit`, as do cyclic
structures. Convert your own classes to a `Map`, a `SocketIOValue`, or use
[kotlinx.serialization](#kotlinxserialization). `org.json` values
(`JSONObject`, `JSONArray`, `JSONObject.NULL`) are converted too when `org.json` is
on the classpath, as it always is on Android; see [org.json adapters](#orgjson-adapters)
for the way back.

Binary data can appear anywhere in a payload, including inside maps and lists; the
client sends it as a Socket.IO binary attachment:

```kotlin
socket.emit("upload", mapOf("name" to "photo.jpg", "data" to bytes))
```

The conversion copies your data, so mutating a map or array after `emit` does not
change what is sent.

### Reserved event names

`connect`, `connect_error`, `disconnect`, `disconnecting`, `newListener` and
`removeListener` are reserved. Emitting one of them throws
`IllegalArgumentException`. Listening with `on("connect")`,
`on("connect_error")` or `on("disconnect")` receives the lifecycle events as in
JavaScript, but the typed `onConnect`, `onConnectError` and `onDisconnect` are
preferred; see [Connections](Connections.md#lifecycle-events).

## Reading `SocketIOValue`

Payloads arrive as an immutable `SocketIOValue` tree: `Null`, `Bool`, `Number`,
`Text`, `Binary`, `Array` and `Object`. Typed accessors return `null` when the value
has a different type:

| Accessor | Returns |
| --- | --- |
| `string` | `String?` |
| `boolean` | `Boolean?` |
| `long`, `int` | Integral numbers only; `int` also requires the value to fit |
| `double` | Any number as `Double?` |
| `bytes` | A copy of binary data |
| `obj` | `Map<String, SocketIOValue>?` |
| `array` | `List<SocketIOValue>?` |
| `isNull`, `containsBinary` | `Boolean` |
| `toKotlin()` | Plain `null`/`Boolean`/`Long`/`Double`/`String`/`ByteArray`/`List`/`Map`; an attachment referenced several times is one shared `ByteArray` |

`SocketIOValue.Object` and `SocketIOValue.Array` also support `value["key"]` and
`value[index]`. Numbers follow JavaScript: an integral value is stored as a `Long`,
so `1`, `1L` and `1.0` are equal.

Build values explicitly with `socketIOObject(...)` and `socketIOArray(...)`:

```kotlin
import io.github.kaeferfreund.socketio.parser.socketIOObject

socket.emit("position", socketIOObject("lat" to 52.52, "lng" to 13.40))
```

## Catch-all listeners

`onAny` sees every incoming application event before the named listeners. It does
not see lifecycle events (`connect`, `disconnect`, `connect_error`):

```kotlin
val logger = AnyListener { name, args -> println("<- $name $args") }

socket.onAny(logger)
socket.prependAny { name, _ -> println("first: $name") } // runs before existing ones
socket.offAny(logger)                                    // remove one
socket.offAny()                                          // remove all
```

Outgoing events can be observed the same way. An outgoing listener runs when the
event is handed to the transport, so a buffered emit is reported when the buffer is
flushed, and a dropped volatile emit is not reported:

```kotlin
val outgoing = AnyListener { name, args -> println("-> $name $args") }

socket.onAnyOutgoing(outgoing)
socket.offAnyOutgoing(outgoing)
```

`prependAnyOutgoing`, `listenersAny()` and `listenersAnyOutgoing()` complete the
JavaScript set.

## Flow API

Every socket publishes coroutine-friendly views:

| Property | Type | Content |
| --- | --- | --- |
| `socket.state` | `StateFlow<ConnectionState>` | `Disconnected(reason, details)`, `Connecting`, `Connected(id, recovered)` |
| `socket.events` | `SharedFlow<SocketEvent>` | `Connected`, `ConnectError`, `Disconnected`, `Received` |
| `socket.flow("name")` | `Flow<IncomingEvent>` | Received events with that name |

```kotlin
lifecycleScope.launch {
    socket.flow("news").collect { event -> showNews(event[0]?.string) }
}

lifecycleScope.launch {
    socket.state.collect { state ->
        when (state) {
            is ConnectionState.Connected -> showOnline(state.id)
            ConnectionState.Connecting -> showConnecting()
            is ConnectionState.Disconnected -> showOffline(state.reason)
        }
    }
}
```

`events` and `flow(...)` are hot: a collector sees only events emitted after it
started collecting. Use listeners in the setup block for events that must not be
missed. The stream's buffer is unbounded, so a slow collector never blocks the
protocol but does hold events in memory until it catches up.

## Volatile events

Volatile events are for frequently updated state where dropping an update is better
than sending a stale one later: cursor positions, live locations, typing
indicators, telemetry.

```kotlin
socket.volatile.emit("cursor", mapOf("x" to 120, "y" to 240))
```

When the transport cannot write immediately (including while disconnected), a
volatile event is dropped instead of buffered. Volatile emits bypass the
[retry queue](Acknowledgements.md#retries).

`socket.volatile`, `socket.timeout(...)` and `socket.compress(...)` return an
immutable `SocketEmitter`. Flags combine and the emitter can be kept and reused:

```kotlin
val cursor = socket.volatile.compress(false)
cursor.emit("cursor", mapOf("x" to 1, "y" to 2))
```

OkHttp compresses WebSocket messages by size only, so `compress(false)` cannot turn
compression off for a single frame.

## kotlinx.serialization

Add `socketio-serialization` and the Kotlin serialization plugin to use
`@Serializable` classes directly:

```kotlin
import io.github.kaeferfreund.socketio.serialization.decodeAs
import io.github.kaeferfreund.socketio.serialization.emitSerializable
import io.github.kaeferfreund.socketio.serialization.emitSerializableWithAck
import io.github.kaeferfreund.socketio.serialization.onSerializable
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val user: String, val text: String)

@Serializable
data class Receipt(val id: String)

socket.emitSerializable("chat", ChatMessage("ada", "Hello"))

socket.onSerializable<ChatMessage>("chat") { message, event ->
    println("${message.user}: ${message.text}")
}

val receipt: Receipt = socket.emitSerializableWithAck<ChatMessage, Receipt>("chat", ChatMessage("ada", "Hi"))

val decoded: ChatMessage? = someEvent[0]?.decodeAs<ChatMessage>()
```

`onSerializable` decodes the first argument; a payload that does not decode is
passed to its `onError` parameter (by default rethrown to the manager's
`listenerErrorHandler`). `serializableAck<T> { result -> }` builds an
`AckCallback` that decodes the first acknowledgement argument. Every decoding
function (`decodeAs`, `emitSerializableWithAck`, `serializableAck`,
`onSerializable`) reports a payload nested too deeply for a recursive class to
decode as a `SerializationException` caused by the `StackOverflowError` that
kotlinx.serialization hit, like any other payload that does not decode. All functions take
an optional `Json` instance; the default ignores unknown keys. Binary data has no
JSON form: send `ByteArray` payloads as separate arguments.

## org.json adapters

For code migrating from `socket.io-client-java`, `socketio-android` converts between
`org.json` and `SocketIOValue`:

```kotlin
import io.github.kaeferfreund.socketio.android.toJSONObject
import io.github.kaeferfreund.socketio.android.toSocketIOValue

socket.emit("legacy", JSONObject().put("id", 7)) // converted like a Map

socket.on("legacy") { event ->
    val json: JSONObject? = (event[0] as? SocketIOValue.Object)?.toJSONObject()
}
```

`JSONArray.toSocketIOValue()`, `SocketIOValue.Array.toJSONArray()`,
`SocketIOValue.toJson()` and `fromJson(value)` cover the remaining cases. Prefer
`SocketIOValue` or kotlinx.serialization in new code.
