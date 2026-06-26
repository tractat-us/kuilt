# Spec B — Native warp metrics → OTel (Phase-E follow-up)

**Status:** design approved (brainstorm 2026-06-25). Follow-up to epic [#854](https://github.com/tractat-us/kuilt/issues/854) (query planning). Pairs with Spec A (coordination-round consolidation), which is born instrumented against this.

## Motivation

warp produces numbers worth watching — how many tasks ran, how many were
duplicate executions absorbed during failover, how often peers failed over, how
much data a coordinated step commits over, and (with Spec A) how many
coordination rounds a plan costs. Phase A (`:kuilt-otel`) already shipped the
*export* half: an offline-first, CRDT-backed `WarpMetricExporter` whose three
metric kinds are themselves CRDTs (`SUM`→`GCounter`, `GAUGE`→`LWWRegister<Double>`,
`CARDINALITY`→`HyperLogLog`). What's missing is purely **instrumentation**: warp
emits nothing into it today.

The design-doc promise (`docs/warp-planning.md`) is "the same gossiped sketches
are also your metrics and telemetry — paid for once, spent twice." This spec
cashes that in.

## Design in one line

warp holds its operational metrics as **mergeable CRDTs**; a one-file bridge
**merges** them into the app's existing `WarpMetricExporter`. No SPI, no adapter
object, no push path — because the exporter's series are the *same CRDT types*
warp already holds, a snapshot is a CRDT merge, and merge is idempotent.

```
:kuilt-warp                          :kuilt-warp-otel (NEW)               :kuilt-otel (Phase A, unchanged)
┌──────────────────────────┐         ┌────────────────────────────┐       ┌─────────────────────────┐
│ WarpNode.executions  : GCounter ──►│ fun WarpMetricExporter      │       │ WarpMetricExporter      │
│ WarpNode.duplicates  : GCounter ──►│   .recordWarp(node)         │─merge─►│  sums:  Map<Key,GCounter>│
│ WarpNode.failovers   : GCounter ──►│ fun WarpMetricExporter      │ Sum/   │  cards: Map<Key,HLL>     │
│ WarpStats (HLL, E-4) ───────────  ►│   .recordPlan(draft, stats) │ Card/  │  gauges:Map<Key,LWWReg> │
│ Draft.coordinationCost() ───────  ►│                            │ Gauge  │  (app already owns this) │
└──────────────────────────┘         └────────────────────────────┘       └─────────────────────────┘
       holds metrics as CRDTs            the only both-aware unit             stays warp-agnostic
```

## What warp gains (small, and worth having anyway)

Three `GCounter` fields on `WarpNode`, incremented at the sites where the events
already happen, exposed as public read-only snapshots:

- `executions` — incremented in `doExecute` (one per successful task execution).
- `duplicates` — incremented when the `Results` ORMap backstop absorbs a
  duplicate execution (this is the dup-rate the sims currently measure by hand;
  giving it a home retires that ad-hoc measurement).
- `failovers` — incremented on `PartitionEvent.PeerUnresponsive`/`PeerLost`
  driven ring rebuilds (one per re-home).

These are `GCounter`s (not plain `Long`s) so they are mergeable and gossip for
free like the rest of warp's state; they need the same lock discipline as the
existing counters in `WarpNode` (guarded by the existing `reentrantLock`).

`WarpStats` gains one accessor so the bridge can merge the raw sketch (it exposes
only `estimatedCardinality` today):

```kotlin
public fun sketches(): Map<OpId, HyperLogLog>   // or sketch(source): HyperLogLog?
```

No SPI, no interface, no `Noop` default, no new coroutines in warp.

## The bridge (`:kuilt-warp-otel`, ~1 file)

A new module depending on both `:kuilt-warp` and `:kuilt-otel` — the only unit
aware of both, mirroring `:kuilt-mdns → :kuilt-websocket`. Both libraries stay
standalone. Contents are extension functions on the *existing* exporter:

```kotlin
public suspend fun WarpMetricExporter.recordWarp(node: WarpNode, attributes: Map<String,String> = emptyMap())
public suspend fun WarpMetricExporter.recordStats(stats: WarpStats, sourceAttr: String = "source")
public suspend fun WarpMetricExporter.recordPlan(draft: Draft<*>, stats: WarpStats, attributes: Map<String,String> = emptyMap())
```

Each is a direct CRDT merge / value set into the exporter:

| Metric name | Kind | Source | Exporter call |
|---|---|---|---|
| `warp.tasks.executed` | SUM | `node.executions` | `mergeSum(key, node.executions)` |
| `warp.tasks.duplicate` | SUM | `node.duplicates` | `mergeSum(key, node.duplicates)` |
| `warp.failover.count` | SUM | `node.failovers` | `mergeSum(key, node.failovers)` |
| `warp.task.cardinality` | CARDINALITY | `WarpStats` per-source HLL | `mergeCardinality(key{source}, sketch)` |
| `warp.coordination.volume` | GAUGE | `Draft.coordinationCost(stats).coordinatedVolume` | `setGauge(key, value)` |
| `warp.coordination.rounds` | GAUGE | `…rounds` (→ DAG-depth once Spec A lands) | `setGauge(key, value)` |

`plan` attribute (`"unplanned"` / `"planned"`) differentiates the cost series so a
dashboard shows the planner's win directly (the E-3 ≈95%-volume-cut number,
live).

## The load-bearing property: idempotent snapshot

warp's `executions` and the exporter's `warp.tasks.executed` series are *the same
CRDT* (`GCounter`); `WarpStats`' sketch and the `CARDINALITY` series are *the same
CRDT* (`HyperLogLog`). So `recordWarp` is a **merge** (`GCounter` element-wise
max), which is idempotent — calling it on any cadence (a timer, after each plan,
on every roster change) **never double-counts**. The exporter's own KDoc already
guarantees this for `mergeSum`. The app owns cadence; the bridge owns no scheduler
and injects no dispatcher in v1.

`GAUGE`s are last-writer-wins; `setGauge` needs a timestamp, which is a tuning
input the caller supplies (consistent with the exporter's existing gauge API).

## Module & dependency direction

- New module `:kuilt-warp-otel`, targets = the `kmp-library` default set
  (matches both deps). `id("kuilt.kmp-library")`, `explicitApi`.
- Depends `implementation(project(":kuilt-warp"))` + `implementation(project(":kuilt-otel"))`.
- **Out of the BOM?** No — it is a normal published module; add it to `:kuilt-bom`
  alongside the others (unlike `:kuilt-warp` historically; warp is now in the BOM).
- Neither `:kuilt-warp` nor `:kuilt-otel` gains a dependency on the other.

## Testing

Pure-value tests in `:kuilt-warp-otel` (no coroutines beyond `runTest` for the
`suspend` exporter calls):

- counters surface under the right `MetricKey` with the right value;
- **idempotence**: `recordWarp` twice == once (the merge law — the spec's keystone);
- `WarpStats` cardinality lands as a `CARDINALITY` series with per-source
  attributes; merging two peers' stats then recording matches recording each then
  merging the exporter (CRDT commutativity end-to-end);
- a planned vs. unplanned `Draft` produce the expected `coordination.volume`
  gauge values (re-uses the E-3 representative query; pins the ≈95% cut as a
  metric, not just a test).

## Out of scope (deferred, not designed away)

- **Point-event metrics** needing a push path (per-round latency, failover
  duration as a distribution/histogram). If a real need appears, add a thin
  `WarpMetrics` SPI then — the pull/merge bridge does not preclude it. YAGNI for v1.
- **Bridge-owned periodic snapshotting** (a `launchSnapshotting(scope, interval)`
  loop). v1 is one-shot `recordWarp(...)`; if added later it takes a **required**
  injected scope/dispatcher (no real-dispatcher default — coroutine-determinism rule).

## Relationship to Spec A

Spec A (coordination-round consolidation) changes `CoordinationCost.rounds` from a
constant ≤1 into DAG-depth. This spec's `warp.coordination.rounds` gauge is the
surface that makes A's round reduction *observable* — A lands already emitting it.
That is why metrics is specced first.
