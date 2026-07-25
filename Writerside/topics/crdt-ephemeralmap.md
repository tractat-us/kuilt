# EphemeralMap

For transient presence data — cursor positions, "who's typing", online indicators. Each device owns one slot. Entries expire after a caller-supplied TTL if not refreshed, so stale presence disappears automatically.

**Converges to:** a map where each replica's slot holds the value carried by the highest clock that replica has emitted (or a tombstone if it left gracefully), with stale slots dropped locally once their TTL elapses.

## One slot per replica, clock-ordered

A replica writes only its own slot. Updates to that slot are ordered by a monotonically increasing per-replica `clock`; the higher clock always wins. Clocks are **never** compared across replicas, so wall-clock skew between peers is irrelevant — different replicas' slots are independent and simply union on merge.

## Graceful departure and rejoining

Writing `leave(replica, clock)` records a null value at a higher clock — a tombstone that signals the peer left on purpose (the Yjs awareness pattern). A later non-null `put` at a still-higher clock means the peer rejoined. Because the merge is driven purely by clock comparison within the slot, it is commutative regardless of arrival order.

Leaving is meant to stick. A message that was sent *before* the departure can still arrive afterwards — over a slow link, or when peers re-sync and hand each other everything they know — and that late arrival must not put the departed peer back on the list. It doesn't: the departure was recorded at a higher clock than anything the peer said earlier, so the older message loses. Coming back is something the peer has to do itself, by saying something newer than its own goodbye.

## Who told you, and when

Expiry answers "have we heard from this device lately?", so it only means something if the update really did come *from* that device. A copy passed along by somebody else, or a bulk re-sync of everything a peer knows, tells you nothing about whether the original device is still switched on. `EphemeralMapTracker` keeps the two apart: hand it a device's own update with `received`, and anything second-hand with `relayed` — the second one merges the data without restarting anybody's countdown unless it genuinely carries something newer.

## Expiry: caller-supplied receive times and TTL

`EphemeralMap` holds no clock of its own. To compute the live view, the caller passes a map of per-replica *receive* times, the current `now`, and a `ttlMs`; `live(...)` returns only the slots seen within the window (and never a departed/null slot). Expiry is therefore a function of local receive time, not of any cross-peer wall clock — see `EphemeralMapTracker` for tracking those receive times.

## Code examples

**Write a presence value:**

{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt" include-symbol="putAddsEntry" }

**Later clock wins within a replica's slot:**

{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt" include-symbol="laterClockWins_sameReplica" }

**Rejoin beats a stale departure:**

{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt" include-symbol="presenceWithHigherClockWinsOverStaleDeparture" }

**A stale entry is evicted from the live view:**

{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt" include-symbol="expiredEntryIsEvicted" }

## When to use

`EphemeralMap` is the right choice for transient, self-owned state that should disappear on silence — presence, cursors, activity — where each replica unilaterally controls its slot and you cannot trust peers' wall clocks. For durable replicated data, reach for the other CRDTs in this zoo; for a last-write-wins map of persistent values, see [LWWMap](crdt-lwwmap.md).
