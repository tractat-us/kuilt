# EphemeralMap — presence/awareness CRDT with TTL heartbeats

Design spec for issue #309. A presence/awareness CRDT for `:kuilt-crdt`: each
peer owns one slot holding its live presence value; slots expire from the *view*
if no heartbeat arrives within a TTL. Unlike the durable CRDT zoo this models
live presence, not persisted state.

## Core decision: ephemerality lives outside the lattice

A `Quilted` CRDT's `piece` is a join-semilattice least-upper-bound and MUST stay
idempotent / commutative / associative — that is what makes convergence robust
to kuilt's drop/duplicate/reorder frame delivery. TTL expiry *removes* data, so
it can never be a `piece` operation without breaking those laws.

The resolution is a clean split:

- **Convergence** uses a per-peer logical `clock` and a pure max-merge. Provably
  correct, skew-free.
- **Liveness** ("is this peer shown as present?") is a *read-time projection*
  computed from each peer's **local receive wall-clock** — never stored in the
  convergent state, never part of `piece`. Peers may disagree on liveness by
  milliseconds; that is correct for presence.

This split also settles issue #309's three open questions:

| Open question | Resolution |
|---|---|
| Wall-clock TTL vs. logical-clock expiry | **Both, layered:** logical clock for convergence; local wall-clock for the liveness view. Skew can never silently drop a live peer. |
| Graceful departure: `null`-state+clock vs. explicit `Leave` | **`null`-state + bumped clock.** One mechanism for all departures (graceful = self-authored, crash = detector-authored); rides the existing slot, no new message type. |
| TTL eviction in the CRDT vs. the replicator | **Neither.** Liveness is a read-time view; memory reclamation is a coordinator-level compaction. `piece` is untouched. |

## §1 — Type & lattice

```kotlin
public class EphemeralMap<V> {              // keyed implicitly by owner ReplicaId
    // state: Map<ReplicaId, Slot<V>>
    // Slot(clock: Long, value: V?)         // value == null  ⇒  tombstone (departed)

    public val slots: Map<ReplicaId, Slot<V>>     // raw, incl. tombstones — for the coordinator
    public fun set(owner: ReplicaId, clock: Long, value: V?): EphemeralMap<V>
    public fun piece(other: EphemeralMap<V>): EphemeralMap<V>
}
```

**Ownership granularity:** one value per peer (Yjs-Awareness shape), not a
keyed sub-namespace. `V` is a structured payload (e.g.
`data class CursorPresence(pos, status, name)`). App code writes only its **own**
slot; a peer cannot write another peer's presence value (the one exception is the
system-level tombstone, §3).

**Slot tag and merge order.** Each slot is tagged `(clock, present-bit)` and
`piece` keeps the per-owner maximum under:

1. higher `clock` wins;
2. at equal `clock`, **`present` beats `null`** (a real value beats a tombstone).

Rationale for the present-over-null tie-break: a detector minting a tombstone at
`seenClock+1` can collide in clock with a genuinely-alive peer's next real
heartbeat at the same value of `clock`. Resolving that collision by `origin`
(the `LWWRegister` approach) is non-deterministic and could briefly evict a live
peer. Present-over-null makes a live peer deterministically override a false
tombstone, while a *true* departure stands because no `present` write at that
clock ever arrives. Graceful self-leave wins cleanly anyway (`clock+1 > seen`).

`origin` is **not** part of the tag: only the owning peer ever writes a
`present` value to its own slot, and a peer never reuses a `clock`, so two
distinct `present` values can never collide at one `(owner, clock)`. Two
concurrent detector tombstones collide only as `(clock, null)` vs `(clock, null)`
— equal values, trivially convergent.

`piece` is a max over a total order on `(clock, present-bit)` and is therefore
idempotent, commutative, and associative — a valid join-semilattice.
`EphemeralMap` exposes no `causalDots()` (it is not an op-log CRDT); it does not
participate in the RGA causal-stability cut.

## §2 — Liveness as a read-time projection

The convergent state never expires; expiry is a view the *coordinator* computes,
never stored:

- The coordinator keeps a non-replicated `Map<ReplicaId, Long>` of
  `localReceivedAtMillis`, stamped whenever a slot's `clock` **advances**
  (observed by diffing successive `state` emissions; the owner's own slot is
  stamped on local `apply`).
- `presence = slots.filter { (_, slot) -> slot.value != null && now - localRecv[owner] < ttl }`
  mapped to `owner -> slot.value`.

A `null` slot (tombstone) is excluded regardless of freshness, so a
detector-authored tombstone need not affect `localRecv`. `clock` is logical
(drives convergence); the TTL comparison is purely local wall-clock, so NTP-class
skew between peers cannot drop a live peer from anyone's view.

## §3 — Heartbeat & departure

- **Heartbeat** — republish the owner's own slot at `clock+1`, both on value
  change and every `heartbeatInterval` to refresh remote TTL. Default interval
  ≈ TTL/2 (Yjs uses 15 s heartbeat / 30 s TTL; final defaults in §4).
- **Graceful leave** — self-write `null` at `clock+1`. Propagates departure
  faster than waiting out the TTL.
- **Crash (ungraceful)** — a peer observing another peer TTL-silent writes `null`
  at `seenClock+1` for the dead peer and broadcasts it (a *detector-minted
  tombstone*). This is a **coordinator-level** operation, the only write to a
  foreign slot; it is analogous to the replicator evicting an absent peer, not
  an application action. It makes "C departed" converge uniformly across peers
  rather than each peer timing out at a slightly different instant.

The present-over-null tie-break (§1) guarantees a false tombstone (peer was only
partitioned, not dead) is overridden by that peer's next real heartbeat.

## §4 — The coordinator

A single class wrapping `SeamReplicator<EphemeralMap<V>>`, analogous to
`RgaGcCoordinator`. Working name: **`EphemeralMapCoordinator`** (final name TBD —
candidates: `PresenceCoordinator`, `AwarenessCoordinator`). Responsibilities:

1. **Heartbeat ticker** — periodic `apply(set(self, clock+1, value))`.
2. **Presence view** — `presence: StateFlow<Map<ReplicaId, V>>`, recomputed from
   the replicator's `state` and the local-receive map (§2). The primary public
   read surface.
3. **Silence detection → tombstone** — a periodic sweep mints a detector
   tombstone (§3) for any owner whose slot is `present` but TTL-silent.
4. **Compaction** — drops `null` slots from local memory after a grace (§5).

Configuration (a `data class`, à la `SeamReplicatorConfig`):

| Param | Default (proposed) | Meaning |
|---|---|---|
| `ttl` | 30 s | A slot is `present` only if heard from within this window. |
| `heartbeatInterval` | 10 s (≈ TTL/3) | Re-publish cadence; comfortably < TTL so one dropped beat doesn't expire a live peer. |
| `silenceSweepInterval` | ≈ TTL/2 | How often silence-detection runs. |
| `compactionGrace` | 2 × TTL | How long a `null` slot lingers before local memory drop. |

Per the repo coroutine-determinism rule, the coordinator takes an injectable
dispatcher / clock (production default) so tests drive it under
`UnconfinedTestDispatcher(testScheduler)` with a fake clock rather than a real
production dispatcher under `runTest`.

## §5 — Compaction & the one constraint

Memory reclamation is **local-grace**, not ack-gated. Each peer independently
drops a `null` slot from its own memory once the slot has been a tombstone for
`compactionGrace`. This is safe **because of** read-time liveness: if a
redelivered old delta or a late `FullState` resurrects the slot, the liveness
filter hides it instantly (stale `localRecv`, or `null` value) and it is
re-compacted. Resurrection is never observable in `presence`.

This deliberately does **not** lead with `universalAckFlow`-gated compaction.
`universalAckFlow` only covers *this* replica's own deltas, so only the detector
that authored a tombstone could use it; every other peer received the tombstone
but it is not their delta. Local-grace is uniform across all peers and needs no
cross-peer coordination. (An authoring detector *may* additionally drop on
`universalAck` to reclaim a touch sooner — an optional optimization, not the
mechanism.)

**Constraint to document on the public API:** a reclaimed `ReplicaId` must not
return with a **reset** clock. If peer C departs (tombstoned, then compacted) and
later reconnects reusing the same `ReplicaId` but restarting its clock at a low
value, a still-partitioned peer holding C's old tombstone at `clock = K+1` would
swallow C's revival (`null` at `K+1` beats `present` at `1`). Reconnects must
either (a) resume C's monotonic clock, or (b) mint a fresh `ReplicaId`. Clock
continuity / identity across reconnect is the session layer's reconnect-token
concern; this CRDT documents the requirement and does not enforce it at runtime
(consistent with the tag-uniqueness preconditions already documented on
`LWWRegister` / `LWWMap`).

## §6 — Placement, serialization, tests

**Module:** `:kuilt-crdt` (all targets). New files:

- `EphemeralMap.kt` — the CRDT + `Slot`.
- `EphemeralMapCoordinator.kt` — the coordinator + its config.
- A `@Serializable` wire form (CBOR via the replicator), mirroring how the other
  zoo CRDTs serialize. `V` is generic, so the coordinator threads the value
  serializer the same way `Rga` does.

**Tests:**

- Lattice laws (idempotent / commutative / associative) and the present-over-null
  tie-break, via the existing CRDT-law test pattern.
- Coordinator behavior under `UnconfinedTestDispatcher` + injected clock:
  heartbeat refresh keeps a peer present; TTL silence drops it from `presence`;
  a false tombstone is overridden by a live peer's next heartbeat; graceful leave
  removes immediately; `null` slots compact after the grace; a resurrected slot
  is never visible in `presence`.
- Convergence over a fabric is already covered by the generic replicator/seam
  suites; `EphemeralMap` is just another `Quilted<S>` payload there.

## Out of scope / deferred

- **Presence history** (windowed "C was active N ms ago") — would need the
  append-op-log model that this overwrite-slot design intentionally rejects.
- **Ack-gated compaction as primary** — local-grace is sufficient; revisit only
  if a concrete need for deterministic global reclamation timing appears.
- **Cross-`ReplicaId` reconnect identity** — owned by the session layer; this
  spec only states the constraint.
