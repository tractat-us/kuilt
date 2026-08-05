# Bounding the persisted otel log — design

**Issue:** [#2127](https://github.com/tractat-us/kuilt/issues/2127) (part of
[#1860](https://github.com/tractat-us/kuilt/issues/1860), follows
[#2126](https://github.com/tractat-us/kuilt/issues/2126))
**Date:** 2026-08-05
**Status:** approved, not yet implemented

## What this is for

`WarpLogRecordExporter` writes a device's logs to disk so they survive a restart and can
be handed to a peer later. Today it never throws anything away. A phone that has been
running for months keeps every log line it ever wrote, and every launch re-opens all of
them one file at a time. This design makes the exporter forget old records for real —
the bytes leave the disk, the memory is released, and startup stops getting slower.

The reason it is not simply "delete the old ones" is that the log is a *replicated* data
structure. Two devices can exchange logs and merge them. If one device deletes a record
and then merges with a peer that still has it, the record comes back — so deleting needs
a small permanent note saying "this was deliberately dropped", and the whole problem is
making those notes cheap enough that they don't become the new unbounded thing.

---

## 1. The premise this replaces

The decision recorded on #2127 (2026-08-05) was: *pursue the real causal-stability
barrier via `Rga.compact`*, expecting cross-module scope into `:kuilt-quilter` because
supplying `stableCut` / `frontierMax` / `delivered` "is `Quilter`'s job".

**That reclaims zero bytes under `DROP_OLDEST`, the default and the policy the field
incident is on.** `Rga.compact`'s condition 4 refuses any id that is a surviving
`Insert`'s `after` (`Rga.kt:376-378`); `insertsById` excludes only *compacted* ids, not
tombstoned ones, so in an append chain every tombstoned prefix element is the next
element's predecessor and is refused. Chaining does not rescue it: `compactUntilStable`
(`RgaGcCoordinator.kt:139-153`) eats a tombstoned region from the tail end inward, and
under `DROP_OLDEST` that region always terminates at the live window.

Executed probe — 10 appends, tombstone the leading 5, maximally permissive vectors
(`stableCut = frontierMax = delivered = {me: 10}`):

```
[probe] compact() returned: null
```

Condition 4 is **structural, not a stability condition**. This refutes the decision on
its own stated best case — the comment argues a never-merged replica should get the bound
cheaply "where everything is trivially stable", and a perfect stability oracle changes
nothing.

**Known qualification.** Under `DROP_NEWEST` the barrier *does* fire: eviction removes
the visible tail, which is a successor-free leaf, and a probe reclaimed all 5 evicted
tails in one pass. This is the exact inverse of #2126's asymmetry (segment-drop
reclamation fired only under `DROP_OLDEST`). The design below deliberately does not use
two mechanisms — see §4.

## 2. The mechanism: un-gated windowing

`RgaGcCoordinator` carries a **second path** beside barrier-gated GC: history windowing
(`RgaGcCoordinator.kt:171-188`), driven by `WindowPolicy` and `Rga.positionsFor`. Its
KDoc states it "does **not** need the causal-stability gate: reroot-to-HEAD (#254) keeps
the retained window reachable". It needs no version vectors, no `Quilter`, no membership.

Probe — window-drop the prefix, then merge with a peer holding every raw `Insert`:

```
[probe] ops before=15 after=6 list=[r5, r6, r7, r8, r9]
[probe] after merge with an un-compacted peer: [r5, r6, r7, r8, r9]
```

No resurrection. Suppression comes from `mergedCompactedIds` in `piece` (`Rga.kt:512-521`)
and the `compactedIds` guard in `applyInsert` (`Rga.kt:418`). **The retained `Compact` op
is what #2126's raw segment deletion lacked** — that is the whole difference between this
and the reclamation that was withdrawn under review.

Why windowing is the right fit rather than a weaker barrier: barrier-gated GC is
*position-preserving* and refuses an element with a surviving successor because compacting
it can reorder that successor among its new siblings. Windowing deliberately *forgets*
position. For a single-author append-only log there are no branches, so the order
perturbation is unreachable in the dominant shape and tolerable after merges.

**No new module edge.** `:kuilt-otel` depends on `:kuilt-crdt` and `:kuilt-core`, not
`:kuilt-quilter`. The exporter computes its own drop set from `log.sequence` and the
retained window; `WindowPolicy` stays where it is.

## 3. The bound: a compacted range on `Rga`

Windowing alone is a **constant factor, not a bound**. `RgaOp.Compact.positions` retains
one `(RgaId → RgaId)` pair per dropped record forever — roughly 110 B against the measured
~491 B/record, so ~4.5×, still Θ(records ever). A probe over 30 records at cap 3 with
per-eviction windowing left **27 retained singleton `Compact` ops**.

### It is a field, not an op

An op would need coalescing to be bounded, and coalescing breaks "piece is pure set
union": canonicalisation would have to be applied identically in `piece`, `apply`, **and**
`fromOps` (`Rga.kt:708`) or `equals` — which is ops-only (`Rga.kt:541-544`) — diverges
between a canonicalised replica and a wire round-trip. `RgaSerializer`'s Compact ordering
(`compareCompactPositions`, `OpLogEngine.kt:125-134`) also has no key-list to sort a
watermark by.

As a **field** on `Rga`, the state is the product of (op-set under union) × (compacted
record under its own merge). A product of join-semilattices is a join-semilattice, so
idempotence / commutativity / associativity hold by construction and
`RgaLawsPropertyTest` / `QuiltedLawsTest` are satisfied structurally rather than by
argument. The field participates in `equals`.

### Why a range, not a floor

A plain floor `VersionVector` is downward-closed. `DROP_NEWEST`'s drop set is not: the
exporter evicts visible index `visibleCount - 1` *before* inserting
(`WarpLogRecordExporter.kt:652-684`), so it retains seqs `1..maxRecords-1` **plus the
current newest**, making the compacted set the band `[maxRecords, newest)`. A floor would
bound `DROP_OLDEST` only — reintroducing the policy asymmetry that
`bothBufferPoliciesGetTheSameBoundedWrite` (`WarpLogRecordExporterSegmentTest.kt:176-204`)
exists to catch.

So the field is a per-replica **range** — one half-open `[lo, hi)` of author seqs:

```kotlin
public val compacted: Map<ReplicaId, LongRange>
```

- `DROP_OLDEST` → `[1, k)`
- `DROP_NEWEST` → `[maxRecords, newest)`

Both O(1) per replica. One mechanism, one code path, symmetric bound.

**Merge** is elementwise `(min lo, max hi)` per replica. That is sound only because the
ranges for a given replica come from a single producer and are therefore nested — which
the mint rule below guarantees.

**The wire encoding must be canonical.** A `Map<ReplicaId, LongRange>`'s iteration order
is not a function of its value while `equals` is order-insensitive, so two replicas at the
same logical state would otherwise emit different bytes — the #2010 defect, already fixed
for `VersionVector.entries` and `RgaOp.Compact.positions` by `CanonicalMapSerializer`
(`Rga.kt:104`). The new field takes the same treatment, and the golden vectors pin it.

### The mint rule

**A replica may only raise its own entry.** The justification is that a replica can never
have an undelivered self-dot, whereas raising a *foreign* entry could pre-annihilate a dot
that author has not minted yet — strictly worse than the resurface-at-the-window-boundary
semantics windowing otherwise promises.

Sufficiency needs more than own-authorship. Own dots need not form a sequence prefix after
a merge (a foreign live element can sit between two of this replica's own records, and
reroot can move a higher-seq own subtree above a lower-seq one), so eviction order can
diverge from own-seq order. The rule is therefore:

> Raise to the largest `k` such that **every** own dot in `[lo, k)` is in the drop set.
> Anything dropped above `k` keeps an explicit `Compact` positions entry.

Under pure single-author `DROP_OLDEST` this is exact and the residue is empty. After
merges the range lags and explicit entries bridge the gap — bounded in the shipped shape,
unbounded under adversarial interleaving. That is a stated limit, not a defect.

The mint path additionally **checks the new range is adjacent to or overlapping the
existing one**, falling back to explicit positions otherwise, so a policy switch across
restarts cannot punch a hole that `(min lo, max hi)` would silently swallow.

**Enforceability.** Merge cannot verify authorship. This adds **no new trust surface**:
the existing un-gated `RgaOp.Compact` already lets any peer purge arbitrary ids.
Enforcement is mint-time API discipline — a `compactBelow(replica)`-style entry point that
raises only its own entry — the same trust model as today.

### Reroot

`nearestPresentAncestor` does `positions[cur] ?: head` (`OpLogEngine.kt:161`), so an id
covered by a range and carrying no positions entry reroots to HEAD. For a prefix drop that
is exactly correct: the dropped id's ancestors are all dropped too, so the chain-walk would
have reached HEAD anyway.

**Rejected shortcut.** Recording `id → HEAD` in `positions` instead of `id → after` would
halve the bytes and is equivalent *for a prefix drop*. It is unsound: `piece` merges
`compactPositions` by map union relying on "a given id's `after` is fixed at insert time"
(`Rga.kt:216-220`). A replica recording HEAD and a peer recording the real `after` collide,
last-writer-wins, and the two replicas diverge.

## 4. `Quilted.causalCompacted()` — a capability, not an `Rga` patch

`Quilter` recomputes `contiguousFrontier(_state.value.causalDots())` on **every state
change** (`Quilter.kt:538`). A compacted range with no explicit id set would force Θ(range)
dot enumeration per call — an unbounded tax on the hot path, worse than the disease it
cures.

The fix is a new `Quilted` capability, shaped exactly like the existing `causalDots()`
(`Quilted.kt:57-75`) and defaulted so it is non-breaking for every CRDT that does not use
this path:

```kotlin
/** Per-author seq ranges this state delivered and has since compacted away. */
public fun causalCompacted(): Map<ReplicaId, LongRange> = emptyMap()
```

**It returns ranges, not a floor.** A `VersionVector` floor is downward-closed and so
cannot represent `DROP_NEWEST`'s band `[maxRecords, newest)` — the frontier walk would stop
at `maxRecords - 1` and pin the author's delivered high-water below the gap forever,
stalling all downstream GC exactly as `Rga.kt:477-491` warns. Since §3 went to trouble to
make the *state* symmetric across policies, the capability that reads it has to be
symmetric too.

`contiguousHighWater` (`Quilter.kt:1189-1193`) still counts up from `1`, but on hitting a
missing seq it checks whether a range covers it and, if so, resumes at that range's `hi`.
`Rga.causalDots` then stops re-emitting range-swallowed dots — `causalCompacted()` carries
them, and the walk bridges the gap they leave.

**This is deliberately generic.** `Fugue` has the same op-log shape — `compact`
(`Fugue.kt:422`), `causalDots` (`Fugue.kt:466`), and the same `nearestPresentAncestor`
reroot — and is the intended second adopter. The capability belongs on `Quilted` beside
`causalDots()` so any op-log CRDT can bound its own compaction record without `Quilter`
learning anything about it.

## 5. The exporter

### Batched windowing

The window pass runs **once per batch of evictions**, not per eviction. Segments roll on
op count (`activeOpCount >= segmentOps`, `WarpLogRecordExporter.kt:551`), so a per-eviction
`Compact` takes ops-written-per-record from 2 to 3 and **accelerates** key growth ~1.5×.
The drop set is every id in `log.sequence` outside the retained window, computed
policy-aware:

- `DROP_OLDEST` retains the **last** `maxRecords` visible ids; the drop set is everything
  before them (the `WindowPolicy.byCount` walk, mirrored in `:kuilt-otel` rather than
  taking a `:kuilt-quilter` dependency).
- `DROP_NEWEST` retains the **first** `maxRecords` visible ids; the drop set is everything
  after them.

### Segment retirement

A sealed segment is retirable iff every op it holds is an `Insert`/`Remove` now covered by
the compacted range or a retained `Compact`, **and** it carries no `Compact` op that has
not been consolidated. The second clause is what keeps
`aCompactionInheritedFromTheLegacyBlobIsNeverDropped`
(`WarpLogRecordExporterSegmentTest.kt:354-382`) green — the legacy segment 0 and every
`adoptRemoteSegment` segment can carry foreign `Compact`s.

**Fix `opCountOf` first** (`WarpLogRecordExporter.kt:613-614`): `sequence.size +
tombstones.size` is Compact-blind — a live instance of the #2126 trap ("`Compact` is
invisible to a `sequence`/`tombstones` projection"). Benign today as a roll-threshold
wobble; a correctness bug the moment segments carry Compacts.

### Write ordering

Extending the file's own precedents (the index is the commit point; `adoptRemoteSegment`
writes the index first, `WarpLogRecordExporter.kt:578-581`):

1. Durably write the consolidation state (the range + any residual `Compact`).
2. Write the index moving retired numbers from `sealedSegments` into a new
   `retired: List<Int>` field — **the commit point**.
3. Delete the retired keys.
4. Drop confirmed deletions from `retired` on a later index write.

A crash anywhere is safe: before (2) the old layout rules; between (2) and (3) the
`retired` list is the sweep ledger. That ledger is the **only** way to avoid permanent
leaks in a store with no key-enumeration API — `LogSegmentIndex`'s numbers are only ever
added, so a segment the index forgets is unreachable forever. This generalises the
existing `sweepLegacyKey` pattern (`WarpLogRecordExporter.kt:316-320`).

Step (1) is strictly first: deleting before the index stops naming a segment is tolerated
by `readSegment` (absence is expected, `WarpLogRecordExporter.kt:347-359`) but loses
records if the covering write had not landed.

### A new race class

Batches are built under `lock` but committed **outside** it
(`WarpLogRecordExporter.kt:523-538`). Today's worst case is a stale segment write; a
retirement *delete* racing an export *write* on the same key is new and needs explicit
serialization — a single-writer queue, or committing retirement in the same ordering
domain as exports.

### The `log.ops == union(segment.ops)` invariant

This invariant (`WarpLogRecordExporter.kt:168-169`) breaks the moment a windowing
`Compact` purges in-memory ops that still persist in sealed segments. Recovery still
converges — `piece` re-purges via `mergedCompactedIds` order-independently
(`Rga.kt:521`) — but the comment must be restated as "the segments' union *pieces* to
`log`", and `scriptedRunMatchesPreOptimisationReference` needs rework: the issue body
already flags that its 14-step script at `segmentOps = 256` never rolls a segment, so it
has zero coverage of any reclaiming path, and op-set equality against a reference
implementation is the wrong shape once reclamation exists.

## 6. Acceptance criteria

1. `recover()` opens **O(maxRecords / segmentOps) + 1** keys, independent of records ever
   exported. Asserted by counting `DurableStore.read` calls, not inferred.
2. Bytes on disk are **O(maxRecords · record + replicas)**, not Θ(records ever).
3. The in-memory op-log is bounded on the same terms — the issue lists it as in-scope, and
   a persistence-only fix cannot deliver it.
4. `aMergeCannotResurrectRecordsThisReplicaAlreadyEvicted` and
   `aCompactionInheritedFromTheLegacyBlobIsNeverDropped` still pass, unmodified.
5. `bothBufferPoliciesGetTheSameBoundedWrite` extended: both policies get the same bounded
   **total**, not only the same bounded per-export write.
6. Lattice laws hold for the range field under mixed explicit-`Compact` / range states.
7. Seq survival across a range raise plus a cacheless reload — a range analogue of
   `RgaCompactionSeqSurvivalTest` (#639). `maxSeqByReplica` / `nextSeqFor` must not regress
   when the range swallows the ids that were holding the per-author high-water up, so
   `OpLogEngine.deliveredDots` (`OpLogEngine.kt:97-102`) has each range contribute at least
   `(r, hi - 1)`.
8. A `Quilter` over an `Rga` keeps a **gap-free** delivered frontier under both policies —
   including `DROP_NEWEST`, where the compacted band does not touch seq 1 and the walk has
   to bridge it. This is the case a floor-shaped capability would have failed silently.

## 7. Known costs, accepted

- **Wire-format break**, on the persisted blob *and* the gossip path — `merge()` is a
  cross-app-version anti-entropy surface. Approved (pre-1.0). Golden vectors regenerate
  (`CanonicalGoldenVectorTest`).
- **Foreign explicit `Compact`s are never range-subsumed.** An own-replica range cannot
  cover another author's dots, and `RgaGcCoordinator.compactWithWindow` mints
  mixed-replica-key Compacts routinely (`RgaGcCoordinator.kt:184-186`). Retained forever.
  Irrelevant in the single-author exporter shape; a stated limit of the bound.
- **Post-merge interleaving degrades the bound**, per the mint rule in §3.

## 8. PR sequence

| # | module | content |
|---|--------|---------|
| 1 | `:kuilt-crdt` | `Rga.compacted` range field, merge, `equals`, mint entry point, serializer, goldens, lattice + seq-survival coverage |
| 2 | `:kuilt-crdt` | `Quilted.causalCompacted()` defaulted capability; `Rga` implements it |
| 3 | `:kuilt-quilter` | `contiguousHighWater` bridges compacted ranges; frontier gap-free test under both policies |
| 4 | `:kuilt-otel` | `opCountOf` fix, batched windowing, segment retirement, `retired` ledger, write-ordering, race serialization |

#2127 closes on PR 4. PRs 1–3 are `part of #2127`.

`Quilted.causalCompacted()` is deliberately its own PR: it is a capability other op-log
CRDTs (`Fugue` first) should adopt, not an `Rga` implementation detail, and reviewing it
apart from the `Rga` change keeps that visible.

## 9. Declined

- **Barrier-gated `Rga.compact` as the mechanism** — §1. Zero reclamation under the
  default policy.
- **Drop bodies, retain tombstones** — declined on #2127; leaves the segment count
  untouched.
- **A single-replica fast path** — declined on #2127; narrows a public `merge()` that
  advertises anti-entropy.
- **Dropping the no-resurrection contract** — trivially bounds everything by deleting
  `aMergeCannotResurrectRecordsThisReplicaAlreadyEvicted`, but overturns a deliberate
  #2126 review decision and lets a peer re-inflate this replica's log without limit.
- **One consolidation key, rewritten each pass** — bounds keys at Θ(N²) bytes written
  (~1.1 GB at N=100k, batch=500), the exact defect #2126 fixed.
- **LSM-style tiered consolidation in `:kuilt-otel`** — genuinely bounds keys at
  O(N·log N) writes without touching the CRDT, but is ~60 lines of compaction machinery
  that the range field makes dead code. Sequencing, not merit.
