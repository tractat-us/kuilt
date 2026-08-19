# JsonCrdt

A full JSON document that merges. Objects use [ORMap](crdt-ormap.md) (add-wins keys), arrays use [Rga](crdt-rga.md) (stable insertion order), and scalar values use [MVRegister](crdt-mvregister.md) (surfaces conflicts). Concurrent edits at any depth — nested objects, array items, scalar fields — all resolve automatically.

**Converges to:** the same document on every replica — concurrently-added keys are all preserved (add-wins), array elements keep a stable order by insertion id, and concurrent scalar writes surface together as a multi-value the caller resolves.

## Structure: ORMap + RGA + MVRegister

```
JsonNode = Object(ORMap<String, JsonNode>)   // add-wins keys, recursive values
         | Array(Rga<JsonNode>)               // insertion-ordered, stable ids
         | Leaf(MVRegister<JsonValue>)        // scalar, multi-value on conflict
```

`piece` recurses: nested objects merge key-by-key, arrays union their operation logs, and leaves merge as multi-value registers. The three lattice laws (idempotent, commutative, associative) hold at every depth.

## Add-wins keys and multi-value leaves

A key added concurrently with a remove survives — the add wins. When two replicas write different scalars to the same key concurrently, the leaf becomes a multi-value register holding both, so no write is silently lost; the caller picks a winner.

## What each edit costs to send

Renaming the title of a long document should cost about as much as the title. So `set` and `remove`
hand you back **the change** — the one field you touched — and that is what travels to the other
devices. The size of what travels does not depend on how many fields the document holds. Editing one
field of a 1,000-field document sends 177 bytes rather than the 127 KB the whole document weighs.

In code, both mutators return a `Patch<JsonCrdt>`. Hand it to a replicator with
`quilter.mutate { it.set(key, node) }`; to hold the resulting whole document outside a replicator,
absorb the patch: `doc.piece { it.set(key, node) }`.

One thing this does *not* yet make cheap. Changing a field **inside** a nested object means
rebuilding that object and setting it at the top, so the frame is one key whose value is the whole
rebuilt subtree. A path-addressed edit would make that cost depend on the depth rather than the size
of the subtree; that is [issue 2469](https://github.com/tractat-us/kuilt/issues/2469).

## Code examples

**Ship the field, not the document:**

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleJsonCrdt -->
```kotlin
// B retitles the document and puts only that key on the wire. The body does not travel —
// that is the whole saving, and it holds however large the rest of the document gets.
val retitle = bravo.set("title", text(b, "Final"))
check(retitle.delta.keys == setOf("title"))
check(retitle.delta["body"] == null)
```


**Set and read a scalar:**

{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt" include-symbol="setThenGet" }

**Concurrent edits to a nested object both survive:**

{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt" include-symbol="nestedObjectMerge" }

**A concurrent add wins over a remove:**

{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt" include-symbol="addWinsOverConcurrentRemove" }

**Concurrent scalar writes surface as a multi-value:**

{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt" include-symbol="concurrentScalarWritesProduceMultiValue" }

## When to use

`JsonCrdt` fits collaborative JSON documents — config, metadata, document editors — where concurrent edits to nested structure must converge. For a flat key-value map, [LWWMap](crdt-lwwmap.md) is lighter; for an add-wins set of keys whose values are themselves CRDTs, use [ORMap](crdt-ormap.md) directly.
