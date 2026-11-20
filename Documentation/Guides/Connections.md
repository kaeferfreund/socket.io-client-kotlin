# Authentication and connection lifecycle

[Documentation index](../README.md) · [Project overview](../../README.md)

Examples assume an existing `manager` (see [Getting started](GettingStarted.md)).
`tokens` stands for your application's credential store.

## Namespaces

One `SocketManager` multiplexes any number of namespaces over a single Engine.IO
connection:

```kotlin
val manager = SocketManager("https://example.com", options)

val chat = manager.socket("/chat") {
    on("message") { event -> println("Chat: ${event.args}") }
}
val notifications = manager.socket("/notifications") {
    on("notification") { event -> println("Notification: ${event.args}") }
}
```

- `manager.socket(nsp)` returns the same socket for the same namespace. Its
  `SocketOptions` and the setup block apply only when the socket is first created;
  add listeners to an existing socket directly.
- The path of the manager URL does **not** select a namespace: only scheme, host,
  port and query are used. The request path is the `path` option (`/socket.io` by
  default).
- The connection closes when the last active namespace disconnects.

### `SocketIO.io` lookup

For code ported from JavaScript, `SocketIO.io(url)` returns the socket for the
namespace in the URL path and shares one manager per server, like `io()` in
`socket.io-client`:

```kotlin
val chat = SocketIO.io("https://example.com/chat")
val admin = SocketIO.io("https://example.com/admin") // same connection
```

A new connection is opened instead when `forceNew = true`, when `multiplex = false`,
or when the cached manager already has that namespace (also when two threads look up
the same namespace at once: only one of them gets the cached manager). A manager
closed with `close()` leaves the cache; the next lookup creates a new one.
`SocketIO.closeAll()` closes every cached manager. Managers stay cached until they are
closed, so use `SocketIO.io` with a fixed set of server URLs.

A shared manager keeps the `SocketManagerOptions` of the call that created it. As in
JavaScript, the options of a later call for the same server — TLS settings, client
certificates, `extraHeaders`, `query` (also from the URL), limits — are **not applied**
to the connection that call gets; its `SocketOptions` (`auth`, retries) still apply to
its namespace. The client logs a warning when a call passes options or a query that
the manager was not created with; a call with the default options and no query asks
for nothing and logs none. Pass `forceNew = true`, or create a `SocketManager`
yourself, for a connection with its own security settings or credentials, and use one
manager per user when an app talks to the server as more than one user. Explicit
managers are the recommended style when an app needs control over the connection's
lifetime.

## Authentication

### Static payload

`auth` is sent in the namespace CONNECT packet (`socket.handshake.auth` on the
server). It must be a `Map` or a `SocketIOValue.Object`:

```kotlin
val socket = manager.socket("/", SocketOptions { auth = mapOf("token" to accessToken) })
```

### Dynamic provider

When credentials change over time, use an `AuthProvider`. It is a `suspend`
function evaluated before **every** CONNECT, including reconnects; `attempt` is the
manager's reconnection attempt, `0` for the first connection. It wins over `auth`.

```kotlin
val socket =
    manager.socket(
        "/",
        SocketOptions {
            authProvider = AuthProvider { attempt -> mapOf("token" to tokens.validAccessToken()) }
        },
    )
```

The provider may suspend (for example for a token refresh); only its own namespace
waits. If it throws, the CONNECT is not sent and `onConnectError` receives an
`AuthProviderException` whose `cause` is your exception. Call `socket.connect()` to
try again.

A typical token renewal refreshes when the server's middleware refuses the token,
then connects again:

```kotlin
class Realtime(private val manager: SocketManager, private val tokens: TokenStore, private val scope: CoroutineScope) {
    val socket: Socket =
        manager.socket(
            "/",
            SocketOptions { authProvider = AuthProvider { mapOf("token" to tokens.accessToken()) } },
        ) {
            onConnectError { error ->
                if (error is SocketConnectException && error.isServerRefusal && error.message == "token expired") {
                    val target = this // the socket being set up
                    scope.launch {
                        tokens.refresh()
                        target.connect()
                    }
                }
            }
        }
}
```

### HTTP headers and query parameters

If your server authenticates the HTTP handshake instead, set headers on the manager.
They are fixed for the manager's lifetime:

```kotlin
val options = SocketManagerOptions { extraHeaders = mapOf("Authorization" to "Bearer $accessToken") }
```

`query = mapOf(...)` adds URL query parameters to every request. Use it only when
the server expects them, and avoid long-lived credentials in URLs.

## Lifecycle events

```kotlin
val socket =
    manager.socket("/") {
        onConnect { println("Connected as $id, recovered: $recovered") }
        onConnectError { error ->
            if (error is SocketConnectException && error.isServerRefusal) {
                println("Refused by the server: ${error.message}, data: ${error.data}")
            } else {
                println("Connection failed: $error")
            }
        }
        onDisconnect { reason, details ->
            println("Disconnected: ${reason.wireValue} (${details?.description})")
            if (!reason.reconnectsAutomatically) println("Call connect() to rejoin")
        }
    }
```

Each returns a `Subscription`. `SocketConnectException.data` carries `err.data`
from a server middleware. When `isServerRefusal` is `true`, the server rejected the
namespace and the socket does not retry by itself; other connection errors are
retried by the manager. `disconnect` is reported only after a successful connect.

| `DisconnectReason` | `wireValue` | Cause | Reconnects automatically |
| --- | --- | --- | --- |
| `IO_SERVER_DISCONNECT` | `io server disconnect` | The server called `socket.disconnect()` | No |
| `IO_CLIENT_DISCONNECT` | `io client disconnect` | You called `socket.disconnect()` | No |
| `PING_TIMEOUT` | `ping timeout` | No heartbeat within `pingInterval + pingTimeout` | Yes |
| `TRANSPORT_CLOSE` | `transport close` | Connection closed (network change, server restart) | Yes |
| `TRANSPORT_ERROR` | `transport error` | Connection failed (for example an HTTP error) | Yes |
| `PARSE_ERROR` | `parse error` | The server sent an undecodable packet | Yes |
| `FORCED_CLOSE` | `forced close` | The manager closed the connection, for example `pause()` | No (`resume()` does) |

`DisconnectDetails` adds a `description`, the causing `error`, the WebSocket
`closeCode`/`closeReason` and, for polling, the HTTP `statusCode`.

## Reconnection

Automatic reconnection is enabled by default, with the JavaScript defaults:

| `SocketManagerOptions` | Default |
| --- | --- |
| `reconnection` | `true` |
| `reconnectionAttempts` | `Int.MAX_VALUE` (unlimited) |
| `reconnectionDelay` | 1 second |
| `reconnectionDelayMax` | 5 seconds |
| `randomizationFactor` | 0.5 |
| `timeout` (per connection attempt) | 20 seconds |

Each can also be changed at runtime on the manager, for example
`manager.reconnectionDelayMax = 30.seconds`.

Reconnection is a manager concern, so its events are manager events. Register them
in the constructor's setup block:

```kotlin
val manager =
    SocketManager(url, options) {
        on<ManagerEvent.ReconnectAttempt> { println("Reconnect attempt ${it.attempt}") }
        on<ManagerEvent.ReconnectError> { println("Attempt failed: ${it.error}") }
        on<ManagerEvent.Reconnect> { println("Reconnected after ${it.attempt} attempts") }
        on<ManagerEvent.ReconnectFailed> { println("Reconnect attempts exhausted") }
    }
```

A successful reconnection emits `Reconnect`, followed by `onConnect` on each socket
once its namespace has been joined again. `manager.events` offers the same events
as a `SharedFlow<ManagerEvent>`.

### Manual reconnect

`socket.disconnect()` leaves the namespace and does not reconnect automatically.
Call `socket.connect()` to rejoin; the same applies after a server-side disconnect
or a refusal. `manager.disconnect()` disconnects every socket in the same way (reason
`io client disconnect`).
`manager.close()` is final: acknowledgements that can no longer arrive fail with
`SocketDisconnectedException` (plain callbacks without a timeout are not called, as
on any disconnection), buffered emits are dropped, and later calls fail instead of
waiting. It lets the connection send its last packets first, for up to 10 seconds.

## Connection state recovery

Socket.IO 4.6+ can restore a session after a temporary disconnection: the socket
keeps its id and rooms, and the server replays the events it missed. The server must
enable it:

```javascript
const io = new Server(httpServer, {
  connectionStateRecovery: { maxDisconnectionDuration: 2 * 60 * 1000 },
});
```

The client needs no configuration. It remembers the session id (`pid`) sent by the
server and the offset of the last received event, and presents both on the next
CONNECT. Check the outcome with `socket.recovered`, `ConnectionState.Connected.recovered`
or `SocketEvent.Connected.recovered`:

```kotlin
socket.onConnect {
    if (socket.recovered) {
        println("Session recovered; missed events are being replayed")
    } else {
        reloadState() // fresh session: fetch what you need
    }
}
```

Recovery is not guaranteed: the server discards the session after
`maxDisconnectionDuration`, after a restart, and after a deliberate disconnect by
either side. Always handle a fresh session. The client does not deduplicate replayed
events, and [retries](Acknowledgements.md#retries) may resend client events; make
handlers idempotent where duplicates matter.

To switch to another user on the same socket, disconnect, call
`socket.clearRecoveryState()`, update the credentials and connect again:

```kotlin
socket.disconnect()
socket.clearRecoveryState()
token = newUserToken // read by the auth provider
socket.connect()
```

`clearRecoveryState()` makes the next CONNECT start a fresh session and drops what
the previous user left waiting: emits in the send buffer and the retry queue, and
events received before a CONNECT. Their acknowledgements fail with
`SocketDisconnectedException`, so nothing of the previous user is sent under the
new identity. Emits made after the call are kept. Listeners stay registered; remove
the previous user's listeners yourself. Closing the manager and creating a new one
is the other clean way to switch users.

## Pausing and network changes

`manager.pause()` closes the connection but keeps every active socket subscribed;
sockets see a `forced close` disconnect and nothing reconnects while paused.
`manager.resume()` reconnects them, with recovery when the server supports it.
`manager.isPaused` reports the state. The Android background policy uses these
methods; see [Android integration](Android.md).

On the JVM, or with your own connectivity monitoring, tell the manager about the
network:

| Method | Effect |
| --- | --- |
| `setNetworkAvailable(false)` | A due reconnection attempt waits instead of failing and using up an attempt |
| `setNetworkAvailable(true)` | A pending attempt starts immediately instead of finishing its backoff delay |
| `reconnectNow()` | Starts a pending reconnection attempt now |
| `onNetworkLost()` | Closes the connection with `transport close` at once instead of waiting for a ping timeout |

With `android(context)`, `ConnectivityManager` drives all of these automatically.
