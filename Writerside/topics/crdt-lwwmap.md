# LWWMap

A last-write-wins map: a per-key `LWWRegister`. Each key resolves concurrently according to timestamp; keys are independent.

**Converges to:** a map where each key holds the value written at the highest `(timestamp, replicaId)` for that key.

## Merge rule

`LWWMap` is a map from key to `LWWRegister<V>`. `piece` merges each key's register independently using the LWW rule. Keys with no conflict simply union.

## Code examples

**Set and read:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt" include-symbol="setReturnsTheValue" }

**Per-key LWW: later timestamp wins:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt" include-symbol="perKeyLwwSemantics_laterWins" }

**Different keys compose independently:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWMapTest.kt" include-symbol="differentKeysComposeIndependently" }

## When to use

`LWWMap` is a good fit for converging metadata — display names, preferences, labels — where per-key last-write-wins semantics are acceptable. For a map whose keys are ORSet-managed (add-wins on key presence) and whose values merge via their own CRDT, use [ORMap](crdt-ormap.md).
