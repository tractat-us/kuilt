package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.TopologyStep.Activate
import us.tractat.kuilt.heddle.TopologyStep.Close
import us.tractat.kuilt.heddle.TopologyStep.Prepare
import us.tractat.kuilt.heddle.TopologyStep.Reconcile
import us.tractat.kuilt.heddle.TopologyStep.Retire
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TopologyDiffTest {

    private val acme = GroupId("root/acme")
    private val batch = GroupId("root/acme/batch")
    private val interactive = GroupId("root/acme/interactive")
    private val hobby = GroupId("root/hobby")
    private val hobbyDefault = GroupId("root/hobby/default")

    /** The tree in `docs/heddle-design.md` §11.1, with the weights a test changes as parameters. */
    private fun fairness(acmeWeight: Long = 3, batchWeight: Long = 1, hobbyWeight: Long = 1) = Topology(
        "root",
        listOf(
            Group("acme", Weight.of(acmeWeight), listOf(Lane("interactive", Weight.of(3)), Lane("batch", Weight.of(batchWeight)))),
            Group("hobby", Weight.of(hobbyWeight), listOf(Lane("default"))),
        ),
    )

    private fun e(id: String) = AttachmentId(id)

    private fun record(id: String, child: GroupId, weight: Long) =
        AttachmentRecord(e(id), GroupId(child.value.substringBeforeLast('/')), child, Weight.of(weight))

    /** Seven units served at `batch` and charged, so every edge on its path is drained but has a history. */
    private fun served(ledger: EntitlementLedger): EntitlementLedger {
        val delegated = assertNotNull(delegateDown(ledger, batch, 7))
        return delegated.piece(assertNotNull(delegated.spend(BOOT_HOLDER, batch, 7)))
    }

    /** Four units handed down to `batch` and not yet spent or returned: `acme` and `batch` cannot retire. */
    private fun busy(ledger: EntitlementLedger): EntitlementLedger = assertNotNull(delegateDown(ledger, batch, 4))

    @Test
    fun addingALaneIsPrepareThenActivate() {
        val ledger = bootFrom(fairness())
        val target = Topology(
            "root",
            fairness().children.map { if (it.name == "acme") (it as Group).copy(children = it.children + Lane("c-7f3a")) else it },
        )
        val lane = GroupId("root/acme/c-7f3a")
        val steps = Topology.diff(target, ledger, generation = 1)
        val applied = applyAll(steps, ledger)
        assertAll(
            { assertEquals(listOf(Prepare(record("root/acme/c-7f3a@1:1#1", lane, 1)), Activate(e("root/acme/c-7f3a@1:1#1"), lane)), steps) },
            { assertEquals(bootFrom(target).activeTriples(), applied.ledger.activeTriples()) },
            { assertEquals(emptyList(), applied.ledger.validate()) },
        )
    }

    @Test
    fun removingAGroupClosesTheSubtreeAndRetiresBottomUp() {
        val ledger = bootFrom(fairness())
        val target = Topology("root", listOf(Group("hobby", Weight.ONE, listOf(Lane("default")))))
        val steps = Topology.diff(target, ledger, generation = 0)
        val applied = applyAll(steps, ledger)
        assertAll(
            {
                assertEquals(
                    listOf(
                        Close(e("root/acme@3:1#0"), acme),
                        Close(e("root/acme/batch@1:1#0"), batch),
                        Close(e("root/acme/interactive@3:1#0"), interactive),
                        Retire(e("root/acme/batch@1:1#0"), batch),
                        Retire(e("root/acme/interactive@3:1#0"), interactive),
                        Retire(e("root/acme@3:1#0"), acme),
                    ),
                    steps,
                )
            },
            { assertEquals(bootFrom(target).activeTriples(), applied.ledger.activeTriples()) },
            { assertEquals(Lifecycle.RETIRED, applied.ledger.lifecycle(e("root/acme@3:1#0"))) },
        )
    }

    @Test
    fun aLeafReweightIsPrepareCloseRetireActivate() {
        val ledger = bootFrom(fairness())
        val target = fairness(batchWeight = 2)
        val steps = Topology.diff(target, ledger, generation = 1)
        val applied = applyAll(steps, ledger)
        assertAll(
            {
                assertEquals(
                    listOf(
                        Prepare(record("root/acme/batch@2:1#1", batch, 2)),
                        Close(e("root/acme/batch@1:1#0"), batch),
                        Retire(e("root/acme/batch@1:1#0"), batch),
                        Activate(e("root/acme/batch@2:1#1"), batch),
                    ),
                    steps,
                )
            },
            { assertEquals(bootFrom(target).activeTriples(), applied.ledger.activeTriples()) },
            { assertEquals(emptyList(), applied.ledger.validate()) },
        )
    }

    @Test
    fun internalReweightEmitsReconcileAndEndsClean() {
        val ledger = served(bootFrom(fairness()))
        val target = fairness(acmeWeight = 4)
        val steps = Topology.diff(target, ledger, generation = 1)
        val applied = applyAll(steps, ledger)
        // The rig: without the reconcile the strand is real, and the ledger says so.
        val unreconciled = applyAll(steps.filter { it !is Reconcile }, ledger).ledger
        assertAll(
            {
                assertEquals(
                    listOf(
                        Prepare(record("root/acme@4:1#1", acme, 4)),
                        Close(e("root/acme@3:1#0"), acme),
                        Retire(e("root/acme@3:1#0"), acme),
                        Activate(e("root/acme@4:1#1"), acme),
                        Reconcile(acme),
                    ),
                    steps,
                )
            },
            { assertEquals(-7L, unreconciled.holdings(acme, BOOT_HOLDER)) },
            { assertTrue(unreconciled.validate().any { it is LedgerConflict.PersistentNegativeHoldings }) },
            { assertEquals(listOf(acme), applied.reconciled) },
            { assertEquals(emptyList(), applied.ledger.validate()) },
            { assertEquals(0L, applied.ledger.holdings(acme, BOOT_HOLDER)) },
            { assertEquals(emptyList(), Topology.diff(target, applied.ledger, generation = 1)) },
        )
    }

    @Test
    fun revertNeverReusesAnId() {
        var ledger = bootFrom(fairness())
        val prepared = ArrayList<AttachmentId>()
        for (weight in listOf(4L, 3L)) {
            val steps = Topology.diff(fairness(acmeWeight = weight), ledger, generation = 0)
            prepared += steps.filterIsInstance<Prepare>().map { it.record.id }
            ledger = applyAll(steps, ledger).ledger
        }
        assertAll(
            { assertEquals(listOf(e("root/acme@4:1#1"), e("root/acme@3:1#2")), prepared) },
            { assertEquals(listOf(e("root/acme@3:1#2")), ledger.liveInto(acme)) },
            { assertEquals(Lifecycle.RETIRED, ledger.lifecycle(e("root/acme@3:1#0"))) },
        )
    }

    @Test
    fun diffOfAReachedStateIsEmpty() {
        val reweighted = applyAll(Topology.diff(fairness(acmeWeight = 4), served(bootFrom(fairness())), 0), served(bootFrom(fairness())))
        assertAll(
            { assertEquals(emptyList(), Topology.diff(fairness(), bootFrom(fairness()), generation = 0)) },
            { assertEquals(emptyList(), Topology.diff(fairness(), bootFrom(fairness(), generation = 5), generation = 0)) },
            { assertEquals(emptyList(), Topology.diff(fairness(acmeWeight = 4), reweighted.ledger, generation = 9)) },
        )
    }

    /**
     * Every phase in one save, from a ledger with history: a lane and a group added, `hobby`
     * removed, `acme` (internal, served) and `batch` (a leaf, served) reweighted.
     */
    private fun goldenTarget() = Topology(
        "root",
        listOf(
            Group("acme", Weight.of(4), listOf(Lane("interactive", Weight.of(3)), Lane("batch", Weight.of(2)), Lane("c-7f3a"))),
            Group("n", Weight.ONE, listOf(Lane("default"))),
        ),
    )

    @Test
    fun diffStepOrderIsGolden() {
        val ledger = served(bootFrom(fairness()))
        val lane = GroupId("root/acme/c-7f3a")
        val n = GroupId("root/n")
        val nDefault = GroupId("root/n/default")
        val steps = Topology.diff(goldenTarget(), ledger, generation = 1)
        val applied = applyAll(steps, ledger)
        assertAll(
            {
                assertEquals(
                    listOf(
                        Prepare(record("root/acme@4:1#1", acme, 4)),
                        Prepare(record("root/n@1:1#1", n, 1)),
                        Prepare(record("root/acme/batch@2:1#1", batch, 2)),
                        Prepare(record("root/acme/c-7f3a@1:1#1", lane, 1)),
                        Prepare(record("root/n/default@1:1#1", nDefault, 1)),
                        Activate(e("root/n@1:1#1"), n),
                        Activate(e("root/acme/c-7f3a@1:1#1"), lane),
                        Activate(e("root/n/default@1:1#1"), nDefault),
                        Close(e("root/acme@3:1#0"), acme),
                        Close(e("root/hobby@1:1#0"), hobby),
                        Close(e("root/acme/batch@1:1#0"), batch),
                        Close(e("root/hobby/default@1:1#0"), hobbyDefault),
                        Retire(e("root/acme/batch@1:1#0"), batch),
                        Retire(e("root/hobby/default@1:1#0"), hobbyDefault),
                        Retire(e("root/acme@3:1#0"), acme),
                        Retire(e("root/hobby@1:1#0"), hobby),
                        Activate(e("root/acme@4:1#1"), acme),
                        Activate(e("root/acme/batch@2:1#1"), batch),
                        Reconcile(acme),
                    ),
                    steps,
                )
            },
            { assertEquals(emptyList(), applied.refused) },
            { assertEquals(bootFrom(goldenTarget()).activeTriples(), applied.ledger.activeTriples()) },
            { assertEquals(emptyList(), applied.ledger.validate()) },
        )
    }

    @Test
    fun diffAfterPartialApplyReturnsOnlyTheRemainder() {
        val ledger = served(bootFrom(fairness()))
        val steps = Topology.diff(goldenTarget(), ledger, generation = 1)
        // Every cut, including the one between a retire and its replacement's activation.
        assertAll(
            *(0..steps.size).map { cut ->
                {
                    val partial = applyAll(steps.take(cut), ledger)
                    assertEquals(emptyList(), partial.refused, "cut $cut")
                    assertEquals(steps.drop(cut), Topology.diff(goldenTarget(), partial.ledger, generation = 1), "cut $cut")
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun aPrepareThatLandedOutOfBandIsAdopted() {
        val booted = bootFrom(fairness())
        val landed = booted.piece(assertNotNull(booted.prepare(record("root/acme/batch@2:1#5", batch, 2))))
        val steps = Topology.diff(fairness(batchWeight = 2), landed, generation = 1)
        assertEquals(
            listOf(
                Close(e("root/acme/batch@1:1#0"), batch),
                Retire(e("root/acme/batch@1:1#0"), batch),
                Activate(e("root/acme/batch@2:1#5"), batch),
            ),
            steps,
        )
    }

    @Test
    fun aPrepareNothingWantsIsLeftPrepared() {
        // batch is reweighted while it holds work, so its old edge is left closing and the new one
        // prepared; then the weight goes back before the drain finishes.
        val first = applyAll(Topology.diff(fairness(batchWeight = 2), busy(bootFrom(fairness())), 0), busy(bootFrom(fairness())))
        val back = Topology.diff(fairness(), first.ledger, generation = 0)
        val done = applyAll(back, drainAll(first.ledger))
        assertAll(
            // The rig: an edge closing with work on it, beside a prepared one nothing wants now.
            { assertEquals(listOf(batch), first.refused) },
            { assertEquals(Lifecycle.CLOSING, first.ledger.lifecycle(e("root/acme/batch@1:1#0"))) },
            { assertEquals(Lifecycle.PREPARED, first.ledger.lifecycle(e("root/acme/batch@2:1#1"))) },
            // Closing the prepared edge would give batch two live inbound edges, quarantining it
            // and so stopping the very drain its retire waits for. It is left alone.
            {
                assertEquals(
                    listOf(
                        Prepare(record("root/acme/batch@1:1#2", batch, 1)),
                        Retire(e("root/acme/batch@1:1#0"), batch),
                        Activate(e("root/acme/batch@1:1#2"), batch),
                    ),
                    back,
                )
            },
            { assertEquals(listOf(e("root/acme/batch@1:1#2")), done.ledger.liveInto(batch)) },
            { assertEquals(emptyList(), done.ledger.validate()) },
            { assertEquals(emptyList(), Topology.diff(fairness(), done.ledger, generation = 0)) },
            // And if its weight comes back, it is the edge adopted.
            {
                assertEquals(
                    listOf(
                        Close(e("root/acme/batch@1:1#2"), batch),
                        Retire(e("root/acme/batch@1:1#2"), batch),
                        Activate(e("root/acme/batch@2:1#1"), batch),
                    ),
                    Topology.diff(fairness(batchWeight = 2), done.ledger, generation = 0),
                )
            },
        )
    }

    @Test
    fun differentRootsAreRejected() {
        val ledger = bootFrom(fairness())
        val moved = assertFailsWith<IllegalArgumentException> { Topology.diff(fairness().copy(root = "pool"), ledger, generation = 0) }
        // An unminted ledger names its root only through its edges, and those count too.
        val unminted = applyAll(Topology.diff(fairness(), EntitlementLedger.ZERO, 0), EntitlementLedger.ZERO).ledger
        val bare = assertFailsWith<IllegalArgumentException> { Topology.diff(fairness().copy(root = "pool"), unminted, generation = 0) }
        assertAll(
            { assertTrue("pool" in moved.message.orEmpty() && "root" in moved.message.orEmpty(), moved.message) },
            { assertTrue("pool" in bare.message.orEmpty() && "root" in bare.message.orEmpty(), bare.message) },
            { assertEquals(emptyList(), unminted.mintedRoots()) },
            { assertEquals(emptyList(), Topology.diff(fairness(), unminted, generation = 0)) },
            { assertFailsWith<IllegalArgumentException> { Topology.diff(fairness(), ledger, generation = -1) } },
        )
    }

    @Test
    fun reconcileLeavesARetiredEdgeFromAnotherParentStanding() {
        // Hand-written history the diff never writes: acme hung off hobby for a while, through an
        // edge outside the grammar, and work was charged beneath it.
        val foreign = AttachmentRecord(e("x#1"), hobby, acme, Weight.ONE)
        var ledger = assertNotNull(delegateDown(bootFrom(fairness()), hobby, 5))
        for (patch in listOf(
            { l: EntitlementLedger -> l.prepare(foreign) },
            { l: EntitlementLedger -> l.close(e("root/acme@3:1#0")) },
            { l: EntitlementLedger -> l.retire(e("root/acme@3:1#0")) },
            { l: EntitlementLedger -> l.activate(foreign.id) },
            { l: EntitlementLedger -> l.delegate(BOOT_HOLDER, foreign.id, 3) },
            { l: EntitlementLedger -> l.delegate(BOOT_HOLDER, e("root/acme/batch@1:1#0"), 3) },
            { l: EntitlementLedger -> l.spend(BOOT_HOLDER, batch, 3) },
            { l: EntitlementLedger -> l.close(foreign.id) },
            { l: EntitlementLedger -> l.retire(foreign.id) },
        )) {
            ledger = ledger.piece(assertNotNull(patch(ledger)))
        }
        val applied = applyAll(Topology.diff(fairness(), ledger, generation = 0), ledger)
        val live = applied.ledger.liveInto(acme).single()
        val ungated = applied.ledger.relocationPatch(live, applied.ledger.retiredInboundEdges(acme).associateWith { applied.ledger.baseFinalsOn(it) })
        assertAll(
            // The rig: the foreign strand is real, and moving it would move something.
            { assertEquals(listOf(e("root/acme@3:1#0"), foreign.id), applied.ledger.retiredInboundEdges(acme)) },
            { assertIs<Relocation.Moved>(ungated) },
            // Moving it would credit hobby with three units nobody gave it back.
            { assertEquals(emptyList(), applied.reconciled) },
            { assertIs<Relocation.Nothing>(applied.ledger.reconcileLocally(acme)) },
            { assertEquals(2L, applied.ledger.holdings(hobby, BOOT_HOLDER)) },
        )
    }

    @Test
    fun aGroupTurnedIntoALaneWithAReweightIsReconciled() {
        val delegated = assertNotNull(delegateDown(bootFrom(fairness()), hobbyDefault, 7))
        val ledger = delegated.piece(assertNotNull(delegated.spend(BOOT_HOLDER, hobbyDefault, 7)))
        val target = Topology("root", listOf(fairness().children.first(), Lane("hobby", Weight.of(2))))
        val steps = Topology.diff(target, ledger, generation = 1)
        val applied = applyAll(steps, ledger)
        val unreconciled = applyAll(steps.filter { it !is Reconcile }, ledger).ledger
        assertAll(
            {
                assertEquals(
                    listOf(
                        Prepare(record("root/hobby@2:1#1", hobby, 2)),
                        Close(e("root/hobby@1:1#0"), hobby),
                        Close(e("root/hobby/default@1:1#0"), hobbyDefault),
                        Retire(e("root/hobby/default@1:1#0"), hobbyDefault),
                        Retire(e("root/hobby@1:1#0"), hobby),
                        Activate(e("root/hobby@2:1#1"), hobby),
                        Reconcile(hobby),
                    ),
                    steps,
                )
            },
            // The rig: a lane now, but its retired child edge still counts against it.
            { assertEquals(-7L, unreconciled.holdings(hobby, BOOT_HOLDER)) },
            { assertTrue(unreconciled.validate().any { it is LedgerConflict.PersistentNegativeHoldings }) },
            { assertEquals(listOf(hobby), applied.reconciled) },
            { assertEquals(0L, applied.ledger.holdings(hobby, BOOT_HOLDER)) },
            { assertEquals(emptyList(), applied.ledger.validate()) },
            { assertEquals(emptyList(), Topology.diff(target, applied.ledger, generation = 1)) },
        )
    }

    @Test
    fun diffFromEmptyLedgerReplaysTheTopology() {
        val minted = EntitlementLedger.bootstrap(GroupId("root"), mapOf(BOOT_HOLDER to BOOT_SUPPLY), "boot")
        val lowered = fairness().lower().sortedWith(compareBy({ r -> r.child.value.count { it == '/' } }, { it.child }))
        val steps = Topology.diff(fairness(), minted, generation = 0)
        assertAll(
            { assertEquals(lowered.map { Prepare(it) } + lowered.map { Activate(it.id, it.child) }, steps) },
            { assertEquals(bootFrom(fairness()), applyAll(steps, minted).ledger) },
            { assertEquals(steps, Topology.diff(fairness(), EntitlementLedger.ZERO, generation = 0)) },
        )
    }

    @Test
    fun edgesTheDiffDoesNotOwnAreLeftAlone() {
        var ledger = bootFrom(fairness())
        val foreign = listOf(
            AttachmentRecord(e("e#99"), GroupId("root"), GroupId("elsewhere"), Weight.ONE),
            record("root/acme/!x@1:1#0", GroupId("root/acme/!x"), 1),
            record("pool/a@1:1#0", GroupId("pool/a"), 1),
        )
        for (r in foreign) {
            ledger = ledger.piece(assertNotNull(ledger.prepare(r)))
            ledger = ledger.piece(assertNotNull(ledger.activate(r.id)))
        }
        assertEquals(emptyList(), Topology.diff(fairness(), ledger, generation = 0))
    }

    @Test
    fun secondChangeMidDrainLeavesNoOrphan() {
        val withoutAcme = Topology("root", listOf(Group("hobby", Weight.ONE, listOf(Lane("default")))))
        val first = applyAll(Topology.diff(withoutAcme, busy(bootFrom(fairness())), 0), busy(bootFrom(fairness())))
        val readded = fairness(acmeWeight = 5)
        val second = Topology.diff(readded, first.ledger, generation = 0)
        val done = applyAll(second, drainAll(first.ledger))
        assertAll(
            // The rig: acme and batch are still draining when the second change arrives.
            { assertEquals(listOf(acme, batch), first.refused) },
            { assertEquals(Lifecycle.CLOSING, first.ledger.lifecycle(e("root/acme@3:1#0"))) },
            { assertEquals(4L, first.ledger.edge(e("root/acme/batch@1:1#0"))?.outstanding) },
            // The second diff owns the edges the first one left closing.
            { assertTrue(Retire(e("root/acme@3:1#0"), acme) in second, "$second") },
            { assertTrue(Retire(e("root/acme/batch@1:1#0"), batch) in second, "$second") },
            { assertEquals(emptyList(), done.refused) },
            { assertEquals(listOf(e("root/acme@5:1#1")), done.ledger.liveInto(acme)) },
            { assertEquals(listOf(e("root/acme/batch@1:1#1")), done.ledger.liveInto(batch)) },
            { assertEquals(1, done.ledger.liveInto(interactive).size) },
            { assertEquals(bootFrom(readded).activeTriples(), done.ledger.activeTriples()) },
            { assertEquals(emptyList(), done.ledger.validate()) },
            { assertEquals(emptyList(), Topology.diff(readded, done.ledger, generation = 0)) },
        )
    }

    @Test
    fun revertMidDrainConverges() {
        val withoutAcme = Topology("root", listOf(Group("hobby", Weight.ONE, listOf(Lane("default")))))
        val first = applyAll(Topology.diff(withoutAcme, busy(bootFrom(fairness())), 0), busy(bootFrom(fairness())))
        val second = Topology.diff(fairness(), first.ledger, generation = 0)
        val done = applyAll(second, drainAll(first.ledger))
        assertAll(
            { assertEquals(listOf(acme, batch), first.refused) },
            // A closing edge cannot be reopened, so the revert is a fresh generation at the old weight.
            { assertTrue(Prepare(record("root/acme@3:1#1", acme, 3)) in second, "$second") },
            { assertEquals(listOf(e("root/acme@3:1#1")), done.ledger.liveInto(acme)) },
            { assertEquals(bootFrom(fairness()).activeTriples(), done.ledger.activeTriples()) },
            { assertEquals(emptyList(), done.ledger.validate()) },
            { assertEquals(emptyList(), Topology.diff(fairness(), done.ledger, generation = 0)) },
        )
    }

    @Test
    fun unrelatedGroupInTheSameSaveApplies() {
        val ledger = busy(bootFrom(fairness()))
        val target = fairness(acmeWeight = 4, hobbyWeight = 2)
        val applied = applyAll(Topology.diff(target, ledger, generation = 0), ledger)
        assertAll(
            { assertEquals(listOf(acme), applied.refused) },
            { assertEquals(listOf(e("root/hobby@2:1#1")), applied.ledger.liveInto(hobby)) },
            { assertEquals(listOf(e("root/acme@3:1#0")), applied.ledger.liveInto(acme)) },
            { assertEquals(Lifecycle.PREPARED, applied.ledger.lifecycle(e("root/acme@4:1#1"))) },
            { assertEquals(emptyList(), applied.ledger.validate()) },
            // What is left is acme's alone, and it is listed again rather than lost.
            {
                assertEquals(
                    listOf(Retire(e("root/acme@3:1#0"), acme), Activate(e("root/acme@4:1#1"), acme), Reconcile(acme)),
                    Topology.diff(target, applied.ledger, generation = 0),
                )
            },
        )
    }

    @Test
    fun renameIsDeleteThenAdd() {
        val ledger = served(bootFrom(fairness()))
        val target = Topology("root", fairness().children.map { if (it.name == "acme") (it as Group).copy(name = "acme2") else it })
        val acme2 = GroupId("root/acme2")
        val batch2 = GroupId("root/acme2/batch")
        val interactive2 = GroupId("root/acme2/interactive")
        val steps = Topology.diff(target, ledger, generation = 1)
        val applied = applyAll(steps, ledger)
        assertAll(
            {
                assertEquals(
                    listOf(
                        Prepare(record("root/acme2@3:1#1", acme2, 3)),
                        Prepare(record("root/acme2/batch@1:1#1", batch2, 1)),
                        Prepare(record("root/acme2/interactive@3:1#1", interactive2, 3)),
                        Activate(e("root/acme2@3:1#1"), acme2),
                        Activate(e("root/acme2/batch@1:1#1"), batch2),
                        Activate(e("root/acme2/interactive@3:1#1"), interactive2),
                        Close(e("root/acme@3:1#0"), acme),
                        Close(e("root/acme/batch@1:1#0"), batch),
                        Close(e("root/acme/interactive@3:1#0"), interactive),
                        Retire(e("root/acme/batch@1:1#0"), batch),
                        Retire(e("root/acme/interactive@3:1#0"), interactive),
                        Retire(e("root/acme@3:1#0"), acme),
                    ),
                    steps,
                )
            },
            // The history stays with the old edges; the new child starts with none.
            { assertEquals(7L, applied.ledger.edge(e("root/acme@3:1#0"))?.spent) },
            { assertEquals(0L, applied.ledger.edge(e("root/acme2@3:1#1"))?.issued) },
            { assertEquals(emptyList(), applied.ledger.validate()) },
        )
    }

    @Test
    fun diffWithoutPriorTopologyAfterRestart() {
        val busyThenReweighted = applyAll(Topology.diff(fairness(acmeWeight = 4), busy(bootFrom(fairness())), 0), busy(bootFrom(fairness())))
        val beforeRestart = drainAll(busyThenReweighted.ledger)
        // A restart keeps the ledger's bytes and nothing else: no prior topology, no counter.
        val restarted = Json.decodeFromString(EntitlementLedger.serializer(), Json.encodeToString(EntitlementLedger.serializer(), beforeRestart))
        val remainder = listOf(Retire(e("root/acme@3:1#0"), acme), Activate(e("root/acme@4:1#1"), acme))
        val fresh = Topology.diff(fairness(acmeWeight = 4), restarted, generation = 0)
        assertAll(
            // The rig: the save before the restart really was left half-applied.
            { assertEquals(listOf(acme), busyThenReweighted.refused) },
            // What a diff from the prior topology, `fairness()`, would still owe: the drained
            // edge's retire and its replacement's activation, and no reconcile, since a release
            // left nothing on the old edge to move.
            { assertEquals(remainder, fresh) },
            { assertEquals(Topology.diff(fairness(acmeWeight = 4), beforeRestart, generation = 0), fresh) },
            { assertEquals(emptyList(), applyAll(fresh, restarted).ledger.validate()) },
            { assertIs<Relocation.Nothing>(applyAll(fresh, restarted).ledger.reconcileLocally(acme)) },
        )
    }
}
