package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.LocalHeddle.Companion.MAX_RESERVATION

/**
 * The questions you can ask a [LocalHeddle] without changing it, asked of a saved state instead of
 * a running engine: who goes next ([previewPicks]), why a leaf is waiting ([explain]), and what
 * share it is owed ([entitlement]). An admin screen that holds only the last stored snapshot gets
 * the same answers the engine that took it would give, with no engine to start and nothing to move.
 *
 * Get one from a snapshot with [LocalHeddleSnapshot.view]. The engine answers these questions
 * through a view of its own state, so the two cannot disagree: a view of the snapshot [LocalHeddle.persist]
 * returns answers exactly as that engine did when it took it.
 *
 * A view is a value. It reads the snapshot's books, wake memory, capacity and last applied topology,
 * and each question plays its picks forward on a throwaway engine built from them, so asking twice
 * gives the same answer twice. It needs the policy too, which a snapshot does not keep: pass the one
 * the engine ran with.
 *
 * A view from a snapshot shares nothing with any engine, and each question only reads it, so any
 * number of threads may ask one view at once. The engine's own calls go through a view that reads
 * its live state for the length of one call, under the same single writer as every other call.
 *
 * @sample us.tractat.kuilt.heddle.sampleSnapshotView
 */
public class LocalHeddleView internal constructor(
    private val root: GroupId,
    private val policy: LocalPolicy,
    private val epoch: Long,
    private val ledger: EntitlementLedger,
    /**
     * The wake memory each throwaway engine starts from. It is only ever forked, never moved, so a
     * view the engine builds over its own allocator leaves that allocator as it found it.
     */
    private val allocator: FairShareAllocator,
    private val capacity: Capacity,
    private val leasable: Leasable,
    /**
     * Whether the throwaway engines rebase (#3329): off only for a test's twin, which never rebases.
     * Required rather than defaulted: a default on this internal constructor would put a public
     * synthetic constructor in the JVM ABI.
     */
    private val rebase: Boolean,
    /**
     * How many leases were open beneath each capped group when the state was taken: counted, not
     * carried, since a pick needs the count against each ceiling and never the leases themselves.
     * Every throwaway engine counts it beside the leases it opens. Empty when no ceiling is set.
     */
    private val openBeneath: Map<GroupId, Int>,
) {

    /** An engine over this view's state, with its own copy of the wake memory: it picks and tops up as the engine would next. */
    private fun scratch(): LocalHeddle =
        LocalHeddle.scratch(root, policy, epoch, ledger, allocator.fork(), leasable, capacity, openBeneath).also { it.rebaseForTest = rebase }

    /**
     * Who would go next: the leaves `reservations.size` calls to [LocalHeddle.acquire] would pick,
     * in order, if nothing else happened in between. Changes nothing, not even the wake memory.
     *
     * [waiting] gives each leaf's queued work as a count. A leaf counts as waiting while its count
     * is above zero, and each pick takes one from the leaf it picked. Pick `i` reserves
     * `reservations[i]`, and its eligible set is [eligible] among the leaves still waiting. The list
     * ends early at the first call that would not acquire.
     *
     * @throws IllegalArgumentException when a count is negative or a reservation is not in `1..`[MAX_RESERVATION].
     * @sample us.tractat.kuilt.heddle.sampleSnapshotView
     */
    public fun previewPicks(waiting: Map<GroupId, Int>, eligible: Set<GroupId>, reservations: List<Long>): List<GroupId> {
        require(waiting.values.all { it >= 0 }) { "a queued count must be non-negative" }
        val scratch = scratch()
        val remaining = HashMap(waiting)
        val picks = ArrayList<GroupId>(reservations.size)
        for (reservation in reservations) {
            val waitingNow = remaining.filterValues { it > 0 }.keys
            val eligibleNow = eligible.filterTo(HashSet()) { it in waitingNow }
            val out = scratch.acquire(waitingNow, eligibleNow, startedAt = 0L, reservation = reservation)
            if (out !is AcquireOutcome.Acquired) break
            val leaf = out.lease.leaf
            picks += leaf
            remaining[leaf] = remaining.getValue(leaf) - 1
        }
        return picks
    }

    /**
     * Who would go next, with each leaf's own next job: the leaves a caller's loop over [queues]
     * would pick, in order, if nothing else happened in between. Changes nothing, not even the wake
     * memory.
     *
     * [queues] lists each leaf's queued jobs by their reservations, head first. A leaf counts as
     * waiting while its list is not empty. Each pick does what that caller does:
     * [LocalHeddle.acquire] with [reservation] among the waiting leaves in [eligible], then, when the
     * winner's head is larger than [reservation], [LocalHeddle.extend] the lease by the difference,
     * then pop the head. An extend makes no pick, so the order is the one the slot's own reservation
     * decides, and the head sets only what the lease holds: `max(reservation, head)`. A head below
     * [reservation] holds [reservation], since a lease cannot shrink.
     *
     * The list ends when no waiting leaf is eligible, or at the first call that is refused, a
     * `PositionCeiling` refusal included. When the acquire lands and its extend is the call
     * refused, that pick is left out and the list ends there, even if the root could fund the next
     * leaf: the caller's lease would hold less than its job needs, and its loop stops at it.
     *
     * @throws IllegalArgumentException when [reservation] or any queued reservation, not only a
     *   head, is not in `1..`[MAX_RESERVATION].
     * @sample us.tractat.kuilt.heddle.samplePreviewQueues
     */
    public fun previewPicks(
        queues: Map<GroupId, List<Long>>,
        eligible: Set<GroupId>,
        reservation: Long = policy.defaultReservation,
    ): List<GroupId> {
        LocalHeddle.requireReservation("reservation", reservation)
        for ((leaf, queue) in queues) {
            for (queued in queue) {
                // Checked inline, so the message is built only for the reservation that fails.
                require(queued in 1L..MAX_RESERVATION) { "a queued reservation of ${leaf.value} must be in 1..$MAX_RESERVATION, was $queued" }
            }
        }
        val scratch = scratch()
        val remaining = queues.mapValuesTo(HashMap()) { (_, queue) -> ArrayDeque(queue) }
        val picks = ArrayList<GroupId>()
        while (true) {
            val waitingNow = remaining.filterValues { it.isNotEmpty() }.keys
            val eligibleNow = eligible.filterTo(HashSet()) { it in waitingNow }
            val out = scratch.acquire(waitingNow, eligibleNow, startedAt = 0L, reservation = reservation)
            if (out !is AcquireOutcome.Acquired) break
            val head = remaining.getValue(out.lease.leaf).removeFirst()
            if (head > reservation && scratch.extend(out.lease.id, head - reservation) !is LeaseOutcome.Done) break
            picks += out.lease.leaf
        }
        return picks
    }

    /**
     * Why [leaf] is not, or is, what the next [LocalHeddle.acquire] on these same arguments picks.
     * Changes nothing, not even the wake memory.
     *
     * The reasons are checked in [WaitExplanation.Kind] order, and the first that holds is the
     * answer: [leaf] is not in [waiting], not in [eligible], cannot be reached (the same test the
     * pick applies, so a leaf behind a closing edge, a group with an active child, and a group that
     * is not leasable are each [WaitExplanation.NoLiveLineage]), or the root cannot fund
     * [reservation]. Past those, the answer is the pick itself, run on a throwaway engine with the
     * wake memory, so a group waking from idle is clamped here exactly as the acquire would clamp
     * it: [WaitExplanation.WouldBePickedNext] when the pick ends at [leaf],
     * [WaitExplanation.PositionCeiling] when it is refused at a position ceiling on the way, and
     * otherwise [WaitExplanation.BehindAtLevel] at the first group where it goes another way.
     *
     * @throws IllegalArgumentException as [LocalHeddle.acquire] does: [reservation] out of range, or
     *   [eligible] not a subset of [waiting].
     * @throws ArithmeticException when a [WaitExplanation.BehindAtLevel] front does not fit an exact
     *   [Rational], which can happen well below the position ceiling (see "Failure surface" on
     *   [LocalHeddle]).
     * @sample us.tractat.kuilt.heddle.sampleExplainWait
     */
    public fun explain(
        leaf: GroupId,
        waiting: Set<GroupId>,
        eligible: Set<GroupId>,
        reservation: Long = policy.defaultReservation,
    ): WaitExplanation {
        LocalHeddle.requireReservation("reservation", reservation)
        require(waiting.containsAll(eligible)) { "eligible must be a subset of waiting" }
        if (leaf !in waiting) return WaitExplanation.NotWaiting
        if (leaf !in eligible) return WaitExplanation.NotEligible
        if (!ledger.canEndAt(leaf, root, leasable)) return WaitExplanation.NoLiveLineage(leaf)
        val trial = scratch()
        // The pick drops a leaf beneath a group at its ceiling from both sets, so for it the leaf is
        // not eligible on this slot. Until the explanation can name the ceiling (#3324), that is the
        // answer, and it agrees with the acquire, which never picks it.
        val (waitingNow, eligibleNow) = trial.belowCeilings(waiting, eligible)
        if (leaf !in eligibleNow) return WaitExplanation.NotEligible
        val base = trial.fundedBase(reservation) ?: return WaitExplanation.SupplyExhausted

        val demands = trial.demands(base, waitingNow, eligibleNow, reservation)
        val descent = trial.descend(base, demands, eligibleNow, reservation)
        if (descent is LocalHeddle.Descent.Ceiling) return WaitExplanation.PositionCeiling(descent.at)
        // An eligible leaf the pick can end at, with the root funded: the root's pick has a candidate
        // in the leaf's own top edge, and every pick below goes toward an eligible leaf.
        check(descent is LocalHeddle.Descent.Reached) { "no pick reached any leaf, though ${leaf.value} is eligible and reachable" }
        if (descent.leaf == leaf) return WaitExplanation.WouldBePickedNext
        val lineage = checkNotNull(base.lineageOf(leaf)) { "${leaf.value} can be reached but has no lineage" }
        // Every group above the leaf on its lineage has an active child, so the pick cannot have
        // stopped there: it left the lineage at some level.
        val level = checkNotNull(lineage.indices.firstOrNull { it >= descent.levels.size || descent.levels[it].edge != lineage[it] }) {
            "the pick went past ${leaf.value}, an eligible leaf on its own path"
        }
        check(level < descent.levels.size) { "the pick stopped above ${leaf.value}, at a group with an active child" }
        val at = descent.levels[level]
        // The trial's wake memory has moved on below this level, but each level writes only its own
        // children's entries, so what it holds for this level's children is what this level read.
        return WaitExplanation.BehindAtLevel(
            parent = at.parent,
            nextChild = checkNotNull(base.record(at.edge)) { "picked edge ${at.edge.value} has no record" }.child,
            leafSideVirtualService = checkNotNull(trial.allocator.effectiveVirtualService(at.seated, at.parent, lineage[level])) {
                "${lineage[level].value} is not an active child of ${at.parent.value}"
            },
            front = checkNotNull(trial.allocator.virtualTime(at.seated, at.parent, demands.waiting)) {
                "${at.parent.value} has no active children, though a pick was made there"
            },
            // Over the children the pick saw as candidates: every one is seated by now, and each is
            // granted the whole reservation, so the set the pick averaged is exactly the set
            // `eligible` wants.
            eligibleFront = checkNotNull(trial.allocator.virtualTime(at.seated, at.parent, demands.eligible)) {
                "${at.parent.value} has no active children, though a pick was made there"
            },
        )
    }

    /**
     * [leaf]'s fair share of [totalSlots] slots right now, as an exact rational: its weight's share
     * among the siblings competing beside it, times its parent's share among the siblings beside
     * that, up to the root, times [totalSlots]. This is the share an idealised fair scheduler
     * (generalised processor sharing) would give it while [waiting] stays as it is.
     *
     * "Competing" is the set a pick takes its front over: an ACTIVE child counts when a leaf of
     * [waiting] that a pick could end at lies under it. So work no descent can reach, behind a
     * closing edge, at a group with an active child or at one that is not leasable, competes for
     * nothing and is owed nothing, and it takes nothing from its siblings' shares. `0` when [leaf]
     * is not in [waiting], or is not a leaf a pick could end at.
     *
     * A share, not a promise: the slots a leaf gets over a run track it only while the waiting set
     * holds still, and a leaf that is waiting but rarely eligible gets less.
     *
     * @throws IllegalArgumentException when [totalSlots] is negative.
     * @throws ArithmeticException when the exact share does not fit in `Long` numerators.
     * @sample us.tractat.kuilt.heddle.sampleExplainWait
     */
    public fun entitlement(leaf: GroupId, waiting: Set<GroupId>, totalSlots: Long): Rational {
        require(totalSlots >= 0L) { "totalSlots must be non-negative, was $totalSlots" }
        val base = ledger
        if (leaf !in waiting || !base.canEndAt(leaf, root, leasable)) return Rational.ZERO
        val competing = base.reachable(waiting, root, leasable)
        var share = Rational.of(totalSlots)
        for (edge in checkNotNull(base.lineageOf(leaf)) { "${leaf.value} can be reached but has no lineage" }) {
            val record = checkNotNull(base.record(edge)) { "lineage edge ${edge.value} has no record" }
            val competingWeight = base.activeChildren(record.parent)
                .mapNotNull { base.record(it.attachment)?.takeIf { sibling -> sibling.child in competing } }
                .fold(Rational.ZERO) { sum, sibling -> sum + Rational.of(sibling.weight.numerator, sibling.weight.denominator) }
            share = share * Rational.of(record.weight.numerator, record.weight.denominator) / competingWeight
        }
        return share
    }
}

/** [slots] as last reconciled, and the root holdings it sizes: the [low] watermark and the [target] a top-up leaves. */
internal data class Capacity(val slots: Long, val low: Long, val target: Long) {
    companion object {
        val NONE = Capacity(slots = 0L, low = 0L, target = 0L)

        /**
         * The largest supply target [sized] allows, `Long.MAX_VALUE / 4`: room above it for the
         * operation a top-up funds, at most [MAX_RESERVATION], and for the epoch's running mint
         * total, which carries every unit ever charged.
         */
        const val SUPPLY_TARGET_LIMIT: Long = Long.MAX_VALUE / 4

        /**
         * The watermarks [policy] sizes for [totalSlots] slots: `totalSlots × defaultReservation`
         * per round, times the policy's low and target slot counts. The one sizing
         * [LocalHeddle.reconcileCapacity], [LocalHeddle.restore] and [LocalHeddleSnapshot.view] share.
         *
         * @throws IllegalArgumentException when [totalSlots] is negative, or the target it sizes is
         *   above [SUPPLY_TARGET_LIMIT].
         */
        fun sized(totalSlots: Long, policy: LocalPolicy): Capacity {
            require(totalSlots >= 0L) { "totalSlots must be non-negative, was $totalSlots" }
            val rounds = policy.supply
            val perRound = policy.defaultReservation
            // n × r × t ≤ limit, by floor division so the check itself cannot overflow.
            require(rounds.targetSlots == 0L || totalSlots <= SUPPLY_TARGET_LIMIT / perRound / rounds.targetSlots) {
                "totalSlots $totalSlots × defaultReservation $perRound × targetSlots ${rounds.targetSlots} is above $SUPPLY_TARGET_LIMIT"
            }
            // A zero target makes both levels zero (low ≤ target) for any count, so nothing is multiplied.
            // Otherwise the check above bounds every product, and the checked multiplies restate it.
            if (rounds.targetSlots == 0L) return Capacity(totalSlots, low = 0L, target = 0L)
            val round = checkedMul(totalSlots, perRound)
            return Capacity(totalSlots, low = checkedMul(round, rounds.lowWatermarkSlots), target = checkedMul(round, rounds.targetSlots))
        }
    }
}
