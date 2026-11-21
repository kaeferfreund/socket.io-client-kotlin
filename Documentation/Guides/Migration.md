# Migrating from socket.io-client-java

[Documentation index](../README.md) · [Project overview](../../README.md)

This guide moves an Android app from `io.socket:socket.io-client` 2.1.x to this
library. Both speak the Socket.IO 4 protocol, so the server does not change. The
API does: payloads are `SocketIOValue`s instead of `org.json`, times are
`Duration`s, and callbacks arrive on the main thread.

## Dependencies

```kotlin
// app/build.gradle.kts — before
implementation("io.socket:socket.io-client:2.1.2") {
    exclude(group = "org.json", module = "json")
}

// after
implementation("io.github.kaeferfreund.socketio:socketio-android:17.2.0")
```

The `org.json` exclusion is no longer needed: the library does not depend on
`org.json`. Its optional adapters use the copy built into Android. The repository
setup is described in [Getting started](GettingStarted.md#installation).

OkHttp 5 replaces the OkHttp 3/4 pulled in by the Java client. OkHttp 5 is
largely compatible with the 4.x API, but check other libraries in the app that
depend on a specific OkHttp version.

## Concept mapping

| socket.io-client-java | This library |
| --- | --- |
| `IO.socket(url, opts)` | `SocketIO.io(url, options, socketOptions)`, or `SocketManager(url, options).socket(nsp)` for explicit lifetime |
| `IO.Options()` / `SocketOptionBuilder` | `SocketManagerOptions { … }` for the connection, `SocketOptions { … }` per namespace |
| `opts.timeout = 10_000` (ms) | `timeout = 10.seconds` |
| `opts.reconnectionDelay`, `reconnectionDelayMax` (ms) | Same names, `Duration` |
| `opts.transports = arrayOf("websocket")` | `transports = listOf(Transport.WEBSOCKET)` |
| `opts.query = "a=1&b=2"` | `query = mapOf("a" to "1", "b" to "2")` |
| `opts.extraHeaders` (`Map<String, List<String>>`) | `extraHeaders` (`Map<String, String>`) |
| `opts.auth` (`Map<String, String>`) | `SocketOptions.auth` (any map), or `authProvider` evaluated before every connect |
| `opts.callFactory`, `opts.webSocketFactory`, `IO.setDefaultOkHttp…` | `android(context) { okHttpClient = … }`, or `okHttp { client = … }` on the JVM |
| `socket.on(Socket.EVENT_CONNECT) { }` | `socket.onConnect { }` |
| `socket.on(Socket.EVENT_CONNECT_ERROR) { args -> }` | `socket.onConnectError { error: Throwable -> }` |
| `socket.on(Socket.EVENT_DISCONNECT) { args -> }` (reason string) | `socket.onDisconnect { reason: DisconnectReason, details -> }` |
| `socket.on("event") { args: Array<Any> -> }` | `socket.on("event") { event: IncomingEvent -> }`; `event.args` is `List<SocketIOValue>`, `event[0]` the first |
| Last listener argument `is Ack` | `event.ack?.send(…)` |
| `socket.emit("event", payload, Ack { args -> })` | `socket.emit("event", payload) { result: Result<List<SocketIOValue>> -> }` |
| `AckWithTimeout(ms) { onSuccess / onTimeout }` | `socket.timeout(d).emit(…) { result -> }` or `socket.timeout(d).emitWithAck(…)` |
| `JSONObject` / `JSONArray` payloads | `Map` / `List`, `SocketIOValue`, or `JSONObject.toSocketIOValue()` / `SocketIOValue.Object.toJSONObject()` |
| `byte[]` | `ByteArray` (received as `SocketIOValue.Binary`) |
| `socket.off()` | `socket.removeAllListeners()`; or cancel the `Subscription` returned by `on`/`onConnect` |
| `socket.off("event")` | `socket.off("event")` |
| `socket.connected()`, `socket.id()` | `socket.connected`, `socket.id`; or `socket.state` (`StateFlow`) |
| `socket.io()` | `socket.manager` |
| `socket.onAnyIncoming`, `onAnyOutgoing` | `socket.onAny`, `socket.onAnyOutgoing` |
| `manager.on(Manager.EVENT_RECONNECT_ATTEMPT) { }` | `manager.on<ManagerEvent.ReconnectAttempt> { it.attempt }` |
| `Manager.EVENT_RECONNECT`, `EVENT_RECONNECT_ERROR`, `EVENT_RECONNECT_FAILED` | `ManagerEvent.Reconnect`, `ReconnectError`, `ReconnectFailed` |
| `Manager.EVENT_OPEN`, `EVENT_CLOSE`, `EVENT_ERROR`, `EVENT_PACKET` | `ManagerEvent.Open`, `Close`, `Error`, `Packet` |
| `Manager.EVENT_TRANSPORT` | `manager.transportName` (`StateFlow<String?>`) |
| `manager.open(OpenCallback)` | `manager.open { error: Throwable? -> }` |

Listeners on the reserved names still work with `on`: `"connect_error"` receives
an object with `message` and `data`, `"disconnect"` the reason string. The typed
functions are preferred.

## Behavioural differences

**Callback thread.** The Java client calls listeners and acknowledgements on its
own `EventThread`, so UI code needed `runOnUiThread`. With `android(context)`,
callbacks run on the main thread; remove the hop, and move blocking work out of
listeners. Without `android()`, they run on the protocol executor. `emit` and
`connect` may be called from any thread.

**Register listeners before connecting.** The Java client starts connecting on
`socket.connect()`. Here the manager connects on creation (`autoConnect = true`),
on another thread. Register listeners in the `socket(nsp) { … }` setup block,
which runs before the connection starts, or set `autoConnect = false`.

**Connection errors are typed.** Java delivers a `JSONObject` (server refusal) or
an `EngineIOException`/`SocketIOException` as an untyped argument. Here
`onConnectError` receives a `Throwable`:

| Java argument | Kotlin error |
| --- | --- |
| `JSONObject` with `message`/`data` from middleware | `SocketConnectException` with `isServerRefusal = true`, `message`, `data: SocketIOValue?` |
| `EngineIOException("xhr poll error")`, `"websocket error"` | `TransportException` with `statusCode`, `responseBody`, `cause` |
| `SocketIOException("timeout")` | `EngineIOException("timeout")` |
| — | `AuthProviderException` when the `AuthProvider` threw |

A server refusal is final, as in Java: the socket does not retry until `connect()`
is called again.

**Disconnect reasons** are `DisconnectReason` values whose `wireValue` is the Java
and JavaScript string (`"io server disconnect"`, `"io client disconnect"`,
`"ping timeout"`, `"transport close"`, `"transport error"`, `"forced close"`), plus
`"parse error"`. `reason.reconnectsAutomatically` tells whether the manager will
reconnect. `onDisconnect` fires only after a connection was established.

**Acknowledgements on disconnect.** Java calls `AckWithTimeout.onTimeout()` for
pending acknowledgements when the socket disconnects. Here an acknowledgement
with a timeout fails with `SocketDisconnectedException` (a timeout yields
`AckTimeoutException`). A plain acknowledgement without timeout is dropped and
never called, as in JavaScript: always set a timeout where the result matters.

**Reconnection events** are emitted only by the manager, as in Java 2.x.
`ReconnectAttempt.attempt` counts from 1; `Reconnect.attempt` is the number of
attempts it took.

**Payload conversion is strict.** Arguments are converted with `SocketIOValue.of`
when `emit` is called. `JSONObject`/`JSONArray` arguments keep working and are
converted like a `Map`/`List`; any other unsupported type (your own classes, for
example) throws `IllegalArgumentException` immediately. Emitting a reserved name
(`connect`, `connect_error`, `disconnect`, `disconnecting`, `newListener`,
`removeListener`) also throws.

**Numbers** arrive as `SocketIOValue.Number`: read them with `.long`, `.int` or
`.double` instead of casting to `Integer`/`Double`. `JSONObject.NULL` becomes
`SocketIOValue.Null`.

**New delivery features** are opt-in and have consequences: `retries` resends
unacknowledged emits, so the server can receive an event twice; connection state
recovery replays missed events after a short outage. See
[Acknowledgements](Acknowledgements.md#retries) and
[Connections](Connections.md#connection-state-recovery).

**Transport fallback.** Engine.IO client 2.1.0 cannot fall back when an initial
WebSocket connection fails. Here `tryAllTransports = true` with
`transports = listOf(Transport.WEBSOCKET, Transport.POLLING)` does that inside one
connection attempt.

## Before and after

A typical Java-client transport: token authentication, WebSocket first, its own
reconnect loop, and requests awaited with a coroutine.

```kotlin
// Before: io.socket:socket.io-client 2.1.2
class SyncTransport(url: String, token: String, private val onEvent: (String, Any?) -> Unit) {
    private val socket: Socket = IO.socket(url, IO.Options().apply {
        auth = mapOf("token" to token)
        reconnection = false
        transports = arrayOf("websocket")
        timeout = 10_000
        forceNew = true
    })

    init {
        listOf("sessionReady", "itemUpdated", Socket.EVENT_DISCONNECT, Socket.EVENT_CONNECT_ERROR).forEach { name ->
            socket.on(name) { args -> onEvent(name, args.firstOrNull()) }
        }
    }

    fun connect() { socket.connect() }

    fun close() { socket.off(); socket.disconnect() }

    suspend fun request(event: String, payload: JSONObject): JSONObject = withTimeout(15_000) {
        suspendCancellableCoroutine { continuation ->
            socket.emit(event, payload, Ack { args ->
                val response = args.firstOrNull() as? JSONObject
                if (continuation.isActive) {
                    if (response != null) continuation.resume(response)
                    else continuation.resumeWithException(IllegalStateException("invalid response"))
                }
            })
        }
    }
}
```

```kotlin
// After
import android.content.Context
import io.github.kaeferfreund.socketio.AuthProvider
import io.github.kaeferfreund.socketio.SocketConnectException
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.SocketOptions
import io.github.kaeferfreund.socketio.Transport
import io.github.kaeferfreund.socketio.android.android
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlin.time.Duration.Companion.seconds

fun interface TokenSource {
    suspend fun currentToken(): String
}

class SyncTransport(
    context: Context,
    url: String,
    tokens: TokenSource,
    private val onEvent: (String, SocketIOValue?) -> Unit,
    private val onRefused: (SocketConnectException) -> Unit,
) {
    private val manager = SocketManager(
        url,
        SocketManagerOptions {
            android(context)
            autoConnect = false
            transports = listOf(Transport.WEBSOCKET, Transport.POLLING)
            tryAllTransports = true
            timeout = 10.seconds
        },
    )

    private val socket = manager.socket(
        "/",
        SocketOptions { authProvider = AuthProvider { mapOf("token" to tokens.currentToken()) } },
    ) {
        for (name in listOf("sessionReady", "itemUpdated")) {
            on(name) { event -> onEvent(name, event[0]) }
        }
        onDisconnect { reason, _ -> onEvent("disconnect", SocketIOValue.Text(reason.wireValue)) }
        onConnectError { error ->
            if (error is SocketConnectException && error.isServerRefusal) onRefused(error)
        }
    }

    fun connect() {
        socket.connect()
    }

    fun close() = manager.close()

    suspend fun request(event: String, payload: Map<String, Any?>): SocketIOValue.Object =
        socket.timeout(15.seconds).emitWithAck(event, payload).firstOrNull() as? SocketIOValue.Object
            ?: throw IllegalStateException("invalid response")
}
```

What changed:

- The `AuthProvider` fetches a fresh token before every connect and reconnect, so
  the built-in reconnection can replace a hand-written loop. Keep
  `reconnection = false` if the app's own session logic must stay in charge.
- "Transport failure or server answer?" becomes a type check:
  `error is SocketConnectException && error.isServerRefusal`. Middleware details
  are in `error.data`, for example
  `(error.data as? SocketIOValue.Object)?.get("code")?.string`.
- `emitWithAck` with `timeout` replaces `withTimeout` + `suspendCancellableCoroutine`.
  It throws `AckTimeoutException` or `SocketDisconnectedException`; cancelling the
  coroutine withdraws the acknowledgement and, if still buffered, the packet.
- `manager.close()` releases the connection, listeners and executor.

## Checklist

1. Swap the dependency and remove the `org.json` exclusion.
2. Create one `SocketManager` per server with `SocketManagerOptions { android(context) }`;
   keep it in a `ViewModel` or your DI graph and `close()` it when done.
3. Translate options: milliseconds to `Duration`, arrays to lists, query strings to
   maps, auth maps to `auth` or `authProvider`.
4. Move listener registration into the `socket(nsp) { … }` setup block.
5. Replace `EVENT_CONNECT`/`EVENT_CONNECT_ERROR`/`EVENT_DISCONNECT` listeners with
   `onConnect`, `onConnectError`, `onDisconnect`; rewrite error handling on the
   exception types above.
6. Replace `args[i] as JSONObject` with `event[i]` and `SocketIOValue` accessors,
   or convert with `toJSONObject()` where old code still needs `org.json`.
7. Replace `Ack`/`AckWithTimeout` with `emit(…) { result -> }` or `emitWithAck`,
   and give every acknowledgement that matters a timeout.
8. Remove `runOnUiThread`/`Handler` hops from listeners; move blocking work out.
9. Replace custom `callFactory`/`webSocketFactory` setup with `okHttpClient` and
   `tlsPolicy`.
10. Decide on `retries` and connection state recovery; make server handlers
    idempotent if you enable retries.
11. Test offline/online transitions and backgrounding on a device; see
    [Android integration](Android.md) and [Troubleshooting](Troubleshooting.md).
