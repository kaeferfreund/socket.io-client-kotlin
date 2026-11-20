# Android integration

[Documentation index](../README.md) · [Project overview](../../README.md)

The `socketio-android` module adapts a manager to the platform with one call:

```kotlin
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.android.BackgroundPolicy
import io.github.kaeferfreund.socketio.android.android
import kotlin.time.Duration.Companion.seconds

val manager = SocketManager(
    "https://example.com",
    SocketManagerOptions {
        android(context) {
            backgroundPolicy = BackgroundPolicy.DisconnectAfter(30.seconds)
            trafficStatsTag = 0x5001
        }
    },
)
```

`android(context)` stores the application context, never an `Activity`. The
module's manifest adds `INTERNET` and `ACCESS_NETWORK_STATE`.

## Settings

| Setting | Default | Effect |
| --- | --- | --- |
| `bindToActiveNetwork` | `true` | Sockets and DNS go through the current default network and move with it; a VPN is left to the system's routing |
| `reconnectOnNetworkAvailable` | `true` | Hold reconnection attempts while offline; reconnect as soon as a network returns |
| `backgroundPolicy` | `BackgroundPolicy.KeepAlive` | What happens while no activity is visible |
| `trafficStatsTag` | `null` | `TrafficStats` tag for this manager's sockets |
| `mainThreadCallbacks` | `true` | Listener and acknowledgement callbacks on `Dispatchers.Main.immediate` |
| `logTag` | `"SocketIO"` | Logcat tag, gated by `Log.isLoggable`; `null` disables logging |
| `tracing` | `false` | Perfetto sections through `androidx.tracing` |
| `releaseConnectionsOnTrimMemory` | `true` | Evict idle pooled connections on `onTrimMemory` |
| `tlsPolicy` | `TlsPolicy.systemDefault()` | Server trust and client certificate; see [Configuration](Configuration.md#tls) |
| `okHttpClient` | `null` (shared default) | Base OkHttp client: proxy, interceptors, cookie jar, … |

In addition, `android(context)` always sets `timeSource = ElapsedRealtimeTimeSource`
and installs the network monitor. Options assigned after the `android(context)`
call in the same builder override what it set (for example `logger`). Do not call
`okHttp { }` after it: that replaces the network-bound client. Configure the base
client through `okHttpClient`, or add settings inside the block with
`configureOkHttp { … }` (proxy, interceptors, cookie jar), which is applied on top
of the network binding.

## Network changes

The manager follows the default network with
`ConnectivityManager.registerDefaultNetworkCallback`:

| Event | Reaction |
| --- | --- |
| Default network lost | Idle connections are evicted and the engine closes at once with `"transport close"` (details `"network connection lost"`). Reconnection attempts wait without using up attempts |
| A network becomes available | A pending reconnection attempt starts immediately instead of finishing its backoff delay |
| Default network changes (Wi-Fi ↔ mobile, VPN on/off) | The connection on the old network is dropped and a reconnect starts on the new one right away |

Without this, a connection on a vanished network is only noticed after
`pingInterval + pingTimeout` (45 s with server defaults).

With `bindToActiveNetwork`, connections are created with `Network.socketFactory`
and names are resolved with `Network.getAllByName`. A connection therefore belongs
to exactly one network and fails cleanly when it goes away instead of hanging. A
VPN default network is not bound (the system routes through it anyway, and bound
lookups on some VPNs fail); a bound lookup that fails falls back to the system
resolver. Set
it to `false` if the app manages routing itself (for example
`ConnectivityManager.bindProcessToNetwork`).

### Network status

`manager.networkStatus` (extension property) publishes the default network as a
`StateFlow<NetworkStatus>`; it is `null` for managers built without `android()`:

```kotlin
import io.github.kaeferfreund.socketio.android.networkStatus

lifecycleScope.launch {
    manager.networkStatus?.collect { status ->
        offlineBanner.isVisible = !status.available
    }
}
```

`NetworkStatus` has `available`, `metered` and `validated` (Android validated
internet access). The initial value is `NetworkStatus.UNKNOWN` until the first
callback.

## Background policy

`BackgroundPolicy` uses `ProcessLifecycleOwner`: "background" means no activity
of the process is started.

| Policy | Behaviour |
| --- | --- |
| `KeepAlive` | Stay connected (default). The system may still freeze the process |
| `DisconnectImmediately` | Pause as soon as the app goes to the background |
| `DisconnectAfter(delay)` | Pause after `delay` in the background; returning earlier cancels it. `Duration.INFINITE` never pauses |

A manager created while the app is already in the background (the process was
started for a push message or a job) follows the policy from the start.

Pausing calls `manager.pause()`: the connection closes (sockets see
`DisconnectReason.FORCED_CLOSE`) but every socket stays *active*. When the app
returns, `manager.resume()` reconnects them, with connection state recovery if the
server enables it (see [Connections](Connections.md#connection-state-recovery)).
While paused, no automatic reconnection happens; emits are buffered and sent after
reconnecting. `manager.isPaused` reports the state.

## Doze and heartbeats

The heartbeat deadline is measured with `ElapsedRealtimeTimeSource`
(`SystemClock.elapsedRealtimeNanos()`), which keeps counting in deep sleep.
`System.nanoTime()` stops, which would make a connection that the server already
dropped look healthy after wake-up.

After Doze or app standby:

1. Timers do not advance during deep sleep, so the first emit after wake-up checks
   the deadline. If it passed, the emit is buffered, not written to a dead
   connection.
2. The engine closes once with `"ping timeout"`.
3. The manager reconnects once and flushes the buffer.

The library holds no wake locks and cannot keep a connection alive in Doze. For
delivery while the app is in the background, use push messages (FCM) or a
foreground service owned by your app.

## Threads and StrictMode

With `mainThreadCallbacks = true`, listeners (`on`, `onConnect`, `onAny`,
`ManagerEvent` listeners) and `AckCallback`s run on the main thread, so they may
update views directly. They must stay short: do not do disk or network I/O there.
Library calls (`emit`, `connect`, `disconnect`, `close`) only enqueue work and are
safe on the main thread. Network, DNS and KeyChain access happen on OkHttp threads.

StrictMode's `detectUntaggedSockets()` reports sockets without a `TrafficStats`
tag. Set `trafficStatsTag` to attribute the traffic in `NetworkStatsManager` and
Android Studio's Network Inspector.

## Logging and tracing

`AndroidLogger` writes to logcat under `logTag` and checks `Log.isLoggable`
first, so disabled levels cost nothing. By default only `INFO` and above are
loggable. Enable debug output on a device:

```sh
adb shell setprop log.tag.SocketIO DEBUG
adb logcat -s SocketIO
```

Log output can contain URLs, headers and payloads; do not enable verbose levels on
production devices.

With `tracing = true`, `AndroidTracer` emits asynchronous Perfetto sections:
`socket.io connect` (engine open attempt), `socket.io upgrade` (WebSocket probe to
switch) and `socket.io ack <event>` (emit to acknowledgement). Record a trace with
app tracing enabled for your package (Perfetto UI or Android Studio's System
Trace) to see them.

## Memory

With `releaseConnectionsOnTrimMemory`, `onTrimMemory` at
`TRIM_MEMORY_BACKGROUND` or above evicts idle OkHttp connections. Buffered emits
and received events are never dropped by memory pressure; bound them with
`SocketBufferLimits` ([Configuration](Configuration.md#limits)).

## Client certificates from KeyChain

Mutual TLS with a certificate the user installed on the device:

```kotlin
import io.github.kaeferfreund.socketio.android.chooseKeyChainAlias
import io.github.kaeferfreund.socketio.android.withKeyChainAlias
import io.github.kaeferfreund.socketio.okhttp.TlsPolicy

// 1. Once, from an Activity: the system dialog lets the user pick and grant a certificate.
lifecycleScope.launch {
    val alias = chooseKeyChainAlias(activity, host = "api.example.com", port = 443) ?: return@launch
    preferences.edit().putString("client_cert_alias", alias).apply()
}

// 2. Whenever a manager is created: present that certificate.
val alias = preferences.getString("client_cert_alias", null)
val manager = SocketManager(
    "https://api.example.com",
    SocketManagerOptions {
        android(context) {
            if (alias != null) tlsPolicy = TlsPolicy.systemDefault().withKeyChainAlias(context, alias)
        }
    },
)
```

`chooseKeyChainAlias` returns `null` when the user cancels. The grant survives app
restarts; store only the alias. `KeyChainKeyManager` reads the key during the TLS
handshake on OkHttp's connection thread. If the user removes the certificate, or
the KeyChain cannot provide the key (for example after a lock-screen change
invalidated it), the handshake fails and `connect_error` reports a
`TransportException` with the TLS error as `cause`; ask for a new alias.

`KeyChainKeyManager` presents the certificate to every server that asks for one
during a handshake of this manager, whatever certificate authorities the server
lists, as Android's own KeyChain clients do: servers that accept certificates
from authorities they do not list keep working. The manager connects only to the
origin of its URL: while a client certificate is configured, a polling redirect
to another origin is refused before any connection is made (see
[Redirects and cookies](Configuration.md#redirects-and-cookies)).

## Network Security Config

`TlsPolicy.systemDefault()` keeps the platform trust manager, so the app's
`network_security_config.xml` applies to polling and WebSocket alike.

Allow cleartext only for local development, in the debug source set
(`src/debug/res/xml/network_security_config.xml`):

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

Pins can be declared here instead of in `TlsPolicy`:

```xml
<domain-config>
    <domain includeSubdomains="true">example.com</domain>
    <pin-set expiration="2027-06-30">
        <pin digest="SHA-256">base64-encoded-spki-hash=</pin>
        <pin digest="SHA-256">base64-encoded-backup-hash=</pin>
    </pin-set>
</domain-config>
```

Reference the file with `android:networkSecurityConfig="@xml/network_security_config"`
on `<application>`. `TlsPolicy.customTrust(…)` replaces the platform trust manager
for this client, so the config's trust anchors and pins no longer apply to it
(declare pins in the `TlsPolicy` then); cleartext rules still apply. A client
certificate alone (`withKeyChainAlias`, `withClientCertificate`) keeps the
trust manager of the base client: the platform's, or that of your `okHttpClient`.

## R8 and ProGuard

The AAR ships consumer rules that keep `OkHttpEngineClientsProvider` (found through
`ServiceLoader`) and the name of `EngineClients.Provider`. No app rules are
required. `android(context)` passes its OkHttp stack explicitly and does not rely
on `ServiceLoader` at all.

## Saving configuration across process death

`SavedSocketConfig` stores the URL, `path`, `transports`, `query`, `extraHeaders`
and namespace list in a `Bundle`. Session state (ids, buffers, acknowledgements) is
not saved; a restored app starts a new connection. Do not store credentials in it;
provide them again with an `AuthProvider`.

```kotlin
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.android.SavedSocketConfig
import io.github.kaeferfreund.socketio.android.android

class ChatActivity : ComponentActivity() {
    private lateinit var config: SavedSocketConfig
    private lateinit var manager: SocketManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = SavedSocketConfig.fromBundle(savedStateRegistry.consumeRestoredStateForKey(KEY))
            ?: SavedSocketConfig(uri = "https://example.com", namespaces = listOf("/chat"))
        savedStateRegistry.registerSavedStateProvider(KEY) { config.toBundle() }

        val saved = config
        manager = SocketManager(
            saved.uri,
            SocketManagerOptions {
                android(this@ChatActivity)
                saved.applyTo(this)
            },
        )
        saved.namespaces.forEach { manager.socket(it) }
    }

    override fun onDestroy() {
        manager.close()
        super.onDestroy()
    }

    private companion object {
        const val KEY = "socket-config"
    }
}
```

In a `ViewModel`, store `config.toBundle()` in the `SavedStateHandle` instead.

## Work that must outlive the connection

The library does not schedule WorkManager jobs or run a foreground service. It
exposes what such code needs:

- `socket.pendingEmits` — a `StateFlow<Int>` of emits waiting in the send buffer or
  the retry queue.
- `SocketOptions.outgoingInterceptor` — an `OutgoingInterceptor` told when an emit
  is buffered (`onBuffered`), handed to the transport (`onSent`) or discarded
  (`onDropped`).

```kotlin
import io.github.kaeferfreund.socketio.OutgoingEvent
import io.github.kaeferfreund.socketio.OutgoingInterceptor
import io.github.kaeferfreund.socketio.SocketOptions

val socket = manager.socket(
    "/",
    SocketOptions {
        retries = 3
        outgoingInterceptor = object : OutgoingInterceptor {
            override fun onBuffered(event: OutgoingEvent) {
                outbox.offer(event.name, event.args)
            }

            override fun onSent(event: OutgoingEvent) {
                outbox.markSent(event.name, event.args)
            }
        }
    },
)
```

`outbox` stands for your persistence layer. Interceptor callbacks run on the
protocol executor: hand the work to a queue or coroutine instead of writing to disk
there. A worker can later re-send persisted events once the app is connected.
