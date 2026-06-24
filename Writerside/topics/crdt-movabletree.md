# Movable Tree

Imagine a shared folder where you and a colleague can both reorganise files at the same time — even when one of you is on a plane with no internet. You move a folder under a new parent; they move the same folder somewhere else. When you reconnect, you end up with the same folder structure. No "merge conflict" dialog. No one's change is silently lost, and the hierarchy is never broken into a cycle.

That's what `MovableTree` is for: a replicated hierarchy where anyone can reparent any node at any time, offline or concurrent, and every device converges to the same valid tree.

**Converges to:** the same acyclic tree on every replica, regardless of the order moves were received.

## Why concurrent moves are hard

A plain "last writer wins" rule fails almost immediately for trees. Take two nodes A and B. Alice moves A under B while Bob — working offline at the same time — moves B under A. If you apply both moves naively, you get a cycle: A is under B, and B is under A. The tree is broken.

The same hazard appears with a single node moved by two people to different parents: whoever's move lands second may contradict the first in ways that leave one replica's tree inconsistent with another's.

The challenge is finding a rule that:
1. Makes both replicas agree on *one* final parent for every node.
2. Never produces a cycle, no matter what concurrent moves happened.
3. Requires no coordination — replicas can apply moves in any order and still converge.

## The mechanism: move-log union and deterministic replay

`MovableTree` records every move as a `MoveOp` stamped with a logical timestamp `(ts, replicaId)`. Merging two replicas takes the **union** of their move-logs, then **replays** every op from that union in a strict total order — smallest timestamp first, `replicaId` lexicographically (ascending) as the tie-break.

At each step of the replay, the algorithm asks: *would applying this op create a cycle?* Specifically, would the node being moved become an ancestor of its proposed new parent in the tree built from ops replayed so far? If yes, the op is skipped. If no, the parent map is updated.

Because every replica replays the same union in the same order and applies the same cycle check, every replica arrives at the same tree. The three semilattice laws hold:

- **Idempotent** — absorbing the same delta twice changes nothing; the same `(ts, replicaId)` op is deduplicated before replay.
- **Commutative** — it doesn't matter whether Alice absorbs Bob's patch or Bob absorbs Alice's first; the union is the union.
- **Associative** — follows from set-union and deterministic replay.

## Concurrent moves: the winning op

When two replicas independently move the same node (or create a cycle together), the higher-timestamped op wins. Ties on timestamp break on `replicaId` — lexicographically ascending, so a larger `replicaId` supersedes a smaller one at the same instant.

The losing op is not discarded — it stays in the log. On merge, it participates in the union but is skipped by the replay (it's the move that would have lost the conflict). The winning op stands alone, and the result is a single parent for that node across all replicas.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleMovableTree -->

```kotlin
val alice = ReplicaId("alice")
val bob = ReplicaId("bob")

// Shared initial state: root → A, root → B, root → C.
val base = MovableTree.empty<String>()
val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
val (t3, idC) = t2.addNode(alice, ts = 3L, parent = MovableTree.ROOT_ID, value = "C")

// Alice moves A under B (ts=4); Bob moves A under C (ts=5). Both diverge from t3.
val (aliceState, alicePatch) = t3.move(alice, ts = 4L, node = idA, newParent = idB)
val (bobState, bobPatch)     = t3.move(bob,   ts = 5L, node = idA, newParent = idC)

// Each replica absorbs the other's delta.
val mergedByAlice = aliceState.piece(bobPatch)
val mergedByBob   = bobState.piece(alicePatch)

// Convergence guaranteed: both arrive at the same tree.
check(mergedByAlice == mergedByBob)

// Bob's ts=5 wins — A ends up under C.
check(mergedByAlice.parentOf(idA) == idC)
```

Here Bob's move has a higher timestamp (`ts=5` vs. `ts=4`), so A ends up under C on both replicas. Alice's move is recorded in the log and participates in the replay, but the cycle check skips it once Bob's higher-priority move has already placed A under C (applying Alice's move at that point would give A two parents — but since the replay is sequential, the first move to apply determines the parent, and only the *later* timestamp is allowed to override it).

## Cycle prevention in detail

The cycle check runs against the tree as it exists at that point in the replay — the "effective" state built from all ops with a lower timestamp that were not themselves skipped. This means: when two concurrent moves together would form a cycle (Alice moves A under B while Bob moves B under A), the lower-priority move gets to run first (smaller timestamp), and then the higher-priority move is checked against the tree that now includes the first move. One of the two will always be a cycle and be skipped; the other stands. The result is always a valid tree.

Moving a node to itself is also a cycle and is silently skipped.

## The honest constraint: unbounded log growth

The move-log grows by one entry for every `move` or `addNode` call. Safe garbage collection — pruning old ops from the log — requires *causal stability*: an op can only be dropped once every peer has received and applied it (i.e. the op's timestamp falls below the minimum of all replicas' delivered vectors). Determining that requires coordination at the transport layer, such as the delivered-vector gossip that `RgaGcCoordinator` uses for [RGA](crdt-rga.md).

That coordination is not yet wired up for `MovableTree`. For short-lived sessions, small trees, or infrequent reparenting, this is not a concern. For long-running trees with continuous moves, expect log size to grow linearly with the total number of moves ever made.

## When to use

Use `MovableTree` for hierarchical data where any peer can reparent any node: file systems, task hierarchies, scene graphs, card piles. For flat collections, see [ORSet](crdt-orset.md) or [GSet](crdt-gset.md). For ordered sequences within a tree level, combine with [RGA](crdt-rga.md).
