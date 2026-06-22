# Scaling to many peers

When only a handful of devices share a session, every device can simply talk to
every other one. That stops working as the group grows: with a hundred peers,
"everyone talks to everyone" means each device juggles a hundred connections and
every change is sent a hundred times.

**Gossip** fixes this — and it works just like gossip does. Each device keeps in
touch with only a small **handful** of others — its neighbours — and updates spread
from neighbour to neighbour until they reach everyone. The work each device does
grows with the size of its handful, not with the size of the whole group. In a small
session it makes no difference; in a large one it's the difference between practical
and not.

You opt in by wrapping your existing connection in a `GossipSeam`. Everything else —
the CRDTs, [Quilter](crdt-quilter.md), your application code — works exactly as
before, because a `GossipSeam` is still just a [`Seam`](contract.md).

## When you need it

| Session size | What to use |
|--------------|-------------|
| A few peers (a card game, a shared doc with a small group) | A plain `Seam` — gossip adds nothing. |
| Dozens to low hundreds of peers | Wrap it in a `GossipSeam`. |

If you're not sure, start without it. Adding gossip later is a one-line change at
the point where you build the seam — nothing downstream changes.

## How to use it

Wrap the seam you already have, start the gossip layer on a scope you own, then
build your [Quilter](crdt-quilter.md) over the `GossipSeam` instead of the raw seam —
pointing its delta targets at the active-neighbour view:

```kotlin
// Wrap the underlying full-membership seam.
val gossip = GossipSeam(
    base = seam,
    random = Random(seed),         // seeded per peer — picks this peer's neighbours
    clock = { Clock.System.now() },
)
gossip.start(scope)

// Replicate over the gossip seam; garbage-collect against the ~k neighbours,
// not all N peers — this is the scaling win.
val quilter = Quilter(
    seam = gossip,
    initial = GCounter.ZERO,
    valueSerializer = GCounter.serializer(),
    scope = scope,
    deltaTargets = { gossip.activePeers.value },
)
```

That `deltaTargets = { gossip.activePeers.value }` line is the whole point: it tells
Quilter to track acknowledgements from the handful of neighbours rather than from
every peer, which is what keeps memory and bandwidth flat as the group grows. A
background safety net (Quilter's anti-entropy reconcile) still guarantees **everyone**
converges, even peers outside the neighbour set — so the neighbour network only has
to be *usually* connected, not perfectly so.

## The two views

A `GossipSeam` exposes two views of the room, and they answer different questions:

| View | What it is | Use it for |
|------|-----------|------------|
| `activePeers` | The ~k neighbours this peer exchanges updates with | Delta targets, "who am I gossiping with" |
| `peers` | Everyone in the room (delegated from the base seam) | Full membership, the anti-entropy backstop's sampling pool |
| `spares` | A short standby list | Inspecting failover state; promoted automatically on neighbour loss |

`broadcast` floods only to `activePeers`; a normal `sendTo` still reaches any peer
directly. Collect `incoming` exactly once, as with any seam.

## How it works underneath

Three mechanisms combine. None needs a global coordinator:

- **A k-regular partial view.** Each peer derives its neighbours as a seeded random
  *k-out* sample of the room roster, with `k ≈ ln N` (concretely
  `max(4, ⌈ln N⌉ + 2)`, so ~4–7 neighbours for tens-to-hundreds of peers). Because
  the choices are random, the union of everyone's neighbours is a single connected
  network with high probability — updates can reach everyone even though no one is
  connected to everyone. The view heals on membership change (with a small per-peer
  jitter so peers don't all recompute at once) and promotes a spare when a neighbour
  drops.
- **Eager-flood dissemination.** A `broadcast` is stamped with its origin and a
  sequence number and flooded to the sender's neighbours. Each receiver delivers it
  to the app **once** (duplicates are recognised and dropped), then re-floods to its
  *own* neighbours minus whoever it just heard from. The deduplication is what stops
  the flood; a hop-count budget is only a backstop against pathological loops. So a
  broadcast walks the whole network device-to-device along ~k edges instead of being
  sent N times by the origin.
- **An anti-entropy backstop.** Periodically each peer reconciles full state with one
  random peer. This is the safety net that makes everything else able to be
  *approximate*: anything a flood drops, or any peer temporarily outside the neighbour
  graph, is caught up here. It's why the flood doesn't need its own reliability
  machinery.

For the measured scaling numbers — and the optimizations that are deliberately *not*
built yet — see [Performance and Scaling](performance.md).

## Why not HyParView or Plumtree

If you know the gossip literature, the natural reference points are **HyParView**
(partial-view membership) and **Plumtree** (spanning-tree broadcast with lazy
repair). kuilt deliberately uses simpler variants of both, because two assumptions
those protocols are built to *avoid* are things kuilt already has:

- HyParView's value is building a connected overlay when **no node knows the full
  membership**, at thousands of peers. kuilt has a roster (`Seam.peers`) and targets
  tens-to-low-hundreds, so a roster-derived random view is enough — HyParView's
  shuffle/forward-join protocol would be complexity with no payoff here. (kuilt does
  borrow HyParView's one genuinely useful idea: the spare list.)
- Plumtree's tree-repair earns its complexity only when the broadcast layer must be
  **reliable on its own**. kuilt's broadcast doesn't have to be: the anti-entropy
  backstop already guarantees convergence, so a plain flood-with-dedup is enough and
  the tree-repair machinery falls away.

The full evaluation against the literature is in
[`docs/gossip-mesh-design.md`](https://github.com/tractat-us/kuilt/blob/main/docs/gossip-mesh-design.md).
