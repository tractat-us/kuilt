# Metrics collection pipeline — OTel-SDK ingress + metric tap — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Feed an app's OpenTelemetry-SDK metrics into kuilt's durable CRDT buffer automatically (ingress, #1025) and let a test/CI process pull that buffer off a device over a `Seam` (tap, #1026) — the metrics twin of the shipped log pipeline. Design: `docs/superpowers/specs/2026-07-01-metrics-pipeline-design.md`.

**Architecture:** A monotonic `DOUBLE_SUM` needs full precision, so a new `GCounterDouble` CRDT (#1035) lands **first**; `WarpMetricExporter` gains a parallel double-sum store; a `MetricCatalog` composite becomes the single replicated metric value; `KuiltMetricExporter` (JVM/Android, `compileOnly` OTel) maps `MetricData` → the buffer with `DELTA` temporality and a non-blocking enqueue+drain; and `MetricTapHost`/`MetricTapClient` replicate `MetricCatalog` over a `Quilter`, mirroring the log tap. Signals stay **separate replicators muxed over one transport** (`MuxSeam`) — never a unified composite CRDT, never a new module.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization (CBOR), kotlinx-coroutines (`Channel`, `Quilter`), OpenTelemetry Java SDK (`compileOnly`), JUnit + `kotlinx-coroutines-test`.

## Cross-plan dependency

- **`GCounterDouble` (#1035) is a hard prerequisite for the ingress bridge (#1025).** Tasks 1–2 must land before Task 6. The metric tap (#1026, Tasks 7–10) depends on `MetricCatalog`/`snapshotAll()` (Tasks 3–4) but **not** on the ingress bridge — tap and ingress can be developed in parallel once the buffer work (Tasks 1–4) is done.
- Suggested PR split: **PR-A** = Tasks 1–4 (`GCounterDouble` + buffer double-sum store + `MetricCatalog`, all in `:kuilt-crdt`/`:kuilt-otel`, closes #1035); **PR-B** = Tasks 5–6 (ingress, closes #1025); **PR-C** = Tasks 7–10 (tap, closes #1026). PR-B and PR-C both stack on PR-A.

## Global Constraints

- `explicitApi()` is enforced — every new public declaration gets an explicit `public`/`internal`.
- **No `!!`** anywhere — detekt hard-fails on it. Use `?:`, `requireNotNull`, or a local `val`.
- Test method names carry no `test` prefix; `@Test` suffices. Multi-assert tests use `assertAll()`.
- Coroutine tests use `StandardTestDispatcher(testScheduler)` (or `backgroundScope` + `runCurrent()`), a **seeded** `Random`, and virtual time. **No production dispatchers** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`) in test sources.
- Scope-owning types take a **required** injected `CoroutineScope` — never a real-dispatcher default.
- In any coroutine/suspend context use `runCatchingCancellable` (from `:kuilt-core`), never bare `runCatching`.
- `:kuilt-otel-sdk` is `kuilt.jvmAndroidOnly=true` — the bridge lives in `jvmAndAndroidMain`; `commonMain`/native/wasm compile empty. OTel artifacts stay `compileOnly`.
- References policy: abstract use case only; no third-party citations; no other `tractat-us/*` repos.
- **Verify before declaring done:** `./gradlew :<module>:build detektAll --rerun-tasks` (add `--no-build-cache` if any test-compile shows `FROM-CACHE`); `detektAll`, never bare `detekt`. Prefix every gradle command with `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem &&`.

---

### Task 1: `GCounterDouble` CRDT (#1035)

**Files:**
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/GCounterDouble.kt`
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/GCounterDoubleTest.kt`

**Interface produced:** `class GCounterDouble : Quilted<GCounterDouble>` with `value: Double` (canonical-order sum), `count(replica): Double`, `inc(replica, by: Double): Patch<GCounterDouble>` (`require(by > 0.0)`), `piece` = elementwise max, `ZERO`, `of(...)`.

- [ ] **Step 1: Write the failing test**

`GCounterDoubleTest.kt`:
```kotlin
package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GCounterDoubleTest {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    @Test
    fun incAccumulatesOwnSlot() {
        var g = GCounterDouble.ZERO
        g = g.piece(g.inc(a, 1.5).delta)
        g = g.piece(g.inc(a, 2.25).delta)
        assertEquals(3.75, g.value)
        assertEquals(3.75, g.count(a))
        assertEquals(0.0, g.count(b))
    }

    @Test
    fun incMustBePositive() {
        assertFailsWith<IllegalArgumentException> { GCounterDouble.ZERO.inc(a, 0.0) }
        assertFailsWith<IllegalArgumentException> { GCounterDouble.ZERO.inc(a, -1.0) }
    }

    @Test
    fun pieceIsElementwiseMaxAndSumsSlots() {
        val ga = GCounterDouble.of(a to 2.0, b to 1.0)
        val gb = GCounterDouble.of(b to 4.0, c to 3.0)
        val merged = ga.piece(gb)
        // a=2, b=max(1,4)=4, c=3  → 9.0
        assertEquals(9.0, merged.value)
        assertEquals(merged, gb.piece(ga)) // commutative
        assertEquals(merged, merged.piece(gb)) // idempotent
    }

    @Test
    fun valueIsCanonicalOrderIndependent() {
        // Same converged state built two ways must report the same value bit-for-bit.
        val forward = GCounterDouble.of(a to 0.1, b to 0.2, c to 0.3)
        val shuffled = GCounterDouble.of(c to 0.3, a to 0.1, b to 0.2)
        assertEquals(forward.value, shuffled.value)
        // And equal to an explicit canonical (sorted-key) sum.
        val canonical = listOf(a to 0.1, b to 0.2, c to 0.3).sortedBy { it.first }.sumOf { it.second }
        assertEquals(canonical, forward.value)
    }

    @Test
    fun deltaCarriesOnlyBumpedSlot() {
        val patch = GCounterDouble.of(a to 5.0).inc(a, 1.0)
        assertEquals(6.0, patch.delta.count(a))
        assertTrue(patch.delta.count(b) == 0.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`... && ./gradlew :kuilt-crdt:jvmTest --tests "*GCounterDoubleTest"`
Expected: FAIL — unresolved reference `GCounterDouble`.

- [ ] **Step 3: Write the implementation**

`GCounterDouble.kt` (mirror `GCounter.kt`; the one difference is the canonical-order `value`):
```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A grow-only counter over `Double` — the exact-precision sibling of [GCounter].
 *
 * Same lattice as [GCounter] (a per-replica map, [piece] is elementwise max), but the
 * slot values are `Double`. It exists so a monotonic OTLP `DOUBLE_SUM` metric folds
 * into a grow-only counter **without** truncation or fixed-point scaling.
 *
 * ## The one wrinkle: value determinism
 *
 * [value] sums the per-replica slots, and floating-point `+` is **not associative**, so
 * summing in map-iteration order could yield a slightly different [value] on two
 * replicas that hold *identical converged state*. The merged **state** always converges
 * (elementwise max is order-independent); only the derived [value] is order-sensitive.
 * [value] therefore sums in **canonical [ReplicaId] order** so every replica computes the
 * same number. (Very large magnitude differences can still lose low-order bits — the
 * honest floating-point limit, analogous to [HyperLogLog]'s estimation error.)
 *
 * @sample us.tractat.kuilt.crdt.sampleGCounterDouble
 */
@Serializable
public class GCounterDouble private constructor(
    private val counts: Map<ReplicaId, Double>,
) : Quilted<GCounterDouble> {

    /** The counter's value: the sum of all per-replica counts, in canonical replica order. */
    public val value: Double get() = counts.entries.sortedBy { it.key }.sumOf { it.value }

    /** This replica's current count (0.0 if it has never incremented). */
    public fun count(replica: ReplicaId): Double = counts[replica] ?: 0.0

    /**
     * Increment [replica]'s own slot by [by] (must be > 0). Returns the delta to merge
     * in with [piece]; the receiver is unchanged.
     */
    public fun inc(replica: ReplicaId, by: Double = 1.0): Patch<GCounterDouble> {
        require(by > 0.0) { "GCounterDouble increment must be positive, was $by" }
        val newCount = (counts[replica] ?: 0.0) + by
        return Patch(GCounterDouble(mapOf(replica to newCount)))
    }

    /** The join: elementwise max of the two count maps. */
    override fun piece(other: GCounterDouble): GCounterDouble {
        val merged = HashMap<ReplicaId, Double>(counts)
        for ((replica, c) in other.counts) {
            val current = merged[replica]
            if (current == null || c > current) merged[replica] = c
        }
        return GCounterDouble(merged)
    }

    override fun equals(other: Any?): Boolean = other is GCounterDouble && counts == other.counts
    override fun hashCode(): Int = counts.hashCode()
    override fun toString(): String = "GCounterDouble($counts)"

    public companion object {
        /** The zero counter. */
        public val ZERO: GCounterDouble = GCounterDouble(emptyMap())

        /** A counter with the given per-replica counts (test/seed helper). */
        public fun of(vararg pairs: Pair<ReplicaId, Double>): GCounterDouble = GCounterDouble(pairs.toMap())
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

`... && ./gradlew :kuilt-crdt:jvmTest --tests "*GCounterDoubleTest"` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

`feat(kuilt-crdt): GCounterDouble — Double-valued grow-only counter for exact metric sums (#1035)`

---

### Task 2: `GCounterDouble` zoo docs (`@sample` + Writerside)

**Files:**
- Create: `kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/GCounterDoubleSamples.kt`
- Modify: `kuilt-crdt/module.md` (mention `GCounterDouble` next to `GCounter`)
- Create: `Writerside/topics/crdt-gcounterdouble.md` **or** add a `GCounterDouble` section to the existing counter topic (follow the per-type addition pipeline; accessible-first prose, verbatim-from citation)
- Modify: `Writerside/kuilt.tree` if a new topic file is added

**Note:** the `@sample` function is compiled as part of `commonTest` (the `kuilt.kmp-library` plugin adds `commonSamples` to test roots), so a broken sample breaks the build — this task's "test" is the compile.

- [ ] **Step 1: Write the sample referenced by the KDoc**

`GCounterDoubleSamples.kt`:
```kotlin
package us.tractat.kuilt.crdt

internal fun sampleGCounterDouble() {
    val phone = ReplicaId("phone")
    val watch = ReplicaId("watch")

    // Each device independently accumulates fractional seconds of CPU time.
    var onPhone = GCounterDouble.ZERO
    onPhone = onPhone.piece(onPhone.inc(phone, 0.75).delta)

    var onWatch = GCounterDouble.ZERO
    onWatch = onWatch.piece(onWatch.inc(watch, 0.5).delta)

    // Merge either direction — the total is the same, to the bit.
    val total = onPhone.piece(onWatch).value // 1.25
    check(total == 1.25)
}
```

- [ ] **Step 2: Write the Writerside topic / section**

Accessible-first: open with "a running fractional total that several devices add to independently, and that always agrees when they sync" — no "CRDT"/"lattice" in the first sentence. Cite the snippet `<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/GCounterDoubleSamples.kt#sampleGCounterDouble -->`. If a new topic file is added, register it in `Writerside/kuilt.tree` next to the existing counter topic.

- [ ] **Step 3: Verify the sample compiles**

`... && ./gradlew :kuilt-crdt:compileTestKotlinJvm` → SUCCESS (samples are on the test source set).

- [ ] **Step 4: Commit**

`docs(kuilt-crdt): GCounterDouble zoo docs + @sample (#1035)`

---

### Task 3: `WarpMetricExporter` parallel double-sum store

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpMetricExporter.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpMetricExporterTest.kt` (add cases)

**Interface produced:** `incrementSumDouble(key, by: Double): MetricExportResult`, `mergeSumDouble(key, remote: GCounterDouble)`, `doubleSumValue(key): Double`, `doubleSumSnapshot(key): GCounterDouble`; store key `otel.metrics.sums.double`; the four-map buffer cap. The `Long` sum path is untouched.

- [ ] **Step 1: Write the failing test**

Add to `WarpMetricExporterTest.kt`:
```kotlin
    // ---- Double sum metrics (GCounterDouble — exact-precision monotonic) ----

    @Test
    fun doubleSumAccumulatesExactly() = runTest {
        val exporter = exporterFor()
        val key = sumKey("cpu.seconds")
        exporter.incrementSumDouble(key, by = 0.75)
        exporter.incrementSumDouble(key, by = 0.25)
        assertEquals(1.0, exporter.doubleSumValue(key))
    }

    @Test
    fun doubleSumIsSeparateFromLongSum() = runTest {
        val exporter = exporterFor()
        val key = sumKey("requests")
        exporter.incrementSum(key, by = 2L)
        exporter.incrementSumDouble(key, by = 1.5)
        assertEquals(2L, exporter.sumValue(key))
        assertEquals(1.5, exporter.doubleSumValue(key))
    }

    @Test
    fun doubleSumRecoversFromStore() = runTest {
        val store = InMemoryDurableStore()
        exporterFor(store = store).incrementSumDouble(sumKey("cpu.seconds"), by = 3.5)
        val recovered = exporterFor(store = store).also { it.recover() }
        assertEquals(3.5, recovered.doubleSumValue(sumKey("cpu.seconds")))
    }

    @Test
    fun doubleSumMergeIsIdempotent() = runTest {
        val exporter = exporterFor()
        val key = sumKey("cpu.seconds")
        val remote = GCounterDouble.of(replicaB to 4.0)
        exporter.mergeSumDouble(key, remote)
        exporter.mergeSumDouble(key, remote)
        assertEquals(4.0, exporter.doubleSumValue(key))
    }
```
Add `import us.tractat.kuilt.crdt.GCounterDouble` to the test.

- [ ] **Step 2: Run test to verify it fails**

`... && ./gradlew :kuilt-otel:jvmTest --tests "*WarpMetricExporterTest"`
Expected: FAIL — unresolved `incrementSumDouble`/`doubleSumValue`/`mergeSumDouble`.

- [ ] **Step 3: Implement — add the fourth store, mirroring the `Long` sum store**

In `WarpMetricExporter.kt`:

Add the import and the backing map (next to `sums`):
```kotlin
import us.tractat.kuilt.crdt.GCounterDouble
```
```kotlin
    private val sumsDouble: LinkedHashMap<MetricKey, GCounterDouble> = LinkedHashMap()
```
In the `companion object`, add the store key and serializers:
```kotlin
        private val SUM_DOUBLE_STORE_KEY = StoreKey("otel.metrics.sums.double")
        private val gcounterDoubleSerializer = GCounterDouble.serializer()
        private val sumsDoubleSerializer = MapSerializer(metricKeySerializer, gcounterDoubleSerializer)
```
Extend `recover()` and add the recovery helper:
```kotlin
    public suspend fun recover() {
        recoverSums()
        recoverSumsDouble()
        recoverGauges()
        recoverCardinalities()
    }

    private suspend fun recoverSumsDouble() {
        val bytes = store.read(SUM_DOUBLE_STORE_KEY) ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, GCounterDouble>> {
            cbor.decodeFromByteArray(sumsDoubleSerializer, bytes)
        }.getOrNull() ?: run {
            logger.warn { "otel.metrics.sums.double: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> sumsDouble[k] = v } }
    }
```
Add the public methods (mirror the `Long` sum block):
```kotlin
    /**
     * Increment the exact-precision cumulative sum for [key] by [by] on this replica.
     * The double-precision sibling of [incrementSum]; a monotonic OTLP `DOUBLE_SUM`
     * routes here. Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun incrementSumDouble(key: MetricKey, by: Double): MetricExportResult {
        val encoded = lock.withLock {
            maybeEvictForNewKey(key, sumsDouble)
            val current = sumsDouble.getOrPut(key) { GCounterDouble.ZERO }
            sumsDouble[key] = current.piece(current.inc(replica, by).delta)
            encodeSumsDouble()
        }
        return persistSumsDouble(encoded, key)
    }

    /** Merge a remote [GCounterDouble] snapshot into this exporter's double-sum for [key]. Idempotent. */
    public suspend fun mergeSumDouble(key: MetricKey, remote: GCounterDouble): MetricExportResult {
        val encoded = lock.withLock {
            val current = sumsDouble[key] ?: GCounterDouble.ZERO
            sumsDouble[key] = current.piece(remote)
            encodeSumsDouble()
        }
        return persistSumsDouble(encoded, key)
    }

    /** Read the current double-sum value for [key], or 0.0 if never incremented. */
    public fun doubleSumValue(key: MetricKey): Double = lock.withLock { sumsDouble[key]?.value ?: 0.0 }

    /** Return a snapshot of the [GCounterDouble] for [key] (for gossip/anti-entropy). */
    public fun doubleSumSnapshot(key: MetricKey): GCounterDouble = lock.withLock { sumsDouble[key] ?: GCounterDouble.ZERO }
```
Add the encode/persist helpers:
```kotlin
    private fun encodeSumsDouble(): ByteArray = cbor.encodeToByteArray(sumsDoubleSerializer, sumsDouble)
    private suspend fun persistSumsDouble(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(SUM_DOUBLE_STORE_KEY, encoded, key)
```
Fold `sumsDouble` into the eviction bookkeeping — update the three helpers so the cap counts and evicts all four maps:
```kotlin
    private fun totalCount(): Int = sums.size + sumsDouble.size + gauges.size + cardinalities.size

    private fun evictOne() {
        val victim = when (bufferPolicy) {
            MetricBufferPolicy.DROP_OLDEST -> pickOldest()
            MetricBufferPolicy.DROP_NEWEST -> pickNewest()
        } ?: return
        logEviction(victim)
        sums.remove(victim); sumsDouble.remove(victim); gauges.remove(victim); cardinalities.remove(victim)
    }

    private fun pickOldest(): MetricKey? = listOfNotNull(
        sums.keys.firstOrNull(), sumsDouble.keys.firstOrNull(), gauges.keys.firstOrNull(), cardinalities.keys.firstOrNull(),
    ).firstOrNull()

    private fun pickNewest(): MetricKey? = listOfNotNull(
        sums.keys.lastOrNull(), sumsDouble.keys.lastOrNull(), gauges.keys.lastOrNull(), cardinalities.keys.lastOrNull(),
    ).lastOrNull()
```
And `metricCount()`:
```kotlin
    public fun metricCount(): Int = lock.withLock { sums.size + sumsDouble.size + gauges.size + cardinalities.size }
```

- [ ] **Step 4: Run test to verify it passes**

`... && ./gradlew :kuilt-otel:jvmTest --tests "*WarpMetricExporterTest"` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

`feat(kuilt-otel): WarpMetricExporter double-sum store (incrementSumDouble) (#1035)`

---

### Task 4: `MetricCatalog` composite CRDT + `snapshotAll()`

**Files:**
- Create: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/MetricCatalog.kt`
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpMetricExporter.kt` (add `snapshotAll()`)
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/MetricCatalogTest.kt`

**Interface produced:** `class MetricCatalog(sums, doubleSums, gauges, cardinalities) : Quilted<MetricCatalog>` (key-union + per-value join); `WarpMetricExporter.snapshotAll(): MetricCatalog`.

- [ ] **Step 1: Write the failing test**

`MetricCatalogTest.kt`:
```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricCatalogTest {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private fun sk(n: String) = MetricKey(n, MetricKind.SUM)

    @Test
    fun pieceUnionsKeysAndJoinsValues() {
        val left = MetricCatalog(sums = mapOf(sk("x") to GCounter.of(a to 1L)))
        val right = MetricCatalog(sums = mapOf(sk("x") to GCounter.of(b to 2L), sk("y") to GCounter.of(a to 5L)))
        val merged = left.piece(right)
        assertEquals(3L, merged.sums.getValue(sk("x")).value) // a=1,b=2
        assertEquals(5L, merged.sums.getValue(sk("y")).value)
        assertEquals(merged, right.piece(left)) // commutative
        assertEquals(merged, merged.piece(right)) // idempotent
    }

    @Test
    fun pieceMergesDoubleSums() {
        val left = MetricCatalog(doubleSums = mapOf(sk("cpu") to GCounterDouble.of(a to 1.5)))
        val right = MetricCatalog(doubleSums = mapOf(sk("cpu") to GCounterDouble.of(b to 2.5)))
        assertEquals(4.0, left.piece(right).doubleSums.getValue(sk("cpu")).value)
    }

    @Test
    fun snapshotAllReflectsEveryStore() = runTest {
        val exporter = WarpMetricExporter(replica = a, store = InMemoryDurableStore())
        exporter.incrementSum(MetricKey("req", MetricKind.SUM), by = 4L)
        exporter.incrementSumDouble(MetricKey("cpu", MetricKind.SUM), by = 2.5)
        exporter.setGauge(MetricKey("temp", MetricKind.GAUGE), 21.0, timestamp = 1L)
        exporter.addCardinality(MetricKey("users", MetricKind.CARDINALITY), "u1")
        val cat = exporter.snapshotAll()
        assertEquals(4L, cat.sums.getValue(MetricKey("req", MetricKind.SUM)).value)
        assertEquals(2.5, cat.doubleSums.getValue(MetricKey("cpu", MetricKind.SUM)).value)
        assertEquals(21.0, cat.gauges.getValue(MetricKey("temp", MetricKind.GAUGE)).value)
        assertEquals(1L, cat.cardinalities.getValue(MetricKey("users", MetricKind.CARDINALITY)).estimate())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`... && ./gradlew :kuilt-otel:jvmTest --tests "*MetricCatalogTest"` → FAIL (unresolved `MetricCatalog`, `snapshotAll`).

- [ ] **Step 3: Implement**

`MetricCatalog.kt`:
```kotlin
package us.tractat.kuilt.otel

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.Quilted

/**
 * A converged snapshot of every metric series a device holds — counters, gauges, and
 * distinct-count estimates — bundled into one value that replicates as a whole.
 *
 * This is the metric tap's replication surface: the analogue of the log buffer's
 * `Rga<LogRecord>`. It is a **metrics-internal** composite (four maps of per-kind
 * CRDTs); it deliberately does **not** bundle logs — signals replicate as separate
 * values muxed over one transport, never a unified CRDT.
 *
 * [piece] unions the keys of each map and joins matching values by that value's own
 * CRDT lattice, so the composite converges by construction. No observed-remove
 * semantics: buffer eviction is a local cap, never a replicated delete, so a plain
 * grow-merge map is sufficient (a key evicted on the device but already pulled lingers
 * on the puller — acceptable for a diagnostic snapshot).
 */
@Serializable
public class MetricCatalog(
    public val sums: Map<MetricKey, GCounter> = emptyMap(),
    public val doubleSums: Map<MetricKey, GCounterDouble> = emptyMap(),
    public val gauges: Map<MetricKey, LWWRegister<Double>> = emptyMap(),
    public val cardinalities: Map<MetricKey, HyperLogLog> = emptyMap(),
) : Quilted<MetricCatalog> {

    override fun piece(other: MetricCatalog): MetricCatalog = MetricCatalog(
        sums = mergeMaps(sums, other.sums),
        doubleSums = mergeMaps(doubleSums, other.doubleSums),
        gauges = mergeMaps(gauges, other.gauges),
        cardinalities = mergeMaps(cardinalities, other.cardinalities),
    )

    override fun equals(other: Any?): Boolean =
        other is MetricCatalog && sums == other.sums && doubleSums == other.doubleSums &&
            gauges == other.gauges && cardinalities == other.cardinalities

    override fun hashCode(): Int {
        var h = sums.hashCode()
        h = 31 * h + doubleSums.hashCode()
        h = 31 * h + gauges.hashCode()
        h = 31 * h + cardinalities.hashCode()
        return h
    }

    private companion object {
        fun <K, S : Quilted<S>> mergeMaps(a: Map<K, S>, b: Map<K, S>): Map<K, S> {
            if (b.isEmpty()) return a
            if (a.isEmpty()) return b
            val out = HashMap<K, S>(a)
            for ((k, v) in b) {
                val current = out[k]
                out[k] = if (current == null) v else current.piece(v)
            }
            return out
        }
    }
}
```
Add to `WarpMetricExporter.kt` (in the Diagnostics section):
```kotlin
    /**
     * A converged snapshot of **every** metric series across all four kinds, as one
     * replicable [MetricCatalog]. The metric analogue of the log buffer's `snapshot()`;
     * the tap host offers this value to a joining puller.
     */
    public fun snapshotAll(): MetricCatalog = lock.withLock {
        MetricCatalog(
            sums = sums.toMap(),
            doubleSums = sumsDouble.toMap(),
            gauges = gauges.toMap(),
            cardinalities = cardinalities.toMap(),
        )
    }
```

- [ ] **Step 4: Run test to verify it passes** → `... && ./gradlew :kuilt-otel:jvmTest --tests "*MetricCatalogTest"`.

- [ ] **Step 5: Full-build gate for PR-A**

`... && ./gradlew :kuilt-crdt:build :kuilt-otel:build detektAll --rerun-tasks` — confirm Android/Native variants compile (the `jvmTest`-hides-Android trap). Commit: `feat(kuilt-otel): MetricCatalog composite + snapshotAll() replication surface (#1026)`.

---

### Task 5: OTel-metrics catalog entry + `:kuilt-otel-sdk` build wiring

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `kuilt-otel-sdk/build.gradle.kts`

- [ ] **Step 1: Add the metrics SDK artifact to the catalog**

In `libs.versions.toml`, next to the existing `opentelemetry-sdk-logs` line (shares the `otel` version ref, `1.45.0`):
```toml
opentelemetry-sdk-metrics = { module = "io.opentelemetry:opentelemetry-sdk-metrics", version.ref = "otel" }
```

- [ ] **Step 2: Wire it `compileOnly` in `jvmAndAndroidMain` and as a test runtime dep**

In `kuilt-otel-sdk/build.gradle.kts`, add to the `jvmAndAndroidMain` `dependencies { ... }` block:
```kotlin
                compileOnly(libs.opentelemetry.sdk.metrics)
```
and to `jvmTest.dependencies { ... }`:
```kotlin
            implementation(libs.opentelemetry.sdk.metrics)
```

- [ ] **Step 3: Verify wiring resolves (compile only, no code yet)**

`... && ./gradlew :kuilt-otel-sdk:compileKotlinJvm` → SUCCESS. Commit: `delight(kuilt-otel-sdk): pin opentelemetry-sdk-metrics for the metric ingress bridge (#1025)`.

---

### Task 6: `KuiltMetricExporter` ingress bridge (#1025)

**Files:**
- Create: `kuilt-otel-sdk/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/otel/sdk/KuiltMetricExporter.kt`
- Test: `kuilt-otel-sdk/src/jvmTest/kotlin/us/tractat/kuilt/otel/sdk/KuiltMetricExporterTest.kt`

**Interface produced:** `class KuiltMetricExporter(exporter: WarpMetricExporter, scope: CoroutineScope) : MetricExporter` — non-blocking enqueue+drain, `getAggregationTemporality(...) = DELTA`, mapping per the design table.

- [ ] **Step 1: Write the failing test**

The test hand-rolls tiny fakes for the OTel `MetricData`/`SumData`/`PointData` interfaces (version-robust — avoids the SDK's `internal.data` immutable factories). Only the abstract methods actually consulted are given real values; typed accessors like `getLongSumData()` resolve through `MetricData`'s `default` methods off `getData()`+`getType()`.

`KuiltMetricExporterTest.kt`:
```kotlin
package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.DoublePointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.data.SumData
import io.opentelemetry.sdk.metrics.data.GaugeData
import io.opentelemetry.sdk.metrics.data.DoubleExemplarData
import io.opentelemetry.sdk.metrics.data.LongExemplarData
import io.opentelemetry.sdk.resources.Resource
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KuiltMetricExporterTest {

    private fun buffer() = WarpMetricExporter(replica = ReplicaId("dev"), store = InMemoryDurableStore())

    // ---- fakes (only the consulted methods carry real values) ----

    private fun longPoint(value: Long, attrs: Attributes = Attributes.empty(), epoch: Long = 1_000L) =
        object : LongPointData {
            override fun getStartEpochNanos() = 0L
            override fun getEpochNanos() = epoch
            override fun getAttributes() = attrs
            override fun getValue() = value
            override fun getExemplars(): List<LongExemplarData> = emptyList()
        }

    private fun doublePoint(value: Double, attrs: Attributes = Attributes.empty(), epoch: Long = 1_000L) =
        object : DoublePointData {
            override fun getStartEpochNanos() = 0L
            override fun getEpochNanos() = epoch
            override fun getAttributes() = attrs
            override fun getValue() = value
            override fun getExemplars(): List<DoubleExemplarData> = emptyList()
        }

    private fun <T> sum(monotonic: Boolean, points: Collection<T>) = object : SumData<T> {
        override fun isMonotonic() = monotonic
        override fun getAggregationTemporality() = AggregationTemporality.DELTA
        override fun getPoints() = points
    }

    private fun <T> gauge(points: Collection<T>) = object : GaugeData<T> {
        override fun getPoints() = points
    }

    private fun metric(name: String, type: MetricDataType, data: Any) = object : MetricData {
        override fun getResource() = Resource.empty()
        override fun getInstrumentationScopeInfo() = InstrumentationScopeInfo.empty()
        override fun getName() = name
        override fun getDescription() = ""
        override fun getUnit() = ""
        override fun getType() = type
        @Suppress("UNCHECKED_CAST")
        override fun getData() = data as io.opentelemetry.sdk.metrics.data.Data<*>
    }

    // ---- tests ----

    @Test
    fun temporalityIsDelta() {
        val bridge = KuiltMetricExporter(buffer(), StandardTestDispatcher().let { kotlinx.coroutines.CoroutineScope(it) })
        InstrumentType.entries.forEach {
            assertEquals(AggregationTemporality.DELTA, bridge.getAggregationTemporality(it))
        }
    }

    @Test
    fun monotonicLongSumIncrementsGCounter() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("requests", MetricDataType.LONG_SUM, sum(monotonic = true, points = listOf(longPoint(3L))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(3L, exp.sumValue(MetricKey("requests", MetricKind.SUM)))
    }

    @Test
    fun monotonicDoubleSumIncrementsGCounterDouble() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("cpu.seconds", MetricDataType.DOUBLE_SUM, sum(monotonic = true, points = listOf(doublePoint(0.75))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(0.75, exp.doubleSumValue(MetricKey("cpu.seconds", MetricKind.SUM)))
    }

    @Test
    fun doubleGaugeSetsLwwRegister() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("temp", MetricDataType.DOUBLE_GAUGE, gauge(listOf(doublePoint(21.5, epoch = 5L))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(21.5, exp.gaugeValue(MetricKey("temp", MetricKind.GAUGE)))
    }

    @Test
    fun nonMonotonicSumBecomesGauge() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("queue.depth", MetricDataType.LONG_SUM, sum(monotonic = false, points = listOf(longPoint(7L, epoch = 9L))))
        bridge.export(listOf(md))
        runCurrent()
        assertEquals(7.0, exp.gaugeValue(MetricKey("queue.depth", MetricKind.GAUGE)))
    }

    @Test
    fun histogramIsDroppedNotFailed() = runTest {
        val exp = buffer()
        val bridge = KuiltMetricExporter(exp, backgroundScope)
        val md = metric("latency", MetricDataType.HISTOGRAM, gauge<LongPointData>(emptyList()))
        val code = bridge.export(listOf(md))
        runCurrent()
        assertEquals(0, exp.metricCount()) // nothing written
        assertTrue(code.isSuccess) // dropped is not an export failure
    }

    @Test
    fun resultCodeSucceedsAfterDrain() = runTest {
        val bridge = KuiltMetricExporter(buffer(), backgroundScope)
        val md = metric("requests", MetricDataType.LONG_SUM, sum(monotonic = true, points = listOf(longPoint(1L))))
        val code = bridge.export(listOf(md))
        runCurrent()
        assertTrue(code.isSuccess)
    }

    @Test
    fun shutdownDrainsCleanly() = runTest {
        val bridge = KuiltMetricExporter(buffer(), backgroundScope)
        bridge.export(listOf(metric("r", MetricDataType.LONG_SUM, sum(true, listOf(longPoint(1L))))))
        val code = bridge.shutdown()
        runCurrent()
        assertTrue(code.isSuccess)
    }
}
```
> Note: `CompletableResultCode.isSuccess` returns null until completed; assert after `runCurrent()`. If a target OTel version renames a fake's abstract method, adjust the `object :` overrides — the interfaces are the SPI ground truth.

- [ ] **Step 2: Run test to verify it fails**

`source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-otel-sdk:jvmTest --tests "*KuiltMetricExporterTest"`
Expected: FAIL — unresolved `KuiltMetricExporter`.

- [ ] **Step 3: Write the implementation**

`KuiltMetricExporter.kt`:
```kotlin
package us.tractat.kuilt.otel.sdk

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.DoublePointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.export.MetricExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.MetricExportResult
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.sdk.KuiltMetricExporter")

/**
 * An OpenTelemetry SDK [MetricExporter] that funnels metric points into kuilt's durable
 * [WarpMetricExporter] buffer — the metric twin of `KuiltLogRecordExporter`.
 *
 * For apps **already** running the OTel SDK: register this on the metric reader and every
 * monotonic counter and gauge also lands in kuilt's offline-first buffer, extractable via
 * the metric tap — without adopting kuilt's own instrumentation. Purely additive.
 *
 * ## Delta temporality (load-bearing)
 *
 * [getAggregationTemporality] returns [AggregationTemporality.DELTA] for every instrument
 * type. `WarpMetricExporter.incrementSum(by)` **adds** `by`, i.e. it is a delta operation;
 * a cumulative point (running total) would double-count on every collection. Requesting
 * delta makes each sum point exactly the increment to add — and a delta is inherently
 * additive, so two replicas' counters merge to the true total with no coordination.
 *
 * ## Non-blocking bridge
 *
 * `export`/`flush`/`shutdown` return a [CompletableResultCode]. [export] enqueues the raw
 * batch on an unbounded [Channel] and returns immediately; a single scope-bound drain
 * coroutine maps and applies each [MetricData], then completes the batch's code.
 *
 * ## Mapping
 * - monotonic `LONG_SUM` → `incrementSum`; monotonic `DOUBLE_SUM` → `incrementSumDouble`.
 * - non-monotonic sums (UpDownCounter) and gauges → `setGauge`.
 * - histograms / exponential histograms / summaries → **dropped** (WARN once), never a failure.
 * - a delta of ≤ 0 for a monotonic sum is skipped (grow-only counters reject it).
 *
 * @param exporter the durable kuilt buffer points are written into.
 * @param scope the drain coroutine's scope (required — never a real-dispatcher default).
 */
public class KuiltMetricExporter(
    private val exporter: WarpMetricExporter,
    scope: CoroutineScope,
) : MetricExporter {

    private class Batch(val metrics: Collection<MetricData>, val code: CompletableResultCode)

    private val queue = Channel<Batch>(Channel.UNLIMITED)

    private val drain = scope.launch {
        for (batch in queue) {
            val ok = runCatchingCancellable {
                batch.metrics.map { applyMetric(it) }.all { it }
            }.getOrDefault(false)
            if (ok) batch.code.succeed() else batch.code.fail()
        }
    }

    override fun export(metrics: Collection<MetricData>): CompletableResultCode {
        val code = CompletableResultCode()
        if (queue.trySend(Batch(metrics.toList(), code)).isFailure) code.fail()
        return code
    }

    override fun flush(): CompletableResultCode {
        val code = CompletableResultCode()
        if (queue.trySend(Batch(emptyList(), code)).isFailure) code.fail()
        return code
    }

    override fun shutdown(): CompletableResultCode {
        val code = CompletableResultCode()
        queue.close()
        drain.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) code.succeed() else code.fail()
        }
        return code
    }

    /** Delta so each sum point maps onto incrementSum(by); gauges ignore temporality. */
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        AggregationTemporality.DELTA

    /** Apply one metric's points; return whether every point-write succeeded (drop = success). */
    private suspend fun applyMetric(md: MetricData): Boolean {
        val name = md.name
        return when (md.type) {
            MetricDataType.LONG_SUM -> {
                val s = md.longSumData
                s.points.map { p ->
                    if (s.isMonotonic) incLong(name, p) else setGaugeOk(name, p.attributes, p.value.toDouble(), p.epochNanos)
                }.all { it }
            }
            MetricDataType.DOUBLE_SUM -> {
                val s = md.doubleSumData
                s.points.map { p ->
                    if (s.isMonotonic) incDouble(name, p) else setGaugeOk(name, p.attributes, p.value, p.epochNanos)
                }.all { it }
            }
            MetricDataType.LONG_GAUGE ->
                md.longGaugeData.points.map { setGaugeOk(name, it.attributes, it.value.toDouble(), it.epochNanos) }.all { it }
            MetricDataType.DOUBLE_GAUGE ->
                md.doubleGaugeData.points.map { setGaugeOk(name, it.attributes, it.value, it.epochNanos) }.all { it }
            MetricDataType.HISTOGRAM, MetricDataType.EXPONENTIAL_HISTOGRAM, MetricDataType.SUMMARY -> {
                logger.warn { "KuiltMetricExporter: dropping unsupported metric '$name' (${md.type}) — no CRDT for it (#798)" }
                true
            }
        }
    }

    private suspend fun incLong(name: String, p: LongPointData): Boolean {
        if (p.value <= 0L) return true // grow-only rejects ≤0; a delta of 0/negative is a no-op
        return exporter.incrementSum(MetricKey(name, MetricKind.SUM, attrs(p.attributes)), p.value) is MetricExportResult.Success
    }

    private suspend fun incDouble(name: String, p: DoublePointData): Boolean {
        if (p.value <= 0.0) return true
        return exporter.incrementSumDouble(MetricKey(name, MetricKind.SUM, attrs(p.attributes)), p.value) is MetricExportResult.Success
    }

    private suspend fun setGaugeOk(name: String, attributes: Attributes, value: Double, epochNanos: Long): Boolean =
        exporter.setGauge(MetricKey(name, MetricKind.GAUGE, attrs(attributes)), value, epochNanos) is MetricExportResult.Success

    private fun attrs(a: Attributes): Map<String, String> = buildMap { a.forEach { k, v -> put(k.key, v.toString()) } }
}
```

- [ ] **Step 4: Run test to verify it passes**

`... && ./gradlew :kuilt-otel-sdk:jvmTest --tests "*KuiltMetricExporterTest"` → BUILD SUCCESSFUL.

- [ ] **Step 5: Full-build gate for PR-B**

`... && ./gradlew :kuilt-otel-sdk:build detektAll --rerun-tasks` (add `--no-build-cache` if any test-compile is `FROM-CACHE`). Confirm the Android variant compiles. Commit: `feat(kuilt-otel-sdk): KuiltMetricExporter — OTel-SDK metric ingress bridge (#1025)`.

---

### Task 7: `MetricTapConfig` + `MetricTapWire`

**Files:**
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/MetricTapConfig.kt`
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/MetricTapWire.kt`
- Test: `kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/MetricTapWireTest.kt`

**Interface produced:** `MetricTapConfig` (mirrors `LogTapConfig`, pattern `kuilt-metric-tap`); `internal val MetricTapCbor` + `internal fun metricCatalogSerializer(): KSerializer<MetricCatalog>`.

- [ ] **Step 1: Write the failing test**

`MetricTapWireTest.kt`:
```kotlin
package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricTapWireTest {
    @Test
    fun metricCatalogRoundTripsThroughCbor() {
        val cat = MetricCatalog(sums = mapOf(MetricKey("x", MetricKind.SUM) to GCounter.of(ReplicaId("A") to 3L)))
        val bytes = MetricTapCbor.encodeToByteArray(metricCatalogSerializer(), cat)
        val back = MetricTapCbor.decodeFromByteArray(metricCatalogSerializer(), bytes)
        assertEquals(cat, back)
    }

    @Test
    fun configDefaultsMatchTheTapPattern() {
        assertEquals("kuilt-metric-tap", MetricTapConfig().pattern.value)
    }
}
```

- [ ] **Step 2: Run to verify it fails** → `... && ./gradlew :kuilt-otel-tap:jvmTest --tests "*MetricTapWireTest"`.

- [ ] **Step 3: Implement**

`MetricTapConfig.kt` (mirror `LogTapConfig.kt` exactly, renaming):
```kotlin
package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning for a metric tap. Defaults are conservative — the tap is something a developer
 * turns on to reach a hard-to-get-at device, not an always-on production feature.
 *
 * @param pattern rendezvous pattern the host opens and a client joins.
 * @param syncInterval how often the host re-reads the buffer and offers new state.
 * @param pullTimeout upper bound on a single [MetricTapClient.pull].
 * @param pullSettleStep quiet window after first state arrives before treating a snapshot as complete.
 * @param quilterConfig replication tuning; tests set `expectVirtualTime = true`.
 */
public data class MetricTapConfig(
    val pattern: Pattern = Pattern("kuilt-metric-tap"),
    val syncInterval: Duration = 1.seconds,
    val pullTimeout: Duration = 10.seconds,
    val pullSettleStep: Duration = 200.milliseconds,
    val quilterConfig: QuilterConfig = QuilterConfig(),
)
```
`MetricTapWire.kt`:
```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.otel.MetricCatalog

/**
 * Binary codec for the metric tap's replication wire. `alwaysUseByteString` matches the
 * on-device buffer's CBOR settings so the HyperLogLog register arrays round-trip
 * byte-for-byte.
 */
internal val MetricTapCbor: Cbor = Cbor { alwaysUseByteString = true }

/** Serializer for the replicated metric composite. */
internal fun metricCatalogSerializer(): KSerializer<MetricCatalog> = MetricCatalog.serializer()
```

- [ ] **Step 4: Run to verify it passes**; **Step 5: Commit** `feat(kuilt-otel-tap): MetricTapConfig + wire codec (#1026)`.

---

### Task 8: `MetricTapHost` + `installMetricTap`

**Files:**
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/MetricTapHost.kt`
- Test: covered by the convergence test in Task 9 (host has no standalone behaviour to assert without a client).

**Interface produced:** `class MetricTapHost : ScopedCloseable` with `sync()`, `selfId`; `suspend fun installMetricTap(loom, exporter, scope, config): MetricTapHost`.

- [ ] **Step 1: Implement (mirror `LogTapHost.kt`, swapping `Rga<LogRecord>`→`MetricCatalog` and `snapshot()`→`snapshotAll()`)**

`MetricTapHost.kt`:
```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.quilter.Quilter

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.tap.MetricTapHost")

/**
 * The device side of a metric tap: a peer that offers its converged metric buffer for
 * extraction. Opt-in — nothing opens until [installMetricTap] is called.
 *
 * Rides a [Quilter] over the woven [Seam], replicating the device's [MetricCatalog]
 * (counters, gauges, cardinalities) to whoever joins. Replication is idempotent, so a
 * puller that reconnects converges without double-counting.
 */
public class MetricTapHost internal constructor(
    private val seam: Seam,
    private val exporter: WarpMetricExporter,
    parentScope: CoroutineScope,
    private val config: MetricTapConfig,
) : ScopedCloseable(parentScope) {

    private val replicator: Quilter<MetricCatalog> = Quilter(
        seam = seam,
        initial = exporter.snapshotAll(),
        valueSerializer = metricCatalogSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = MetricTapCbor,
    )

    init { scope.launch { offerLoop() } }

    /** This peer's own id — useful for per-device artifact naming on a multi-device harness. */
    public val selfId: PeerId get() = seam.selfId

    /** Offer the buffer's current converged state for replication now, without waiting for the tick. */
    public fun sync() {
        val snapshot = exporter.snapshotAll()
        if (snapshot != replicator.state.value) replicator.apply(Patch(snapshot))
    }

    private suspend fun offerLoop() {
        while (true) {
            delay(config.syncInterval)
            sync()
        }
    }

    override fun onClose() {
        logger.debug { "MetricTapHost(${seam.selfId}) closing" }
        replicator.close()
    }
}

/**
 * Install an opt-in metric tap and start offering the device's metric buffer. A no-op
 * until called. Hosts a session on [loom] — bind it to loopback by default.
 */
public suspend fun installMetricTap(
    loom: Loom,
    exporter: WarpMetricExporter,
    scope: CoroutineScope,
    config: MetricTapConfig = MetricTapConfig(),
): MetricTapHost {
    val seam = loom.host(config.pattern)
    return MetricTapHost(seam, exporter, scope, config)
}
```

- [ ] **Step 2: Verify it compiles** `... && ./gradlew :kuilt-otel-tap:compileKotlinJvm`; **Step 3: Commit** `feat(kuilt-otel-tap): MetricTapHost + installMetricTap (#1026)`.

---

### Task 9: `MetricTapClient.pull()` / `tail()` + convergence test

**Files:**
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/MetricTapClient.kt`
- Test: `kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/MetricTapConvergenceTest.kt`

**Interface produced:** `MetricSnapshot` (plain-value result); `class MetricTapClient : ScopedCloseable` with `suspend fun pull(): MetricSnapshot` and `fun tail(): Flow<MetricSnapshot>`.

- [ ] **Step 1: Write the failing test** (mirror `LogTapConvergenceTest.kt`; `InMemoryLoom` + `backgroundScope`)

`MetricTapConvergenceTest.kt`:
```kotlin
package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricTapConvergenceTest {
    private val config = MetricTapConfig(quilterConfig = us.tractat.kuilt.quilter.QuilterConfig(expectVirtualTime = true))

    @Test
    fun pullReconstructsEveryKind() = runTest {
        val exporter = WarpMetricExporter(replica = ReplicaId("dev"), store = InMemoryDurableStore())
        exporter.incrementSum(MetricKey("req", MetricKind.SUM), by = 4L)
        exporter.incrementSumDouble(MetricKey("cpu", MetricKind.SUM), by = 2.5)
        exporter.setGauge(MetricKey("temp", MetricKind.GAUGE), 21.0, timestamp = 1L)
        exporter.addCardinality(MetricKey("users", MetricKind.CARDINALITY), "u1")

        val loom = InMemoryLoom()
        val host = installMetricTap(loom, exporter, backgroundScope, config)
        val client = MetricTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config)

        val snap = client.pull()
        assertEquals(4L, snap.sums.getValue(MetricKey("req", MetricKind.SUM)))
        assertEquals(2.5, snap.doubleSums.getValue(MetricKey("cpu", MetricKind.SUM)))
        assertEquals(21.0, snap.gauges.getValue(MetricKey("temp", MetricKind.GAUGE)))
        assertEquals(1L, snap.cardinalities.getValue(MetricKey("users", MetricKind.CARDINALITY)))

        client.close(); host.close()
    }
}
```

- [ ] **Step 2: Run to verify it fails** → `... && ./gradlew :kuilt-otel-tap:jvmTest --tests "*MetricTapConvergenceTest"`.

- [ ] **Step 3: Implement (mirror `LogTapClient.kt`; the settle/await ceremony is identical, the collapse differs)**

`MetricTapClient.kt`:
```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.quilter.Quilter

/**
 * The reconstructed metric buffer: CRDTs collapsed to the numbers they report.
 */
public data class MetricSnapshot(
    public val sums: Map<MetricKey, Long>,
    public val doubleSums: Map<MetricKey, Double>,
    public val gauges: Map<MetricKey, Double>,
    public val cardinalities: Map<MetricKey, Long>,
)

/**
 * The test/harness side of a metric tap: join a device and read its metrics out.
 *
 * [pull] takes a one-shot converged snapshot; [tail] emits a fresh whole [MetricSnapshot]
 * each time the replicated state changes. Unlike the log tap's per-record [tail], metrics
 * have no per-record identity — the live view is the converged snapshot, re-emitted on
 * change.
 */
public class MetricTapClient(
    private val seam: Seam,
    parentScope: CoroutineScope,
    private val config: MetricTapConfig = MetricTapConfig(),
) : ScopedCloseable(parentScope) {

    private val replicator: Quilter<MetricCatalog> = Quilter(
        seam = seam,
        initial = MetricCatalog(),
        valueSerializer = metricCatalogSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = MetricTapCbor,
    )

    /** Wait for the host's state to replicate and settle, then collapse to plain values. */
    public suspend fun pull(): MetricSnapshot = withTimeout(config.pullTimeout) {
        awaitRemotePeer()
        val firstNonEmpty = replicator.state.first { it.isNotEmpty() }
        collapse(settle(firstNonEmpty))
    }

    /** Emit the converged snapshot on every change (deduped by value). */
    public fun tail(): Flow<MetricSnapshot> =
        replicator.state.map { collapse(it) }.distinctUntilChanged()

    private fun collapse(cat: MetricCatalog) = MetricSnapshot(
        sums = cat.sums.mapValues { it.value.value },
        doubleSums = cat.doubleSums.mapValues { it.value.value },
        // LWWRegister.value is nullable; a replicated gauge entry always carries a value
        // (the key exists only because setGauge wrote one). requireNotNull, never `!!` (detekt).
        gauges = cat.gauges.mapValues { requireNotNull(it.value.value) { "gauge ${it.key} has no value" } },
        cardinalities = cat.cardinalities.mapValues { it.value.estimate() },
    )

    private fun MetricCatalog.isNotEmpty(): Boolean =
        sums.isNotEmpty() || doubleSums.isNotEmpty() || gauges.isNotEmpty() || cardinalities.isNotEmpty()

    private suspend fun awaitRemotePeer() {
        seam.peers.first { peers -> peers.any { it != seam.selfId } }
    }

    private suspend fun settle(initial: MetricCatalog): MetricCatalog {
        var current = initial
        repeat(SETTLE_ITERATIONS) {
            val next = withTimeoutOrNull(config.pullSettleStep) {
                replicator.state.first { it != current }
            } ?: return current
            current = next
        }
        return current
    }

    override fun onClose() { replicator.close() }

    private companion object { const val SETTLE_ITERATIONS = 32 }
}
```

- [ ] **Step 4: Run to verify it passes** → `... && ./gradlew :kuilt-otel-tap:jvmTest --tests "*MetricTapConvergenceTest"`.

- [ ] **Step 5: Full-build gate for PR-C**

`... && ./gradlew :kuilt-otel-tap:build detektAll --rerun-tasks`. Commit: `feat(kuilt-otel-tap): MetricTapClient pull/tail over a Seam (#1026)`.

---

### Task 10 (optional / stretch): `installSignalTap` — logs + metrics over one `MuxSeam`

**Files:**
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/SignalTap.kt`
- Test: `kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/SignalTapTest.kt`

**Rationale:** the transport-unification half of the locked decision — one `Seam`, two replicators on distinct `MuxSeam` channels (tag `0` = logs, tag `1` = metrics). Only build this if the harness genuinely wants a single session for both signals; the per-signal taps above are independently useful.

- [ ] **Step 1: Write the failing test** — host both taps over one `InMemoryLoom`; a `SignalTapHost` wraps `MuxSeam(hostSeam)` and installs a `LogTapHost` on `.channel(0)` and a `MetricTapHost` on `.channel(1)`; the client wraps `MuxSeam(joinSeam)` and pulls logs from channel 0 and metrics from channel 1. Assert both reconstruct.

- [ ] **Step 2–4:** implement `SignalTapHost`/`SignalTapClient` as thin wiring over the two existing hosts/clients and `MuxSeam.channel(tag)` (from `:kuilt-core`); no new CRDT. Verify + commit `feat(kuilt-otel-tap): installSignalTap — logs+metrics muxed over one Seam (#1026)`.

---

## Final verification (before opening PRs)

- Per PR: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :<touched modules>:build detektAll --rerun-tasks` (add `--no-build-cache` if any test-compile shows `FROM-CACHE`). Confirm tasks are `EXECUTED`, not `FROM-CACHE`, and Android/Native variants compile.
- `explicitApi()`, no `!!`, seeded `Random`, `StandardTestDispatcher`/`backgroundScope`, `runCatchingCancellable` all satisfied.
- PR-A closes #1035; PR-B closes #1025; PR-C closes #1026. Each PR body: AI-attribution prefix, one-paragraph summary, `Part of #986`, stack links.
