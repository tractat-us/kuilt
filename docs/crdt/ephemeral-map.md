# EphemeralMap — presence/awareness CRDT

`EphemeralMap<V>` is the kuilt-crdt primitive for live presence and awareness
state: each peer owns one slot holding a structured value (e.g. cursor position,
connection status, display name); slots appear expired in the read-time view when
no heartbeat has arrived within a TTL.

## Core design decision: ephemerality outside the lattice

A `Quilted` CRDT's `piece` operation is a join-semilattice least-upper-bound —
idempotent, commutative, associative. TTL expiry *removes* data, which would
violate those laws if modelled as a `piece` operation.

The resolution is a clean split:

- **Convergence** uses a per-peer logical `clock` and pure max-merge. Skew-free
  by construction.
- **Liveness** ("is this peer shown as present?") is a *read-time projection*
  computed from each peer's **local receive wall-clock** — never stored in the
  convergent state, never part of `piece`. Peers may disagree on liveness by
  milliseconds; that is correct for presence.
- **Compaction** is a coordinator-level concern; the lattice itself does not shrink.

## Slot merge rule

Each slot is tagged `(clock, present-bit)`. `piece` keeps the per-owner maximum
under:

1. Higher `clock` wins.
2. At equal `clock`, **present beats null** (a live value overrides a tombstone).

The present-over-null tiebreak exists because a liveness detector minting a
tombstone at `seenClock+1` can collide in clock with a live peer's next real
heartbeat. Resolving the tie by `origin` would be non-deterministic and could
briefly evict a live peer; present-over-null makes a live peer deterministically
override a false tombstone. A true departure stands because no `present` write at
that clock ever arrives.

## Departure protocol

Graceful leave and crash detection use the same mechanism: a `null`-valued slot
at `clock+1`. Graceful self-leave always wins cleanly (self increments past
whatever the detector could mint). Crash detection mints the tombstone on behalf
of the absent peer.

**Departure is permanent, not TTL-bounded.** The tombstone outranks every entry
the replica published before it, so no late re-delivery of one of those can bring
it back — the join discards it, and the tracker will not evict a tombstone to let
it in. A departed replica returns only by publishing at a clock strictly above its
own departure clock; a replica whose counter reset on restart needs the
`IncarnationClock` packing to do that.

## Freshness lives outside the lattice too

TTL expiry reads "this replica has gone quiet" from *when its update arrived
here*. That inference only holds if the update was published by the replica it
names — a relayed or re-exchanged frame says nothing about whether its author is
still alive. `EphemeralMapTracker` therefore takes updates on two channels:
`received` for author-fresh deltas (which may evict an expired slot so a restarted
replica is not pinned behind its own dead incarnation) and `relayed` for
anti-entropy, forwarded slots, and whole-state exchange (a pure join that never
evicts and refreshes a TTL only on a genuine advance).

Eviction is the tracker's one non-monotone step, so it is confined to the single
case that needs it: a presence entry re-opening an expired presence slot. It never
drops a tombstone, and never installs an entry the standing one already dominates
— either would invert the ordering the lattice exists to preserve.

## What `EphemeralMap` is not

It does not implement at-least-once delivery guarantees and does not participate
in the RGA causal-stability cut. It is a pure convergent CRDT for mutable,
short-lived presence data — not for durable application state.
