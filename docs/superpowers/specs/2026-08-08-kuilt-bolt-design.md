# `:kuilt-bolt` — a write-only archive for op-log CRDTs

**Status:** design, not yet planned into tasks.
**Date:** 2026-08-08.

## What this is, in plain language

A phone can only keep so much. It holds the last few thousand log lines, and when it runs out of
room it forgets the oldest ones — which is the right trade on a device with a small disk and a
battery. A server has no such problem: it could happily keep a year.

Today it cannot, because the two of them share their forgetting. When the phone drops a record it
records *that it dropped it*, and that decision travels with the data — so the moment the server
syncs with the phone, the server forgets the same records. Retention is a property of the group, not
of each machine.

`:kuilt-bolt` is a **bolt of cloth**: a rolled-up store of everything that has been woven, kept
beside the live replica rather than inside it. Deltas flow into it and never out. Because nothing
flows back, its retention is nobody else's business — a server's bolt can hold a year while the
phone that fed it holds an hour, and neither one changes the other's mind.

You can ask a bolt what it holds — "everything this node wrote last Tuesday" — but you can never
merge it back into the live system. That restriction is the whole reason the design works, and it is
enforced by the type, not by a comment.

## Why the obvious approach does not work

The natural implementation is "make a second replica and give it a bigger `maxRecords`". That is
defeated by the first sync, and the mechanism is worth stating exactly because it is not obvious.

`Rga.piece` (`kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt:782`):

```kotlin
val mergedFloor = compactedBelow.ceilWith(other.compactedBelow)   // elementwise MAX
val mergedCompactedIds = compactedIds + other.compactedIds        // UNION
val mergedOps = purgeBelow(purge(rawUnion, mergedCompactedIds), mergedFloor)
```

Suppression is **monotone and contagious**. A phone's window pass raises a floor and mints
`RgaOp.Compact`s; a merge takes the *maximum* floor and the *union* of compacted ids, then purges
under both. So a big-retention replica keeps its extra history right up until it talks to a
small-retention one.

This is not a defect. It is load-bearing for the phone: it is precisely what stops a peer that still
holds the raw `Insert` from pushing a forgotten record back in. **Asymmetric retention and
"windowing is safe without a causal-stability barrier" are the same knob pulled in opposite
directions**, and any design that wants the first must give up being a replica.

## The invariant

> **A bolt consumes operations, never states, and never joins the lattice.**

Three consequences, each load-bearing:

1. **It is fed ops, not deltas.** A `Patch<S>` is a state fragment of the same lattice
   (`Quilted.kt:128`), so absorbing one means `piece`, which means inheriting suppression. A bolt
   takes `List<Op>` instead. The `compactedBelow` floor is *state*, not an operation — so an op
   stream **cannot carry a floor even by mistake**. The firewall is structural rather than enforced.
2. **It discards `Compact`.** Of the three op shapes, `Insert` and `Remove` are content and
   `Compact` is suppression. A bolt keeps the first two and drops the third. This is the only
   deliberate divergence from CRDT semantics in the design, and it is what lets a bolt retain more
   than its source.
3. **It never merges back.** A bolt's op-set is a strict superset of its source's. Feeding it back
   into the mesh would resurrect every windowed record everywhere. `Bolt` therefore does not
   implement `Quilted` and exposes no `piece`.

A replay is consequently **not a valid CRDT state**. Recovery replays a *bounded suffix*; the
unbounded read is deliberately the awkward one to reach for.

## Scope: op-log CRDTs only

`Rga` and `Fugue` — the two structures with an op-log. `GCounter`, `LWWRegister`, `ORSet` and the
rest of the zoo have no operations to archive, and the contract must say "op-log CRDT" rather than
"any `Quilted`" so nobody tries to archive a counter and gets an empty file.

The two are **not symmetric**, and the design must not assume they are:

| | `Compact` ops | `compactedBelow` floor |
|---|---|---|
| `Rga` | yes | **yes** |
| `Fugue` | yes | **no** |

Verified: `Rga.kt` references `compactedBelow` 39 times; `Fugue.kt` zero. Feeding ops rather than
states means this asymmetry never reaches the bolt — a second reason for consequence 1 above.

## The abstraction already exists

`:kuilt-crdt` already carries the exact op-log view a bolt needs, as `internal`
(`OpLogEngine.kt`):

```kotlin
internal sealed interface LogOp<out Id> {
    data class Insert<Id>(val id: Id) : LogOp<Id>
    data class Remove<Id>(val id: Id) : LogOp<Id>
    data class Compact<Id>(val compactedIds: Set<Id>) : LogOp<Id>
}

internal class OpLogEngine<Id : Any, Op : Any>(
    private val view: (Op) -> LogOp<Id>,
    private val dotOf: (Id) -> Dot,
)
```

`Rga` and `Fugue` each already construct that adapter pair (`Rga.kt:1090`, `Fugue.kt:1036`), and
`OpLogEngine.purge` already performs the exact keep-`Insert`/`Remove`, retain-`Compact`
classification the bolt inverts.

### The one open decision

**What becomes public, and in what shape.** `LogOp`, the adapters and `Rga.ops` are all `internal`,
so `:kuilt-bolt` cannot currently see an op-log at all. `explicitApi()` is enforced, so anything
exposed is a compatibility commitment.

Recommendation: a **narrow `OpLogCrdt` contract** in `:kuilt-crdt` exposing exactly what an archive
needs — classify an op, project an id to a `Dot`, iterate a delta's ops — rather than making `ops`
public. Making `ops` public would expose the whole internal representation and foreclose Phase 3B's
`RgaCache` work and any future backing change.

**This is deliberately left open for the implementation plan to argue rather than settled here.**

## Interface

```kotlin
public interface Bolt<Op> {
    public suspend fun append(ops: List<Op>): AppendResult
    public fun replay(scope: ReplayScope): Flow<Archived<Op>>
    public fun availability(): BoltAvailability
}
```

`availability()` mirrors `Loom.availability(): FabricAvailability`, the pattern this repo already
uses for a facility that is real on some runtimes and absent on others.

### Frame format — fixed now, because it is expensive to change

| Field | Purpose |
|---|---|
| append offset | physical seek; monotonic; the only strictly ordered coordinate |
| arrival timestamp | "what did this node write last Tuesday", with no knowledge of `V` |
| causal dots covered | exact resume for recovery, and "replay from dot X" |
| reserved key slot | lets event-time indexing be layered later without a format change |

**Arrival time is not event time.** A delta merged from a peer arrives long after it happened. The
distinction must be in the KDoc, because a consumer who conflates them will draw wrong conclusions
from a correct archive.

Ops are serialized with the **canonical** serializers (`RgaOpSerializer` is already public), not the
compiler-generated ones — not for wire parity, since a bolt is a local file, but because the
canonical form is the one with a stability guarantee and golden vectors behind it, and an archive is
read by *future* versions of the code that wrote it.

## Backends

Synchronous and asynchronous are **one mechanism and a flag**, not two implementations: `msync` /
`MappedByteBuffer.force()` per append is the synchronous backend; letting the OS flush is the
asynchronous one.

| Target | Backend | Notes |
|---|---|---|
| JVM / Android | mmap via `FileChannel.map()` | the server case — unbounded retention is the point |
| iOS / macOS | mmap via `platform.posix` | **not the default.** Mapped dirty pages count against jetsam, and Data Protection can make the file unreadable while the device is locked. A phone should retain least anyway |
| wasmJs | chunked IndexedDB, or `Unavailable` | no filesystem exists; `availability()` is how a consumer learns this without a crash |

`platform.posix` interop is already proven in-tree — `NSFileManagerDurableStore` imports `memcpy`,
`rename`, `errno`.

**A bolt does not use `DurableStore`.** That SPI is whole-blob overwrite
(`write(key, bytes)`, fsync'd), which is why the segmented layout had to exist at all — segments
bound how much gets rewritten per record. An archive is append-only, and mmap is excellent for
append-at-an-offset and pointless for replace-the-whole-value.

## Wiring

A **decorator over the op stream**. The CRDT owner publishes the ops it applied; the decorator
consumes them. The exporter stays ignorant of archiving and the bolt stays ignorant of telemetry, so
the same decorator serves any `Rga`/`Fugue` owner rather than only the log exporter.

`WarpLogRecordExporter` does not publish applied ops today, but the tee point exists: `export()`
already holds them, returned from `insertAllAfter` / `removeFirst`.

## Failure semantics

**Best-effort, with a counted in-process health signal**, mirroring `ExporterHealth`. A node running
both replicas has the in-memory one as its source of truth, and a full archive disk must not take
down the application's logging. But silent loss is the inversion #1860 was about, so a failed append
is counted and readable in-process.

"Maximum safety" therefore means *the append is fsync'd before `append` returns* — not *the
application dies if it cannot be*.

## Testing

- **The firewall is the thing to pin.** A bolt fed a delta stream that includes a `Compact` must
  retain the ops that `Compact` suppresses. Mutation-check it: remove the discard and the test must
  redden.
- **Asymmetric retention end-to-end.** A small-retention live replica plus a large-retention bolt;
  window the live one; assert the bolt still replays the windowed records. This is the capability
  the whole module exists for and it must be pinned directly, not inferred.
- **The one-way edge.** There must be no API by which a bolt's contents reach a live replica other
  than an explicitly bounded replay. A compile-level absence is worth more than a test here.
- **Backends through one conformance suite**, per this repo's `SeamConformanceSuite` precedent — an
  in-memory bolt and an mmap bolt must pass the same suite.
- **Every amortisation or cost claim needs a control arm.** Order, contents and counts are identical
  whether an implementation batches or loops, so a test whose name contains a comparative must run
  the same input through the slow path and assert the fast one is strictly cheaper.

## Explicitly out of scope

- **Any change to `Rga`/`Fugue` semantics.** A bolt is additive; the live path is untouched.
- **Retention *policy*.** A bolt stores what it is given; deciding what to keep stays with the owner.
- **Anti-entropy or gossip from a bolt.** It is not a peer. This is the invariant, not a missing
  feature.
- **The CHAMP question (#2193 Phase 3A).** Measured at ~17% of the export path; independent of this
  module and now a weak case on its own merits.
