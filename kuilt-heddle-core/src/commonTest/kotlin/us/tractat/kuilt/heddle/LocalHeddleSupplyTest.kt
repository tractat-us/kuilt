package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [LocalHeddle]'s capacity, supply top-up and lease reconcile (#3173, plan Task L2, with
 * Amendments 3, 8 and 9).
 */
class LocalHeddleSupplyTest {

    private val root = GroupId("root")
    private val a = GroupId("a/b")
    private val b = GroupId("#3")
    private val c = GroupId("é#%@")

    private val oneLeaf = shapedLedger(root, listOf(edgeOf(root, a)))
    private val threeLeaves = shapedLedger(root, listOf(edgeOf(root, a, 3), edgeOf(root, b, 2), edgeOf(root, c, 1)))

    private fun policy(reservation: Long, low: Long = 1L, target: Long = 2L): LocalPolicy =
        LocalPolicy(defaultReservation = reservation, supply = SupplyPolicy(lowWatermarkSlots = low, targetSlots = target))

    private fun LocalHeddle.minted(): Long = snapshot().ledger.mintedTotal()
    private fun LocalHeddle.atRoot(): Long = snapshot().ledger.holdings(root, LOCAL)

    @Test
    fun reconcileCapacityIsIdempotent() {
        val h = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 5L))
        val before = h.snapshot()
        h.reconcileCapacity(3L)
        val once = h.snapshot()
        h.reconcileCapacity(3L)
        val twice = h.snapshot()
        h.lease(setOf(a), reservation = 5L)
        val sizedByThree = h.minted()
        val wider = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 5L))
        wider.reconcileCapacity(3L)
        wider.reconcileCapacity(5L)
        wider.lease(setOf(a), reservation = 5L)
        assertAll(
            { assertSame(before.ledger, once.ledger, "sizing the watermark mints nothing") },
            { assertEquals(before, twice, "and a repeated value changes nothing") },
            // Target 3 slots × 5 × 2 rounds = 30 left at the root, plus the 5 the lease took.
            { assertEquals(35L, sizedByThree, "the stored capacity sizes the next top-up") },
            { assertEquals(5L * 5L * 2L + 5L, wider.minted(), "the latest value is the one used") },
        )
    }

    /**
     * Capacity 2 at reservation 5: the low watermark is 10 and the target 20. A top-up runs only
     * when the operation would leave the root below 10, and then leaves it at 20 after the
     * operation. Holding exactly the watermark after it is not below it.
     */
    @Test
    fun topUpInsideAcquireOnlyBelowTheWatermark() {
        val h = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 5L))
        h.reconcileCapacity(2L)
        val minted = ArrayList<Long>()
        val held = ArrayList<Long>()
        fun record() { minted += h.minted(); held += h.atRoot() }
        val first = h.lease(setOf(a), reservation = 5L).also { record() } // 0 − 5 < 10: mint 25
        h.lease(setOf(a), reservation = 5L).also { record() } // 20 − 5 = 15: none
        h.lease(setOf(a), reservation = 5L).also { record() } // 15 − 5 = 10, at the watermark: none
        h.lease(setOf(a), reservation = 5L).also { record() } // 10 − 5 < 10: mint 15
        val extended = h.extend(first.id, 15L).also { record() } // 20 − 15 < 10: mint 15
        h.extend(first.id, 5L).also { record() } // 20 − 5 = 15: none
        assertAll(
            { assertEquals(LeaseOutcome.Done(0L, 0L), extended) },
            { assertEquals(listOf(25L, 25L, 25L, 40L, 55L, 55L), minted, "minted after each operation") },
            { assertEquals(listOf(20L, 15L, 10L, 20L, 20L, 15L), held, "held at the root after each") },
            { assertEquals(setOf(MintId("local#0")), h.snapshot().ledger.mintIds()) },
            { assertEquals(emptyList(), h.snapshot().ledger.validate()) },
        )
    }

    /** A reservation larger than the whole target is still funded: a top-up covers the operation first. */
    @Test
    fun aReservationAboveTheTargetIsFunded() {
        val h = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 1L))
        h.reconcileCapacity(1L)
        val out = h.acquire(setOf(a), setOf(a), startedAt = 0L, reservation = 100L)
        val lease = assertNotNull((out as? AcquireOutcome.Acquired)?.lease, "leased: $out")
        val afterAcquire = h.atRoot()
        val extended = h.extend(lease.id, 50L)
        assertAll(
            { assertEquals(2L, afterAcquire, "the target, 1 slot × 1 × 2 rounds, is left after the 100") },
            { assertEquals(LeaseOutcome.Done(0L, 0L), extended) },
            { assertEquals(2L, h.atRoot()) },
            { assertEquals(152L, h.minted()) },
        )
    }

    /** The top-up is part of the operation: an acquire that is refused leaves none behind. */
    @Test
    fun aRefusedAcquireLeavesNoTopUpBehind() {
        val x = GroupId("x")
        val h = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 1L))
        h.reshapeForTest { it.piece(assertNotNull(it.prepare(edgeOf(root, x))).delta) }
        h.reconcileCapacity(4L)
        val before = h.snapshot().ledger
        val refused = h.acquire(setOf(x), setOf(x), startedAt = 0L)
        val after = h.snapshot().ledger
        h.lease(setOf(a))
        assertAll(
            { assertEquals(AcquireOutcome.NoLiveLineage(root), refused, "x sits behind a prepared edge") },
            { assertSame(before, after, "nothing was minted for it") },
            { assertEquals(0L, after.mintedTotal()) },
            { assertEquals(4L * 2L + 1L, h.minted(), "the rig: the same root does top up for a lease that runs") },
        )
    }

    /**
     * An extend refused as draining leaves no top-up behind either. The root sits exactly at the
     * watermark, so this extend would top up; the control, the same engine with the edge left open,
     * shows it does.
     */
    @Test
    fun aDrainingExtendLeavesNoTopUpBehind() {
        fun atTheWatermark(): Pair<LocalHeddle, Lease> {
            val h = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 5L))
            h.reconcileCapacity(2L)
            val first = h.lease(setOf(a), reservation = 5L)
            repeat(2) { h.lease(setOf(a), reservation = 5L) }
            return h to first
        }
        val (h, lease) = atTheWatermark()
        val held = h.atRoot()
        h.reshapeForTest { it.piece(assertNotNull(it.close(AttachmentId("root>a/b"))).delta) }
        val before = h.snapshot().ledger
        val draining = h.extend(lease.id, 1L)
        val (open, control) = atTheWatermark()
        val mintedBefore = open.minted()
        val extended = open.extend(control.id, 1L)
        assertAll(
            { assertEquals(10L, held, "the rig: the root holds exactly the low watermark, 2 slots × 5") },
            { assertEquals(LeaseOutcome.PathDraining(AttachmentId("root>a/b")), draining) },
            { assertSame(before, h.snapshot().ledger, "nothing was minted for it") },
            { assertEquals(LeaseOutcome.Done(0L, 0L), extended) },
            { assertTrue(open.minted() > mintedBefore, "the control: the same extend on an open path tops up") },
        )
    }

    /**
     * A zero low watermark mints just in time: only when the root cannot fund the operation. With a
     * zero target too it mints exactly the shortfall; with a positive target it leaves the target.
     */
    @Test
    fun aZeroLowWatermarkMintsOnlyWhenTheRootIsShort() {
        val exact = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 4L, low = 0L, target = 0L))
        exact.reconcileCapacity(3L)
        val exactAfter = listOf(
            exact.lease(setOf(a), reservation = 4L).let { exact.minted() to exact.atRoot() },
            exact.lease(setOf(a), reservation = 3L).let { exact.minted() to exact.atRoot() },
        )
        val ahead = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 4L, low = 0L, target = 1L))
        ahead.reconcileCapacity(3L)
        val first = ahead.lease(setOf(a), reservation = 4L)
        val aheadAfter = listOf(ahead.minted() to ahead.atRoot())
        repeat(3) { ahead.lease(setOf(a), reservation = 4L) }
        val drained = ahead.minted() to ahead.atRoot()
        ahead.extend(first.id, 2L)
        assertAll(
            { assertEquals(listOf(4L to 0L, 7L to 0L), exactAfter, "each lease minted exactly what it took") },
            { assertEquals(listOf(16L to 12L), aheadAfter, "short, so it minted the target of 3 × 4 plus the 4 it took") },
            { assertEquals(16L to 0L, drained, "and nothing more while the root could fund each lease, down to empty") },
            { assertEquals(16L + 12L + 2L to 12L, ahead.minted() to ahead.atRoot(), "short again: back to the target") },
        )
    }

    /** A preview tops up where the acquires it previews would, so it does not stop at an empty root. */
    @Test
    fun previewPicksTopUpLikeTheAcquiresTheyPreview() {
        val h = engineOver(threeLeaves, root, supply = 0L, policy = policy(reservation = 2L))
        h.reconcileCapacity(1L)
        val reservations = List(12) { 2L }
        val before = h.snapshot()
        val preview = h.previewPicks(mapOf(a to 20, b to 20, c to 20), setOf(a, b, c), reservations)
        val previewChanged = h.snapshot() != before
        val leased = reservations.map { h.lease(setOf(a, b, c), reservation = it).leaf }
        assertAll(
            { assertEquals(false, previewChanged, "the preview minted nothing for real") },
            { assertEquals(12, preview.size, "the preview ran past the empty root") },
            { assertEquals(leased, preview) },
        )
    }

    /** Before any capacity is reconciled nothing is minted ahead, so supply runs out and is named. */
    @Test
    fun withoutCapacitySupplyIsExhausted() {
        val h = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = 1L))
        assertAll(
            { assertEquals(AcquireOutcome.SupplyExhausted, h.acquire(setOf(a), setOf(a), startedAt = 0L)) },
            { assertEquals(0L, h.minted()) },
        )
    }

    /**
     * 10,000 leases on four slots, with reservations up to three times the default, extensions and
     * overruns, and capacity reconciled once: no acquire and no extend is ever refused for supply.
     */
    @Test
    fun supplyNeverRunsOutBetweenReconciles() {
        val leaves = setOf(a, b, c)
        val h = engineOver(threeLeaves, root, supply = 0L, policy = policy(reservation = 10L))
        h.reconcileCapacity(4L)
        val rng = Random(3173)
        var leased = 0
        var extended = 0
        var completed = 0
        val refusals = ArrayList<String>()
        while (leased < LEASES && refusals.isEmpty()) {
            val open = h.snapshot().leases
            if (open.size < 4) {
                val out = h.acquire(leaves, leaves, startedAt = leased.toLong(), reservation = 1L + rng.nextInt(30))
                if (out is AcquireOutcome.Acquired) leased++ else refusals += "acquire $leased: $out"
            } else if (rng.nextInt(4) == 0) {
                val out = h.extend(open[rng.nextInt(open.size)].id, 1L + rng.nextInt(20))
                if (out == LeaseOutcome.Done(0L, 0L)) extended++ else refusals += "extend at $leased: $out"
            } else {
                h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(40).toLong())
                completed++
            }
        }
        val l = h.snapshot().ledger
        val held = groupsOf(l).sumOf { l.holdings(it, LOCAL) }
        assertAll(
            { assertEquals(emptyList(), refusals) },
            { assertEquals(LEASES, leased) },
            { assertTrue(extended > 1_000, "the rig extended: $extended") },
            { assertTrue(completed > 5_000, "the rig completed: $completed") },
            { assertEquals(setOf(MintId("local#0")), l.mintIds()) },
            { assertEquals(l.mintedTotal(), held + l.leafSpentTotal()) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    /**
     * Since #3197 an epoch keeps one mint record carrying its running total, so the record count
     * stays one however many top-ups run. A watermark sized by the capacity the caller really runs
     * leaves enough headroom that no overrun and no admission needs a mint of its own; the control
     * declares one slot for the same four-slot workload, and its overruns do.
     */
    @Test
    fun mintRecordsStayOnePerEpochAndACapacitySizedWatermarkForcesNoMint() {
        class Run(val forcedMints: Int, val topUps: Int, val mintIds: Set<MintId>, val validate: List<LedgerConflict>)

        fun run(declaredSlots: Long): Run {
            val h = engineOver(threeLeaves, root, supply = 0L, policy = policy(reservation = 10L), epoch = 4L)
            h.reconcileCapacity(declaredSlots)
            val leaves = setOf(a, b, c)
            var topUps = 0
            fun acquire() {
                val was = h.minted()
                h.lease(leaves, reservation = 10L)
                if (h.minted() > was) topUps++
            }
            repeat(4) { acquire() }
            repeat(CYCLES) { i ->
                // One slot frees: the oldest lease completes with an overrun of up to 25.
                h.complete(h.snapshot().leases.first().id, 10L + i % 26)
                if (i % 10 == 0) {
                    val admitted = assertNotNull((h.admit(a, startedAt = i.toLong(), reservation = 10L) as? AcquireOutcome.Acquired)?.lease)
                    h.complete(admitted.id, 10L)
                }
                acquire()
            }
            val l = h.snapshot().ledger
            return Run(h.forcedMints, topUps, l.mintIds(), l.validate())
        }

        val sized = run(declaredSlots = 4L)
        val undersized = run(declaredSlots = 1L)
        assertAll(
            { assertTrue(sized.topUps > CYCLES / 4, "the rig: many top-ups ran, ${sized.topUps}") },
            { assertEquals(setOf(MintId("local#4")), sized.mintIds, "one record for the epoch") },
            { assertEquals(0, sized.forcedMints, "no overrun or admission forced a mint") },
            { assertEquals(emptyList(), sized.validate) },
            { assertTrue(undersized.forcedMints > 0, "the control: one declared slot leaves too little headroom") },
            { assertEquals(setOf(MintId("local#4")), undersized.mintIds, "and its forced mints write the same record") },
        )
    }

    @Test
    fun capacityAndSupplyPolicyRejectBadValues() {
        val h = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = LocalHeddle.MAX_RESERVATION))
        assertAll(
            { assertFailsWith<IllegalArgumentException> { h.reconcileCapacity(-1L) } },
            // 2^44 × 2 rounds × 2^16 slots = 2^61, one above Long.MAX_VALUE / 4.
            { assertFailsWith<IllegalArgumentException> { h.reconcileCapacity(1L shl 16) } },
            { h.reconcileCapacity((1L shl 16) - 1L) },
            { assertFailsWith<IllegalArgumentException> { h.reconcileCapacity(Long.MAX_VALUE) } },
            { assertFailsWith<IllegalArgumentException> { SupplyPolicy(lowWatermarkSlots = -1L, targetSlots = 1L) } },
            { assertFailsWith<IllegalArgumentException> { SupplyPolicy(lowWatermarkSlots = 3L, targetSlots = 2L) } },
            { assertEquals(SupplyPolicy(0L, 0L), SupplyPolicy(lowWatermarkSlots = 0L, targetSlots = 0L)) },
            {
                // A zero target sizes nothing, so no slot count can overflow it.
                val justInTime = engineOver(oneLeaf, root, supply = 0L, policy = policy(reservation = LocalHeddle.MAX_RESERVATION, low = 0L, target = 0L))
                justInTime.reconcileCapacity(Long.MAX_VALUE)
                justInTime.lease(setOf(a), reservation = 7L)
                assertEquals(7L, justInTime.minted())
            },
        )
    }

    @Test
    fun reconcileLeasesReportsBothDirections() {
        val h = engineOver(oneLeaf, root, supply = 100L)
        val leases = (0 until 4).map { h.lease(setOf(a), startedAt = it * 10L) }
        val strangers = listOf(LeaseId(7L, 0L), LeaseId(0L, 99L))
        val before = h.snapshot()
        val found = h.reconcileLeases(setOf(leases[1].id, leases[3].id) + strangers, now = 50L)
        val agreeing = h.reconcileLeases(leases.map { it.id }.toSet(), now = 50L)
        assertAll(
            { assertEquals(listOf(leases[0], leases[2]), found.orphaned.map { it.lease }, "open leases nobody named, in id order") },
            { assertEquals(listOf(LeaseId(0L, 99L), LeaseId(7L, 0L)), found.unknown, "named ids with no open lease, in id order") },
            { assertEquals(LeaseReconciliation(emptyList(), emptyList()), agreeing) },
            { assertEquals(before, h.snapshot(), "it changed nothing") },
        )
    }

    @Test
    fun orphanElapsedIsNowMinusStart() {
        val h = engineOver(oneLeaf, root, supply = 100L)
        val early = h.lease(setOf(a), reservation = 3L, startedAt = 100L)
        val late = h.lease(setOf(a), reservation = 3L, startedAt = 250L)
        h.extend(late.id, 4L)
        val orphans = h.reconcileLeases(emptySet(), now = 400L).orphaned
        assertAll(
            { assertEquals(listOf(300L, 150L), orphans.map { it.elapsed }) },
            { assertEquals(listOf(3L, 7L), orphans.map { it.granted }, "granted counts the extension") },
        )
    }

    @Test
    fun orphanElapsedClampsAtZeroWhenTheClockGoesBack() {
        val h = engineOver(oneLeaf, root, supply = 100L)
        h.lease(setOf(a), startedAt = 500L)
        h.lease(setOf(a), startedAt = 200L)
        h.lease(setOf(a), startedAt = Long.MAX_VALUE)
        val orphans = h.reconcileLeases(emptySet(), now = 200L).orphaned
        assertAll(
            { assertEquals(listOf(0L, 0L, 0L), orphans.map { it.elapsed }, "behind, level, and far behind without overflow") },
            { assertEquals(listOf(0L, 0L, 0L), h.reconcileLeases(emptySet(), now = Long.MIN_VALUE).orphaned.map { it.elapsed }) },
            { assertFailsWith<IllegalArgumentException> { LeaseReconciliation.Orphan(orphans.first().lease, elapsed = -1L) } },
        )
    }

    /** Forward, an elapsed time that leaves `Long` throws rather than wrapping to a small one. */
    @Test
    fun orphanElapsedThatOverflowsThrows() {
        val h = engineOver(oneLeaf, root, supply = 100L)
        h.lease(setOf(a), startedAt = Long.MIN_VALUE)
        assertFailsWith<ArithmeticException> { h.reconcileLeases(emptySet(), now = 1L) }
    }

    /** The documented settlement charges the time an orphan ran, capped at what it was granted. */
    @Test
    fun defaultSettlementCapsAtGranted() {
        val h = engineOver(oneLeaf, root, supply = 100L)
        val long = h.lease(setOf(a), reservation = 10L, startedAt = 0L)
        val short = h.lease(setOf(a), reservation = 10L, startedAt = 47L)
        val (overran, under) = h.reconcileLeases(emptySet(), now = 50L).orphaned
        val doneLong = h.complete(long.id, overran.defaultSettlement)
        val doneShort = h.complete(short.id, under.defaultSettlement)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(50L to 10L, overran.elapsed to overran.granted, "the rig: it ran past its grant") },
            { assertEquals(10L, overran.defaultSettlement) },
            { assertEquals(3L, under.defaultSettlement) },
            { assertEquals(LeaseOutcome.Done(charged = 10L, overrun = 0L), doneLong) },
            { assertEquals(LeaseOutcome.Done(charged = 3L, overrun = 0L), doneShort) },
            { assertEquals(13L, l.leafSpentTotal()) },
            { assertEquals(87L, l.holdings(root, LOCAL), "the short orphan's 7 unused units went back") },
        )
    }

    /** A caller with evidence settles an orphan at its whole elapsed cost, overrun and all. */
    @Test
    fun orphanCompletedAtElapsedCostCharges() {
        val h = engineOver(oneLeaf, root, supply = 100L)
        val lease = h.lease(setOf(a), reservation = 10L, startedAt = 20L)
        val orphan = h.reconcileLeases(emptySet(), now = 45L).orphaned.single()
        val done = h.complete(orphan.lease.id, orphan.elapsed)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(lease, orphan.lease) },
            { assertEquals(LeaseOutcome.Done(charged = 25L, overrun = 15L), done) },
            { assertEquals(25L, assertNotNull(l.edge(AttachmentId("root>a/b"))).spent) },
            { assertEquals(LeaseReconciliation(emptyList(), emptyList()), h.reconcileLeases(emptySet(), now = 45L), "settled") },
            { assertEquals(l.mintedTotal(), l.holdings(root, LOCAL) + l.leafSpentTotal()) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    private companion object {
        const val LEASES = 10_000
        const val CYCLES = 2_000
    }
}
