# Canonical CRDT encoding — enforce it, then fix what it catches

_Design for [#1957](https://github.com/tractat-us/kuilt/issues/1957). Written 2026-08-01._

## The property

> **A CRDT's serialized form is a function of its logical value — not of its merge history, and not
> of the platform it runs on.**

Two replicas that have converged must encode to identical bytes: on one target, and across targets.

kuilt does not hold this property. **Nine zoo types violate it**, and seven of the nine are
invisible to a JVM-only test run.

## Evidence

Each type was driven through the `CrdtConvergenceHarness` scenario — three replicas, eight random
ops each, all six delivery permutations, eight seeds — with a CBOR byte-equality check added. Run on
JVM and on `macosArm64`.

| Type | JVM | macosArm64 | Root cause |
|---|---|---|---|
| `GSet` | ❌ | ❌ | B |
| `TwoPhaseSet` | ❌ | ❌ | B |
| `GCounter` | ✅ | ❌ | A |
| `PNCounter` | ✅ | ❌ | A |
| `LWWMap` | ✅ | ❌ | A |
| `ORMap<String, GCounter>` | ✅ | ❌ (39/48 permutations) | A, via its values |
| `EphemeralMap` | ✅ | ❌ (40/48) | A |
| `BoundedCounter` | ✅ | ❌ (40/48) | A |
| `MovableTree` | ✅ | ❌ (40/48) | A |
| `LWWRegister`, `ORSet`, `MVRegister`, `Rga`, `Fugue`, `DotContext` | ✅ | ✅ | — |

State equality held in every case (`stateMismatch=0`). Convergence is correct; only the *encoding*
diverges.

### Root cause A — `MapMerge` builds a `HashMap`

`kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/MapMerge.kt:5,17` — both `mergeValues` and
`mergeMax` return a `HashMap`, so iteration follows hash-bucket order. That is a per-platform
implementation detail: `java.util.HashMap` and Kotlin/Native's `HashMap` spread hashes differently,
and bucket order also depends on capacity, so even JVM stability is incidental rather than
guaranteed.

A `GCounter` holding `{r1:1, r2:1, r3:1}` encodes its keys `r2, r3, r1` on the JVM and `r1, r2, r3`
on macOS.

Every root-cause-A type reaches `HashMap` through `MapMerge`: `GCounter`/`PNCounter`/`LWWMap`
directly, `EphemeralMap` at `EphemeralMap.kt:186`, `MovableTree` at `MovableTree.kt:209`
(`seqByReplica`), `BoundedCounter` at `BoundedCounter.kt:108` (`transfers`), and `ORMap` transitively
through whatever map-backed value type it carries.

### Root cause B — plain `Set<E>` merged with `+`

`GSet.piece` is `elements + other.elements` and `TwoPhaseSet` is two such sets. `+` yields a
`LinkedHashSet` in merge order, so the encoding is order-dependent on every target.

## Prior art, and why it gave false assurance

[#713](https://github.com/tractat-us/kuilt/issues/713) already established this property and built
`kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalSerializationTest.kt`, plus the
sorting serializers (`DotContextSerializer`, `DotSetSerializer`, `DotMapSerializer`,
`RgaSerializer`, `FugueSerializer`) that keep the dot-based family clean. That work is sound; the
audit simply stopped at the dot-based family.

The nine violators are the types #713 never reached — **and one of them has a passing test that
cannot fail**. `orMapSerializationIsDeliveryOrderIndependent` builds its values as
`GCounter.of(a to 1L)`: a **single-entry** map has exactly one iteration order, so no `HashMap`
ordering defect can manifest. The test is under-powered, not wrong. This is why the design puts the
check in the randomized permutation harness rather than adding more hand-written pairs.

## Why it matters

The `Quilter` wire path is correct today — both encodings decode to the same value. What is unsound
is every use that treats the encoding as an identity:

- **[#1955](https://github.com/tractat-us/kuilt/issues/1955) (Merkle anti-entropy) is impossible
  until this is fixed.** A digest over a non-canonical encoding reports permanent false divergence
  between a JVM peer and a Native peer, so anti-entropy would never quiesce.
- Content-addressed dedup, cache keys, and any signature over state are unsound.
- The existing conformance suite is structurally blind: it asserts `a == b`, and `Set`/`Map` equality
  is order-insensitive exactly where the encoding is not.

## Design

### 1. Two shared canonical serializers, not nine bespoke ones

The root causes are two, so the fix is two:

- `CanonicalMapSerializer<K, V>` — sorts entries by `serialKeyComparator(kSerializer)`, delegates to
  `MapSerializer`.
- `CanonicalSetSerializer<E>` — sorts elements by `serialKeyComparator(eSerializer)`, emits via
  `ListSerializer` (the `DotSetSerializer` wire shape).

Both live in `:kuilt-crdt` and are built on `serialKeyComparator`
(`crdt/internal/SerialKeyComparator.kt`, from
[#752](https://github.com/tractat-us/kuilt/issues/752)), which already derives a total order for any
serializable key by encoding it to primitive leaves and comparing lexicographically. No new
dependency; `:kuilt-crdt`'s depends-only-on-kotlinx-serialization rule is untouched.

They are then applied per field via `@Serializable(with = …)` across the eight violating types —
a uniform one-line change per field rather than a hand-written serializer class per type.

**Fix at the serializer, not at `MapMerge`.** Making `MapMerge` emit a sorted map would need a
comparator on `K` threaded through every merge site, and would change `toString()` and `entries`
iteration order for consumers. The property under test is about the wire form; that is where the fix
belongs, and it is the precedent #713 set.

### 2. The check extends `CrdtConvergenceHarness`

`CrdtConvergenceHarness.assertAllPermutationsConverge` already builds the exact scenario — one
logical value reached by every delivery permutation, over 32 seeds — and asserts `result ==
canonical`. The byte check is one more assertion in the same loop:

```kotlin
check(encoded(result) contentEquals encoded(canonical)) { … }
```

That is ~192 byte assertions per type (32 seeds × 6 permutations at `replicaCount = 3`) on JVM,
Android, iOS, macOS and wasmJs. It found four violators that the hand-written pair tests missed, so
it earns its place over adding more cases to `CanonicalSerializationTest`.

`CrdtConvergenceHarness` takes `serializer: KSerializer<S>` as a **required** constructor parameter.
A nullable one would gate a functional code path, which the "Optional ≠ tuning" rule bans. Cost: 13
existing call sites updated; the `IntMax` self-validation fixture gains `@Serializable`.

`GSet` and `TwoPhaseSet` have no `CrdtConvergenceSuite` subclass today. Both get one — they are
violators, so the check must reach them.

Two types expose their serializer as `wireSerializer(vSer)` rather than `serializer(vSer)` (`Rga`,
`Fugue` — `FugueSerializer` is `internal`). Call sites must use the right accessor.

### 3. Golden vectors pin the cross-target property

A `commonTest` test in `:kuilt-crdt` holding a hex string per type, from a fixed construction
sequence — the `HeddlePolicyGoldenVectorTest` pattern.

The harness check proves *order*-independence separately on each target. It does **not** prove that
two targets agree. Only a pinned byte string does, and that is the dimension #1957 asks for.
`commonTest` compiles and runs on every target, so this *is* the cross-target check — no
cross-process or multi-runtime harness is needed.

### 4. Digest helper

`canonicalDigest(serializer, value): Long` in `:kuilt-conformance` — FNV-1a-64 over the canonical
CBOR encoding.

The harness assertion compares **raw bytes**, not digests: no collision risk, and a failure prints
the diverging encodings instead of two unequal numbers. The digest serves the callers #1957 names —
cross-process and real-socket tests that would rather ship one small value than a whole state, and a
periodic divergence alarm between live peers.

`Murmur3` exists at `crdt/internal/Murmur3.kt` but is `internal`. Widening `:kuilt-crdt`'s public API
for a test-side diagnostic is the wrong trade against ~15 lines of FNV-1a in the module that needs it.

`:kuilt-conformance` gains `kotlinx-serialization-cbor` (`commonTest` for the harness usage;
`commonMain` if the digest ships in main).

## Non-goals

- **No change to `MapMerge` or to any in-memory iteration order.** A consumer observing a
  platform-dependent `toString()` or `entries` order is a separate concern; file it if it bites.
- **No wire-format change.** Sorting reorders map entries and array elements; every serializer keeps
  its structural layout, so encodings stay decodable by peers running older versions.
- **No Merkle tree.** That is #1955, which this de-risks but does not implement.
- **No digest on `Quilted`, and no hash dependency in `:kuilt-crdt`.**

## Testing

- **Order-independence** — the harness assertion: every permutation × 32 seeds × every target.
- **Cross-target parity** — golden vectors in `:kuilt-crdt` `commonTest`.
- **Round-trip** — `decode(encode(a)) == a` per changed type, so sorting does not corrupt.
- **Regression proof** — per fix, confirm the harness assertion fails before the serializer change
  and passes after (TDD step 3: revert, watch it redden, restore).
- **Under-powered-sample guard** — `orMapSerializationIsDeliveryOrderIndependent` gains a
  multi-entry value so it can actually fail; leaving it as-is would preserve a green test that
  proves nothing.

`./gradlew build` is the gate. **`jvmTest` is a proven false green here** — it is exactly what hid
seven of the nine violations. Every task must be verified on `macosArm64` at minimum.

## Risks

- **`MovableTree` and `BoundedCounter` are the least certain.** Their states nest logs and maps, so
  a single field annotation may not be sufficient. Both are behind root cause A, so start from
  `seqByReplica` / `transfers` and re-run the sweep rather than assuming.
- **Golden vectors are brittle by design.** A deliberate serializer change moves every vector. That
  is the point; the KDoc must say so, as `HeddlePolicyGoldenVectorTest` does.
- **Fixing a serializer changes bytes on the wire** for the affected types. Pre-1.0 with no
  cross-version compatibility guarantee, so this is acceptable, but it should be called out in the
  PR rather than discovered by a consumer.
