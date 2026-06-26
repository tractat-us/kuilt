@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp.otel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.ClaimStrategy
import us.tractat.kuilt.warp.Draft
import us.tractat.kuilt.warp.Op
import us.tractat.kuilt.warp.OpId
import us.tractat.kuilt.warp.OpRegistry
import us.tractat.kuilt.warp.TaskDescriptor
import us.tractat.kuilt.warp.TaskId
import us.tractat.kuilt.warp.Warp
import us.tractat.kuilt.warp.WarpNode
import us.tractat.kuilt.warp.WarpStats
import us.tractat.kuilt.warp.coordinationCost
import us.tractat.kuilt.warp.plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import us.tractat.kuilt.quilter.QuilterConfig

/**
 * Tests for the [WarpMetricBridge] extension functions.
 *
 * Verifies:
 * 1. Counter → MetricKey mapping for [recordWarp].
 * 2. [recordWarp] idempotence — the GCounter merge law: calling twice is the same as once.
 * 3. WarpStats → CARDINALITY series with per-source attributes ([recordStats]).
 * 4. CRDT commutativity: merge-then-record == record-then-merge.
 * 5. Planned vs. unplanned [Draft] → correct `warp.coordination.volume` gauge ([recordPlan]).
 */
class WarpMetricBridgeTest {

    private val testReplicaId = ReplicaId("test-replica")

    private val testQuilterConfig = QuilterConfig(
        antiEntropyInterval = 100.milliseconds,
        fullStateRetryInterval = 150.milliseconds,
        expectVirtualTime = true,
    )

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun newExporter() = WarpMetricExporter(
        replica = testReplicaId,
        store = InMemoryDurableStore(),
    )

    private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
        { Instant.fromEpochMilliseconds(scheduler.currentTime) }

    private fun TaskId.descriptor(): TaskDescriptor =
        TaskDescriptor(op = OpId("echo"), args = value.encodeToByteArray())

    private fun echoRegistry() =
        OpRegistry().also { it.register(OpId("echo"), Op { args -> args }) }

    /**
     * Creates a single-node WarpNode using [ClaimStrategy.Ring] so tasks are claimed and
     * executed immediately without the intent-register settle window.
     */
    private suspend fun TestScope.makeSingleNode(
        loom: InMemoryLoom = InMemoryLoom(),
    ): WarpNode {
        val seam = loom.host(Pattern("bridge-test"))
        val rosterFlow = MutableStateFlow<Set<PeerId>>(setOf(seam.selfId))
        return WarpNode(
            selfId = seam.selfId,
            seam = seam,
            rosterFlow = rosterFlow,
            scope = backgroundScope,
            quilterConfig = testQuilterConfig,
            clock = schedulerClock(testScheduler),
            strategy = ClaimStrategy.Ring,
            registry = echoRegistry(),
        )
    }

    private fun TestScope.drain() {
        repeat(5) { advanceTimeBy(testQuilterConfig.antiEntropyInterval); runCurrent() }
    }

    private fun observeAll(stats: WarpStats, source: OpId, vararg elements: String): WarpStats =
        elements.fold(stats) { acc, e -> acc.piece(acc.observe(source, e)) }

    private fun statsFor(opId: OpId, n: Int): WarpStats =
        (1..n).fold(WarpStats.empty()) { stats, i ->
            stats.piece(stats.observe(opId, "element_$i"))
        }

    // ── 1. Counter → MetricKey mapping ──────────────────────────────────────

    @Test
    fun recordWarpSurfacesCountersUnderExpectedKeys() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val node = makeSingleNode()
            val exporter = newExporter()

            node.enqueue(TaskId("t1"), TaskId("t1").descriptor())
            node.enqueue(TaskId("t2"), TaskId("t2").descriptor())
            drain()

            exporter.recordWarp(node)

            assertAll(
                {
                    assertEquals(
                        2L,
                        exporter.sumValue(MetricKey("warp.tasks.executed", MetricKind.SUM)),
                        "executions counter must map to warp.tasks.executed SUM",
                    )
                },
                {
                    assertEquals(
                        0L,
                        exporter.sumValue(MetricKey("warp.tasks.duplicate", MetricKind.SUM)),
                        "duplicates counter must map to warp.tasks.duplicate SUM",
                    )
                },
                {
                    assertEquals(
                        0L,
                        exporter.sumValue(MetricKey("warp.failover.count", MetricKind.SUM)),
                        "failovers counter must map to warp.failover.count SUM",
                    )
                },
            )
        }

    // ── 2. recordWarp idempotence (the keystone property) ───────────────────

    @Test
    fun recordWarpIdempotentCallTwiceEqualCallOnce() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val node = makeSingleNode()
            val exporter = newExporter()

            node.enqueue(TaskId("task-a"), TaskId("task-a").descriptor())
            drain()

            // First call: merges node.executions (GCounter{selfId:1}) into the sum.
            exporter.recordWarp(node)
            // Second call: same GCounter state — elementwise-max is a no-op.
            exporter.recordWarp(node)

            assertEquals(
                1L,
                exporter.sumValue(MetricKey("warp.tasks.executed", MetricKind.SUM)),
                "GCounter merge is idempotent: calling recordWarp twice must equal calling it once",
            )
        }

    // ── 3. WarpStats → CARDINALITY series with per-source attrs ─────────────

    @Test
    fun recordStatsEmitsCardinalitySeriesPerSource() = runTest {
        val src1 = OpId("source.docs")
        val src2 = OpId("source.images")

        val stats = observeAll(
            observeAll(WarpStats.empty(), src1, "a", "b", "c", "d", "e"),
            src2, "x", "y", "z",
        )

        val exporter = newExporter()
        exporter.recordStats(stats)

        val key1 = MetricKey("warp.task.cardinality", MetricKind.CARDINALITY, mapOf("source" to src1.value))
        val key2 = MetricKey("warp.task.cardinality", MetricKind.CARDINALITY, mapOf("source" to src2.value))
        assertAll(
            { assertTrue(exporter.cardinalityEstimate(key1) >= 1L, "src1 cardinality must be non-zero") },
            { assertTrue(exporter.cardinalityEstimate(key2) >= 1L, "src2 cardinality must be non-zero") },
        )
    }

    // ── 4. CRDT commutativity: merge-then-record == record-then-merge ────────

    @Test
    fun recordStatsCommutativityMergeThenRecordEqualsRecordThenMerge() = runTest {
        val src = OpId("source.tasks")
        val statsA = observeAll(WarpStats.empty(), src, "a1", "a2", "a3", "a4")
        val statsB = observeAll(WarpStats.empty(), src, "b1", "b2", "b3", "b4")

        val cardKey = MetricKey("warp.task.cardinality", MetricKind.CARDINALITY, mapOf("source" to src.value))

        // merge first, then record
        val exporterMergeFirst = newExporter()
        exporterMergeFirst.recordStats(statsA.piece(statsB))

        // record A, then record B (the bridge merges into the same cardinality sketch)
        val exporterRecordFirst = newExporter()
        exporterRecordFirst.recordStats(statsA)
        exporterRecordFirst.recordStats(statsB)

        assertEquals(
            exporterMergeFirst.cardinalityEstimate(cardKey),
            exporterRecordFirst.cardinalityEstimate(cardKey),
            "recordStats must satisfy CRDT commutativity: merge-then-record == record-then-merge",
        )
    }

    // ── 5. recordPlan: planned vs unplanned Draft coordination volume ────────

    @Test
    fun recordPlanShowsPlannerVolumeWinOnRepresentativeQuery() = runTest {
        // E-3 representative scenario: 1 000 source docs, 5% (50 docs) pass the filter.
        val src = OpId("source.docs")
        val mapScore = OpId("map.score")
        val filterThreshold = OpId("filter.above-threshold")
        val emb = OpId("embroider.rank")

        val stats = statsFor(src, 1_000).piece(statsFor(filterThreshold, 50))

        // Unplanned: embroider before filter → sees ~1 000 docs at the consensus step.
        val unplanned = Warp.shuttle(src).map(mapScore).embroider(emb).filter(filterThreshold)
        // Planned: embroider deferred past filter → sees ~50 docs at the consensus step.
        val planned = unplanned.plan(stats)

        val exporterUnplanned = newExporter()
        exporterUnplanned.recordPlan(unplanned, stats, timestamp = 1L)

        val exporterPlanned = newExporter()
        exporterPlanned.recordPlan(planned, stats, timestamp = 1L)

        // Both drafts have an embroider → plan attribute = "planned" in both exporters.
        val volKey = MetricKey("warp.coordination.volume", MetricKind.GAUGE, mapOf("plan" to "planned"))
        val unplannedVol = exporterUnplanned.gaugeValue(volKey)
        val plannedVol = exporterPlanned.gaugeValue(volKey)

        assertAll(
            { assertNotNull(unplannedVol, "unplanned draft must emit warp.coordination.volume") },
            { assertNotNull(plannedVol, "planned draft must emit warp.coordination.volume") },
            {
                assertTrue(
                    plannedVol!! < unplannedVol!!,
                    "planner win: planned volume $plannedVol must be less than unplanned volume $unplannedVol",
                )
            },
        )
    }

    @Test
    fun recordPlanMonotoneDraftUsesUnplannedAttribute() = runTest {
        val src = OpId("source.docs")
        val stats = statsFor(src, 100)

        // Monotone draft: no embroider → plan attribute = "unplanned".
        val monotone = Warp.shuttle(src).map(OpId("map.score"))

        val exporter = newExporter()
        exporter.recordPlan(monotone, stats, timestamp = 1L)

        val volKey = MetricKey("warp.coordination.volume", MetricKind.GAUGE, mapOf("plan" to "unplanned"))
        val roundsKey = MetricKey("warp.coordination.rounds", MetricKind.GAUGE, mapOf("plan" to "unplanned"))

        assertAll(
            {
                assertEquals(
                    0.0,
                    exporter.gaugeValue(volKey),
                    "monotone draft must report 0 coordination volume with plan='unplanned'",
                )
            },
            {
                assertEquals(
                    0.0,
                    exporter.gaugeValue(roundsKey),
                    "monotone draft must report 0 coordination rounds with plan='unplanned'",
                )
            },
        )
    }
}
