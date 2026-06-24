# Fugue (`Fugue`)

Imagine two people editing the same document while offline — Alice types a sentence at the
top, Bob types a different sentence at the same spot. When they reconnect, a naive merge
might shred both sentences, scattering letters from each word across the other. Fugue
prevents that. Each person's run of characters stays together as one contiguous block, no
matter how many devices were writing at the same place at the same time.

That single guarantee — **runs of text don't get jumbled** — is what makes Fugue the right
choice whenever humans collaborate on ordered content.

**Converges to:** the same ordered sequence on every replica, regardless of the order
concurrent insertions and removals arrived. Unlike [Rga](crdt-rga.md), Fugue additionally
guarantees that concurrently-inserted runs are never interleaved.

## The problem with naive merging

When two people insert at the same position, the system has to decide where each new
character goes relative to the other's characters. The simplest rule — "sort by author
id, character by character" — produces a result like `AaBbCc` from `ABC` (Alice) and
`abc` (Bob). Each letter is technically in the right place, but the two runs are
completely interwoven. Nobody can read either person's contribution.

[Rga](crdt-rga.md) improves on the simplest rule, but it only remembers each
element's *left neighbour* at insert time. When Alice types three characters in a row
and Bob independently types three characters into the same gap, Rga has no information
to tell it those characters belong together — it can end up interleaving them.

Fugue solves this by having each insertion record **both** its left and right context.

## How Fugue keeps runs together

Every element in a Fugue sequence carries:

- A globally unique id — `(lamportTimestamp, replicaId)` — used for deterministic
  tiebreaking.
- Its **left origin**: which element it was inserted after.
- Its **right origin**: which element was immediately to its right when it was inserted.
  This is the key difference from Rga — knowing what was on the right as well as the
  left lets the tree place the element unambiguously.

These two origins define the element's place in an implicit tree. Each consecutive
insertion by the same person naturally becomes a child of the previous one, forming a
chain. A depth-first traversal of the tree then reads out the sequence — and because
each person's chain is a parent-child path, the whole chain must appear contiguously in
the traversal. Concurrent insertions from different people land in different subtrees,
so their runs sit adjacent to each other but never interleaved.

This property — **maximal non-interleaving** — is proven formally in
Weidner et al., "The Art of the Fugue" (arXiv:2305.00583, 2023). Fugue is the first
sequence CRDT with such a proof.

### Concrete example

Alice types `"a1"`, `"a2"`, `"a3"` at position 0, offline. Bob independently types
`"b1"`, `"b2"` at the same position. When they sync:

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleFugue -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// Replica A builds a run: "a1", "a2", "a3" (each prepended before the prior front).
val (fA1, opA1) = Fugue.empty<String>().insertAt(a, 0, "a1")
val (fA2, opA2) = fA1.insertAt(a, 0, "a2")
val (fA3, opA3) = fA2.insertAt(a, 0, "a3")

// Replica B independently builds "b1", "b2" at the same position.
val (fB1, opB1) = Fugue.empty<String>().insertAt(b, 0, "b1")
val (fB2, opB2) = fB1.insertAt(b, 0, "b2")

// Merge all ops into both replicas.
val mergedByA = fA3.apply(opB1).apply(opB2)
val mergedByB = fB2.apply(opA1).apply(opA2).apply(opA3)

// Both converge to the same order.
check(mergedByA.toList() == mergedByB.toList())

// The A-run and B-run each form a contiguous block — no interleaving.
```

After the merge, the result might be `["a3", "a2", "a1", "b2", "b1"]` or
`["b2", "b1", "a3", "a2", "a1"]` — the tie between the two runs is broken
deterministically by replica id — but it will never be something like
`["a3", "b2", "a2", "b1", "a1"]`. Alice's run and Bob's run are each intact.

## The tree in more detail

The Fugue tree is rebuilt from the op-log on each materialisation. Its structure:

- **`FugueId.HEAD`** is the virtual root, sorting before every real element.
- Each element is either a **left child** or a **right child** of its parent.
  - A left child was inserted *after a descendant* of its parent. Left children are
    traversed before the parent.
  - A right child claims the space *immediately after* its parent. Right children are
    traversed after the parent. The right origin is recorded so concurrent right children
    can be sorted deterministically — the one whose right origin appeared later in the
    sequence at insert time is placed first.
- **Traversal** is depth-first: all left children (recursively), then the node itself,
  then all right children (recursively).

Left siblings are ordered by ascending `FugueId`. Right siblings are ordered in reverse
right-origin sequence order, with descending `FugueId` as a tiebreak.

## Tombstone-based removal

`removeAt(index)` records a `FugueOp.Remove` tombstone in the op-log; it does not delete
the element's original `Insert` op. The `Insert` must be kept because a later insertion
with `after = removedId` as its left origin still needs to find its parent in the tree.
Deleting the entry would break the tree structure for all subsequent elements that rooted
themselves there.

`toList()` filters out tombstoned elements and returns only the visible sequence in
traversal order.

## Vs. `Rga`

| | [Rga](crdt-rga.md) | `Fugue` |
|---|---|---|
| Left-origin tracking | ✓ | ✓ |
| Right-origin tracking | — | ✓ |
| Non-interleaving guarantee | ✗ (can interleave) | ✓ (maximal non-interleaving) |
| GC / compaction | ✓ via `RgaGcCoordinator` | Not yet (op-log grows unbounded) |

The op-log currently grows without bound. Garbage-collecting tombstones once they are
*causally stable* (every peer has seen the remove) requires causal-stability tracking,
which is not yet implemented. If bounded memory is important and the non-interleaving
guarantee is not required, [Rga](crdt-rga.md) currently has richer GC support.

## When to use

Use `Fugue` for ordered sequences where:
- Concurrent insertions from multiple replicas must not interleave — collaborative
  text editing, comment threads, ordered task lists.
- Deletions are possible.
- Op-log growth is acceptable for the workload (no long-running GC requirement yet).

For unordered collections, prefer [ORSet](crdt-orset.md) or [GSet](crdt-gset.md).
For sequences where compaction matters more than the non-interleaving guarantee, use
[Rga](crdt-rga.md).

## Serialization

Use `Fugue.wireSerializer(serializer<T>())` rather than the compiler-generated
serializer when wiring into a `Quilter`. The generated serializer fails for CBOR because
it defaults to `PolymorphicSerializer(Any::class)` for the element type.

The wire serializer sorts ops in canonical `FugueId` ascending order before encoding, so
two replicas holding the same logical state (same op set, delivered in different order)
produce byte-for-byte identical output. Both `FugueOp.Insert` and `FugueOp.Remove` carry
an `id` field that serves as the stable sort key.
