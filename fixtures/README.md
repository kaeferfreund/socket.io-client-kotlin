# Node fixtures

Real Socket.IO and Engine.IO servers for the end-to-end suite. Dependencies are
pinned by `package-lock.json` (`socket.io` 4.8.3) and installed with
`npm ci --ignore-scripts`; the JUnit harness does this on first use.

| File | Origin | Purpose |
| --- | --- | --- |
| `server.js`, `engine-parity-server.mjs`, `native-tls-server.mjs`, `native-tls-expired.mjs`, `generate-native-tls.mjs`, `polling-request-timeout.mjs`, `polling-proof-observer.mjs`, `*.test.mjs`, `upgrade-race-proof.mjs`, `max-payload-proof.mjs`, `package.json`, `package-lock.json` | Copied unchanged from `kaeferfreund/socket.io-client-swift` 17.1.0 (`Tests/TestSocketIO/E2E/Fixtures`, commit `549c8d4d1d89334af28d4f8a5c7cfdb0f4209f99`) | Shared fixtures with the Swift client |
| `upstream-server.mjs` | Port of `packages/socket.io-client/test/support/server.ts` at the pinned reference | The server of the original client suite |
| `engine-server.mjs` | Port of `packages/engine.io-client/test/support/hooks.js` at the pinned reference | The server of the original engine suite |
| `auth-server.mjs` | Kotlin-specific | Token revocation and renewal across reconnects (KT contracts) |
| `shim.mjs` | Kotlin-specific | Runs `server.js` as a child, like a version-manager shim, so `FixtureServerTest` can pin that closing a fixture stops its server |

Every server listens on an ephemeral `127.0.0.1` port (`native-tls-server.mjs` on
every interface, since its certificate names `localhost`, which resolves to `::1`
or `127.0.0.1`; it runs only during the TLS suite, with throwaway keys) and prints
`READY port=<port> secret=<secret>` once it accepts connections. Admin routes
require the `X-Admin-Secret` header. TLS material is generated per test run in
a temporary directory and never committed.

The protocol proofs (`node --test *.test.mjs`, `upgrade-race-proof.mjs`,
`max-payload-proof.mjs`) run in CI without the Kotlin toolchain.
