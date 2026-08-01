# CRDT Canonical Encoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every CRDT's serialized form a function of its logical value — identical bytes for converged replicas, on one target and across targets — and enforce it so it cannot regress.

**Architecture:** Two shared sorting serializers (`CanonicalMapSerializer`, `CanonicalSetSerializer`) in `:kuilt-crdt`, applied per field to the nine violating types. Enforcement is a byte-equality assertion added to the existing `CrdtConvergenceHarness` permutation loop, plus `commonTest` golden vectors that pin the cross-target dimension the harness cannot see.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization (core + CBOR), kotlin-test, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-01-crdt-canonical-encoding-design.md`

## Global Constraints

- **`:kuilt-crdt` may depend only on `kotlinx-serialization-core`.** No new dependency in that module — not CBOR, not a hash library. `serialKeyComparator` and `Murmur3` already exist in-module.
- **`explicitApi()` is enforced.** Every new public declaration needs an explicit `public` modifier.
- **`jvmTest` is a false green for this work.** Seven of the nine defects are invisible on JVM. Every verification step runs `macosArm64Test` at minimum; the final gate is the full `./gradlew build`.
- **Use `detektAll`, never bare `detekt`.** Bare `detekt` is `NO-SOURCE` in this KMP setup and reports success without analyzing.
- **Verify cache-disabled before auto-merge:** `./gradlew :<module>:build detektAll --rerun-tasks`. Confirm tasks are `EXECUTED`, not `FROM-CACHE`.
- **Test methods take no `test` prefix.** `@Test` suffices. Multi-assert tests use `assertAll()`.
- **Never use the word "chore"** in a commit message or PR title.
- **Environment setup for every shell:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`
- **Two types expose `wireSerializer(vSer)`, not `serializer(vSer)`:** `Rga` and `Fugue` (their serializers are `internal`). All others use the generated `serializer(...)`.

---

### Task 1: Add `CanonicalMapSerializer` and `CanonicalSetSerializer`

The two shared sorting serializers every later fix task applies. Nothing changes behaviourally yet.

**Files:**
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/CanonicalCollectionSerializers.kt`
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalCollectionSerializersTest.kt`

**Interfaces:**
- Consumes: `us.tractat.kuilt.crdt.internal.serialKeyComparator` (existing, `internal`)
- Produces:
  - `public class CanonicalMapSerializer<K, V>(kSerializer: KSerializer<K>, vSerializer: KSerializer<V>) : KSerializer<Map<K, V>>`
  - `public class CanonicalSetSerializer<E>(eSerializer: KSerializer<E>) : KSerializer<Set<E>>`

- [ ] **Step 1: Write the failing test**

Create `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalCollectionSerializersTest.kt`:

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class CanonicalCollectionSerializersTest {

    private val cbor = Cbor {}

    @Test
    fun mapEncodingIsInsertionOrderIndependent() {
        val ser = CanonicalMapSerializer(String.serializer(), Long.serializer())
        // HashMap so iteration order is neither insertion nor sorted — the real defect shape.
        val forward = HashMap<String, Long>().apply { put("a", 1L); put("b", 2L); put("c", 3L) }
        val reverse = HashMap<String, Long>().apply { put("c", 3L); put("b", 2L); put("a", 1L) }

        assertEquals(
            cbor.encodeToByteArray(ser, forward).toList(),
            cbor.encodeToByteArray(ser, reverse).toList(),
            "canonical map encoding must not depend on insertion order",
        )
    }

    @Test
    fun mapRoundTripsAndSorts() {
        val ser = CanonicalMapSerializer(String.serializer(), Long.serializer())
        val value = mapOf("c" to 3L, "a" to 1L, "b" to 2L)
        val decoded = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, value))
        assertAll(
            { assertEquals(value, decoded, "round-trip must preserve the map") },
            { assertEquals(listOf("a", "b", "c"), decoded.keys.toList(), "decoded order must be sorted") },
        )
    }

    @Test
    fun setEncodingIsInsertionOrderIndependent() {
        val ser = CanonicalSetSerializer(String.serializer())
        val forward = linkedSetOf("alpha", "beta", "gamma")
        val reverse = linkedSetOf("gamma", "beta", "alpha")

        assertEquals(
            cbor.encodeToByteArray(ser, forward).toList(),
            cbor.encodeToByteArray(ser, reverse).toList(),
            "canonical set encoding must not depend on insertion order",
        )
    }

    @Test
    fun setRoundTripsAndSorts() {
        val ser = CanonicalSetSerializer(String.serializer())
        val value = linkedSetOf("gamma", "alpha", "beta")
        val decoded = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, value))
        assertAll(
            { assertEquals(value, decoded, "round-trip must preserve the set") },
            { assertEquals(listOf("alpha", "beta", "gamma"), decoded.toList(), "decoded order must be sorted") },
        )
    }

    @Test
    fun compoundKeysSortStructurallyNotByToString() {
        // Dot is a data class with (replica, seq) — serialKeyComparator must order it by
        // serialized leaves, so this works without any Comparable bound on the key.
        val ser = CanonicalMapSerializer(Dot.serializer(), Long.serializer())
        val a = Dot(ReplicaId("A"), 2L)
        val b = Dot(ReplicaId("B"), 1L)
        val forward = HashMap<Dot, Long>().apply { put(a, 1L); put(b, 2L) }
        val reverse = HashMap<Dot, Long>().apply { put(b, 2L); put(a, 1L) }

        assertEquals(
            cbor.encodeToByteArray(ser, forward).toList(),
            cbor.encodeToByteArray(ser, reverse).toList(),
            "compound keys must sort structurally",
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-crdt:compileTestKotlinJvm
```

Expected: FAIL — `Unresolved reference 'CanonicalMapSerializer'`.

- [ ] **Step 3: Write the implementation**

Create `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/CanonicalCollectionSerializers.kt`:

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.internal.serialKeyComparator

/**
 * [KSerializer] for a [Map] that emits entries in a canonical order, so two replicas at the
 * same logical state produce identical bytes regardless of merge history or host platform.
 *
 * The zoo's map-backed states are merged through `MapMerge`, which builds a [HashMap] — its
 * iteration order is hash-bucket order, a per-platform implementation detail. A `GCounter`
 * holding `{r1:1, r2:1, r3:1}` encoded its keys `r2, r3, r1` on the JVM and `r1, r2, r3` on
 * Kotlin/Native before this serializer existed (issue #1957).
 *
 * **Sort order:** by the structural encoding of each key — every [K] is serialized to a
 * sequence of primitive leaves and those sequences compared lexicographically. That is a total
 * order for any serializable key, including data classes, inline value classes and compound
 * keys, and is correct where a [toString]-based sort is not. See `serialKeyComparator` (#752).
 *
 * Wire format is unchanged — the same map layout, with entries reordered.
 */
@OptIn(ExperimentalSerializationApi::class)
public class CanonicalMapSerializer<K, V>(
    kSerializer: KSerializer<K>,
    vSerializer: KSerializer<V>,
) : KSerializer<Map<K, V>> {

    private val mapSerializer = MapSerializer(kSerializer, vSerializer)
    private val keyComparator: Comparator<K> = serialKeyComparator(kSerializer)

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Map<K, V>) {
        val sorted = LinkedHashMap<K, V>(value.size)
        for (key in value.keys.sortedWith(keyComparator)) {
            @Suppress("UNCHECKED_CAST")
            sorted[key] = value.getValue(key)
        }
        mapSerializer.serialize(encoder, sorted)
    }

    override fun deserialize(decoder: Decoder): Map<K, V> = mapSerializer.deserialize(decoder)
}

/**
 * [KSerializer] for a [Set] that emits elements in a canonical order, so two replicas at the
 * same logical state produce identical bytes regardless of merge order.
 *
 * `GSet.piece` is `elements + other.elements`, which yields a `LinkedHashSet` in merge order —
 * so the auto-generated serializer encoded the same logical set differently depending on which
 * side of the join a replica saw first (issue #1957).
 *
 * Wire format: a **list**, matching `DotSetSerializer` — [ListSerializer] preserves the sorted
 * order and every format encodes a list as an array.
 *
 * Sort order is the structural key order described on [CanonicalMapSerializer].
 */
@OptIn(ExperimentalSerializationApi::class)
public class CanonicalSetSerializer<E>(
    eSerializer: KSerializer<E>,
) : KSerializer<Set<E>> {

    private val listSerializer = ListSerializer(eSerializer)
    private val elementComparator: Comparator<E> = serialKeyComparator(eSerializer)

    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Set<E>) {
        listSerializer.serialize(encoder, value.sortedWith(elementComparator))
    }

    override fun deserialize(decoder: Decoder): Set<E> =
        listSerializer.deserialize(decoder).toSet()
}
```

If `serialKeyComparator` is not visible, widen it from `internal` to `internal` within the same module (it already is) — no change should be needed since both files are in `:kuilt-crdt`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :kuilt-crdt:jvmTest --tests "*CanonicalCollectionSerializersTest*" --rerun-tasks
./gradlew :kuilt-crdt:macosArm64Test --tests "*CanonicalCollectionSerializersTest*" --rerun-tasks
```

Expected: PASS on both.

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/CanonicalCollectionSerializers.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalCollectionSerializersTest.kt
git commit -m "feat(crdt): add CanonicalMapSerializer and CanonicalSetSerializer (part of #1957)"
```

---

### Task 2: Add the byte-equality check to `CrdtConvergenceHarness`

Lands the enforcement. It will go **red** for nine types — that is the expected, desired outcome of this task, and Tasks 3–6 turn it green. Do not weaken the check to make it pass.

**Files:**
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/convergence/CrdtConvergenceHarness.kt`
- Modify: `kuilt-conformance/build.gradle.kts`
- Modify (13 call sites): every file under `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/convergence/*ConvergenceTest.kt`
- Modify: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/convergence/IntMaxConvergenceTest.kt` (make `IntMax` serializable)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `CrdtConvergenceHarness<S>(initial: S, gen: OperationGenerator<S>, serializer: KSerializer<S>, replicaCount: Int = 3, opsPerReplica: Int = 8)` — `serializer` is the **third positional parameter and is required**.

- [ ] **Step 1: Add the CBOR test dependency**

In `kuilt-conformance/build.gradle.kts`, inside the `sourceSets` block, add a `commonMain` CBOR dependency next to the existing `api(project(":kuilt-crdt"))` line:

```kotlin
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-liveness"))
            api(project(":kuilt-session"))
            api(project(":kuilt-raft"))
            api(project(":kuilt-crdt"))
            api(project(":kuilt-test"))
            // The convergence harness asserts byte-level canonicality of encoded CRDT
            // states (#1957), so it needs a concrete BinaryFormat. CBOR matches Quilter's
            // default wire format.
            api(libs.kotlinx.serialization.cbor)
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.coroutines.core)
        }
```

- [ ] **Step 2: Write the failing check into the harness**

In `CrdtConvergenceHarness.kt`, add the imports:

```kotlin
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
```

Change the constructor to take a required `serializer`, and replace `assertAllPermutationsConverge`:

```kotlin
@OptIn(ExperimentalSerializationApi::class)
public class CrdtConvergenceHarness<S : Quilted<S>>(
    public val initial: S,
    public val gen: OperationGenerator<S>,
    public val serializer: KSerializer<S>,
    public val replicaCount: Int = 3,
    public val opsPerReplica: Int = 8,
) {
    private val cbor = Cbor {}

    private fun encoded(state: S): ByteArray = cbor.encodeToByteArray(serializer, state)

    private fun assertAllPermutationsConverge(replicas: List<S>, canonical: S) {
        val canonicalBytes = encoded(canonical)
        for (permutation in permutationsOf(replicas.indices.toList())) {
            val result = permutation.fold(initial) { acc, idx -> acc.piece(replicas[idx]) }
            check(result == canonical) {
                "Convergence failure under permutation $permutation:\n" +
                    "  expected $canonical\n" +
                    "  got      $result\n" +
                    "  replicas $replicas"
            }
            // Byte-level canonicality (#1957): converged replicas must ENCODE identically,
            // not merely compare equal. Set/Map equality is order-insensitive exactly where
            // the encoding is not, so `result == canonical` is structurally blind to a
            // history- or platform-dependent encoding.
            val resultBytes = encoded(result)
            check(resultBytes.contentEquals(canonicalBytes)) {
                "Canonical-encoding failure under permutation $permutation:\n" +
                    "  canonical bytes ${canonicalBytes.toHexString()}\n" +
                    "  permuted  bytes ${resultBytes.toHexString()}\n" +
                    "  state     $canonical"
            }
        }
    }
```

Add `@OptIn(kotlin.ExperimentalStdlibApi::class)` on the class if `toHexString()` requires it.

Keep the rest of the class (`run`, `runSeeds`, `buildReplicas`, `mergeAll`, `permutationsOf`) unchanged.

- [ ] **Step 3: Update all 13 call sites**

Each `*ConvergenceTest.kt` under `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/convergence/` gains a `serializer = …` argument. Use this exact mapping:

| File | Argument |
|---|---|
| `BoundedCounterConvergenceTest.kt` | `serializer = BoundedCounter.serializer(),` |
| `EphemeralMapConvergenceTest.kt` | `serializer = EphemeralMap.serializer(String.serializer()),` |
| `FugueConvergenceTest.kt` | `serializer = Fugue.wireSerializer(String.serializer()),` |
| `GCounterConvergenceTest.kt` | `serializer = GCounter.serializer(),` |
| `IntMaxConvergenceTest.kt` | `serializer = IntMax.serializer(),` |
| `JsonCrdtConvergenceTest.kt` | `serializer = JsonCrdt.serializer(),` |
| `LWWMapConvergenceTest.kt` | `serializer = LWWMap.serializer(String.serializer(), String.serializer()),` — match the test's actual type arguments |
| `LWWRegisterConvergenceTest.kt` | `serializer = LWWRegister.serializer(String.serializer()),` |
| `MovableTreeConvergenceTest.kt` | `serializer = MovableTree.serializer(String.serializer()),` |
| `MVRegisterConvergenceTest.kt` | `serializer = MVRegister.serializer(String.serializer()),` |
| `ORMapConvergenceTest.kt` | `serializer = ORMap.serializer(String.serializer(), GCounter.serializer()),` |
| `ORSetConvergenceTest.kt` | `serializer = ORSet.serializer(String.serializer()),` |
| `RgaConvergenceTest.kt` | `serializer = Rga.wireSerializer(String.serializer()),` |

Each needs `import kotlinx.serialization.builtins.serializer` where `String.serializer()` is used.

`IntMax` in `IntMaxConvergenceTest.kt` (and its twin in `IntMaxConformanceTest.kt`) is a plain `data class`. Add the annotation and import:

```kotlin
import kotlinx.serialization.Serializable

@Serializable
internal data class IntMax(val value: Int) : Quilted<IntMax> {
    override fun piece(other: IntMax): IntMax = IntMax(maxOf(value, other.value))
}
```

If `IntMax` is declared in `IntMaxConformanceTest.kt` and reused, annotate it there only — do not duplicate the declaration.

- [ ] **Step 4: Run and record exactly which types go red**

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*Convergence*" --rerun-tasks 2>&1 | tee /tmp/canonical-baseline.txt
```

Expected: FAIL. Per the spec's evidence, these nine should fail: `GSet`*, `TwoPhaseSet`*, `GCounter`, `PNCounter`, `LWWMap`, `ORMap`, `EphemeralMap`, `BoundedCounter`, `MovableTree`. (*`GSet`/`TwoPhaseSet` have no convergence test yet — Task 3 adds them.)

Record the actual failing set in the commit message. If it differs from the spec's nine, that is a finding — report it, do not silently adjust.

Also run the JVM suite and note the difference:

```bash
./gradlew :kuilt-conformance:jvmTest --tests "*Convergence*" --rerun-tasks
```

Expected: far fewer failures than macOS. This is the false-green receipt.

- [ ] **Step 5: Commit (red is expected)**

```bash
git add kuilt-conformance/
git commit -m "test(conformance): assert byte-level canonicality in CrdtConvergenceHarness (part of #1957)

Fails for nine types; the following tasks fix them."
```

---

### Task 3: Fix root cause B — `GSet` and `TwoPhaseSet`

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/GSet.kt`
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/TwoPhaseSet.kt`
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/convergence/GSetConvergenceTest.kt`
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/convergence/TwoPhaseSetConvergenceTest.kt`

**Interfaces:**
- Consumes: `CanonicalSetSerializer<E>` from Task 1; the `serializer` harness parameter from Task 2.
- Produces: nothing new.

- [ ] **Step 1: Write the failing convergence tests**

Create `GSetConvergenceTest.kt`:

```kotlin
package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.piece

// A small element pool forces overlapping adds across replicas, so the merged sets differ
// only in insertion order — the shape that exposes a non-canonical set encoding.
internal class GSetConvergenceTest : CrdtConvergenceSuite<GSet<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<GSet<String>> = CrdtConvergenceHarness(
        initial = GSet.empty(),
        gen = OperationGenerator { state, _, random ->
            state.piece(GSet.of("e${random.nextInt(6)}"))
        },
        serializer = GSet.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
```

Create `TwoPhaseSetConvergenceTest.kt`:

```kotlin
package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.TwoPhaseSet
import us.tractat.kuilt.crdt.piece

// Mixed adds and removes populate both the added and removed sets, so both fields are
// exercised for canonical encoding.
internal class TwoPhaseSetConvergenceTest : CrdtConvergenceSuite<TwoPhaseSet<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<TwoPhaseSet<String>> = CrdtConvergenceHarness(
        initial = TwoPhaseSet.empty(),
        gen = OperationGenerator { state, _, random ->
            val element = "e${random.nextInt(6)}"
            if (random.nextInt(3) == 0) state.piece(state.remove(element))
            else state.piece(state.add(element))
        },
        serializer = TwoPhaseSet.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*GSetConvergence*" --tests "*TwoPhaseSetConvergence*" --rerun-tasks
```

Expected: FAIL with "Canonical-encoding failure under permutation …".

- [ ] **Step 3: Apply `CanonicalSetSerializer`**

In `GSet.kt`, annotate the backing field:

```kotlin
import kotlinx.serialization.Serializable

@Serializable
public class GSet<E> private constructor(
    @Serializable(with = CanonicalSetSerializer::class)
    public val elements: Set<E>,
) : Quilted<GSet<E>> {
```

In `TwoPhaseSet.kt`, annotate both backing fields the same way (`added` and `removed`).

- [ ] **Step 4: Run to verify they pass**

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*GSetConvergence*" --tests "*TwoPhaseSetConvergence*" --rerun-tasks
./gradlew :kuilt-crdt:macosArm64Test --rerun-tasks
```

Expected: PASS. The second command catches any existing `:kuilt-crdt` test that pinned the old byte layout — if one fails, its golden expectation must be updated, not the serializer reverted.

- [ ] **Step 5: Prove the test catches the bug**

Revert only the `GSet.kt` annotation, re-run `*GSetConvergence*`, confirm it goes red, then restore. Record the observed failure line in the commit message.

- [ ] **Step 6: Commit**

```bash
git add kuilt-crdt/ kuilt-conformance/
git commit -m "fix(crdt): canonical set encoding for GSet and TwoPhaseSet (part of #1957)"
```

---

### Task 4: Fix root cause A — the three directly map-backed types

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/GCounter.kt`
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/PNCounter.kt` (only if it does not delegate wholly to `GCounter`)
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/LWWMap.kt`

**Interfaces:**
- Consumes: `CanonicalMapSerializer<K, V>` from Task 1.
- Produces: nothing new.

- [ ] **Step 1: Confirm the tests fail**

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*GCounterConvergence*" --tests "*LWWMapConvergence*" --rerun-tasks
```

Expected: FAIL with "Canonical-encoding failure". Record the byte diff.

- [ ] **Step 2: Apply `CanonicalMapSerializer` to `GCounter`**

```kotlin
@Serializable
public class GCounter private constructor(
    @Serializable(with = CanonicalMapSerializer::class)
    private val counts: Map<ReplicaId, Long>,
) : Quilted<GCounter> {
```

- [ ] **Step 3: Check whether `PNCounter` needs its own change**

`PNCounter` holds two `GCounter`s (`inc`, `dec`). If both are `GCounter`-typed, fixing `GCounter` fixes it transitively — no edit needed. Verify:

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*PNCounterConvergence*" --rerun-tasks
```

If it passes, make no change to `PNCounter.kt`. If it fails, it holds a raw map — apply the same annotation to that field.

- [ ] **Step 4: Apply `CanonicalMapSerializer` to `LWWMap`**

```kotlin
@Serializable
public class LWWMap<K, V> private constructor(
    @Serializable(with = CanonicalMapSerializer::class)
    private val cells: Map<K, LWWRegister<V>>,
) : Quilted<LWWMap<K, V>> {
```

- [ ] **Step 5: Run to verify**

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*GCounterConvergence*" --tests "*PNCounterConvergence*" --tests "*LWWMapConvergence*" --rerun-tasks
./gradlew :kuilt-crdt:macosArm64Test --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Prove the test catches the bug**

Revert the `GCounter.kt` annotation, re-run `*GCounterConvergence*`, confirm red, restore.

- [ ] **Step 7: Commit**

```bash
git add kuilt-crdt/
git commit -m "fix(crdt): canonical map encoding for GCounter, PNCounter and LWWMap (part of #1957)"
```

---

### Task 5: Fix the composite types — `ORMap`, `EphemeralMap`, `BoundedCounter`, `MovableTree`

These nest maps inside other structures, so some may already be green after Task 4. Check before editing.

**Files:**
- Modify (only where still failing): `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/EphemeralMap.kt` (the `entries` field, merged at `EphemeralMap.kt:186`)
- Modify (only where still failing): `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/BoundedCounter.kt` (the `transfers` field, merged at `BoundedCounter.kt:108`)
- Modify (only where still failing): `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/MovableTree.kt` (the `seqByReplica` field, merged at `MovableTree.kt:209`)
- Modify (only where still failing): `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ORMap.kt`

**Interfaces:**
- Consumes: `CanonicalMapSerializer<K, V>` from Task 1; the Task 4 fixes.
- Produces: nothing new.

- [ ] **Step 1: Re-measure after Task 4**

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*ORMapConvergence*" --tests "*EphemeralMapConvergence*" --tests "*BoundedCounterConvergence*" --tests "*MovableTreeConvergence*" --rerun-tasks
```

`ORMap<String, GCounter>` is expected to pass now — its defect came from its `GCounter` values, which Task 4 fixed. Record which of the four still fail; only edit those.

- [ ] **Step 2: Annotate each remaining failing field**

For each still-failing type, add the annotation to the map field named in **Files** above:

```kotlin
    @Serializable(with = CanonicalMapSerializer::class)
    private val entries: Map<ReplicaId, EphemeralEntry<V>>,
```

Match the field's real name and type — read the file, do not assume.

`MovableTree` also holds a `log: List<MoveOp<V>>`. **Do not sort it** — a move log is order-bearing and sorting it would change semantics. If `MovableTree` still fails after `seqByReplica` is annotated, stop and report: the log's own ordering is the suspect, and that is a design question, not a mechanical fix.

- [ ] **Step 3: Run to verify**

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*ORMapConvergence*" --tests "*EphemeralMapConvergence*" --tests "*BoundedCounterConvergence*" --tests "*MovableTreeConvergence*" --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Run the full convergence suite on both targets**

```bash
./gradlew :kuilt-conformance:macosArm64Test --tests "*Convergence*" --rerun-tasks
./gradlew :kuilt-conformance:jvmTest --tests "*Convergence*" --rerun-tasks
```

Expected: PASS everywhere. This is the moment the enforcement from Task 2 goes fully green.

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/
git commit -m "fix(crdt): canonical encoding for the nested map-backed CRDTs (part of #1957)"
```

---

### Task 6: Strengthen the under-powered `ORMap` canonicality test

`orMapSerializationIsDeliveryOrderIndependent` builds values as `GCounter.of(a to 1L)`. A single-entry map has one iteration order, so the test structurally cannot fail. It stays green whether or not the bug exists — worse than no test.

**Files:**
- Modify: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalSerializationTest.kt:148-170`

**Interfaces:**
- Consumes: the Task 4 `GCounter` fix.
- Produces: nothing new.

- [ ] **Step 1: Make the values multi-entry**

Replace both `GCounter.of(…)` calls in that test so each value holds three replicas:

```kotlin
        val alphaValue = GCounter.of(a to 1L, b to 2L, ReplicaId("C") to 3L)
        val betaValue = GCounter.of(b to 2L, ReplicaId("C") to 5L, a to 4L)

        // Replica 1: A puts "alpha", B puts "beta"
        val m1a = ORMap.empty<String, GCounter>().put(a, "alpha", alphaValue)
        val m1b = ORMap.empty<String, GCounter>().put(b, "beta", betaValue)
        val merged1 = m1a.piece(m1b)

        // Replica 2: B puts "beta", A puts "alpha"
        val m2b = ORMap.empty<String, GCounter>().put(b, "beta", betaValue)
        val m2a = ORMap.empty<String, GCounter>().put(a, "alpha", alphaValue)
        val merged2 = m2b.piece(m2a)
```

Update the KDoc to say the values are deliberately multi-entry so the map's iteration order is actually exercised.

- [ ] **Step 2: Prove the strengthened test catches the bug**

Revert the `GCounter.kt` annotation from Task 4, run:

```bash
./gradlew :kuilt-crdt:macosArm64Test --tests "*CanonicalSerializationTest*" --rerun-tasks
```

Expected: FAIL on `orMapSerializationIsDeliveryOrderIndependent`. Then restore the annotation and confirm PASS. If it does **not** fail with the fix reverted, the test is still under-powered — increase the entry count and repeat.

- [ ] **Step 3: Commit**

```bash
git add kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalSerializationTest.kt
git commit -m "test(crdt): make the ORMap canonicality test able to fail (part of #1957)"
```

---

### Task 7: Golden vectors pin the cross-target encoding

The harness proves order-independence *within* a target. It cannot prove two targets agree — only a pinned byte string does, and that is the dimension #1957 asks for.

**Files:**
- Create: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalGoldenVectorTest.kt`

**Interfaces:**
- Consumes: every fix from Tasks 3–5.
- Produces: nothing new.

- [ ] **Step 1: Write the test with placeholder vectors**

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-target encoding pin (issue #1957), following the `HeddlePolicyGoldenVectorTest`
 * and SRA wire byte-parity precedent.
 *
 * The convergence harness proves each target encodes a converged value the same way
 * regardless of merge order. It cannot prove that **two different targets** agree — a
 * platform-dependent encoding is self-consistent on each side while the bytes differ.
 * Only a checked-in byte string catches that, and because `commonTest` compiles and runs
 * on JVM, Android, iOS, macOS and wasmJs, this file *is* the cross-target check.
 *
 * Every constant below was captured once from a fixed construction. **Regenerate only on a
 * deliberate encoding change, and expect every vector to move.** A single vector changing
 * on one target and not another is the exact defect this file exists to catch — investigate,
 * do not re-record.
 */
@OptIn(ExperimentalSerializationApi::class, kotlin.ExperimentalStdlibApi::class)
class CanonicalGoldenVectorTest {

    private val cbor = Cbor {}
    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")
    private val r3 = ReplicaId("r3")

    @Test
    fun everyVectorMatchesOnEveryTarget() {
        assertAll(
            { assertEquals(GSET, hex(GSet.serializer(String.serializer()), gSet()), "GSet") },
            { assertEquals(TWO_PHASE_SET, hex(TwoPhaseSet.serializer(String.serializer()), twoPhaseSet()), "TwoPhaseSet") },
            { assertEquals(GCOUNTER, hex(GCounter.serializer(), gCounter()), "GCounter") },
            { assertEquals(PNCOUNTER, hex(PNCounter.serializer(), pnCounter()), "PNCounter") },
            { assertEquals(LWWMAP, hex(LWWMap.serializer(String.serializer(), Int.serializer()), lwwMap()), "LWWMap") },
            { assertEquals(ORSET, hex(ORSet.serializer(String.serializer()), orSet()), "ORSet") },
        )
    }

    private fun <S> hex(ser: kotlinx.serialization.KSerializer<S>, value: S): String =
        cbor.encodeToByteArray(ser, value).toHexString()

    // Built in a fixed order; the canonical serializers make the encoding independent of it.
    private fun gSet(): GSet<String> =
        GSet.of("gamma").piece(GSet.of("alpha")).piece(GSet.of("beta"))

    private fun twoPhaseSet(): TwoPhaseSet<String> =
        TwoPhaseSet.empty<String>()
            .piece(TwoPhaseSet.empty<String>().add("gamma"))
            .piece(TwoPhaseSet.empty<String>().add("alpha"))
            .piece(TwoPhaseSet.empty<String>().remove("gamma"))

    private fun gCounter(): GCounter =
        GCounter.ZERO
            .piece(GCounter.ZERO.inc(r3, 3L))
            .piece(GCounter.ZERO.inc(r1, 1L))
            .piece(GCounter.ZERO.inc(r2, 2L))

    private fun pnCounter(): PNCounter =
        PNCounter.ZERO
            .piece(PNCounter.ZERO.increment(r2, 5L))
            .piece(PNCounter.ZERO.decrement(r1, 2L))

    private fun lwwMap(): LWWMap<String, Int> =
        LWWMap.empty<String, Int>()
            .piece(LWWMap.empty<String, Int>().set(r1, 3L, "c", 3))
            .piece(LWWMap.empty<String, Int>().set(r1, 1L, "a", 1))
            .piece(LWWMap.empty<String, Int>().set(r1, 2L, "b", 2))

    private fun orSet(): ORSet<String> =
        ORSet.empty<String>()
            .piece(ORSet.empty<String>().add(r2, "beta"))
            .piece(ORSet.empty<String>().add(r1, "alpha"))

    private companion object {
        const val GSET = "REPLACE_ME"
        const val TWO_PHASE_SET = "REPLACE_ME"
        const val GCOUNTER = "REPLACE_ME"
        const val PNCOUNTER = "REPLACE_ME"
        const val LWWMAP = "REPLACE_ME"
        const val ORSET = "REPLACE_ME"
    }
}
```

Adjust each construction to the type's real API — `piece` with a `Patch` where the mutator returns one, plain `piece` where it returns a full state. Read each type before writing its builder.

- [ ] **Step 2: Capture the real vectors**

```bash
./gradlew :kuilt-crdt:jvmTest --tests "*CanonicalGoldenVectorTest*" --rerun-tasks
```

It fails and the assertion message prints each actual hex string. Paste each into the matching constant.

- [ ] **Step 3: Verify the vectors hold on every target**

```bash
./gradlew :kuilt-crdt:jvmTest --tests "*CanonicalGoldenVectorTest*" --rerun-tasks
./gradlew :kuilt-crdt:macosArm64Test --tests "*CanonicalGoldenVectorTest*" --rerun-tasks
./gradlew :kuilt-crdt:wasmJsTest --rerun-tasks
./gradlew :kuilt-crdt:iosSimulatorArm64Test --rerun-tasks
```

Expected: PASS on all four. **A failure here is a real cross-target defect** — the vectors were captured on JVM, so a macOS/wasm/iOS mismatch means that target still encodes differently. Report it; do not record a per-target vector.

- [ ] **Step 4: Commit**

```bash
git add kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalGoldenVectorTest.kt
git commit -m "test(crdt): pin canonical encodings with cross-target golden vectors (part of #1957)"
```

---

### Task 8: Add the digest helper

**Files:**
- Create: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/CanonicalDigest.kt`
- Test: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CanonicalDigestTest.kt`

**Interfaces:**
- Consumes: the CBOR dependency added in Task 2.
- Produces: `public fun <S> canonicalDigest(serializer: KSerializer<S>, value: S): Long`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.conformance

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CanonicalDigestTest {

    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")

    @Test
    fun convergedReplicasShareADigest() {
        val ser = GSet.serializer(String.serializer())
        val forward = GSet.of("alpha").piece(GSet.of("beta")).piece(GSet.of("gamma"))
        val reverse = GSet.of("gamma").piece(GSet.of("beta")).piece(GSet.of("alpha"))
        assertAll(
            { assertEquals(forward, reverse, "sanity: same logical state") },
            {
                assertEquals(
                    canonicalDigest(ser, forward),
                    canonicalDigest(ser, reverse),
                    "converged replicas must share a digest",
                )
            },
        )
    }

    @Test
    fun divergentStatesDiffer() {
        val ser = GCounter.serializer()
        val a = GCounter.ZERO.piece(GCounter.ZERO.inc(r1, 1L))
        val b = GCounter.ZERO.piece(GCounter.ZERO.inc(r2, 1L))
        assertNotEquals(canonicalDigest(ser, a), canonicalDigest(ser, b), "distinct states must differ")
    }

    @Test
    fun digestIsStableAcrossCalls() {
        val ser = GSet.serializer(String.serializer())
        val value = GSet.of("alpha", "beta")
        assertEquals(canonicalDigest(ser, value), canonicalDigest(ser, value), "digest must be pure")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :kuilt-conformance:compileTestKotlinJvm
```

Expected: FAIL — `Unresolved reference 'canonicalDigest'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package us.tractat.kuilt.conformance

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor

private val digestCbor = Cbor {}

private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIME: Long = 1099511628211L

/**
 * A 64-bit digest of [value]'s canonical CBOR encoding — FNV-1a.
 *
 * Two replicas that have converged share a digest; two that have diverged almost certainly
 * do not. Intended for the cases where comparing the states directly is impossible:
 *
 *  - **cross-process and real-socket tests**, where shipping one `Long` back for assertion
 *    beats shipping a whole state;
 *  - **a divergence alarm** between live peers, in a harness or in production diagnostics.
 *
 * In-process, `assertEquals(a, b)` is strictly better — exact, no collision risk, and a far
 * better failure message. The convergence harness deliberately compares raw bytes rather than
 * digests for that reason; this exists only for the boundary-crossing cases.
 *
 * Correctness rests on the encoding being canonical, which the `CrdtConvergenceHarness` byte
 * assertion and the `CanonicalGoldenVectorTest` vectors enforce (issue #1957). A digest over a
 * non-canonical encoding reports permanent false divergence.
 *
 * Not cryptographic — do not use it to authenticate state.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun <S> canonicalDigest(serializer: KSerializer<S>, value: S): Long {
    var hash = FNV_OFFSET_BASIS
    for (byte in digestCbor.encodeToByteArray(serializer, value)) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV_PRIME
    }
    return hash
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :kuilt-conformance:jvmTest --tests "*CanonicalDigestTest*" --rerun-tasks
./gradlew :kuilt-conformance:macosArm64Test --tests "*CanonicalDigestTest*" --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-conformance/
git commit -m "feat(conformance): add canonicalDigest for cross-process convergence checks (part of #1957)"
```

---

### Task 9: Documentation and the full gate

**Files:**
- Modify: `docs/agent-cookbook.md`
- Modify: `kuilt-crdt/module.md`
- Modify: `.claude/skills/kuilt-primitives/SKILL.md` (only if its routing or description needs to change)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new.

- [ ] **Step 1: Add the cookbook entry**

`CLAUDE.md` requires a symptom→primitive entry in `docs/agent-cookbook.md` for any new public primitive a consumer would reach for. Add one for `canonicalDigest`, keyed on the symptom "I need to check two peers hold the same state but I can't compare the objects." Quote a compiled snippet verbatim with a `<!-- verbatim from … -->` citation pointing at `CanonicalDigestTest#convergedReplicasShareADigest`.

Confirm `.claude/skills/kuilt-primitives/SKILL.md` still routes correctly; update its `description` only if a developer would now phrase the need differently.

- [ ] **Step 2: Document the invariant in `kuilt-crdt/module.md`**

Add a short paragraph stating that a CRDT's serialized form is a function of its logical value, that `CanonicalMapSerializer`/`CanonicalSetSerializer` are how map- and set-backed states hold it, and that new zoo types must use them. Write it accessible-first per `CLAUDE.md` — lead with what it means for a person, then the mechanism.

- [ ] **Step 3: Run the full gate**

```bash
./gradlew build detektAll --rerun-tasks
```

Expected: BUILD SUCCESSFUL, tasks `EXECUTED` not `FROM-CACHE`. If any test-compile task still shows `FROM-CACHE`, re-run with `--no-build-cache`.

This is the required gate — a module-scoped build is a false green for this change, because `:examples` and `:kuilt-cluster` E2E tests exercise the serialization path downstream.

- [ ] **Step 4: Run the type-resolution detekt pass**

```bash
./gradlew :kuilt-crdt:detektJvmMain :kuilt-conformance:detektJvmMain --rerun-tasks
```

Expected: PASS. `detektAll` can false-green on `UnsafeCallOnNullableType`; this is the check CI actually runs.

- [ ] **Step 5: Commit**

```bash
git add docs/ kuilt-crdt/module.md .claude/
git commit -m "docs(crdt): record the canonical-encoding invariant and the digest primitive (part of #1957)"
```

---

## PR shape

Tasks 1–5 must land **together** — Task 2 lands red by design, and `main` must never be red. Open one PR containing Tasks 1–5, then optionally a second for Tasks 6–9.

PR body must use `closes #1957` and note that the fix **changes the wire bytes** for the nine affected types (reordered map entries and set elements; structural layout unchanged, so old peers still decode). Pre-1.0 with no cross-version compatibility guarantee, but call it out rather than let a consumer discover it.

Reference `part of #1955` — this de-risks the Merkle work but does not implement it.
