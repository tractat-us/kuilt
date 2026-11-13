package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.RuntimeChildren
import us.tractat.kuilt.heddle.TopologyStep.Activate
import us.tractat.kuilt.heddle.TopologyStep.Prepare
import us.tractat.kuilt.heddle.TopologyStep.Reconcile
import us.tractat.kuilt.heddle.TopologyStep.Retire
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [LocalHeddle.apply], one property per test (#3176, plan Task L3): a reload is one unit, a
 * reweight moves its open leases onto the new edge, and a removal drains.
 */
class LocalHeddleApplyTest {

    /**
     * `root → {acme:[acmeW] → {batch:[batchW], interactive:3}, hobby:1 → default, tenants:1 (marked), s:1}`,
     * without `hobby` or `s` when told so, and with `batch` turned into a group over `x` when told so.
     */
    private fun tree(
        acmeW: Long = 3,
        batchW: Long = 1,
        tenantsW: Long = 1,
        hobby: Boolean = true,
        s: Boolean = true,
        batchIsGroup: Boolean = false,
        batch: Boolean = true,
    ) = Topology(
        "root",
        listOfNotNull(
            Group(
                "acme",
                Weight.of(acmeW),
                listOfNotNull(
                    if (!batch) null else if (batchIsGroup) Group("batch", Weight.of(batchW), listOf(Lane("x"))) else Lane("batch", Weight.of(batchW)),
                    Lane("interactive", Weight.of(3)),
                ),
            ),
            if (hobby) Group("hobby", Weight.ONE, listOf(Lane("default"))) else null,
            Group("tenants", Weight.of(tenantsW), emptyList(), runtimeChildren = RuntimeChildren(Weight.ONE)),
            if (s) Lane("s") else null,
        ),
    )

    private val t = tree()
    private val acme = t.groupId("acme")
    private val batch = t.groupId("acme", "batch")
    private val interactive = t.groupId("acme", "interactive")
    private val hobby = t.groupId("hobby")
    private val default = t.groupId("hobby", "default")
    private val s = t.groupId("s")
    private val x = GroupId("${batch.value}/x")
    private val c = t.runtimeChildId(listOf("tenants"), "c-7f3a")

    private fun engine(topology: Topology = t): LocalHeddle =
        LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 2L), epoch = 0L).also { it.reconcileCapacity(4L) }

    private fun LocalHeddle.edgeInto(group: GroupId): AttachmentId = assertNotNull(snapshot().ledger.lineageOf(group)).last()

    private fun LocalHeddle.paths(): List<List<AttachmentId>> = snapshot().leases.map { assertNotNull(capturedPath(it.id)) }

    /** Acquire and complete [n] slots with [waiting] as both sets, each at its reservation. */
    private fun LocalHeddle.serve(waiting: Set<GroupId>, n: Int) = repeat(n) {
        val lease = lease(waiting, reservation = 2L, startedAt = it.toLong())
        complete(lease.id, 2L)
    }

    @Test
    fun reweightRehomesOpenLeasesAtomically() {
        val h = engine()
        h.serve(setOf(batch, interactive, default), 12)
        val leases = listOf(batch, interactive, batch).map { h.lease(setOf(it), reservation = 2L) }
        val oldAcme = h.edgeInto(acme)
        val spentBefore = h.snapshot().ledger.leafSpentTotal()
        val result = h.apply(tree(acmeW = 4), generation = 1L)
        val l = h.snapshot().ledger
        val newAcme = h.edgeInto(acme)
        val stillOpen = h.snapshot().leases
        val paths = leases.map { assertNotNull(h.capturedPath(it.id)) }
        val dones = leases.map { lease ->
            val done = h.complete(lease.id, 3L)
            val now = h.snapshot().ledger
            Triple(done, now.validate(), now.holdings(acme, LOCAL))
        }
        val after = h.snapshot().ledger
        assertAll(
            { assertEquals(emptyList(), result.refusals) },
            { assertTrue(newAcme != oldAcme, "the rig: acme has a new edge") },
            { assertEquals(leases, stillOpen, "every lease is still open, unchanged") },
            { assertEquals(Lifecycle.RETIRED, l.lifecycle(oldAcme)) },
            { assertEquals(Lifecycle.ACTIVE, l.lifecycle(newAcme)) },
            { assertTrue(paths.all { it.first() == newAcme }, "every path runs through the new edge: $paths") },
            { assertTrue(paths.none { p -> p.any { l.lifecycle(it) == Lifecycle.RETIRED } }, "no path names a retired edge: $paths") },
            { assertEquals(leases.map { l.lineageOf(it.leaf) }, paths, "each is its leaf's one live lineage") },
            { assertEquals(6L, assertNotNull(l.edge(newAcme)).outstanding, "the three reservations crossed the new edge") },
            { assertEquals(emptyList(), l.validate()) },
            { assertEquals(0L, l.holdings(acme, LOCAL)) },
            { assertEquals(List(3) { Triple(LeaseOutcome.Done(3L, 1L), emptyList<LedgerConflict>(), 0L) }, dones, "each charged once, clean, never negative") },
            { assertEquals(spentBefore + 9L, after.leafSpentTotal(), "nine units charged, once each") },
            { assertEquals(9L, assertNotNull(after.edge(newAcme)).spent - assertNotNull(l.edge(newAcme)).spent, "on the new edge") },
            { assertEquals(0L, assertNotNull(after.edge(newAcme)).outstanding) },
        )
    }

    @Test
    fun reweightOfAGroupAndItsChildMovesALeaseOnce() {
        val h = engine()
        val lease = h.lease(setOf(batch), reservation = 2L)
        val result = h.apply(tree(acmeW = 4, batchW = 2), generation = 1L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(emptyList(), result.refusals) },
            { assertEquals(l.lineageOf(batch), h.capturedPath(lease.id), "the lease charges the new lineage") },
            { assertEquals(listOf("root/acme@4:1#1", "root/acme/batch@2:1#1"), h.capturedPath(lease.id)?.map { it.value }) },
            { assertEquals(emptyList(), l.validate()) },
            { assertEquals(LeaseOutcome.Done(2L, 0L), h.complete(lease.id, 2L)) },
            { assertEquals(emptyList(), h.snapshot().ledger.validate()) },
        )
    }

    /**
     * R1's reset, with leases held open across the reweight so the re-home itself is the new edge's
     * first delegation: seated at the root's front, then advanced by the moved reservations. With no
     * lease open the next pick would seat it, and this would pass whatever the re-home did.
     */
    @Test
    fun reweightRestartsAtTheParentsFront() {
        val h = engine(tree(hobby = true, s = false))
        h.serve(setOf(batch, default), 40)
        // Each opened with hobby still waiting, so the root keeps observing it as demanding.
        val leases = listOf(batch, interactive).map { leaf ->
            assertNotNull((h.acquire(setOf(leaf, default), setOf(leaf), startedAt = 0L, reservation = 2L) as? AcquireOutcome.Acquired)?.lease)
        }
        val before = h.snapshot().ledger
        // The one other demanding child of the root: its effective virtual service is the front.
        val front = assertNotNull(before.virtualService(h.edgeInto(hobby)))
        val result = h.apply(tree(acmeW = 4, s = false), generation = 1L)
        val l = h.snapshot().ledger
        val gauge = assertNotNull(l.gauge(h.edgeInto(acme)), "the re-home wrote the new edge's gauge")
        assertAll(
            { assertEquals(2, before.let { leases.size }, "the rig: two leases open across the reweight") },
            { assertTrue(front > Rational.of(10L), "the rig: the front is far from the origin, at $front") },
            { assertEquals(emptyList(), result.refusals) },
            { assertEquals(4L, gauge.folded, "folded at the four re-homed units") },
            { assertEquals(front, gauge.floor - Rational.of(4L, 4L), "seated at the front, then advanced 4 units at weight 4") },
            { assertEquals(front + Rational.ONE, l.virtualService(h.edgeInto(acme))) },
            { assertEquals(emptyList(), leases.map { h.complete(it.id, 2L) }.filter { it != LeaseOutcome.Done(2L, 0L) }) },
        )
    }

    @Test
    fun rehomeIsAllOrNothing() {
        val h = engine()
        h.lease(setOf(batch), reservation = 2L)
        h.lease(setOf(interactive), reservation = 2L)
        // Another writer's unit at acme, which this engine cannot hand back: acme's old edge cannot retire.
        val other = ReplicaId("other")
        h.reshapeForTest { l ->
            var r = l.piece(l.mint(MintId("other#0"), t.groupId(), other, 1L).delta)
            r = r.piece(assertNotNull(r.delegate(other, assertNotNull(r.lineageOf(acme)).last(), 1L)).delta)
            r
        }
        val oldAcme = h.edgeInto(acme)
        val before = h.snapshot()
        val pathsBefore = h.paths()
        val result = h.apply(tree(acmeW = 4), generation = 1L)
        val after = h.snapshot()
        assertAll(
            // No lease of this engine's holds it, and waiting will not cure it: refused, not draining.
            { assertEquals(listOf(StepRefusal.Refused(acme, Retire(oldAcme, acme), "outstanding units this engine does not hold: 1")), result.refusals) },
            { assertEquals(emptyList(), result.applied, "nothing of the reweight landed") },
            { assertSame(before.ledger, after.ledger, "the ledger is the very same value") },
            { assertEquals(before, after) },
            { assertEquals(pathsBefore, h.paths(), "every lease still charges its old path") },
        )
    }

    @Test
    fun internalReweightAfterSpendEndsClean() {
        val h = engine()
        h.serve(setOf(batch, interactive), 20)
        val oldAcme = h.edgeInto(acme)
        val spent = assertNotNull(h.snapshot().ledger.edge(oldAcme)).spent
        val result = h.apply(tree(acmeW = 2), generation = 1L)
        val l = h.snapshot().ledger
        val newAcme = h.edgeInto(acme)
        val next = h.lease(setOf(batch), reservation = 2L)
        assertAll(
            { assertTrue(spent >= 40L, "the rig: acme served 40 units, read $spent") },
            { assertEquals(emptyList(), result.refusals) },
            { assertTrue(Reconcile(acme) in result.applied, "the old edge's spend moved: ${result.applied}") },
            { assertEquals(spent, assertNotNull(l.edge(newAcme)).spent, "onto the new edge") },
            { assertEquals(0L, l.holdings(acme, LOCAL)) },
            { assertEquals(emptyList(), l.validate()) },
            { assertEquals(LeaseOutcome.Done(2L, 0L), h.complete(next.id, 2L)) },
            { assertEquals(emptyList(), h.snapshot().ledger.validate()) },
        )
    }

    @Test
    fun oneDrainingChildDoesNotBlockAnother() {
        val h = engine()
        val lease = h.lease(setOf(default), reservation = 2L)
        val defaultEdge = h.edgeInto(default)
        val hobbyEdge = h.edgeInto(hobby)
        val result = h.apply(tree(acmeW = 4, hobby = false), generation = 1L)
        val l = h.snapshot().ledger
        assertAll(
            {
                assertEquals(
                    listOf(
                        StepRefusal.Draining(hobby, hobbyEdge, 2L, listOf(lease)),
                        StepRefusal.Draining(default, defaultEdge, 2L, listOf(lease)),
                    ),
                    result.refusals,
                )
            },
            { assertEquals(Lifecycle.ACTIVE, l.lifecycle(h.edgeInto(acme))) },
            { assertEquals("root/acme@4:1#1", h.edgeInto(acme).value, "acme's reweight landed") },
            { assertEquals(Lifecycle.CLOSING, l.lifecycle(defaultEdge)) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    @Test
    fun removalWithOpenLeaseIsDrainingThenCompletes() {
        val h = engine()
        val lease = h.lease(setOf(default), reservation = 2L)
        val defaultEdge = h.edgeInto(default)
        val hobbyEdge = h.edgeInto(hobby)
        val removed = tree(hobby = false)
        val first = h.apply(removed, generation = 1L)
        val extend = h.extend(lease.id, 1L)
        val acquire = h.acquire(setOf(default), setOf(default), startedAt = 0L)
        val done = h.complete(lease.id, 2L)
        val second = h.apply(removed, generation = 1L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(listOf(hobby, default), first.refusals.map { it.child }) },
            { assertTrue(first.refusals.all { it is StepRefusal.Draining && it.blockingLeases == listOf(lease) }) },
            { assertEquals(LeaseOutcome.PathDraining(hobbyEdge), extend) },
            { assertEquals(AcquireOutcome.NoLiveLineage(t.groupId()), acquire, "nothing new is leased in a draining group") },
            { assertEquals(LeaseOutcome.Done(2L, 0L), done) },
            { assertEquals(emptyList(), second.refusals) },
            { assertEquals(listOf(Retire(defaultEdge, default), Retire(hobbyEdge, hobby)), second.applied) },
            { assertEquals(emptyList(), l.allEdges().filter { l.lifecycle(it) == Lifecycle.CLOSING }) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    @Test
    fun orphanUnderRemovedGroupDoesNotBlockReload() {
        val h = engine()
        val orphan = h.lease(setOf(default), reservation = 2L, startedAt = 10L)
        val target = tree(hobby = false, acmeW = 5)
        val first = h.apply(target, generation = 1L)
        val found = h.reconcileLeases(live = emptySet(), now = 13L).orphaned.single()
        val settled = h.complete(found.lease.id, found.defaultSettlement)
        val second = h.apply(target, generation = 1L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(listOf(hobby, default), first.refusals.map { it.child }) },
            { assertEquals("root/acme@5:1#1", h.edgeInto(acme).value, "the rest of the reload landed") },
            { assertEquals(orphan, found.lease) },
            { assertEquals(LeaseOutcome.Done(2L, 0L), settled) },
            { assertEquals(emptyList(), second.refusals) },
            { assertEquals(emptyList(), Topology.diff(target, l, generation = 0L)) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }

    @Test
    fun reapplyingTheSameDiffIsANoOp() {
        val h = engine()
        h.serve(setOf(batch, default), 6)
        val lease = h.lease(setOf(batch), reservation = 2L)
        val before = h.snapshot()
        val result = h.apply(t, generation = 7L)
        assertAll(
            { assertEquals(ApplyResult(emptyList(), emptyList()), result) },
            { assertSame(before.ledger, h.snapshot().ledger) },
            { assertEquals(before, h.snapshot()) },
            { assertEquals(LeaseOutcome.Done(2L, 0L), h.complete(lease.id, 2L)) },
        )
    }

    @Test
    fun fromTopologyMatchesBootingTheTarget() {
        val l = engine().snapshot().ledger
        val booted = bootFrom(t)
        assertAll(
            { assertEquals(t.lower().map { it.id }, l.allEdges().sorted(), "exactly the lowered ids, at generation 0") },
            { assertTrue(l.allEdges().all { l.lifecycle(it) == Lifecycle.ACTIVE }) },
            { assertEquals(booted.activeTriples(), l.activeTriples()) },
            { assertEquals(emptyList(), l.validate()) },
            { assertEquals(0L, l.mintedTotal(), "no supply until a capacity is reconciled") },
        )
    }

    /**
     * A former lane keeps its open lease's reservation through a re-home that moves it. `batch`
     * becomes a group over `x` while a lease is open at it, and then `acme` is reweighted. The
     * re-home delegates the lease's own reservation down to its own leaf, `batch`, which is no longer
     * leasable, so `x`'s next lease is handed exactly its own reservation and `batch` still holds
     * the open one.
     */
    @Test
    fun rehomeKeepsAFormerLanesOpenReservationsEarmarked() {
        val h = engine()
        val open = h.lease(setOf(batch), reservation = 5L)
        val grown = h.apply(tree(batchIsGroup = true), generation = 1L)
        val batchEdge = h.edgeInto(batch)
        val moved = h.apply(tree(batchIsGroup = true, acmeW = 4), generation = 2L)
        val child = h.lease(setOf(x), reservation = 2L)
        val mid = h.snapshot().ledger
        val path = h.capturedPath(open.id)
        val done = h.complete(open.id, 5L)
        val after = h.snapshot().ledger
        assertAll(
            { assertEquals(emptyList(), grown.refusals) },
            { assertTrue(!mid.isLeaf(batch), "the rig: batch is a group now") },
            { assertEquals(emptyList(), moved.refusals) },
            { assertEquals(listOf(h.edgeInto(acme), batchEdge), path, "the lease moved onto acme's new edge, still ending at batch") },
            { assertEquals(x, child.leaf) },
            { assertEquals(2L, mid.holdings(x, LOCAL), "the new child got its own reservation") },
            { assertEquals(5L, mid.holdings(batch, LOCAL), "the open lease's 5 units stayed at the former lane") },
            { assertEquals(LeaseOutcome.Done(5L, 0L), done) },
            { assertEquals(0L, after.holdings(batch, LOCAL)) },
            { assertEquals(emptyList(), after.validate()) },
        )
    }

    @Test
    fun runtimeLeavesUnderAReweightedParentAreRehomed() {
        val h = engine()
        val attached = h.attachRuntimeChild(listOf("tenants"), "c-7f3a", generation = 0L)
        val lease = h.lease(setOf(c), reservation = 2L)
        val runtimeEdge = h.edgeInto(c)
        val result = h.apply(tree(tenantsW = 2), generation = 2L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(emptyList(), attached.refusals) },
            { assertEquals(emptyList(), result.refusals) },
            { assertEquals(listOf(h.edgeInto(t.groupId("tenants")), runtimeEdge), h.capturedPath(lease.id), "moved onto tenants' new edge") },
            { assertEquals("root/tenants@2:1#2", h.edgeInto(t.groupId("tenants")).value) },
            { assertEquals(emptyList(), l.validate()) },
            { assertEquals(LeaseOutcome.Done(2L, 0L), h.complete(lease.id, 2L)) },
        )
    }

    /**
     * A runtime child detached while a lease is open drains, and attaching it again lists
     * `[Prepare, Retire, Activate]`: a new edge for the same group, which the re-home detector
     * reads as a replacement. The draining lease moves onto the new edge, and the child is attached
     * at once rather than when the drain ends.
     */
    @Test
    fun reattachMidDrainMovesTheDrainingLeaseOntoTheNewEdge() {
        val h = engine()
        h.attachRuntimeChild(listOf("tenants"), "c-7f3a", generation = 0L)
        val lease = h.lease(setOf(c), reservation = 2L)
        val oldEdge = h.edgeInto(c)
        val detached = h.detachRuntimeChild(listOf("tenants"), "c-7f3a")
        val draining = h.extend(lease.id, 1L)
        val again = h.attachRuntimeChild(listOf("tenants"), "c-7f3a", generation = 0L)
        val l = h.snapshot().ledger
        val newEdge = h.edgeInto(c)
        assertAll(
            { assertEquals(listOf(StepRefusal.Draining(c, oldEdge, 2L, listOf(lease))), detached.refusals) },
            { assertEquals(LeaseOutcome.PathDraining(oldEdge), draining) },
            {
                assertEquals(
                    listOf(Prepare::class, Retire::class, Activate::class),
                    again.applied.map { it::class },
                    "the re-attach the replacement detector matches: ${again.applied}",
                )
            },
            { assertEquals(emptyList(), again.refusals) },
            { assertEquals(Lifecycle.RETIRED, l.lifecycle(oldEdge)) },
            { assertEquals(l.lineageOf(c), h.capturedPath(lease.id)) },
            { assertEquals(newEdge, h.capturedPath(lease.id)?.last()) },
            { assertEquals(LeaseOutcome.Done(0L, 0L), h.extend(lease.id, 1L), "it can grow again") },
            { assertEquals(emptyList(), l.validate()) },
            { assertEquals(LeaseOutcome.Done(3L, 0L), h.complete(lease.id, 3L)) },
        )
    }

    /** The declared form of the same shape: a group removed with a lease open, then put back before it drained. */
    @Test
    fun readdMidDrainMovesTheDrainingLeaseOntoTheNewEdge() {
        val h = engine()
        val lease = h.lease(setOf(default), reservation = 2L)
        val removed = h.apply(tree(hobby = false), generation = 1L)
        val back = h.apply(t, generation = 2L)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(listOf(hobby, default), removed.refusals.map { it.child }) },
            { assertEquals(emptyList(), back.refusals) },
            { assertEquals(listOf("root/hobby@1:1#2", "root/hobby/default@1:1#2"), h.capturedPath(lease.id)?.map { it.value }) },
            { assertEquals(emptyList(), l.allEdges().filter { l.lifecycle(it) == Lifecycle.CLOSING }) },
            { assertEquals(emptyList(), l.validate()) },
            { assertEquals(LeaseOutcome.Done(2L, 0L), h.complete(lease.id, 2L)) },
        )
    }

    /**
     * Plan Amendment 6: a removed child with an open lease, under a group the same reload
     * reweights. The removal wins. The lease drains under its old ids, and the reweight waits for
     * it, changing nothing: acme's old edge stays ACTIVE, so `interactive` beside it is still
     * leased. Once the lease completes, the same reload lands.
     */
    @Test
    fun aRemovalUnderAReweightedGroupWins() {
        val h = engine()
        val lease = h.lease(setOf(batch), reservation = 2L)
        val oldAcme = h.edgeInto(acme)
        val batchEdge = h.edgeInto(batch)
        val target = tree(acmeW = 4, batch = false)
        val first = h.apply(target, generation = 1L)
        val l = h.snapshot().ledger
        val beside = h.lease(setOf(interactive), reservation = 2L)
        val pathKept = h.capturedPath(lease.id)
        h.complete(lease.id, 2L)
        h.complete(beside.id, 2L)
        val second = h.apply(target, generation = 1L)
        assertAll(
            {
                assertEquals(
                    listOf(StepRefusal.Draining(acme, oldAcme, 2L, listOf(lease)), StepRefusal.Draining(batch, batchEdge, 2L, listOf(lease))),
                    first.refusals,
                )
            },
            { assertEquals(Lifecycle.ACTIVE, l.lifecycle(oldAcme), "the reweight changed nothing") },
            { assertEquals(Lifecycle.CLOSING, l.lifecycle(batchEdge), "the removal closed batch") },
            { assertEquals(listOf(oldAcme, batchEdge), pathKept) },
            { assertEquals(interactive, beside.leaf) },
            { assertEquals(emptyList(), l.validate()) },
            { assertEquals(emptyList(), second.refusals) },
            { assertEquals("root/acme@4:1#1", h.edgeInto(acme).value) },
            { assertEquals(emptyList(), h.snapshot().ledger.validate()) },
        )
    }

    /**
     * Plan Amendment 9's open item, decided: an overrun charged under a top-level group the reload
     * is removing has no ACTIVE group below the root to land on, and the ledger keeps no spend at
     * the root. It is reported and recorded nowhere. That is accepted: the only counter it could
     * reach belongs to a group on its way out, whose history leaves with it, and conservation is
     * untouched, since nothing was minted or delegated for it.
     */
    @Test
    fun anOverrunUnderARemovedTopLevelGroupIsReportedAndRecordedNowhere() {
        val h = engine()
        val lease = h.lease(setOf(s), reservation = 2L)
        val target = tree(s = false)
        val removing = h.apply(target, generation = 1L)
        val before = h.snapshot().ledger
        // An overrun far above what the root holds: were it delegated anywhere, the shortfall would be minted.
        val done = h.complete(lease.id, 1_000L)
        val after = h.snapshot().ledger
        val retired = h.apply(target, generation = 1L)
        assertAll(
            { assertTrue(before.holdings(t.groupId(), LOCAL) < 998L, "the rig: the root cannot cover the overrun") },
            { assertEquals(listOf(s), removing.refusals.map { it.child }) },
            { assertEquals(LeaseOutcome.Done(charged = 1_000L, overrun = 998L), done, "reported in full") },
            { assertEquals(before.leafSpentTotal() + 2L, after.leafSpentTotal(), "only the reservation is recorded") },
            { assertEquals(before.mintedTotal(), after.mintedTotal(), "nothing was minted for the overrun") },
            { assertEquals(0, h.forcedMints) },
            { assertEquals(emptyList(), after.validate()) },
            { assertEquals(emptyList(), retired.refusals) },
        )
    }

    /**
     * A group's retire waits on every group below it whose change did not land: a child left out
     * of the unit by an earlier attempt, not only one stopped in this one. Here `default`'s change is
     * refused outright (an activate of an edge the ledger does not know) after `hobby`'s retire has
     * run, so the first attempt leaves only `default` out. In the next attempt `default` is not
     * stopped, only left out, and its edge stays ACTIVE: retiring `hobby` above it would leave that
     * edge under a retired one, so `hobby` is left out too, untouched. No diff writes such a list,
     * so it goes in through the test seam.
     */
    @Test
    fun aRetireWaitsOnAChildLeftOutBelowIt() {
        val h = engine()
        val hobbyEdge = h.edgeInto(hobby)
        val defaultEdge = h.edgeInto(default)
        val bogus = AttachmentId("root/hobby/default@9:1#9")
        val steps = listOf(
            TopologyStep.Close(hobbyEdge, hobby),
            TopologyStep.Close(defaultEdge, default),
            Retire(hobbyEdge, hobby),
            Activate(bogus, default),
        )
        val before = h.snapshot().ledger
        val result = h.applyStepsForTest(steps)
        val l = h.snapshot().ledger
        assertAll(
            { assertEquals(listOf(hobby, default), result.refusals.map { it.child }) },
            { assertTrue(result.refusals.all { it is StepRefusal.Refused }, "neither waits on a lease: ${result.refusals}") },
            { assertEquals(Lifecycle.ACTIVE, l.lifecycle(hobbyEdge), "hobby is untouched") },
            { assertEquals(Lifecycle.ACTIVE, l.lifecycle(defaultEdge), "default is untouched") },
            { assertSame(before, l) },
            { assertEquals(emptyList(), l.validate()) },
        )
    }
}
