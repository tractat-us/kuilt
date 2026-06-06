# `:kuilt-crdt` Rung 1 — Dots & Causal Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the causal-history primitives the whole observed-remove family depends on: `ReplicaId`, `Dot`, and a `DotContext` (version vector **plus dot cloud**) that is itself a `Quilted` join-semilattice.

**Architecture:** A `Dot` is a clock-free unique name for one operation — `(ReplicaId, seq)`, with each replica counting its own `seq` from 1. A `DotContext` records *every dot a replica has ever witnessed*. It stores the contiguous prefix compactly as a version vector (`replica → highest contiguous seq`) and any non-contiguous dots in a *dot cloud*, compacting the cloud into the vector as gaps fill. The cloud is essential, not optional: kuilt fabrics reorder and duplicate frames, so dots arrive out of order; a bare version vector cannot represent "seen (A,3) but not (A,2)". `DotContext.piece` (its CRDT merge) is union: elementwise-max the vectors, union the clouds, then compact.

**Tech Stack:** Kotlin Multiplatform, `kuilt.kmp-library`, `explicitApi()`, kotlinx.serialization (introduced this rung — every type must survive becoming a `Swatch`), `kotlin-test`.

**Design reference:** `docs/superpowers/specs/2026-06-06-crdt-module-design.md` (Rung 1). Builds on the Rung 0 `Quilted<S>` interface already in `:kuilt-crdt`.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `kuilt-crdt/build.gradle.kts` (modify) | Add the serialization plugin + `kotlinx-serialization-core` |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ReplicaId.kt` (create) | The replica identity (value class) |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Dot.kt` (create) | The `(replica, seq)` unique name |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/DotContext.kt` (create) | Version vector + dot cloud; `Quilted` merge |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DotTest.kt` (create) | `Dot`/`ReplicaId` behaviour + serialization |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DotContextTest.kt` (create) | `DotContext` behaviour (contains/nextDot/add/compaction/merge) + serialization |
| `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/DotContextConformanceTest.kt` (create) | `DotContext` passes the `Quilted` laws |

> **Why the conformance test lives in `:kuilt-conformance` (not `:kuilt-crdt`):** the suite is in `:kuilt-conformance`'s `commonMain`, which already `api`-depends on `:kuilt-crdt`. Putting the subclass in `:kuilt-crdt`'s test set would require `:kuilt-crdt` to depend on `:kuilt-conformance`, creating a project cycle. So it goes beside `IntMaxConformanceTest`.

---

## Preamble (run once)

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
```

---

## Task 1: Serialization wiring + `ReplicaId` + `Dot`

**Files:**
- Modify: `kuilt-crdt/build.gradle.kts`
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ReplicaId.kt`
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Dot.kt`
- Create: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DotTest.kt`

- [ ] **Step 1: Add serialization to the module build**

Replace the entire contents of `kuilt-crdt/build.gradle.kts` with:

```kotlin
plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.core)
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DotTest.kt`:

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DotTest {

    private val a = ReplicaId("A")

    @Test
    fun seqMustBePositive() {
        assertFailsWith<IllegalArgumentException> { Dot(a, 0L) }
    }

    @Test
    fun dotsAreValueEqual() {
        assertEquals(Dot(a, 1L), Dot(ReplicaId("A"), 1L))
    }

    @Test
    fun dotRoundTripsThroughJson() {
        val dot = Dot(a, 7L)
        val encoded = Json.encodeToString(Dot.serializer(), dot)
        assertEquals(dot, Json.decodeFromString(Dot.serializer(), encoded))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :kuilt-crdt:jvmTest`
Expected: FAIL — `unresolved reference: ReplicaId` / `Dot`.

- [ ] **Step 4: Implement `ReplicaId` and `Dot`**

Create `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ReplicaId.kt`:

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identity of one replica (peer) in a CRDT. Used to namespace [Dot]s so every
 * operation gets a globally-unique name with no coordination and no clock.
 */
@Serializable
@JvmInline
public value class ReplicaId(public val value: String)
```

Create `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Dot.kt`:

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A clock-free unique name for a single operation: the issuing [replica] paired
 * with that replica's own monotonically-increasing [seq] (1, 2, 3…).
 *
 * Uniqueness needs no coordination: the [replica] namespaces the dot, and a
 * replica only ever bumps its own counter, so no two operations anywhere can
 * mint the same dot. This replaces wall-clock timestamps for causality.
 *
 * @property seq the per-replica sequence number; always `>= 1`.
 */
@Serializable
public data class Dot(public val replica: ReplicaId, public val seq: Long) {
    init {
        require(seq >= 1L) { "Dot seq must be >= 1, was $seq" }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :kuilt-crdt:jvmTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kuilt-crdt/build.gradle.kts \
        kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ReplicaId.kt \
        kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Dot.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DotTest.kt
git commit -m "feat(kuilt-crdt): ReplicaId + Dot — clock-free unique operation names"
```

---

## Task 2: `DotContext` — version vector + dot cloud

The causal history: every dot ever witnessed. Contiguous prefix in a version
vector; out-of-order dots in a cloud that compacts as gaps fill. `piece` is the
join (union). Equality is canonical because compaction always pushes the cloud
as far into the vector as it will go and drops cloud dots already covered, so two
contexts with the same causal history have identical `(vv, cloud)`.

**Files:**
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/DotContext.kt`
- Create: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DotContextTest.kt`

- [ ] **Step 1: Write the failing test**

Create `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DotContextTest.kt`:

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DotContextTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyContainsNothing() {
        assertFalse(DotContext.EMPTY.contains(Dot(a, 1L)))
    }

    @Test
    fun addThenContains() {
        val ctx = DotContext.EMPTY.add(Dot(a, 1L))
        assertTrue(ctx.contains(Dot(a, 1L)))
        assertFalse(ctx.contains(Dot(a, 2L)))
    }

    @Test
    fun nextDotMintsTheNextSeq() {
        assertEquals(Dot(a, 1L), DotContext.EMPTY.nextDot(a))
        assertEquals(Dot(a, 2L), DotContext.of(Dot(a, 1L)).nextDot(a))
    }

    @Test
    fun contiguousDotsCompactRegardlessOfOrder() {
        val forward = DotContext.EMPTY.add(Dot(a, 1L)).add(Dot(a, 2L))
        val backward = DotContext.EMPTY.add(Dot(a, 2L)).add(Dot(a, 1L))
        assertEquals(forward, backward)
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L)), forward)
    }

    @Test
    fun gapStaysInCloudUntilFilled() {
        val withGap = DotContext.EMPTY.add(Dot(a, 3L)) // missing (A,1),(A,2)
        assertTrue(withGap.contains(Dot(a, 3L)))
        assertFalse(withGap.contains(Dot(a, 2L)))
        // its own count is still 0, so the next *self* mint is (A,1)
        assertEquals(Dot(a, 1L), withGap.nextDot(a))
        // fill the gap → everything compacts
        val filled = withGap.add(Dot(a, 1L)).add(Dot(a, 2L))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L)), filled)
        assertTrue(filled.contains(Dot(a, 2L)))
    }

    @Test
    fun addIsIdempotent() {
        val once = DotContext.EMPTY.add(Dot(a, 1L))
        assertEquals(once, once.add(Dot(a, 1L)))
    }

    @Test
    fun pieceUnionsHistories() {
        val left = DotContext.of(Dot(a, 1L), Dot(a, 2L))
        val right = DotContext.of(Dot(b, 1L))
        val merged = left.piece(right)
        assertTrue(merged.contains(Dot(a, 2L)))
        assertTrue(merged.contains(Dot(b, 1L)))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(b, 1L)), merged)
    }

    @Test
    fun pieceCompactsAcrossOperands() {
        // one side has the prefix, the other the gap dot — merging fills it
        val merged = DotContext.of(Dot(a, 1L)).piece(DotContext.of(Dot(a, 2L)))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L)), merged)
    }

    @Test
    fun roundTripsThroughJson() {
        val ctx = DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(b, 4L))
        val encoded = Json.encodeToString(DotContext.serializer(), ctx)
        assertEquals(ctx, Json.decodeFromString(DotContext.serializer(), encoded))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kuilt-crdt:jvmTest`
Expected: FAIL — `unresolved reference: DotContext`.

- [ ] **Step 3: Implement `DotContext`**

Create `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/DotContext.kt`:

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * The causal history of a replica: every [Dot] it has ever witnessed, live or
 * already removed. It is the memory that outlives a delete — that is what lets a
 * merge tell "I saw that dot and dropped it on purpose" from "I just haven't
 * seen it yet".
 *
 * Stored compactly: the contiguous prefix per replica is a version vector
 * ([vv]: `replica → highest contiguous seq`), and any non-contiguous dots wait
 * in a [cloud] until the gaps before them fill, at which point they compact into
 * the vector. The cloud is required because kuilt fabrics reorder and duplicate
 * frames, so dots can arrive out of order.
 *
 * It is itself a [Quilted]: [piece] is the join (union of both histories).
 * Equality is canonical — compaction pushes the cloud as far into the vector as
 * possible and drops already-covered dots — so two contexts with the same causal
 * history are structurally equal, which the conformance laws rely on.
 */
@Serializable
public class DotContext private constructor(
    private val vv: Map<ReplicaId, Long>,
    private val cloud: Set<Dot>,
) : Quilted<DotContext> {

    /** True if [dot] has been witnessed — covered by the vector or held in the cloud. */
    public fun contains(dot: Dot): Boolean =
        dot.seq <= (vv[dot.replica] ?: 0L) || dot in cloud

    /** The next dot [replica] should mint for a new local operation. */
    public fun nextDot(replica: ReplicaId): Dot =
        Dot(replica, (vv[replica] ?: 0L) + 1L)

    /** This history with [dot] witnessed (idempotent; compacts the cloud). */
    public fun add(dot: Dot): DotContext = compact(vv, cloud + dot)

    /** The join: the union of two causal histories. */
    override fun piece(other: DotContext): DotContext {
        val mergedVv = HashMap<ReplicaId, Long>(vv)
        for ((replica, seq) in other.vv) {
            val current = mergedVv[replica]
            if (current == null || seq > current) mergedVv[replica] = seq
        }
        return compact(mergedVv, cloud + other.cloud)
    }

    override fun equals(other: Any?): Boolean =
        other is DotContext && vv == other.vv && cloud == other.cloud

    override fun hashCode(): Int = 31 * vv.hashCode() + cloud.hashCode()

    override fun toString(): String = "DotContext(vv=$vv, cloud=$cloud)"

    public companion object {
        /** The empty history — nothing witnessed. */
        public val EMPTY: DotContext = DotContext(emptyMap(), emptySet())

        /** A history witnessing exactly [dots]. */
        public fun of(vararg dots: Dot): DotContext =
            dots.fold(EMPTY) { ctx, dot -> ctx.add(dot) }

        /**
         * Normalize `(vv, cloud)`: drop cloud dots already covered by the vector,
         * and repeatedly extend the vector by any cloud dot that sits exactly at
         * the next contiguous seq, until no more compaction is possible.
         */
        private fun compact(vv: Map<ReplicaId, Long>, cloud: Set<Dot>): DotContext {
            val newVv = HashMap(vv)
            val remaining = cloud.toMutableSet()
            var changed = true
            while (changed) {
                changed = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val dot = iterator.next()
                    val current = newVv[dot.replica] ?: 0L
                    when {
                        dot.seq <= current -> iterator.remove()
                        dot.seq == current + 1L -> {
                            newVv[dot.replica] = dot.seq
                            iterator.remove()
                            changed = true
                        }
                        // else: a gap remains before this dot — keep it in the cloud
                    }
                }
            }
            return DotContext(newVv, remaining)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kuilt-crdt:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/DotContext.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DotContextTest.kt
git commit -m "feat(kuilt-crdt): DotContext — version vector + dot cloud causal history"
```

---

## Task 3: `DotContext` passes the `Quilted` laws

**Files:**
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/DotContextConformanceTest.kt`

- [ ] **Step 1: Write the conformance test**

Create `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/DotContextConformanceTest.kt`:

```kotlin
package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.ReplicaId

/** DotContext is a join-semilattice (union of causal histories) — it obeys every law. */
internal class DotContextConformanceTest : QuiltedConformanceSuite<DotContext>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<DotContext> = listOf(
        DotContext.EMPTY,
        DotContext.of(Dot(a, 1L)),
        DotContext.of(Dot(a, 1L), Dot(a, 2L)),
        DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        DotContext.of(Dot(b, 3L)), // a gap dot, exercised under the laws
    )
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `./gradlew :kuilt-conformance:jvmTest`
Expected: PASS — the four inherited laws hold for `DotContext`.

- [ ] **Step 3: Commit**

```bash
git add kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/DotContextConformanceTest.kt
git commit -m "test(kuilt-conformance): DotContext satisfies the Quilted laws"
```

---

## Task 4: Full multiplatform verification + PR

- [ ] **Step 1: Build both modules across all platforms (enforces explicitApi)**

Run: `./gradlew :kuilt-crdt:build :kuilt-conformance:build`
Expected: BUILD SUCCESSFUL. Run in the foreground with `timeout: 600000`.

- [ ] **Step 2: Run all-platform tests**

Run: `./gradlew :kuilt-crdt:allTests :kuilt-conformance:allTests`
Expected: BUILD SUCCESSFUL — behaviour + laws hold on JVM, wasmJs, iOS sim, macOS. Foreground, `timeout: 600000`.

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin <branch>
gh pr create --title "feat(kuilt-crdt): Rung 1 — Dot + DotContext (causal history)" --body "<see below>"
```

PR body:

```
> 🤖 This comment was generated by Claude on behalf of @keddie.

Rung 1 of the :kuilt-crdt zoo (design: docs/superpowers/specs/2026-06-06-crdt-module-design.md).

- `ReplicaId` + `Dot` — clock-free unique operation names (replica, seq).
- `DotContext` — causal history as a version vector + dot cloud, compacting as gaps fill; robust to kuilt's out-of-order/duplicate frame delivery. Implements `Quilted` (merge = union of histories) and passes `QuiltedConformanceSuite`.

Foundation for the DotStore family next (Rung 2). All serializable.
```

- [ ] **Step 4: Enable auto-merge**

Run: `gh pr merge <n> --auto --squash`

---

## Self-Review notes

- **Spec coverage:** Implements Rung 1 ("Dots + causal context (`Dot`, version vector)"). The plan deliberately includes the **dot cloud** beyond the spec's "version vector" wording, because a bare vector cannot represent non-contiguous dots and kuilt explicitly reorders/duplicates frames — the cloud is required for the DotStore merge (Rung 2) and replicator (Rung 12) to be correct.
- **Type consistency:** `ReplicaId`, `Dot(replica, seq)`, `DotContext.{EMPTY, of, contains, nextDot, add, piece}` are used identically across Tasks 1–3.
- **Canonical equality:** `compact` guarantees a single representation per causal history, so `DotContext.equals` is sound — required by `QuiltedConformanceSuite`, which checks laws with `==`.
