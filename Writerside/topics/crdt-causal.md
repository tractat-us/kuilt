# Causal primitives

Most users can ignore this page and use `ORSet`, `MVRegister`, and `ORMap` directly.
Come here when you want to understand the data model underneath those types, or
when you want to build your own CRDTs on the same foundation.

The causal layer (`Causal`, `DotContext`, `DotSet`, `DotFun`, `DotMap`) is what
gives those higher-level types their convergence behavior.

## The causal stability barrier

A **dot** is a `(replicaId, counter)` pair — a unique identifier for a single event. A **causal context** (`DotContext`) is the set of all dots a replica has observed. It is the "what I have seen" part of a state.

A `Causal<S>` pairs a store `S` (the current data) with a causal context (what we have seen). The merge rule is:

- For each dot `d` in the universe: keep it in the store if and only if `d` is present in *at least one replica's store* **and** `d` is NOT present in the *other replica's causal context* (i.e. the other side never saw and therefore never removed it).

This rule gives add-wins semantics: a dot added by one replica and not yet observed by another is kept on merge.

## Code examples

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleCausal -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// Alice removed the only dot she saw; her context still remembers (A,1).
val alice = Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L)))
// Bob concurrently added a fresh dot; he still holds both.
val bob = Causal(
    DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
    DotContext.of(Dot(a, 1L), Dot(b, 1L)),
)
val merged = alice.piece(bob)
// (A,1): Alice saw & dropped -> gone. (B,1): Alice never saw -> kept.
check(merged.store.dots == setOf(Dot(b, 1L)))
check(!merged.store.isBottom)  // present — add wins
```

## DotFun and DotMap

`DotFun<V>` is a map from dots to values — the backing structure for `MVRegister`. `DotMap<K, S>` is a map from keys to causal stores — the backing structure for `ORMap`. Both compose with `Causal<>` to get add-wins merge for free.

These types are exposed in the public API and are needed when building custom CRDT types on top of the causal layer. For most consumers, the higher-level types (`ORSet`, `MVRegister`, `ORMap`) are sufficient.
