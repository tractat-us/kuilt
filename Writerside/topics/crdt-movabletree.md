# Movable Tree

A shared folder hierarchy — files, tasks, scene objects — where anyone can move anything and all devices end up with the same tree, with no cycles, no coordination.

**Converges to:** the same acyclic tree on every replica, regardless of the order moves were received.

## The key idea

Every move is tagged with a logical timestamp `(ts, replicaId)`. Merging two replicas takes the **union** of their move-logs and replays it in timestamp order. Before applying each move, the algorithm checks whether it would introduce a cycle — if it would, the move is skipped. Because the replay is deterministic, every replica arrives at the same tree.

This is the algorithm from Kleppmann et al., "A highly-available move operation for replicated trees" (IEEE TPDS 2021).

## Concurrent moves

When two replicas concurrently move the same node to different parents, the higher-timestamped move wins. Ties break on `replicaId` (lexicographic). The losing move is recorded but skipped during replay, so the tree never has two parents for one node.

## Cycle prevention

Moving node A under node B is safe only when B is **not** a descendant of A. When concurrent moves together would form a cycle (Alice moves A under B, Bob moves B under A), the lower-priority move is skipped and the higher-priority one stands. The result is always a valid tree.

## Move-log GC

The move-log grows with every operation. Safe garbage collection requires *causal stability* — an op can be dropped only once every replica has received and applied it. This is not yet wired to the transport layer; the log is unbounded in the current implementation. For short-lived sessions or small trees this is not a concern.

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

## When to use

Use `MovableTree` for hierarchical data where any peer can reparent any node: file systems, task hierarchies, scene graphs, card piles. For flat collections, see [ORSet](crdt-orset.md) or [GSet](crdt-gset.md). For ordered sequences within a tree level, combine with [RGA](crdt-rga.md).
