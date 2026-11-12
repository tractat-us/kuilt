package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [LocalHeddle]'s lease model, one property per test (#3173, plan Task L1). The fairness evidence
 * carried over from Task C's prototype is `LocalHeddleFairnessTest`; random sequences are
 * `LocalHeddlePropertyTest`.
 */
class LocalHeddleTest {

    private val root = GroupId("root")
    private val a = GroupId("a")
    private val b = GroupId("b")
    private val c = GroupId("c")

    private fun count(picks: List<GroupId>, leaf: GroupId): Int = picks.count { it == leaf }

    /** Acquire and immediately complete at the reservation, [n] times; the leaves picked. */
    private fun cycles(h: LocalHeddle, waiting: Set<GroupId>, eligible: Set<GroupId>, n: Int, reservation: Long = 1L): List<GroupId> =
        (0 until n).map {
            val out = h.acquire(waiting, eligible, startedAt = it.toLong(), reservation = reservation)
            val lease = (out as? AcquireOutcome.Acquired)?.lease ?: error("cycle $it: $out")
            assertEquals(LeaseOutcome.Done(reservation, 0L), h.complete(lease.id, reservation))
            lease.leaf
        }

    @Test
    fun weightedLeasesFollowTheWeights() {
        val x = GroupId("c-7f3a")
        val y = GroupId("é#%@")
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, x, 3), edgeOf(root, y, 1))), root, supply = 1_000)
        val picks = cycles(h, setOf(x, y), setOf(x, y), 400)
        assertAll(
            { assertEquals(400, picks.size, "every cycle ran") },
            { assertTrue(count(picks, x) in 299..301, "x (weight 3): ${count(picks, x)}") },
            { assertTrue(count(picks, y) in 99..101, "y (weight 1): ${count(picks, y)}") },
        )
    }

    @Test
    fun twoLevelTreeWithConcurrentLeases() {
        val p = GroupId("p")
        val q = GroupId("q")
        val ledger = shapedLedger(root, listOf(edgeOf(root, p), edgeOf(root, q), edgeOf(p, a), edgeOf(p, b), edgeOf(q, c)))
        val ideal = mapOf(a to 0.25, b to 0.25, c to 0.5)
        for (k in listOf(1, 4, 16)) {
            val h = engineOver(ledger, root, supply = 100_000)
            val charged = HashMap<GroupId, Long>()
            var completes = 0
            var maxOpen = 0
            while (completes < 2_000) {
                val open = h.snapshot().leases
                maxOpen = maxOf(maxOpen, open.size)
                if (open.size < k) {
                    h.lease(setOf(a, b, c), reservation = 2L)
                } else {
                    val oldest = open.first()
                    h.complete(oldest.id, 2L)
                    charged[oldest.leaf] = (charged[oldest.leaf] ?: 0L) + 2L
                    completes++
                }
            }
            val total = charged.values.sum().toDouble()
            val worst = ideal.entries.maxOf { (leaf, share) -> kotlin.math.abs((charged[leaf] ?: 0L) / total - share) }
            assertAll(
                { assertEquals(k, maxOpen, "k=$k leases were in flight at once") },
                { assertTrue(worst <= 0.03, "k=$k: worst share error $worst, charged $charged") },
            )
        }
    }

    @Test
    fun intermittentWaitingSetsDoNotGrantIdleCredit() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a), edgeOf(root, b))), root, supply = 1_000)
        cycles(h, setOf(a), setOf(a), 50)
        val observed = h.snapshot().allocatorMemory.demandingObserved[AttachmentId("root>b")]
        val after = cycles(h, setOf(a, b), setOf(a, b), 20)
        assertAll(
            { assertEquals(false, observed, "the rig: b was observed idle while a ran alone") },
            { assertTrue(count(after, b) in 9..11, "b wakes level with a, not 50 units behind it: ${count(after, b)} of 20") },
        )
    }

    @Test
    fun filteredEligibleSetsStillFeedWakeDetection() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a), edgeOf(root, b))), root, supply = 1_000)
        // b is waiting throughout, but these 50 slots can only run a.
        cycles(h, setOf(a, b), setOf(a), 50)
        val observed = h.snapshot().allocatorMemory.demandingObserved[AttachmentId("root>b")]
        val after = cycles(h, setOf(a, b), setOf(a, b), 20)
        assertAll(
            { assertEquals(true, observed, "b was observed waiting while it could not run") },
            { assertEquals(20, count(after, b), "b kept its claim, so it catches up rather than being clamped: $after") },
        )
    }

    @Test
    fun differentialAgainstSchedule() {
        val x = GroupId("x")
        val y = GroupId("y")
        val z = GroupId("z")
        val ledger = shapedLedger(root, listOf(edgeOf(root, x, 3), edgeOf(root, y, 1), edgeOf(root, z, 2)))
        val reservation = 2L
        val n = 60

        val h = engineOver(ledger, root, supply = n * reservation)
        val leased = (0 until n).map { h.lease(setOf(x, y, z), reservation).leaf }

        // The same ledger and supply, through `schedule` with quantum = reservation, recording which child each grant lands on.
        val funded = ledger.piece(ledger.mint(MintId("local#0"), root, LOCAL, n * reservation).delta)
        val granted = ArrayList<GroupId>()
        val cell = object : LedgerCell {
            override var current: EntitlementLedger = funded
            override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
                val patch = transform(current) ?: return false
                patch.delta.issuedEdges().singleOrNull()?.let { granted += checkNotNull(current.record(it)).child }
                current = current.piece(patch.delta)
                return true
            }
        }
        val wants = Demand(targetOutstanding = 1L shl 40, maximumUsefulGrant = reservation)
        FairShareAllocator(LOCAL, PolicyConfig(quantum = reservation)).schedule(root, cell, earmarked = 0L) { wants }

        assertAll(
            { assertEquals(n, granted.size, "schedule made one grant per reservation's worth of supply") },
            { assertEquals(granted, leased, "the acquires pick schedule's sequence") },
        )
    }

    @Test
    fun overrunCompleteExtendsAndCharges() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 100)
        val lease = h.lease(setOf(a), reservation = 2L)
        val done = h.complete(lease.id, 5L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 5L, overrun = 3L), done) },
            { assertEquals(5L, assertNotNull(l.edge(AttachmentId("root>a"))).spent, "the whole actual cost is charged") },
            { assertEquals(0L, l.holdings(a, LOCAL), "nothing is left at the leaf") },
            { assertEquals(95L, l.holdings(root, LOCAL), "the root paid the overrun too") },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    @Test
    fun unusedReservationIsReturned() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 100)
        val lease = h.lease(setOf(a), reservation = 5L)
        val done = h.complete(lease.id, 2L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 2L, overrun = 0L), done) },
            { assertEquals(98L, l.holdings(root, LOCAL), "the unused 3 went back to the root") },
            { assertEquals(0L, l.holdings(a, LOCAL)) },
            { assertEquals(emptyList(), h.snapshot().leases) },
        )
    }

    @Test
    fun extendDelegatesMoreDownTheSamePath() {
        val t = GroupId("t")
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, t), edgeOf(t, a))), root, supply = 100)
        val lease = h.lease(setOf(a), reservation = 2L)
        val extended = h.extend(lease.id, 3L)
        val mid = h.snapshot()
        val done = h.complete(lease.id, 4L)
        assertAll(
            { assertEquals(LeaseOutcome.Done(0L, 0L), extended) },
            { assertEquals(5L, mid.leases.single().reserved, "the lease now holds 2 + 3") },
            { assertEquals(5L, mid.ledger.holdings(a, LOCAL), "all of it sits at the leaf") },
            { assertEquals(LeaseOutcome.Done(4L, 0L), done) },
            { assertEquals(96L, h.snapshot().ledger.holdings(root, LOCAL)) },
        )
    }

    @Test
    fun cancelReturnsEverything() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 100)
        val lease = h.lease(setOf(a), reservation = 4L)
        val done = h.cancel(lease.id)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(LeaseOutcome.Done(0L, 0L), done) },
            { assertEquals(100L, l.holdings(root, LOCAL)) },
            { assertEquals(0L, l.leafSpentTotal()) },
            { assertEquals(emptyList(), h.snapshot().leases) },
        )
    }

    @Test
    fun staleAndUnknownLeasesAreNamed() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 100, epoch = 3L)
        val lease = h.lease(setOf(a))
        h.complete(lease.id, 1L)
        val settled = h.snapshot().ledger
        assertAll(
            { assertEquals(LeaseId(3L, 0L), lease.id, "ids carry the epoch") },
            { assertEquals(LeaseOutcome.Unknown, h.complete(lease.id, 1L), "a late complete of a settled lease") },
            { assertEquals(LeaseOutcome.Unknown, h.extend(LeaseId(3L, 99L), 1L)) },
            { assertEquals(LeaseOutcome.Stale(3L), h.cancel(LeaseId(2L, 0L)), "another epoch") },
            { assertEquals(LeaseOutcome.Stale(3L), h.complete(LeaseId(4L, 0L), 1L)) },
            { assertSame(settled, h.snapshot().ledger, "none of them changed anything") },
        )
    }

    @Test
    fun acquireIsAllOrNothing() {
        // root → t → a, with t→a closing: a's lineage is live but cannot be delegated down, so the
        // descent picks t at the root, delegates there, and only then finds nothing under t.
        val t = GroupId("t")
        val ta = edgeOf(t, a)
        var shaped = shapedLedger(root, listOf(edgeOf(root, t), ta))
        shaped = shaped.piece(shaped.mint(MintId("old"), root, LOCAL, 3L).delta)
        shaped = assertNotNull(delegateDown(shaped, a, 3L, LOCAL))
        shaped = shaped.piece(assertNotNull(shaped.close(ta.id)).delta)
        val h = engineOver(shaped, root, supply = 100)
        val before = h.snapshot().ledger
        val out = h.acquire(setOf(a), setOf(a), startedAt = 0L)
        assertAll(
            { assertEquals(AcquireOutcome.NoLiveLineage(t), out, "the descent got past the root and stopped at t") },
            { assertSame(before, h.snapshot().ledger, "the root's delegation was not kept") },
            { assertEquals(emptyList(), h.snapshot().leases) },
        )
    }

    /**
     * A config group whose only child is closing is never leased (#3193 review). The sequence it
     * stands for: `pool` declares runtime children and nothing else, `pool/!c` is attached and
     * leased, then detached while its lease is open, so its retire is refused and `pool → c` stays
     * CLOSING with `root → pool` ACTIVE. The ledger then counts `pool` as a leaf. The state is built
     * directly, since L1 has no `apply`.
     */
    @Test
    fun aGroupWhoseOnlyChildIsClosingIsNeverLeased() {
        val pool = GroupId("pool")
        val runtime = GroupId("pool/!c")
        val poolC = edgeOf(pool, runtime)
        var shaped = shapedLedger(root, listOf(edgeOf(root, pool), poolC))
        shaped = shaped.piece(shaped.mint(MintId("open-lease"), root, LOCAL, 2L).delta)
        shaped = assertNotNull(delegateDown(shaped, runtime, 2L, LOCAL))
        shaped = shaped.piece(assertNotNull(shaped.close(poolC.id)).delta)
        val h = engineOver(shaped, root, supply = 100)
        val before = h.snapshot().ledger
        val out = h.acquire(setOf(runtime), setOf(runtime), startedAt = 0L)
        assertAll(
            { assertTrue(before.activeChildren(pool).isEmpty(), "the rig: pool has no active child, so the ledger reads it as a leaf") },
            { assertEquals(AcquireOutcome.NoLiveLineage(pool), out, "the descent ends only at an eligible group") },
            { assertSame(before, h.snapshot().ledger) },
        )
    }

    @Test
    fun newbornLeafIsSeatedNotFavoured() {
        val rootB = edgeOf(root, b)
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a)), prepared = listOf(rootB)), root, supply = 1_000)
        cycles(h, setOf(a), setOf(a), 50)
        h.reshapeForTest { it.piece(assertNotNull(it.activate(rootB.id)).delta) }
        val front = assertNotNull(h.snapshot().ledger.virtualService(AttachmentId("root>a")))
        val after = cycles(h, setOf(a, b), setOf(a, b), 20)
        val served = count(after, b).toLong()
        assertAll(
            { assertEquals(Rational.of(50L), front, "the rig: a ran 50 units first") },
            // Seated at a's front, then advanced one unit per lease. Seated at its origin it would read just the leases.
            { assertEquals(Rational.of(50L + served), h.snapshot().ledger.virtualService(rootB.id), "b was seated at a's front") },
            { assertTrue(count(after, b) in 9..11, "b shares from the front rather than bursting: ${count(after, b)} of 20") },
        )
    }

    @Test
    fun exhaustedSupplyIsNamed() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 3)
        val lease = h.lease(setOf(a), reservation = 2L)
        val before = h.snapshot().ledger
        assertAll(
            { assertEquals(AcquireOutcome.SupplyExhausted, h.acquire(setOf(a), setOf(a), 0L, reservation = 2L)) },
            { assertEquals(LeaseOutcome.SupplyExhausted, h.extend(lease.id, 2L)) },
            { assertSame(before, h.snapshot().ledger, "neither changed the ledger") },
            { assertEquals(2L, h.snapshot().leases.single().reserved, "the refused extend left the lease alone") },
        )
    }

    @Test
    fun nothingEligibleIsNamed() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 3)
        val before = h.snapshot()
        assertAll(
            { assertEquals(AcquireOutcome.NothingEligible, h.acquire(setOf(a), emptySet(), 0L)) },
            { assertEquals(before, h.snapshot()) },
        )
    }

    @Test
    fun snapshotIsImmutable() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 10)
        val first = h.snapshot()
        val lease = h.lease(setOf(a), startedAt = 7L)
        val second = h.snapshot()
        h.complete(lease.id, 1L)
        assertAll(
            { assertEquals(emptyList(), first.leases, "the first snapshot never saw the lease") },
            { assertEquals(10L, first.ledger.holdings(root, LOCAL), "nor its delegation") },
            { assertEquals(listOf(lease), second.leases, "the second still holds it after the complete") },
            { assertEquals(7L, second.leases.single().startedAt, "with the start time the caller gave") },
            { assertNotEquals(second, h.snapshot()) },
        )
    }

    @Test
    fun admitSeatsANewbornBeforeDelegating() {
        val rootC = edgeOf(root, c)
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a)), prepared = listOf(rootC)), root, supply = 1_000)
        cycles(h, setOf(a), setOf(a), 50)
        h.reshapeForTest { it.piece(assertNotNull(it.activate(rootC.id)).delta) }
        val out = h.admit(c, startedAt = 11L, reservation = 2L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(AcquireOutcome.Acquired(Lease(LeaseId(0L, 50L), c, 2L, 11L)), out) },
            { assertEquals(null, h.snapshot().allocatorMemory.demandingObserved[rootC.id], "admit observed nothing") },
            // Seated at a's front of 50, then advanced by its own 2 units. Unseated it would read 2.
            { assertEquals(Rational.of(52L), l.virtualService(rootC.id), "c starts at the front, not at its origin") },
            { assertEquals(2L, l.holdings(c, LOCAL)) },
        )
    }

    @Test
    fun admitWithNoLiveLineageIsNamed() {
        val t = GroupId("t")
        val x = GroupId("x")
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, t), edgeOf(t, a)), prepared = listOf(edgeOf(root, x))), root, supply = 10)
        val before = h.snapshot().ledger
        assertAll(
            { assertEquals(AcquireOutcome.NoLiveLineage(root), h.admit(root, 0L), "the root is never a leaf") },
            { assertEquals(AcquireOutcome.NoLiveLineage(t), h.admit(t, 0L), "a group with an active child is not a leaf") },
            { assertEquals(AcquireOutcome.NoLiveLineage(x), h.admit(x, 0L), "a prepared edge is no lineage") },
            { assertEquals(AcquireOutcome.NoLiveLineage(GroupId("#3")), h.admit(GroupId("#3"), 0L), "an unknown name") },
            { assertSame(before, h.snapshot().ledger) },
        )
    }

    @Test
    fun admitIsNeverRefusedForSupply() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 0)
        val out = h.admit(a, startedAt = 0L, reservation = 3L)
        val lease = assertNotNull((out as? AcquireOutcome.Acquired)?.lease, "admitted: $out")
        val done = h.complete(lease.id, 3L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(1, h.forcedMints, "the shortfall was minted") },
            { assertEquals(LeaseOutcome.Done(3L, 0L), done) },
            { assertEquals(3L, l.mintedTotal()) },
            { assertEquals(3L, l.leafSpentTotal()) },
        )
    }

    @Test
    fun startedAtNeverAffectsAPick() {
        val ledger = shapedLedger(root, listOf(edgeOf(root, a, 3), edgeOf(root, b, 1), edgeOf(root, c, 2)))
        val starts = (0 until 300).map { it.toLong() * 7 }
        fun run(startedAt: List<Long>): List<GroupId> {
            val rng = Random(3173)
            val h = engineOver(ledger, root, supply = 100_000)
            val picks = ArrayList<GroupId>()
            for (step in startedAt.indices) {
                val open = h.snapshot().leases
                if (open.size < 4 && rng.nextBoolean()) {
                    val lease = h.lease(setOf(a, b, c), reservation = 1L + rng.nextInt(5), startedAt = startedAt[step])
                    assertEquals(startedAt[step], lease.startedAt)
                    picks += lease.leaf
                } else if (open.isNotEmpty()) {
                    h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(9).toLong())
                }
            }
            return picks
        }
        val inOrder = run(starts)
        val shuffled = run(starts.shuffled(Random(7)))
        assertAll(
            { assertTrue(inOrder.size > 100, "the rig acquired: ${inOrder.size}") },
            { assertEquals(inOrder, shuffled, "the same picks whatever the start times") },
        )
    }
}
