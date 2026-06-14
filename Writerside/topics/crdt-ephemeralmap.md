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

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt#putAddsEntry -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt
// Test: putAddsEntry
val m = EphemeralMap.empty<String>().put(a, "cursor", clock = 1L)
assertEquals("cursor", m.entries[a]?.value)
assertEquals(1L, m.entries[a]?.clock)
```

**Later clock wins within a replica's slot:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt#laterClockWins_sameReplica -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt
// Test: laterClockWins_sameReplica
val m1 = EphemeralMap.empty<String>().put(a, "old", clock = 1L)
val m2 = EphemeralMap.empty<String>().put(a, "new", clock = 2L)
assertEquals("new", m1.piece(m2).entries[a]?.value)
assertEquals("new", m2.piece(m1).entries[a]?.value) // commutative
```

**Rejoin beats a stale departure:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt#presenceWithHigherClockWinsOverStaleDeparture -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt
// Test: presenceWithHigherClockWinsOverStaleDeparture
val departed = EphemeralMap.empty<String>().leave(a, clock = 1L)
val rejoined = EphemeralMap.empty<String>().put(a, "back", clock = 3L)
val merged = departed.piece(rejoined)
assertEquals("back", merged.entries[a]?.value)
```

**A stale entry is evicted from the live view:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt#expiredEntryIsEvicted -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt
// Test: expiredEntryIsEvicted
val m = EphemeralMap.empty<String>().put(a, "stale", clock = 1L)
val receiveTime = mapOf(a to 0L)
val live = m.live(receiveTime, now = 6000L, ttlMs = 5000L)
assertFalse(a in live)
```

## When to use

`EphemeralMap` is the right choice for transient, self-owned state that should disappear on silence — presence, cursors, activity — where each replica unilaterally controls its slot and you cannot trust peers' wall clocks. For durable replicated data, reach for the other CRDTs in this zoo; for a last-write-wins map of persistent values, see [LWWMap](crdt-lwwmap.md).
