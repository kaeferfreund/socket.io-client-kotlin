# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/).

## Unreleased

### Behaviour changes

- **Polling follows redirects to other origins, without the first origin's
  credentials.** 17.1.0 refused every polling redirect to another scheme, host
  or port. Polling now follows them like a browser, curl and the Swift client,
  with OkHttp's method rules (303 and, for a POST, 301/302 continue as a GET;
  307/308 repeat the POST with its body) and at most 20 follow-ups:
  - on an origin change, `Authorization` and `Proxy-Authorization` are removed;
  - the `Cookie` header is rebuilt for every hop: cookies of the
    `withCredentials` jar that RFC 6265 allows for the new URL, and a `Cookie`
    entry of `extraHeaders` only on the host of the server URL (any scheme or
    port). `Set-Cookie` headers of redirect responses are stored before the
    redirect is followed;
  - HTTP→HTTPS is followed, HTTPS→HTTP never (also not after an HTTP→HTTPS hop);
  - with a client certificate configured through `TlsPolicy` (or
    `withKeyChainAlias`), redirects to another origin are still refused, and
    with certificate pins, a redirect to another host is refused unless a pin
    covers it. Both are decided before any connection to the target;
  - other headers, such as an API key in `extraHeaders`, follow the redirect.
    Send tokens that must stay with your server as `Authorization`.
  The WebSocket handshake still follows no redirect. See
  [Redirects and cookies](Documentation/Guides/Configuration.md#redirects-and-cookies).
- **The `withCredentials` cookie jar is scoped by RFC 6265.** Cookies stored
  from responses go back only where their `Domain` (host-only without it),
  `Path`, `Secure` flag and expiry allow; ports do not scope them. A `Domain`
  the server's host is not part of, a single-label `Domain` and a `Domain` on an
  IP address host reject the cookie; a `Secure` cookie is sent over HTTPS/WSS
  only and ignored when set over plain HTTP, except on loopback hosts, which
  count as secure as in browsers. A cookie now replaces only the one with the
  same name, domain and path.

Migration:

- A server that relied on the refusal (a redirect to another origin used to fail
  the request) now sees the redirect followed. To keep refusing, configure your
  OkHttp client with `followRedirects(false)`: polling then follows no redirect
  at all.
- Move tokens from custom headers to `Authorization` if they must not reach a
  redirect target; custom `extraHeaders` follow redirects.
- A server on plain HTTP (other than `localhost`) that sets `Secure` cookies no
  longer gets them back; drop `Secure` there or serve over HTTPS. A server that
  sets `Domain` to a host its own name is not part of no longer gets the cookie.

### API

- `EngineRedirectCookies` and `EngineHttpRequest.redirectCookies`: the engine
  hands every polling request the cookies it may carry on redirects. Additive:
  the existing `EngineHttpRequest` constructor is unchanged, and a custom
  `EngineHttpClient` that ignores the new property keeps working.

## 17.1.0 — 2026-11-20

A security release. A review of the whole repository found ways in which a
hostile or compromised server, or a malformed URL, could redirect traffic to
another host, keep connections and memory alive without bound, mix data between
sessions or users, or leak credentials into logs. This version fixes them.

The public API is unchanged: every `api/*.api` signature is the same as in
17.0.1. Defaults are unchanged too: every resource limit stays off unless you set
it, as in the JavaScript client. Against a well-behaved Socket.IO server, almost
everything behaves as before. **Three changes can affect a correct app** and are
listed first, each with its own issue for questions and feedback. Every
deliberate difference from the JavaScript client is described in the
[compatibility guide](Documentation/Guides/Compatibility.md).

### Behaviour changes to check before upgrading

- **`clearRecoveryState()` also drops pending emits**
  ([#16](https://github.com/kaeferfreund/socket.io-client-kotlin/issues/16)).
  - **What it drops:** emits waiting in the send buffer (made while
    disconnected), the retry queue and events received before a CONNECT. Their
    acknowledgements fail with `SocketDisconnectedException`.
  - **Why:** the user-switch recipe in the connections guide relies on this
    method, and without it emits made by the previous user while offline were
    sent under the next user's session.
  - **Who is affected:** apps that call it for another reason, such as a fresh
    session for the same user. They lose emits buffered while offline; re-emit
    them after the call if they must be delivered.
- **Closing engines end after at most 10 seconds**
  ([#17](https://github.com/kaeferfreund/socket.io-client-kotlin/issues/17)).
  - **What changed:** every Engine.IO connection the manager closes or replaces
    is closed hard if it has not finished closing after 10 seconds. This covers
    `disconnect()`, `pause()`, connect timeouts and reconnects. Before, only
    `close()` had this limit.
  - **Why:** a server could otherwise keep a closed connection polling forever.
  - **Who is affected:** on the polling transport over a slow uplink, a large
    emit sent right before `disconnect()` is cancelled after 10 seconds. Wait for
    its acknowledgement before disconnecting, or use WebSocket.
- **Cookies follow the `Path` rules across transports**
  ([#18](https://github.com/kaeferfreund/socket.io-client-kotlin/issues/18)).
  - **What changed:** with `withCredentials = true`, a cookie without `Path`
    applies only to the path that set it, as in a browser.
  - **Who is affected:** only setups where polling and WebSocket use different
    paths (`TransportOverrides.path`). A sticky-session cookie without `Path` set
    on the polling path is no longer sent with the WebSocket handshake. Have the
    server set `Path=/`, or use one path for both transports.

Smaller differences that only a server breaking the protocol, or unusual
configurations, will notice:

- **Unsolicited packets are ignored.** This covers acknowledgements for emits
  that were not sent yet, and a namespace CONNECT or CONNECT_ERROR that answers
  no CONNECT of the socket. An acknowledgement that arrives after its connection
  closed is not sent on the next one.
- **Cookie jar limits** (with `withCredentials`), as in browsers: at most 180
  cookies, the oldest makes room, and at most 4096 bytes of name and value per
  cookie.
- **Shared binary arrays.** An attachment referenced several times in one
  packet is converted to one shared `ByteArray`, as JavaScript shares one
  Buffer.
- **TLS pins are combined.** `TlsPolicy` keeps the trust manager and the pins
  of a supplied OkHttp client instead of replacing them. A host pinned both
  there and in the policy accepts either set of pins, as OkHttp combines pins.
- **Multiplexing warning.** `SocketIO.io()` logs a warning when it returns a
  cached manager although the call passed other options or another query.
- **Fatal errors propagate.** `serializableAck` no longer turns VM errors such
  as `OutOfMemoryError` into a failed `Result`.
- **Unoffered WebSocket extensions.** A WebSocket handshake that enables an
  extension the client did not offer fails, as RFC 6455 requires.

### Security

- **URL parsing.** The parser now finds the same host as OkHttp's `HttpUrl`.
  - Brackets count as an IPv6 host only in the host part, so
    `https://chat.example.com/?ids[]=1` no longer connects to `hat.example.co`.
  - The scheme is matched regardless of case, so `HTTPS://host` no longer
    connects without TLS to a host named `HTTPS`.
  - A relative transport `path` can no longer change the host.
  - Hosts written with `\`, extra slashes, several `@` or `:` in the user info,
    or an `@` after `\`, `?` or `#` resolve to the same host as in `HttpUrl`.
- **Engine.IO.**
  - Duplicate upgrade offers and repeated handshakes no longer start extra
    probe connections.
  - A handshake that arrives after `close()` no longer reopens the engine.
  - A failed upgrade probe no longer stalls polling or blocks `close()`.
  - Closing engines no longer extend their heartbeat and cancel pending
    requests; see [#17](https://github.com/kaeferfreund/socket.io-client-kotlin/issues/17)
    for the time limit.
  - At most one unsent pong is buffered.
  - Polling packets are handled as they are decoded.
  - Cookies: an already expired cookie never evicts a live one, and `Path`
    attributes are recognised after a `;` without a space.
- **Socket.IO.**
  - The user-switch recipe no longer leaks emits to the next user
    ([#16](https://github.com/kaeferfreund/socket.io-client-kotlin/issues/16)).
  - An unrequested CONNECT_ERROR used to leave a connected socket marked as
    connected, so `connect()` did nothing any more. It is now ignored.
  - Empty events count against the receive-buffer byte limit.
  - Concurrent `SocketIO.io()` calls no longer share one namespace socket
    between callers.
- **Parser and serialization.**
  - A repeated attachment is copied once per conversion instead of once per
    reference.
  - Rejected binary input is not copied before the byte-limit check.
  - A payload nested too deeply to decode is reported as a
    `SerializationException` from every decoder, not as a `StackOverflowError`.
- **OkHttp and TLS.**
  - Cross-origin polling redirects are refused before any connection to the
    other origin.
  - A refused WebSocket handshake releases its pooled connection.
- **Credentials.** Query values, user info, fragments, cookie values, rejected
  header and subprotocol values and invalid auth values no longer appear in
  `toString()` output or error messages.

### Documentation

- A [hardening recipe](Documentation/Guides/Configuration.md#hardening-for-untrusted-servers)
  for apps that talk to servers they do not control, with every limit option.
- A precise description of what bounds incoming WebSocket messages, how
  `SocketIO.io()` multiplexing treats options, and the user-switch recipe.
- New sections "Stricter session handling" and "Engine.IO connection hardening"
  in the compatibility guide.

### Build

- Release jobs no longer restore the CI Gradle cache or use the build cache, and
  pass workflow values to scripts through the environment.

### Testing

- `socketio-testing`: `FixtureServer.close()` also stops the processes the
  fixture started. When `node` is a version-manager shim (Volta, asdf, mise),
  the server runs as the shim's child and a signal to the shim alone left it
  running until the test JVM exited. The same applies when a fixture fails to
  start and when `npm ci` times out.
- `socketio-testing`: the fixture watchdog script is created readable and
  writable by its owner only.

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
