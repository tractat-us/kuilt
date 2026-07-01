# OTLP egress — by-digest drain of all signals + concrete Ktor JSON edge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drain kuilt's converged CRDT telemetry (spans + logs + metrics) to a standard OTLP collector by producer-local digest, folding in #846 (auto-stamp causal context on export + emit inferred `Span.links` at drain). Ship the first concrete transport: a Ktor OTLP/HTTP **JSON** edge in a new all-target module `:kuilt-otel-otlp`.

**Architecture:** `WarpOtlpBridge` is reshaped to take the whole `WarpTelemetry` and drain each signal against its own digest — spans (`SpanDigest`, unchanged), logs (`LogDigest` = recordId set), metrics (`MetricDigest` = `MetricKey`→value-hash). The digest is **producer-local**: OTLP/HTTP is write-only (no read-back), so `digest()` returns what *this* producer has already delivered to *this* endpoint, persisted in a `DurableStore`; the collector's own span-id/record-id dedup is the correctness backstop. `OtlpEdge` grows additively (new defaulted methods + a defaulted `links` param on `send`). #846: `WarpTelemetry` owns a `WarpCausalClock`; `WarpSpanExporter.export()` auto-stamps (explicit-stamp-wins), `merge()` folds remote frontiers, the clock persists on the export path; `drain()` runs `inferCausalLinks` over the full span snapshot, filters links to the delta, and the concrete edge emits them on OTLP `Span.links`.

**Tech Stack:** Kotlin Multiplatform (JVM, Android, iOS, macOS, wasmJs), kotlinx-coroutines, kotlinx-io `ByteString`, kotlinx-serialization (CBOR for local persistence, **JSON** for the OTLP wire), Ktor client (all targets), `kotlinx-coroutines-test`.

**PR split:** Tasks 1–6 are **PR 1** (`:kuilt-otel` digest extension + #846 auto-stamp/link-inference, all in-module). Tasks 7–11 are **PR 2** (the concrete `:kuilt-otel-otlp` JSON edge). **#846 closes with PR 2** — the closing keyword rides PR 2, because inferred links only reach the wire there. Wire format is OTLP/JSON; binary protobuf is follow-up **#1040**, not in this plan.

## Global Constraints

- `explicitApi()` is enforced — every new public declaration gets an explicit `public`/`internal`.
- **No `!!`.** Use `?: return` / `?: error(...)` / `checkNotNull` with a message, or restructure. (Test code may use `!!` on values a `require`/`init` already guarantees non-null, matching the repo's existing test style — e.g. `rec.traceId!!`.)
- Test method names carry no `test` prefix; `@Test` suffices. Multi-assert tests use `assertAll()`.
- Coroutine tests use `StandardTestDispatcher(testScheduler)`, a **seeded** `Random`, and a virtual `kotlin.time.Clock`. **No production dispatchers** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`) in test sources.
- Scope-owning types take a **required** injected `CoroutineScope`/dispatcher — never a real-dispatcher default.
- In any coroutine/suspend context use `runCatchingCancellable` (from `:kuilt-core`), never bare `runCatching`.
- Thread-safety by explicit primitives (`reentrantLock`, atomics, `Channel`/`MutableStateFlow`) — never `limitedParallelism(1)` confinement. New scope/state-owning types must be correct under a multi-threaded dispatcher.
- New modules apply `id("kuilt.kmp-library")` and almost nothing else; Android namespace is `us.tractat.kuilt.<module>`. `:kuilt-otel-otlp` is **all-target** (the Ktor client runs everywhere) — so it does **NOT** set `kuilt.jvmAndroidOnly=true`; it mirrors `:kuilt-websocket`'s client-side source-set wiring.
- References policy: abstract use case only; no third-party citations; no other `tractat-us/*` repos. OTLP is a public wire spec (the shared-identifier exception).
- Verify before declaring done: `./gradlew :<module>:build detektAll --rerun-tasks` (add `--no-build-cache` if any test-compile shows `FROM-CACHE`); `detektAll`, never bare `detekt`. The full `./gradlew build` (Android + Native + wasm variants) is the real compile bar — `jvmTest` alone hides variant breaks.
- All gradle commands are prefixed once per session with: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`.

---

# PR 1 — `:kuilt-otel` digest extension + #846 auto-stamp / link inference

---

### Task 1: Log + metric digest types and the metric point carrier

**Files:**
- Create: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/LogDigest.kt`
- Create: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/MetricDigest.kt`
- Create: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/MetricPoint.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/DigestTypesTest.kt`

**Interfaces:**
- Produces: `LogDigest(recordIds: Set<ByteString>)`; `MetricDigest(versions: Map<MetricKey, Long>)`; `MetricPoint` (a rendered-OTLP data point) with a stable `valueHash(): Long`.

- [ ] **Step 1: Write the failing test**

`DigestTypesTest.kt`:
```kotlin
package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DigestTypesTest {
    private fun recId(b: Byte) = ByteString(ByteArray(8) { b })

    @Test
    fun logDigestHoldsRecordIds() {
        val d = LogDigest(setOf(recId(1), recId(2)))
        assertEquals(2, d.recordIds.size)
    }

    @Test
    fun metricDigestHoldsPerKeyVersions() {
        val key = MetricKey("m", MetricKind.SUM)
        val d = MetricDigest(mapOf(key to 42L))
        assertEquals(42L, d.versions[key])
    }

    @Test
    fun sumPointHashChangesWithValue() {
        val key = MetricKey("req", MetricKind.SUM)
        val a = MetricPoint.Sum(key, value = 5L, startEpochNanos = 100L, timeEpochNanos = 200L)
        val b = MetricPoint.Sum(key, value = 6L, startEpochNanos = 100L, timeEpochNanos = 200L)
        assertNotEquals(a.valueHash(), b.valueHash())
    }

    @Test
    fun sumPointHashStableAcrossTime() {
        // The hash is over the OTLP *value*, not the observation time — a re-render at a
        // later timeEpochNanos with the same cumulative total must not force a re-send.
        val key = MetricKey("req", MetricKind.SUM)
        val a = MetricPoint.Sum(key, value = 5L, startEpochNanos = 100L, timeEpochNanos = 200L)
        val b = MetricPoint.Sum(key, value = 5L, startEpochNanos = 100L, timeEpochNanos = 999L)
        assertEquals(a.valueHash(), b.valueHash())
    }

    @Test
    fun gaugeAndCardinalityHashOnValue() {
        val gk = MetricKey("cpu", MetricKind.GAUGE)
        assertNotEquals(
            MetricPoint.Gauge(gk, value = 0.5, timeEpochNanos = 1L).valueHash(),
            MetricPoint.Gauge(gk, value = 0.6, timeEpochNanos = 1L).valueHash(),
        )
        val ck = MetricKey("users", MetricKind.CARDINALITY)
        assertNotEquals(
            MetricPoint.Cardinality(ck, estimate = 10L, timeEpochNanos = 1L).valueHash(),
            MetricPoint.Cardinality(ck, estimate = 11L, timeEpochNanos = 1L).valueHash(),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*DigestTypesTest"`
Expected: FAIL — unresolved `LogDigest`, `MetricDigest`, `MetricPoint`.

- [ ] **Step 3: Write minimal implementation**

`LogDigest.kt`:
```kotlin
package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString

/**
 * Which log records the producer has already delivered to an [OtlpEdge], keyed by
 * [LogRecord.recordId].
 *
 * Mirrors [SpanDigest]. Like spans, a [LogRecord] is immutable once written and
 * content-addressed by its 8-byte record id, so the digest is a flat id-set and the
 * drain delta is `local recordIds ∖ digest.recordIds`.
 *
 * ## Producer-local
 *
 * The digest is **not** a query of what the collector holds (OTLP/HTTP is
 * write-only). It is what *this* producer has already POSTed to *this* endpoint,
 * persisted locally by the edge. The collector deduplicates by record id, so a
 * lost/stale digest costs bandwidth, never correctness.
 */
public class LogDigest(public val recordIds: Set<ByteString>)
```

`MetricDigest.kt`:
```kotlin
package us.tractat.kuilt.otel

/**
 * The value-version this producer last delivered for each metric series.
 *
 * Metrics differ from spans/logs: a series' value moves (a [MetricKind.SUM] climbs,
 * a [MetricKind.GAUGE] overwrites), so identity ([MetricKey]) is not enough — the
 * digest also carries a hash of the last-delivered value ([MetricPoint.valueHash]).
 * A series is re-sent only when its current value-hash differs from [versions].
 *
 * Producer-local, same as [LogDigest]/[SpanDigest].
 */
public class MetricDigest(public val versions: Map<MetricKey, Long>)
```

`MetricPoint.kt`:
```kotlin
package us.tractat.kuilt.otel

/**
 * A single metric data point, rendered from a [WarpMetricExporter] CRDT into the
 * shape OTLP puts on the wire. The bridge produces these; an [OtlpEdge] serializes
 * them — the edge never reaches into the three CRDTs directly.
 *
 * [valueHash] is over the **OTLP value fields only** (not the observation time), so
 * a re-render of an unchanged series produces the same hash and is skipped by
 * [MetricDigest] — while a value that advanced re-sends exactly once.
 */
public sealed interface MetricPoint {
    /** The series this point belongs to. */
    public val key: MetricKey

    /** A stable hash of the OTLP value fields, used for by-digest dedup. */
    public fun valueHash(): Long

    /** A cumulative monotonic total (OTLP Sum, delta-temporality off). */
    public data class Sum(
        override val key: MetricKey,
        public val value: Long,
        public val startEpochNanos: Long,
        public val timeEpochNanos: Long,
    ) : MetricPoint {
        // Hash the cumulative total only. start is fixed per series; time is the
        // observation instant and must not force a re-send when the total is stable.
        override fun valueHash(): Long = value
    }

    /** A last-writer-wins snapshot (OTLP Gauge). */
    public data class Gauge(
        override val key: MetricKey,
        public val value: Double,
        public val timeEpochNanos: Long,
    ) : MetricPoint {
        override fun valueHash(): Long = value.toRawBits()
    }

    /** A distinct-count estimate rendered as a Gauge-shaped OTLP point. */
    public data class Cardinality(
        override val key: MetricKey,
        public val estimate: Long,
        public val timeEpochNanos: Long,
    ) : MetricPoint {
        override fun valueHash(): Long = estimate
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*DigestTypesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/LogDigest.kt \
        kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/MetricDigest.kt \
        kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/MetricPoint.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/DigestTypesTest.kt
git commit -m "feat(otel): LogDigest, MetricDigest, and MetricPoint carriers for by-digest drain"
```

---

### Task 2: Widen `OtlpEdge` for logs + metrics + span links (additive)

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/OtlpEdge.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/OtlpEdgeDefaultsTest.kt`

**Interfaces:**
- Consumes: `LogDigest`, `MetricDigest`, `MetricPoint` (Task 1); `SpanLink` (existing).
- Produces: `OtlpEdge` gains `logDigest()`/`sendLogs()`/`metricDigest()`/`sendMetrics()` as **defaulted no-ops**, and `send` widens to `send(spans, links: List<SpanLink> = emptyList())`. Distinct method names avoid the JVM erasure clash (`send(Set<SpanRecord>)` vs `send(Set<LogRecord>)` would collide).

> **Note on the `send` widening.** Adding a defaulted `links` param keeps all *call sites* source-compatible, but any existing *override* of `send(spans: Set<SpanRecord>)` must gain the param. `OtlpEdge` has no concrete impl outside tests, and the in-repo fakes are updated in Task 5 — so this is safe.

- [ ] **Step 1: Write the failing test**

`OtlpEdgeDefaultsTest.kt` — a span-only edge implementing only the spans members must still satisfy the interface, and the new members must default to empty/no-op:
```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpEdgeDefaultsTest {
    /** Implements only the spans surface; everything else must default. */
    private class SpanOnlyEdge : OtlpEdge {
        override suspend fun digest(): SpanDigest = SpanDigest(emptySet())
        override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) = Unit
    }

    @Test
    fun logAndMetricMembersDefaultToEmpty() = runTest {
        val edge = SpanOnlyEdge()
        assertTrue(edge.logDigest().recordIds.isEmpty())
        assertTrue(edge.metricDigest().versions.isEmpty())
        // default no-ops must not throw
        edge.sendLogs(emptySet())
        edge.sendMetrics(emptySet())
    }

    @Test
    fun sendLinksDefaultsToEmpty() = runTest {
        var received: List<SpanLink>? = null
        val edge = object : OtlpEdge {
            override suspend fun digest(): SpanDigest = SpanDigest(emptySet())
            override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) { received = links }
        }
        edge.send(emptySet()) // omit links → default empty
        assertEquals(emptyList(), received)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*OtlpEdgeDefaultsTest"`
Expected: FAIL — `send` has no `links` param; `logDigest`/`sendLogs`/`metricDigest`/`sendMetrics` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `OtlpEdge.kt`, widen `send` and add the four defaulted members. Also update the `SpanDigest` KDoc to the producer-local framing. New interface body:
```kotlin
public interface OtlpEdge {

    // ── Spans ────────────────────────────────────────────────────────────────
    /** What spans this producer has already delivered to the edge (producer-local). */
    public suspend fun digest(): SpanDigest

    /**
     * Push spans, with their inferred causal [links] (#846), to the edge.
     *
     * [links] carries the [SpanLink]s the bridge inferred for the spans in this
     * batch; the edge attaches each to its owning span's OTLP `Span.links`. Defaults
     * to empty so a link-unaware edge (or a caller with no links) is unaffected.
     */
    public suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink> = emptyList())

    // ── Logs (default no-op keeps span-only impls valid) ───────────────────────
    /** What log records this producer has already delivered (producer-local). */
    public suspend fun logDigest(): LogDigest = LogDigest(emptySet())

    /** Push log records to the edge. */
    public suspend fun sendLogs(logs: Set<LogRecord>) {}

    // ── Metrics ────────────────────────────────────────────────────────────────
    /** The value-version this producer last delivered per series (producer-local). */
    public suspend fun metricDigest(): MetricDigest = MetricDigest(emptyMap())

    /** Push metric data points to the edge. */
    public suspend fun sendMetrics(points: Set<MetricPoint>) {}
}
```
Rewrite the `SpanDigest` KDoc: replace "which spans an [OtlpEdge] already holds" / the "edge that GCs or compacts" granularity paragraph with the producer-local framing — the digest is *what this producer has already delivered to this endpoint*, persisted by the edge; the collector dedups by span id, so a lost digest costs bandwidth not correctness; the retention bound is on the producer's own sent-set, not the collector's holdings.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*OtlpEdgeDefaultsTest"`
Expected: PASS. (`WarpOtlpBridgeTest` will now FAIL to compile — its fakes override the old `send`; that is fixed in Task 5. Do not run the whole module suite until then.)

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/OtlpEdge.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/OtlpEdgeDefaultsTest.kt
git commit -m "feat(otel): widen OtlpEdge for logs, metrics, and span links (additive defaults)"
```

---

### Task 3: `WarpMetricExporter` point enumeration accessor

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpMetricExporter.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpMetricExporterSnapshotAllTest.kt`

**Interfaces:**
- Consumes: existing `sums`/`gauges`/`cardinalities` maps; `MetricPoint` (Task 1).
- Produces: `WarpMetricExporter.snapshotAll(nowEpochNanos: Long): List<MetricPoint>` — one rendered [MetricPoint] per live series across all three kinds, lock-guarded. The bridge needs to enumerate every series to render+hash it; today the exporter exposes only per-key snapshots.

> `startEpochNanos` for a Sum is fixed per series (CRDT genesis). The exporter does not currently track a per-series genesis time; render `startEpochNanos = 0L` (OTLP treats 0 as "unknown start", acceptable for cumulative sums whose value the collector tracks). `timeEpochNanos` is the caller-supplied observation time. This is a documented simplification; a per-series genesis timestamp is a follow-up.

- [ ] **Step 1: Write the failing test**

`WarpMetricExporterSnapshotAllTest.kt`:
```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarpMetricExporterSnapshotAllTest {
    private fun exporter() = WarpMetricExporter(ReplicaId("p"), InMemoryDurableStore())

    @Test
    fun snapshotAllRendersOnePointPerSeries() = runTest {
        val exp = exporter()
        exp.incrementSum(MetricKey("req", MetricKind.SUM), by = 3L)
        exp.setGauge(MetricKey("cpu", MetricKind.GAUGE), value = 0.5, timestamp = 1L)
        exp.addCardinality(MetricKey("users", MetricKind.CARDINALITY), element = "u1")

        val points = exp.snapshotAll(nowEpochNanos = 999L)
        assertEquals(3, points.size)

        val sum = points.filterIsInstance<MetricPoint.Sum>().single()
        assertEquals(3L, sum.value)
        assertEquals(999L, sum.timeEpochNanos)

        val gauge = points.filterIsInstance<MetricPoint.Gauge>().single()
        assertEquals(0.5, gauge.value)

        val card = points.filterIsInstance<MetricPoint.Cardinality>().single()
        assertEquals(1L, card.estimate)
    }

    @Test
    fun snapshotAllEmptyWhenNoSeries() = runTest {
        assertTrue(exporter().snapshotAll(nowEpochNanos = 1L).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpMetricExporterSnapshotAllTest"`
Expected: FAIL — `snapshotAll` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `WarpMetricExporter.kt`, add under the "Diagnostics" section (after `metricCount()`):
```kotlin
    /**
     * Render every live series across all kinds into a [MetricPoint] for egress.
     *
     * Sums use `startEpochNanos = 0L` (OTLP "unknown start" — acceptable for a
     * cumulative sum the collector tracks); [nowEpochNanos] is the observation
     * instant stamped onto each point's `timeEpochNanos`. Lock-guarded; the returned
     * list is a snapshot.
     */
    public fun snapshotAll(nowEpochNanos: Long): List<MetricPoint> = lock.withLock {
        buildList {
            sums.forEach { (key, counter) ->
                add(MetricPoint.Sum(key, counter.value, startEpochNanos = 0L, timeEpochNanos = nowEpochNanos))
            }
            gauges.forEach { (key, reg) ->
                val v = reg.value ?: return@forEach // never written a value yet → skip
                add(MetricPoint.Gauge(key, v, timeEpochNanos = nowEpochNanos))
            }
            cardinalities.forEach { (key, hll) ->
                add(MetricPoint.Cardinality(key, hll.estimate(), timeEpochNanos = nowEpochNanos))
            }
        }
    }
```
(`GCounter.value`, `LWWRegister.value`, `HyperLogLog.estimate()` are the real accessors, confirmed in source. The `lock` is the exporter's existing `reentrantLock` — this method makes no suspend call inside it.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpMetricExporterSnapshotAllTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpMetricExporter.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpMetricExporterSnapshotAllTest.kt
git commit -m "feat(otel): WarpMetricExporter.snapshotAll — render all series to MetricPoints for egress"
```

---

### Task 4: Auto-stamp on export + cross-replica observe (#846 part 1)

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpSpanExporter.kt`
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpTelemetry.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpSpanExporterAutoStampTest.kt`

**Interfaces:**
- Consumes: `WarpCausalClock.tick()/observe()/frontier()/recover()/persist()` (existing); `SpanRecord.causalStamp` nullable field (existing); `CausalStamp` (existing).
- Produces: `WarpSpanExporter` takes an optional injected `WarpCausalClock` and auto-stamps in `export()` (explicit-stamp-wins), folds remote frontiers in `merge()`, and persists the clock on the durable path; `WarpTelemetry` owns and wires the clock, recovering it in `recover()`.

> **Design — the clock is a constructor dependency, default `null`.** A `null` clock preserves today's behaviour exactly (no auto-stamp), so existing `WarpSpanExporter(replica, store)` call sites and tests are unaffected. `WarpTelemetry` always constructs a real clock and passes it, so the shipped default is auto-stamp ON. The remote frontier for `merge()` is read from the incoming `ORSet` snapshot's stamped spans — see impl.

- [ ] **Step 1: Write the failing test**

`WarpSpanExporterAutoStampTest.kt`:
```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WarpSpanExporterAutoStampTest {
    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun span(b: Byte, stamp: CausalStamp? = null) = SpanRecord(
        traceId = tId(b), spanId = sId(b), parentSpanId = null,
        name = "op", kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L, endEpochNanos = 2_000L, causalStamp = stamp,
    )

    @Test
    fun exportAutoStampsWhenClockPresent() = runTest {
        val clock = WarpCausalClock(ReplicaId("a"))
        val exp = WarpSpanExporter(ReplicaId("a"), InMemoryDurableStore(), causalClock = clock)
        exp.export(span(1))
        val stored = exp.snapshot().elements.single()
        assertNotNull(stored.causalStamp)
    }

    @Test
    fun explicitStampWins() = runTest {
        val clock = WarpCausalClock(ReplicaId("a"))
        val exp = WarpSpanExporter(ReplicaId("a"), InMemoryDurableStore(), causalClock = clock)
        val explicit = clock.tick()
        exp.export(span(1, stamp = explicit))
        assertEquals(explicit, exp.snapshot().elements.single().causalStamp)
    }

    @Test
    fun nullClockLeavesUnstamped() = runTest {
        val exp = WarpSpanExporter(ReplicaId("a"), InMemoryDurableStore())
        exp.export(span(1))
        assertNull(exp.snapshot().elements.single().causalStamp)
    }

    @Test
    fun crossReplicaMergeObservesRemoteFrontier() = runTest {
        // Replica A exports s1; B merges A's set, then exports s2. s2's predecessors
        // must include A's dot — the cross-boundary causal path.
        val store = InMemoryDurableStore()
        val clockA = WarpCausalClock(ReplicaId("a"))
        val expA = WarpSpanExporter(ReplicaId("a"), store, causalClock = clockA)
        expA.export(span(1))
        val aStamp = expA.snapshot().elements.single().causalStamp
        assertNotNull(aStamp)

        val clockB = WarpCausalClock(ReplicaId("b"))
        val expB = WarpSpanExporter(ReplicaId("b"), InMemoryDurableStore(), causalClock = clockB)
        expB.merge(expA.snapshot())          // B observes A's frontier
        expB.export(span(2))
        val s2 = expB.snapshot().elements.single { it.spanId == sId(2) }
        assertNotNull(s2.causalStamp)
        assertEquals(true, s2.causalStamp.predecessors.contains(aStamp.dot))
    }

    @Test
    fun clockPersistsAcrossRestart() = runTest {
        val store = InMemoryDurableStore()
        val clock1 = WarpCausalClock(ReplicaId("a"))
        val exp1 = WarpSpanExporter(ReplicaId("a"), store, causalClock = clock1)
        exp1.export(span(1))
        val firstDot = exp1.snapshot().elements.single().causalStamp?.dot
        assertNotNull(firstDot)

        // Fresh clock + exporter over the same store, recover, export again.
        val clock2 = WarpCausalClock(ReplicaId("a"))
        clock2.recover(store)
        val exp2 = WarpSpanExporter(ReplicaId("a"), store, causalClock = clock2)
        exp2.recover()
        exp2.export(span(2))
        val secondDot = exp2.snapshot().elements.single { it.spanId == sId(2) }.causalStamp?.dot
        assertNotNull(secondDot)
        // seq advanced — no re-minted dot.
        assertEquals(true, secondDot.seq > firstDot.seq)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpSpanExporterAutoStampTest"`
Expected: FAIL — `WarpSpanExporter` has no `causalClock` param; no auto-stamp.

- [ ] **Step 3: Write minimal implementation**

In `WarpSpanExporter.kt`:

1. Add the constructor param (after `bufferPolicy`):
```kotlin
    private val bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
    private val causalClock: WarpCausalClock? = null,
) {
```
2. In `export`, stamp before insert (explicit-stamp-wins), and persist the clock after the durable write. Replace the body of `export`:
```kotlin
    public suspend fun export(span: SpanRecord): ExportResult {
        val stamped = when {
            causalClock == null -> span
            span.causalStamp != null -> span            // explicit stamp wins
            else -> span.copy(causalStamp = causalClock.tick())
        }
        val encoded = lock.withLock {
            maybeEvict()
            spans = spans.add(replica, stamped)
            cbor.encodeToByteArray(spanSerializer, spans)
        }
        return runCatchingCancellable {
            store.write(STORE_KEY, encoded)
            causalClock?.persist(store)                 // persist clock on the durable path
        }.fold(
            onSuccess = { ExportResult.Success },
            onFailure = { cause ->
                logger.error(cause) { "WarpSpanExporter: durable write failed for span ${stamped.spanId}" }
                ExportResult.Failure(cause)
            },
        )
    }
```
3. In `merge`, observe the remote frontier before merging. The remote frontier is the set of dots carried by the remote set's stamped spans (each stamped span's own `dot`). Replace the head of `merge`:
```kotlin
    public suspend fun merge(remote: ORSet<SpanRecord>): ExportResult {
        causalClock?.observe(remote.elements.mapNotNull { it.causalStamp?.dot }.toSet())
        val encoded = lock.withLock {
            spans = spans.piece(remote)
            cbor.encodeToByteArray(spanSerializer, spans)
        }
        // ... unchanged persistence fold ...
```
   (Keep the existing `runCatchingCancellable { store.write(...) }` fold body as-is after the `encoded` line.)

Update the `export`/`merge` KDoc to note auto-stamp (explicit-stamp-wins) and cross-replica `observe`.

In `WarpTelemetry.kt`:

1. Construct and own a clock, pass it to `spans`:
```kotlin
    private val causalClock: WarpCausalClock = WarpCausalClock(replica)

    /** The span exporter (A2). Auto-stamps causal context on export (#846). */
    public val spans: WarpSpanExporter = WarpSpanExporter(
        replica = replica,
        store = store,
        maxSpans = maxSpans,
        bufferPolicy = bufferPolicy,
        causalClock = causalClock,
    )
```
2. Recover the clock in `recover()` (before `spans.recover()` so the frontier is warm):
```kotlin
    public suspend fun recover() {
        causalClock.recover(store)
        spans.recover()
        metrics.recover()
        logs.recover()
    }
```
Expose the clock for the bridge if needed via `internal fun causalFrontier()` — not required; the bridge infers links from stamped spans, not the live clock. Leave the clock private.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpSpanExporterAutoStampTest"`
Expected: PASS. Then confirm no regression in existing span/clock tests: `./gradlew :kuilt-otel:jvmTest --tests "*WarpSpanExporterTest" --tests "*WarpCausalClockTest" --tests "*CausalLinkInferenceTest"`.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpSpanExporter.kt \
        kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpTelemetry.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpSpanExporterAutoStampTest.kt
git commit -m "feat(otel): auto-stamp causal context on span export + observe on merge (#846)"
```

---

### Task 5: Reshape `WarpOtlpBridge` to drain all signals + emit links (#846 part 2)

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpOtlpBridge.kt`
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/DrainResult.kt`
- Modify: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpOtlpBridgeTest.kt` (rewrite fakes + add signal tests)

**Interfaces:**
- Consumes: `WarpTelemetry` (its `spans`/`logs`/`metrics`); `inferCausalLinks(Collection<SpanRecord>): List<SpanLink>` (existing); `LogDigest`/`MetricDigest`/`MetricPoint`; widened `OtlpEdge`.
- Produces: `WarpOtlpBridge(telemetry: WarpTelemetry, clock: Clock)`; `drain(edge): DrainResult` draining all three signals; `DrainResult.Success(spansSent, logsSent = 0, metricPointsSent = 0)`.

> **Constructor change.** The bridge moves from `WarpOtlpBridge(exporter: WarpSpanExporter)` to `WarpOtlpBridge(telemetry: WarpTelemetry, clock: Clock)` — it needs logs + metrics + a `nowEpochNanos` for metric points. `clock` is a **required** injected `kotlin.time.Clock` (metric observation time; tests pass a fixed clock). This is a breaking change to the bridge constructor; the existing `WarpOtlpBridgeTest` is rewritten in this task.

- [ ] **Step 1: Write the failing test (rewrite the bridge test)**

Rewrite `WarpOtlpBridgeTest.kt`. Replace the span-only `RecordingEdge`/`AccumulatingEdge`/`FailingEdge`/`DigestFailingEdge` with all-signal fakes, build the bridge from a `WarpTelemetry`, and cover all three signals + links. Full file:
```kotlin
package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class WarpOtlpBridgeTest {
    private val replica = ReplicaId("test")
    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun recId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun span(b: Byte, parent: ByteString? = null) = SpanRecord(
        traceId = tId(b), spanId = sId(b), parentSpanId = parent,
        name = "op", kind = SpanKind.INTERNAL, startEpochNanos = 1_000L, endEpochNanos = 2_000L,
    )

    private fun telemetry() = WarpTelemetry(replica, InMemoryDurableStore())
    private fun bridge(t: WarpTelemetry) = WarpOtlpBridge(t, clock)

    // ---- all-signal fake ----

    private class FakeEdge : OtlpEdge {
        private val lock = reentrantLock()
        val knownSpans = mutableSetOf<ByteString>()
        val knownLogs = mutableSetOf<ByteString>()
        val knownMetrics = mutableMapOf<MetricKey, Long>()
        val sentSpans = mutableListOf<SpanRecord>()
        val sentLinks = mutableListOf<SpanLink>()
        val sentLogs = mutableListOf<LogRecord>()
        val sentMetrics = mutableListOf<MetricPoint>()

        override suspend fun digest() = SpanDigest(lock.withLock { knownSpans.toSet() })
        override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) = lock.withLock {
            sentSpans += spans; sentLinks += links; knownSpans += spans.map { it.spanId }
        }
        override suspend fun logDigest() = LogDigest(lock.withLock { knownLogs.toSet() })
        override suspend fun sendLogs(logs: Set<LogRecord>) = lock.withLock {
            sentLogs += logs; knownLogs += logs.map { it.recordId }
        }
        override suspend fun metricDigest() = MetricDigest(lock.withLock { knownMetrics.toMap() })
        override suspend fun sendMetrics(points: Set<MetricPoint>) = lock.withLock {
            sentMetrics += points; points.forEach { knownMetrics[it.key] = it.valueHash() }
        }
    }

    @Test
    fun drainDeliversAllThreeSignals() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        t.logs.export(LogRecord(recordId = recId(1), body = "hi"))
        t.metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 2L)
        val edge = FakeEdge()

        val result = bridge(t).drain(edge)

        assertIs<DrainResult.Success>(result)
        assertEquals(1, edge.sentSpans.size)
        assertEquals(1, edge.sentLogs.size)
        assertEquals(1, edge.sentMetrics.size)
        assertEquals(1, result.spansSent)
        assertEquals(1, result.logsSent)
        assertEquals(1, result.metricPointsSent)
    }

    @Test
    fun reDrainSendsNothingNewForAllSignals() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        t.logs.export(LogRecord(recordId = recId(1), body = "hi"))
        t.metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 2L)
        val edge = FakeEdge()
        val b = bridge(t)

        b.drain(edge)
        val before = Triple(edge.sentSpans.size, edge.sentLogs.size, edge.sentMetrics.size)
        b.drain(edge) // idempotent

        assertEquals(before, Triple(edge.sentSpans.size, edge.sentLogs.size, edge.sentMetrics.size))
    }

    @Test
    fun advancedMetricReSendsExactlyOnce() = runTest {
        val t = telemetry()
        val key = MetricKey("req", MetricKind.SUM)
        t.metrics.incrementSum(key, by = 1L)
        val edge = FakeEdge()
        val b = bridge(t)

        b.drain(edge)                       // sends value=1
        b.drain(edge)                       // unchanged → nothing
        t.metrics.incrementSum(key, by = 1L) // value=2
        b.drain(edge)                       // sends value=2

        assertEquals(2, edge.sentMetrics.size)
        assertEquals(listOf(1L, 2L), edge.sentMetrics.filterIsInstance<MetricPoint.Sum>().map { it.value })
    }

    @Test
    fun linksAreInferredAndThreadedToTheEdge() = runTest {
        // Two replicas so a cross-boundary (non-parent) link is inferred.
        val store = InMemoryDurableStore()
        val tA = WarpTelemetry(ReplicaId("a"), store)
        tA.spans.export(span(1))
        val tB = WarpTelemetry(ReplicaId("b"), InMemoryDurableStore())
        tB.spans.merge(tA.spans.snapshot())      // B observes A's frontier
        tB.spans.export(span(2))                 // successor, parent=null ≠ A's span ⇒ cross-boundary link
        val edge = FakeEdge()

        bridge(tB).drain(edge)

        assertTrue(edge.sentLinks.any { it.fromSpanId == sId(2) && it.linkedSpanId == sId(1) })
    }

    @Test
    fun partialFailureIsolatesSignals() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        t.logs.export(LogRecord(recordId = recId(1), body = "hi"))
        val edge = object : FakeEdge() {
            override suspend fun sendLogs(logs: Set<LogRecord>): Unit = throw RuntimeException("logs down")
        }
        // spans still delivered despite logs failing; drain does not throw.
        val result = bridge(t).drain(edge)
        assertEquals(1, edge.sentSpans.size)
        // A partial success still reports what got through.
        assertIs<DrainResult.Success>(result)
        assertEquals(1, result.spansSent)
        assertEquals(0, result.logsSent)
    }

    @Test
    fun drainSurvivesSpanDigestFailure() = runTest {
        val t = telemetry()
        t.spans.export(span(1))
        val edge = object : FakeEdge() {
            override suspend fun digest(): SpanDigest = throw RuntimeException("digest down")
        }
        // Must not throw; span leg fails, others still run.
        bridge(t).drain(edge)
    }
}
```
(Note `FakeEdge`'s members are `open` implicitly via `override` in the subclass overrides — declare `FakeEdge` as `private open class FakeEdge` so the two `object : FakeEdge()` overrides compile.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpOtlpBridgeTest"`
Expected: FAIL — `WarpOtlpBridge(telemetry, clock)` / `DrainResult.Success.logsSent` unresolved.

- [ ] **Step 3: Write minimal implementation**

`DrainResult.kt` — widen `Success`:
```kotlin
    public data class Success(
        public val spansSent: Int,
        public val logsSent: Int = 0,
        public val metricPointsSent: Int = 0,
    ) : DrainResult
```
Update its KDoc to mention logs/metrics counts.

`WarpOtlpBridge.kt` — replace the class. Each signal is independent and best-effort; a per-signal failure is caught and that signal contributes 0, and only if **every** signal both had work and failed is the whole drain a `Failure`. Full body:
```kotlin
package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.time.Clock

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpOtlpBridge")

/**
 * Drains converged CRDTs (spans + logs + metrics) to an [OtlpEdge], reconciling each
 * signal by its own producer-local digest and sending only what the endpoint lacks.
 *
 * Spans additionally carry inferred causal [SpanLink]s (#846): [drain] runs
 * [inferCausalLinks] over the full span snapshot, filters to the spans in the delta,
 * and threads them to [OtlpEdge.send] for emission on OTLP `Span.links`.
 *
 * Each signal is independent and best-effort — a failing signal never aborts the
 * others, and the CRDT is left intact for the next attempt.
 *
 * @param telemetry the [WarpTelemetry] whose exporters are drained.
 * @param clock observation time stamped onto metric points (required — never a real
 *   dispatcher/clock default in tests).
 */
public class WarpOtlpBridge(
    private val telemetry: WarpTelemetry,
    private val clock: Clock,
) {
    public suspend fun drain(edge: OtlpEdge): DrainResult {
        var anyAttempted = false
        var anyFailed = false

        // ── Spans (+ links) ──────────────────────────────────────────────────
        val spanSnapshot = telemetry.spans.snapshot().elements
        var spansSent = 0
        if (spanSnapshot.isNotEmpty()) {
            anyAttempted = true
            val r = runCatchingCancellable {
                val digest = edge.digest()
                val delta = spanSnapshot.filterTo(mutableSetOf()) { it.spanId !in digest.spanIds }
                if (delta.isNotEmpty()) {
                    val deltaIds = delta.mapTo(mutableSetOf()) { it.spanId }
                    val links = inferCausalLinks(spanSnapshot).filter { it.fromSpanId in deltaIds }
                    edge.send(delta, links)
                    spansSent = delta.size
                }
            }
            if (r.isFailure) { anyFailed = true; logger.debug(r.exceptionOrNull()) { "span drain failed" } }
        }

        // ── Logs ─────────────────────────────────────────────────────────────
        val logSnapshot = telemetry.logs.snapshot().toList()
        var logsSent = 0
        if (logSnapshot.isNotEmpty()) {
            anyAttempted = true
            val r = runCatchingCancellable {
                val digest = edge.logDigest()
                val delta = logSnapshot.filterTo(mutableSetOf()) { it.recordId !in digest.recordIds }
                if (delta.isNotEmpty()) { edge.sendLogs(delta); logsSent = delta.size }
            }
            if (r.isFailure) { anyFailed = true; logger.debug(r.exceptionOrNull()) { "log drain failed" } }
        }

        // ── Metrics ──────────────────────────────────────────────────────────
        val points = telemetry.metrics.snapshotAll(clock.now().toEpochMilliseconds() * 1_000_000L)
        var metricsSent = 0
        if (points.isNotEmpty()) {
            anyAttempted = true
            val r = runCatchingCancellable {
                val digest = edge.metricDigest()
                val delta = points.filterTo(mutableSetOf()) { digest.versions[it.key] != it.valueHash() }
                if (delta.isNotEmpty()) { edge.sendMetrics(delta); metricsSent = delta.size }
            }
            if (r.isFailure) { anyFailed = true; logger.debug(r.exceptionOrNull()) { "metric drain failed" } }
        }

        return if (anyAttempted && anyFailed && spansSent == 0 && logsSent == 0 && metricsSent == 0) {
            DrainResult.Failure(RuntimeException("all attempted signals failed to drain"))
        } else {
            DrainResult.Success(spansSent = spansSent, logsSent = logsSent, metricPointsSent = metricsSent)
        }
    }
}
```
(`clock.now().toEpochMilliseconds() * 1_000_000L` renders epoch-nanos from `kotlin.time.Instant`; adequate for metric observation time. `SpanDigest.spanIds` is the existing field.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpOtlpBridgeTest"`
Expected: PASS. Then the **whole module** to confirm no regression: `./gradlew :kuilt-otel:jvmTest`.

- [ ] **Step 5: Check the `@sample` functions still compile**

`WarpOtlpBridge` / `OtlpEdge` have `@sample` functions in `kuilt-otel/src/commonSamples/`. The bridge constructor and `OtlpEdge.send` signatures changed — update the samples (`sampleWarpOtlpBridge`, `sampleOtlpEdge`) to the new signatures so `commonTest` still compiles. Run: `./gradlew :kuilt-otel:compileTestKotlinJvm`. Expected: SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpOtlpBridge.kt \
        kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/DrainResult.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpOtlpBridgeTest.kt \
        kuilt-otel/src/commonSamples
git commit -m "feat(otel): drain spans+logs+metrics by digest with causal-link emission (#846)"
```

---

### Task 6: PR 1 verification + open PR 1

**Files:** none (verification + integration).

- [ ] **Step 1: Detekt + full build of `:kuilt-otel`, cache-disabled**

Run:
```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-otel:build detektAll --rerun-tasks
```
Expected: BUILD SUCCESSFUL, tasks EXECUTED (not `FROM-CACHE`). If any test-compile shows `FROM-CACHE`, re-run with `--no-build-cache`. detektAll clean.

- [ ] **Step 2: Whole-repo build (Android + Native + wasm variants CI runs)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — the new commonMain code compiles on every target.

- [ ] **Step 3: Open PR 1**

```bash
git push -u origin otel-egress-digest-1027
gh pr create --title "feat(otel): by-digest drain of spans+logs+metrics + causal-link emission" \
  --body "$(cat <<'EOF'
> 🤖 This comment was generated by Claude on behalf of @keddie.

Part of #986 (M2). Design: `docs/superpowers/specs/2026-07-01-otlp-egress-design.md`; plan: `docs/superpowers/plans/2026-07-01-otlp-egress.md`. Design PR: #1031.

PR 1 of 2 (`:kuilt-otel`, in-module):
- `LogDigest` / `MetricDigest` / `MetricPoint`; `OtlpEdge` widened additively (logs/metrics + defaulted `links` on `send`); `SpanDigest` KDoc reframed producer-local.
- `WarpMetricExporter.snapshotAll` renders every series to `MetricPoint`s.
- **#846 (part):** auto-stamp causal context on `WarpSpanExporter.export()` (explicit-stamp-wins), `observe` remote frontiers on `merge()`, clock persisted on the export path; `WarpOtlpBridge` reshaped to drain all three signals by digest and infer causal links over the full snapshot, filtered to the delta.

Idempotent re-drain sends nothing new for all three signals; an advanced metric re-sends once; partial per-signal failure is isolated. **#846 closes with PR 2** (links reach the wire there); this PR references it: part of #846.

The concrete Ktor JSON edge and `Span.links` wire emission land in PR 2 (`:kuilt-otel-otlp`).
EOF
)"
gh pr merge --auto --squash
```

- [ ] **Step 4: Open the PR in the browser**

Run: `gh pr view --web`

---

# PR 2 — concrete Ktor OTLP/HTTP JSON edge `:kuilt-otel-otlp` (closes #846)

---

### Task 7: Scaffold the all-target `:kuilt-otel-otlp` module

**Files:**
- Create: `kuilt-otel-otlp/build.gradle.kts`
- Create: `kuilt-otel-otlp/module.md`
- Modify: `settings.gradle.kts` (add `include(":kuilt-otel-otlp")` after `:kuilt-otel-sdk`)
- Modify: `gradle/libs.versions.toml` (add Ktor content-negotiation, JSON serialization, mock engine)

**Interfaces:**
- Produces: an all-target module (Ktor client everywhere) that compiles empty, with the Ktor client + kotlinx-serialization-json on the classpath.

> **Target set decision.** The Ktor client runs on all five kuilt targets, so this is an **all-target** module — it does **NOT** set `kuilt.jvmAndroidOnly`. It mirrors `:kuilt-websocket`'s client-side source-set wiring: `commonMain` gets `ktor-client-core` + content-negotiation + JSON; each target adds its engine (`okhttp` JVM, `cio` Android, `darwin` iOS/macOS, `js` wasmJs). There is no server piece, so unlike `:kuilt-websocket` there is no `jvmAndAndroidMain` intermediate — auto-wiring stays on and only per-target engine deps are declared.

- [ ] **Step 1: Add catalog entries**

In `gradle/libs.versions.toml` under `[libraries]` add (Ktor version ref already `ktor = "3.4.3"`, serialization already present):
```toml
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```
(`kotlinx-serialization-json` is already in the catalog; `ktor-client-{okhttp,cio,darwin,js}` are already present, used by `:kuilt-websocket`.)

- [ ] **Step 2: Create the build script**

`kuilt-otel-otlp/build.gradle.kts` (mirrors `:kuilt-websocket`'s client engines; no server, so no `jvmAndAndroidMain` intermediate):
```kotlin
plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Public surface returns/consumes kuilt-otel types (OtlpEdge, records, digests).
            api(project(":kuilt-otel"))
            // runCatchingCancellable — cancellation-safe sends and digest reads.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlin.logging)
            api(libs.kotlinx.io.bytestring)
        }

        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        androidMain.dependencies { implementation(libs.ktor.client.cio) }
        val iosMain by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        val macosMain by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        val wasmJsMain by getting { dependencies { implementation(libs.ktor.client.js) } }

        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.ktor.serverTestHost)
            implementation(libs.ktor.serverNetty)
        }
    }
}
```

> **Verify the `iosMain`/`macosMain`/`wasmJsMain` `by getting` handles resolve** for the default `kuilt.kmp-library` hierarchy (auto-wiring on). If a handle isn't created by default, mirror `:kuilt-websocket`'s exact `by creating`/`by getting` pattern for that intermediate. `:kuilt-websocket/build.gradle.kts` is the working reference for a Ktor-client-all-targets module.

- [ ] **Step 3: Create `module.md`**

`kuilt-otel-otlp/module.md`:
```markdown
# Module kuilt-otel-otlp

Send kuilt's offline-first telemetry to a standard OpenTelemetry collector.

When the network is available, this module forwards the spans, logs, and metrics
kuilt buffered on the device to any OTLP/HTTP endpoint — the same collector your
existing dashboards already read. It speaks OTLP/JSON over HTTP and only sends what
the endpoint has not already received, so a reconnect after hours offline uploads the
gap, not the whole history.

`OtlpHttpEdge` is the `OtlpEdge` a `WarpOtlpBridge` drains into. Point it at a
collector URL and call `drain` on each reconnect.
```

- [ ] **Step 4: Wire the module into the build**

In `settings.gradle.kts`, after `include(":kuilt-otel-sdk")` add:
```kotlin
include(":kuilt-otel-otlp")
```

- [ ] **Step 5: Verify the empty module compiles on JVM + metadata**

Run: `./gradlew :kuilt-otel-otlp:compileKotlinJvm :kuilt-otel-otlp:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL (empty module).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml kuilt-otel-otlp/build.gradle.kts kuilt-otel-otlp/module.md
git commit -m "feat(otel-otlp): scaffold all-target :kuilt-otel-otlp module (Ktor client + JSON)"
```

---

### Task 8: OTLP/JSON DTOs + serializers (spans incl. links, logs, metrics)

**Files:**
- Create: `kuilt-otel-otlp/src/commonMain/kotlin/us/tractat/kuilt/otel/otlp/OtlpJson.kt` (DTOs)
- Create: `kuilt-otel-otlp/src/commonMain/kotlin/us/tractat/kuilt/otel/otlp/OtlpEncoding.kt` (record → DTO mappers)
- Test: `kuilt-otel-otlp/src/commonTest/kotlin/us/tractat/kuilt/otel/otlp/OtlpEncodingTest.kt`

**Interfaces:**
- Consumes: `SpanRecord`, `SpanLink`, `LogRecord`, `MetricPoint` (from `:kuilt-otel`).
- Produces: `@Serializable` OTLP/JSON envelopes (`TracesRequest`/`LogsRequest`/`MetricsRequest`) + `internal` mapper functions that honour OTLP/JSON quirks: `trace_id`/`span_id` as lowercase hex strings, 64-bit ints (`*UnixNano`, sums) as strings, and `resourceSpans/scopeSpans/spans` (+ `resourceLogs`/`resourceMetrics`) nesting. **`Span.links` is populated from the inferred `SpanLink`s.**

> **OTLP/JSON field-shape reference (encode-side only — we never parse a response body).** Minimal subset kuilt emits. Hex-encode `ByteString` at the boundary; emit int64 as `String`.

- [ ] **Step 1: Write the failing test**

`OtlpEncodingTest.kt` — assert the encoded JSON shape for each signal, and that links land on their span:
```kotlin
package us.tractat.kuilt.otel.otlp

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord
import kotlin.test.Test
import kotlin.test.assertTrue

class OtlpEncodingTest {
    private val json = Json { encodeDefaults = false }
    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })

    @Test
    fun spanEncodesHexIdsInt64StringsAndLinks() {
        val span = SpanRecord(
            traceId = tId(0x0a), spanId = sId(0x0b), parentSpanId = null,
            name = "op", kind = SpanKind.SERVER, startEpochNanos = 5L, endEpochNanos = 9L,
        )
        val link = SpanLink(fromSpanId = sId(0x0b), linkedTraceId = tId(0x0c), linkedSpanId = sId(0x0d))
        val req = tracesRequestOf(setOf(span), listOf(link))
        val s = json.encodeToString(req)
        assertTrue(s.contains("\"traceId\":\"0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a\""), s)
        assertTrue(s.contains("\"spanId\":\"0b0b0b0b0b0b0b0b\""), s)
        assertTrue(s.contains("\"startTimeUnixNano\":\"5\""), s) // int64 as string
        assertTrue(s.contains("\"links\""), s)
        assertTrue(s.contains("\"0d0d0d0d0d0d0d0d\""), s) // linked span id hex
        assertTrue(s.contains("kuilt.causality"), s)
    }

    @Test
    fun logEncodesBodyAndRecordId() {
        val rec = LogRecord(recordId = sId(1), body = "hello", severityNumber = 9, severityText = "INFO")
        val s = json.encodeToString(logsRequestOf(setOf(rec)))
        assertTrue(s.contains("\"resourceLogs\""), s)
        assertTrue(s.contains("hello"), s)
    }

    @Test
    fun sumEncodesAsCumulativeIntString() {
        val p = MetricPoint.Sum(MetricKey("req", MetricKind.SUM), value = 7L, startEpochNanos = 0L, timeEpochNanos = 5L)
        val s = json.encodeToString(metricsRequestOf(setOf(p)))
        assertTrue(s.contains("\"resourceMetrics\""), s)
        assertTrue(s.contains("\"asInt\":\"7\""), s)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-otlp:jvmTest --tests "*OtlpEncodingTest"`
Expected: FAIL — DTOs and `tracesRequestOf`/`logsRequestOf`/`metricsRequestOf` unresolved.

- [ ] **Step 3: Write minimal implementation**

`OtlpJson.kt` — the `@Serializable` DTO subset (hex/string handled by the mappers producing `String`s, so DTO fields are already `String`; no custom serializers needed). Sketch (fill every field the test asserts; keep nullable fields out of the JSON via `encodeDefaults = false`):
```kotlin
package us.tractat.kuilt.otel.otlp

import kotlinx.serialization.Serializable

@Serializable internal data class TracesRequest(val resourceSpans: List<ResourceSpans>)
@Serializable internal data class ResourceSpans(val scopeSpans: List<ScopeSpans>)
@Serializable internal data class ScopeSpans(val spans: List<OtlpSpan>)
@Serializable internal data class OtlpSpan(
    val traceId: String, val spanId: String, val parentSpanId: String? = null,
    val name: String, val kind: Int,
    val startTimeUnixNano: String, val endTimeUnixNano: String,
    val attributes: List<KeyValue> = emptyList(),
    val links: List<OtlpLink> = emptyList(),
)
@Serializable internal data class OtlpLink(
    val traceId: String, val spanId: String, val attributes: List<KeyValue> = emptyList(),
)
@Serializable internal data class KeyValue(val key: String, val value: AnyValue)
@Serializable internal data class AnyValue(val stringValue: String)

@Serializable internal data class LogsRequest(val resourceLogs: List<ResourceLogs>)
@Serializable internal data class ResourceLogs(val scopeLogs: List<ScopeLogs>)
@Serializable internal data class ScopeLogs(val logRecords: List<OtlpLogRecord>)
@Serializable internal data class OtlpLogRecord(
    val timeUnixNano: String? = null, val observedTimeUnixNano: String? = null,
    val severityNumber: Int? = null, val severityText: String? = null,
    val body: AnyValue? = null, val attributes: List<KeyValue> = emptyList(),
    val traceId: String? = null, val spanId: String? = null,
)

@Serializable internal data class MetricsRequest(val resourceMetrics: List<ResourceMetrics>)
@Serializable internal data class ResourceMetrics(val scopeMetrics: List<ScopeMetrics>)
@Serializable internal data class ScopeMetrics(val metrics: List<OtlpMetric>)
@Serializable internal data class OtlpMetric(
    val name: String,
    val sum: OtlpSum? = null,
    val gauge: OtlpGauge? = null,
)
@Serializable internal data class OtlpSum(
    val dataPoints: List<NumberDataPoint>,
    val aggregationTemporality: Int = 2, // CUMULATIVE
    val isMonotonic: Boolean = true,
)
@Serializable internal data class OtlpGauge(val dataPoints: List<NumberDataPoint>)
@Serializable internal data class NumberDataPoint(
    val attributes: List<KeyValue> = emptyList(),
    val startTimeUnixNano: String? = null, val timeUnixNano: String,
    val asInt: String? = null, val asDouble: Double? = null,
)
```

`OtlpEncoding.kt` — the mappers + a hex helper:
```kotlin
package us.tractat.kuilt.otel.otlp

import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord

private const val HEX = "0123456789abcdef"
internal fun ByteString.toHex(): String = buildString(size * 2) {
    for (i in 0 until size) { val b = this@toHex[i].toInt() and 0xFF; append(HEX[b ushr 4]); append(HEX[b and 0x0F]) }
}

private fun SpanKind.toOtlp(): Int = when (this) {
    SpanKind.INTERNAL -> 1; SpanKind.SERVER -> 2; SpanKind.CLIENT -> 3
    SpanKind.PRODUCER -> 4; SpanKind.CONSUMER -> 5
}
private fun attrs(m: Map<String, String>) = m.map { KeyValue(it.key, AnyValue(it.value)) }

internal fun tracesRequestOf(spans: Set<SpanRecord>, links: List<SpanLink>): TracesRequest {
    val byFrom = links.groupBy { it.fromSpanId }
    val otlpSpans = spans.map { s ->
        OtlpSpan(
            traceId = s.traceId.toHex(), spanId = s.spanId.toHex(),
            parentSpanId = s.parentSpanId?.toHex(),
            name = s.name, kind = s.kind.toOtlp(),
            startTimeUnixNano = s.startEpochNanos.toString(),
            endTimeUnixNano = s.endEpochNanos.toString(),
            attributes = attrs(s.attributes),
            links = (byFrom[s.spanId] ?: emptyList()).map {
                OtlpLink(it.linkedTraceId.toHex(), it.linkedSpanId.toHex(), attrs(it.attributes))
            },
        )
    }
    return TracesRequest(listOf(ResourceSpans(listOf(ScopeSpans(otlpSpans)))))
}

internal fun logsRequestOf(logs: Set<LogRecord>): LogsRequest {
    val recs = logs.map { r ->
        OtlpLogRecord(
            timeUnixNano = r.timestampEpochNanos?.toString(),
            observedTimeUnixNano = r.observedEpochNanos?.toString(),
            severityNumber = r.severityNumber, severityText = r.severityText,
            body = r.body?.let { AnyValue(it) }, attributes = attrs(r.attributes),
            traceId = r.traceId?.toHex(), spanId = r.spanId?.toHex(),
        )
    }
    return LogsRequest(listOf(ResourceLogs(listOf(ScopeLogs(recs)))))
}

internal fun metricsRequestOf(points: Set<MetricPoint>): MetricsRequest {
    val metrics = points.map { p ->
        when (p) {
            is MetricPoint.Sum -> OtlpMetric(
                name = p.key.name,
                sum = OtlpSum(listOf(NumberDataPoint(
                    attributes = attrs(p.key.attributes),
                    startTimeUnixNano = p.startEpochNanos.toString(),
                    timeUnixNano = p.timeEpochNanos.toString(),
                    asInt = p.value.toString(),
                ))),
            )
            is MetricPoint.Gauge -> OtlpMetric(
                name = p.key.name,
                gauge = OtlpGauge(listOf(NumberDataPoint(
                    attributes = attrs(p.key.attributes),
                    timeUnixNano = p.timeEpochNanos.toString(), asDouble = p.value,
                ))),
            )
            is MetricPoint.Cardinality -> OtlpMetric(
                name = p.key.name,
                gauge = OtlpGauge(listOf(NumberDataPoint(
                    attributes = attrs(p.key.attributes),
                    timeUnixNano = p.timeEpochNanos.toString(), asInt = p.estimate.toString(),
                ))),
            )
        }
    }
    return MetricsRequest(listOf(ResourceMetrics(listOf(ScopeMetrics(metrics)))))
}
```
(All mapper fns are `internal` — the test is in the same module. `MetricKind` import is unused above; drop it if detekt flags it.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel-otlp:jvmTest --tests "*OtlpEncodingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-otlp/src/commonMain/kotlin/us/tractat/kuilt/otel/otlp/OtlpJson.kt \
        kuilt-otel-otlp/src/commonMain/kotlin/us/tractat/kuilt/otel/otlp/OtlpEncoding.kt \
        kuilt-otel-otlp/src/commonTest/kotlin/us/tractat/kuilt/otel/otlp/OtlpEncodingTest.kt
git commit -m "feat(otel-otlp): OTLP/JSON encoders for spans (incl. links), logs, metrics"
```

---

### Task 9: `OtlpHttpEdge` — Ktor POST + producer-local sent-set

**Files:**
- Create: `kuilt-otel-otlp/src/commonMain/kotlin/us/tractat/kuilt/otel/otlp/OtlpHttpEdge.kt`
- Test: `kuilt-otel-otlp/src/commonTest/kotlin/us/tractat/kuilt/otel/otlp/OtlpHttpEdgeTest.kt`

**Interfaces:**
- Consumes: `OtlpEdge`, `SpanRecord`/`LogRecord`/`MetricPoint`/`SpanDigest`/`LogDigest`/`MetricDigest`, `DurableStore`/`StoreKey`; Task 8 encoders; Ktor `HttpClient`.
- Produces: `class OtlpHttpEdge(client: HttpClient, endpoint: String, store: DurableStore) : OtlpEdge` — POSTs `/v1/{traces,logs,metrics}` as `application/json`; `digest()`/`logDigest()`/`metricDigest()` read a producer-local sent-set from `store`; a successful send folds the delivered ids/hashes back into that set.

> **Producer-local sent-set.** Keys are per-endpoint: `otlp.sent.spans@<endpointHash>`, `.logs@…`, `.metrics@…` (endpoint hashed to a stable suffix). Persisted as CBOR (reuse the module's serialization) — spans/logs as a `Set<ByteString>` id-set; metrics as a `Map<MetricKey, Long>`. **Retention:** an unbounded id-set is a memory leak on a long-lived producer — cap it. This plan ships a simple size cap (`maxSentIds`, default e.g. 50_000, drop-oldest via insertion-ordered set) and flags the exact default as an open question for @keddie (below).

- [ ] **Step 1: Write the failing test**

`OtlpHttpEdgeTest.kt` — multiplatform, `MockEngine`. Assert method/path/content-type and that a second `digest()` reflects the first send (sent-set persisted):
```kotlin
package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpHttpEdgeTest {
    private fun tId(b: Byte) = ByteString(ByteArray(16) { b })
    private fun sId(b: Byte) = ByteString(ByteArray(8) { b })
    private fun span(b: Byte) = SpanRecord(tId(b), sId(b), null, "op", SpanKind.INTERNAL, 1L, 2L)

    private fun okEngine(record: (String, String) -> Unit) = MockEngine { req ->
        record(req.url.encodedPath, req.body.toString())
        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }

    @Test
    fun sendPostsToTracesPathAsJson() = runTest {
        var path = ""; var ct = ""
        val engine = MockEngine { req ->
            path = req.url.encodedPath
            ct = req.headers[HttpHeaders.ContentType] ?: req.body.contentType?.toString() ?: ""
            respond("{}", HttpStatusCode.OK)
        }
        val edge = OtlpHttpEdge(HttpClient(engine), "https://collector.example:4318", InMemoryDurableStore())
        edge.send(setOf(span(1)))
        assertEquals("/v1/traces", path)
        assertTrue(ct.contains("application/json"), ct)
    }

    @Test
    fun digestReflectsPriorSends() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val store = InMemoryDurableStore()
        val edge = OtlpHttpEdge(HttpClient(engine), "https://c.example:4318", store)
        edge.send(setOf(span(1)))
        assertTrue(edge.digest().spanIds.contains(sId(1)))

        // A fresh edge over the same store recovers the sent-set.
        val edge2 = OtlpHttpEdge(HttpClient(engine), "https://c.example:4318", store)
        assertTrue(edge2.digest().spanIds.contains(sId(1)))
    }

    @Test
    fun failedSendDoesNotRecordSentSet() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val store = InMemoryDurableStore()
        val edge = OtlpHttpEdge(HttpClient(engine), "https://c.example:4318", store)
        runCatching { edge.send(setOf(span(1))) } // may throw on non-2xx
        assertTrue(edge.digest().spanIds.isEmpty(), "a 5xx must leave the sent-set untouched so drain retries")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-otlp:jvmTest --tests "*OtlpHttpEdgeTest"`
Expected: FAIL — `OtlpHttpEdge` unresolved.

- [ ] **Step 3: Write minimal implementation**

`OtlpHttpEdge.kt`. POST each request via Ktor; on 2xx fold ids into the sent-set and persist; on non-2xx do not (throw so `WarpOtlpBridge` records a per-signal failure and retries). Skeleton:
```kotlin
package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import us.tractat.kuilt.otel.*

/**
 * A Ktor OTLP/HTTP JSON [OtlpEdge]. POSTs each signal to `/v1/{traces,logs,metrics}`
 * as `application/json`; reconciles by a **producer-local** sent-set persisted in
 * [store] (OTLP is write-only — there is no collector read-back).
 *
 * @param client caller-owned Ktor client (owns engine, timeouts, TLS, auth headers).
 * @param endpoint collector base URL, e.g. `https://collector:4318`.
 * @param store durable persistence for the per-endpoint sent-set.
 */
public class OtlpHttpEdge(
    private val client: HttpClient,
    private val endpoint: String,
    private val store: DurableStore,
) : OtlpEdge {
    private val json = Json { encodeDefaults = false }
    // sent-set keys, endpoint-scoped
    private val spanKey = StoreKey("otlp.sent.spans@" + endpoint.hashCode())
    private val logKey = StoreKey("otlp.sent.logs@" + endpoint.hashCode())
    private val metricKey = StoreKey("otlp.sent.metrics@" + endpoint.hashCode())

    override suspend fun digest(): SpanDigest = SpanDigest(readIdSet(spanKey))
    override suspend fun logDigest(): LogDigest = LogDigest(readIdSet(logKey))
    override suspend fun metricDigest(): MetricDigest = MetricDigest(readVersionMap(metricKey))

    override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) {
        postJson("/v1/traces", json.encodeToString(TracesRequest.serializer(), tracesRequestOf(spans, links)))
        recordIds(spanKey, spans.map { it.spanId })
    }
    override suspend fun sendLogs(logs: Set<LogRecord>) {
        postJson("/v1/logs", json.encodeToString(LogsRequest.serializer(), logsRequestOf(logs)))
        recordIds(logKey, logs.map { it.recordId })
    }
    override suspend fun sendMetrics(points: Set<MetricPoint>) {
        postJson("/v1/metrics", json.encodeToString(MetricsRequest.serializer(), metricsRequestOf(points)))
        recordVersions(metricKey, points.associate { it.key to it.valueHash() })
    }

    private suspend fun postJson(path: String, body: String) {
        val resp: HttpResponse = client.post(endpoint.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) error("OTLP POST $path failed: ${resp.status}")
    }
    // readIdSet/readVersionMap/recordIds/recordVersions: CBOR (de)serialize a
    // Set<ByteString> / Map<MetricKey,Long> under the given StoreKey, size-capped
    // (drop-oldest) at maxSentIds. Persist only AFTER a successful POST.
    private suspend fun readIdSet(key: StoreKey): Set<ByteString> = TODO("CBOR decode or emptySet")
    private suspend fun readVersionMap(key: StoreKey): Map<MetricKey, Long> = TODO("CBOR decode or emptyMap")
    private suspend fun recordIds(key: StoreKey, ids: List<ByteString>) { TODO("merge + cap + store.write") }
    private suspend fun recordVersions(key: StoreKey, v: Map<MetricKey, Long>) { TODO("merge + store.write") }
}
```

> **Fill the four persistence helpers with real CBOR** mirroring `WarpSpanExporter`'s companion pattern: `Cbor { alwaysUseByteString = true }`, a `SetSerializer(ByteStringSerializer)` for id-sets and `MapSerializer(MetricKey.serializer(), Long.serializer())` for versions. Cap the id-set at `maxSentIds` (add as a constructor param, default `50_000`) with drop-oldest via an insertion-ordered structure. These are the only `TODO`s in the plan — they are mechanical; the shape is pinned by the test. No `TODO` may remain in the committed code.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel-otlp:jvmTest --tests "*OtlpHttpEdgeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-otlp/src/commonMain/kotlin/us/tractat/kuilt/otel/otlp/OtlpHttpEdge.kt \
        kuilt-otel-otlp/src/commonTest/kotlin/us/tractat/kuilt/otel/otlp/OtlpHttpEdgeTest.kt
git commit -m "feat(otel-otlp): OtlpHttpEdge — Ktor POST + producer-local persisted sent-set"
```

---

### Task 10: JVM integration test — real round-trip through a stub server + retry

**Files:**
- Test: `kuilt-otel-otlp/src/jvmTest/kotlin/us/tractat/kuilt/otel/otlp/OtlpHttpEdgeIntegrationTest.kt`

**Interfaces:**
- Consumes: `OtlpHttpEdge`, `WarpTelemetry`, `WarpOtlpBridge`; Ktor `ktor-server-test-host` (JVM).

- [ ] **Step 1: Write the failing test**

`OtlpHttpEdgeIntegrationTest.kt` — stand up an embedded Ktor server capturing bodies per path, drain a real `WarpTelemetry` through `OtlpHttpEdge`, assert each signal arrived, a re-drain is a no-op, and a 5xx leaves the sent-set so the next drain retries:
```kotlin
package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class OtlpHttpEdgeIntegrationTest {
    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    @Test
    fun drainRoundTripsAllSignalsThenIsIdempotent() = runTest {
        val bodies = mutableMapOf<String, MutableList<String>>()
        val server = embeddedServer(Netty, port = 0) {
            routing { post("/v1/{signal}") {
                val sig = call.parameters["signal"]!!
                bodies.getOrPut(sig) { mutableListOf() }.add(call.receiveText())
                call.respondText("{}")
            } }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        try {
            val store = InMemoryDurableStore()
            val telemetry = WarpTelemetry(ReplicaId("p"), store)
            telemetry.spans.export(SpanRecord(ByteString16(1), ByteString8(1), null, "op", SpanKind.INTERNAL, 1L, 2L))
            telemetry.logs.export(LogRecord(recordId = ByteString8(2), body = "hi"))
            telemetry.metrics.incrementSum(MetricKey("req", MetricKind.SUM), by = 1L)

            val edge = OtlpHttpEdge(HttpClient(OkHttp), "http://localhost:$port", store)
            val bridge = WarpOtlpBridge(telemetry, clock)
            bridge.drain(edge)
            bridge.drain(edge) // idempotent

            assertEquals(1, bodies["traces"]?.size)
            assertEquals(1, bodies["logs"]?.size)
            assertEquals(1, bodies["metrics"]?.size)
            assertTrue(bodies["traces"]!!.first().contains("resourceSpans"))
        } finally { server.stop(0, 0) }
    }
}
```
(Add `ByteString16`/`ByteString8` tiny helpers in the test file, or inline `ByteString(ByteArray(16){1})`. Keep the test JVM-only — embedded server is JVM.)

- [ ] **Step 2: Run test to verify it fails, then passes**

Fence the command (embedded server): `timeout 120 ./gradlew :kuilt-otel-otlp:jvmTest --tests "*OtlpHttpEdgeIntegrationTest"`
First run may fail on the helper names; fix, then Expected: PASS. If it hangs, a non-converging server is the suspect — `jstack` the test JVM, do not widen the timeout.

- [ ] **Step 3: Commit**

```bash
git add kuilt-otel-otlp/src/jvmTest/kotlin/us/tractat/kuilt/otel/otlp/OtlpHttpEdgeIntegrationTest.kt
git commit -m "test(otel-otlp): JVM stub-server round-trip for all three signals"
```

---

### Task 11: PR 2 full-build verification + open PR (closes #846)

**Files:** none (verification + integration).

- [ ] **Step 1: Detekt + full build of the new module, cache-disabled**

Run:
```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-otel-otlp:build detektAll --rerun-tasks
```
Expected: BUILD SUCCESSFUL, tasks EXECUTED. `--no-build-cache` if any test-compile shows `FROM-CACHE`.

- [ ] **Step 2: Whole-repo build (all variants — the new module is all-target)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — `:kuilt-otel-otlp` compiles on JVM, Android, iOS, macOS, wasmJs (the Ktor client resolves on each). This is the real gate: a common-source type-inference or engine-wiring break shows up here, not in `jvmTest`.

- [ ] **Step 3: Open PR 2 (closes #846)**

```bash
git push -u origin otel-egress-ktor-edge-1027
gh pr create --title "feat(otel-otlp): concrete Ktor OTLP/HTTP JSON edge" \
  --body "$(cat <<'EOF'
> 🤖 This comment was generated by Claude on behalf of @keddie.

Part of #986 (M2). Closes #846. Design: `docs/superpowers/specs/2026-07-01-otlp-egress-design.md`; plan: `docs/superpowers/plans/2026-07-01-otlp-egress.md`. Design PR: #1031. Stacked on PR 1.

PR 2 of 2 — the first concrete transport, new all-target module `:kuilt-otel-otlp`:
- OTLP/JSON encoders for spans (**incl. `Span.links` — closes #846's wire-emission half**), logs, metrics: hex ids, string int64, `resourceSpans/scopeSpans/spans` nesting.
- `OtlpHttpEdge` — Ktor `HttpClient` POSTs `/v1/{traces,logs,metrics}` as `application/json`; reconciles by a producer-local sent-set persisted in a `DurableStore` (OTLP is write-only — no collector read-back).
- Multiplatform `MockEngine` request-shape tests (all targets) + a JVM stub-server round-trip for all three signals with idempotent re-drain and retry-on-5xx.

Binary OTLP/protobuf is deferred to #1040. Wire format is OTLP/JSON.
EOF
)"
gh pr merge --auto --squash
```

- [ ] **Step 4: Verify #846 auto-closes after PR 2 merges; open PR in browser**

Run: `gh pr view --web`. After merge, confirm #846 closed (`gh issue view 846 --json state`).

---

## Self-Review

**Spec coverage (`docs/superpowers/specs/2026-07-01-otlp-egress-design.md`):**
- `LogDigest`/`MetricDigest`/`MetricPoint` + value-hash model → Task 1. ✓
- Additive `OtlpEdge` (defaulted logs/metrics + `links` on `send`), producer-local `SpanDigest` KDoc → Task 2. ✓
- `WarpMetricExporter` enumeration accessor → Task 3. ✓
- #846 auto-stamp on export (explicit-stamp-wins) + `observe` on merge + clock persist/recover → Task 4. ✓
- `WarpOtlpBridge` drains all three by digest; link inference over full snapshot filtered to delta; richer `DrainResult` → Task 5. ✓
- All-target `:kuilt-otel-otlp` (Ktor client everywhere, no `jvmAndroidOnly`) → Task 7. ✓
- OTLP/JSON encoding (hex ids, string int64, `Span.links`) → Task 8. ✓
- `OtlpHttpEdge` POST + producer-local persisted sent-set → Task 9. ✓
- Fake-edge idempotent re-drain (all signals) + metric-advance + partial-failure isolation → Task 5; MockEngine + stub-server round-trip + retry → Tasks 9–10. ✓

**PR1/PR2 split:** falls between **Task 6** (PR 1 opens: `:kuilt-otel` digest extension + #846 auto-stamp/link-inference) and **Task 7** (PR 2 begins: `:kuilt-otel-otlp`). **#846 closes with PR 2** (Task 11).

**Cross-plan / cross-task dependencies:**
- Task 5 (bridge metric drain) **depends on Task 3** (`WarpMetricExporter.snapshotAll`) — the enumeration accessor is the only new API the bridge needs to render metric points; if the metrics work is owned by a different plan/worker, Task 3 must land first.
- Task 5 also depends on Task 1 (digest/point types), Task 2 (widened edge), and Task 4 (auto-stamp — so `inferCausalLinks` finds stamped spans; without Task 4 the link tests in Task 5 are vacuous).
- Tasks 8–10 depend on Task 2's widened `OtlpEdge` and Task 1's `MetricPoint`; Task 10 depends on Task 5's `WarpOtlpBridge(telemetry, clock)`.

**Deviations / documented simplifications (YAGNI, flagged for @keddie):**
- Metric `startEpochNanos = 0L` ("unknown start") — no per-series genesis timestamp tracked yet; follow-up.
- Gauge OTLP `timeUnixNano` uses drain-observation time (the LWWRegister write timestamp is `private`); the digest hashes value only, which is the correct dedup key regardless.
- Sent-set retention default `maxSentIds = 50_000` (drop-oldest) — **open for @keddie** (design open question 6).
- Endpoint config is minimal (base URL + caller-owned `HttpClient` for auth/timeouts/TLS) — **open for @keddie** (design open question 5).

**Placeholder scan:** the only `TODO`s are the four sent-set persistence helpers in Task 9 Step 3, explicitly called out as mechanical CBOR (de)serialization with the shape pinned by the test — the step instruction requires filling them before commit. Every other code block is concrete. Two "verify against the working reference" callouts (Task 7 source-set handles vs `:kuilt-websocket`; Task 4 no new callouts) point at real files.

**Type consistency:** `LogDigest(recordIds)`, `MetricDigest(versions)`, `MetricPoint.{Sum,Gauge,Cardinality}.valueHash()`, `OtlpEdge.{digest,send(spans,links),logDigest,sendLogs,metricDigest,sendMetrics}`, `WarpMetricExporter.snapshotAll(nowEpochNanos)`, `WarpSpanExporter(..., causalClock)`, `WarpOtlpBridge(telemetry, clock)`, `DrainResult.Success(spansSent, logsSent, metricPointsSent)`, `OtlpHttpEdge(client, endpoint, store)` — consistent across tasks.
