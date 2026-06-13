# LWWMap

A last-write-wins map: a per-key `LWWRegister`. Each key resolves concurrently according to timestamp; keys are independent.

**Converges to:** a map where each key holds the value written at the highest `(timestamp, replicaId)` for that key.

## Merge rule

`LWWMap` is a map from key to `LWWRegister<V>`. `piece` merges each key's register independently using the LWW rule. Keys with no conflict simply union.

## Code examples

**Set and read:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt#setReturnsTheValue -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt
// Test: setReturnsTheValue
val m = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
assertEquals("en", m["lang"])
assertEquals(mapOf("lang" to "en"), m.entries)
```

**Per-key LWW: later timestamp wins:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt#perKeyLwwSemantics_laterWins -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt
// Test: perKeyLwwSemantics_laterWins
val m1 = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
val m2 = LWWMap.empty<String, String>().set(b, 20L, "lang", "fr")
assertEquals("fr", m1.piece(m2)["lang"])
assertEquals("fr", m2.piece(m1)["lang"])  // commutative
```

**Different keys compose independently:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt#differentKeysComposeIndependently -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt
// Test: differentKeysComposeIndependently
val m1 = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
val m2 = LWWMap.empty<String, String>().set(b, 5L, "tz", "UTC")
val merged = m1.piece(m2)
assertEquals("en", merged["lang"])
assertEquals("UTC", merged["tz"])
```

## When to use

`LWWMap` is a good fit for converging metadata — display names, preferences, labels — where per-key last-write-wins semantics are acceptable. For a map whose keys are ORSet-managed (add-wins on key presence) and whose values merge via their own CRDT, use [ORMap](crdt-ormap.md).
