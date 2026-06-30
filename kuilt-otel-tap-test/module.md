# Module kuilt-otel-tap-test

Test and CI support for extracting a device's logs with the log tap.

This is the helper layer a test or a simulator/CI harness uses on top of
`:kuilt-otel-tap`'s `LogTapClient`:

- **`awaitLog` / `awaitLogBodyContaining`** — live-tail a tapped device and wait
  (under a bounded, virtual-time-aware timeout) for a `LogRecord` matching a
  predicate, failing with the records that *did* arrive.
- **`dumpLogs` / `dumpingOnFailure`** — on a test failure, pull the device's
  captured logs and surface them human-readably and as NDJSON, so a CI failure
  carries the device's own logs.
- **`writeLogArtifact` / `logArtifactLines`** — write a per-device NDJSON artifact
  (one JSON `LogRecord` per line) through a multiplatform kotlinx-io `Sink`.

Shipped in `commonMain` so downstream consumers can depend on it from their own
tests, mirroring `:kuilt-raft-test` / `:kuilt-session-test` / `:kuilt-deal-test`.
