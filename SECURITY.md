# Security policy

## Supported versions

Security fixes go into the latest release. Upgrade to it before reporting.

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub security advisories](https://github.com/kaeferfreund/socket.io-client-kotlin/security/advisories/new),
not in public issues. Include the library version or commit, the affected module,
a minimal reproducer and the impact you expect. You will get an answer within a
week; fixes are released as soon as they are verified, and the advisory credits
the reporter unless asked otherwise.

## Scope

In scope: the published modules (`socketio-client`, `engineio-client`, both parsers,
`socketio-okhttp`, `socketio-android`, `socketio-serialization`, `socketio-testing`),
for example parser input that crashes or exhausts the client, TLS policy bypasses,
or leaking credentials into logs. The Node fixtures, the sample app and the CI
setup are development tools; report problems with them as normal issues.

## Hardening already in place

- Peer input is treated as hostile: the parsers only fail with their parse
  exception, are fuzzed with seeded malformed input, and optional limits bound
  sizes, nesting and buffered packets ([Configuration](Documentation/Guides/Configuration.md)).
  These limits are off by default, as in the JavaScript client; apps that talk
  to servers they do not control should set them, see
  [Hardening for untrusted servers](Documentation/Guides/Configuration.md#hardening-for-untrusted-servers).
- There is no switch that turns certificate verification off; private CAs, pins and
  client certificates go through `TlsPolicy`.
- CI runs with read-only tokens, pinned action commits and no persisted
  credentials; Dependabot proposes Gradle and GitHub Actions updates. Release
  jobs build from source without restoring the Gradle or build cache of CI runs.
