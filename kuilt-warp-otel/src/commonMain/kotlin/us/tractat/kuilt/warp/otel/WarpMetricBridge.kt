package us.tractat.kuilt.warp.otel

import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.warp.Draft
import us.tractat.kuilt.warp.WarpNode
import us.tractat.kuilt.warp.WarpStats
import us.tractat.kuilt.warp.coordinationCost

/**
 * Records warp-node execution metrics into this [WarpMetricExporter].
 *
 * Each call **merges** [WarpNode.executions], [WarpNode.duplicates], and
 * [WarpNode.failovers] — which are [us.tractat.kuilt.crdt.GCounter]s — into the
 * corresponding SUM series. Because GCounter's join is element-wise max, this call is
 * **idempotent**: calling it twice with the same [node] snapshot produces exactly the
 * same result as calling it once. This is the keystone CRDT property: the exporter's
 * SUM series and the node's counter are the same CRDT, so merge-under-retry is safe.
 *
 * Metrics written:
 * | name | kind | source |
 * |---|---|---|
 * | `warp.tasks.executed` | SUM | [WarpNode.executions] |
 * | `warp.tasks.duplicate` | SUM | [WarpNode.duplicates] |
 * | `warp.failover.count` | SUM | [WarpNode.failovers] |
 * | `warp.tasks.interpreted` | SUM | [WarpNode.executionsInterpreted] |
 * | `warp.tasks.compiled` | SUM | [WarpNode.executionsCompiled] |
 *
 * @param node The [WarpNode] whose counter snapshots to merge.
 * @param attributes Additional label attributes added to every [MetricKey].
 */
public suspend fun WarpMetricExporter.recordWarp(
    node: WarpNode,
    attributes: Map<String, String> = emptyMap(),
) {
    mergeSum(MetricKey("warp.tasks.executed", MetricKind.SUM, attributes), node.executions)
    mergeSum(MetricKey("warp.tasks.duplicate", MetricKind.SUM, attributes), node.duplicates)
    mergeSum(MetricKey("warp.failover.count", MetricKind.SUM, attributes), node.failovers)
    mergeSum(MetricKey("warp.tasks.interpreted", MetricKind.SUM, attributes), node.executionsInterpreted)
    mergeSum(MetricKey("warp.tasks.compiled", MetricKind.SUM, attributes), node.executionsCompiled)
}

/**
 * Records per-source cardinality sketches from [stats] into this [WarpMetricExporter].
 *
 * For each source [us.tractat.kuilt.warp.OpId] in [stats], its
 * [us.tractat.kuilt.crdt.HyperLogLog] sketch is merged into the `warp.task.cardinality`
 * CARDINALITY series. The attribute key that identifies the source is [sourceAttr]
 * (default `"source"`); its value is the [us.tractat.kuilt.warp.OpId.value] string.
 *
 * **Idempotent and commutative.** Recording [statsA] then [statsB] produces the same
 * result as recording `statsA.piece(statsB)` once — both exploit HyperLogLog's
 * element-wise-max join under [mergeCardinality].
 *
 * @param stats The [WarpStats] snapshot carrying per-source HyperLogLog sketches.
 * @param sourceAttr Attribute key used to label each per-source series.
 */
public suspend fun WarpMetricExporter.recordStats(
    stats: WarpStats,
    sourceAttr: String = "source",
) {
    for ((opId, hll) in stats.sketches()) {
        mergeCardinality(
            MetricKey("warp.task.cardinality", MetricKind.CARDINALITY, mapOf(sourceAttr to opId.value)),
            hll,
        )
    }
}

/**
 * Records [Draft] coordination-cost gauges into this [WarpMetricExporter].
 *
 * Computes the [us.tractat.kuilt.warp.CoordinationCost] from [draft] and [stats], then
 * writes two GAUGE metrics:
 *
 * | name | value |
 * |---|---|
 * | `warp.coordination.volume` | estimated elements entering the coordinated stage |
 * | `warp.coordination.rounds` | number of consensus rounds the draft requires |
 *
 * A `plan` attribute is added automatically:
 * - `"unplanned"` when [Draft.isMonotone] is `true` — no coordination step, so no
 *   planning consideration is needed (rounds = 0, volume = 0).
 * - `"planned"` when [Draft.isMonotone] is `false` — the draft has a coordination
 *   stage; the planner's work is relevant, and the volume reflects filter pushdown.
 *
 * This lets dashboards compare pre- and post-[us.tractat.kuilt.warp.plan] volumes:
 * an unoptimised draft records a high volume under `plan="planned"`, while the
 * optimised draft records a lower volume under the same key.
 *
 * @param draft The [Draft] pipeline to cost.
 * @param stats [WarpStats] sketches used to estimate cardinality at each stage.
 * @param attributes Additional label attributes merged with the auto-computed `plan` attr.
 * @param timestamp Monotonically increasing timestamp for LWW tiebreaking across replicas.
 *   Use `Clock.System.now().toEpochMilliseconds()` in production; a fixed value in tests.
 */
public suspend fun WarpMetricExporter.recordPlan(
    draft: Draft<*>,
    stats: WarpStats,
    attributes: Map<String, String> = emptyMap(),
    timestamp: Long = 0L,
) {
    val cost = draft.coordinationCost(stats)
    val planAttr = if (draft.isMonotone) "unplanned" else "planned"
    val attrs = attributes + mapOf("plan" to planAttr)
    setGauge(MetricKey("warp.coordination.volume", MetricKind.GAUGE, attrs), cost.coordinatedVolume.toDouble(), timestamp)
    setGauge(MetricKey("warp.coordination.rounds", MetricKind.GAUGE, attrs), cost.rounds.toDouble(), timestamp)
}
