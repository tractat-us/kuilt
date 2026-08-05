# ORSet

A set where elements can be added and removed. When two devices edit at the same time — one adds an item, another removes it — **add wins**. The item stays in.

**Converges to:** a set containing exactly the elements whose add operations have not been *causally dominated* by a subsequent remove from a replica that observed that add.

## Merge rule

Every `add(replica, element)` tags the element with a unique dot `(replica, counter)`. A `remove(element)` witnesses all dots currently associated with the element in the local state and marks them as removed. On merge:

- A dot present in only one replica's store (the other never saw it) is kept.
- A dot present in the store of A but in the causal context of B (B saw it and removed it) is dropped.

This is why add wins over a *concurrent* remove: a concurrent remove only witnessed dots it already had. A new dot minted by the concurrent add was never seen by the remover, so it survives.

## What each change costs to send

Ticking one item off a four-hundred-item list should cost about as much as one item, not four
hundred. So `add` and `remove` hand you back **the change** — the element you touched, and a short
note about which older versions of it this replaces — and that is what goes to the other devices.
The size of what travels does not depend on how big the set is.

In code, both mutators return a `Patch<ORSet<E>>`. Hand it to a replicator with
`quilter.mutate { it.add(replica, element) }`, which reads the current state under the replicator's
own lock and broadcasts only the delta. If you are holding a set outside a replicator and want the
resulting whole set, absorb the patch: `set.piece { it.add(replica, element) }`.

An add's delta names the dot it mints **and the dots that add supersedes**. That second part is not
an optimisation detail — leave it out and a later remove retires only the dot the remover knew
about, and the element comes back from the dead on every other device.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORSet -->
```kotlin
// Two peers have converged: "alice" is present on both, added by B.
var alpha = ORSet.empty<String>().piece { it.add(b, "alice") }
var bravo = alpha

// A re-adds "alice" and puts only the change on the wire. The delta names A's new dot
// *and* B's older one, which the re-add supersedes — so both peers drop the old dot.
val readd = alpha.add(a, "alice")
alpha = alpha.piece(readd)
bravo = bravo.piece(readd)
check(alpha == bravo)

// A concurrent add beats a concurrent remove: the remove can only retire the dots it saw.
val elsewhere = ORSet.empty<String>().piece { it.add(b, "alice") }
check(alpha.piece(alpha.remove("alice")).piece(elsewhere).contains("alice"))
```

## When to use

`ORSet` is the right choice for most concurrent set workloads where elements can be re-added after removal. Add-wins semantics are intuitive: a concurrent re-add "undoes" a concurrent remove. For permanent removal (tombstone-wins), see [TwoPhaseSet](crdt-twophaseset.md).
