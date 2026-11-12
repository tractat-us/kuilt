package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId

/**
 * A **test-only** prototype of the lease model the `LocalHeddle` engine is planned to expose (#3173,
 * plan Task C, ruling R2): one lease per slot, time as the cost. It exists to produce evidence — the
 * fairness and scale numbers `LeaseCharacterizationTest` and `LeaseScaleProbe` record — before that
 * engine's API is frozen, and it is deleted when the engine lands.
 *
 * It runs over the production [EntitlementLedger] and [FairShareAllocator] unchanged:
 * - **acquire** descends from [root], one pick per level among the eligible children, and
 *   delegates exactly the reservation down the chosen path. The waiting set feeds the allocator's
 *   seat and wake step; the eligible set alone defines the candidates.
 * - **extend** delegates more down the same captured path. It is not a new pick.
 * - **complete** extends inline for any shortfall, spends the actual cost on the captured path, and
 *   releases the rest back up to the root.
 *
 * ## Supply
 *
 * Before an acquire or an extend delegates from the root, **at most one** mint of [mintChunk] is
 * written, and only when the root holds less than [watermark]. If the root still cannot cover the
 * amount, the call reports [PrototypeAcquire.SupplyExhausted] / [PrototypeExtend.SupplyExhausted]
 * and the ledger is unchanged. So exhaustion is reachable, and whether it happens is a property of
 * how the watermark and chunk are sized against the reservations.
 *
 * `complete` is the exception: R2 says a charge is never lost or refused, so its inline shortfall
 * mints whatever it lacks, uncapped, and counts that in [forcedMints].
 *
 * Every operation is computed on a local ledger value and swapped in once, so a failed acquire leaves
 * [ledger] untouched (only the allocator's wake memory may have moved).
 *
 * ## Two seams the production allocator does not have yet
 *
 * [FairShareAllocator.schedule] runs grants until nothing more can move, and takes one `demand`
 * function for both its seat/wake step and its pick. A lease needs exactly one pick, and two demand
 * views. This prototype gets both without touching production code, through [OneGrantCell]:
 * - the cell applies the seat patch and the **first** delegation, then refuses every later write, so
 *   the loop stops after one grant; it names the picked edge by reading it out of the patch;
 * - the demand function answers from the **waiting** set outside a write and from the **eligible**
 *   set inside one. `schedule` builds its seat/wake view outside any write and its pick inside one,
 *   so the split lands exactly where the planned `pickChild(waiting, eligible)` puts it.
 *
 * Both are workarounds that read the allocator's call order. They are the evidence that L1 needs
 * `pickChild`, not a design to copy. The same call order lets [wakes] count what the allocator
 * actually sampled: an answer given outside a write is the observation its wake detector stores.
 *
 * @param root the tree's root group.
 * @param parentOf the static tree, child → parent. Used only to lift a set of leaves to the interior
 *   groups above them; the ledger's own lineage walk is `O(edges)` and would dominate the timing.
 * @param watermark top-up mints only when the root holds less than this.
 * @param mintChunk the units one top-up mint adds.
 */
internal class LeasePrototype(
    initial: EntitlementLedger,
    private val root: GroupId,
    private val parentOf: Map<GroupId, GroupId>,
    private val watermark: Long,
    private val mintChunk: Long,
    sleeperCredit: Long = 0L,
) {
    init {
        require(watermark >= 0L) { "watermark must be non-negative, was $watermark" }
        require(mintChunk >= 1L) { "mintChunk must be positive, was $mintChunk" }
    }

    /** The ledger as of the last completed operation. */
    var ledger: EntitlementLedger = initial
        private set

    /** The quantum is carried per pick by `maximumUsefulGrant = reservation`, so the config's is unbounded. */
    private val allocator = FairShareAllocator(
        SELF,
        PolicyConfig(quantum = Long.MAX_VALUE, perChildOutstandingCap = Long.MAX_VALUE, sleeperCredit = sleeperCredit),
    )

    private val interior: Set<GroupId> = parentOf.values.toSet()
    private val open = ArrayList<PrototypeLease>()
    private var nextSeq = 0L
    private var mintSeq = 0L

    /** The demanding state the allocator last sampled per edge, as this prototype answered it. */
    private val sampled = HashMap<AttachmentId, Boolean>()

    /** Top-up mints written by acquire and extend: at most one per call. */
    var mints: Int = 0
        private set

    /** Mints `complete` wrote to cover an overrun the root could not, uncapped because a charge is never refused. */
    var forcedMints: Int = 0
        private set

    /**
     * Idle→waiting transitions the allocator **sampled**: an edge it was last told was not demanding,
     * told now that it is. A flip that opens and closes between two samples is not one.
     */
    var wakes: Int = 0
        private set

    /** The lowest root holdings read before any top-up decision, once supply exists. */
    var minUnspent: Long = Long.MAX_VALUE
        private set

    /** Open leases, oldest first. A `List` the prototype built, so a seeded draw over it is portable. */
    fun openLeases(): List<PrototypeLease> = open.toList()

    /**
     * Acquire one lease of [reservation] units for a leaf in [eligible]. [waiting] is the set of
     * leaves that currently want service; it must contain [eligible].
     */
    fun acquire(waiting: Set<GroupId>, eligible: Set<GroupId>, reservation: Long): PrototypeAcquire {
        require(reservation >= 1L) { "reservation must be positive, was $reservation" }
        require(waiting.containsAll(eligible)) { "eligible must be a subset of waiting" }
        if (eligible.isEmpty()) return PrototypeAcquire.NothingEligible
        val funded = topUp(ledger, reservation, force = false) ?: return PrototypeAcquire.SupplyExhausted

        val waitingGroups = withAncestors(waiting)
        val eligibleGroups = withAncestors(eligible)
        val cell = OneGrantCell(funded)
        val wants = Demand(targetOutstanding = UNBOUNDED_TARGET, maximumUsefulGrant = reservation)
        val demand: (AttachmentId) -> Demand = { edge ->
            val child = cell.current.record(edge)?.child
            if (cell.inPick()) {
                if (child != null && child in eligibleGroups) wants else Demand.NONE
            } else {
                val demanding = child != null && child in waitingGroups
                if (demanding && sampled[edge] == false) wakes++
                sampled[edge] = demanding
                if (demanding) wants else Demand.NONE
            }
        }

        val path = ArrayList<AttachmentId>()
        var group = root
        while (group in interior) {
            cell.arm(group)
            allocator.schedule(group, cell, earmarked = 0L, demand)
            val picked = cell.granted() ?: return PrototypeAcquire.DescentFailed(group)
            check(cell.grantedAmount == reservation) {
                "picked ${picked.value} for ${cell.grantedAmount}, not the reservation $reservation"
            }
            path += picked
            group = checkNotNull(cell.current.record(picked)) { "picked edge ${picked.value} has no record" }.child
        }

        ledger = cell.current
        val lease = PrototypeLease(nextSeq++, group, path.toList(), reservation)
        open += lease
        return PrototypeAcquire.Acquired(lease)
    }

    /** Delegate [amount] more down [lease]'s captured path, unless supply cannot cover it. */
    fun extend(lease: Long, amount: Long): PrototypeExtend {
        require(amount >= 1L) { "extend amount must be positive, was $amount" }
        val index = indexOf(lease)
        val current = open[index]
        val funded = topUp(ledger, amount, force = false) ?: return PrototypeExtend.SupplyExhausted
        ledger = delegateDown(funded, current.path, amount)
        open[index] = current.copy(reserved = current.reserved + amount)
        return PrototypeExtend.Extended
    }

    /**
     * Charge [actual] to [lease] and close it: extend inline for any shortfall, spend [actual] on the
     * captured path, release the unused remainder back up to the root. Never refused.
     */
    fun complete(lease: Long, actual: Long): PrototypeComplete {
        require(actual >= 0L) { "actual must be non-negative, was $actual" }
        val index = indexOf(lease)
        val current = open[index]
        val overrun = maxOf(0L, actual - current.reserved)
        var local = ledger
        if (overrun > 0L) {
            local = delegateDown(checkNotNull(topUp(local, overrun, force = true)), current.path, overrun)
        }
        if (actual > 0L) {
            local = local.piece(checkNotNull(local.spendCaptured(SELF, current.path, actual)) { "spend refused" }.delta)
        }
        val remainder = current.reserved + overrun - actual
        if (remainder > 0L) {
            for (edge in current.path.asReversed()) {
                val patch = checkNotNull(local.release(SELF, edge, remainder)) { "release of $remainder up ${edge.value} refused" }
                local = local.piece(patch.delta)
            }
        }
        ledger = local
        open.removeAt(index)
        return PrototypeComplete(charged = actual, overrun = overrun)
    }

    private fun indexOf(lease: Long): Int =
        open.indexOfFirst { it.id == lease }.also { require(it >= 0) { "unknown lease $lease" } }

    private fun delegateDown(start: EntitlementLedger, path: List<AttachmentId>, amount: Long): EntitlementLedger {
        var local = start
        for (edge in path) {
            val patch = checkNotNull(local.delegate(SELF, edge, amount)) { "delegate of $amount down ${edge.value} refused" }
            local = local.piece(patch.delta)
        }
        return local
    }

    /**
     * Fund a delegation of [amount] from the root: one [mintChunk] mint if the root is under the
     * watermark, then `null` if that still does not cover [amount] — unless [force], which mints the
     * exact shortfall instead. The counters move only for a mint that is returned, so a refused call
     * counts nothing.
     */
    private fun topUp(start: EntitlementLedger, amount: Long, force: Boolean): EntitlementLedger? {
        val held = start.holdings(root, SELF)
        if (mints + forcedMints > 0) minUnspent = minOf(minUnspent, held)
        val chunk = if (held < watermark) mintChunk else 0L
        val shortfall = maxOf(0L, amount - held - chunk)
        if (shortfall > 0L && !force) return null
        var local = start
        if (chunk > 0L) {
            local = local.piece(local.mint(nextMintId(), root, SELF, chunk).delta)
            mints++
        }
        if (shortfall > 0L) {
            local = local.piece(local.mint(nextMintId(), root, SELF, shortfall).delta)
            forcedMints++
        }
        return local
    }

    /**
     * A fresh id per mint act. Two acts under one id max-join into one record and the smaller amount
     * is silently lost: an unreleased draft of this prototype derived the id from two counters, reused
     * one, and its descent then failed at the root for want of supply it believed it had minted.
     */
    private fun nextMintId(): MintId = MintId("local#${mintSeq++}")

    private fun withAncestors(leaves: Set<GroupId>): Set<GroupId> {
        val out = HashSet<GroupId>()
        for (leaf in leaves) {
            var g: GroupId? = leaf
            while (g != null && out.add(g)) g = parentOf[g]
        }
        return out
    }

    /**
     * A [LedgerCell] that admits the seat patch and exactly one delegation per [arm], then refuses
     * every later write, so [FairShareAllocator.schedule] stops after one grant.
     */
    private class OneGrantCell(initial: EntitlementLedger) : LedgerCell {
        override var current: EntitlementLedger = initial
            private set

        private var parent: GroupId? = null
        private var picked: AttachmentId? = null
        private var picking = false
        var grantedAmount: Long = 0L
            private set

        fun arm(parent: GroupId) {
            this.parent = parent
            picked = null
            grantedAmount = 0L
        }

        fun inPick(): Boolean = picking

        fun granted(): AttachmentId? = picked

        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            if (picked != null) return false
            val before = current
            picking = true
            val patch = try {
                transform(before)
            } finally {
                picking = false
            }
            if (patch == null) return false
            val after = before.piece(patch.delta)
            // A delegation is the only write here that bumps `issued` on a child edge of the armed
            // parent; a seat writes only a gauge, and a delegation's witness names ancestor edges.
            val at = checkNotNull(parent) { "cell not armed" }
            val bumped = patch.delta.issuedEdges().filter { before.record(it)?.parent == at }
            if (bumped.isNotEmpty()) {
                val edge = bumped.single()
                picked = edge
                grantedAmount = after.baseIssuance(edge) - before.baseIssuance(edge)
            }
            current = after
            return true
        }
    }

    companion object {
        /** The one writer. */
        val SELF: ReplicaId = ReplicaId("local")

        /** A target so large that `targetOutstanding − outstanding` never runs out. */
        private const val UNBOUNDED_TARGET: Long = 1L shl 40
    }
}

/** One open lease: the leaf it runs at, the path captured at acquire, and what is reserved so far. */
internal data class PrototypeLease(val id: Long, val leaf: GroupId, val path: List<AttachmentId>, val reserved: Long)

internal sealed interface PrototypeAcquire {
    data class Acquired(val lease: PrototypeLease) : PrototypeAcquire
    data object NothingEligible : PrototypeAcquire
    /** One capped top-up could not cover the reservation; the ledger is unchanged. */
    data object SupplyExhausted : PrototypeAcquire
    /** The descent found no grantable child under [at]; the ledger is unchanged. */
    data class DescentFailed(val at: GroupId) : PrototypeAcquire
}

internal sealed interface PrototypeExtend {
    data object Extended : PrototypeExtend
    /** One capped top-up could not cover the extension; the ledger and the lease are unchanged. */
    data object SupplyExhausted : PrototypeExtend
}

internal data class PrototypeComplete(val charged: Long, val overrun: Long)
