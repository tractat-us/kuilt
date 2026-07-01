# Log capture M2 — sampling gate + OTel-SDK bridge

_Design for #990. Part of the log-capture epic #986. Builds on M1
(`2026-06-27-log-capture-and-extraction-design.md`)._

## What this adds

M1 capture is **always-on**: every `kotlin-logging` event on every platform lands
in the durable buffer. M2 layers two **opt-in** capabilities on top, without
changing that default:

1. A **trace/sampling gate** — when an app runs distributed tracing, let its
   sampling decision also decide which logs are worth keeping, and stamp the ones
   it keeps with their trace/span id so logs and spans line up.
2. An **OTel-SDK ingress bridge** — let an app that *already* runs the
   OpenTelemetry SDK point its existing log pipeline at kuilt's offline-first
   buffer, without adopting kuilt's own capture edges.

Both are JVM/Android-only in their concrete form (the OpenTelemetry SDK is
JVM-world), so the gate's *mechanism* lives in shared `commonMain` but its OTel
*wiring* lives in a new JVM/Android module. Default behaviour — no provider
configured, no OTel SDK present — is byte-identical to M1.

## Piece 1 — Sampling gate (`:kuilt-otel-logging`, `commonMain`)

### New public types

```kotlin
// A live view of the caller's current trace context.
public data class ActiveTrace(
    val traceId: ByteString,   // 16 bytes
    val spanId: ByteString,    //  8 bytes
    val sampled: Boolean,
) {
    init { /* require 16/8-byte sizes, matching LogRecord/SpanRecord */ }
}

public fun interface TraceContextProvider {
    // The trace active on the current call, or null when there is none.
    public fun current(): ActiveTrace?
}

public enum class UntracedPolicy { CAPTURE, DROP }
```

### Wiring

- `CaptureConfig` gains `untracedPolicy: UntracedPolicy = UntracedPolicy.CAPTURE`.
  It is a pure value, so it belongs on the config `data class`.
- `TraceContextProvider` is a **live dependency, not a value** — a functional
  interface in a `data class` would break `equals`/`hashCode`. So it becomes a
  **nullable constructor parameter on `LogCapture`** (default `null`) and a new
  optional parameter on `installLogCapture(...)`. `null` provider ⇒ M1 always-on
  behaviour, unchanged.

### The gate

`LogCapture.capture` consults the provider *after* the existing self-exclusion and
`minLevel` checks, *before* building the record:

| Provider | Active trace | Decision |
|---|---|---|
| `null` | — | capture, no stamp (M1 default) |
| set | `null` (untraced) | `untracedPolicy`: `CAPTURE` ⇒ continue, `DROP` ⇒ return `null` |
| set | present, `sampled = true` | capture **and stamp** `traceId`/`spanId` onto the `LogRecord` |
| set | present, `sampled = false` | return `null` (drop before export) |

Stamping reuses the existing `LogRecord.traceId`/`spanId` fields (already present
and validated). A dropped event returns `null` exactly as the level/self-exclusion
filters already do, so callers need no new contract.

## Piece 2 — OTel-SDK bridge (new module `:kuilt-otel-sdk`, JVM/Android)

Mirrors `:kuilt-otel-logback`'s shape: a `jvmAndAndroidMain` intermediate holds
everything, `commonMain`/native/wasm are empty, and the OpenTelemetry artifacts
are **`compileOnly`** — a consumer already running the OTel SDK brings them at
runtime; kuilt never forces the SDK onto anyone.

### `OtelSdkTraceContextProvider : TraceContextProvider`

Reads `io.opentelemetry.api.trace.Span.current().getSpanContext()`. Maps a valid,
sampled/unsampled span context to `ActiveTrace`; an invalid context (no active
span) to `null`. Trace/span ids come from the span context's bytes. Needs
`opentelemetry-api`.

### `KuiltLogRecordExporter` — implements `io.opentelemetry.sdk.logs.export.LogRecordExporter`

The SPI is **already non-blocking**: `export`/`flush`/`shutdown` each return a
`CompletableResultCode` — OpenTelemetry's async completion handle, which the caller
(`BatchLogRecordProcessor`) awaits only when it needs `flush`/`shutdown` to mean
something. So the bridge neither blocks a thread nor lies about completion:

- **`export(logs)`** — map each `LogRecordData` → kuilt `LogRecord` (severity
  number + text, body, attributes, `timestampEpochNanos`/`observedEpochNanos`,
  span context → `traceId`/`spanId`, and an 8-byte `recordId` synthesized from the
  injected `Random`). Allocate a fresh incomplete `CompletableResultCode`, enqueue
  `(records, code)` onto an **unbounded `Channel`**, and return the code
  immediately (non-blocking).
- **Drain** — a single scope-bound drain coroutine pulls each batch, runs
  `WarpLogRecordExporter.export(...)` per record, then completes the batch's code:
  `.succeed()` on `ExportResult.Success`, `.fail()` otherwise. The OTel processor
  thus gets real success/failure signalling.
- **`flush()`** — return a code the drain completes once the queue is empty.
- **`shutdown()`** — stop accepting, drain, complete outstanding codes, cancel the
  scope.

Determinism: `Clock`, `Random`, and the drain `CoroutineScope` are **required**
injected parameters (never a real dispatcher default), same discipline as the
logback appender. The synchronous `export` → `suspend` core boundary is crossed by
a single-writer channel drain — a scope-bound bridge, not dispatcher confinement.

### Catalog

Add to `gradle/libs.versions.toml`, pinned via `opentelemetry-bom`:
`opentelemetry-api` and `opentelemetry-sdk-logs` (holds `LogRecordExporter` /
`LogRecordData`). Both `compileOnly` in `:kuilt-otel-sdk`. `settings.gradle.kts`
gains `include(":kuilt-otel-sdk")`.

## Testing

- **Gate** (`:kuilt-otel-logging` `commonTest`) — a fake `TraceContextProvider`
  drives every branch: sampled (captures + stamps the right bytes), unsampled
  (drops), no-active-trace × `CAPTURE`/`DROP`, and null-provider (M1 parity). Under
  `StandardTestDispatcher(testScheduler)`, seeded `Random`, virtual `Clock`.
- **Bridge** (`:kuilt-otel-sdk` `jvmTest`) — a fake `LogRecordData` maps to the
  expected `LogRecord` (all fields incl. span context and synthesized `recordId`)
  and drains into a real `WarpLogRecordExporter`; assert the returned
  `CompletableResultCode` succeeds after the drain, and that `shutdown()` drains
  cleanly. The `OtelSdkTraceContextProvider` maps a fake span context to
  `ActiveTrace`. JVM/Android compile is a hard acceptance bar.

## Done when

Fake-provider gate tests assert drop-vs-capture and trace stamping; the OTel-SDK
provider + `LogRecordExporter` compile on JVM/Android; the full `./gradlew build`
is green; one ready PR closes #990.

## Non-goals / notes

- No OTel SDK on non-JVM targets — off the JVM the uniform M1 edge is the whole
  story; `:kuilt-otel-sdk` is empty there.
- The ingress bridge is never a prerequisite for kuilt logging — it exists only
  for apps that already run the OTel SDK.
- References policy: abstract use case only; no third-party citations.
