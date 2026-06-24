# Module kuilt-otel

**Offline-first OpenTelemetry for Kotlin Multiplatform.**

Record traces, metrics, and logs on any platform — JVM, Android, iOS, macOS, or
browser (wasm) — and have them automatically reconcile across all your users'
devices when connectivity returns, with no duplicates and no data loss.

## Why this is different

Standard OpenTelemetry exporters POST spans to a collector and fail if the
network is down. `kuilt-otel` turns that around: `export()` succeeds the moment
the data is **durably written to local storage**. Delivery to a backend happens
whenever the network allows, possibly hours later. Reconnecting devices exchange
only the *missing* deltas — not a full queue replay — so a brief reconnection
is kind to a flaky link.

The trick that makes this correct (not just buffered): every signal is stored as
a **CRDT**. Spans are an [ORSet] keyed by span id; sending the same span twice
is a set union and therefore idempotent. Metrics are mergeable counters. Logs
are an ordered append-only sequence. A resend **cannot double-count** — the
delta-temporality retry bug is structurally impossible.

## Quick start

```kotlin
@sample us.tractat.kuilt.otel.sampleWarpTelemetry
```

## What's here (slice A1 + A2 + WAL-JVM)

| Type | What it does |
|---|---|
| [DurableStore] | Write-through persistence interface. Plug in any WAL. |
| [InMemoryDurableStore] | Non-durable, test-safe store. |
| [FileChannelDurableStore] | Crash-safe JVM/Android WAL: temp-write + `force(true)` + atomic rename. |
| [WarpSpanExporter] | CRDT-backed span buffer (ORSet). Idempotent export + merge. |
| [WarpTelemetry] | Facade that composes all exporters under one surface. |
| [SpanRecord] | OTLP-shaped span data model. |
| [ExportResult] | Typed result of `export()` / `merge()`. |
| [BufferPolicy] | Bounded-buffer eviction strategy (always logs what it drops). |

## Deferred (follow-up PRs)

- **A3 MetricExporter** — cumulative `GCounter`/`PNCounter` + `HyperLogLog` for cardinality.
- **A4 LogRecordExporter** — `Rga<LogRecord>` ordered append-only log.
- **A5 WarpOtlpBridge** — drains converged CRDTs to a real OTLP endpoint.
- **Platform WALs** — NSFileManager (iOS/macOS, #802), IndexedDB (wasmJs, #801).

## Honest limits

- **Clock skew.** Timestamps are the producer's local clock. Long-offline devices
  may have skewed clocks; an HLC offset could be estimated on reconnect but is not
  yet implemented.
- **Late traces.** A trace straddling an offline and an online producer only
  assembles when the offline half syncs. Collectors accept late spans within a
  configurable assembly window.
- **Bounded buffer.** The span buffer is capped at [DEFAULT_MAX_SPANS]. Evicted
  spans are always logged with their trace/span ids — never silently dropped.
  Metrics are O(1) regardless of offline duration (a counter compresses
  losslessly).
- **Trust.** On-device telemetry and peer-relayed telemetry need auth/encryption
  to prevent forgery. Deferred to a later PR.
