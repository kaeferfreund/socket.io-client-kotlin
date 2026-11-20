# Compatibility

[Documentation index](../README.md) · [Project overview](../../README.md)

## Servers and protocol

The client implements **Socket.IO protocol 5** (`SocketIO.PROTOCOL`) over
**Engine.IO protocol 4** (`EIO=4`), the protocols of Socket.IO 3 and 4 servers.

| Socket.IO server | Support |
| --- | --- |
| 2.x and older | Not supported. The connection fails with `connect_error` "It seems you are trying to reach a Socket.IO server in v2.x with a v3.x client, but they are not compatible (…)", or with an invalid handshake |
| 3.x | Same wire protocol, but not tested or supported |
| 4.x | Supported; the reference is the JavaScript client 4.8.x |
| 4.6+ with `connectionStateRecovery` | Additionally, connection state recovery |

There is no protocol-version option. Socket.IO 3 and 4 share the wire protocol,
so the handshake cannot prove the server's major version; 3.x is a supported-version
boundary, not something the client detects.

Other Socket.IO 4 implementations (Python, Go, Java, …) work to the extent they
implement the protocol like the Node server. Only the default parser is
supported; servers using `socket.io-msgpack-parser` or another custom parser
cannot be reached. A plain WebSocket server cannot be reached either.

## Transports

| Transport | Support |
| --- | --- |
| HTTP long-polling | Yes (OkHttp) |
| WebSocket | Yes (OkHttp), including upgrade from polling and `permessage-deflate` |
| WebTransport | No |
| JSONP polling | No |
| Custom | Through `transportFactories`; the server must implement the same transport |

## JavaScript options

Every option of `ManagerOptions`, `SocketOptions` and the Engine.IO options that
makes sense outside a browser exists under the same name with the same default.
Durations are `Duration` instead of milliseconds.

| JavaScript option | Kotlin |
| --- | --- |
| `forceNew`, `multiplex`, `autoConnect` | Same name |
| `reconnection`, `reconnectionAttempts`, `reconnectionDelay`, `reconnectionDelayMax`, `randomizationFactor`, `timeout` | Same name; `reconnectionAttempts` unlimited is `Int.MAX_VALUE`, `timeout = null` disables it |
| `path`, `query`, `extraHeaders`, `addTrailingSlash` | Same name; maps of strings |
| `transports` | Same name, transport names; transport classes go to `transportFactories` |
| `upgrade`, `rememberUpgrade`, `tryAllTransports` | Same name |
| `forceBase64`, `timestampParam`, `timestampRequests` | Same name |
| `requestTimeout` | Same name (polling only) |
| `withCredentials` | Same name; per-connection cookie jar, as the Node client. Cookies that are not printable ASCII are ignored instead of failing every later request |
| Invalid header values | A value OkHttp refuses fails the request with a transport error; JavaScript's polling transport skips such a header silently |
| Redirects | Polling follows redirects within the same origin only (Node's client follows any, browsers apply CORS); the WebSocket handshake follows none, as browsers and Node's `ws` |
| `protocols` | Same name |
| Reconnection backoff | Same formula and jitter; after about 1,024 failed attempts in a row the delay stays at `reconnectionDelayMax`, where JavaScript's computation degenerates to 0 ms |
| WebSocket message size | Unlimited in browsers and Node; OkHttp sends at most 16 MiB per message. Bursts are paced below OkHttp's queue limit. Incoming messages are buffered whole by OkHttp, without a size limit (Node's `ws` defaults to 100 MiB) |
| `perMessageDeflate` | `perMessageDeflateThreshold` (`null` = no compression offered); the per-message `compress` flag reaches the transport, but OkHttp compresses by size only |
| `transportOptions` | Same name, `TransportOverrides` (`query`, `extraHeaders`, `requestTimeout`, `timestampRequests`, `forceBase64`, `path`) |
| `parser` | Not available; `parserOptions` bounds the default parser |
| `auth` (object) | `SocketOptions.auth` |
| `auth` (function) | `SocketOptions.authProvider` |
| `retries`, `ackTimeout` | `SocketOptions.retries`, `SocketOptions.ackTimeout` |
| `hostname`, `secure`, `port`, `host` | Taken from the URL |
| URL parsing | As `parseuri`, except that brackets count as an IPv6 host only inside the authority: `https://example.com/?ids[]=1` keeps the host `example.com`, where JavaScript takes `xample.co`. The scheme matches case-insensitively and is lowercased, so `HTTPS://example.com` uses TLS; JavaScript takes `HTTPS` as the host name. The authority ends at `\` and the host follows its last `@`, as in WHATWG URLs and OkHttp's `HttpUrl`: `https://good.example\@evil.example` and `https://evil.example:x:y@good.example` both connect to `good.example`, where JavaScript takes `evil.example`. Any number of `/` and `\` after the scheme is skipped, also as in `HttpUrl`: `http:///good.example`, `https://\good.example` and `http:/good.example` connect to `good.example`, where JavaScript connects to `localhost` or to the host `http`, and an `@` after a `\`, `?` or `#` does not change that (`http:\\user@good.example` and `http:good.example?a=@b` connect to `good.example`); a scheme name followed by a port (`http:8080`) stays a host name, and a URL without a host (`http://`) still connects to `localhost`. A `path` without leading slash gets one, so it cannot extend or replace the host |
| `ca`, `cert`, `key`, `pfx`, `passphrase`, `rejectUnauthorized` | `TlsPolicy` (no "reject unauthorized = false") |
| `ciphers`, `agent`, `localAddress` | Configure the OkHttp client (`okHttp { }` or `android { okHttpClient = … }`) |
| `closeOnBeforeunload` | Not applicable; Android: `backgroundPolicy` |
| `autoUnref`, `useNativeTimers` | Not applicable (Node/browser timer details) |

Options without JavaScript counterpart: `dispatcher`, `callbackDispatcher`,
`listenerErrorHandler`, `timeSource`, `random`, `logger`, `packetObserver`,
`tracer`, `plugins`, `bufferLimits`, `maxPollingResponseBytes`, `handshakeLimits`,
`clients`, `SocketOptions.outgoingInterceptor`. See [Configuration](Configuration.md).

The API follows the JavaScript client (`emit`, `timeout`, `volatile`, `onAny`,
`onAnyOutgoing`, `emitWithAck`, `SocketIO.io` lookup); the complete comparison,
including deliberate differences, is in [PARITY.md](../../PARITY.md).

### Stricter session handling

A JavaScript client trusts the server to follow the protocol. This client checks
a few things a well-behaved server never does, so behaviour against a real server
is unchanged:

| Situation | JavaScript client | This client |
| --- | --- | --- |
| An acknowledgement for an emit that is still in the send buffer, or a volatile emit that was dropped | Completes the callback | Ignored; the callback waits for the real answer or its timeout |
| A namespace CONNECT with a session id that answers no CONNECT of this socket (for example while the auth provider is still running) | Connects the namespace | Ignored |
| A namespace CONNECT_ERROR, or a CONNECT without a session id (a v2.x server), that answers no CONNECT of this socket (for example on a connected socket) | Emits `connect_error`; CONNECT_ERROR also destroys the socket but leaves it marked as connected, so `connect()` does nothing | Ignored |
| `event.ack.send(…)` after the connection that delivered the event closed | Sent on the current connection | Not sent: the new server session numbers its requests from 0 again |
| Events received before a CONNECT on a connection that then closed | Delivered after the next CONNECT | Dropped |
| `SocketIO.io` called from several threads for one namespace | Not possible (single-threaded) | Only one caller gets the cached manager; the others get a new connection, as a later call does |

## Engine.IO connection hardening

The Engine.IO connection follows the JavaScript client, except where a server
could otherwise make it open connections, keep running or buffer data without
bound. A well-behaved server never notices these differences.

- **Upgrade offers.** Each transport in the handshake's `upgrades` list is
  probed at most once, and never the transport already in use. JavaScript opens
  one probe connection per entry, duplicates included.
- **Repeated handshakes.** An `open` packet after the handshake is ignored.
  JavaScript handles it as a new handshake and probes again.
- **Handshake after `close()`.** When the handshake arrives after `close()`
  was called on a connecting engine, the packets buffered before `close()` are
  still sent and the engine then closes, but it does not become open again: no
  `open` event, no upgrade probe, and pings do not extend its heartbeat
  deadline. JavaScript reopens the engine.
- **Failed upgrade.** When a probe fails after it answered `probe` and the
  polling transport is already pausing for the upgrade, the upgrade is undone:
  polling and writing resume, and `close()` no longer waits for the upgrade.
  JavaScript stays paused until the heartbeat deadline ends the connection, and
  a `close()` in the meantime waits for it.
- **Heartbeat replies.** While a write is pending, one buffered pong answers
  every ping that arrives. JavaScript buffers one pong per ping, so a server
  that stalls the write could grow the buffer without bound.
- **Cookies.** With `withCredentials`, the cookie jar keeps the limits
  browsers apply to one site: at most 180 cookies (the oldest makes room) and
  at most 4096 bytes of name and value per cookie. A cookie that arrives
  already expired only deletes the stored cookie of its name, so it never makes
  a live one give way. The jar of the JavaScript Node client is unbounded. It also ignores `Path`; here, when transports use
  different paths (`TransportOverrides.path`), a cookie set by one transport
  reaches another only if its `Path` matches that path as in a browser. On the
  path that set it, a cookie is always sent back, so connections whose
  transports share a path behave as in JavaScript. `Path` is recognised after
  any `;`, with or without a space, as RFC 6265 parses attributes.
- **Polling responses.** The packets of a polling response are decoded and
  handled one at a time; JavaScript first decodes the whole response into a
  list.
- **Closing.** `close()` still flushes buffered packets first, but a closing
  engine no longer extends its heartbeat deadline when a ping arrives. It does
  not answer pings either (as in JavaScript), so the server drops the session by
  then anyway. A server that never completes the pending write therefore cannot
  keep a closed connection polling: it ends with `"ping timeout"` after at most
  `pingInterval + pingTimeout`.
- **Pending requests.** When the polling transport closes, its pending poll and
  writes are cancelled, also during an upgrade. Only the close packet is still
  sent, with `requestTimeout` or at most 10 seconds. JavaScript leaves every
  request running until the server answers.
- **Engines the manager closed.** An engine that the manager closed while it
  was still sending its buffered packets gets 10 seconds to finish; then it is
  closed at once and its requests are cancelled. This also applies to engines
  replaced by a reconnection, and `close()` on the manager waits for (or ends)
  all of them before the executor stops. JavaScript waits without a limit.

## Platforms

| Component | Requirement |
| --- | --- |
| Android | `minSdk` 26 (Android 8.0), compiled against API 37 |
| JVM | Java 17 or newer |
| Kotlin | Built with Kotlin 2.4 for language and API version 2.2: apps on Kotlin 2.2 or newer (the version AGP 9 bundles) can use it. `socketio-serialization` needs what kotlinx.serialization 1.11 needs (Kotlin 2.3.20) |
| Coroutines | `kotlinx-coroutines` 1.11 |
| HTTP stack | OkHttp 5 (5.5.0), an `api` dependency of `socketio-okhttp` |
| AndroidX | `lifecycle-process` and `tracing` (added by `socketio-android`) |

`socketio-client` has no Android or HTTP dependency and runs on any JVM 17+.
Provide an HTTP/WebSocket stack through `clients` if you do not use OkHttp.

## Values

Payloads use `SocketIOValue`, which follows JavaScript's JSON semantics with two
deliberate points:

**Numbers.** JavaScript has one number type (a double). Here, an integral value
that fits a `Long` is stored as `Long`, everything else as `Double`; `1`, `1L` and
`1.0` are the same value. Integers are therefore **exact** up to ±2⁶³, while the
JavaScript peer rounds beyond 2⁵³ (`9007199254740993` becomes
`9007199254740992` on a Node server). Send large identifiers as strings. `NaN` and
infinities encode as `null`, `-0.0` as `0`, as `JSON.stringify` does.

**Object key order** is the JavaScript order: integer-like keys (`"0"`, `"1"`, …)
first in ascending numeric order, then other keys in insertion order. So
`mapOf("b" to 1, "2" to 2, "1" to 3)` is sent as `{"1":3,"2":2,"b":1}`, exactly as
the JavaScript client would send the same object.

Other conversions: `Date` and `Instant` become ISO-8601 strings like a JavaScript
`Date`; enums become their name; `ByteArray` and `ByteBuffer` become binary
attachments. Other types are rejected; see [Events](Events.md) for the full table.

**Binary data.** Values are immutable, so `bytes` returns a fresh copy on every
read. `toKotlin()` and the Android `toJson()` copy each attachment once per
conversion and reuse that array wherever the packet references it, as
JavaScript reuses the one `Buffer`; a server repeating a placeholder therefore
cannot multiply an attachment in memory beyond `maxBinaryPacketBytes`.
