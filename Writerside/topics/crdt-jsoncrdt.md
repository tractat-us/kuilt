# JsonCrdt

A recursive CRDT over an arbitrary JSON document. It composes three lower-level CRDTs: objects are an [ORMap](crdt-ormap.md), arrays use [RGA (`Rga`)](crdt-rga.md), and scalar leaves are an [MVRegister](crdt-mvregister.md). Concurrent edits at any depth merge structurally and converge.

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

## Code examples

**Set and read a scalar:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt" include-symbol="setThenGet" }

**Concurrent edits to a nested object both survive:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt" include-symbol="nestedObjectMerge" }

**A concurrent add wins over a remove:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt" include-symbol="addWinsOverConcurrentRemove" }

**Concurrent scalar writes surface as a multi-value:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt" include-symbol="concurrentScalarWritesProduceMultiValue" }

## When to use

`JsonCrdt` fits collaborative JSON documents — config, metadata, document editors — where concurrent edits to nested structure must converge. For a flat key-value map, [LWWMap](crdt-lwwmap.md) is lighter; for an add-wins set of keys whose values are themselves CRDTs, use [ORMap](crdt-ormap.md) directly.
