# Getting started

[Documentation index](../README.md) · [Project overview](../../README.md)

This guide installs the library, connects an Android app and a plain JVM program,
and explains the lifetime and threading rules every other guide relies on.

This library implements the Socket.IO protocol. It is not a generic WebSocket
client: the server must speak Socket.IO 4.x (Engine.IO protocol 4).

## At a glance

- Socket.IO 4.x / Engine.IO 4, HTTP long-polling and WebSocket with automatic upgrade
- Text and binary events, namespaces, catch-all listeners
- Acknowledgements as callbacks or `suspend` functions, timeouts and ordered retries
- Automatic reconnection with the JavaScript backoff; dynamic `AuthProvider`
- Connection state recovery with Socket.IO 4.6+ servers
- `StateFlow`/`SharedFlow` views of connection state and events
- Android: network, lifecycle, TLS/KeyChain and logging integration

## Requirements

| Minimum | |
| --- | --- |
| Kotlin | 2.2 (2.3.20 for `socketio-serialization`) |
| JVM | Java 17 |
| Android | `minSdk` 26 (Android 8.0) |
| Socket.IO server | 4.x |
| Engine.IO protocol | 4 |

## Installation

The library is split into modules. Pick the one that matches your platform; each
module brings the modules it builds on.

| Module | Use it for |
| --- | --- |
| `socketio-android` | Android apps: OkHttp transports plus network, lifecycle, TLS/KeyChain and logcat integration |
| `socketio-okhttp` | JVM applications: the OkHttp transports and `TlsPolicy` |
| `socketio-client` | The protocol core without an HTTP stack (bring your own transport) |
| `socketio-serialization` | Optional: `@Serializable` classes in and out of events |
| `socketio-testing` | Tests: an in-memory Socket.IO server and a Node fixture launcher |

The artifacts are published to Maven Central, which new Gradle projects already
list:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts (Android)
dependencies {
    implementation("io.github.kaeferfreund.socketio:socketio-android:17.1.0")
}
```

```kotlin
// build.gradle.kts (JVM)
dependencies {
    implementation("io.github.kaeferfreund.socketio:socketio-okhttp:17.1.0")
}
```

A version requirement selects a published release, not the development branch you
are viewing. `kotlinx-coroutines-core` is part of the public API and comes in
transitively.

## Android quick start

Create the manager once (for example in a `ViewModel` or your DI graph), register
listeners in the `socket(...) { }` block and close the manager when you are done.

```kotlin
import android.content.Context
import io.github.kaeferfreund.socketio.Socket
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.android.android
import kotlin.time.Duration.Companion.seconds

class RealtimeClient(context: Context, url: String) {
    private val manager = SocketManager(url, SocketManagerOptions { android(context) })

    private val socket: Socket =
        manager.socket("/") {
            onConnect { println("Connected as $id") }
            onDisconnect { reason, _ -> println("Disconnected: ${reason.wireValue}") }
            onConnectError { error -> println("Connection failed: ${error.message}") }
            on("message") { event -> println("Received: ${event.args}") }
        }

    fun send(message: String) {
        socket.emit("message", message)
    }

    /** Suspends until the server acknowledges, or throws after five seconds. */
    suspend fun ask(question: String): String? = socket.timeout(5.seconds).emitWithAck("question", question).firstOrNull()?.string

    fun close() = manager.close()
}
```

`android(context)` installs the OkHttp transports, delivers listener callbacks on
the main thread, follows `ConnectivityManager` and logs to logcat. See the
[Android integration guide](Android.md) for its settings.

## Plain JVM

On the JVM, `socketio-okhttp` registers its transports through `ServiceLoader`, so a
`SocketManager` without options finds them automatically:

```kotlin
import io.github.kaeferfreund.socketio.SocketManager
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main() =
    runBlocking {
        SocketManager("http://localhost:3000").use { manager ->
            val socket =
                manager.socket("/") {
                    onConnect { println("Connected as $id") }
                    on("news") { event -> println("News: ${event[0]}") }
                }

            // Buffered until the namespace is connected, then sent.
            val reply = socket.timeout(5.seconds).emitWithAck("hello", "world")
            println("Server replied: $reply")
        }
    }
```

`SocketManager` implements `AutoCloseable`, so `use { }` closes it at the end of the
block. To customise OkHttp (proxy, TLS policy, interceptors), use
`SocketManagerOptions { okHttp { ... } }`; see [Configuration](Configuration.md).

## Connecting and registering listeners

`autoConnect` defaults to `true`, as in the JavaScript client: the manager starts
connecting as soon as it is created, and `manager.socket(...)` connects the
namespace right away.

Because the connection runs on another thread, a listener added *after*
`socket(...)` returns can miss events that arrive immediately (`connect`, early
server events). Register listeners where they run before connecting:

- namespace listeners in the setup block of `manager.socket("/") { ... }`;
- manager listeners in the setup block of the constructor:

```kotlin
val manager =
    SocketManager(url, options) {
        on<ManagerEvent.ReconnectAttempt> { println("Reconnect attempt ${it.attempt}") }
    }
```

To connect later instead, set `autoConnect = false` and call `socket.connect()`
yourself:

```kotlin
val manager = SocketManager(url, SocketManagerOptions { autoConnect = false })
val socket = manager.socket("/")
socket.on("message") { event -> println(event.args) }
socket.connect()
```

## Lifetime of a manager

A `SocketManager` is one connection to one server, shared by all its namespace
sockets. Treat it like an OkHttp client or a database:

- **Create it once** per server and keep a reference for as long as you use its
  sockets. Creating a manager per screen or per request opens a new connection each
  time.
- **`manager.close()`** disconnects every socket, stops reconnecting and releases
  the manager's executor. Pending `emitWithAck` calls fail with
  `SocketDisconnectedException`. The manager cannot be used afterwards; create a
  new one.
- **`manager.disconnect()`** or `socket.disconnect()` disconnect without releasing
  anything; call `socket.connect()` to connect again.

In an Android `ViewModel`, create the manager in the constructor (or in a
`connect()` method) and close it in `onCleared()`.

## Threading

Every public method may be called from any thread. The protocol runs on a private
serial executor per manager.

| Where | Listener callbacks (`on`, `onConnect`, `AckCallback`, manager events) run on |
| --- | --- |
| Core (`socketio-client`, `socketio-okhttp`) | The protocol executor, inline. Do not block in them; hand long work to your own dispatcher. |
| Android with `android(context)` | The main thread (`Dispatchers.Main.immediate`) |
| Custom | `SocketManagerOptions { callbackDispatcher = ... }` |

- `emit` arguments are converted to `SocketIOValue` on the calling thread, so later
  changes to your objects never leak into the packet, and an unsupported argument
  throws immediately.
- `suspend` functions (`emitWithAck`) resume in the caller's coroutine context.
- `Flow` collectors run in the collecting coroutine's context.
- An exception thrown by a listener never corrupts protocol state; it is passed to
  `listenerErrorHandler` (default: logged).

## Next steps

| Task | Guide |
| --- | --- |
| Receive and send events, payload types, `Flow`, serialization | [Events and payloads](Events.md) |
| Acknowledgements, timeouts, retries, cancellation | [Acknowledgements and delivery](Acknowledgements.md) |
| Namespaces, authentication, reconnection, recovery | [Connection lifecycle](Connections.md) |
| Transports, TLS, cookies, limits | [Configuration](Configuration.md) |
| Network, background policy, KeyChain, logging | [Android integration](Android.md) |
