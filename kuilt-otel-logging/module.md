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

## Quick start

One call turns capture on; closing the returned handle turns it off. Your log
call sites do not change.

```kotlin
@sample us.tractat.kuilt.otel.logging.sampleInstallLogCapture
```

To then pull those captured logs off the device from a test or CI process, see
the companion `kuilt-otel-tap` module.

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

## Never captures kuilt's own logs

Capture hooks the process-global logging config, so it sees every log event in the
process — including kuilt's own. The capture core drops any event from a
`us.tractat.kuilt` logger before recording it. This is a safety invariant, not a
setting: the durable buffer logs when it evicts, so capturing that would feed an
eviction back into the buffer and loop. A consumer app is never under that
package, so only kuilt internals are excluded — and every capture edge inherits
the rule through the one shared core.

## Stopping capture

`installLogCapture` returns a handle. Closing it (`installation.close()`) is the
way to stop capture: it restores the previously-installed appender and stops the
capturing appender from buffering any further events. Cancelling the install scope
alone is **not** sufficient — that kills the drain but leaves the appender wired
into the global config, buffering forever.

## Determinism

Time and randomness are injected: the event timestamps come from a `Clock` and the
per-record id from a `Random`, both required parameters — never reached for
directly. Tests inject a virtual clock and a seeded RNG.
