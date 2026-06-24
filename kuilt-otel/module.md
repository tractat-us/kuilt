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

## What's here (slices A1–A5 + WAL-JVM + WAL-iOS + WAL-wasm)

| Type | What it does |
|---|---|
| [DurableStore] | Write-through persistence interface. Plug in any WAL. |
| [InMemoryDurableStore] | Non-durable, test-safe store. |
| [FileChannelDurableStore] | Crash-safe JVM/Android WAL: temp-write + `force(true)` + atomic rename. |
| [WarpSpanExporter] | CRDT-backed span buffer (ORSet). Idempotent export + merge. |
| [WarpMetricExporter] | CRDT-backed metric buffer: sums (GCounter), gauges (LWWRegister), cardinality (HyperLogLog). |
| [WarpLogRecordExporter] | CRDT-backed log buffer (Rga). Ordered, idempotent export + merge. |
| [WarpTelemetry] | Facade that composes all exporters under one surface. |
| [WarpOtlpBridge] | Drains converged CRDTs to an OTLP edge, reconciling by digest. |
| [OtlpEdge] | Interface your backend implements to receive spans. |
| [SpanDigest] | Compact set of span ids the edge already holds; drives delta computation. |
| [DrainResult] | Typed result of [WarpOtlpBridge.drain]: spans sent or failure. |
| [SpanRecord] | OTLP-shaped span data model. |
| [LogRecord] | OTLP-shaped log-record data model with optional trace correlation. |
| [MetricKey] | Identity of one metric time series (name + kind + label set). |
| [MetricKind] | SUM / GAUGE / CARDINALITY. |
| [ExportResult] | Typed result of span/log `export()` / `merge()`. |
| [MetricExportResult] | Typed result of metric mutations. |
| [BufferPolicy] | Span/log bounded-buffer eviction strategy (always logs what it drops). |
| [MetricBufferPolicy] | Metric bounded-buffer eviction strategy (always logs what it drops). |

## Deferred (follow-up PRs)

- **Histogram metrics** — DDSketch or t-digest for merge-able quantile estimates.
- **Platform WALs** — NSFileManager (iOS/macOS, #802), IndexedDB (wasmJs, #801).

## Honest limits

- **Clock skew.** Timestamps are the producer's local clock. Long-offline devices
  may have skewed clocks; an HLC offset could be estimated on reconnect but is not
  yet implemented. Gauge values from a device with a slow clock may be silently
  overwritten by a peer with a faster clock even if the slow-clock value is "newer"
  in wall time.
- **Late traces.** A trace straddling an offline and an online producer only
  assembles when the offline half syncs. Collectors accept late spans within a
  configurable assembly window.
- **Bounded buffer.** The span and log buffers are capped ([DEFAULT_MAX_SPANS],
  [DEFAULT_MAX_LOG_RECORDS]); the metric buffer at [DEFAULT_MAX_METRICS] distinct series.
  Evicted entries are always logged — never silently dropped. Counters are O(1)
  regardless of offline duration (a counter compresses losslessly).
- **Cardinality estimation.** HyperLogLog gives ~0.81% relative error at default
  precision (`p=14`). Small cardinalities (< ~5 distinct elements) have higher
  relative error; the linear-counting correction reduces but does not eliminate this.
  Precision is fixed per metric; changing it after production data exists is
  wire-breaking.
- **Trust.** On-device telemetry and peer-relayed telemetry need auth/encryption
  to prevent forgery. Deferred to a later PR.
