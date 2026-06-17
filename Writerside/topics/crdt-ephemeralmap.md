# EphemeralMap

A presence/awareness CRDT for transient per-replica state — cursor positions, "who's online", typing indicators. Each replica owns exactly one slot, ordered by a per-replica clock, and entries expire when a caller-supplied TTL lapses without a refresh.

**Converges to:** a map where each replica's slot holds the value carried by the highest clock that replica has emitted (or a tombstone if it left gracefully), with stale slots dropped locally once their TTL elapses.

## One slot per replica, clock-ordered

A replica writes only its own slot. Updates to that slot are ordered by a monotonically increasing per-replica `clock`; the higher clock always wins. Clocks are **never** compared across replicas, so wall-clock skew between peers is irrelevant — different replicas' slots are independent and simply union on merge.

## Graceful departure and rejoining

Writing `leave(replica, clock)` records a null value at a higher clock — a tombstone that signals the peer left on purpose (the Yjs awareness pattern). A later non-null `put` at a still-higher clock means the peer rejoined. Because the merge is driven purely by clock comparison within the slot, it is commutative regardless of arrival order.

## Expiry: caller-supplied receive times and TTL

`EphemeralMap` holds no clock of its own. To compute the live view, the caller passes a map of per-replica *receive* times, the current `now`, and a `ttlMs`; `live(...)` returns only the slots seen within the window (and never a departed/null slot). Expiry is therefore a function of local receive time, not of any cross-peer wall clock — see `EphemeralMapTracker` for tracking those receive times.

## Code examples

**Write a presence value:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt" include-symbol="putAddsEntry" }

**Later clock wins within a replica's slot:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt" include-symbol="laterClockWins_sameReplica" }

**Rejoin beats a stale departure:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt" include-symbol="presenceWithHigherClockWinsOverStaleDeparture" }

**A stale entry is evicted from the live view:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt" include-symbol="expiredEntryIsEvicted" }

## When to use

`EphemeralMap` is the right choice for transient, self-owned state that should disappear on silence — presence, cursors, activity — where each replica unilaterally controls its slot and you cannot trust peers' wall clocks. For durable replicated data, reach for the other CRDTs in this zoo; for a last-write-wins map of persistent values, see [LWWMap](crdt-lwwmap.md).
