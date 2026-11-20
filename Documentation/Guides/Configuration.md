# Configuration

[Documentation index](../README.md) · [Project overview](../../README.md)

All options live in two immutable objects built with DSL functions:

- `SocketManagerOptions { … }` — one connection: the JavaScript `ManagerOptions`
  (including the Engine.IO options) plus Kotlin runtime settings.
- `SocketOptions { … }` — one namespace socket: `auth`, `authProvider`, `retries`,
  `ackTimeout`, `outgoingInterceptor`. See [Connections](Connections.md) and
  [Acknowledgements](Acknowledgements.md).

Every default is the JavaScript client's default. Durations are
`kotlin.time.Duration`, not milliseconds. On Android, call `android(context)`
first; it sets the clock, callback thread, logger, plugins and HTTP stack (see
[Android integration](Android.md)).

```kotlin
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.Transport
import kotlin.time.Duration.Companion.seconds

val options = SocketManagerOptions {
    reconnectionDelayMax = 30.seconds
    timeout = 10.seconds
    transports = listOf(Transport.WEBSOCKET, Transport.POLLING)
    tryAllTransports = true
}
val manager = SocketManager("https://example.com", options)
```

`options.newBuilder()` returns a pre-filled builder for a modified copy.

## Reconnection

| Option | Default | Meaning |
| --- | --- | --- |
| `reconnection` | `true` | Reconnect automatically after an unexpected close |
| `reconnectionAttempts` | `Int.MAX_VALUE` (unlimited) | Attempts before `ManagerEvent.ReconnectFailed` |
| `reconnectionDelay` | `1.seconds` | First backoff delay |
| `reconnectionDelayMax` | `5.seconds` | Backoff ceiling |
| `randomizationFactor` | `0.5` | Jitter, between 0 and 1 |
| `random` | `Random.Default` | Source of the jitter (tests pass a seeded one) |

`reconnection`, `reconnectionAttempts`, `reconnectionDelay`,
`reconnectionDelayMax`, `randomizationFactor` and `timeout` are also mutable
properties of a running `SocketManager`, like the JavaScript setters.

## Connection

| Option | Default | Meaning |
| --- | --- | --- |
| `autoConnect` | `true` | Open the connection when the manager/socket is created |
| `timeout` | `20.seconds` | Limit for each connection attempt; `null` disables it. Expiry fails the attempt with `connect_error` `"timeout"` |
| `forceNew` | `false` | `SocketIO.io` always creates a new manager |
| `multiplex` | `true` | `SocketIO.io` shares one manager per scheme, host, port and path |

## Transports and HTTP

| Option | Default | Meaning |
| --- | --- | --- |
| `transports` | `[polling, websocket]` | Transport names in the order they are tried; use `Transport.POLLING` / `Transport.WEBSOCKET` |
| `upgrade` | `true` | Probe WebSocket after connecting over polling and switch to it |
| `tryAllTransports` | `false` | If the first transport fails to *open*, try the next one in `transports` |
| `rememberUpgrade` | `false` | Start with WebSocket when a previous connection in this process upgraded successfully |
| `path` | `"/socket.io"` | Request path on the server; must match the server's `path` |
| `addTrailingSlash` | `true` | Append `/` to `path` (`/socket.io/`) |
| `query` | empty | Query parameters on every request; a query string in the manager URL replaces it, as in JavaScript |
| `extraHeaders` | empty | Headers on every polling request and on the WebSocket handshake |
| `withCredentials` | `false` | Keep `Set-Cookie` values and resend them within one connection |
| `forceBase64` | `false` | Send binary attachments as Base64 text |
| `timestampRequests` | `null` | Cache-busting parameter: `null` = on for polling, off for WebSocket |
| `timestampParam` | `"t"` | Name of the cache-busting parameter |
| `protocols` | empty | `Sec-WebSocket-Protocol` values to offer |
| `perMessageDeflateThreshold` | `1024` | WebSocket messages below this many bytes are not compressed; `null` disables compression |
| `requestTimeout` | `null` | Whole-request timeout of each polling request |
| `maxPollingResponseBytes` | `Long.MAX_VALUE` | Largest accepted polling response body |
| `handshakeLimits` | unlimited | Upper bounds for `pingInterval` / `pingTimeout` in the server handshake |
| `transportOptions` | empty | Overrides per transport name (below) |
| `transportFactories` | empty | Custom transport implementations by name (below) |
| `clients` | `null` | HTTP/WebSocket stack; discovered from `socketio-okhttp` when `null` |

Notes:

- The path of the URL passed to `SocketManager` is ignored; only scheme, host,
  port and query are used. With `SocketIO.io(url)` the URL path names the
  *namespace*. The request path is always the `path` option.
- `tryAllTransports = true` with `transports = listOf(Transport.WEBSOCKET, Transport.POLLING)`
  gives "WebSocket first, polling if WebSocket is blocked".
- `rememberUpgrade` state is shared by every manager in the process, like the
  static flag in JavaScript. A failed connection clears it.
- `withCredentials` uses a cookie jar per Engine.IO connection; it starts empty on
  every reconnect. For cookies that must survive reconnects (for example sticky
  sessions), set an OkHttp `CookieJar` with `okHttp { configure { cookieJar(…) } }`.
  The application's `CookieManager` is never used implicitly. A cookie whose name
  or value is not printable ASCII is ignored: it could not be sent back in a
  request header.
- `extraHeaders` holds one value per name. Join repeated values with `", "`.
  Header names and values must be ASCII; a request with a value OkHttp refuses
  (or a malformed URL) fails with a transport error (`"xhr poll error"`,
  `"websocket error"`) and `connect_error`, never with an exception.
- **Compression:** OkHttp negotiates `permessage-deflate` and compresses messages
  by size only (`perMessageDeflateThreshold`). The per-message flag of
  `socket.compress(false)` is carried to the transport, but OkHttp offers no way to
  switch compression off for a single frame. With `perMessageDeflateThreshold = null`
  the extension is not offered, and a server that enables it anyway fails the
  handshake (`"websocket error"`).
- **Large WebSocket messages:** OkHttp queues at most 16 MiB of outgoing WebSocket
  data. The transport hands it only what fits and waits for the rest, so bursts of
  large emits do not close the connection; a single message above 16 MiB cannot be
  sent over WebSocket (see [Troubleshooting](Troubleshooting.md#limits)).
- **JVM applications and process exit:** `manager.close()` stops every timer; the
  protocol runs on daemon threads. OkHttp's request dispatcher, however, uses
  non-daemon threads that stay idle for up to 60 seconds, so a `main` function can
  outlive its work by that long (socket.io-client-java #324). Pass your own client
  with `okHttp { client = … }` and call `dispatcher.executorService.shutdown()` on it
  when the application ends. Android apps are not affected.
- `requestTimeout` applies to polling only. OkHttp's read timeout is always
  disabled for the transports because a long poll legitimately waits a full
  heartbeat interval; the heartbeat detects dead connections.
- A polling body larger than `maxPollingResponseBytes`, or a handshake outside
  `handshakeLimits`, fails the connection with a transport error
  (`"xhr poll error"` or `"invalid handshake"`).

### Per-transport options

`TransportOverrides` accepts `query`, `extraHeaders`, `requestTimeout`,
`timestampRequests`, `forceBase64` and `path`. Values are merged over the global
ones for that transport only:

```kotlin
import io.github.kaeferfreund.socketio.engineio.TransportOverrides

val options = SocketManagerOptions {
    transportOptions = mapOf(
        Transport.POLLING to TransportOverrides(requestTimeout = 30.seconds),
        Transport.WEBSOCKET to TransportOverrides(extraHeaders = mapOf("X-Transport" to "ws")),
    )
}
```

### Custom transports

`transportFactories` maps a transport name to an `EngineTransport.Factory`.
Built-in names without an entry use the built-in transport. A factory can wrap a
built-in transport, for example to count connection attempts:

```kotlin
import io.github.kaeferfreund.socketio.engineio.EngineTransport
import io.github.kaeferfreund.socketio.engineio.PollingTransport

val options = SocketManagerOptions {
    transportFactories = mapOf(
        Transport.POLLING to EngineTransport.Factory { transportOptions ->
            println("polling attempt to ${transportOptions.hostname}")
            PollingTransport(transportOptions)
        },
    )
}
```

A new transport subclasses `EngineTransport` and implements `name`, `doOpen`,
`doClose`, `write` and `uri`. All methods run on the protocol executor; hop to
`TransportOptions.executor` before calling the protected `on…` methods from I/O
callbacks. The server must implement the same transport.

## OkHttp client

Without configuration the default OkHttp stack is found through `ServiceLoader`.
To customise it on the JVM, use `okHttp { }` from `socketio-okhttp`:

```kotlin
import io.github.kaeferfreund.socketio.okhttp.okHttp
import java.net.Proxy
import okhttp3.OkHttpClient

val sharedClient = OkHttpClient()

val options = SocketManagerOptions {
    okHttp {
        client = sharedClient            // connection pool and dispatcher are reused
        configure { proxy(Proxy.NO_PROXY) }
    }
}
```

The library derives its own client from yours: read timeout disabled,
HTTPS→HTTP redirects not followed (`followSslRedirects = false`), polling redirects
followed only within the same origin (scheme, host and port) so extra headers,
cookies, request bodies and a client certificate never reach another host (the
origin is checked before any connection to the new location), no redirects for the
WebSocket handshake (as in browsers and Node's `ws`), and at least 64 concurrent
requests per host. Interceptors, proxy, DNS, cookie jar,
`connectionSpecs` and call timeouts are kept.

On Android, set `okHttpClient` and `tlsPolicy` inside `android(context) { }`
instead. Calling `okHttp { }` after `android(context)` replaces the Android
client, which removes network binding and traffic tagging.

## TLS

`TlsPolicy` covers what Node's `ca`, `cert`, `key`, `pfx` and
`rejectUnauthorized` do in JavaScript. The default needs no configuration.

| Policy | Effect |
| --- | --- |
| `TlsPolicy.systemDefault()` | Platform trust store. On Android, the app's Network Security Config (pins, user CAs, cleartext rules) applies unchanged |
| `TlsPolicy.customTrust(anchors)` | Trust only these certificates (private CA or self-signed leaf) for this client |
| `TlsPolicy.pinned(hostPattern, "sha256/…")` | System trust plus public-key pins |
| `.withPins(hostPattern, …)` / `.withPinnedCertificates(hostPattern, certs)` | Add pins to any policy |
| `.withClientCertificate(pkcs12, password)` / `.withClientCertificate(keyManager)` | Present a client certificate (mutual TLS) |
| `.withKeyChainAlias(context, alias)` (Android) | Client certificate from the Android KeyChain |

Host patterns follow OkHttp's `CertificatePinner`: `api.example.com`,
`*.example.com` (one label) or `**.example.com` (any depth).

A policy is applied on top of the base client (`client` in `okHttp { }`,
`okHttpClient` on Android) and never removes its restrictions. Without
`customTrust`, the base client's trust manager stays in charge, also when a client
certificate is added. Pins are added to the base client's `CertificatePinner`;
as in OkHttp, a host matched by several patterns accepts any of their pins.

```kotlin
import io.github.kaeferfreund.socketio.okhttp.TlsPolicy

/** [caPem], [clientP12] and [password] come from your application's resources or secure storage. */
fun mutualTlsOptions(caPem: ByteArray, clientP12: ByteArray, password: CharArray): SocketManagerOptions {
    val policy = TlsPolicy
        .customTrust(TlsPolicy.certificates(caPem))
        .withPins("api.example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .withClientCertificate(clientP12, password)
    return SocketManagerOptions {
        okHttp { tlsPolicy = policy }
    }
}
```

`TlsPolicy.certificates` parses PEM or DER, one or several certificates.
Hostname verification, validity and chain checks always stay enabled.

There is deliberately no "trust all certificates" mode: it is the most common way
production apps end up accepting any certificate. For development servers, trust
their CA with `customTrust`, or on Android with a debug-only Network Security
Config (see [Android integration](Android.md#network-security-config)).

## Threading

| Option | Default | Meaning |
| --- | --- | --- |
| `dispatcher` | `Dispatchers.Default` | Runs the protocol, limited to parallelism 1 |
| `callbackDispatcher` | `null` (Android: `Dispatchers.Main.immediate`) | Thread for listener and acknowledgement callbacks |
| `listenerErrorHandler` | `null` (log it) | Receives exceptions thrown by listeners |

Each manager owns a `ProtocolExecutor`: one serial context for the engine, its
transports, the manager and every socket. Public methods may be called from any
thread; they hand work to the executor and return immediately.

With `callbackDispatcher = null`, listeners run directly on the executor, which
keeps the exact JavaScript ordering. A listener that blocks then stalls the
connection, heartbeats included. With a dispatcher, callbacks are posted there,
and what a listener calls from there (`emit`, `ack.send`, `disconnect`, …) is
queued on the executor like a call from any other thread.
In both cases: **never block in a listener** — no network or disk I/O, no
`runBlocking`, no waiting on an acknowledgement. Launch a coroutine instead.

An exception thrown by a listener never corrupts protocol state; it is passed to
`listenerErrorHandler`, or logged when no handler is set. This covers errors such
as `NotImplementedError` from `TODO()` too (out-of-memory errors excepted), and a
handler that throws itself is logged with the original exception.

`Flow`s (`socket.events`, `socket.state`, `manager.events`) are delivered in the
collector's context, independent of `callbackDispatcher`. `logger`,
`packetObserver`, `tracer` and `OutgoingInterceptor` run on the executor; keep them
fast.

## Time

`timeSource` (default `TimeSource.Monotonic`) measures heartbeat, backoff and
acknowledgement deadlines. `android(context)` sets `ElapsedRealtimeTimeSource`,
which keeps counting in deep sleep, so a heartbeat that expired during Doze is
detected after wake-up. Tests can use a virtual-time dispatcher and matching time
source.

## Diagnostics

| Option | Default | Meaning |
| --- | --- | --- |
| `logger` | `SocketLogger.NONE` (Android: `AndroidLogger("SocketIO")`) | Log sink; messages are built only when `isLoggable` is true |
| `packetObserver` | `null` | Sees every Engine.IO packet in both directions |
| `tracer` | `null` (Android: `AndroidTracer` with `tracing = true`) | Async sections `socket.io connect`, `socket.io upgrade`, `socket.io ack <event>` |
| `plugins` | empty | `SocketManagerPlugin`s attached when a manager is created and cancelled when it is closed |

```kotlin
import io.github.kaeferfreund.socketio.engineio.EnginePacketObserver
import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.engineio.SocketLogger

val options = SocketManagerOptions {
    logger = SocketLogger.console(LogLevel.DEBUG)
    packetObserver = EnginePacketObserver { outgoing, packet ->
        println("${if (outgoing) "->" else "<-"} ${packet.type} ${packet.text.orEmpty().take(200)}")
    }
}
```

Log output and observed packets can contain authentication data and payloads.
Keep them out of production builds.

## Limits

Everything is unlimited by default, as in JavaScript. Two independent layers
exist for apps that process untrusted or unusually large input.

`SocketParserOptions` (`parserOptions`) bounds one incoming packet:

| Field | Default |
| --- | --- |
| `maxAttachments` | `10` (binary attachments per packet, as in `socket.io-parser`) |
| `maxBinaryPacketBytes` | `Long.MAX_VALUE` |
| `maxTextPacketBytes` | `Long.MAX_VALUE` |
| `maxNestingDepth` | `Int.MAX_VALUE` (parsing is iterative; this is a resource bound) |
| `reviver` | `null` (`JSON.parse` reviver) |

A packet over a parser limit closes the connection with `"parse error"`; the
manager reconnects. On WebSocket, OkHttp reads each incoming message completely,
and inflates it when `permessage-deflate` was negotiated (the default), before the
client sees it. OkHttp has no size limit for this buffer and offers no hook to set
one, and a compressed message can grow to about a thousand times the bytes sent.
The parser limits therefore bound what is processed, not that buffer, and
`maxPollingResponseBytes` does not apply to WebSocket. For a server you do not
trust, use polling only (`transports = listOf(Transport.POLLING)`) with
`maxPollingResponseBytes`, which is enforced while reading. Where WebSocket is
needed, `perMessageDeflateThreshold = null` at least keeps compression off, so a
message takes no more memory than the server actually sends.

`SocketBufferLimits` (`bufferLimits`) bounds what is kept across packets:
`maxSendBufferPackets`/`Bytes`, `maxRetryQueuePackets`/`Bytes`,
`maxReceiveBufferPackets`/`Bytes` and `binaryReconstructionTimeout`. An emit over
an outgoing limit fails with `SocketBufferLimitException` through its
acknowledgement callback and `ManagerEvent.Error` (not as `connect_error`); nothing
already accepted is evicted. A receive-buffer overflow closes the connection with `"transport error"`;
a binary packet still missing attachments after `binaryReconstructionTimeout`
closes it with `"parse error"`. The manager reconnects in both cases.

```kotlin
import io.github.kaeferfreund.socketio.SocketBufferLimits
import io.github.kaeferfreund.socketio.parser.SocketParserOptions
import kotlin.time.Duration.Companion.seconds

val options = SocketManagerOptions {
    parserOptions = SocketParserOptions(maxTextPacketBytes = 1L shl 20, maxBinaryPacketBytes = 8L shl 20)
    bufferLimits = SocketBufferLimits(maxSendBufferPackets = 500, binaryReconstructionTimeout = 30.seconds)
}
```

Byte counts of the buffers are estimates of retained memory, not wire sizes.

## Hardening for untrusted servers

The defaults above stay unlimited for parity with JavaScript. An app that
connects to servers it does not control (a public or user-supplied URL, or a
backend whose compromise must not take the app down with it) should set the
limits explicitly. The values below are a starting point for chat-sized
messages; size them from the largest payload your protocol really sends.

```kotlin
import io.github.kaeferfreund.socketio.SocketBufferLimits
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.SocketOptions
import io.github.kaeferfreund.socketio.engineio.HandshakeLimits
import io.github.kaeferfreund.socketio.parser.SocketParserOptions
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val hardened = SocketManagerOptions {
    // One incoming packet.
    parserOptions = SocketParserOptions(
        maxAttachments = 10,
        maxTextPacketBytes = 1L shl 20, // 1 MiB of header plus JSON
        maxBinaryPacketBytes = 8L shl 20, // 8 MiB of attachments
        maxNestingDepth = 64,
    )
    // What is kept across packets.
    bufferLimits = SocketBufferLimits(
        maxSendBufferPackets = 500,
        maxSendBufferBytes = 4L shl 20,
        maxRetryQueuePackets = 500,
        maxRetryQueueBytes = 4L shl 20,
        maxReceiveBufferPackets = 100,
        maxReceiveBufferBytes = 1L shl 20,
        binaryReconstructionTimeout = 30.seconds,
    )
    // Engine.IO: one polling response and the handshake timers.
    maxPollingResponseBytes = 16L shl 20
    requestTimeout = 30.seconds
    handshakeLimits = HandshakeLimits(maxPingInterval = 2.minutes, maxPingTimeout = 2.minutes)
    // Listener exceptions go to your own reporting instead of the log.
    listenerErrorHandler = { error -> reportToCrashTool(error) }
}
val manager = SocketManager("https://example.com", hardened)
val socket = manager.socket("/", SocketOptions { ackTimeout = 30.seconds })
```

- One polling response can batch several packets, and binary attachments travel
  base64-encoded (a third larger), so keep `maxPollingResponseBytes` above
  `maxTextPacketBytes` and about 4/3 of `maxBinaryPacketBytes`.
- `handshakeLimits` must stay above the server's `pingInterval` and
  `pingTimeout` (Socket.IO defaults: 25 and 20 seconds), or every connection
  fails with `"invalid handshake"`. Without a bound, a server can announce timers
  so large that a dead connection is never detected.
- `ackTimeout` (per socket, or per emit with `timeout(…)`) bounds how long
  acknowledgement callbacks wait for a server that never answers.
- On WebSocket the parser limits bound what the client processes, not the
  message OkHttp reads first; see [Limits](#limits). Where that matters, restrict
  `transports` to `Transport.POLLING`.
- Without `listenerErrorHandler`, a listener exception goes to `logger`; if the
  logger does not log errors (`SocketLogger.NONE`, the JVM default), the
  exception and its stack trace are printed to standard error. Set a handler
  when exception messages may contain user data.

A limit that triggers closes the connection with the error described in
[Limits](#limits), and the manager reconnects with its usual backoff.
