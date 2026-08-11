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

## When an app suddenly has a lot to say

Saving one log line used to cost a full round of bookkeeping and two writes to the
device's storage — every single time. Hand over a handful of lines at once and that
cost is paid once for the lot, so an app that gets chatty for a moment doesn't have
to pay for each line separately.

Nothing is delayed to make that happen. A batch is only ever what the caller already
had in hand, and it is on disk before the call returns — a lone line on a quiet app
is written just as promptly as before.

[WarpLogRecordExporter.export] takes either a single record or a list of them. The
list overload admits the whole run to the [Rga], encodes the active segment once and
writes it once, rather than repeating that per record; a run too large for one
segment is split across turns, so a failure means "stop", not "none of it landed"
(#2194). Dedup and the buffer cap remain per-record decisions. Its KDoc carries a
worked example.

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
| [WarpTelemetry.clear] | The supported reset: empties every signal's buffer and its persisted state on a **live** instance — no restart, no per-platform directory delete. Logs and spans suppress what they drop, so a peer cannot re-merge it back; metrics forget only locally (a monotonic join has no merge-safe forget). |
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
| [BufferPolicy] | Span/log bounded-buffer eviction strategy (spans log each drop; log records count them on [ExporterHealth]). |
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
  Eviction is never silent, but it is reported differently by rate: spans and metrics
  log each drop; log records are counted exactly on [ExporterHealth.dropped] /
  [ExporterHealth.refused] with one rate-limited summary line, because at
  [DEFAULT_MAX_LOG_RECORDS] every exported record evicts one and a per-record line
  would narrate the cap doing its job. Counters are O(1) regardless of offline
  duration (a counter compresses losslessly).
- **For logs, the total settles on the export path and still grows on the gossip path.**
  [WarpLogRecordExporter] persists its op-log in segments of [DEFAULT_LOG_SEGMENT_OPS]
  operations, so one export rewrites one segment rather than the whole log; it then
  periodically drops everything outside the retained window from memory and deletes any
  sealed segment the resulting suppression state fully covers. A device fed only by its own
  [WarpLogRecordExporter.export] calls therefore holds O([DEFAULT_MAX_LOG_RECORDS]) ops and
  a flat number of keys however long it runs, and startup reads that many keys rather than
  one per segment ever written (#2127).
  **That is one arm of the claim, not the whole of it — and the other arm costs whole
  records, not a small note.** A record that arrived from a *peer* through
  [WarpLogRecordExporter.merge] cannot fold into the per-author floor — raising another
  author's floor would annihilate records it has not written yet — so each one windowed away
  is suppressed by an explicit compaction record instead. *In memory* that is one bodiless
  `(id -> id)` pair per element. *On disk it is not bodiless at all:* nothing prunes a
  compaction record, so a segment carrying one is pinned, and a pinned segment is retained
  **entire** — every record it holds, bodies included, permanently. Two shapes reach it. A
  sealed segment that happened to be active when a pass minted one keeps its full
  [DEFAULT_LOG_SEGMENT_OPS] operations (~123 KB at the defaults) forever. And
  [WarpLogRecordExporter.merge] persists the peer's op-log verbatim under a key of its own,
  so merging from a peer that has itself windowed a foreign author's records — which is any
  peer in a steady-state mesh — pins that peer's whole log: megabytes per merge at the
  default [DEFAULT_MAX_LOG_RECORDS]. So the gossip path is growth, not a bound, and a replica
  that gossips accumulates it for as long as the process lives. Bounding it needs the same
  causal-stability argument tombstone collection does; consolidation — rewriting a pinned
  segment's compaction record forward so the segment can go — was considered and declined,
  and nothing implements it. The span and metric exporters still rewrite their whole state
  per export.
- **Cardinality estimation.** HyperLogLog gives ~0.81% relative error at default
  precision (`p=14`). Small cardinalities (< ~5 distinct elements) have higher
  relative error; the linear-counting correction reduces but does not eliminate this.
  Precision is fixed per metric; changing it after production data exists is
  wire-breaking.
- **Trust.** On-device telemetry and peer-relayed telemetry need auth/encryption
  to prevent forgery. Deferred to a later PR.
