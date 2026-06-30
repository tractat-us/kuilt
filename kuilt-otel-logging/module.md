# Module kuilt-otel-logging

Capture an application's existing log output into kuilt's offline-first telemetry
buffer, through one uniform call on every platform.

An app keeps logging exactly the way it already does. `installLogCapture` wires
that output into a shared capture core that maps each log line to an OTLP-shaped
`LogRecord` and hands it to the durable buffer (`WarpLogRecordExporter` in
`kuilt-otel`). The install call, the record model, and the buffer are identical on
JVM, Android, iOS, macOS, and wasmJs; only the thin capture *edge* that hooks into
the platform's logging output differs.

## Where capture happens

- **Native / JS / wasm** — a `kotlin-logging` (oshai) `Appender` is registered,
  normalizing each event and feeding the shared core.
- **JVM / Android** — a no-op for now. `kotlin-logging` logs *through* SLF4J here,
  so capture must sit at the SLF4J layer; that edge lands in a later milestone.

## Determinism

Time and randomness are injected: the event timestamps come from a `Clock` and the
per-record id from a `Random`, both required parameters — never reached for
directly. Tests inject a virtual clock and a seeded RNG.
