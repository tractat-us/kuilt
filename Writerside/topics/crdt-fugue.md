# Fugue (`Fugue`)

An ordered list for collaborative text and sequences — the successor to `Rga`. When two people type at the same spot at the same time, Fugue keeps each person's run of characters together rather than mixing them character-by-character.

**Converges to:** the same ordered sequence on every replica, regardless of the order concurrent insertions and removals arrived. Unlike `Rga`, Fugue additionally guarantees that concurrently-inserted runs are never interleaved.

## The key idea

Every element gets a globally unique id = `(lamportTimestamp, replicaId)`. Like `Rga`, insertions say "place this after element X" rather than "place it at index 3." The difference is in how concurrent insertions from different devices are ordered:

- **`Rga`** tracks only the left neighbour. When two replicas both insert at the same position, their characters can end up interleaved (Alice types "abc", Bob types "XYZ", merged result might be "aXbYcZ").
- **`Fugue`** tracks both the left neighbour and the right neighbour at insert time, building a tree whose depth-first traversal guarantees each run stays together (result is "abcXYZ" or "XYZabc" — never interleaved).

This property — **maximal non-interleaving** — is proven formally in Weidner et al., "The Art of the Fugue" (arXiv:2305.00583, 2023). Fugue is the first sequence CRDT with such a proof.

## Tombstone-based removal

`removeAt(index)` adds a tombstone to the op-log; it does not delete the element's op. `toList()` renders only non-tombstoned elements in sequence order.

## Usage

```kotlin
<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleFugue -->
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

## Vs. `Rga`

| | `Rga` | `Fugue` |
|---|---|---|
| Left-origin tracking | ✓ | ✓ |
| Right-origin tracking | — | ✓ |
| Non-interleaving guarantee | ✗ (can interleave) | ✓ (maximal non-interleaving) |
| GC / compaction | ✓ via `compact` | Not yet (no GC in current impl) |

Use `Fugue` when concurrent collaborative editing is the use-case and you need the non-interleaving property. For sequences where GC/compaction of old tombstones is important, `Rga` currently has richer GC support.

## Serialization

Use `Fugue.wireSerializer(serializer<T>())` rather than the compiler-generated serializer when wiring into a `Quilter` — the generated serializer fails for CBOR because it defaults to `PolymorphicSerializer(Any::class)` for the element type.

## When to use

Use `Fugue` for ordered sequences where:
- Concurrent insertions from multiple replicas must not interleave (collaborative text editing, comment threads, chat logs).
- Deletions are possible.

For unordered collections, prefer [ORSet](crdt-orset.md) or [GSet](crdt-gset.md). For sequences where GC matters more than non-interleaving guarantees, consider [Rga](crdt-rga.md).
