# Log capture & extraction — design

> Epic spec. Captures an application's existing `kotlin-logging` output into
> kuilt's offline-first telemetry buffer, and lets a test or CI process **join the
> device as a kuilt peer and pull the logs out automatically** — the answer to
> "I can't get at the logs on an iOS simulator or a real phone while debugging."

## What it is, plainly

An app keeps logging exactly the way it already does — `kotlin-logging`, no code
changes at the call sites. Those log lines are captured into a durable on-device
buffer that survives going offline. When you want them, a test or CI process
**connects to the running app as a peer and pulls the logs out** — on an iOS
simulator or a real phone, where logs are otherwise hard to reach. An optional
mode only captures logs that belong to a *sampled* trace, so a production-style
sampling decision can keep the volume down.

## Why kuilt is the right substrate

The device already buffers logs as a CRDT (`WarpLogRecordExporter`, an
`Rga<LogRecord>` in `:kuilt-otel`). Extraction is therefore not a new transport
problem — it is *"a peer joins the device's Room and the log CRDT replicates over
whatever fabric reaches it"*: loopback WebSocket for a simulator/CI, mDNS+WebSocket
or Apple Multipeer for a real phone. kuilt already ships every one of those
fabrics, and replication is `:kuilt-quilter` anti-entropy over a `Seam` — idempotent
and order-preserving by construction, so a reconnect never double-counts.

## What already exists (the observability work)

- `:kuilt-otel` — `WarpTelemetry` facade owning `spans` / `metrics` / `logs`
  exporters; each CRDT-backed and persisted to a per-platform durable WAL.
- `WarpLogRecordExporter` + `LogRecord` — the log buffer. `LogRecord` is
  OTLP-shaped, already carries optional `traceId`/`spanId`, is idempotent by
  `recordId`, and is stored as an `Rga`.
- `WarpOtlpBridge` + `OtlpEdge` — egress seam to a real OTLP backend (currently
  wires **spans only**; logs/metrics deferred — A3/A4). No concrete `OtlpEdge`
  implementation exists yet (interface + test double only).

This epic adds the two missing ends: **capture** (get app logs into
`WarpLogRecordExporter`) and **extraction** (get them off the device to a test
harness).

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Receiving end of extracted logs | **Both, in sequence** — kuilt-native log-tap peer *first* (primary), standard OTLP collector *secondary*. |
| Capture mechanism | **Approach A — two-surface capture**: oshai `Appender` (Native/JS/wasm) + SLF4J/logback appender (JVM/Android). |
| Trace source for the sampled-gate | **OTel-SDK adapter now** (JVM/Android), behind a `TraceContextProvider` interface so a **kuilt-native KMP provider** can drop in later. |
| Who pulls the logs | **In-test kuilt peer** (assert / dump-on-failure) **and CI / simulator-harness auto-collect**. (Standalone dev CLI deprioritized.) |
| Production safety | **Opt-in, off by default, loopback-bound by default.** kuilt stays policy-free; the app decides when to enable. Join-token admission is a follow-on. |
| First milestone | **Capture + sampling-gate + extraction**, end-to-end (extraction pulled forward). |

## Module layout

| Module | Targets | Role |
|---|---|---|
| `:kuilt-otel-logging` | all | **Capture.** Core event→`LogRecord` mapping, `CaptureConfig`, the `TraceContextProvider` interface, and the sampling-gate. Two surfaces (Approach A): an oshai `Appender` in `commonMain` (Native/JS/wasm), and an SLF4J/logback appender in `jvmAndAndroidMain` (the real JVM/Android capture point). Depends on `:kuilt-otel` + kotlin-logging. |
| `:kuilt-otel-logging-otel` | JVM, Android | The OTel-SDK `TraceContextProvider` (reads `io.opentelemetry.api.trace.Span.current().spanContext`). Isolates the opt-in `opentelemetry-api` dependency so a capture-only consumer never pulls it in. |
| `:kuilt-otel-tap` | all | **The log-tap peer.** Host side opens an opt-in, loopback-default log Room; client side joins and pulls a snapshot or live-tails. Replicates the log `Rga` over a `Seam` via `:kuilt-quilter`. Fabric-agnostic — the milestone wires loopback WebSocket (`:kuilt-websocket`); mDNS/Multipeer is a config swap (follow-on). Depends on `:kuilt-otel` + `:kuilt-quilter` + `:kuilt-core`. |
| `:kuilt-otel-otlp` *(follow-on)* | all | Concrete Ktor OTLP/HTTP edge → a standard OpenTelemetry Collector. The secondary egress path. |

`:kuilt-otel` already uses `kotlin-logging` for its *own* internal logging;
capture of *application* logs is a distinct concern and a separate module, which
also avoids any "capture our own captured logs" feedback risk.

## Milestone 1 — capture + sampling-gate + extraction (the useful unit)

### 1. Capture core + non-JVM appender (`:kuilt-otel-logging`)
- `LogCapture` maps a normalized event to a `LogRecord`:
  - level → OTLP `severityNumber` + `severityText`
  - message → `body`
  - logger name + key/value pairs (MDC-equivalent) → `attributes`
  - fresh 8-byte `recordId` per record; `timestampEpochNanos` / `observedEpochNanos`
    from the injected `Clock` (time is a dependency — never the wall clock directly).
  - calls `exporter.export(record)`.
- `CaptureConfig`: minimum level, attribute mapping, and the no-active-trace
  policy (see §3).
- oshai `Appender` implementation registered via `KotlinLoggingConfiguration`
  — covers Native/JS/wasm (and JVM only when an app deliberately opts out of SLF4J).
- One-call install: `installLogCapture(exporter, config)`.

### 2. JVM/Android capture surface (`jvmAndAndroidMain`)
- On JVM/Android `kotlin-logging` is an SLF4J facade; capture must hook the
  SLF4J/logback layer, not the oshai appender. Provide an SLF4J/logback appender
  that feeds the same `LogCapture` core. (Capturing at SLF4J also incidentally
  captures non-kotlin-logging SLF4J output — a documented bonus, not a goal.)

### 3. Sampling-gate (`TraceContextProvider` + `:kuilt-otel-logging-otel`)
- `TraceContextProvider` (in `:kuilt-otel-logging`):
  ```kotlin
  public interface TraceContextProvider {
      /** Active trace context at the call site, or null if no span is active. */
      public fun current(): ActiveTrace?
  }
  public data class ActiveTrace(
      val traceId: ByteString,   // 16 bytes
      val spanId: ByteString,    // 8 bytes
      val sampled: Boolean,
  )
  ```
- The appender, when a provider is configured, consults it per record:
  - no active trace → behavior per `CaptureConfig.untracedPolicy` (`CAPTURE` |
    `DROP`; default `CAPTURE` so always-on capture is the zero-config behavior).
  - active + `sampled` → capture, stamping `traceId`/`spanId` onto the `LogRecord`
    (free trace-correlation, reusing existing fields).
  - active + `!sampled` → **drop** before `export`.
- `:kuilt-otel-logging-otel` provides `OtelSdkTraceContextProvider` reading
  `Span.current().spanContext` (JVM/Android; `opentelemetry-api` dependency).
- With no provider configured, capture is always-on — the two app-facing knobs
  are "install capture" and "install capture + a provider."

### 4–6. The log-tap peer (`:kuilt-otel-tap`)
- **Host (on the device, opt-in):** `installLogTap(loom, exporter, config)` — a
  no-op unless called; binds loopback by default. Hosts a kuilt Room and
  continuously offers the exporter's `Rga<LogRecord>` for replication via
  `:kuilt-quilter`.
- **Client (in the test/harness):** `LogTapClient(seam)` exposing
  - `pull(): List<LogRecord>` — one anti-entropy round, then `toList()`.
  - `tail(): Flow<LogRecord>` — live replication for an interactive run.
- **Test/CI helpers:**
  - in-test peer: assert-on-logs and dump-on-failure utilities (test-support flavor).
  - sim/CI harness: a per-device artifact writer (NDJSON of `LogRecord`s).
- **Rendezvous:** device hosts `Rendezvous.New(pattern)` on a loopback WebSocket
  `Loom` at a debug port; the harness assigns one port per simulator (host-shared
  loopback means N sims need N ports) and the client joins
  `Rendezvous.Existing(tag)`.

### Milestone-1 done when
A JVM app's `kotlin-logging` output is captured into `WarpLogRecordExporter`; an
in-test peer over a loopback-WebSocket `Loom` pair `pull()`s and reconstructs the
host's log sequence in order with no duplicates; the OTel-SDK sampled-gate drops
unsampled-trace records and stamps `traceId`/`spanId` on sampled ones; full build
green across all targets.

## Milestone 2 — secondary egress & reach (follow-on sub-issues)

7. Wire logs (and metrics) through `WarpOtlpBridge` by digest — the deferred
   A3/A4 work; extend `OtlpEdge.send` to accept log records.
8. Concrete Ktor OTLP/HTTP edge (`:kuilt-otel-otlp`) → standard OTel Collector.
9. Real-phone reach: mDNS + Multipeer fabrics for the tap; **join-token
   admission control** so extraction is safe over LAN; a kuilt-native
   coroutine-scoped `TraceContextProvider` so the sampled-gate works on wasm/iOS.
10. JVM/Android: optional log4j2 appender variant if an app doesn't use logback.

## Key contracts

- **Capture** is a pure mapping plus an `export` call; the `Clock` and any RNG for
  `recordId` are injected dependencies (test determinism, repo policy).
- **Sampling-gate** decides *before* `export`; an absent provider means always-on.
  Trace stamping reuses `LogRecord.traceId`/`spanId` — no new fields.
- **Extraction** replicates the existing `Rga<LogRecord>` over a `Seam` via
  Quilter; `pull()` is idempotent and order-preserving by CRDT construction.
- **Safety:** the tap is off until `installLogTap` is called and binds loopback by
  default; kuilt ships no implicit always-on log Room.

## Testing

- **Capture:** per-platform appender wired to an `InMemoryDurableStore`; assert
  the resulting `LogRecord` shape (severity/body/attributes/timestamps).
- **Sampling-gate:** a fake `TraceContextProvider` exercising sampled / unsampled
  / no-active-trace; assert drop vs. capture and `traceId`/`spanId` stamping.
- **Tap:** two exporters over an in-memory `Loom` pair (the conformance harness);
  assert the client `pull()` reconstructs the host's log sequence (order +
  no-duplicate); a loopback-WebSocket integration test for simulator realism.
- Coroutine determinism per repo policy: injected `StandardTestDispatcher`,
  bounded `settle()`/`await*`, no `advanceUntilIdle()`, scope-owning types correct
  under a multi-threaded dispatcher (explicit locks/atomics, not
  `limitedParallelism(1)` confinement).

## Honest limits

- **JVM capture is SLF4J-shaped.** Apps that route `kotlin-logging` through a
  non-logback SLF4J backend need the log4j2 variant (M2 #10) or the bonus capture
  of all SLF4J output may surprise.
- **Per-sim ports.** iOS simulators share the host's loopback, so multi-sim
  extraction needs one port per sim; the harness owns that assignment.
- **Sampled-gate is JVM/Android-first.** wasm/iOS get always-on capture until the
  kuilt-native `TraceContextProvider` lands (M2 #9).
- **Clock skew.** Inherited from `LogRecord`: timestamps are the producer's local
  clock; cross-producer ordering is RGA Lamport-tiebroken, not wall-clock exact.

## References policy

Abstract use cases only; no third-party issue/PR citations; no references to other
`tractat-us/*` repos. The iOS-debugging motivation is described generically.
