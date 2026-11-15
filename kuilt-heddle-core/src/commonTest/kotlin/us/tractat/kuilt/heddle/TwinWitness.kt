package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128

/**
 * The witnesses the step B twin rows read (design §7.1, *Future: positions with no origin*, slice 1).
 *
 * A twin is an engine with the rebase switched off. Since step A it never raises an origin, so every
 * position it holds is the edge's service from its start: it is the step B engine. These read, from
 * outside, the two things a row has to show besides "the same picks": that the twin really did
 * climb into the range the rebase exists to avoid, and whether the rebasing side met one of the two
 * cases the design allows to differ.
 */

/** `2⁶³` ticks: the first position a `Long` cannot hold, which only a twin's positions can reach. */
internal val LONG_LIMIT_TICKS: Int128 = Int128.fromTwosComplement(0L, Long.MIN_VALUE)

/**
 * The highest effective position, in ticks, among [parent]'s active seated children in [ledger]:
 * the raw position, net of returns and relative to [parent]'s origin, plus the wake offset in
 * [wakeOffsets]. What a pick orders. `null` when no child is seated.
 */
internal fun peakEffectiveTicks(ledger: EntitlementLedger, wakeOffsets: Map<AttachmentId, Int128>, parent: GroupId): Int128? =
    ledger.activeChildren(parent).mapNotNull { child ->
        effectiveTicks(ledger, wakeOffsets, child.attachment)
    }.maxOrNull()

/** [peakEffectiveTicks] under every parent in [ledger]: the highest effective position anywhere, or zero. */
internal fun peakEffectiveTicksAnywhere(ledger: EntitlementLedger, wakeOffsets: Map<AttachmentId, Int128>): Int128 =
    ledger.allEdges().mapNotNull { ledger.record(it)?.parent }.toSet()
        .mapNotNull { peakEffectiveTicks(ledger, wakeOffsets, it) }.maxOrNull() ?: Int128.ZERO

/** [peakEffectiveTicksAnywhere] on [engine]'s current state. */
internal fun peakEffectiveTicksAnywhere(engine: LocalHeddle): Int128 =
    peakEffectiveTicksAnywhere(engine.snapshot().ledger, engine.allocator.memory.wakeOffsets)

/**
 * [neverObservedBelowZero] on [engine] before a call whose waiting set is [waiting]: an edge is
 * demanding when a waiting group sits at or beneath its child.
 */
internal fun neverObservedBelowZero(engine: LocalHeddle, waiting: Set<GroupId>): Int {
    val ledger = engine.snapshot().ledger
    val demanding = waiting.flatMapTo(HashSet()) { leaf -> ledger.lineageOf(leaf).orEmpty().mapNotNull { ledger.record(it)?.child } }
    return neverObservedBelowZero(ledger, engine.allocator) { it.child in demanding }.size
}

/** [edge]'s effective position in ticks, or `null` when it is not seated. */
private fun effectiveTicks(ledger: EntitlementLedger, wakeOffsets: Map<AttachmentId, Int128>, edge: AttachmentId): Int128? {
    val gauge = ledger.gauge(edge) ?: return null
    val record = ledger.record(edge) ?: return null
    val returned = ledger.edge(edge)?.returned ?: 0L
    return gauge.rawTicksAt(ledger.baseIssuance(edge), returned, record.weight, ledger.origin(record.parent)) +
        (wakeOffsets[edge] ?: Int128.ZERO)
}

/**
 * The edges in [ledger] that [allocator] would clamp under the below-zero arm of the first-sighting
 * rule (design §7.2): seated, demanding by [demanding], never observed by [allocator], and below
 * zero. The second of the two causes a twin row may differ by. Read on the rebasing side before a
 * call, from outside, so it needs no counter in the allocator.
 */
internal fun neverObservedBelowZero(
    ledger: EntitlementLedger,
    allocator: FairShareAllocator,
    demanding: (AttachmentRecord) -> Boolean,
): List<AttachmentId> {
    val unobserved = ledger.allEdges().filter { edge ->
        val record = ledger.record(edge) ?: return@filter false
        allocator.lastObservedDemanding(edge) == null && demanding(record) && ledger.gauge(edge) != null &&
            ledger.activeChildren(record.parent).any { it.attachment == edge }
    }
    if (unobserved.isEmpty()) return emptyList()
    // Read only when there is a candidate: the wake memory is sorted on every read.
    val offsets = allocator.memory.wakeOffsets
    return unobserved.sorted().filter { edge -> (effectiveTicks(ledger, offsets, edge) ?: return@filter false) < Int128.ZERO }
}

/** Every parent [ledger] has raised an origin under, with that origin: empty for a twin. */
internal fun raisedOrigins(ledger: EntitlementLedger): Map<GroupId, Int128> =
    ledger.allEdges().mapNotNull { ledger.record(it)?.parent }.toSet().sortedBy { it.value }
        .associateWith { ledger.origin(it) }.filterValues { it != Int128.ZERO }
