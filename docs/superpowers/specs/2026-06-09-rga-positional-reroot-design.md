---
name: rga-positional-reroot
description: Positional reroot for RGA GC — fix eviction-orphan reorder by carrying predecessor position in Compact
metadata:
  type: project
---

# RGA Positional Reroot — Design

**Issue:** [#293](https://github.com/tractat-us/kuilt/issues/293)

## Problem

When the eviction-orphan safety-net GC's element I (partition > `evictionAfter`), any successor
J with `after=I` reroots to `HEAD` (current behavior). If M also sits at HEAD, J's higher
lamport sorts it above M in the descending-id tiebreak → `[J, M]` instead of `[M, J]`.

The reorder is convergent (all replicas agree) and not data loss, but it silently moves a live
message to the wrong position in a chat window. A test pinning the current `[J, M]` behaviour
already exists in `RgaRerootTest.evictionOrphanRerootsAbovePrecedingContent`.

**Trigger scope:** eviction-orphan safety-net only. Normal tombstone GC is blocked by barrier
condition 4 (no surviving local successor). `byCount` windowing is always leading-prefix, where
HEAD-reroot is already correct.

## Decision

Implement **positional reroot (Approach A)**. `Compact` carries `Map<RgaId, RgaId>` (id → its
`Insert.after` at GC time). `computeSequence` chain-walks the map to find the nearest surviving
ancestor for any orphaned element. Merge remains map union.

Approach B (eager chain collapsing) is a possible future optimisation — the wire format is
identical, only the internal resolution strategy changes.

## Data model

```kotlin
// before
data class Compact(val ids: Set<RgaId>) : RgaOp<Nothing>

// after
data class Compact(val positions: Map<RgaId, RgaId>) : RgaOp<Nothing>
```

`positions[id] = after` records where element `id` was positioned at GC time. All internal
callers of `ids` switch to `positions.keys` (mechanical). Merge: map union — sound because any
given id's `after` is immutable once its `Insert` was created, so two replicas always agree.

## `computeSequence`

```kotlin
private val compactPositions: Map<RgaId, RgaId> by lazy {
    ops.filterIsInstance<RgaOp.Compact>()
        .flatMap { it.positions.entries }
        .associate { (k, v) -> k to v }
}

private fun computeSequence(): List<RgaId> {
    val present = insertsByid.keys
    fun nearestAncestor(start: RgaId): RgaId {
        var cur = start
        while (cur != RgaId.HEAD && cur !in present) cur = compactPositions[cur] ?: RgaId.HEAD
        return cur
    }
    val childrenOf = insertsByid.values
        .groupBy(
            keySelector = { ins ->
                val a = ins.after
                if (a == RgaId.HEAD || a in present) a else nearestAncestor(a)
            },
            valueTransform = { it.id },
        )
        .mapValues { (_, ids) -> ids.sortedDescending() }
    val result = mutableListOf<RgaId>()
    appendChildren(RgaId.HEAD, childrenOf, result)
    return result
}
```

`compactPositions` is `by lazy` so it is built once per `Rga` instance. `sequence` is already
`by lazy`, so `computeSequence` (including chain-following) runs exactly once per instance. The
`a == RgaId.HEAD || a in present` fast-path keeps the common case (no orphan on the path)
zero-cost.

## Compaction methods

Both `compact()` and `compactCandidates()` replace the `Set` with a `Map`:

```kotlin
val positions = gcIds.associateWith { id -> insertsByid.getValue(id).after }
val compactOp = RgaOp.Compact(positions)
```

The deprecated `compact(watermark: Long)` receives the same treatment — it still builds
`positions` from the surviving `insertsByid` before purging.

## Serialization

`RgaOpSerializer` changes the Compact wire encoding:

```
before: { "t": 2, "ids": Set<RgaId> }
after:  { "t": 2, "pos": Map<RgaId, RgaId> }
```

Field key changes from `"ids"` (Set) to `"pos"` (Map). Breaking change — bump `kuiltVersionLine`
at merge per project convention. No migration path needed (pre-1.0).

`idsSerializer: KSerializer<Set<RgaId>>` is replaced by
`positionsSerializer: KSerializer<Map<RgaId, RgaId>>` using `MapSerializer(rgaIdSerializer, rgaIdSerializer)`.

## Tests

**Existing pin test** — `RgaRerootTest.evictionOrphanRerootsAbovePrecedingContent` already
constructs `RgaOp.Compact(setOf(opI.id))` and asserts `[J, M]`. After the fix:
- Update the `Compact` constructor call to `RgaOp.Compact(mapOf(opI.id to opM.id))` (I was after M)
- Flip assertion from `listOf("J", "M")` to `listOf("M", "J")`
- Update the comment to remove the "accepted quirk" note

**New chain test** — add to `RgaRerootTest`:
- Build `[M, I, K, J]` with `I@M`, `K@I`, `J@K`
- Compact K (after=I) then I (after=M) — two GC passes
- Assert J resolves to M (nearest surviving ancestor, not HEAD)

**Mechanical updates** — every call to `RgaOp.Compact(setOf(...))` across the test suite needs
to become `RgaOp.Compact(mapOf(id to after, ...))`. The `after` value for each id comes from
the `Insert.after` field in the test's setup. Affected files:
- `RgaCompactEvictionSafeBarrierTest` (uses `Compact` via `compactV3` — no direct construction,
  but `compactedIds` references change internally)
- `RgaCompactV3AdversarialProbeTest` (same — no direct `Compact` construction)
- `RgaCompactConcurrentInsertSoundnessTest`, `RgaWindowByCountIntegrationTest`,
  `RgaGcCoordinatorTest`, others that call `rga.compact(...)` — these get positions
  automatically from the production method, no manual update needed
- Any test that constructs `RgaOp.Compact(setOf(...))` directly needs the `mapOf` form

After all changes, `./gradlew :kuilt-crdt:jvmTest` must be green.
