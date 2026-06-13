# Causal primitives

The causal layer (`Causal`, `DotContext`, `DotSet`, `DotFun`, `DotMap`) is the foundation that `ORSet`, `MVRegister`, and `ORMap` are built on. Understanding it is optional for most users, but it explains *why* these CRDTs have their convergence properties.

## The causal stability barrier

A **dot** is a `(replicaId, counter)` pair — a unique identifier for a single event. A **causal context** (`DotContext`) is the set of all dots a replica has observed. It is the "what I have seen" part of a state.

A `Causal<S>` pairs a store `S` (the current data) with a causal context (what we have seen). The merge rule is:

- For each dot `d` in the universe: keep it in the store if and only if `d` is present in *at least one replica's store* **and** `d` is NOT present in the *other replica's causal context* (i.e. the other side never saw and therefore never removed it).

This rule gives add-wins semantics: a dot added by one replica and not yet observed by another is kept on merge.

## Code examples

**Add wins over concurrent remove (causal level):**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CausalDotSetTest.kt#addWinsOverConcurrentRemove -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CausalDotSetTest.kt
// Test: addWinsOverConcurrentRemove
// Alice removed the only dot she saw; her context still remembers (A,1).
val alice = Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L)))
// Bob concurrently added a fresh dot; he still holds both.
val bob = Causal(
    DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
    DotContext.of(Dot(a, 1L), Dot(b, 1L)),
)
val merged = alice.piece(bob)
// (A,1): Alice saw & dropped -> gone. (B,1): Alice never saw -> kept.
assertEquals(setOf(Dot(b, 1L)), merged.store.dots)
```

**Remove wins when the add was already seen:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CausalDotSetTest.kt#removeWinsWhenTheAddWasAlreadySeen -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CausalDotSetTest.kt
// Test: removeWinsWhenTheAddWasAlreadySeen
val alice = Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L), Dot(b, 1L)))
val bob = Causal(
    DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
    DotContext.of(Dot(a, 1L), Dot(b, 1L)),
)
assertTrue(alice.piece(bob).store.isBottom)
```

## DotFun and DotMap

`DotFun<V>` is a map from dots to values — the backing structure for `MVRegister`. `DotMap<K, S>` is a map from keys to causal stores — the backing structure for `ORMap`. Both compose with `Causal<>` to get add-wins merge for free.

These types are exposed in the public API and are needed when building custom CRDT types on top of the causal layer. For most consumers, the higher-level types (`ORSet`, `MVRegister`, `ORMap`) are sufficient.
