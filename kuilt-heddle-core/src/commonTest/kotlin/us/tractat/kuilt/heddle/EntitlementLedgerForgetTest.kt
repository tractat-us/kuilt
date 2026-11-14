package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [EntitlementLedger.forget] (#3192, design §5.5): a retired leaf edge's slots leave the ledger and
 * what they added up to stays, folded, so no reader that counted the edge can tell it went.
 */
class EntitlementLedgerForgetTest {

    // ── one edge, by hand ───────────────────────────────────────────────────────────────────────

    private val root = GroupId("root")
    private val c = GroupId("root/c")
    private val d = GroupId("root/c/d")
    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")
    private val e = AttachmentId("root/c@1:1#3")
    private val e2 = AttachmentId("root/c@1:1#4")

    private fun counter(vararg slots: Pair<ReplicaId, Long>): GCounter = GCounter.of(*slots)

    /**
     * `root → c` over [e] at generation 3, RETIRED: [r1] was issued 5 and spent all 5 at `c`, out of
     * 10 minted at the root. Its books closed, so it is forgettable; each case below breaks one thing.
     */
    private fun closed(
        records: Map<AttachmentId, Set<AttachmentRecord>> = mapOf(e to setOf(AttachmentRecord(e, root, c, Weight.ONE))),
        issued: Map<AttachmentId, GCounter> = mapOf(e to counter(r1 to 5L)),
        returned: Map<AttachmentId, GCounter> = emptyMap(),
        leafSpent: Map<AttachmentId, GCounter> = mapOf(e to counter(r1 to 5L)),
        lifecycle: Map<AttachmentId, Lifecycle> = mapOf(e to Lifecycle.RETIRED),
        leafRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
        transfers: Map<PathKey, Map<ReplicaId, GCounter>> = emptyMap(),
        gauges: Map<AttachmentId, Gauge> = emptyMap(),
    ): EntitlementLedger = EntitlementLedger.of(
        records = records,
        minted = mapOf(MintId("m") to MintRecord(root, r1, 10L)),
        issued = issued,
        returned = returned,
        leafSpent = leafSpent,
        lifecycle = lifecycle,
        leafRelocOut = leafRelocOut,
        transfers = transfers,
        gauges = gauges,
    )

    @Test
    fun aClosedRetiredLeafEdgeIsForgottenAndItsBooksStay() {
        val before = closed()
        val after = checkNotNull(before.forget(listOf(e)))
        assertAll(
            { assertNull(before.forgetRefusal(e), "the rig: the fixture is forgettable") },
            { assertEquals(5L, before.holdings(root, r1), "the rig: the root holds what the child did not take") },
            { assertEquals(emptySet(), after.allEdges(), "the edge is gone from every component") },
            { assertEquals(before.holdings(root, r1), after.holdings(root, r1)) },
            { assertEquals(5L, after.leafSpentTotal(), "its spend still counts for conservation") },
            { assertEquals(3L, after.generationFloor(), "its generation is still spent") },
            { assertEquals(4L, effectiveGeneration(0L, after)) },
            { assertTrue(after.hasChildEdges(root), "the root still has had a child") },
            { assertEquals(emptyList(), after.validate()) },
            { assertTrue(after.hasForgotten()) },
            { assertSame(before, before.forget(emptyList()), "forgetting nothing is the ledger itself") },
        )
    }

    /**
     * Every check [EntitlementLedger.forgetRefusal] makes, each tripped on its own: the fixture is
     * [closed] with one thing broken, and the refusal names that thing, so a case cannot pass by
     * tripping an earlier check than the one it was built for.
     */
    @Test
    fun eachIneligibleEdgeIsRefusedForItsOwnReason() {
        val divergent = setOf(AttachmentRecord(e, root, c, Weight.ONE), AttachmentRecord(e, root, GroupId("root/x"), Weight.ONE))
        val grandchild = AttachmentId("root/c/d@1:1#1")
        val withGrandchild = mapOf(
            e to setOf(AttachmentRecord(e, root, c, Weight.ONE)),
            grandchild to setOf(AttachmentRecord(grandchild, c, d, Weight.ONE)),
        )
        val liveSecond = mapOf(
            e to setOf(AttachmentRecord(e, root, c, Weight.ONE)),
            e2 to setOf(AttachmentRecord(e2, root, c, Weight.ONE)),
        )
        val x = AttachmentId("x")
        val cases = listOf(
            Triple("an edge nothing names", closed(), AttachmentId("root/z@1:1#0")) to ForgetRefusal.UNKNOWN,
            Triple(
                "a closed edge outside the topology grammar",
                closed(
                    records = mapOf(x to setOf(AttachmentRecord(x, root, c, Weight.ONE))),
                    issued = mapOf(x to counter(r1 to 5L)),
                    leafSpent = mapOf(x to counter(r1 to 5L)),
                    lifecycle = mapOf(x to Lifecycle.RETIRED),
                ),
                x,
            ) to ForgetRefusal.NOT_IN_GRAMMAR,
            Triple("two records", closed(records = mapOf(e to divergent)), e) to ForgetRefusal.NOT_ONE_RECORD,
            Triple("an active edge", closed(lifecycle = emptyMap()), e) to ForgetRefusal.LIVE,
            Triple("a closing edge", closed(lifecycle = mapOf(e to Lifecycle.CLOSING)), e) to ForgetRefusal.LIVE,
            Triple("a child with a child edge", closed(records = withGrandchild, lifecycle = mapOf(e to Lifecycle.RETIRED, grandchild to Lifecycle.RETIRED)), e) to
                ForgetRefusal.CHILD_HAS_CHILD_EDGES,
            Triple("a transfer row at its path", closed(transfers = mapOf(PathKey.of(e) to mapOf(r1 to counter(r2 to 1L)))), e) to ForgetRefusal.TRANSFER_ROWS,
            Triple("a prepared edge that was issued", closed(lifecycle = mapOf(e to Lifecycle.PREPARED)), e) to ForgetRefusal.PREPARED_CARRIES_HISTORY,
            Triple("a prepared edge with a gauge", closed(issued = emptyMap(), leafSpent = emptyMap(), lifecycle = mapOf(e to Lifecycle.PREPARED), gauges = mapOf(e to Gauge(Rational.ZERO, 0L))), e) to
                ForgetRefusal.PREPARED_CARRIES_HISTORY,
            Triple("more charged than issued", closed(leafSpent = mapOf(e to counter(r1 to 6L))), e) to ForgetRefusal.PER_EDGE_SAFETY,
            Triple("a negative effective spend", closed(issued = emptyMap(), leafSpent = emptyMap(), leafRelocOut = mapOf(e to counter(r1 to 1L))), e) to
                ForgetRefusal.NEGATIVE_EFFECTIVE_SPEND,
            Triple("a fold negative for one replica", oneReplicaNegative(), e) to ForgetRefusal.NEGATIVE_FOLD,
            Triple("units outstanding", closed(leafSpent = mapOf(e to counter(r1 to 3L))), e) to ForgetRefusal.OUTSTANDING,
            Triple("a strand that nets to zero", closed(leafSpent = mapOf(e to counter(r2 to 5L))), e) to ForgetRefusal.STRANDED,
            Triple(
                "a child live under another edge",
                closed(records = liveSecond, issued = mapOf(e to counter(r1 to 5L), e2 to counter(r1 to 3L)), lifecycle = mapOf(e to Lifecycle.RETIRED)),
                e,
            ) to ForgetRefusal.CHILD_HOLDS,
        )
        assertAll(
            *cases.map { (case, expected) ->
                val (name, ledger, edge) = case
                val check: () -> Unit = {
                    assertEquals(expected, ledger.forgetRefusal(edge), name)
                    assertNull(ledger.forget(listOf(edge)), "$name: forget refuses")
                }
                check
            }.toTypedArray(),
            { assertNull(closed(leafSpent = mapOf(e to counter(r1 to 3L))).forget(listOf(e, AttachmentId("root/z@1:1#0"))), "one refusal refuses the lot") },
        )
    }

    /** A PREPARED edge that never carried anything folds its generation and nothing else. */
    @Test
    fun anUnusedPreparedEdgeMovesOnlyTheFloor() {
        val before = closed(issued = emptyMap(), leafSpent = emptyMap(), lifecycle = mapOf(e to Lifecycle.PREPARED))
        val after = checkNotNull(before.forget(listOf(e)))
        assertAll(
            { assertNull(before.forgetRefusal(e), "the rig: forgettable") },
            { assertEquals(emptySet(), after.allEdges()) },
            { assertEquals(3L, after.generationFloor()) },
            { assertEquals(10L, after.holdings(root, r1)) },
            { assertEquals(0L, after.leafSpentTotal()) },
            { assertTrue(after.hasChildEdges(root), "the root still counts as having had a child") },
        )
    }

    /**
     * Two replicas whose totals close but whose own books do not: [r1] returned 1 it was never
     * issued and moved 1 of leaf spend it never spent away, while [r2] was issued 1 and spent it.
     * Per edge, issued 1 = returned 1 + spent 0, nothing is outstanding and no total is negative.
     * Per replica, [r1]'s net inflow and effective leaf spend are both `-1`, which is what its fold
     * would hold.
     */
    private fun oneReplicaNegative(): EntitlementLedger = closed(
        issued = mapOf(e to counter(r2 to 1L)),
        returned = mapOf(e to counter(r1 to 1L)),
        leafSpent = mapOf(e to counter(r2 to 1L)),
        leafRelocOut = mapOf(e to counter(r1 to 1L)),
    )

    /**
     * The fold is kept per replica, so an edge whose totals pass every check but one of whose
     * replicas would fold a negative amount is refused, not folded into a `-1` for that replica.
     */
    @Test
    fun anEdgeThatWouldFoldNegativeForOneReplicaIsRefused() {
        val ledger = oneReplicaNegative()
        val summary = checkNotNull(ledger.edge(e))
        assertAll(
            { assertEquals(listOf(1L, 1L, 0L, 0L), listOf(summary.issued, summary.returned, summary.spent, summary.outstanding), "the rig: the totals close") },
            {
                val netInflow = ledger.effectiveIssued(e, r1) - ledger.storedSlot(CounterFamily.RETURNED, e, r1)
                assertEquals(-1L, netInflow, "the rig: r1's own net inflow is negative")
            },
            { assertEquals(ForgetRefusal.NEGATIVE_FOLD, ledger.forgetRefusal(e)) },
            { assertNull(ledger.forget(listOf(e))) },
        )
    }

    /** A negative fold cannot be held at all, so no ledger, built by hand or decoded, carries one. */
    @Test
    fun aNegativeFoldCannotBeBuilt() {
        assertAll(
            { assertFailsWith<IllegalArgumentException>("outflow") { EntitlementLedger.of(forgottenOutflow = mapOf(root to mapOf(r1 to -1L))) } },
            { assertFailsWith<IllegalArgumentException>("leaf spend") { EntitlementLedger.of(forgottenLeafSpend = mapOf(r1 to -1L)) } },
            { assertFailsWith<IllegalArgumentException>("floor") { EntitlementLedger.of(forgottenGenerationFloor = -2L) } },
            {
                val honest = EntitlementLedger.of(forgottenOutflow = mapOf(root to mapOf(r1 to 0L)), forgottenLeafSpend = mapOf(r1 to 0L), forgottenGenerationFloor = 3L)
                assertTrue(honest.hasForgotten(), "the control: a fold at zero, of the same shape, is held")
            },
        )
    }

    /**
     * A prepare of an id at or below the forgotten floor is refused where it is asked, with `null`,
     * rather than handed back as a patch that the resurrection guard in [EntitlementLedger.piece]
     * then throws on. The ledger cannot tell a forgotten id from one it never held, so the floor
     * refuses both. An id outside the topology grammar has no generation, and no floor names it.
     */
    @Test
    fun aPrepareAtOrBelowTheForgottenFloorIsRefused() {
        val forgot = checkNotNull(closed().forget(listOf(e)))
        val child = GroupId("root/n")
        fun prepareAt(generation: Long) = forgot.prepare(AttachmentRecord(attachmentIdOf(child, Weight.ONE, generation), root, child, Weight.ONE))
        val outsideGrammar = AttachmentId("root>n")
        assertAll(
            { assertEquals(3L, forgot.forgottenGenerationFloor(), "the rig: the floor is the forgotten edge's generation") },
            { assertNull(forgot.prepare(AttachmentRecord(e, root, c, Weight.ONE)), "the forgotten id itself") },
            { assertNull(prepareAt(3L), "another id at the floor") },
            { assertNull(prepareAt(0L), "an id below it") },
            { assertTrue(prepareAt(4L) != null, "the control: the id above the floor is prepared") },
            { assertTrue(forgot.prepare(AttachmentRecord(outsideGrammar, root, child, Weight.ONE)) != null, "an id with no generation") },
            { assertTrue(closed().prepare(AttachmentRecord(attachmentIdOf(child, Weight.ONE, 0L), root, child, Weight.ONE)) != null, "a ledger that forgot nothing has no floor") },
        )
    }

    // ── the join refuses what a second writer would produce ───────────────────────────────────

    @Test
    fun theJoinRefusesTwoFoldsAndAResurrectedEdge() {
        val other = AttachmentId("root/o@1:1#5")
        val two = closed(
            records = mapOf(e to setOf(AttachmentRecord(e, root, c, Weight.ONE)), other to setOf(AttachmentRecord(other, root, GroupId("root/o"), Weight.ONE))),
            issued = mapOf(e to counter(r1 to 5L), other to counter(r1 to 2L)),
            leafSpent = mapOf(e to counter(r1 to 5L), other to counter(r1 to 2L)),
            lifecycle = mapOf(e to Lifecycle.RETIRED, other to Lifecycle.RETIRED),
        )
        val forgotE = checkNotNull(two.forget(listOf(e)))
        val forgotOther = checkNotNull(two.forget(listOf(other)))
        val later = AttachmentId("root/n@1:1#6")
        val fresh = checkNotNull(forgotE.prepare(AttachmentRecord(later, root, GroupId("root/n"), Weight.ONE))).delta
        assertAll(
            { assertNotEquals(forgotE, forgotOther, "the rig: two different folds") },
            { assertFailsWith<IllegalArgumentException> { forgotE.piece(forgotOther) } },
            {
                // Two folds and not one edge between them, so no floor can be crossed: only the fold
                // comparison itself tells these two apart.
                val one = EntitlementLedger.of(forgottenLeafSpend = mapOf(r1 to 1L))
                val another = EntitlementLedger.of(forgottenLeafSpend = mapOf(r1 to 2L))
                assertFailsWith<IllegalArgumentException>("two folds, and no edge resurrected") { one.piece(another) }
            },
            { assertFailsWith<IllegalArgumentException>("the edge it forgot, back from a ledger that kept it") { forgotE.piece(two) } },
            { assertFailsWith<IllegalArgumentException>("a ledger that never forgot takes no fold") { two.piece(forgotE) } },
            { assertEquals(forgotE, forgotE.piece(forgotE), "the same fold joins to itself") },
            {
                // Nothing on the left to resurrect, so only the fold check refuses this (§5.5: `piece`
                // refuses a delta that carries a non-empty `forgotten`).
                assertFailsWith<IllegalArgumentException>("an empty ledger adopts no fold") { EntitlementLedger.ZERO.piece(forgotE) }
            },
            { assertEquals(forgotE, forgotE.piece(EntitlementLedger.ZERO), "the forgetting ledger keeps its own fold") },
            { assertEquals(setOf(other, later), forgotE.piece(fresh).allEdges(), "an edge above the floor is accepted") },
            { assertTrue(forgotE.piece(fresh).hasForgotten(), "and the fold is kept") },
        )
    }

    // ── on an engine's real histories ──────────────────────────────────────────────────────────

    private val tree = Topology(
        "root",
        listOf(
            Topology.Lane("a"),
            Topology.Group("tenants", Weight.ONE, emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.ONE)),
            Topology.Group("pool", Weight.of(2), emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.of(3, 2))),
        ),
    )
    private val marked = listOf(listOf("tenants"), listOf("pool"))
    private val names = listOf("t0", "t1", "t2", "t3")
    private val policy = LocalPolicy(defaultReservation = 2L)

    /** What the test knows of one engine: which runtime children it attached, in attach order. */
    private class History(val h: LocalHeddle, val attached: MutableList<Pair<List<String>, String>> = ArrayList())

    private fun leasable(history: History): Set<GroupId> =
        setOf(tree.groupId("a")) + history.attached.map { (g, n) -> tree.runtimeChildId(g, n) }

    /** One random step: attach, detach, acquire, complete, extend or cancel. */
    private fun step(history: History, r: Random, at: Long): Any {
        val h = history.h
        val open = h.snapshot().leases
        return when (r.nextInt(10)) {
            0, 1 -> {
                val child = marked[r.nextInt(marked.size)] to names[r.nextInt(names.size)]
                val out = h.attachRuntimeChild(child.first, child.second, generation = r.nextLong(0L, 3L))
                if (child !in history.attached) history.attached += child
                out
            }
            2 -> if (history.attached.isEmpty()) "idle" else {
                val (g, n) = history.attached.removeAt(r.nextInt(history.attached.size))
                h.detachRuntimeChild(g, n)
            }
            3, 4, 5 -> {
                val all = leasable(history)
                h.acquire(all, all.filterTo(HashSet()) { r.nextBoolean() }.ifEmpty { all }, at, 1L + r.nextInt(3))
            }
            6 -> if (open.isEmpty()) "idle" else h.extend(open[r.nextInt(open.size)].id, 1L + r.nextInt(2))
            7 -> if (open.isEmpty()) "idle" else h.cancel(open[r.nextInt(open.size)].id)
            else -> if (open.isEmpty()) "idle" else h.complete(open[r.nextInt(open.size)].id, r.nextLong(0L, 6L))
        }
    }

    private class Tally {
        var forgotten = 0
        var foldedSpend = 0
        var checkpoints = 0
        var lockstepSteps = 0
        var edgesPreparedAfterAForget = 0
    }

    /**
     * The property: on real engine histories, forgetting any set of forgettable edges preserves every
     * group's holdings for every replica, `leafSpentTotal`, `mintedTotal`, the generation floor,
     * `hasChildEdges`, every other edge's summary and lifecycle, and a clean `validate()`. Then an
     * engine restored from the forgotten ledger runs in lockstep with one restored from the whole
     * ledger: the same calls give the same outcomes, holdings and totals, and a runtime child
     * attached again gets an edge above every generation either ever held.
     *
     * The draws walk sorted lists, so the trajectory is the same on every target (#2592).
     */
    @Test
    fun forgettingIsInvisibleToEveryReaderAndToAnEngine() {
        val tally = Tally()
        for (seed in 0 until 12) {
            val r = Random(seed)
            val history = History(LocalHeddle.fromTopology(tree, policy, epoch = 0L).also { it.reconcileCapacity(4L) })
            repeat(6) { round ->
                repeat(40) { step(history, r, at = round * 100L + it) }
                checkpoint(history, r, tally, "seed $seed round $round")
            }
        }
        assertAll(
            { assertEquals(72, tally.checkpoints, "the rig: every checkpoint ran") },
            { assertTrue(tally.forgotten >= 300, "the rig: edges forgotten ${tally.forgotten}") },
            { assertTrue(tally.foldedSpend >= 120, "the rig: forgotten edges that had spent something ${tally.foldedSpend}") },
            { assertTrue(tally.lockstepSteps >= 55 * 30, "the rig: lockstep steps ${tally.lockstepSteps}") },
            { assertTrue(tally.edgesPreparedAfterAForget >= 100, "the rig: edges prepared after a forget ${tally.edgesPreparedAfterAForget}") },
        )
    }

    private fun checkpoint(history: History, r: Random, tally: Tally, label: String) {
        tally.checkpoints++
        val snapshot = history.h.persist()
        val before = snapshot.ledger
        val candidates = before.allEdges().sorted().filter { before.forgetRefusal(it) == null }
        val chosen = candidates.filter { r.nextInt(4) != 0 }
        val after = checkNotNull(before.forget(chosen)) { "$label: forget refused edges forgetRefusal accepted" }
        tally.forgotten += chosen.size
        tally.foldedSpend += chosen.count { (before.edge(it)?.spent ?: 0L) > 0L }

        val groups = before.allGroups().sorted()
        val replicas = before.allReplicas().sorted()
        val kept = before.allEdges().sorted() - chosen.toSet()
        assertAll(
            { assertEquals(emptyList(), before.validate(), "$label: the rig: an honest engine's ledger") },
            { assertEquals(kept, after.allEdges().sorted(), "$label: exactly the chosen edges are gone") },
            { assertEquals(groups.map { g -> replicas.map { before.holdings(g, it) } }, groups.map { g -> replicas.map { after.holdings(g, it) } }, "$label: holdings") },
            { assertEquals(before.leafSpentTotal(), after.leafSpentTotal(), "$label: leafSpentTotal") },
            { assertEquals(before.mintedTotal(), after.mintedTotal(), "$label: mintedTotal") },
            { assertEquals(before.generationFloor(), after.generationFloor(), "$label: generation floor") },
            { assertEquals(groups.map(before::hasChildEdges), groups.map(after::hasChildEdges), "$label: hasChildEdges") },
            { assertEquals(kept.map { before.edge(it) to before.lifecycle(it) }, kept.map { after.edge(it) to after.lifecycle(it) }, "$label: every other edge") },
            { assertEquals(emptyList(), after.validate(), "$label: validate") },
        )
        if (chosen.isEmpty()) return
        lockstep(history, snapshot, after, r, tally, label)
    }

    /** Engines restored from the whole and the forgotten ledger, driven by one seed: they must not differ. */
    private fun lockstep(history: History, snapshot: LocalHeddleSnapshot, forgotten: EntitlementLedger, r: Random, tally: Tally, label: String) {
        val folded = LocalHeddleSnapshot(snapshot.epoch, forgotten, snapshot.allocatorMemory, snapshot.totalSlots, snapshot.open, snapshot.lastApplied)
        val epoch = snapshot.epoch + 1
        val whole = History(LocalHeddle.restore(snapshot, policy, epoch), ArrayList(history.attached))
        val subject = History(LocalHeddle.restore(folded, policy, epoch), ArrayList(history.attached))
        val seed = r.nextLong()
        val rw = Random(seed)
        val rs = Random(seed)
        val floor = snapshot.ledger.generationFloor()
        repeat(30) {
            val at = 10_000L + it
            assertEquals(step(whole, rw, at), step(subject, rs, at), "$label: lockstep step $it")
            tally.lockstepSteps++
        }
        val wl = whole.h.snapshot().ledger
        val sl = subject.h.snapshot().ledger
        val groups = wl.allGroups().sorted()
        val newEdges = sl.allEdges().filter { it !in snapshot.ledger.allEdges() }
        tally.edgesPreparedAfterAForget += newEdges.count { generationOf(it) != null }
        assertAll(
            { assertEquals(groups.map { wl.holdings(it, LOCAL) }, groups.map { sl.holdings(it, LOCAL) }, "$label: lockstep holdings") },
            { assertEquals(wl.leafSpentTotal(), sl.leafSpentTotal(), "$label: lockstep leafSpentTotal") },
            { assertEquals(wl.mintedTotal(), sl.mintedTotal(), "$label: lockstep mintedTotal") },
            { assertEquals(whole.h.snapshot().leases, subject.h.snapshot().leases, "$label: lockstep leases") },
            { assertTrue(newEdges.all { (generationOf(it) ?: Long.MAX_VALUE) > floor }, "$label: new edges above the floor $floor: $newEdges") },
            { assertEquals(emptyList(), sl.validate(), "$label: lockstep validate") },
        )
    }
}
