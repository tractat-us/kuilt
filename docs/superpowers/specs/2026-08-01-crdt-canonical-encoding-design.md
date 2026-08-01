# Canonical CRDT encoding — enforce it, then fix what it catches

_Design for [#1957](https://github.com/tractat-us/kuilt/issues/1957). Written 2026-08-01._

## The property

> **A CRDT's serialized form is a function of its logical value, not of its history or its host platform.**

Two replicas that have converged must encode to identical bytes — on the same target, and on
different targets.

kuilt does not hold this property today. Five zoo types violate it, and one violation is a genuine
JVM-vs-Native divergence.

## Evidence

Each type below was built twice — the same logical value, reached by two different merge orders —
and CBOR-encoded on JVM and on `macosArm64`.

| Type | Two merge orders, one target | Same value, JVM vs macOS |
|---|---|---|
| `GSet` | diverges (both targets) | consistent |
| `TwoPhaseSet` | diverges (both targets) | consistent |
| `GCounter` | JVM stable · **macOS diverges** | **different bytes** |
| `PNCounter` | JVM stable · **macOS diverges** | **different bytes** |
| `LWWMap` | JVM stable · **macOS diverges** | **different bytes** |
| `ORSet` | canonical | identical |
| `MVRegister` | canonical | identical |

A `GCounter` holding `{r1:1, r2:1, r3:1}` encodes its keys `r2, r3, r1` on the JVM and
`r1, r2, r3` on macOS.

Two distinct root causes:

- **`kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/MapMerge.kt:5`** — `mergeValues` and
  `mergeMax` build a `HashMap`, so iteration follows hash-bucket order. That is a per-platform
  implementation detail, which is why the counters only *looked* canonical under a JVM-only test.
  Bucket order also depends on capacity, so JVM stability is incidental, not guaranteed.
- **`GSet` / `TwoPhaseSet`** — plain `Set<E>` under the auto-generated serializer. `piece` is
  `elements + other.elements`, producing a `LinkedHashSet` in merge order.

`ORSet` and `MVRegister` are already clean because `DotMapSerializer` / `DotSetSerializer` sort
before encoding.

## Why it matters

Convergence itself is not broken — both encodings decode to the same value, so the `Quilter` wire
path is correct today. What is broken is every use that treats the encoding as an identity:

- **[#1955](https://github.com/tractat-us/kuilt/issues/1955) (Merkle anti-entropy) is impossible
  until this is fixed.** A digest over a non-canonical encoding reports permanent false divergence
  between a JVM peer and a Native peer — anti-entropy would never quiesce.
- Content-addressed dedup, cache keys, or any signature over state are unsound.
- The conformance suite is structurally blind to it: it asserts `a == b`, and `Set`/`Map` equality
  is order-insensitive precisely where the encoding is not.

## Design

### 1. The check extends `CrdtConvergenceHarness`

`CrdtConvergenceHarness.assertAllPermutationsConverge` already constructs the exact scenario — one
logical value reached by every delivery permutation, across 32 seeds — and asserts
`result == canonical`. The byte check is one more assertion in the same loop:

```kotlin
check(encoded(result) contentEquals encoded(canonical)) { … }
```

That yields ~192 byte assertions per type (32 seeds × 6 permutations at `replicaCount = 3`), running
on JVM, Android, iOS, macOS and wasmJs. No new suite, and no separate subclass a new CRDT could
forget to add.

`CrdtConvergenceHarness` takes `serializer: KSerializer<S>` as a **required** constructor parameter.
A nullable serializer would gate a functional code path, which `~/.claude/CLAUDE.md`'s "Optional ≠
tuning" rule bans. Cost: 13 existing call sites updated; the `IntMax` self-validation fixture gains
`@Serializable`.

`GSet` and `TwoPhaseSet` have no `CrdtConvergenceSuite` subclass today. Both get one — they are two
of the five violators, so the check must reach them.

### 2. Canonical serializers in `:kuilt-crdt`

One hand-written `KSerializer` per violating type, each mirroring `DotMapSerializer`: sort entries
with `serialKeyComparator(elementSerializer)`, then delegate to the stock `MapSerializer` /
`ListSerializer`.

`serialKeyComparator` (`crdt/internal/SerialKeyComparator.kt`, from
[#752](https://github.com/tractat-us/kuilt/issues/752)) already derives a total order for any
serializable key by encoding it to primitive leaves and comparing lexicographically. Nothing new is
needed and `:kuilt-crdt`'s depends-only-on-kotlinx-serialization rule is untouched.

**Fix at the serializer, not at `MapMerge`.** Making `MapMerge` emit a sorted map would require a
comparator on `K` at every merge site and would change `toString()`, `entries` iteration order, and
consumer-visible behaviour. The property under test is about the *wire form*; the serializer is
where it belongs, and it is the established precedent.

Known list, expected to grow: `GSet`, `TwoPhaseSet`, `GCounter`, `PNCounter`, `LWWMap`. The probe
covered seven types; the harness covers thirteen. `Rga`, `Fugue`, `MovableTree`, `JsonCrdt`,
`EphemeralMap`, `ORMap` and `BoundedCounter` are unprobed and some are expected to go red.

### 3. Golden vectors pin the cross-target property

A `commonTest` test in `:kuilt-crdt` holding a hex string per type, produced from a fixed
construction sequence — the `HeddlePolicyGoldenVectorTest` pattern.

`commonTest` compiles and runs on every target, so **this is the cross-target check**; no
cross-process or multi-runtime harness is required. This is the piece that catches the `GCounter`
JVM-vs-macOS drift, which no single-target test can see.

### 4. Digest helper

`canonicalDigest(serializer, value): Long` in `:kuilt-conformance` — FNV-1a-64 over the canonical
CBOR encoding.

The convergence assertion compares **raw bytes**, not digests: no collision risk, and a failure
prints the diverging encodings instead of two unequal numbers. The digest exists for the callers
#1957 names — cross-process and real-socket tests that would rather ship one small value than a
whole state, and a periodic divergence alarm between live peers.

`Murmur3` already exists at `crdt/internal/Murmur3.kt` but is `internal`. Widening `:kuilt-crdt`'s
public API for a test-side diagnostic is the wrong trade against ~15 lines of FNV-1a in the module
that actually needs it.

`:kuilt-conformance` gains `kotlinx-serialization-cbor` as an `api` dependency. It is a published
module, so this is a real if minor addition to its surface.

## Non-goals

- **No change to `MapMerge` or to any in-memory iteration order.** Consumers who observe a
  platform-dependent `toString()` or `entries` order are out of scope; file separately if it bites.
- **No wire-format change.** Sorting reorders map entries and array elements; every serializer keeps
  its existing structural layout, so encodings stay decodable by older peers.
- **No Merkle tree.** That is #1955, which this de-risks but does not implement.
- **No digest on `Quilted` and no hash dependency in `:kuilt-crdt`.**

## Testing

- **Order-independence** — the harness assertion, every permutation × 32 seeds × every target.
- **Cross-target parity** — golden vectors in `:kuilt-crdt` `commonTest`.
- **Round-trip** — `decode(encode(a)) == a` for each new serializer, so sorting does not corrupt.
- **Regression proof** — for each of the five fixes, confirm the new assertion fails before the
  serializer lands and passes after (TDD step 3: revert, watch it go red, restore).

The full `./gradlew build` is the gate. `jvmTest` is a proven false green here: it is exactly what
hid the `GCounter` divergence.

## Risks

- **The red list may grow past five.** Enumerating the true set requires running the check against
  all thirteen convergence subclasses. Discovery is the first task, not an assumption.
- **A type may be genuinely hard to canonicalize** — `Rga` and `Fugue` carry order-bearing sequences
  where sorting is wrong by construction. If one resists, it gets an explicit documented exemption
  plus a follow-up issue, never a silent skip.
- **Golden vectors are brittle by design.** Any deliberate serializer change moves every vector.
  That is the point; the KDoc must say so, as `HeddlePolicyGoldenVectorTest` does.
