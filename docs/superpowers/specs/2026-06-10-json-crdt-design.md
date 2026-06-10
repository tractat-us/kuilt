# `JsonCrdt` — a recursive CRDT over arbitrary JSON

**Status:** design approved, not yet built.
**Date:** 2026-06-10.
**Issue:** #310.

## What this is

A new rung in the `:kuilt-crdt` zoo: `JsonCrdt`, a CRDT that models an arbitrary
JSON document with correct, structural conflict resolution by composing the
primitives the module already ships — `ORMap` for objects, `Rga` for array
order, `MVRegister`-style causal registers for typed leaves. A consumer gets
replicated, eventually-consistent JSON-shaped state without choosing CRDTs by
hand.

The driver is **craft and conceptual completeness**, consistent with the rest of
the module: get the lattice laws right for *all* cases — including type changes
and concurrent array-element edits — rather than ship a naive sketch with
silent holes.

## Guiding decisions (settled in brainstorming)

1. **Conceptual completeness over a quick v1.** No documented-correctness holes;
   the hard corners (heterogeneous-type merge, deep-merge of array elements) are
   handled, not punted.
2. **Array elements deep-merge (Automerge-style).** An array is decomposed into
   *order* + *content*: `Rga<ElementId>` for position, `ORMap<ElementId, JsonNode>`
   for the elements themselves, so concurrent edits to the *same* element merge
   recursively.
3. **Object-key assignment deep-merges (structural join).** Concurrent
   `x = {a:1}` and `x = {b:2}` converge to `{a:1, b:2}` — the natural structural
   join, matching the existing `ORMap` value-merge behavior. Nodes have **no**
   per-assignment identity / NodeId table (this is the one place we diverge from
   Automerge, deliberately).
4. **Mutation API = structural core + path facade.** A lawful immutable node API
   is the primitive; a thin JSON-Pointer-style path layer is the ergonomic
   convenience over it.
5. **Value model = kotlinx `JsonElement`.** Leaves carry `JsonPrimitive`;
   whole-tree `fromJson` / `value()` bridges `kotlinx.serialization.json`. Adds a
   `kotlinx-serialization-json` dependency to `:kuilt-crdt`.

## The load-bearing insight: every node is a causal register

The issue's sketch — `JsonNode = JsonObject | JsonArray | JsonLeaf`, "merge is
structural" — is **not convergence-correct for type changes**. These are
delta-state CRDTs: an old delta is re-merged repeatedly, in any order, with
duplicates. Consider a replica that sets `x = {a:1}` and later (observing the
object) sets `x = 5`. A bare structural `piece(object, scalar)` carries no
version information, so it cannot tell that `5` *supersedes* the object — it
would resurrect a spurious type-conflict on every re-merge.

The minimal fix that makes cross-type merge lawful: **every `JsonNode` is a tiny
causal register over typed content**. This generalizes the "leaf = `MVRegister`"
idea up to all three node kinds. The register's dots give the causal dominance
that lets an observed overwrite supersede; concurrent assignments of *different*
kinds survive as a genuine conflict; concurrent assignments of the *same* kind
fold structurally.

## Data model

Module: new file `JsonCrdt.kt` in `:kuilt-crdt` (+ a hand-written serializer).

**Naming.** `JsonObject` / `JsonArray` would clash with
`kotlinx.serialization.json.JsonObject` / `JsonArray`, which we import for
interop. So the public document type is **`JsonCrdt`**; the recursive node is
**`JsonNode`**; the content variants are **`ObjContent` / `ArrContent` /
`ScalarContent`** (not exported under clashing names).

```
JsonCrdt : Quilted<JsonCrdt>            // the document; its root is always an object
  └─ root: JsonNode (always ObjContent)

JsonNode : Quilted<JsonNode>            // a causal register over typed content
  └─ reg: Causal<DotFun<Content>>       // dot → typed content; supplies supersede + conflict

Content =
    ObjContent(ORMap<String, JsonNode>)
  | ArrContent(seq: Rga<ElementId>, items: ORMap<ElementId, JsonNode>)   // order + content
  | ScalarContent(JsonPrimitive)

ElementId                               // stable array-element identity (a Dot), minted on insert
```

`ElementId` decouples an array element's *content identity* (the key into the
`items` map, which deep-merges) from its *order identity* (the `RgaId` the `Rga`
assigns internally). The `Rga` element value **is** the `ElementId`; insert mints
a fresh `ElementId` and writes content under it.

## Merge semantics (`JsonNode.piece`)

1. **Causal-merge the register** (`DotFun` join over `Causal`): dominated
   assignments are dropped, mutually-concurrent ones survive.
2. **Group** surviving assignments by content kind (`Obj` / `Arr` / `Scalar`).
3. **Fold within each kind** structurally:
   - `ObjContent` → `ORMap.piece` (keys union; matching keys recurse into
     `JsonNode.piece`).
   - `ArrContent` → `Rga.piece` for order **and** `ORMap.piece` for items (each
     element recurses).
   - `ScalarContent` → kept as a **set** of distinct primitives (concurrent
     scalar writes are a multi-value, surfaced not silently dropped).
4. **Materialize:**
   - **One kind survives** → that node (object / array / scalar; the scalar
     possibly multi-valued).
   - **Multiple kinds survive** → a genuine **type-conflict**: `toJson`
     materializes a deterministic winner (the highest-dot kind); `conflicts(path)`
     exposes every typed value.

The result is total over all variant pairs and lawful — idempotent,
commutative, associative — because each component (`DotFun` causal join, `ORMap`,
`Rga`, set-union) is, and the by-kind grouping is order-independent.

## Mutation API

### Structural core (the lawful primitive)

Immutable; threads `ReplicaId`; returns new state.

```kotlin
// JsonCrdt — document level
fun JsonCrdt.value(): JsonObject                                  // materialize whole doc
companion object {
    fun empty(): JsonCrdt
    fun fromJson(replica: ReplicaId, json: JsonObject): JsonCrdt  // seed from plain JSON
}

// JsonNode — structural ops (return a new node)
fun JsonNode.put(replica: ReplicaId, key: String, child: JsonNode): JsonNode    // object
fun JsonNode.removeKey(key: String): JsonNode
fun JsonNode.insertAt(replica: ReplicaId, index: Int, child: JsonNode): JsonNode // array
fun JsonNode.removeAt(index: Int): JsonNode
fun JsonNode.setScalar(replica: ReplicaId, value: JsonPrimitive): JsonNode        // overwrite → scalar

// constructors
JsonNode.obj() / JsonNode.arr() / JsonNode.scalar(value: JsonPrimitive)
```

### Path facade (convenience over the core)

JSON-Pointer-style paths (`/players/0/hp`); auto-creates intermediate objects
(lawful — concurrent auto-create of the same path deep-merges).

```kotlin
fun JsonCrdt.set(replica: ReplicaId, path: String, value: JsonElement): JsonCrdt // scalar or subtree
fun JsonCrdt.remove(replica: ReplicaId, path: String): JsonCrdt
fun JsonCrdt.get(path: String): JsonElement?
fun JsonCrdt.conflicts(path: String): List<JsonElement>          // type/scalar conflicts at a slot
```

An array index in a path addresses the **current visible position at call
time**, resolved to the stable `ElementId` under the hood — matching
`Rga.insertAt` / `removeAt` semantics.

## Interop

`fromJson` / `value()` / `toJson()` bridge whole
`kotlinx.serialization.json.JsonElement` trees:

- `JsonObject` → `ObjContent`, `JsonArray` → `ArrContent`, `JsonPrimitive` →
  `ScalarContent`.
- `JsonNull` is a **scalar value** (distinct from an absent key), per JSON.

## Serialization (wire)

A hand-written `JsonCrdtSerializer`, mirroring the existing `RgaSerializer`
pattern. The recursive `JsonNode` / `Content` / `DotFun` / `ORMap` / `Rga` tree
needs explicit serializer threading to avoid the CBOR
`PolymorphicSerializer(Any::class)` trap that bit `Rga` (see `RgaSerializer`
KDoc). Expose a `JsonCrdt.wireSerializer()` entry point for wiring into a
`SeamReplicator`, parallel to `Rga.wireSerializer`.

## Testing

Matches the zoo's existing rigor.

- **Lattice laws** (idempotent / commutative / associative) — property tests
  under `jvmTest/property/`, parallel to `RgaLawsPropertyTest`.
- **Scenarios:**
  - concurrent object keys deep-merge (`{a:1}` ⊓ `{b:2}` → `{a:1,b:2}`);
  - concurrent same-array-element edits deep-merge;
  - type-overwrite supersedes with **no** spurious conflict on re-merge of the
    superseded delta;
  - concurrent type-change surfaces as a conflict (`conflicts(path)` non-empty,
    deterministic `toJson` winner);
  - concurrent scalar writes → multi-value.
- **Round-trip:** `fromJson(x).value() == x`; CBOR wire round-trip via
  `wireSerializer`.
- **Convergence under reordered / duplicated delta delivery** — the kuilt
  frame-semantics invariant.

## Scope — explicitly out for v1

- **Subtree move ops** (the open research corner the issue cites,
  arXiv 2311.14007). File a follow-up.
- **Register GC / compaction** of superseded assignment dots. Like RGA
  tombstones, the register retains dominated/concurrent assignments until a later
  write collapses them; a long-lived doc with many overwrites grows. Note as a
  known growth point and file a follow-up, rather than solve it in v1.

## Why this fits kuilt

State-based CRDT merge is robust to dropped, duplicated, and reordered frames by
construction — exactly kuilt's fabric world. `JsonCrdt` inherits that:
`JsonCrdt.piece` is a lawful join, so any two replicas that absorb the same
*set* of deltas — in any order, with any repeats — converge to the same JSON.
