# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/).

## Unreleased

### Security

Fixes from a security review of the whole repository. Defaults and the public
API are unchanged; every difference from the JavaScript client is listed in the
[compatibility guide](Documentation/Guides/Compatibility.md).

- URLs: brackets count as an IPv6 host only in the authority, so
  `https://chat.example.com/?ids[]=1` no longer connects to `hat.example.co`.
  The scheme is matched case-insensitively (`HTTPS://host` connected without TLS
  to a host named `HTTPS`), a relative transport `path` can no longer change the host, and the
  host after `\` or several `@`/`:` in the user info is the one OkHttp uses.
- Engine.IO: duplicate upgrade offers and repeated handshakes no longer start
  extra probe connections; closed and closing engines stop polling and cancel
  pending requests; at most one unsent pong is buffered; the cookie jar keeps
  browser limits (180 cookies, 4096 bytes each) and honours `Path` across
  transports; polling packets are handled as they are decoded.
- Socket.IO: `clearRecoveryState()` also drops the previous user's pending
  emits, retries and early events, as the user-switch guide relies on;
  acknowledgements are accepted only for emits that were sent; a namespace
  CONNECT is accepted only as the answer to the socket's own; acks and early
  events stay within their session; empty events count against the
  receive-buffer byte limit; concurrent `SocketIO.io` calls no longer share one
  namespace socket, and reusing a cached manager with other options logs a
  warning.
- Socket.IO: a CONNECT_ERROR or empty CONNECT that answers no CONNECT of the
  socket is ignored; an unrequested CONNECT_ERROR left a connected socket
  destroyed but marked as connected, so `connect()` did nothing any more.
- Parser: a repeated attachment is copied once per conversion instead of once
  per reference, and rejected binary input is not copied before the byte-limit
  check. A payload too deep to decode reaches `onSerializable`'s `onError`.
- Serialization: `decodeAs`, `emitSerializableWithAck` and `serializableAck`
  also report a payload too deep to decode as a `SerializationException`
  instead of a `StackOverflowError`; `serializableAck` no longer turns VM errors
  such as `OutOfMemoryError` into a failed `Result`.
- OkHttp/TLS: `TlsPolicy` keeps the trust manager and pins of a supplied base
  client; cross-origin polling redirects are refused before connecting; a
  WebSocket handshake that enables an extension the client did not offer fails;
  rejected header values and URL queries are redacted from errors.
- Credentials no longer appear in `toString()` of requests, cookies and parsed
  URLs, or in the error for an invalid auth value.

### Documentation

- A hardening recipe for apps that talk to untrusted servers, and precise notes
  on what bounds incoming WebSocket messages.

### Build

- Release jobs no longer restore the CI Gradle cache or use the build cache, and
  pass workflow values to scripts through the environment.

### Testing

- `socketio-testing`: `FixtureServer.close()` also stops the processes the
  fixture started. When `node` is a version-manager shim (Volta, asdf, mise),
  the server runs as the shim's child and a signal to the shim alone left it
  running until the test JVM exited. The same applies when a fixture fails to
  start and when `npm ci` times out.

## 17.0.1 — 2026-09-26

Maven Central, GitHub Packages and the GitHub releases are now in sync: one
workflow publishes each version from its tag to all three, and the GitHub release
only appears once both repositories serve it. The library code is the same as in
17.0.0; only documentation changed.

### Distribution

- Published to Maven Central: `mavenCentral()` is the only repository an app
  needs, without a GitHub token. 17.0.0 stays on GitHub Packages only.
- Every module comes with sources and Dokka javadoc, all signed with GPG
  (key `6FAF6DA67E403DBD07B86B774A40A9D27353D6AD` on `keyserver.ubuntu.com`).

### Documentation

- `SocketTracer` no longer links to a parameter that KDoc cannot resolve.
- `PARITY.md` says what its test count counts: distinct tests, a parameterized
  test once, a Robolectric test once per SDK.

## 17.0.0 — 2026-09-26

First release: a Socket.IO 4 client for Kotlin and Android, ported from the
official JavaScript client at `socketio/socket.io@aaf2af36`. The version shares
its major number with the Swift client
[kaeferfreund/socket.io-client-swift](https://github.com/kaeferfreund/socket.io-client-swift)
17; there are no earlier Kotlin releases.

### Client

- `SocketManager` and `Socket` with the JavaScript semantics: multiplexed
  namespaces, reconnection with jittered backoff, `connect_error` and
  `disconnect` reasons, send and receive buffering, volatile emits, catch-all
  listeners for incoming and outgoing events.
- Acknowledgements as callbacks or suspending `emitWithAck`, with per-emit and
  default timeouts, ordered retries (`retries`, `ackTimeout`) and cancellation
  that withdraws the packet.
- Connection state recovery (`recovered`, replayed events) and
  `clearRecoveryState()`.
- Authentication as an object or a suspending `AuthProvider` that runs before
  every connection attempt.
- `SocketIO.io()` with the JavaScript manager cache (`forceNew`, `multiplex`).
- Engine.IO 4 over HTTP long-polling and WebSocket: upgrade, `tryAllTransports`,
  `rememberUpgrade`, heartbeat, cookies (`withCredentials`), `extraHeaders`,
  per-transport options, custom transports.
- `SocketIOValue`, an immutable JSON model with JavaScript number formatting, key
  order and coercion; conversion from Kotlin and Java values including
  `org.json` types.
- Optional limits for buffers, packet sizes, nesting depth, attachments,
  polling bodies and handshake values.
- Flows for connection state, socket events and manager events; plugins and a
  tracer hook.

### Modules

- `socketio-okhttp`: OkHttp 5 transport stack, discovered automatically; TLS
  policies with private trust anchors, public-key pins and client certificates.
- `socketio-android`: network monitoring and binding, background policy, Doze-safe
  elapsed-realtime clock, logcat logging, Perfetto sections, `TrafficStats`
  tagging, trim-memory handling, KeyChain client certificates, org.json adapters,
  saved configuration, main-thread callbacks.
- `socketio-serialization`: kotlinx.serialization helpers for emits, listeners and
  acknowledgements.
- `socketio-testing`: in-memory Engine.IO and Socket.IO servers for tests on
  virtual time.

### Compatibility

- Android `minSdk` 26, JVM 17; apps on Kotlin 2.2 or newer
  (`socketio-serialization`: 2.3.20).
- Socket.IO 4.x servers (Engine.IO protocol 4). Socket.IO 2.x servers are
  detected and rejected with a clear error.
- WebTransport is not supported.

### Findings from the socket.io-client-java issues and forks

- WebSocket writes are paced below OkHttp's 16 MiB outgoing queue, which OkHttp
  answers with a close and browsers and Node do not have (socket.io-client-java
  #773, #726: bursts of large emits ended in `transport close`). A single message
  above 16 MiB fails with a transport error that names the limit.
- Regression tests for primitive arrays inside `org.json` payloads (#743),
  JSON-looking acknowledgement strings (#567) and many connect/disconnect cycles
  (#289); end-to-end tests wait for events instead of fixed delays.
- `SECURITY.md` and Dependabot (#785); the JVM process-exit behaviour of OkHttp's
  dispatcher threads is documented (#324).
- The reconnection delay stays at `reconnectionDelayMax` after about 1,024 failed
  attempts in a row, where JavaScript's backoff degenerates to 0 ms (a deliberate
  deviation, documented in PARITY.md).
- See [the triage report](Documentation/UpstreamIssueTriage-2026-09-25.md).

### Android: VPN networks (#1)

- `bindToActiveNetwork` no longer binds a VPN default network. Lookups bound to
  a Tailscale VPN failed with `EAI_NODATA` without sending a query, so apps
  behind the VPN could not connect with the default settings. The system routes
  unbound traffic through the VPN anyway; network changes are still followed.
- A lookup that fails on the bound network is retried with the system resolver.

### Fixes from a full code review

- Listener callbacks on a `callbackDispatcher` (the Android default: main
  thread) no longer count as running on the protocol executor: an `emit` from a
  listener used to run the protocol inline on that thread, concurrently with the
  executor.
- A request the HTTP stack refuses to build (a non-ASCII header value, a
  malformed URL) fails with a transport error instead of an uncaught exception
  that crashed Android apps or left the engine without requests; the cookie jar
  ignores cookies that are not printable ASCII.
- Converting received values to `JsonElement` (`decodeAs`, `onSerializable`) or
  to `org.json` (`toJson`) is iterative: a deeply nested payload no longer
  overflows the stack.
- A `Set-Cookie` `Max-Age` beyond the date range no longer throws inside the
  protocol executor; like JavaScript's Invalid Date, such a cookie never expires.
- With `tryAllTransports`, a transport that fails after it opened (a
  handshake this client rejects) is closed before the next one is tried; it used
  to keep polling, creating a server session per request. Closing a WebSocket
  that is still connecting aborts it instead of queueing a close frame behind a
  handshake that may never complete.
- `TlsPolicy.withPins`/`withPinnedCertificates` add to the pins of a host
  pattern instead of replacing them, so a backup pin no longer drops the primary.
- A binary packet header no longer reserves memory for the attachment count
  it declares; with `maxAttachments` raised, a huge count ended in
  `OutOfMemoryError` instead of waiting for (or rejecting) the attachments.
- An `AuthProvider` whose own `withTimeout` expires now fails the attempt
  with an `AuthProviderException` `connect_error`; the timeout used to be taken
  for a cancellation, leaving the socket connecting forever.
- Decoded packets are handled in a microtask right after the transport event
  that carried them, like JavaScript's `nextTick`. They used to be queued behind
  transport events already waiting, so a CONNECT followed at once by a close
  connected a socket of a closed manager.
- `SocketManager.close()` no longer leaves callers hanging: acknowledgements
  that can no longer arrive fail with `SocketDisconnectedException`, buffered and
  queued emits are dropped, and calls made after `close()` fail instead of
  waiting. Callbacks queued while closing still reach a `callbackDispatcher`,
  the executor stops only after the connection sent its last packets (at most
  10 s), and `SocketIO.io()` never hands out a closed manager again.
  `open(callback)` on a paused manager reports an error.
- Errors thrown by listeners (`TODO()`, failed assertions) and a throwing
  `listenerErrorHandler` no longer escape into protocol code, where they could
  stall the retry queue or skip a reconnection. Reconnection settings read or set
  in the manager's `setup` block are no longer lost, a rejected emit (buffer
  limit) no longer appears as `connect_error` on every socket, and an auth
  provider still running for an attempt that ended is cancelled.
- Polling requests follow redirects only within the same origin, and the
  WebSocket handshake follows none: a redirect to another host used to receive
  the extra headers (API keys), cookies and the POST body with the auth payload.
- A query string in the manager URL replaces the `query` option, as
  `engine.io-client` does (`opts.query = parsedUri.query`); the two used to be
  merged with the option winning, under a comment that claimed JavaScript parity.
- `EngineIOFrameDecoder` fails a frame longer than an array can hold with a
  parser error instead of a `NegativeArraySizeException`.
- Android: `configureOkHttp` now applies on top of the network binding, as
  documented, so a custom DNS or socket factory is kept; without
  `bindToActiveNetwork` the base client's DNS stays. The background policy also
  applies to a manager created while the app is in the background,
  `DisconnectAfter(Duration.INFINITE)` no longer pauses at once, closing a manager
  right after creating it off the main thread no longer leaves a lifecycle
  observer behind, and a KeyChain failure during the TLS handshake is a
  `connect_error` instead of an exception on OkHttp's thread.
  `readSocketIOValue` rejects data after the JSON value.
- Numbers print like JavaScript also at powers of two (`2^89` is
  `6.189700196426902e+26`, not 17 digits); checked against Node for 280,140
  values. The JSON reader and `javaScriptNumber` accept only ASCII digits, as
  `JSON.parse` and `Number()` do; a `null` map key converts to `"null"`
  instead of throwing.
- `emitSerializableWithAck` and `serializableAck` decode an acknowledgement
  without arguments as `null` (JavaScript resolves `undefined`) instead of
  throwing `NoSuchElementException`; `NaN` and infinities from
  `Json { allowSpecialFloatingPointValues = true }` convert instead of failing.
- `socketio-testing`: the in-memory server delivers what it sent before a close
  (a server-side `disconnect(true)` now arrives as `io server disconnect` over
  WebSocket), answers the next poll with its close packet like engine.io, and
  sends a refusal without `data` like socket.io. `FixtureServer` bounds its
  `npm ci` wait even when the process stalls and refuses admin calls on TLS
  fixtures.

### Validation

- Every supported runtime test declaration of the pinned upstream
  `socket.io-client`, `engine.io-client`, `socket.io-parser` and
  `engine.io-parser` suites has an assertion contract whose Kotlin tests must pass
  in the same CI run; see [PARITY.md](PARITY.md) for the numbers and the reviewed
  exclusions.
- A parser differential against the pinned JavaScript parser, end-to-end suites
  against Node servers (HTTP, HTTPS, WS, WSS, mutual TLS), Lincheck concurrency
  tests, Robolectric and emulator tests.
