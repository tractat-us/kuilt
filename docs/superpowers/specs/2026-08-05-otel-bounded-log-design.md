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
reclamation fired only under `DROP_OLDEST`). The design does not exploit it, because PR 0
(§5) removes that eviction entirely — `DROP_NEWEST` stops evicting, so there is nothing
for a second mechanism to reclaim.

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

## 3. The bound: a compacted floor on `Rga`

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

### A floor — enabled by fixing `DROP_NEWEST` first

The field is a downward-closed per-author high-water:

```kotlin
public val compactedBelow: VersionVector
```

merged by the existing `ceilWith`, tested by the existing `contains`, and already
canonically encoded — `VersionVector.entries` carries `CanonicalMapSerializer` for exactly
the #2010 reason (iteration order is not a function of the value while `equals` is
order-insensitive). No new lattice, no new serializer, no new golden-vector shape.

**This only works because `DROP_NEWEST` is fixed first (PR 0).** As shipped, `DROP_NEWEST`
evicts visible index `visibleCount - 1` *before* inserting
(`WarpLogRecordExporter.kt:652-684`) — so it drops the **second**-newest and retains
`1..maxRecords-1` plus the current newest, making the compacted set the band
`[maxRecords, newest)`. That is not downward-closed, and a floor cannot express it; it
would force a per-replica range field, a bespoke `(min lo, max hi)` merge, a new canonical
serializer, and a range-shaped `Quilted` capability.

It is also not what `BufferPolicy.DROP_NEWEST` documents — "**Drop the newest** span when
the buffer is full", which is the *incoming* record. Restoring the documented behaviour
(reject the incoming record once full) means nothing is evicted after the buffer fills, so
`DROP_NEWEST` never compacts at all and its bound is trivial. Every compacted set that
remains comes from `DROP_OLDEST` and is downward-closed.

So the symmetry the design needs is bought by **fixing a behaviour/doc mismatch**, not by
generalising the CRDT to absorb it. PR 0 exists to settle that question before any of the
CRDT work is built on it — see §8.

### The mint rule

**A replica may only raise its own entry.** The justification is that a replica can never
have an undelivered self-dot, whereas raising a *foreign* entry could pre-annihilate a dot
that author has not minted yet — strictly worse than the resurface-at-the-window-boundary
semantics windowing otherwise promises.

Sufficiency needs more than own-authorship. Own dots need not form a sequence prefix after
a merge (a foreign live element can sit between two of this replica's own records, and
reroot can move a higher-seq own subtree above a lower-seq one), so eviction order can
diverge from own-seq order. The rule is therefore:

> Raise `compactedBelow[self]` to the largest `k` such that **every** own dot in `1..k` is
> in the drop set. Anything dropped above `k` keeps an explicit `Compact` positions entry.

Under pure single-author `DROP_OLDEST` this is exact and the residue is empty. After merges
the floor lags and explicit entries bridge the gap — bounded in the shipped shape, degraded
under adversarial interleaving. That is a stated limit, not a defect.

The "every own dot in `1..k`" quantifier is what makes a hole impossible: the floor only
advances across a contiguous run this replica has actually dropped, so `ceilWith` can never
swallow a retained dot.

**Enforceability.** Merge cannot verify authorship. This adds **no new trust surface**:
the existing un-gated `RgaOp.Compact` already lets any peer purge arbitrary ids.
Enforcement is mint-time API discipline — a `compactBelow(replica)`-style entry point that
raises only its own entry — the same trust model as today.

### Reroot

`nearestPresentAncestor` does `positions[cur] ?: head` (`OpLogEngine.kt:161`), so an id
covered by the floor and carrying no positions entry reroots to HEAD. For a prefix drop that
is exactly correct: the dropped id's ancestors are all dropped too, so the chain-walk would
have reached HEAD anyway.

**Rejected shortcut.** Recording `id → HEAD` in `positions` instead of `id → after` would
halve the bytes and is equivalent *for a prefix drop*. It is unsound: `piece` merges
`compactPositions` by map union relying on "a given id's `after` is fixed at insert time"
(`Rga.kt:216-220`). A replica recording HEAD and a peer recording the real `after` collide,
last-writer-wins, and the two replicas diverge.

## 4. `Quilted.causalFloor()` — a capability, not an `Rga` patch

`Quilter` recomputes `contiguousFrontier(_state.value.causalDots())` on **every state
change** (`Quilter.kt:538`). A floor with no explicit id set would force Θ(floor) dot
enumeration per call — an unbounded tax on the hot path, worse than the disease it cures.

The fix is a new `Quilted` capability, shaped exactly like the existing `causalDots()`
(`Quilted.kt:57-75`) and defaulted so it is non-breaking for every CRDT that does not use
this path:

```kotlin
/** Per-author high-water this state delivered and has since compacted away. */
public fun causalFloor(): VersionVector = VersionVector.EMPTY
```

`contiguousHighWater` (`Quilter.kt:1189-1193`) starts its walk at `floor[author]` instead
of `0`. `Rga.causalDots` then stops re-emitting floor-swallowed dots — `causalFloor()`
carries them, which is what keeps the delivered frontier gap-free. Without it, dropping
those dots pins the author's delivered high-water below the gap forever and stalls all
downstream GC, exactly as `Rga.kt:477-491` warns.

**Correctness rests on §3's downward-closure.** The capability is a high-water, so it can
only describe a compacted set that is a prefix. That is guaranteed by the mint rule (a
contiguous own-dot run from 1) *and* by PR 0 removing the one policy that would otherwise
produce a band. If either changes, this capability is the thing that breaks — silently, by
under-reporting the frontier — so criterion 8 in §6 exists to catch it.

**This is deliberately generic.** `Fugue` has the same op-log shape — `compact`
(`Fugue.kt:422`), `causalDots` (`Fugue.kt:466`), and the same `nearestPresentAncestor`
reroot — and is the intended second adopter. The capability belongs on `Quilted` beside
`causalDots()` so any op-log CRDT can bound its own compaction record without `Quilter`
learning anything about it.

## 5. The exporter

### PR 0 — restore `DROP_NEWEST`'s documented behaviour

`BufferPolicy.DROP_NEWEST` documents "**Drop the newest** span when the buffer is full".
The shipped code drops the *second*-newest: `maybeEvict` removes visible index
`visibleCount - 1` and `export` then inserts the incoming record
(`WarpLogRecordExporter.kt:652-684`). Restore the contract — once `visibleCount ==
maxRecords`, log the drop and return `ExportResult.Success` **without** inserting.

Consequences, all wanted:

- The log freezes at the first `maxRecords` records, so `DROP_NEWEST` is bounded outright
  and never compacts. Every remaining compacted set is a `DROP_OLDEST` prefix, which is
  what lets §3 be a floor rather than a range.
- `seenIds` is **not** updated for a rejected record, so a later re-export retries rather
  than being deduped into silence.
- `bothBufferPoliciesGetTheSameBoundedWrite`
  (`WarpLogRecordExporterSegmentTest.kt:176-204`) needs restating: under `DROP_NEWEST` a
  full buffer writes *nothing*, which is a stronger bound than the test currently asserts,
  not a weaker one.

**This is a public behaviour change and it is the decision gate for everything after it.**
It ships first, alone, so that if review prefers the current behaviour we learn before the
CRDT work is built on downward-closure. If it is rejected, §3 reverts to a per-replica
range field with a `(min lo, max hi)` merge, its own canonical serializer, and a
range-shaped `causalFloor`; nothing else in this design changes.

### Batched windowing

The window pass runs **once per batch of evictions**, not per eviction. Segments roll on
op count (`activeOpCount >= segmentOps`, `WarpLogRecordExporter.kt:551`), so a per-eviction
`Compact` takes ops-written-per-record from 2 to 3 and **accelerates** key growth ~1.5×.
The drop set is every id in `log.sequence` outside the retained window, computed
policy-aware:

Only `DROP_OLDEST` reaches this path. It retains the **last** `maxRecords` visible ids and
drops everything before them — the `WindowPolicy.byCount` walk, mirrored in `:kuilt-otel`
rather than taking a `:kuilt-quilter` dependency. After PR 0, `DROP_NEWEST` never evicts, so
it has no drop set and needs no window pass.

### Segment retirement

A sealed segment is retirable iff every op it holds is an `Insert`/`Remove` now covered by
the compacted floor or a retained `Compact`, **and** it carries no `Compact` op that has
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

1. Durably write the consolidation state (the floor + any residual `Compact`).
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
5. `bothBufferPoliciesGetTheSameBoundedWrite` restated: both policies bound the **total**,
   not only the per-export write — `DROP_NEWEST` by never evicting, `DROP_OLDEST` by
   compacting. Neither is exempt.
6. Lattice laws hold for the floor field under mixed explicit-`Compact` / floor states,
   including a replica carrying only a floor merged with one carrying only explicit ops.
7. Seq survival across a floor raise plus a cacheless reload — a floor analogue of
   `RgaCompactionSeqSurvivalTest` (#639). `maxSeqByReplica` / `nextSeqFor` must not regress
   when the floor swallows the ids that were holding the per-author high-water up, so
   `OpLogEngine.deliveredDots` (`OpLogEngine.kt:97-102`) has the floor contribute at least
   `(r, floor[r])`.
8. A `Quilter` over an `Rga` with a non-empty floor keeps a **gap-free** delivered
   frontier, and the frontier is computed **without enumerating the swallowed dots** — the
   whole point of the capability. Assert both: the value, and that the work is O(authors).

## 7. Known costs, accepted

- **Wire-format break**, on the persisted blob *and* the gossip path — `merge()` is a
  cross-app-version anti-entropy surface. Approved (pre-1.0). Golden vectors regenerate
  (`CanonicalGoldenVectorTest`).
- **`DROP_NEWEST`'s observable behaviour changes** (PR 0). Approved; it restores the
  documented contract.
- **Foreign explicit `Compact`s are never floor-subsumed.** An own-replica floor cannot
  cover another author's dots, and `RgaGcCoordinator.compactWithWindow` mints
  mixed-replica-key Compacts routinely (`RgaGcCoordinator.kt:184-186`). Retained forever.
  Irrelevant in the single-author exporter shape; a stated limit of the bound.
- **Post-merge interleaving degrades the bound**, per the mint rule in §3.

## 8. PR sequence

| # | module | content |
|---|--------|---------|
| 0 | `:kuilt-otel` | `DROP_NEWEST` rejects the incoming record when full — the decision gate for §3's downward-closure |
| 1 | `:kuilt-crdt` | `Rga.compactedBelow` floor field, `ceilWith` merge, `equals`, mint entry point, serializer, goldens, lattice + seq-survival coverage |
| 2 | `:kuilt-crdt` | `Quilted.causalFloor()` defaulted capability; `Rga` implements it |
| 3 | `:kuilt-quilter` | `contiguousHighWater` starts at the floor; frontier gap-free + O(authors) test |
| 4 | `:kuilt-otel` | `opCountOf` fix, batched windowing, segment retirement, `retired` ledger, write-ordering, race serialization |

#2127 closes on PR 4. PRs 0–3 are `part of #2127`.

**PR 0 is a gate, not just a first step.** It settles a public-behaviour question the rest
of the design depends on; if it is rejected, PR 1's field changes shape before it is
written. Do not start PR 1 until PR 0 has merged.

`Quilted.causalFloor()` is deliberately its own PR: it is a capability other op-log CRDTs
(`Fugue` first) should adopt, not an `Rga` implementation detail, and reviewing it apart
from the `Rga` change keeps that visible.

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
  that the floor field makes dead code. Sequencing, not merit.
