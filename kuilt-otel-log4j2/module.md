# Module kuilt-otel-log4j2

Catch the log lines other libraries write — frameworks, your own log4j2 config,
transitive dependencies — into the same offline-first telemetry buffer, on the JVM
and Android.

The uniform capture edge in `kuilt-otel-logging` routes an app's own
`kotlin-logging` calls into the buffer, identically on every platform. On the JVM
that edge takes `kotlin-logging` off the SLF4J path while capture is on, so logs
written **straight to SLF4J or log4j2** by other code never reach it. This module
is the optional, additive add-on that recovers them — the log4j2 sibling of
`kuilt-otel-logback`. Pick the one that matches the backend on your classpath.

## What it does

`installLog4j2Capture` builds a capture core over your `WarpLogRecordExporter` —
the *same* exporter the uniform edge uses — and attaches a log4j2 appender to the
root logger. From then on every log4j2 event (level, message, thread-context/MDC
map, exception message, logger name) is mapped to an OTLP-shaped `LogRecord` and
lands in the same buffer, extractable through the same tap.

It is **additive**: it does not replace the uniform `kotlin-logging` edge or any
console appender you already configured — install it alongside them when you want
the JVM "bonus scope" of catching everything on the log4j2 side.

## Targets

JVM and Android only — log4j2 is JVM-world. Off the JVM there is nothing to
capture here; the uniform edge in `kuilt-otel-logging` is the whole story on iOS,
macOS and wasm.

## Determinism

Time and randomness are injected: event timestamps come from a `Clock` and the
per-record id from a `Random`, both required parameters. The synchronous log4j2
`append` hands events to a single drain coroutine over an unbounded channel — a
scope-bound, single-writer bridge to the `suspend` capture core, not dispatcher
confinement. Tests inject a virtual clock, a seeded RNG, and a test scope.
