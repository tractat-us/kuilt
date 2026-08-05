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

## Following a trace

When your app runs distributed tracing, wrap the work in
`withActiveTrace(trace) { … }` and kuilt keeps and stamps the log lines that belong
to a kept trace — on an iPhone and in the browser too, not only on a server. Wire a
`CoroutineContextTraceProvider` into `installLogCapture` and the sampling gate reads
the current trace from that scope; on the JVM you can instead let the OpenTelemetry
SDK be the source (`kuilt-otel-sdk`). Both feed the same gate.

The reach of a trace differs by platform: JVM/Android propagate it across coroutine
thread hops and to child coroutines, and wasmJs is single-threaded so it always
holds within the block. On iOS/macOS the trace is reliable for a line logged
synchronously within the span (the common case); if the block suspends and resumes
on a different worker thread the trace is not carried to it — no Kotlin/Native
primitive can mirror it, and the restore is guarded so it can never corrupt another
scope's trace. `MutableTraceContextHolder` is a minimal escape hatch for code that
can't express its tracing as coroutine scopes.

## Stamping your own context onto every line

Apps usually want to know *what was going on* when a line was logged — which game
was in progress, which request was being served, which screen was open. Give
`CaptureConfig` an `attributeMapper` and it folds that context into every captured
record.

The mapper runs **at the moment the line is logged**, on the thread that logged it —
not later, when the record is written to the buffer. So a line logged during game A
is stamped with game A even if game B has already started by the time the record
lands. That matters most exactly where it is easiest to get wrong: the last few
lines of one phase and the first few of the next, which are usually the ones you
are reading the log to understand.

Two things follow. Keep the mapper cheap, because it runs on your logging path once
per captured line (it is skipped for lines below `minLevel`, and for kuilt's own
exporter loggers). And it should not throw — a mapper that throws loses that one
record rather than surfacing an error inside your `log` call.

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

## When your app logs faster than the buffer takes them

Your `log.info { … }` call does not wait for the record to be written — it hands the
line to a queue and returns, and a drain writes it just behind you. On a slow device
with a large buffer that drain can fall behind a chatty app and stay behind.

The queue is bounded (`CAPTURE_QUEUE_CAPACITY`) and drops the **oldest** line when
it is full, because it lives in the host application's memory: an unbounded queue
here is unbounded heap growth in the app being observed. Suspending the caller
instead was rejected for the same reason — a slow telemetry store must never slow
down the app it is watching. Dropping the oldest matches the exporter's own
`BufferPolicy.DROP_OLDEST` below it, so both buffers on the path behave alike, and
keeps the newest lines, which are the ones a post-hoc diagnosis wants.

The loss is counted, not silent: `LogCaptureInstallation.health.droppedEvents` is an
exact cumulative count of lines dropped for want of queue space, and the first drop
is logged once (under a `us.tractat.kuilt.otel` logger, so the report is excluded
from capture and can never feed back into the queue it is reporting on). Read it
alongside `WarpLogRecordExporter.health`: drops climbing while the exporter is
healthy means the export path works and is simply too slow for this log volume.

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
