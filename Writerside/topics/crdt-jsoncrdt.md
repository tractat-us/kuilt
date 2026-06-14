# JsonCrdt

A recursive CRDT over an arbitrary JSON document. It composes three lower-level CRDTs: objects are an [ORMap](crdt-ormap.md), arrays are an [Rga](crdt-rga.md), and scalar leaves are an [MVRegister](crdt-mvregister.md). Concurrent edits at any depth merge structurally and converge.

**Converges to:** the same document on every replica — concurrently-added keys are all preserved (add-wins), array elements keep a stable order by insertion id, and concurrent scalar writes surface together as a multi-value the caller resolves.

## Structure: ORMap + Rga + MVRegister

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

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt#setThenGet -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt
// Test: setThenGet
val doc = JsonCrdt.empty(a).set("name", str("Alice"))
assertIs<JsonNode.Leaf>(doc["name"])
assertEquals(setOf(JsonValue.Str("Alice")), (doc["name"] as JsonNode.Leaf).register.values)
```

**Concurrent edits to a nested object both survive:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt#nestedObjectMerge -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt
// Test: nestedObjectMerge
val base = JsonCrdt.empty(a).set("profile", JsonNode.Object(ORMap.empty()))
val docA = base.set("profile", obj(a, "name" to str("Alice")))
val docB = base.withReplica(b).set("profile", obj(b, "age" to num(30.0)))
val merged = docA.piece(docB)
val profile = assertIs<JsonNode.Object>(merged["profile"])
assertEquals(setOf("name", "age"), profile.map.keys)
```

**A concurrent add wins over a remove:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt#addWinsOverConcurrentRemove -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt
// Test: addWinsOverConcurrentRemove
val base = JsonCrdt.empty(a).set("x", str("hello"))
val docA = base.remove("x")
val docB = base.withReplica(b).set("x", str("world"))
val merged = docA.piece(docB)
assertContains(merged.keys, "x")
```

**Concurrent scalar writes surface as a multi-value:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt#concurrentScalarWritesProduceMultiValue -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt
// Test: concurrentScalarWritesProduceMultiValue
val base = JsonCrdt.empty(a)
val docA = base.set("flag", JsonNode.Leaf(MVRegister.empty<JsonValue>().set(a, JsonValue.Str("x"))))
val docB = base.withReplica(b).set("flag", JsonNode.Leaf(MVRegister.empty<JsonValue>().set(b, JsonValue.Str("y"))))
val merged = docA.piece(docB)
val leaf = assertIs<JsonNode.Leaf>(merged["flag"])
assertEquals(setOf(JsonValue.Str("x"), JsonValue.Str("y")), leaf.register.values)
```

## When to use

`JsonCrdt` fits collaborative JSON documents — config, metadata, document editors — where concurrent edits to nested structure must converge. For a flat key-value map, [LWWMap](crdt-lwwmap.md) is lighter; for an add-wins set of keys whose values are themselves CRDTs, use [ORMap](crdt-ormap.md) directly.
