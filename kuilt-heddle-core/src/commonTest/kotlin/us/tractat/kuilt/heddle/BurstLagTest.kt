package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Weights hold when work arrives in short bursts with idle gaps between them (#3376).
 *
 * The rigs are the design reviews' (S1, S7, S8, S9, P1, P2), on one [FairShareAllocator] driven
 * through [FairShareAllocator.schedule], with a quantum of 1 and every unit minted at the parent.
 * Each burst mints its supply, the children it names want it, and then everyone idles a round, so
 * the parent drains completely between bursts. Fair share is what a fluid scheduler gives over the
 * stretches each child actually wanted service: a unit handed out while only one child asks is
 * that child's, and a child that was not asking earns nothing for it (design §10.6).
 *
 * Before the net-service clock each wake clamped the waker to the front, which forgave the lag a
 * short burst leaves behind: S1c split 300/300 and S7 handed the light child nothing. Now a
 * returning child moves forward by how far its parent's clock ran while it was away (design §7.2).
 *
 * Every rig counts the wakes it drove, a child this allocator last saw not asking that asks now,
 * so a rig that never reached a wake fails rather than passing on a split it never tested.
 */
class BurstLagTest {
    private val root = GroupId("p")

    /**
     * Set by [everyRowPicksAsItsNoOriginTwin] alone: while it is, [rig] lifts every child and builds
     * each rig with a [Twin], and collects it here.
     */
    private var lifted: MutableList<Rig>? = null

    private fun want(max: Long = 1L) = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = max)

    /** A ledger cell that counts the units each child is delegated: a rise in its base issuance. */
    private class Counting(override var current: EntitlementLedger, private val watched: List<AttachmentRecord>) : LedgerCell {
        val granted = LinkedHashMap<String, Long>().apply { watched.forEach { put(it.child.value, 0L) } }

        /** Each delegation as it landed, `child:units`, in order: the picks a twin is compared on. */
        val picks = ArrayList<String>()

        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            val before = current
            current = current.piece(patch.delta)
            for (edge in watched) {
                val units = current.baseIssuance(edge.id) - before.baseIssuance(edge.id)
                if (units > 0L) {
                    granted[edge.child.value] = granted.getValue(edge.child.value) + units
                    picks += "${edge.child.value}:$units"
                }
            }
            return true
        }

        fun mint(tag: String, units: Long) {
            current = current.piece(current.mint(MintId(tag), GroupId("p"), LOCAL, units).delta)
        }

        fun refund(edge: AttachmentRecord, units: Long) {
            current = current.piece(checkNotNull(current.release(LOCAL, edge.id, units)) { "release refused" }.delta)
        }
    }

    /**
     * The step B twin of a [Rig]'s allocator (#3472, slice 1): its own copy of the ledger and the wake
     * memory, the rebase switched off, so it never raises an origin. It is given every call the rig
     * makes.
     */
    private class Twin(val cell: Counting, val allocator: FairShareAllocator)

    private class Rig(val kids: List<AttachmentRecord>, val cell: Counting, val allocator: FairShareAllocator, val twin: Twin? = null) {
        var wakes = 0

        /** With a [twin]: the children this allocator would clamp under the below-zero first-sighting arm, summed over the rounds. */
        var unobservedBelowZero = 0

        fun granted(): Map<String, Long> = cell.granted

        /** One `schedule`, each child asking for what [demand] says; counts the wakes it drives. */
        fun round(demand: (AttachmentId) -> Demand) {
            wakes += kids.count { allocator.lastObservedDemanding(it.id) == false && demand(it.id).targetOutstanding > 0L }
            if (twin != null) unobservedBelowZero += neverObservedBelowZero(cell.current, allocator) { demand(it.id).targetOutstanding > 0L }.size
            allocator.schedule(GroupId("p"), cell, 0L, demand)
            twin?.let { it.allocator.schedule(GroupId("p"), it.cell, 0L, demand) }
        }

        /** Mint [units] at the parent, on the twin too. */
        fun mint(tag: String, units: Long) {
            cell.mint(tag, units)
            twin?.cell?.mint(tag, units)
        }

        /** Refund [units] of [edge]'s grant, on the twin too. */
        fun refund(edge: AttachmentRecord, units: Long) {
            cell.refund(edge, units)
            twin?.cell?.refund(edge, units)
        }

        fun ask(asking: (AttachmentId) -> Boolean) {
            val wants = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1L)
            round { id -> if (asking(id)) wants else Demand.NONE }
        }
    }

    private fun rig(
        weights: List<Long>,
        quantum: Long = 1L,
        names: List<String> = weights.indices.map { "b$it" },
        seats: Map<Int, Long> = emptyMap(),
        observed: Boolean? = null,
    ): Rig {
        val kids = weights.mapIndexed { i, w -> edgeOf(root, GroupId("p/${names[i]}"), w) }
        var ledger = shapedLedger(root, kids)
        val twins = lifted
        if (twins != null) {
            // Every child seated at one virtual time, far enough up that the heaviest starts past 2⁶³ ticks.
            val lift = Long.MAX_VALUE / (TICKS_PER_UNIT * weights.max()) + 1L
            ledger = ledger.piece(
                EntitlementLedger.of(gauges = kids.indices.associate { i -> kids[i].id to gaugeAt(lift.timesWide(TICKS_PER_UNIT * weights[i]) + (seats[i] ?: 0L), 0L) }),
            )
        } else if (seats.isNotEmpty()) {
            ledger = ledger.piece(EntitlementLedger.of(gauges = seats.entries.associate { (i, t) -> kids[i].id to gaugeAt(Int128.of(t), 0L) }))
        }
        val memory = if (observed == null) AllocatorMemory.EMPTY else AllocatorMemory(emptyMap(), kids.associate { it.id to observed })
        val twin = twins?.let { Twin(Counting(ledger, kids), FairShareAllocator(LOCAL, PolicyConfig(quantum = quantum), memory).apply { rebaseEnabled = false }) }
        val rig = Rig(kids, Counting(ledger, kids), FairShareAllocator(LOCAL, PolicyConfig(quantum = quantum), memory), twin)
        twins?.add(rig)
        // Seen idle once before the first burst, as a lane that has just been created is.
        if (observed == null) rig.ask { _ -> false }
        return rig
    }

    /** [bursts] times: mint [perBurst], every child wants it, then everyone idles a round. */
    private fun together(weights: List<Long>, perBurst: Long, bursts: Int = BURSTS): Rig {
        val rig = rig(weights)
        repeat(bursts) { i ->
            rig.mint("m$i", perBurst)
            rig.ask { _ -> true }
            rig.ask { _ -> false }
        }
        return rig
    }

    /**
     * [bursts] times: one unit only child [first] wants, then, after an idle round when [gap], [rest]
     * units every child wants, then everyone idles. S7 without the gap, S8 with it.
     */
    private fun staggered(weights: List<Long>, first: Int, rest: Long, gap: Boolean = false, bursts: Int = BURSTS): Rig {
        val rig = rig(weights)
        repeat(bursts) { i ->
            rig.mint("f$i", 1L)
            rig.ask { id -> id == rig.kids[first].id }
            if (gap) rig.ask { _ -> false }
            rig.mint("r$i", rest)
            rig.ask { _ -> true }
            rig.ask { _ -> false }
        }
        return rig
    }

    /** S9, a ladder: b0 alone 1 unit, b0 and b1 one more, all three 3 more, then everyone idles. */
    private fun ladder(weights: List<Long>, bursts: Int = BURSTS): Rig {
        val rig = rig(weights)
        repeat(bursts) { i ->
            rig.mint("f$i", 1L)
            rig.ask { id -> id == rig.kids[0].id }
            rig.mint("g$i", 1L)
            rig.ask { id -> id != rig.kids[2].id }
            rig.mint("r$i", 3L)
            rig.ask { _ -> true }
            rig.ask { _ -> false }
        }
        return rig
    }

    private fun near(label: String, fair: List<Long>, rig: Rig, wakes: Int): () -> Unit = {
        val delivered = rig.granted().values.toList()
        assertAll(
            { assertEquals(fair.sum(), delivered.sum(), "$label: the rig handed out every unit it minted") },
            { assertTrue(rig.wakes >= wakes, "$label: the rig drove ${rig.wakes} wakes, wanted at least $wakes") },
            *fair.indices.map { i ->
                { assertTrue(kotlin.math.abs(delivered[i] - fair[i]) <= TOLERANCE, "$label: b$i got ${delivered[i]} of a fair ${fair[i]}, delivered $delivered") }
            }.toTypedArray(),
        )
    }

    /** S1 family: every child asks throughout each burst. S1c is weights 2:1 and two units a burst. */
    @Test
    fun shortBurstsHoldTheWeights() {
        assertAll(
            near("S1c 2:1, 2 a burst", listOf(400, 200), together(listOf(2, 1), 2L), wakes = 2 * BURSTS),
            near("S1 1:1, 3 a burst", listOf(450, 450), together(listOf(1, 1), 3L), wakes = 2 * BURSTS),
            near("S1d 3:1, 5 a burst", listOf(1125, 375), together(listOf(3, 1), 5L), wakes = 2 * BURSTS),
            near("S1e 1:1:1, 4 a burst", listOf(400, 400, 400), together(listOf(1, 1, 1), 4L), wakes = 3 * BURSTS),
            // A spread past 16x: fair is 2,100 · w / 101, so 1,247.5 / 623.8 / 207.9 / 20.8.
            near("S1w 60:30:10:1, 7 a burst", listOf(1247, 624, 208, 21), together(listOf(60, 30, 10, 1), 7L), wakes = 4 * BURSTS),
        )
    }

    /**
     * S7: one child asks alone for the first unit of each burst, then both ask for one more. The
     * first unit is uncontested, so it is that child's; the second splits by weight. With weights
     * 2:1, heavy first, fair is 500/100. The issue's "about 400/200" counts the first unit as
     * contested, which would credit the light child for a stretch it was not asking.
     */
    @Test
    fun aChildThatAlwaysAsksSecondStillGetsItsShare() {
        assertAll(
            near("S7 2:1 heavy first", listOf(500, 100), staggered(listOf(2, 1), first = 0, rest = 1L), wakes = 2 * BURSTS),
            near("S7b 2:1 light first", listOf(200, 400), staggered(listOf(2, 1), first = 1, rest = 1L), wakes = 2 * BURSTS),
            near("S7 3:1 heavy first", listOf(525, 75), staggered(listOf(3, 1), first = 0, rest = 1L), wakes = 2 * BURSTS),
            near("S7 3:1 light first", listOf(225, 375), staggered(listOf(3, 1), first = 1, rest = 1L), wakes = 2 * BURSTS),
            near("S7c 1:1, 1 then 2", listOf(600, 300), staggered(listOf(1, 1), first = 0, rest = 2L), wakes = 2 * BURSTS),
        )
    }

    /**
     * S8: S7 with an idle round between the solo unit and the shared one, so the child that asked
     * first leaves a set of one and comes back. Kept lag let that child bank the gap (2:1 light
     * first gave 150/450); the clock did not run while nobody asked, so it banks nothing.
     */
    @Test
    fun aChildLeavingASetOfOneBanksNothing() {
        assertAll(
            near("S8 1:1", listOf(450, 150), staggered(listOf(1, 1), first = 0, rest = 1L, gap = true), wakes = 3 * BURSTS),
            near("S8 2:1 heavy first", listOf(500, 100), staggered(listOf(2, 1), first = 0, rest = 1L, gap = true), wakes = 3 * BURSTS),
            near("S8 2:1 light first", listOf(200, 400), staggered(listOf(2, 1), first = 1, rest = 1L, gap = true), wakes = 3 * BURSTS),
            near("S8 3:1 heavy first", listOf(525, 75), staggered(listOf(3, 1), first = 0, rest = 1L, gap = true), wakes = 3 * BURSTS),
            near("S8 3:1 light first", listOf(225, 375), staggered(listOf(3, 1), first = 1, rest = 1L, gap = true), wakes = 3 * BURSTS),
        )
    }

    /** S9: a ladder of three children joining one at a time. */
    @Test
    fun aLadderOfJoinersHoldsTheWeights() {
        assertAll(
            near("S9 1:1:1", listOf(750, 450, 300), ladder(listOf(1, 1, 1)), wakes = 3 * BURSTS),
            near("S9 1:2:3", listOf(550, 500, 450), ladder(listOf(1, 2, 3)), wakes = 3 * BURSTS),
            near("S9 3:2:1", listOf(930, 420, 150), ladder(listOf(3, 2, 1)), wakes = 3 * BURSTS),
        )
    }

    /**
     * P1: a, b and c equal, a at [aUnits] and the others at 30. a stops asking (or [pause] is false
     * and it keeps asking), then c stops, then 100 units are handed out to whoever asks: a's take.
     * Pausing must change nothing.
     */
    private fun setShrinks(aUnits: Long, pause: Boolean): Pair<Long, Int> {
        val rig = rig(listOf(1, 1, 1), names = listOf("a", "b", "c"), seats = mapOf(0 to aUnits * 16, 1 to 30L * 16, 2 to 30L * 16), observed = true)
        val (a, b, c) = rig.kids
        rig.ask { id -> !(id == a.id && pause) }
        rig.ask { id -> id == b.id || (id == a.id && !pause) }
        rig.mint("m", 100L)
        rig.ask { id -> id != c.id }
        return rig.granted().getValue("p/a") to rig.wakes
    }

    @Test
    fun aDebtorThatPausesWhileASiblingLeavesKeepsItsPlace() {
        val (debtorPaused, debtorWakes) = setShrinks(60L, pause = true)
        val (debtorStayed, _) = setShrinks(60L, pause = false)
        val (owedPaused, owedWakes) = setShrinks(0L, pause = true)
        val (owedStayed, _) = setShrinks(0L, pause = false)
        assertAll(
            { assertEquals(1, debtorWakes, "the rig: the paused debtor woke once") },
            { assertEquals(1, owedWakes, "the rig: the paused creditor woke once") },
            { assertEquals(35L, debtorStayed, "the debtor that kept asking takes its fair 35") },
            { assertEquals(debtorStayed, debtorPaused, "pausing costs the debtor nothing") },
            { assertEquals(owedStayed, owedPaused, "pausing buys the creditor nothing") },
        )
    }

    /**
     * P2: b's over-reserved grant (10, it uses 1) is outstanding when a leaves, and refunded during
     * a's gap: a/b of the last 20 units plus the 9 refunded, fair 15/14 since a and b are level when
     * a returns. When [early], the refund lands before a leaves, b takes it during
     * the gap, and the last 20 split level, 10/10.
     */
    private fun siblingRefund(early: Boolean): Pair<String, Int> {
        val rig = rig(listOf(1, 1), quantum = 10L, names = listOf("a", "b"))
        val (a, b) = rig.kids
        rig.mint("m1", 11L)
        rig.round { id -> if (id == a.id) want(1) else want(10) }
        if (early) rig.refund(b, 9L)
        rig.mint("m2", 20L)
        rig.round { id -> if (id == a.id) Demand.NONE else want(1) }
        if (!early) rig.refund(b, 9L)
        val before = HashMap(rig.granted())
        rig.mint("m3", 20L)
        rig.round { want(1) }
        return listOf("p/a", "p/b").joinToString("/") { "${rig.granted().getValue(it) - before.getValue(it)}" } to rig.wakes
    }

    /**
     * P2 is not closed by the net-service clock (#3239). b's reservation reached the clock at the shared
     * weight before a left, and its refund comes off at b's weight alone while a is away, so the clock
     * runs 4½ units short and a returns that far behind. Pinned at today's value so a change is seen, not claimed fixed.
     */
    @Test
    fun aSiblingRefundDuringTheGapIsPinnedAtItsKnownCredit() {
        val (inGap, inGapWakes) = siblingRefund(early = false)
        val (beforeLeave, beforeWakes) = siblingRefund(early = true)
        assertAll(
            { assertEquals(3, inGapWakes, "the rig: both children woke at the start, and a woke again after its gap") },
            { assertEquals(3, beforeWakes, "the control rig woke the same") },
            { assertEquals("10/10", beforeLeave, "control: a refund before a leaves is spent while a is away, and a and b return level") },
            { assertEquals("19/10", inGap, "the residual credit #3239 tracks: fair is 15/14") },
        )
    }

    /**
     * a and b equal, a asking for everything and b for [bTarget] outstanding. One call hands out
     * [first], so b fills partway through it beside a and a runs on alone. Then, when [idleCall], a
     * call hands a 30 more while b still holds its target, so b is seen idle at that call's entry.
     * Then both ask for everything and 40 more are handed out: the two takes of that last 40, a's
     * first, and the demanding state this allocator last saw b in before that last call.
     */
    private fun filledPartway(bTarget: Long, first: Long, idleCall: Boolean): Triple<Long, Long, Boolean?> {
        val rig = rig(listOf(1, 1), names = listOf("a", "b"))
        val (a, b) = rig.kids
        val capped = Demand(targetOutstanding = bTarget, maximumUsefulGrant = 1L)
        rig.mint("first", first)
        rig.round { id -> if (id == a.id) want() else capped }
        check(rig.granted() == mapOf("p/a" to first - bTarget, "p/b" to bTarget)) { "the rig: b filled partway, ${rig.granted()}" }
        if (idleCall) {
            rig.mint("idle", 30L)
            rig.round { id -> if (id == a.id) want() else capped }
            check(rig.granted().getValue("p/b") == bTarget) { "the rig: b took nothing while it held its target" }
        }
        val seen = rig.allocator.lastObservedDemanding(b.id)
        val before = HashMap(rig.granted())
        rig.mint("last", 40L)
        rig.round { want() }
        val (aTake, bTake) = listOf("p/a", "p/b").map { rig.granted().getValue(it) - before.getValue(it) }
        return Triple(aTake, bTake, seen)
    }

    /**
     * A child that stops asking partway through a `schedule` call, because it reached its target,
     * stopped at the grant that filled it, and a's grants after that are a's alone (#3416 review).
     * So b comes back level with a and the last 40 split evenly, however the stop was seen. A
     * once-per-call sample charged a's solo grants at both children's weight, so b came back behind
     * by them and was owed a's solo stretch: 15/25 after an idle call. Without an idle call it was
     * never seen to stop at all and kept its raw position, 15/25 again, under the clamp as well.
     *
     * The control is a b that fills on the call's last grant: it stopped where the clock already
     * stood, so it moves by exactly nothing and the split is even either way.
     */
    @Test
    fun aChildFilledPartwayThroughACallIsOwedNothingForItsSiblingsLaterGrants() {
        val (idleA, idleB, idleSeen) = filledPartway(bTarget = 10L, first = 30L, idleCall = true)
        val (nextA, nextB, _) = filledPartway(bTarget = 10L, first = 30L, idleCall = false)
        val (lastA, lastB, _) = filledPartway(bTarget = 10L, first = 20L, idleCall = false)
        assertAll(
            { assertEquals(false, idleSeen, "the rig: b was seen not asking before the last call") },
            { assertEquals(20L to 20L, idleA to idleB, "seen idle at the next entry: b returns level") },
            { assertEquals(20L to 20L, nextA to nextB, "asking again at the next entry: b returns level") },
            { assertEquals(20L to 20L, lastA to lastB, "control: b filled on the call's last grant") },
        )
    }

    /**
     * The pre-seat rebase triggers on where a returner will land, not only on the front and the
     * stayers (#3376). a is a stayer just under its rebase threshold `R`, so neither the front nor
     * any stayer calls for a raise. b left near the same threshold, and its parent's clock has run
     * far enough since that it lands half an `R` past its ceiling. Only a raise taken before b is
     * moved brings it back under, and a's position bounds that raise at nearly `R`, which is enough.
     * Without the landing trigger b lands past its ceiling and the first pick refuses.
     *
     * The state is set directly: how a returner reaches it in a real run is the nested-group rig,
     * `aSaturatedNestedGroupRefusesOnlyPicksThatOrderIt`, which exercises much more besides.
     */
    @Test
    fun aReturnerLandingPastItsCeilingIsRebasedBeforeItLands() {
        val r = rebaseThreshold(Weight.ONE)
        val (a, b) = returnerKids
        val (cell, allocator, outcome) = returnerPastItsCeiling(rebase = true)
        val landed = allocator.effectiveVirtualService(cell.current, root, b.id)
        val aAt = allocator.effectiveVirtualService(cell.current, root, a.id)
        // Read as a lead over a: the window rule lowers the origin to the pair's front, so b lands
        // half way down and a below zero, as far apart as before (#3353).
        val lead = if (landed != null && aAt != null) landed - aAt else null
        assertAll(
            { assertEquals(AllocationOutcome.Delegated(10), outcome, "the pick is made, not refused at b's ceiling") },
            { assertTrue(allocator.rebases > 0, "the rig: the parent rebased") },
            { assertEquals(10L, cell.granted.getValue("p/a"), "a, far behind b, takes every unit") },
            { assertTrue(lead != null && lead > Rational.of(14L * (r / 16L)), "the rig: b came back far ahead of a, by $lead") },
        )
    }

    private val returnerKids = listOf(edgeOf(root, GroupId("p/a")), edgeOf(root, GroupId("p/b")))

    /** The state [aReturnerLandingPastItsCeilingIsRebasedBeforeItLands] sets, and its one `schedule`, rebasing or not. */
    private fun returnerPastItsCeiling(rebase: Boolean): Triple<Counting, FairShareAllocator, AllocationOutcome> {
        val r = rebaseThreshold(Weight.ONE)
        val ceiling = positionCeiling(Weight.ONE)
        val kids = returnerKids
        val (a, b) = kids
        val seated = mapOf(a.id to gaugeAt(Int128.of(r - 16L), 0L), b.id to gaugeAt(Int128.of(r - 16L), 0L))
        val shift = ceiling - (r - 16L) + r / 2
        val memory = AllocatorMemory(
            wakeOffsets = emptyMap(),
            demandingObserved = mapOf(a.id to true, b.id to false),
            clocks = mapOf(root to shift.timesWide(720_720L)),
            served = mapOf(a.id to 0L),
            departures = mapOf(b.id to Int128.ZERO),
        )
        val cell = Counting(shapedLedger(root, kids).piece(EntitlementLedger.of(gauges = seated)), kids)
        cell.mint("m", 10L)
        val allocator = FairShareAllocator(LOCAL, PolicyConfig(quantum = 1L), memory).apply { rebaseEnabled = rebase }
        val outcome = allocator.schedule(root, cell, 0L) { want() }
        return Triple(cell, allocator, outcome)
    }

    /**
     * Every row above, again, beside its step B twin (design §7.1, *Future: positions with no
     * origin*, slice 1). Since step A a pick orders the full 128-bit position, so an allocator with
     * the rebase off never raises an origin: it is the engine step B leaves. Each row runs on a rig
     * that gives both allocators every call, and the two must make the same delegations, unit for
     * unit and in order.
     *
     * The rows as written stay near zero, where nothing rebases, so here each rig seats every child
     * at one virtual time, far enough up that the heaviest child starts past `2⁶³` ticks on its own
     * grid. That is a translation, so every row's own assertions still hold and run here too. It
     * makes the rig fire on every row: the rebasing side rebases, the twin never raises an origin
     * and holds a position past `2⁶³` throughout, and the rebasing side lands no joiner at its floor
     * and clamps no never-observed child below zero, the two causes the design lets a row differ by.
     * The hand-set returner row runs as written, which is already past its ceiling.
     */
    @Test
    fun everyRowPicksAsItsNoOriginTwin() {
        val rigs = ArrayList<Rig>()
        lifted = rigs
        try {
            shortBurstsHoldTheWeights()
            aChildThatAlwaysAsksSecondStillGetsItsShare()
            aChildLeavingASetOfOneBanksNothing()
            aLadderOfJoinersHoldsTheWeights()
            aDebtorThatPausesWhileASiblingLeavesKeepsItsPlace()
            aSiblingRefundDuringTheGapIsPinnedAtItsKnownCredit()
            aChildFilledPartwayThroughACallIsOwedNothingForItsSiblingsLaterGrants()
            idlingLongerEarnsNothing()
            lagOwedWhenLeavingIsStillOwedOnReturn()
            aReturnerShiftThatDoesNotLandOnItsGridDoesNotDrift()
        } finally {
            lifted = null
        }
        val (returner, returnerAllocator, returnerOutcome) = returnerPastItsCeiling(rebase = true)
        val (returnerTwin, returnerTwinAllocator, returnerTwinOutcome) = returnerPastItsCeiling(rebase = false)
        assertAll(
            { assertEquals(LIFTED_RIGS, rigs.size, "every row built its rigs lifted and twinned") },
            *rigs.mapIndexed(::twinAgrees).toTypedArray(),
            { assertEquals(AllocationOutcome.Delegated(10), returnerOutcome, "returner: the rebasing side delegates") },
            { assertTrue(returnerAllocator.rebases > 0, "returner: the rig, the rebasing side rebased") },
            { assertEquals(returnerOutcome, returnerTwinOutcome, "returner: the twin delegates the same") },
            { assertEquals(0, returnerTwinAllocator.rebases, "returner: the twin never rebased") },
            { assertEquals(0, returnerAllocator.floorLandingsForTest, "returner: no joiner landed at its floor") },
            {
                val peak = peakEffectiveTicks(returnerTwin.current, returnerTwinAllocator.memory.wakeOffsets, root)
                assertTrue(peak != null && peak >= LONG_LIMIT_TICKS, "returner: the rig, the twin's b landed past 2⁶³ ticks ($peak)")
            },
            { assertEquals(returner.picks, returnerTwin.picks, "returner: the same delegations in the same order") },
        )
    }

    /** That lifted rig number [i] fired, and that its twin made the same delegations. */
    private fun twinAgrees(i: Int, rig: Rig): () -> Unit = {
        val twin = checkNotNull(rig.twin) { "rig $i has no twin" }
        assertAll(
            { assertTrue(rig.allocator.rebases > 0, "rig $i: the rig, the rebasing side rebased") },
            { assertEquals(0, twin.allocator.rebases, "rig $i: the twin never rebased") },
            { assertEquals(emptyMap(), raisedOrigins(twin.cell.current), "rig $i: the twin never raised an origin") },
            {
                val peak = peakEffectiveTicks(twin.cell.current, twin.allocator.memory.wakeOffsets, root)
                assertTrue(peak != null && peak >= LONG_LIMIT_TICKS, "rig $i: the rig, the twin holds a position past 2⁶³ ticks ($peak)")
            },
            { assertEquals(0, rig.allocator.floorLandingsForTest, "rig $i: the rebasing side landed no joiner at its floor") },
            { assertEquals(0, rig.unobservedBelowZero, "rig $i: the rebasing side clamped no never-observed child below zero") },
            { assertTrue(rig.cell.picks.isNotEmpty(), "rig $i: the rig delegated") },
            { assertEquals(rig.cell.picks, twin.cell.picks, "rig $i: the same delegations in the same order") },
        )
    }

    /**
     * a and b equal. A burst of [first] units both want, then b sits out [idle] rounds in which a
     * takes 2 units alone, then a burst of [last] both want: b's take of that last burst.
     */
    private fun takeAfterIdling(idle: Int, first: Long, last: Long): Int {
        val rig = rig(listOf(1, 1))
        val (a, _) = rig.kids
        rig.mint("first", first)
        rig.ask { _ -> true }
        repeat(idle) { i ->
            rig.mint("idle$i", 2L)
            rig.ask { id -> id == a.id }
        }
        val before = rig.granted().getValue("p/b1")
        val handedOut = rig.granted().values.sum()
        val wakesBefore = rig.wakes
        rig.mint("last", last)
        rig.ask { _ -> true }
        check(rig.granted().values.sum() == handedOut + last) { "the last burst was handed out in full" }
        check(rig.wakes == wakesBefore + 1) { "b woke for the last burst" }
        return (rig.granted().getValue("p/b1") - before).toInt()
    }

    /**
     * §10.6, as a property: how long b sat out changes nothing about what it gets when it is back.
     * The control that makes it falsifiable is a waker that keeps its raw position: on the spike it
     * took 7 of 11 after one idle round and all 11 after ten.
     */
    @Test
    fun idlingLongerEarnsNothing() {
        val random = Random(SEED)
        val cases = List(SAMPLES) { Triple(random.nextLong(2L, 8L), random.nextLong(5L, 20L), random.nextInt(2, 400)) }
        assertAll(
            *cases.map { (first, last, idle) ->
                {
                    val short = takeAfterIdling(1, first, last)
                    val long = takeAfterIdling(idle, first, last)
                    assertEquals(short, long, "first=$first last=$last: b's take after 1 idle round against $idle")
                }
            }.toTypedArray(),
            { assertEquals(SAMPLES, cases.size, "every sample ran") },
        )
    }

    /** b leaves owed half a unit, a having won the last tie. It is still owed when it comes back. */
    @Test
    fun lagOwedWhenLeavingIsStillOwedOnReturn() {
        assertAll(
            { assertEquals(6, takeAfterIdling(1, first = 3L, last = 11L), "owed: b wins the tie it lost") },
            { assertEquals(6, takeAfterIdling(1000, first = 3L, last = 11L), "owed, after a long idle") },
            { assertEquals(5, takeAfterIdling(1000, first = 2L, last = 11L), "level: a wins the tie") },
        )
    }

    /** One stretch of a [cycling] burst: the children at [asking] ask, and [units] are handed out among them. */
    private class Stretch(val asking: Set<Int>, val units: Long)

    /** [bursts] times, each of [stretches] in turn, with no idle round between them. */
    private fun cycling(weights: List<Long>, stretches: List<Stretch>, bursts: Int): Rig {
        val rig = rig(weights)
        repeat(bursts) { i ->
            stretches.forEachIndexed { j, stretch ->
                rig.mint("s$i.$j", stretch.units)
                rig.ask { id -> rig.kids.indexOfFirst { it.id == id } in stretch.asking }
            }
        }
        return rig
    }

    /** What a fluid scheduler gives child [i] over [bursts] of [stretches]: each stretch split by weight among its askers. */
    private fun fluid(weights: List<Long>, stretches: List<Stretch>, bursts: Int, i: Int): Rational =
        stretches.filter { i in it.asking }.fold(Rational.ZERO) { acc, s ->
            acc + Rational.of(s.units * weights[i] * bursts, s.asking.sumOf { weights[it] })
        }

    /**
     * How far child 0's take strays from its fluid share after [bursts] of [stretches], in units:
     * positive when it got more than fair.
     */
    private fun strayOfX(weights: List<Long>, stretches: List<Stretch>, bursts: Int): Rational {
        val rig = cycling(weights, stretches, bursts)
        val delivered = rig.granted().getValue("p/b0")
        check(rig.wakes >= bursts) { "the rig: x drove ${rig.wakes} wakes over $bursts bursts" }
        return Rational.of(delivered) - fluid(weights, stretches, bursts, 0)
    }

    /**
     * A returner's shift does not drift when its parent's clock, while it is back, runs a run that is
     * not a whole number of its ticks (#3416 review). x has weight 1. The stretches it is present
     * for run the clock by `16/13` and `32/10` of x's ticks, so each return's ideal shift is a
     * fraction, and a shift taken as the difference of two rounded readings errs by a fraction
     * that, in a repeating pattern, locks into one phase and comes back every return. Measured with
     * that rule: the first rig held x in **credit**, which §10.5 forbids, 51/13 units over fair at
     * 300 bursts and 61/13 at 1,200 (its siblings return too, and their own errors absorb some of
     * x's); the second drifted toward penalty without bound, −16 at 300 and −66 at 1,200. A shift
     * that carries its own rounding forward keeps x's total over-shift under one tick, so its stray
     * from fair is the same after 1,200 bursts as after 300, and within a unit of fair.
     */
    @Test
    fun aReturnerShiftThatDoesNotLandOnItsGridDoesNotDrift() {
        // x, a, b, c at 1, 7, 2, 3: {a} 2 units, {x, a, b, c} 1, {x, a, b} 2.
        val credit = listOf(1L, 7L, 2L, 3L) to listOf(Stretch(setOf(1), 2L), Stretch(setOf(0, 1, 2, 3), 1L), Stretch(setOf(0, 1, 2), 2L))
        // x, a, d, e at 1, 5, 10, 4: {a, d} 1 unit, {x, a, e} 1, {x, a} 2.
        val penalty = listOf(1L, 5L, 10L, 4L) to listOf(Stretch(setOf(1, 2), 1L), Stretch(setOf(0, 1, 3), 1L), Stretch(setOf(0, 1), 2L))
        val rows = listOf("credit" to credit, "penalty" to penalty).map { (label, rig) ->
            val (weights, stretches) = rig
            Triple(label, strayOfX(weights, stretches, 300), strayOfX(weights, stretches, 1_200))
        }
        println("stray of x from fair, at 300 and 1,200 bursts: ${rows.joinToString { (l, a, b) -> "$l $a, $b" }}")
        assertAll(
            *rows.map { (label, short, long) ->
                { assertTrue(abs(long - short) < Rational.ONE, "$label: x strays $short from fair at 300 bursts and $long at 1,200") }
            }.toTypedArray(),
            *rows.map { (label, _, long) ->
                { assertTrue(abs(long) < Rational.ONE, "$label: x strays $long from fair at 1,200 bursts") }
            }.toTypedArray(),
        )
    }

    private fun abs(r: Rational): Rational = if (r < Rational.ZERO) Rational.ZERO - r else r

    private companion object {
        const val BURSTS = 300

        /** The rigs the rows build: 18 burst rows, P1 4, P2 2, the partway rows 3, 40 idling samples 2 each, lag 3, drift 4. */
        const val LIFTED_RIGS = 114

        /**
         * Units either way of fair, over the whole run: none. Every rig lands on its fair split
         * exactly, S1w's to the unit it rounds to, so a drift of one unit over 300 bursts reds.
         */
        const val TOLERANCE = 0L
        const val SEED = 3376L
        const val SAMPLES = 40
    }
}
