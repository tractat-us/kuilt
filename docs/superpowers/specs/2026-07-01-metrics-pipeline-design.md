# Metrics collection pipeline — OTel-SDK ingress + metric tap

_Design for #1025 and #1026. Part of the log-capture epic #986 (M2). The metrics
twin of the just-shipped log pipeline
(`2026-07-01-log-capture-m2-otel-sdk-design.md`)._

> **Decisions locked (2026-07-01, @keddie on PR #1030).** Four questions this spec
> originally left open are now settled and folded in below: (1) `DOUBLE_SUM`
> precision uses a new **`GCounterDouble`** CRDT (#1035), **not** fixed-point
> scaling; (2) the tap uses **separate per-signal replicators muxed over one
> transport**, not a unified composite CRDT and not a new module; (3)
> `KuiltMetricExporter` requests **`DELTA`** temporality; (4) `WarpMetricExporter`
> gains the additive `snapshotAll()`/key-enumeration accessor the tap needs.
> **`GCounterDouble` (#1035) is a hard prerequisite** — the implementation plan
> (`docs/superpowers/plans/2026-07-01-metrics-pipeline.md`) sequences it first.

## What this adds

The device-side metric **buffer** already exists — `WarpMetricExporter`
accumulates counters, gauges, and distinct-count estimates into durable CRDTs and
survives a restart. But today an app has to feed it by hand
(`incrementSum`/`setGauge`/`addCardinality`) and there is no way to read those
numbers back off a phone or simulator. Logs already have both halves; metrics have
neither. This design fills the two gaps, mirroring the log pipeline stage for
stage:

1. **Ingress (#1025)** — let an app that already runs the OpenTelemetry SDK point
   its existing metric pipeline at kuilt's offline-first buffer, so its counters
   and gauges land in the durable CRDTs automatically, no hand-wiring.
2. **Tap (#1026)** — let a test or CI process reach onto a device and pull those
   numbers out over a `Seam`, the same way the log tap pulls logs.

Both new surfaces are purely additive: no app that isn't running the OTel SDK, and
no app that never installs the tap, sees any change.

## Current state

- `WarpMetricExporter` (`:kuilt-otel`, `commonMain`) — durable, lock-guarded,
  three kinds: **SUM** → `GCounter` (`incrementSum`), **GAUGE** →
  `LWWRegister<Double>` (`setGauge`), **CARDINALITY** → `HyperLogLog`
  (`addCardinality`). Has `recover()`, per-key `sumSnapshot`/`gaugeSnapshot`/
  `cardinalitySnapshot`, per-key merge methods, and `metricCount()`. **It has no
  way to enumerate its keys or take a whole-buffer snapshot** — see the additive
  accessor note under the tap.
- `:kuilt-otel-sdk` (`jvmAndAndroidMain`) — already holds `KuiltLogRecordExporter`
  (the non-blocking OTel-SDK log bridge) and `OtelSdkTraceContextProvider`. OTel
  artifacts are `compileOnly`. This is where the metric ingress bridge lands.
- `:kuilt-otel-tap` (`commonMain`, all targets) — `installLogTap` / `LogTapHost`
  and `LogTapClient.pull()`/`tail()` replicate an `Rga<LogRecord>` over one
  `Quilter`. This is the shape the metric tap mirrors.
- CRDT facts that shape the tap: `GCounter`, `LWWRegister<V>`, `HyperLogLog`, and
  `ORMap<K, S : Quilted<S>>` are all `Quilted` (mergeable), and `Quilter<S>`
  replicates one `Quilted` value over a `Seam`.

## Piece 1 — OTel-SDK metric ingress (`KuiltMetricExporter`, #1025)

A `io.opentelemetry.sdk.metrics.export.MetricExporter` in `:kuilt-otel-sdk` that
funnels `MetricData` into an existing `WarpMetricExporter`. Same non-blocking
enqueue-and-drain shape as `KuiltLogRecordExporter`: `export`/`flush`/`shutdown`
each return a `CompletableResultCode`; the raw batch is put on an unbounded
`Channel`, and a single scope-bound drain coroutine does the mapping (so the seeded
`Random` is only ever touched from that one coroutine) and completes each code.

### The OTLP → CRDT mapping

`MetricData` fans out to points; each point's `MetricDataType` decides the target
buffer kind. `MetricKey.attributes` comes from the point's OTLP attribute set;
`MetricKey.name` from the metric descriptor name.

| OTLP `MetricDataType` | Condition | Buffer call | CRDT | Notes |
|---|---|---|---|---|
| `LONG_SUM` | `isMonotonic == true` | `incrementSum(key, by = point.value)` | `GCounter` | Requires **delta** temporality (below). `by: Long`. |
| `DOUBLE_SUM` | `isMonotonic == true` | `incrementSumDouble(key, by = point.value)` | `GCounterDouble` | New CRDT (#1035); exact precision, **no** truncation/scaling. Requires **delta** temporality. `by: Double`. |
| `LONG_SUM` / `DOUBLE_SUM` | `isMonotonic == false` (UpDownCounter) | `setGauge(key, value, timestamp)` | `LWWRegister` | Can decrease → not a grow-only counter; treat as a current value. |
| `LONG_GAUGE` / `DOUBLE_GAUGE` | — | `setGauge(key, value, timestamp)` | `LWWRegister` | `timestamp = point.epochNanos`. |
| `HISTOGRAM` / `EXPONENTIAL_HISTOGRAM` / `SUMMARY` | — | **dropped**, logged at WARN once per name | — | No mergeable quantile CRDT yet (#798). |
| (no OTLP analog) | — | — | `HyperLogLog` | `CARDINALITY` is **not** fed by this bridge; it stays a manual `addCardinality` surface. |

**`GCounterDouble` (#1035) is a hard prerequisite.** `GCounter` is `Long`-only, so a
monotonic `DOUBLE_SUM` would have to be truncated or fixed-point-scaled — both lose
precision and drift. The locked decision (#1035) is a typed sibling
`GCounterDouble : Quilted<GCounterDouble>` backed by `Map<ReplicaId, Double>`,
identical CRDT shape (elementwise-max `piece`), with one wrinkle: `value` sums the
per-replica map in **canonical `ReplicaId` order** because floating-point `+` is not
associative, so a naïve `.sum()` over iteration order could yield a slightly
different `value` on different replicas from identical converged state. The plan
adds `GCounterDouble` and the `WarpMetricExporter` double-sum store (`incrementSumDouble`
+ `doubleSumSnapshot`, store key `otel.metrics.sums.double`) **before** the ingress
bridge. The `Long` `incrementSum` path is untouched.

### Temporality — the load-bearing decision

`incrementSum(key, by)` **adds** `by` to the counter. That is a **delta**
semantic: each call contributes an increment. OTLP sums, however, can be exported
as either `DELTA` (each point is the increment since the last collection) or
`CUMULATIVE` (each point is the running total). Feeding a **cumulative** point into
`incrementSum` would add the running total on every collection — catastrophic
double-counting.

**Resolution: the bridge requires delta temporality from the SDK.** The
`MetricExporter` SPI exposes
`getAggregationTemporality(InstrumentType): AggregationTemporality`; we override it
to return `AggregationTemporality.DELTA` for every instrument type. The SDK's
metric reader then does the cumulative→delta bookkeeping upstream, and each sum
point handed to us is exactly the increment to pass as `by`. This also happens to
be the offline-first-correct choice: a delta is inherently additive, so two
replicas' `GCounter`s merge to the true total with no coordination — the same
property the `GCounter` was chosen for. Gauges ignore temporality (a gauge point is
always the current value; `LWWRegister` takes value + timestamp regardless).

`double` monotonic sums are the one lossy corner: `GCounter` is `Long`, so a
fractional delta must be truncated, and the dropped fraction accumulates as drift
across collections. Flagged as an open question below — the honest v1 is
`by = point.value.toLong()` with a documented caveat.

### API sketch (`:kuilt-otel-sdk`, `jvmAndAndroidMain`)

```kotlin
public class KuiltMetricExporter(
    private val exporter: WarpMetricExporter,
    scope: CoroutineScope,
) : MetricExporter {

    private class Batch(val metrics: Collection<MetricData>, val code: CompletableResultCode)

    private val queue = Channel<Batch>(Channel.UNLIMITED)
    private val drain = scope.launch { for (b in queue) { /* map + apply, then succeed/fail */ } }

    override fun export(metrics: Collection<MetricData>): CompletableResultCode { /* enqueue */ }
    override fun flush(): CompletableResultCode { /* FIFO empty marker */ }
    override fun shutdown(): CompletableResultCode { /* close, drain, complete */ }

    // The load-bearing override: delta so each sum point maps onto incrementSum(by).
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        AggregationTemporality.DELTA
}
```

No `Random` is needed here (unlike the log bridge — metrics carry no synthesized
`recordId`); no `Clock` (points carry `epochNanos`). Determinism discipline is
identical: `scope` is a **required** injected parameter, never a real-dispatcher
default; the sync `export` → `suspend` buffer boundary is crossed by the
single-writer channel drain, not dispatcher confinement.

### Catalog

Add `opentelemetry-sdk-metrics` (holds `MetricExporter`, `MetricData`,
`MetricDataType`, `AggregationTemporality`, `InstrumentType`) to
`gradle/libs.versions.toml`, pinned via the existing `opentelemetry-bom`;
`compileOnly` in `:kuilt-otel-sdk`.

## Piece 2 — Metric tap (#1026)

### The unified-vs-separate decision (LOCKED)

**Decision (@keddie, PR #1030): separate per-signal replicators, mux the
transport.** Logs keep their append-`Rga` Quilter; metrics get their own
merge-map Quilter; both live in the existing `:kuilt-otel-tap` module; a harness
that wants both signals muxes them over one `Seam` (`MuxSeam`). **Not** a unified
composite CRDT, **not** a separate module.

The reasoning (why this was chosen):

- Logs are an **append log** (`Rga<LogRecord>`); metrics are **merge maps** (three
  `ORMap`s of per-key CRDTs). A `Quilter` replicates exactly one `Quilted` value,
  so one Quilter cannot carry both — they are genuinely different CRDT types. A
  forced union is a composite CRDT bundling an `Rga` and three `ORMap`s, which buys
  nothing and couples the log schema to the metric schema forever.
- Wire cohesion (one fabric, one port, one admission for a harness that wants
  everything) is real and worth having — but it belongs at the fabric layer, where
  kuilt already solves it: `MuxSeam` multiplexes several logical channels over one
  `Seam`. So a harness that wants both opens **one** `Seam` and runs the log
  Quilter and the metric Quilter over two `MuxSeam` channels.

Concretely: ship `MetricTapHost`/`MetricTapClient` next to the log tap, each
standalone over its own `Seam` (the simplest path, and all #1026 requires), and
add an optional `installSignalTap(...)` that muxes both channels over one `Seam`
for the "pull everything" case. Spans slot in later as a third channel with no
schema churn to the others.

### The replication surface — an additive read accessor on `WarpMetricExporter`

The buffer exposes per-key snapshots but **no key enumeration and no whole-buffer
snapshot**, so the tap host cannot today read "all the metrics" to offer them. The
minimal, non-breaking fix (a getter, not a redesign of the buffer's semantics) is
one composite snapshot type plus one accessor:

```kotlin
// New Quilted composite in :kuilt-otel — the single metric replication surface
// (metrics-internal, NOT the rejected logs+metrics union). Plain maps of per-kind
// CRDTs; piece() = key-union + per-value join. Simpler than ORMap — the tap needs
// no observed-remove semantics (eviction is a local cap, not a replicated delete).
@Serializable
public class MetricCatalog(
    public val sums: Map<MetricKey, GCounter> = emptyMap(),
    public val doubleSums: Map<MetricKey, GCounterDouble> = emptyMap(),
    public val gauges: Map<MetricKey, LWWRegister<Double>> = emptyMap(),
    public val cardinalities: Map<MetricKey, HyperLogLog> = emptyMap(),
) : Quilted<MetricCatalog> {
    override fun piece(other: MetricCatalog): MetricCatalog =
        MetricCatalog(
            sums = mergeMaps(sums, other.sums),               // key-union, GCounter.piece
            doubleSums = mergeMaps(doubleSums, other.doubleSums),
            gauges = mergeMaps(gauges, other.gauges),
            cardinalities = mergeMaps(cardinalities, other.cardinalities),
        )
}

// Additive read accessor on WarpMetricExporter (lock-guarded, no semantic change):
public fun snapshotAll(): MetricCatalog
```

Each map's values are `Quilted`, so `mergeMaps` unions keys and joins matching
values by the value's own CRDT lattice — the composite converges by construction.
This is the metric analogue of `WarpLogRecordExporter.snapshot(): Rga<LogRecord>`,
carrying all four sub-stores in one replicated value.

### API sketch (`:kuilt-otel-tap`, `commonMain`)

```kotlin
public class MetricTapHost internal constructor(
    seam: Seam, exporter: WarpMetricExporter, parentScope: CoroutineScope, config: MetricTapConfig,
) : ScopedCloseable(parentScope) {
    // Quilter<MetricCatalog> seeded with exporter.snapshotAll(); offerLoop() re-offers on syncInterval.
    public fun sync()          // re-read snapshotAll(), apply as a local mutation if changed
    public val selfId: PeerId
}

public suspend fun installMetricTap(
    loom: Loom, exporter: WarpMetricExporter, scope: CoroutineScope,
    config: MetricTapConfig = MetricTapConfig(),
): MetricTapHost   // hosts on loom, loopback-bound by default — opt-in, no-op until called

public class MetricTapClient(
    seam: Seam, parentScope: CoroutineScope, config: MetricTapConfig = MetricTapConfig(),
) : ScopedCloseable(parentScope) {
    public suspend fun pull(): MetricSnapshot          // one-shot converged snapshot (settled)
    public fun tail(): Flow<MetricSnapshot>            // whole-snapshot on each change (see below)
}

// The reconstructed, plain-value result — CRDTs collapsed to their reported values.
public data class MetricSnapshot(
    val sums: Map<MetricKey, Long>,          // GCounter.value
    val doubleSums: Map<MetricKey, Double>,  // GCounterDouble.value (canonical-order sum)
    val gauges: Map<MetricKey, Double>,      // LWWRegister.value
    val cardinalities: Map<MetricKey, Long>, // HyperLogLog.estimate()
)
```

`pull()` mirrors the log tap: await a remote peer, wait for the host's
first-contact full-state to merge, `settle()` on a quiet window, then collapse the
`MetricCatalog` to a `MetricSnapshot` (`GCounter.value`, `LWWRegister.value`,
`HyperLogLog.estimate()`). Bounded by `pullTimeout`; throws rather than returning a
partial.

### `tail()` — yes, but whole-snapshot, not per-record

For an append log, `tail()` naturally means "each new record once" — the log tap
dedups by `recordId`. Metrics have no per-record identity: a `GCounter` grows, a
gauge is overwritten, an HLL absorbs. The meaningful live view is **the converged
snapshot re-emitted whenever it changes** — i.e. `tail(): Flow<MetricSnapshot>`
collecting the Quilter's `state` `StateFlow`, mapping to `MetricSnapshot`, and
emitting on distinct values. It fits, but its contract differs from the log tap's
and the KDoc must say so. `pull()` is the primary, always-correct surface; `tail()`
is the convenience for a live dashboard.

### Config

`MetricTapConfig` mirrors `LogTapConfig` field-for-field (`pattern` default
`Pattern("kuilt-metric-tap")`, `syncInterval`, `pullTimeout`, `pullSettleStep`,
`quilterConfig`). Same conservative, opt-in, loopback-by-default safety posture.

## Module / file layout

| Module | New files |
|---|---|
| `:kuilt-crdt` (`commonMain`) | `GCounterDouble.kt` (#1035, the hard prerequisite) + zoo docs |
| `:kuilt-otel` (`commonMain`) | `MetricCatalog.kt` (Quilted composite + serializer); `incrementSumDouble`/`doubleSumSnapshot`/`snapshotAll()` added to `WarpMetricExporter.kt` |
| `:kuilt-otel-sdk` (`jvmAndAndroidMain`) | `KuiltMetricExporter.kt` |
| `:kuilt-otel-tap` (`commonMain`) | `MetricTapHost.kt`, `MetricTapClient.kt`, `MetricTapWire.kt`, `MetricTapConfig.kt`; optional `SignalTap.kt` (`installSignalTap` over `MuxSeam`) |
| `gradle/libs.versions.toml` | `opentelemetry-sdk-metrics` (compileOnly, via `otel` version ref) |

No new module and no `settings.gradle.kts` change — everything lands in modules
that already exist.

## Testing

- **Ingress (`:kuilt-otel-sdk`, `jvmTest`)** — fake `MetricData` for each row of
  the mapping table drains into a **real** `WarpMetricExporter`: a monotonic
  `LONG_SUM` delta point → `incrementSum` (assert `sumValue`); a monotonic
  `DOUBLE_SUM` → `incrementSumDouble` (assert `doubleSumValue`, exact); a
  `DOUBLE_GAUGE` → `setGauge` (assert `gaugeValue`); a non-monotonic sum → gauge; a
  `HISTOGRAM` → dropped (buffer untouched, WARN logged). Assert
  `getAggregationTemporality` returns `DELTA`. Assert the returned
  `CompletableResultCode` succeeds after the drain and `shutdown()` drains cleanly.
  `StandardTestDispatcher(testScheduler)`, injected `scope`. JVM/Android compile is
  a hard acceptance bar.
- **Tap (`:kuilt-otel-tap`, `commonTest`)** — conformance-style: seed a
  `WarpMetricExporter` with a mix of sums/gauges/cardinalities, `installMetricTap`
  over an in-memory/loopback `Seam`, and assert `MetricTapClient.pull()`
  reconstructs every value (sums exact, gauges exact, cardinality within HLL
  tolerance). A `tail()` test asserts a post-connect `incrementSum` produces a new
  emitted `MetricSnapshot`. A loopback-WebSocket variant (`jvmTest`) for simulator
  realism, matching the log tap's test layout.
- **`MetricCatalog` (`:kuilt-otel`, `commonTest`)** — CRDT laws: merge
  commutativity/idempotence, key-union, per-key value join.

## Resolved decisions & remaining open questions

**Resolved (@keddie, PR #1030 — folded into this spec):**

1. ~~`double` monotonic-sum truncation~~ → **`GCounterDouble` (#1035)**, a typed
   double counter; no truncation, no fixed-point scaling. `LONG_SUM` stays on
   `GCounter`.
2. ~~Unified vs separate tap~~ → **separate per-signal replicators, mux the
   transport** (`MuxSeam`); both in `:kuilt-otel-tap`; no unified composite CRDT,
   no new module.
3. ~~Temporality~~ → `KuiltMetricExporter` returns **`DELTA`** for every instrument
   type.
4. ~~Additive accessor~~ → add `snapshotAll(): MetricCatalog` (+ `incrementSumDouble`
   / `doubleSumSnapshot`) to `WarpMetricExporter` — a read-only getter, no semantic
   change to the buffer.

**Still open (defaults chosen; call out if you disagree):**

- **Cumulative-point defensiveness.** `DELTA` is requested from the SDK; should the
  bridge additionally **assert/reject** a point that arrives `CUMULATIVE` anyway
  (defensive, guards a misconfigured reader), or trust the SDK honoured the
  temporality it was asked for (simpler)? Plan default: trust + a one-line WARN if a
  `CUMULATIVE` sum is ever seen, never `incrementSum` it.
- **`tail()` semantics.** Whole-snapshot-on-change (plan default) vs. per-key change
  events (`Flow<MetricChange>`) vs. omit `tail()` for v1 (the issue says "if it
  fits"). Plan default: ship `tail(): Flow<MetricSnapshot>`.

## Done when

- `GCounterDouble` (#1035) passes CRDT laws with canonical-order `value`
  determinism across shuffled merges; zoo docs added.
- `WarpMetricExporter` gains `incrementSumDouble`/`doubleSumSnapshot`/`snapshotAll()`
  with the `Long` sum path untouched.
- `KuiltMetricExporter` maps fake `MetricData` (long-sum/double-sum/gauge/
  dropped-histogram) into a real `WarpMetricExporter`, returns `DELTA` temporality,
  completes its `CompletableResultCode` from the drain, and compiles on JVM/Android.
- `MetricTapClient.pull()` reconstructs a device's sums/double-sums/gauges/
  cardinalities over an in-memory/loopback `Seam`; conformance-style test green.
- Full `./gradlew build` green; PRs: `GCounterDouble` (#1035), ingress (#1025), tap
  (#1026).

## Non-goals / notes

- Histograms/summaries are dropped until a mergeable quantile CRDT exists (#798).
- `CARDINALITY` has no OTLP analog — it stays a manual `addCardinality` surface;
  the ingress bridge never feeds it.
- No OTel SDK off the JVM; `:kuilt-otel-sdk` is empty on native/wasm, so the metric
  ingress bridge is JVM/Android-only. The tap is all-targets (pure kuilt CRDTs).
- References policy: abstract use cases only; no third-party citations.
