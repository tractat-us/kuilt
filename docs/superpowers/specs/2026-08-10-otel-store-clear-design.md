# Emptying a telemetry store — a supported `clear()` for `WarpTelemetry`

**Status:** design, not yet planned into tasks.
**Date:** 2026-08-10.
**Issue:** [#2208](https://github.com/tractat-us/kuilt/issues/2208), which links the consumer-side request that motivated it.

## What this is, in plain language

An app that records its own telemetry fills up a phone. Today there is no way to empty
that store from inside the app — the only reliable reset is deleting the app from the
Home screen by hand, because `xcrun devicectl device uninstall app` refuses.

This adds a `clear()` the app can call: it empties what the telemetry buffer holds, gives
the disk space back, and leaves a store the same running app keeps writing into. No
restart, no flag, no per-platform directory delete.

## Why it is wanted

Three reasons, in the order they came up:

1. **Reclaiming space.** The store grows with the records ever exported.
2. **Testing re-initialisation.** Exercising the "recover from an empty store" path on a
   real device, repeatedly, without reinstalling between runs.
3. **Measuring from a known `N`.** The at-cap export regime
   [costs 4.1× the below-cap one](https://github.com/tractat-us/kuilt/issues/2193#issuecomment-5229062891)
   on an A12; every point on that curve needs a from-empty sweep.

## Why deleting the files does not work

Removing the directory behind a **live** buffer clears nothing durable. The in-memory
op-log still holds every record, and the next captured record writes it back. An app that
logs continuously loses that race immediately. That is the constraint #2208 records, and
it is why the reset has to live inside the exporter rather than beside it.

## What already exists — do not rebuild these

[#2187](https://github.com/tractat-us/kuilt/pull/2187) (closing #2127) landed most of the
machinery this needs. Reusing it is the design.

- **`writeMutex`** serializes whole write turns in `WarpLogRecordExporter`. A `clear()`
  expressed as a turn is therefore already fenced against an in-flight `export()`/`merge()`
  — no epoch counter, no generation, no second lock.
- **`Rga.dropWindow(self, dropped)`** drops elements and records the drop as a per-author
  compaction **floor** (plus an `RgaOp.Compact` for a foreign author's dots). Two properties
  matter here:
  - it **preserves the identity high-water** — `cacheAfterFloor` merges the dropped seqs
    into `maxSeqByReplica`, with the comment *"the floor is itself evidence those seqs were
    minted"* — so nothing re-mints an id it already used;
  - a dropped dot stays **suppressed**, so a peer holding the raw `Insert` cannot push the
    record back through `merge`: `Rga.piece` merges the floor and re-purges beneath it.
- **`retireSupersededSegments()` and the sweep ledger** (`LogSegmentIndex.retired`,
  `StoreAction.CommitRetirement`, `StoreAction.Sweep`) delete segment keys crash-safely and
  **retry a refused delete on the next start**. That last property is exactly what motivation 1
  needs: a delete that fails leaks nothing permanently.
- **`ORSet` removal retains `causal.context`**, so the retired dots stay witnessed and a
  peer's re-merge of the old adds is dominated rather than resurrecting. (`removeWhole` is
  `internal` to `:kuilt-crdt`; `:kuilt-otel` reaches the same behaviour through the public
  `remove` + `piece` — see the spans section.)

Two shapes that looked like prerequisites are not:

- A durable lost-update on the log exporter's write path — **already fixed** by `writeMutex`.
- An index-driven deletion path for `clear()` to build — **already exists** as retirement.

## The design

`clear()` is a turn like any other. Per signal it reuses the CRDT's own forgetting
mechanism rather than resetting to `empty()`, which is what buys merge-safety.

| Signal | Mechanism | Peer re-merge | Store after |
|---|---|---|---|
| **logs** | `Rga.dropWindow` over **every** id, then `retireSupersededSegments()` | cannot resurrect | index + one small active segment |
| **spans** | `ORSet` remove of every element; causal context retained | cannot resurrect | one small blob |
| **metrics** | reset the maps, delete the five keys | **restores old values** | keys deleted |
| **causal clock** | frontier → empty, `seq` untouched | n/a | key rewritten |

### API surface

```kotlin
public suspend fun WarpLogRecordExporter.clear(): ExportResult
public suspend fun WarpSpanExporter.clear(): ExportResult
public suspend fun WarpMetricExporter.clear(): MetricExportResult
public suspend fun WarpTelemetry.clear(): ExportResult
```

Never throws, matching `export()` and `recover()`. `WarpTelemetry.clear()` fans out to all
three signals plus the clock frontier and returns `Failure` if **any** signal failed —
unlike `WarpOtlpBridge.drain`, which tolerates partial success. A half-cleared store is a
result the caller has to see. It is **not atomic across signals**; a failure leaves the
others cleared.

### Logs — a window pass that retains nothing

`windowPass()` already does the whole job for the window `maxRecords`. A clear is the same
pass with a retained window of zero, so the change is to parameterise the retention rather
than to add a path:

- `idsOutsideWindow(retain: Int)` — at `retain = 0` the existing loop breaks on its first
  iteration with `cut` still at `sequence.size`, so it returns every id with no change to
  the loop body.
- `windowPass(retain: Int = maxRecords)` — unchanged otherwise: it `piece`s the delta into
  `activeSegment`, refreshes `activeOpCount`, resets `evictionsSincePass`, and calls
  `rebuildDerivedState()` (which resets `tail` to `RgaId.HEAD`, `visibleCount` to 0, and
  empties `seenIds`, freeing the dedup slots exactly as an eviction already does).
- `clearTurn()` then calls `pendingWrites(retire = true)` and `commit(...)`. The active
  segment write carries the raised floor; retirement follows it, which is the ordering
  `retireSupersededSegments()` already requires.

Emptying `seenIds` means a record whose `recordId` was exported before a clear will be
inserted again if re-exported. That is correct — the earlier copy is gone — and matches
eviction's existing behaviour.

### Spans

`ORSet`'s public surface is `remove(element): Patch<ORSet<E>>`; there is no bulk form, so
the natural implementation folds `set.piece(set.remove(e))` over `elements`. At
`DEFAULT_MAX_SPANS = 10_000` that fold's cost needs measuring before it ships: if the
repeated causal `piece` is superlinear, the fallback is a bulk `ORSet.removeAll(elements)`
in `:kuilt-crdt` built on the existing `removeWhole`, which is a small additive change.
**Measure first; do not add the API speculatively.**

### Metrics — the one signal with an honest limit

`GCounter` and `HyperLogLog` are monotonic join-semilattices. There is no merge-safe forget
for either: after a clear, a merge with a peer holding the old state restores the old values,
because that is what a max-join means. `LWWRegister` gauges have no "cleared" value to write.

So a metric clear is **local-only**, and says so in its KDoc. This is acceptable rather than
ignored: metrics are bounded by `maxMetrics`, live in five single-key blobs, and the
consumer driving this is a non-gossiping device. Making it merge-safe would need a
retraction mechanism the CRDTs deliberately lack.

### Causal clock

`WarpCausalClock` gains an `internal` frontier reset that leaves `seq` untouched. `seq` must
not regress — the clock's own KDoc forbids it, and a regressed `seq` re-mints dots already
used by earlier spans. The **frontier** must go, because it names dots of spans that no longer
exist and `inferCausalLinks` claims totality ("every predecessor dot resolves to a span in the
set").

**`WarpSpanExporter.clear()` does this, not the facade.** The exporter already holds the clock
and is a public entry point in its own right, so putting the reset at `WarpTelemetry.clear()`
would leave the totality broken for anyone calling `spans.clear()` directly. The clock is
persisted before the span write, the same order and for the same reason as `export()` (#1053).

## Failure handling

`clear()` returns `Failure` when the durable write of the turn fails, exactly as
`export()` does.

**A clear emits its writes unconditionally — it must not be gated on whether the window pass
moved anything, and that is not an optimisation worth having.** The first attempt drops
everything in memory before any write. If its commit fails and the retry gates on the pass,
the retry finds nothing left to drop, writes **nothing**, and returns `Success` while the
store still holds every sealed segment — a restart then brings all of them back. Writing
unconditionally costs one index write and one small active-segment write when clearing an
already-empty exporter, and buys convergence by construction rather than by a flag tracking
whether an earlier pass's covering write ever landed. On a retry the pass is a no-op but the
active segment still carries the floor from the first attempt, so the write is still covering
and the sealed segments are still retirable.

One residue: if a failed clear is never retried and the app simply keeps exporting, the next
successful export re-lands the floor but with `retire = false`, so the sealed segments are not
reclaimed until the next natural window pass (roughly `maxRecords` evictions away). Records
stay gone; only the bytes linger. Any later `clear()` reclaims them.

**On failure the in-memory drop has already happened, so the observable count reads zero
while the store still holds records.** This is the contract, not an accident of ordering: a
turn builds its actions from already-mutated state, which is how all three write paths in
this class work. A caller must therefore **treat any non-`Success` as "count unknown"** and
not start a from-empty measurement from that state.

Re-reading the store on the failure path to restore agreement was considered and rejected:
`recover()` degrades to "start fresh" when a read fails, so on a store that is failing
writes — the case that produced the failure — it would leave memory empty again and report
success at lying. A half-guarantee here is worse than a stated limit. On `Success` the count
reads zero synchronously, because the drop precedes the write.

A refused segment delete does **not** leak permanently. The number is on the ledger
(`LogSegmentIndex.retired`) only after a write carrying its covering state was confirmed
durable, and `loadPersistedState` sweeps the ledger unconditionally at the next start.

A failed clear **does** move `ExporterHealth.failed`, and should: the store really did
reject a write, which is exactly what that counter means. A successful clear never moves
`accepted`, which keeps meaning "records durably taken" rather than "store calls made".
Both fall out of reusing the existing `commit()` rather than being arranged for.

## What `clear()` does not do

Stating these because each is an expectation the word "clear" invites:

- **It does not leave zero keys.** The store settles to the index plus one small active
  segment holding the floor. The floor is what prevents resurrection, and it costs
  O(authors). Deleting it would make the store literally empty and re-open the merge hole.
- **It does not reclaim everything on a gossiping replica.** A segment carrying an
  `RgaOp.Compact` is never retired, and a `merge`-adopted segment is pinned entire. An
  export-fed replica mints only its own dots, which fold into the floor, so every sealed
  segment becomes retirable — which is the case the consumer is in.
- **It does not un-send.** Records already drained to an OTLP endpoint stay there;
  `WarpOtlpBridge` reconciles by `LogRecord.recordId`, which a clear does not touch.
- **It does not propagate.** A clear is one replica's decision. For logs and spans a peer
  cannot push the records back, but neither does the peer forget them.

## Testing

TDD per repo rule — failing test first, implement, revert and confirm it reddens. All in
`commonTest` so every target runs them.

Load-bearing, roughly in order of what each would catch:

- **A fresh exporter over the same store recovers empty** after `clear()`. This is the
  whole claim; everything else is a detail of it.
- **Re-initialisation** (motivation 2): `clear()`, export `N` more with a small `segmentOps`
  so segments roll across the boundary, then a fresh instance recovers exactly those `N`
  and nothing else.
- **Reclamation** (motivation 1): a store that records its keys shows the sealed segment
  keys gone after a clear. `InMemoryDurableStore` cannot enumerate and should stay that
  way — the test uses a local recording store.
- **No resurrection**: `preClearLog.piece(postClearLog)` and a merge of a *peer's* copy of
  the pre-clear log both leave the cleared records absent. This is the property
  `dropWindow` buys and the one an `Rga.empty()` implementation would silently lose.
- **No id collision**: export → `clear()` → export, and the two records coexist after a
  merge rather than one displacing the other.
- **Spans**: a peer's re-merge of the pre-clear adds does not resurrect them — the ORSet
  analogue, and the failure a logs-shaped test would not find.
- **Metrics**: the local-only limit is asserted, not assumed — a merge after clear *does*
  restore the old sum, pinned as the documented behaviour so a later change has to face it.
- **Clock**: `tick()` → `clear()` → `tick()`; `seq` did not regress and the frontier emptied.
- **Failure and retry**: a store that throws on the turn's write returns `Failure`, and the
  retry — once the store recovers — must leave a **fresh exporter recovering empty**. That last
  clause is the assertion, not "the retry returned `Success`": a gated retry returns `Success`
  having written nothing, with the live buffer already empty from the failed attempt, so every
  weaker assertion passes. Prove this test red against the gated version before trusting it.
- **Concurrency** (jvmTest, real threads, existing `@Suppress("ForbiddenImport")`
  precedent): interleaved `export()` and `clear()`; the store never ends holding a record
  from before the last clear.

Verified with the full `./gradlew build --rerun-tasks`, not `jvmTest` — Android and Native
variants compile differently and the build cache serves stale greens.

## Documentation

Three of these are staleness rather than additions, and each is a claim `clear()` falsifies:

- `WarpLogRecordExporter`'s retirement section describes retirement as driven by windowing;
  `clear()` becomes a second driver.
- `LogSegmentIndex.retired`'s KDoc and the class KDoc's "no key-enumeration API" sentence —
  still true for consumers, and now qualified by the exporter's own index being sufficient
  for its own reset.
- `Writerside/topics/otel-logs.md` covers the log store and needs the affordance.

Plus: `@sample` functions in `kuilt-otel/src/commonSamples/`, `kuilt-otel/module.md`, an
entry in the cookbook's existing `## Telemetry & log capture` section (CLAUDE.md treats a new
primitive with no cookbook entry as a broken build), a check that
`.claude/skills/kuilt-primitives/SKILL.md` still routes, and `./gradlew verifyDocCitations`.

## Delivery

Two PRs:

1. **Logs** — `idsOutsideWindow(retain)` / `windowPass(retain)` parameterised,
   `WarpLogRecordExporter.clear()`, its tests. The substantive one.
2. **Spans, metrics, facade, docs** — the other two exporters, the clock frontier reset,
   `WarpTelemetry.clear()`, and every doc surface above.

PR 2's body notes that this is what lets the consumer-side request linked from #2208 drop
its interim per-platform directory-delete workaround.

## Deliberately out of scope

- **Key enumeration on `DurableStore`.** #2208 names it as a gap, and this design routes
  around it rather than closing it: the exporter's own index is sufficient for its own
  reset. A consumer holding a bare `DurableStore` still cannot enumerate.
- **Consolidation** — rewriting a pinned segment's `Compact` forward so the segment can be
  retired. Already declined by #2187's design §9; it is what would make a gossiping
  replica's clear total.
- **A merge-safe metric retraction.** Would need a new CRDT, not a new method.
