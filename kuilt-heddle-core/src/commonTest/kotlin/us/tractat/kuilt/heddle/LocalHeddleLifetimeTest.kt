package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The lifetime limit (design §7.1, #3475): what [LocalHeddle] does as an edge's effective issuance or
 * the root's minted supply nears `Long.MAX_VALUE`. A pick refuses at the watermark,
 * `Long.MAX_VALUE − LIFETIME_HEADROOM`, with [AcquireOutcome.LifetimeExhausted]. A charge uses the
 * headroom, and records what still does not fit nowhere, in [LeaseOutcome.Done.unrecorded]. No call
 * throws at either counter.
 *
 * Every counter is rigged rather than run up by picks ([pump], [spendAway]), since a run gets there
 * after about half a million maximum-size leases, and every row asserts the value it rigged before it
 * acts, so a rig that missed fails rather than passes. The one row that does run there,
 * [twoLanesAtTheLargestLeaseEndInTheTypedRefusal], is on the JVM only.
 */
class LocalHeddleLifetimeTest {

    private val topology = draft("life") {
        lane("a")
        lane("b")
        group("g") {
            lane("x")
            lane("y")
        }
    }
    private val a = topology.groupId("a")
    private val b = topology.groupId("b")
    private val g = topology.groupId("g")
    private val x = topology.groupId("g", "x")
    private val y = topology.groupId("g", "y")
    private val policy = LocalPolicy(defaultReservation = R)

    /**
     * A booted engine with [float] at the root and no capacity reconciled, so nothing tops up behind a
     * row's back, and every lane seated by one lease it cancels.
     */
    private fun engine(float: Long = 1_000L): LocalHeddle = LocalHeddle.bootWhole(topology, policy, epoch = 1L).apply {
        mintSupply(float)
        for (lane in listOf(a, b, x, y)) cancel(lease(setOf(lane), reservation = R).id)
    }

    private val root get() = topology.groupId()

    private fun LocalHeddle.path(lane: GroupId): List<AttachmentId> = checkNotNull(snapshot().ledger.lineageOf(lane)) { "${lane.value} has no lineage" }

    private fun LocalHeddle.issued(edge: AttachmentId): Long = snapshot().ledger.effectiveIssuance(edge)

    private fun LocalHeddle.minted(): Long = snapshot().ledger.mintedSupply(root, LOCAL)

    /**
     * Raise the effective issuance of [path]'s last edge to [to], leaving every holding as it was: a
     * chunk is delegated down [path] and handed straight back up its last edge until that edge reads
     * [to], then handed back up the rest. Every edge above gains one chunk of issuance, and the root's
     * minted supply at most one chunk, so a row can fill one edge alone. Counts reservations, as
     * issuance does: a returned remainder handed down again is counted again.
     */
    private fun LocalHeddle.pump(path: List<AttachmentId>, to: Long) {
        val edge = path.last()
        var remaining = to - issued(edge)
        check(remaining >= 0L) { "the rig: ${edge.value} already reads ${issued(edge)}, above $to" }
        if (remaining == 0L) return
        val chunk = minOf(remaining, CHUNK)
        val held = snapshot().ledger.holdings(root, LOCAL)
        if (held < chunk) mintSupply(chunk - held)
        reshapeForTest { start ->
            var l = start
            fun step(patch: us.tractat.kuilt.crdt.Patch<EntitlementLedger>?) {
                l = l.piece(checkNotNull(patch) { "the rig: a pump step was refused" }.delta)
            }
            for (upper in path.dropLast(1)) step(l.delegate(LOCAL, upper, chunk))
            while (remaining > 0L) {
                val amount = minOf(chunk, remaining)
                step(l.delegate(LOCAL, edge, amount))
                step(l.release(LOCAL, edge, amount))
                remaining -= amount
            }
            for (upper in path.dropLast(1).asReversed()) step(l.release(LOCAL, upper, chunk))
            l
        }
        check(issued(edge) == to) { "the rig: ${edge.value} reads ${issued(edge)}, not $to" }
    }

    /**
     * Mint [amount] at the root under this engine's epoch and spend it, half at each of [lanes], so the
     * minted supply grows by [amount] while the root's holdings, and every lease, stay as they were.
     * Each lane's lineage grows by its half.
     */
    private fun LocalHeddle.spendAway(amount: Long, lanes: Pair<GroupId, GroupId> = a to b) {
        if (amount > 0L) mintSupply(amount)
        spendHeld(lanes.first, amount / 2)
        spendHeld(lanes.second, amount - amount / 2)
    }

    /** Delegate [amount] of what the root holds down [lane]'s lineage and spend it there. */
    private fun LocalHeddle.spendHeld(lane: GroupId, amount: Long) {
        if (amount == 0L) return
        reshapeForTest { start ->
            var l = start
            val path = checkNotNull(l.lineageOf(lane))
            for (edge in path) l = l.piece(checkNotNull(l.delegate(LOCAL, edge, amount)) { "the rig: delegate" }.delta)
            l.piece(checkNotNull(l.spendCaptured(LOCAL, path, amount)) { "the rig: spend" }.delta)
        }
    }

    private fun LocalHeddle.state(): LocalHeddleState = snapshot()

    // ─── A pick, both sides of the watermark ─────────────────────────────────────────────────────

    /**
     * An edge whose effective issuance plus the reservation lands exactly on the watermark leases; one
     * unit more and the pick refuses, naming the parent above the edge, with nothing changed but the
     * wake memory. Asked again, it answers the same: a refusal moves nothing.
     */
    @Test
    fun anEdgeOneUnitUnderTheWatermarkLeasesAndOneOverRefuses() {
        val h = engine()
        h.pump(h.path(a), to = LIFETIME_WATERMARK - R)
        h.pump(h.path(b), to = LIFETIME_WATERMARK - R + 1L)
        val aBefore = h.issued(h.path(a).single())
        val bBefore = h.issued(h.path(b).single())
        val leased = h.acquire(setOf(a), setOf(a), startedAt = 0L)
        val before = h.state()
        val refused = h.acquire(setOf(b), setOf(b), startedAt = 0L)
        val again = h.acquire(setOf(b), setOf(b), startedAt = 0L)
        val after = h.state()
        assertAll(
            { assertEquals(LIFETIME_WATERMARK - R, aBefore, "the rig: a's edge is a reservation under the watermark") },
            { assertEquals(LIFETIME_WATERMARK - R + 1L, bBefore, "the rig: b's edge is one unit more") },
            { assertIs<AcquireOutcome.Acquired>(leased, "at the watermark exactly, it leases") },
            { assertEquals(LIFETIME_WATERMARK, h.issued(h.path(a).single()), "and the edge reads the watermark") },
            { assertEquals(AcquireOutcome.LifetimeExhausted(root), refused) },
            { assertEquals(AcquireOutcome.LifetimeExhausted(root), again, "and again, since a refusal moves nothing") },
            { assertEquals(before.ledger, after.ledger, "the ledger is as it was") },
            { assertEquals(before.leases, after.leases, "and so are the open leases") },
            { assertEquals(WaitExplanation.LifetimeExhausted(root), h.explain(b, setOf(b), setOf(b))) },
            { assertEquals(emptyList(), after.ledger.validate()) },
        )
    }

    /**
     * The root's minted supply, held in two epochs' records: a top-up that lands it exactly on the
     * watermark leases, and one unit more refuses with [AcquireOutcome.LifetimeExhausted] at the root.
     * Neither epoch's record is near the watermark alone, so a check of this epoch's record would lease.
     */
    @Test
    fun theRootsMintedSupplyOneUnitUnderTheWatermarkLeasesAndOneOverRefuses() {
        fun rigged(minted: Long): LocalHeddle {
            val first = engine().apply { reconcileCapacity(1L) }
            // Spend the float, so the root holds nothing and a pick must mint.
            first.spendHeld(a, first.state().ledger.holdings(root, LOCAL))
            first.spendAway(minted / 2 - first.minted())
            val second = LocalHeddle.restore(first.persist(), policy, epoch = 2L)
            second.spendAway(minted - second.minted())
            return second
        }
        val fits = rigged(LIFETIME_WATERMARK - R)
        val over = rigged(LIFETIME_WATERMARK - R + 1L)
        val fitsMinted = fits.minted()
        val overMinted = over.minted()
        val overHeld = over.state().ledger.holdings(root, LOCAL)
        val records = over.state().ledger.let { l -> l.mintIds().map { checkNotNull(l.mintRecord(it)).amount } }
        val leased = fits.acquire(setOf(a), setOf(a), startedAt = 0L)
        val before = over.state()
        val refused = over.acquire(setOf(a), setOf(a), startedAt = 0L)
        val after = over.state()
        assertAll(
            { assertEquals(LIFETIME_WATERMARK - R, fitsMinted, "the rig: the supply is a reservation under the watermark") },
            { assertEquals(LIFETIME_WATERMARK - R + 1L, overMinted, "the rig: and one unit more") },
            { assertEquals(0L, overHeld, "the rig: the root holds nothing, so a pick must mint") },
            { assertEquals(2, records.size, "the rig: the total is held in two epochs' records") },
            { assertTrue(records.all { it < LIFETIME_WATERMARK - R }, "the rig: neither record is near the watermark alone: $records") },
            { assertIs<AcquireOutcome.Acquired>(leased, "a top-up to the watermark exactly leases") },
            { assertEquals(LIFETIME_WATERMARK, fits.minted(), "the float is trimmed to the watermark, and never refuses") },
            { assertEquals(AcquireOutcome.LifetimeExhausted(root), refused) },
            { assertEquals(before.ledger, after.ledger, "nothing was minted") },
            { assertEquals(before.leases, after.leases) },
            { assertEquals(WaitExplanation.LifetimeExhausted(root), over.explain(a, setOf(a), setOf(a))) },
        )
    }

    /**
     * A leaf edge full under a parent with room: the refusal names that parent, `g`, and its sibling is
     * leased when it alone is eligible. While the full lane is eligible and next in line, the pick at
     * `g` stalls there rather than skip it, and explain says so for both lanes.
     */
    @Test
    fun aFullLeafEdgeNamesItsParentAndItsSiblingIsLeasedWhenItAloneIsEligible() {
        val h = engine()
        // y ahead of x, so x is next in line at g.
        repeat(3) { h.complete(h.lease(setOf(y), reservation = R).id, actualCost = R, waiting = setOf(x, y)) }
        h.pump(h.path(x), to = LIFETIME_WATERMARK - R + 1L)
        val gEdge = h.path(x).first()
        val alone = h.acquire(setOf(x), setOf(x), startedAt = 0L)
        val sibling = h.acquire(setOf(y), setOf(y), startedAt = 0L)
        val both = setOf(x, y)
        val stalled = h.acquire(both, both, startedAt = 0L)
        val stalledAgain = h.acquire(both, both, startedAt = 0L)
        assertAll(
            { assertEquals(LIFETIME_WATERMARK - R + 1L, h.issued(h.path(x).last()), "the rig: x's edge is a unit past room for a reservation") },
            { assertTrue(pickFits(h.issued(gEdge), R), "the rig: g's own edge has room") },
            { assertEquals(AcquireOutcome.LifetimeExhausted(g), alone) },
            { assertEquals(y, assertIs<AcquireOutcome.Acquired>(sibling).lease.leaf, "the sibling with room is leased") },
            { assertEquals(AcquireOutcome.LifetimeExhausted(g), stalled, "x is next in line, so the pick stalls at g") },
            { assertEquals(AcquireOutcome.LifetimeExhausted(g), stalledAgain) },
            { assertEquals(WaitExplanation.LifetimeExhausted(g), h.explain(x, both, both), "x's own lineage holds the full edge") },
            { assertEquals(WaitExplanation.LifetimeExhausted(g), h.explain(y, both, both), "y waits behind the stall") },
            { assertEquals(WaitExplanation.WouldBePickedNext, h.explain(y, setOf(y), setOf(y)), "and goes next alone") },
        )
    }

    /**
     * An edge whose base issuance is nothing and whose effective issuance is a relocated credit (a
     * reweight of the internal group `g`, #1691): the check reads the effective issuance, so the pick
     * refuses. The root holds the reservation, so the root's own supply is not what refuses.
     */
    @Test
    fun aRelocatedEdgeIsCheckedOnItsEffectiveIssuance() {
        val h = engine()
        val credit = LIFETIME_WATERMARK - R + 1L
        // Spend the credit at x, so g's edge carries it as net inflow, which a reweight re-homes.
        h.mintSupply(credit)
        h.reshapeForTest { start ->
            var l = start
            val path = checkNotNull(l.lineageOf(x))
            for (edge in path) l = l.piece(checkNotNull(l.delegate(LOCAL, edge, credit)).delta)
            l.piece(checkNotNull(l.spendCaptured(LOCAL, path, credit)).delta)
        }
        val reweighted = draft("life") {
            lane("a")
            lane("b")
            group("g", Weight.of(2)) {
                lane("x")
                lane("y")
            }
        }
        val applied = h.apply(reweighted, generation = 1L)
        val gEdge = h.path(x).first()
        val ledger = h.state().ledger
        val out = h.acquire(setOf(x), setOf(x), startedAt = 0L)
        assertAll(
            { assertEquals(emptyList(), applied.refusals, "the rig: the reweight landed") },
            { assertEquals(0L, ledger.baseIssuance(gEdge), "the rig: g's new edge has no base issuance") },
            { assertEquals(credit, ledger.effectiveIssuance(gEdge), "the rig: its effective issuance is the relocated credit") },
            { assertTrue(ledger.holdings(root, LOCAL) >= R, "the rig: the root holds the reservation") },
            { assertEquals(AcquireOutcome.LifetimeExhausted(root), out) },
        )
    }

    // ─── A charge in the headroom, and one that does not fit ─────────────────────────────────────

    /**
     * Past the watermark, inside the headroom: an admit, an extend and an overrunning complete all
     * land in full, with nothing unrecorded, and leave the counter past the watermark.
     */
    @Test
    fun inTheHeadroomAdmitExtendAndAnOverrunLandInFull() {
        val h = engine()
        val edge = h.path(a).single()
        h.pump(h.path(a), to = LIFETIME_WATERMARK)
        val rigged = h.issued(edge)
        val admitted = assertIs<AcquireOutcome.Acquired>(h.admit(a, setOf(a), startedAt = 0L, reservation = R)).lease
        val extended = h.extend(admitted.id, R, waiting = setOf(a))
        val done = h.complete(admitted.id, actualCost = 3 * R, waiting = emptySet())
        assertAll(
            { assertEquals(LIFETIME_WATERMARK, rigged, "the rig: a's edge is at the watermark") },
            { assertEquals(R, admitted.reserved, "the admit reserved in full") },
            { assertEquals(LeaseOutcome.Done(charged = 0L, overrun = 0L, unrecorded = 0L), extended) },
            { assertEquals(LeaseOutcome.Done(charged = 3 * R, overrun = R, unrecorded = 0L), done) },
            { assertEquals(LIFETIME_WATERMARK + 3 * R, h.issued(edge), "the counter is past the watermark") },
            { assertEquals(emptyList(), h.state().ledger.validate()) },
        )
    }

    /**
     * An overrun above a full edge: `g → x` has no room for it within `Long`, so it is charged at `g`,
     * the deepest group it reaches in whole. That edge's issuance does not move, the cost is recorded
     * in full, and the ledger validates.
     */
    @Test
    fun anOverrunAboveAFullEdgeIsChargedAtTheGroupAboveIt() {
        val h = engine()
        val lease = h.lease(setOf(x), reservation = R)
        val overrun = 1_000L
        val (gEdge, xEdge) = h.path(x)
        h.pump(h.path(x), to = Long.MAX_VALUE - overrun + 1L)
        val xBefore = h.issued(xEdge)
        val gBefore = h.issued(gEdge)
        val spentBefore = h.state().ledger.leafSpentTotal()
        val done = h.complete(lease.id, actualCost = R + overrun, waiting = emptySet())
        val ledger = h.state().ledger
        assertAll(
            { assertEquals(Long.MAX_VALUE - overrun + 1L, xBefore, "the rig: x's edge is a unit short of room for the overrun") },
            { assertTrue(chargeFits(gBefore, overrun), "the rig: g's edge has room") },
            { assertEquals(LeaseOutcome.Done(charged = R + overrun, overrun = overrun, unrecorded = 0L), done) },
            { assertEquals(xBefore, ledger.effectiveIssuance(xEdge), "x's edge did not move") },
            { assertEquals(gBefore + overrun, ledger.effectiveIssuance(gEdge), "the overrun crossed g's edge") },
            { assertEquals(spentBefore + R + overrun, ledger.leafSpentTotal(), "and the whole cost is spent") },
            { assertEquals(emptyList(), h.state().leases) },
            { assertEquals(emptyList(), ledger.validate()) },
        )
    }

    /**
     * An overrun under a full top edge: the root's own child edge `root → g` has no room, so no group
     * below the root is reached. The overrun is recorded nowhere and reported as unrecorded, and the
     * reservation is still charged at the leaf, whose own edge had room.
     */
    @Test
    fun anOverrunUnderAFullTopEdgeIsUnrecorded() {
        val h = engine()
        val lease = h.lease(setOf(x), reservation = R)
        val overrun = 1_000L
        val (gEdge, xEdge) = h.path(x)
        h.pump(listOf(gEdge), to = Long.MAX_VALUE - overrun + 1L)
        val gBefore = h.issued(gEdge)
        val spentBefore = h.state().ledger.leafSpentTotal()
        val done = h.complete(lease.id, actualCost = R + overrun, waiting = emptySet())
        val ledger = h.state().ledger
        assertAll(
            { assertEquals(Long.MAX_VALUE - overrun + 1L, gBefore, "the rig: g's edge is a unit short of room") },
            { assertTrue(chargeFits(h.issued(xEdge), overrun), "the rig: x's own edge has room") },
            { assertEquals(LeaseOutcome.Done(charged = R, overrun = overrun, unrecorded = overrun), done) },
            { assertEquals(gBefore, ledger.effectiveIssuance(gEdge), "g's edge did not move") },
            { assertEquals(spentBefore + R, ledger.leafSpentTotal(), "only the reservation is spent") },
            { assertEquals(emptyList(), ledger.validate()) },
        )
    }

    /**
     * An overrun every edge has room for, which the root cannot fund in whole within `Long`: it is
     * recorded nowhere, not minted in part, and the minted supply does not move.
     */
    @Test
    fun anOverrunTheRootCannotFundInWholeIsUnrecorded() {
        val h = engine()
        val overrun = 1L shl 21
        h.spendAway(Long.MAX_VALUE - (1L shl 20) - h.minted(), lanes = b to y)
        val lease = h.lease(setOf(a), reservation = R)
        val minted = h.minted()
        val held = h.state().ledger.holdings(root, LOCAL)
        val done = h.complete(lease.id, actualCost = R + overrun, waiting = emptySet())
        assertAll(
            { assertTrue(chargeFits(h.issued(h.path(a).single()), overrun), "the rig: a's edge has room") },
            { assertTrue(!chargeFits(minted, overrun - held), "the rig: the root cannot mint the shortfall: $minted, holding $held") },
            { assertEquals(LeaseOutcome.Done(charged = R, overrun = overrun, unrecorded = overrun), done) },
            { assertEquals(minted, h.minted(), "nothing was minted") },
            { assertEquals(emptyList(), h.state().ledger.validate()) },
        )
    }

    /**
     * `complete(lease, Long.MAX_VALUE)` on an engine that has served one other lease: the overrun
     * cannot be funded within `Long`, so it is recorded nowhere, the lease closes, and the next
     * acquire leases. The engine carries on.
     */
    @Test
    fun completeAtLongMaxReportsTheOverrunAndTheNextAcquireLeases() {
        val h = engine().apply { reconcileCapacity(1L) }
        h.complete(h.lease(setOf(b), reservation = R).id, actualCost = R, waiting = emptySet())
        val lease = h.lease(setOf(a), reservation = R)
        val mintedBefore = h.minted()
        val done = h.complete(lease.id, actualCost = Long.MAX_VALUE, waiting = emptySet())
        val next = h.acquire(setOf(a), setOf(a), startedAt = 0L)
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = R, overrun = Long.MAX_VALUE - R, unrecorded = Long.MAX_VALUE - R), done) },
            { assertEquals(mintedBefore, h.minted(), "nothing was minted for it") },
            { assertEquals(emptyList(), h.state().leases.filter { it.id == lease.id }, "the lease closed") },
            { assertIs<AcquireOutcome.Acquired>(next, "and the engine carries on") },
            { assertEquals(emptyList(), h.state().ledger.validate()) },
        )
    }

    /**
     * The boundary of the row above, pinned as it is rather than as the design text reads (#3475): on
     * an engine whose only service is the one lease, `complete(lease, Long.MAX_VALUE)` fits exactly,
     * since the edge and the root's supply each have exactly the overrun's room left under
     * `Long.MAX_VALUE`. It is recorded in full, the supply is filled to the brim, and every later
     * acquire is refused at the root.
     */
    @Test
    fun completeAtLongMaxOnAFreshEngineFitsExactlyAndFillsTheSupply() {
        val h = LocalHeddle.bootWhole(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        val lease = h.lease(setOf(a), reservation = R)
        val done = h.complete(lease.id, actualCost = Long.MAX_VALUE, waiting = emptySet())
        val next = h.acquire(setOf(b), setOf(b), startedAt = 0L)
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = Long.MAX_VALUE, overrun = Long.MAX_VALUE - R, unrecorded = 0L), done) },
            { assertEquals(Long.MAX_VALUE, h.minted(), "the supply is filled to Long.MAX_VALUE") },
            { assertEquals(AcquireOutcome.LifetimeExhausted(root), next) },
            { assertEquals(emptyList(), h.state().ledger.validate()) },
        )
    }

    /**
     * An extend at a full counter answers `Done` with the whole amount unrecorded, and the lease, the
     * ledger and the wake memory are as they were. One unit smaller, it lands, with nothing unrecorded.
     */
    @Test
    fun anExtendAtAFullCounterAnswersDoneWithTheAmountUnrecorded() {
        val h = engine()
        val lease = h.lease(setOf(a), reservation = R)
        val edge = h.path(a).single()
        h.pump(h.path(a), to = Long.MAX_VALUE - R + 1L)
        val before = h.state()
        val refused = h.extend(lease.id, R, waiting = setOf(a, b))
        val after = h.state()
        val smaller = h.extend(lease.id, R - 1L, waiting = setOf(a, b))
        assertAll(
            { assertEquals(Long.MAX_VALUE - R + 1L, before.ledger.effectiveIssuance(edge), "the rig: a's edge is a unit short of room") },
            { assertEquals(LeaseOutcome.Done(charged = 0L, overrun = 0L, unrecorded = R), refused) },
            { assertEquals(before.ledger, after.ledger, "the ledger is as it was") },
            { assertEquals(before.leases, after.leases, "and the lease") },
            { assertEquals(before.allocatorMemory, after.allocatorMemory, "and the wake memory") },
            { assertEquals(LeaseOutcome.Done(charged = 0L, overrun = 0L, unrecorded = 0L), smaller, "one unit smaller, it lands") },
            { assertEquals(Long.MAX_VALUE, h.issued(edge)) },
            { assertEquals(2 * R - 1L, h.state().leases.single { it.id == lease.id }.reserved) },
            { assertEquals(emptyList(), h.state().ledger.validate()) },
        )
    }

    /**
     * An admit at a full counter opens the lease with nothing reserved, changes nothing else, and its
     * cost arrives at complete as an overrun, which the full edge leaves unrecorded. A snapshot holding
     * that lease restores.
     */
    @Test
    fun anAdmitThatDoesNotFitOpensAtZeroAndCompletes() {
        val h = engine()
        val edge = h.path(a).single()
        h.pump(h.path(a), to = Long.MAX_VALUE - R + 1L)
        val before = h.state()
        val admitted = assertIs<AcquireOutcome.Acquired>(h.admit(a, setOf(a, b), startedAt = 7L, reservation = R)).lease
        val after = h.state()
        val restored = LocalHeddle.decode(Json.encodeToString(LocalHeddleSnapshot.serializer(), h.persist()), Json)
        val done = h.complete(admitted.id, actualCost = R, waiting = emptySet())
        assertAll(
            { assertEquals(Long.MAX_VALUE - R + 1L, before.ledger.effectiveIssuance(edge), "the rig: a's edge is a unit short of room") },
            { assertEquals(0L, admitted.reserved, "nothing reserved") },
            { assertEquals(before.ledger, after.ledger, "nothing else changed") },
            { assertEquals(before.allocatorMemory, after.allocatorMemory) },
            { assertIs<RestoreResult.Restored>(restored, "a snapshot holding the lease restores") },
            { assertEquals(LeaseOutcome.Done(charged = 0L, overrun = R, unrecorded = R), done) },
            { assertEquals(emptyList(), h.state().leases) },
            { assertEquals(emptyList(), h.state().ledger.validate()) },
        )
    }

    // ─── What an unrecorded charge costs ─────────────────────────────────────────────────────────

    /**
     * The one place an unrecorded overrun reaches (design §7.1, "The lifetime limit"): the parent's
     * net-service clock runs short by it, so a sibling returning there comes back that much less
     * moved. Measured on two twins that differ only in whether `a`'s overrun of [OVERRUN] is
     * recorded: `b` is away while `a` and `g` ask, so the clock over them, total weight 2, runs short
     * by `OVERRUN / 2`, and `b` comes back exactly that far behind `g`, its credit. `a` reads the
     * whole overrun lower, credit it cannot spend: its own edge is the full one.
     */
    @Test
    fun anUnrecordedOverrunRunsTheClockShortByItsShare() {
        fun run(unrecorded: Boolean): Triple<LeaseOutcome, Rational, Rational> {
            val h = engine(float = 100_000L)
            val all = setOf(a, b, x)
            val away = setOf(a, x)
            for (lane in listOf(b, a, x)) {
                val lease = assertIs<AcquireOutcome.Acquired>(h.acquire(all, setOf(lane), startedAt = 0L)).lease
                h.complete(lease.id, R, waiting = all)
            }
            val la = assertIs<AcquireOutcome.Acquired>(h.acquire(away, setOf(a), startedAt = 1L)).lease
            if (unrecorded) h.pump(h.path(a), to = Long.MAX_VALUE - OVERRUN + 1L)
            val done = h.complete(la.id, R + OVERRUN, waiting = away)
            h.complete(assertIs<AcquireOutcome.Acquired>(h.acquire(away, setOf(x), startedAt = 2L)).lease.id, R, waiting = away)
            assertIs<AcquireOutcome.Acquired>(h.acquire(all, setOf(b), startedAt = 3L))
            val ledger = h.state().ledger
            fun at(lane: GroupId) = checkNotNull(h.allocator.effectiveVirtualService(ledger, root, h.path(lane).first()))
            return Triple(done, at(b) - at(g), at(a) - at(g))
        }
        val (recordedDone, bRecorded, aRecorded) = run(unrecorded = false)
        val (unrecordedDone, bUnrecorded, aUnrecorded) = run(unrecorded = true)
        assertAll(
            { assertEquals(0L, (recordedDone as LeaseOutcome.Done).unrecorded, "the rig: the control records the overrun") },
            { assertEquals(OVERRUN, (unrecordedDone as LeaseOutcome.Done).unrecorded, "the rig: the twin records none of it") },
            { assertEquals(Rational.of(OVERRUN, 2L), bRecorded - bUnrecorded, "b comes back the clock's shortfall behind") },
            { assertEquals(Rational.of(OVERRUN), aRecorded - aUnrecorded, "a reads the whole overrun lower") },
        )
    }

    // ─── A driver near both limits ───────────────────────────────────────────────────────────────

    /**
     * Seeded calls near both limits, overruns up to `Long.MAX_VALUE` included: no call throws, the
     * ledger validates after each, every completion's `charged + unrecorded` is the cost it was given,
     * and what the ledger spent is what completions report as charged. It walks the open leases in id
     * order (#2592).
     */
    @Test
    fun aDriverNearBothLimitsNeverThrowsAndAccountsForEveryUnit() {
        for (seed in 0L until 4L) {
            val h = engine().apply { reconcileCapacity(2L) }
            h.pump(h.path(a), to = LIFETIME_WATERMARK - 40 * R)
            h.pump(h.path(x), to = LIFETIME_WATERMARK - 40 * R)
            // The minted supply a hundred reservations under the watermark, spent at b and y.
            h.spendAway(LIFETIME_WATERMARK - 100 * R - h.minted(), lanes = b to y)
            val rng = Random(seed)
            val lanes = listOf(a, b, x, y)
            val costs = listOf(0L, 1L, R, 3 * R, 1L shl 50, LocalHeddle.LIFETIME_HEADROOM, Long.MAX_VALUE / 2, Long.MAX_VALUE)
            val spentAtStart = h.state().ledger.leafSpentTotal()
            var charged = 0L
            var acquires = 0
            var refusals = 0
            var unrecordedCompletes = 0
            for (step in 0 until 300) {
                val open = h.state().leases.sortedBy { it.id }
                val context = "seed $seed step $step"
                when (rng.nextInt(5)) {
                    0 -> {
                        val waiting = lanes.filter { rng.nextBoolean() }.toSet().ifEmpty { setOf(a) }
                        when (h.acquire(waiting, waiting, startedAt = step.toLong(), reservation = R)) {
                            is AcquireOutcome.Acquired -> acquires++
                            is AcquireOutcome.LifetimeExhausted -> refusals++
                            else -> {}
                        }
                    }
                    1 -> h.admit(lanes[rng.nextInt(lanes.size)], lanes.toSet(), startedAt = step.toLong(), reservation = R)
                    2 -> if (open.isNotEmpty()) h.extend(open[rng.nextInt(open.size)].id, R, waiting = lanes.toSet())
                    else -> if (open.isNotEmpty()) {
                        val lease = open[rng.nextInt(open.size)]
                        val cost = costs[rng.nextInt(costs.size)]
                        val done = assertIs<LeaseOutcome.Done>(h.complete(lease.id, cost, waiting = lanes.toSet()), context)
                        assertEquals(cost, done.charged + done.unrecorded, "$context: charged plus unrecorded is the cost")
                        if (done.unrecorded > 0L) unrecordedCompletes++
                        charged += done.charged
                    }
                }
                assertEquals(emptyList(), h.state().ledger.validate(), context)
            }
            for (lease in h.state().leases) charged += assertIs<LeaseOutcome.Done>(h.complete(lease.id, lease.reserved, waiting = emptySet())).charged
            assertAll(
                { assertEquals(charged, h.state().ledger.leafSpentTotal() - spentAtStart, "seed $seed: the ledger spent what completions charged") },
                { assertTrue(refusals > 0, "seed $seed, the rig: a pick met the watermark ($acquires leased)") },
                { assertTrue(unrecordedCompletes > 0, "seed $seed, the rig: a charge did not fit") },
            )
        }
    }

    private companion object {
        /** The reservation every row leases at. */
        const val R: Long = 8L

        /** The most a [pump] step moves at once: small enough for a parent to hold, large enough to need few steps. */
        const val CHUNK: Long = 1L shl 60

        /** The overrun the clock row charges, even so the clock's share of it is whole. */
        const val OVERRUN: Long = 1_000L
    }
}
