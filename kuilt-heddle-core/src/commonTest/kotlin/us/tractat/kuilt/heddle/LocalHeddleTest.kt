package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * `root → t → a` with `t → a` closing, beside `root → b`. With the demand lift matching what the
     * descent can stop on, a failure is decided at the root before anything is delegated: there is no
     * failure below it left to make all-or-nothing (`LocalHeddlePropertyTest.reshapesNeverStarveASiblingNorThrow` pins that every
     * `NoLiveLineage` from `acquire` is at the root and changes nothing). What remains to show is that
     * the dead end costs nothing: `a` alone is refused with the ledger untouched, and with `b` waiting
     * too, `b` is leased every time.
     */
    @Test
    fun acquireIsAllOrNothing() {
        val (h, t) = deadEndBesideB()
        val before = h.snapshot().ledger
        val alone = h.acquire(setOf(a, b), setOf(a), startedAt = 0L)
        val after = h.snapshot()
        val beside = (0 until 5).map { h.lease(setOf(a, b)).leaf }
        assertAll(
            { assertEquals(Lifecycle.CLOSING, before.lifecycle(AttachmentId("${t.value}>a")), "the rig: t → a is closing") },
            { assertEquals(3L, before.holdings(a, LOCAL), "the rig: a still holds what reached it before the close") },
            { assertEquals(AcquireOutcome.NoLiveLineage(root), alone, "a alone has no lineage a pick could end at") },
            { assertSame(before, after.ledger, "and nothing was delegated") },
            { assertEquals(emptyList(), after.leases) },
            { assertEquals(listOf(b, b, b, b, b), beside, "b is leased every time a is waiting beside it") },
        )
    }

    /**
     * The point case of the starvation the demand lift fixes: before, demand at the root counted `t`
     * as wanted (its leaf's lineage is live, through the closing edge), so once `t` was behind `b`
     * every pick went down `t`, found nothing it could delegate to, and was discarded, so `t`'s virtual
     * time never moved and `b` was never picked again.
     */
    @Test
    fun aDeadEndSubtreeNeverStarvesItsSibling() {
        val (h, _) = deadEndBesideB()
        val outs = (0 until 20).map {
            val out = h.acquire(setOf(a, b), setOf(a, b), startedAt = it.toLong())
            if (out is AcquireOutcome.Acquired) h.complete(out.lease.id, 1L)
            out
        }
        assertEquals(List(20) { b }, outs.map { (it as? AcquireOutcome.Acquired)?.lease?.leaf ?: GroupId("$it") })
    }

    /**
     * The second way a subtree had no terminal: the named group is eligible but is no longer a leaf,
     * because a child edge under it activated. Nothing under it is eligible, so a pick down it found
     * nothing, and the sibling starved exactly as behind a closing edge.
     */
    @Test
    fun anEligibleGroupThatGainedAChildIsNotWanted() {
        val ax = edgeOf(a, GroupId("a/x"))
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a), edgeOf(root, b), ax)), root, supply = 100)
        val outs = (0 until 20).map {
            val out = h.acquire(setOf(a, b), setOf(a, b), startedAt = it.toLong())
            if (out is AcquireOutcome.Acquired) h.complete(out.lease.id, 1L)
            out
        }
        assertAll(
            { assertEquals(false, h.snapshot().ledger.isLeaf(a), "the rig: a has an active child") },
            { assertEquals(List(20) { b }, outs.map { (it as? AcquireOutcome.Acquired)?.lease?.leaf ?: GroupId("$it") }) },
        )
    }

    /** `root → t → a` with `t → a` closing and 3 units still at `a`, beside `root → b`; supply 100. */
    private fun deadEndBesideB(): Pair<LocalHeddle, GroupId> {
        val t = GroupId("t")
        val ta = edgeOf(t, a)
        var shaped = shapedLedger(root, listOf(edgeOf(root, t), ta, edgeOf(root, b)))
        shaped = shaped.piece(shaped.mint(MintId("old"), root, LOCAL, 3L).delta)
        shaped = assertNotNull(delegateDown(shaped, a, 3L, LOCAL))
        shaped = shaped.piece(assertNotNull(shaped.close(ta.id)).delta)
        return engineOver(shaped, root, supply = 100) to t
    }

    /**
     * The descent ends only at an eligible group (#3193 review). `pool` declares runtime children and
     * nothing else. `pool/!c` is attached and leased, then detached while its lease is open: the
     * retire is refused, so `pool → c` stays CLOSING with `root → pool` ACTIVE, and the ledger now
     * reads `pool` as a leaf. `pool` is not eligible, so it is not leased, and the runtime child
     * behind the closing edge is not reachable: the sibling `s` is leased instead. The detach is
     * applied through the test seam, since L1 has no `apply`.
     */
    @Test
    fun theDescentEndsOnlyAtAnEligibleGroup() {
        val pool = GroupId("pool")
        val runtime = GroupId("pool/!c")
        val s = GroupId("s")
        val poolC = edgeOf(pool, runtime)
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, pool), poolC, edgeOf(root, s))), root, supply = 100)
        val open = h.lease(setOf(runtime), reservation = 2L)
        h.reshapeForTest { it.piece(assertNotNull(it.close(poolC.id)).delta) }
        val before = h.snapshot().ledger
        val alone = h.acquire(setOf(runtime), setOf(runtime), startedAt = 1L)
        val unchanged = h.snapshot().ledger
        val beside = (0 until 4).map { h.lease(setOf(runtime, s)).leaf }
        assertAll(
            { assertEquals(runtime, open.leaf, "the rig: the runtime child was leased") },
            { assertEquals(null, before.retire(poolC.id), "the rig: its retire is refused while the lease is open") },
            { assertTrue(before.activeChildren(pool).isEmpty(), "the rig: pool has no active child, so the ledger reads it as a leaf") },
            { assertEquals(AcquireOutcome.NoLiveLineage(root), alone, "neither pool nor its runtime child is a place the descent can end") },
            { assertSame(before, unchanged) },
            { assertEquals(listOf(s, s, s, s), beside, "the sibling is leased instead") },
            { assertEquals(LeaseOutcome.Done(2L, 0L), h.complete(open.id, 2L), "the open lease still settles") },
        )
    }

    /** `root → pool → c` with a 2-unit lease open at `c`, then `pool → c` closed under it; supply 100. */
    private fun leaseBehindAClosedEdge(supply: Long = 100L): Triple<LocalHeddle, Lease, AttachmentRecord> {
        val pool = GroupId("pool")
        val poolC = edgeOf(pool, c)
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, pool), poolC)), root, supply = supply)
        val open = h.lease(setOf(c), reservation = 2L)
        h.reshapeForTest { it.piece(assertNotNull(it.close(poolC.id)).delta) }
        return Triple(h, open, poolC)
    }

    @Test
    fun anExtendAcrossAClosingEdgeIsDrainingAndChangesNothing() {
        val (h, open, poolC) = leaseBehindAClosedEdge()
        val before = h.snapshot()
        val out = h.extend(open.id, 1L)
        assertAll(
            { assertEquals(LeaseOutcome.PathDraining(poolC.id), out) },
            { assertSame(before.ledger, h.snapshot().ledger, "the ledger is the very same value") },
            { assertEquals(before.leases, h.snapshot().leases, "the lease kept its reservation") },
            { assertEquals(LeaseOutcome.Done(2L, 0L), h.complete(open.id, 2L), "and still settles") },
        )
    }

    /**
     * An overrun on a lease whose path has a closing edge cannot be delegated down to the leaf. The
     * 2 reserved units are charged at `c` as usual; the 3-unit overrun is delegated to `pool`, the
     * deepest group ACTIVE edges still reach, and charged there.
     */
    @Test
    fun anOverrunAcrossAClosingEdgeIsChargedAboveIt() {
        val (h, open, poolC) = leaseBehindAClosedEdge()
        val done = h.complete(open.id, 5L)
        val l = h.snapshot().ledger
        val pool = poolC.parent
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 5L, overrun = 3L), done) },
            { assertEquals(emptyList(), h.snapshot().leases, "the lease is closed") },
            { assertEquals(2L, assertNotNull(l.edge(poolC.id)).spent, "the reservation is charged at the leaf") },
            { assertEquals(5L, assertNotNull(l.edge(AttachmentId("root>pool"))).spent, "the whole cost reaches the ACTIVE edge") },
            { assertEquals(Rational.of(5L), l.virtualService(AttachmentId("root>pool")), "whose virtual time advanced by all of it") },
            { assertEquals(0L, l.holdings(pool, LOCAL)) },
            { assertEquals(0L, l.holdings(c, LOCAL)) },
            { assertEquals(95L, l.holdings(root, LOCAL)) },
            { assertEquals(l.mintedTotal(), l.holdings(root, LOCAL) + l.leafSpentTotal(), "minted == Σ holdings + spent") },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    /** As above with the root short: the overrun's shortfall is minted at the root, once, and conservation holds. */
    @Test
    fun anOverrunAcrossAClosingEdgeMintsTheRootsShortfall() {
        val (h, open, _) = leaseBehindAClosedEdge(supply = 2L)
        val done = h.complete(open.id, 5L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 5L, overrun = 3L), done) },
            { assertEquals(1, h.forcedMints) },
            { assertEquals(5L, l.mintedTotal()) },
            { assertEquals(5L, l.leafSpentTotal()) },
            { assertEquals(0L, l.holdings(root, LOCAL)) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    /**
     * "The deepest group ACTIVE edges reach" on a three-edge path: `root → p → a → a/x` with
     * `a → a/x` closing. The overrun is delegated to `a` and charged there, so `p → a`'s virtual time
     * advances by all of it; charging it at `p` instead would conserve just as well.
     */
    @Test
    fun anOverrunIsChargedAtTheDeepestGroupActiveEdgesReach() {
        val p = GroupId("p")
        val x = GroupId("a/x")
        val pa = edgeOf(p, a)
        val ax = edgeOf(a, x)
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, p), pa, ax)), root, supply = 100)
        val open = h.lease(setOf(x), reservation = 2L)
        h.reshapeForTest { it.piece(assertNotNull(it.close(ax.id)).delta) }
        val before = h.snapshot().ledger
        val done = h.complete(open.id, 5L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 5L, overrun = 3L), done) },
            { assertEquals(Rational.of(2L), before.virtualService(pa.id), "the rig: p → a carried the reservation") },
            { assertEquals(Rational.of(5L), l.virtualService(pa.id), "p → a advanced by the 3-unit overrun") },
            { assertEquals(5L, assertNotNull(l.edge(pa.id)).spent, "the whole cost is charged at p → a") },
            { assertEquals(2L, assertNotNull(l.edge(ax.id)).spent, "only the reservation crosses the closing edge") },
            { assertEquals(0L, l.holdings(a, LOCAL)) },
            { assertEquals(0L, l.holdings(p, LOCAL)) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    /**
     * Work no descent can reach is idle. `t`'s only leaf is behind a closing edge while it is still
     * listed as waiting, so `t` is observed idle and banks nothing while `p` runs; when `t → a2`
     * activates, `t` is clamped as a waker and shares level with `p` rather than catching up.
     */
    @Test
    fun workNoDescentCanReachIsObservedIdle() {
        val p = GroupId("p")
        val t = GroupId("t")
        val a2 = GroupId("a2")
        val ta = edgeOf(t, a)
        val ta2 = edgeOf(t, a2)
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, p), edgeOf(root, t), ta), prepared = listOf(ta2)), root, supply = 1_000)
        val open = h.lease(setOf(a), reservation = 1L)
        h.reshapeForTest { it.piece(assertNotNull(it.close(ta.id)).delta) }
        val cutOff = cycles(h, setOf(p, a), setOf(p, a), 30)
        val observed = h.snapshot().allocatorMemory.demandingObserved[AttachmentId("root>t")]
        h.reshapeForTest { it.piece(assertNotNull(it.activate(ta2.id)).delta) }
        val after = cycles(h, setOf(p, a2), setOf(p, a2), 20)
        assertAll(
            { assertEquals(List(30) { p }, cutOff, "the rig: a is waiting but unreachable, so p runs alone") },
            { assertEquals(false, observed, "t was observed idle, though a was listed as waiting") },
            { assertTrue(count(after, a2) in 9..11, "t woke level with p, not 30 units behind it: ${count(after, a2)} of 20") },
            { assertEquals(LeaseOutcome.Done(1L, 0L), h.complete(open.id, 1L), "the cut-off lease still settles") },
        )
    }

    /**
     * Each epoch keeps one mint record, `local#<epoch>`, carrying its running total, however many
     * top-ups and forced mints it writes. A same-epoch engine over the ledger carries the total on.
     */
    @Test
    fun anEpochKeepsOneMintRecord() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 0L, epoch = 2L)
        repeat(20) {
            h.mintSupply(2L) // a top-up the lease then takes in full
            val lease = h.lease(setOf(a), reservation = 2L)
            h.complete(lease.id, 3L) // a 1-unit overrun with the root empty: a forced mint
        }
        val admitted = assertNotNull((h.admit(a, 0L, reservation = 4L) as? AcquireOutcome.Acquired)?.lease)
        h.complete(admitted.id, 4L)
        repeat(30) { h.mintSupply(1L) }
        val one = h.snapshot().ledger
        val same = engineOver(one, root, supply = 5L, epoch = 2L).snapshot().ledger
        val next = engineOver(one, root, supply = 7L, epoch = 3L).snapshot().ledger
        assertAll(
            { assertEquals(21, h.forcedMints, "the rig: every overrun and the admit forced a mint") },
            { assertEquals(20L * 3L + 4L + 30L, one.mintedTotal(), "70 mint acts' worth of supply") },
            { assertEquals(setOf(MintId("local#2")), one.mintIds(), "one record for the whole epoch") },
            { assertEquals(one.leafSpentTotal(), one.mintedTotal() - one.holdings(root, LOCAL), "and it carries every unit") },
            { assertEquals(setOf(MintId("local#2")), same.mintIds()) },
            { assertEquals(one.mintedTotal() + 5L, same.mintedTotal(), "a same-epoch engine carries the total on") },
            { assertEquals(setOf(MintId("local#2"), MintId("local#3")), next.mintIds()) },
            { assertEquals(one.mintedTotal() + 7L, next.mintedTotal()) },
            { assertEquals(emptyList(), next.validate()) },
        )
    }

    /**
     * The one place the overrun has nowhere to go: the root's own child edge is closing, so no group
     * below the root is reached through ACTIVE edges, and the ledger keeps no spend at the root. The
     * lease still closes, the reservation is charged at the leaf, conservation holds and nothing is
     * minted; the overrun is reported but recorded on no edge.
     */
    @Test
    fun anOverrunBelowAClosingRootEdgeIsReportedButRecordedNowhere() {
        val rootC = edgeOf(root, c)
        val h = engineOver(shapedLedger(root, listOf(rootC)), root, supply = 100)
        val open = h.lease(setOf(c), reservation = 2L)
        h.reshapeForTest { it.piece(assertNotNull(it.close(rootC.id)).delta) }
        val done = h.complete(open.id, 5L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 5L, overrun = 3L), done) },
            { assertEquals(emptyList(), h.snapshot().leases) },
            { assertEquals(2L, l.leafSpentTotal(), "only the reservation reached an edge") },
            { assertEquals(0, h.forcedMints) },
            { assertEquals(100L, l.mintedTotal()) },
            { assertEquals(l.mintedTotal(), l.holdings(root, LOCAL) + l.leafSpentTotal()) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    /**
     * Reservations up to [LocalHeddle.MAX_RESERVATION] lease, and two of them under one edge still
     * read as wanted: the old target of 2^40 refused the first above it, and made an edge with 2^40
     * outstanding read as satisfied.
     */
    @Test
    fun aReservationAtTheBoundLeasesAndOneAboveItIsRejected() {
        val max = LocalHeddle.MAX_RESERVATION
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 4 * max)
        val first = h.lease(setOf(a), reservation = max)
        val second = h.lease(setOf(a), reservation = max)
        val extended = h.extend(first.id, max)
        assertAll(
            { assertEquals(1L shl 44, max, "the documented bound") },
            { assertEquals(listOf(a, a), listOf(first.leaf, second.leaf)) },
            { assertEquals(LeaseOutcome.Done(0L, 0L), extended) },
            { assertEquals(3 * max, assertNotNull(h.snapshot().ledger.edge(AttachmentId("root>a"))).outstanding, "3 × 2^44 outstanding on one edge") },
            { assertFailsWith<IllegalArgumentException> { h.acquire(setOf(a), setOf(a), 0L, reservation = max + 1) } },
            { assertFailsWith<IllegalArgumentException> { h.admit(a, 0L, reservation = max + 1) } },
            { assertFailsWith<IllegalArgumentException> { h.extend(second.id, max + 1) } },
            { assertFailsWith<IllegalArgumentException> { LocalPolicy(defaultReservation = max + 1) } },
            { assertEquals(LeaseOutcome.Done(2 * max, 0L), h.complete(first.id, 2 * max)) },
        )
    }

    /**
     * Mint ids are scoped by the epoch. An engine restarted over another's ledger counts its mints
     * from zero again; under an unscoped `local#<k>` its first mint max-joined with the old engine's
     * and the smaller of the two was lost.
     */
    @Test
    fun aRestartedEngineDoesNotReuseAMintId() {
        val ledger = shapedLedger(root, listOf(edgeOf(root, a)))
        val first = engineOver(ledger, root, supply = 7L, epoch = 0L).snapshot().ledger
        val restarted = engineOver(first, root, supply = 5L, epoch = 1L).snapshot().ledger
        assertAll(
            { assertEquals(12L, restarted.mintedTotal(), "both mints are kept") },
            { assertEquals(12L, restarted.holdings(root, LOCAL)) },
        )
    }

    /**
     * The engine's own forced mint: an overrun `complete` with the root short mints exactly the
     * shortfall, once. The fairness harness funds every overrun itself, so this is where the engine's
     * supply path is pinned.
     */
    @Test
    fun anOverrunWithTheRootShortForcesOneMint() {
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 2L)
        val lease = h.lease(setOf(a), reservation = 2L)
        val short = h.snapshot().ledger.holdings(root, LOCAL)
        val done = h.complete(lease.id, 5L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(0L, short, "the rig: the root holds nothing when the overrun arrives") },
            { assertEquals(LeaseOutcome.Done(5L, 3L), done) },
            { assertEquals(1, h.forcedMints) },
            { assertEquals(5L, l.mintedTotal(), "2 minted up front plus the 3-unit shortfall") },
            { assertEquals(5L, assertNotNull(l.edge(AttachmentId("root>a"))).spent) },
            { assertEquals(0L, l.holdings(root, LOCAL)) },
            { assertEquals(0L, l.holdings(a, LOCAL)) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    /**
     * A lane that gains a child while a lease is open at it keeps that lease's reservation: the
     * next pick hands the new child exactly its own reservation, never the open one. Each level
     * of a descent receives exactly the reservation and grants at most `maximumUsefulGrant`, which
     * is the reservation, so this holds with no earmark. A re-home that moves units between paths
     * (L3) does not have that cap, and is where an earmark would be needed.
     */
    @Test
    fun aLaneThatGainsAChildKeepsItsOpenReservation() {
        val x = GroupId("a/x")
        val ax = edgeOf(a, x)
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a)), prepared = listOf(ax)), root, supply = 100)
        val open = h.lease(setOf(a), reservation = 5L)
        h.reshapeForTest { it.piece(assertNotNull(it.activate(ax.id)).delta) }
        val child = h.lease(setOf(x), reservation = 2L)
        val mid = h.snapshot().ledger
        val done = h.complete(open.id, 5L)
        val after = h.snapshot().ledger
        assertAll(
            { assertEquals(x, child.leaf) },
            { assertEquals(2L, mid.holdings(x, LOCAL), "the new child got its own reservation") },
            { assertEquals(5L, mid.holdings(a, LOCAL), "the open lease's 5 units stayed at the former lane") },
            { assertEquals(LeaseOutcome.Done(5L, 0L), done) },
            { assertEquals(0L, after.holdings(a, LOCAL)) },
            { assertEquals(emptyList(), after.validate()) },
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
