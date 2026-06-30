# Module kuilt-otel-logging

Capture an application's existing log output into kuilt's offline-first telemetry
buffer, through one uniform call on every platform.

An app keeps logging exactly the way it already does. `installLogCapture` wires
that output into a shared capture core that maps each log line to an OTLP-shaped
`LogRecord` and hands it to the durable buffer (`WarpLogRecordExporter` in
`kuilt-otel`). The install call, the record model, the buffer, **and the capture
edge** are identical on JVM, Android, iOS, macOS, and wasmJs: `kotlin-logging`
exposes one settable appender on every target, so a single appender hooks the
output everywhere.

## Where capture happens

One uniform appender is installed in common code on every platform. It feeds each
event into the shared core and forwards it to a per-platform passthrough so the
app's existing log output is preserved:

- **JVM / Android / wasm** — forwards to the console appender that was already
  installed.
- **iOS / macOS** — writes to the Apple unified logging system (`os_log`). The
  message is passed as the `%{public}s` *argument*, never as the format string, so
  a raw `%` in a line (e.g. `url=%20`, `100% done`) renders literally and can never
  trigger the printf-format-string crash class. The handle honours a configured
  `KotlinLoggingConfiguration.subsystem` / `.category` for filtered Console output.

## Determinism

Time and randomness are injected: the event timestamps come from a `Clock` and the
per-record id from a `Random`, both required parameters — never reached for
directly. Tests inject a virtual clock and a seeded RNG.
