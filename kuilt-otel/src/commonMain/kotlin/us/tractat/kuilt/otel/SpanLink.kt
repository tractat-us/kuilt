package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.Dot

private val logger = KotlinLogging.logger {}

/**
 * A derived causal edge: span [fromSpanId] *could have been caused by* the
 * predecessor span identified by [linkedTraceId] / [linkedSpanId].
 *
 * This maps directly onto an OTLP `Span.Link`. The `kuilt.causality=potential`
 * attribute is non-negotiable: a link records happens-before, which is a superset
 * of true causality — it says "could have caused," never "did."
 *
 * @property fromSpanId the span that carries the link (the successor `e2`).
 * @property linkedTraceId the predecessor's trace id.
 * @property linkedSpanId the predecessor's span id (the `e1` being linked to).
 * @property attributes link attributes; always carries `kuilt.causality=potential`.
 */
public data class SpanLink(
    public val fromSpanId: ByteString,
    public val linkedTraceId: ByteString,
    public val linkedSpanId: ByteString,
    public val attributes: Map<String, String> = mapOf("kuilt.causality" to "potential"),
)

private val linkOrder: Comparator<SpanLink> =
    compareBy({ it.fromSpanId }, { it.linkedSpanId })

/**
 * Derive [SpanLink]s from the happens-before relation recorded in each span's
 * [SpanRecord.causalStamp].
 *
 * Unstamped spans are ignored. For each stamped successor `e2`, each predecessor
 * dot is resolved to a span `e1`; an edge `e2 → e1` is emitted **iff
 * `e1.spanId != e2.parentSpanId`** — the "cross-boundary" filter. That single
 * condition drops predecessor edges that merely duplicate the explicit parent
 * (ordinary intra-trace tree structure) and keeps the novel edges that hand-rolled
 * context propagation would have lost: cross-trace, or same-trace but not the parent.
 *
 * A predecessor dot that doesn't resolve (late or partial sync) is skipped and
 * logged at debug — never thrown. The result is sorted by `(fromSpanId,
 * linkedSpanId)` for deterministic, input-order-independent output.
 *
 * @sample us.tractat.kuilt.otel.sampleInferCausalLinks
 */
public fun inferCausalLinks(spans: Collection<SpanRecord>): List<SpanLink> {
    val byDot: Map<Dot, SpanRecord> = spans
        .mapNotNull { span -> span.causalStamp?.let { it.dot to span } }
        .toMap()
    return byDot.values
        .flatMap { successor -> linksFrom(successor, byDot) }
        .sortedWith(linkOrder)
}

private fun linksFrom(successor: SpanRecord, byDot: Map<Dot, SpanRecord>): List<SpanLink> {
    val predecessors = successor.causalStamp?.predecessors ?: return emptyList()
    return predecessors.mapNotNull { dot -> linkFor(successor, dot, byDot) }
}

private fun linkFor(successor: SpanRecord, dot: Dot, byDot: Map<Dot, SpanRecord>): SpanLink? {
    val predecessor = byDot[dot] ?: run {
        logger.debug { "inferCausalLinks: unresolved predecessor dot=$dot for span ${successor.spanId}" }
        return null
    }
    if (predecessor.spanId == successor.parentSpanId) return null // C-rule: parent edge is tree structure
    return SpanLink(
        fromSpanId = successor.spanId,
        linkedTraceId = predecessor.traceId,
        linkedSpanId = predecessor.spanId,
    )
}
