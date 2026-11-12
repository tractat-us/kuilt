package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.RuntimeChildren
import us.tractat.kuilt.heddle.TopologyStep.Activate
import us.tractat.kuilt.heddle.TopologyStep.Close
import us.tractat.kuilt.heddle.TopologyStep.Prepare
import us.tractat.kuilt.heddle.TopologyStep.Reconcile
import us.tractat.kuilt.heddle.TopologyStep.Retire
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TopologyRuntimeChildrenTest {

    private val tenants = listOf("tenants")
    private val tenantsId = GroupId("root/tenants")

    /** A declared lane `x` inside a group whose runtime children weigh [marker]. */
    private fun pool(marker: Long? = 2, tenantsWeight: Long = 1, acmeWeight: Long = 3) = Topology(
        "root",
        listOf(
            Group("acme", Weight.of(acmeWeight), listOf(Lane("batch"))),
            Group("tenants", Weight.of(tenantsWeight), listOf(Lane("x")), runtimeChildren = marker?.let { RuntimeChildren(Weight.of(it)) }),
        ),
    )

    private fun e(id: String) = AttachmentId(id)

    private fun runtime(name: String) = GroupId("root/tenants/!$name")

    private fun record(id: String, child: GroupId, weight: Long) =
        AttachmentRecord(e(id), GroupId(child.value.substringBeforeLast('/')), child, Weight.of(weight))

    /** [steps] applied by the reference interpreter, every one accepted. */
    private fun applyClean(steps: List<TopologyStep>, ledger: EntitlementLedger): EntitlementLedger {
        val applied = applyAll(steps, ledger)
        assertEquals(emptyList(), applied.refused, "refused applying $steps")
        return applied.ledger
    }

    /** [ledger] with the runtime child [name] attached under `tenants`. */
    private fun attached(ledger: EntitlementLedger, name: String, t: Topology = pool(), generation: Long = 0): EntitlementLedger =
        applyClean(t.attachRuntimeChild(tenants, name, ledger, generation), ledger)

    /** [amount] units delegated down to [group] and [spent] of them charged there. */
    private fun charged(ledger: EntitlementLedger, group: GroupId, amount: Long, spent: Long): EntitlementLedger {
        val delegated = assertNotNull(delegateDown(ledger, group, amount), "delegate down to ${group.value}")
        return if (spent == 0L) delegated else delegated.piece(assertNotNull(delegated.spend(BOOT_HOLDER, group, spent), "spend at ${group.value}"))
    }

    @Test
    fun attachIsIdempotent() {
        val ledger = bootFrom(pool())
        val child = runtime("c-7f3a")
        val steps = pool().attachRuntimeChild(tenants, "c-7f3a", ledger, generation = 0)
        // Only the prepare landed: the second call finishes the job rather than starting another.
        val prepared = applyClean(steps.take(1), ledger)
        val done = applyClean(steps, ledger)
        assertAll(
            {
                assertEquals(
                    listOf(Prepare(record("root/tenants/!c-7f3a@2:1#1", child, 2)), Activate(e("root/tenants/!c-7f3a@2:1#1"), child)),
                    steps,
                )
            },
            { assertEquals(listOf(Activate(e("root/tenants/!c-7f3a@2:1#1"), child)), pool().attachRuntimeChild(tenants, "c-7f3a", prepared, 0)) },
            { assertEquals(emptyList(), pool().attachRuntimeChild(tenants, "c-7f3a", done, generation = 0)) },
            { assertEquals(emptyList(), pool().attachRuntimeChild(tenants, "c-7f3a", done, generation = 9)) },
            { assertEquals(listOf(e("root/tenants/!c-7f3a@2:1#1")), done.liveInto(child)) },
            { assertEquals(emptyList(), done.validate()) },
        )
    }

    @Test
    fun runtimeNamespaceCannotCollide() {
        // Runtime children named exactly like the declared lane `x`, and like the escaped `!x`.
        var ledger = bootFrom(pool())
        val x = pool().attachRuntimeChild(tenants, "x", ledger, generation = 0)
        ledger = applyClean(x, ledger)
        val bang = pool().attachRuntimeChild(tenants, "!x", ledger, generation = 0)
        ledger = applyClean(bang, ledger)
        val declared = GroupId("root/tenants/x")
        // Both are charged on their own, and neither charge reaches the other's edge.
        ledger = charged(ledger, declared, amount = 5, spent = 5)
        ledger = charged(ledger, runtime("x"), amount = 3, spent = 3)
        assertAll(
            { assertEquals(listOf(Prepare(record("root/tenants/!x@2:1#1", runtime("x"), 2)), Activate(e("root/tenants/!x@2:1#1"), runtime("x"))), x) },
            { assertEquals(GroupId("root/tenants/!%21x"), bang.first().child) },
            { assertEquals(listOf(e("root/tenants/x@1:1#0")), ledger.liveInto(declared)) },
            { assertEquals(listOf(e("root/tenants/!x@2:1#1")), ledger.liveInto(runtime("x"))) },
            { assertEquals(5L, ledger.edge(e("root/tenants/x@1:1#0"))?.spent) },
            { assertEquals(3L, ledger.edge(e("root/tenants/!x@2:1#1"))?.spent) },
            { assertEquals(emptyList(), Topology.diff(pool(), ledger, generation = 0)) },
            { assertEquals(emptyList(), ledger.validate()) },
        )
    }

    @Test
    fun runtimeChildNamesAreOpaque() {
        val names = listOf("a/b", "a%2Fb", "é", "#3", "x@1:1#0", "!x", "%21x", "\uD800", "😀", "batch", "tenants")
        var ledger = bootFrom(pool())
        val children = ArrayList<GroupId>()
        for (name in names) {
            val steps = pool().attachRuntimeChild(tenants, name, ledger, generation = 0)
            assertEquals(listOf(Prepare::class, Activate::class), steps.map { it::class }, "attach $name")
            children += steps.first().child
            ledger = applyClean(steps, ledger)
        }
        assertAll(
            { assertEquals(names.size, children.toSet().size, "runtime ids collide: ${children.map { it.value }}") },
            *names.zip(children).map { (name, child) ->
                { assertEquals(ParsedPath(listOf("root", "tenants", name), runtime = true), pathOf(child), name) }
            }.toTypedArray(),
            { assertEquals(emptyList(), Topology.diff(pool(), ledger, generation = 0)) },
            { assertEquals(emptyList(), ledger.validate()) },
            // The only refusals: an empty name, and a group that is not marked.
            { assertFailsWith<IllegalArgumentException> { pool().attachRuntimeChild(tenants, "", ledger, 0) } },
            { assertFailsWith<IllegalArgumentException> { pool().attachRuntimeChild(listOf("acme"), "x", ledger, 0) } },
            { assertFailsWith<IllegalArgumentException> { pool().attachRuntimeChild(emptyList(), "x", ledger, 0) } },
            { assertFailsWith<IllegalArgumentException> { pool().attachRuntimeChild(listOf("nope"), "x", ledger, 0) } },
            { assertFailsWith<IllegalArgumentException> { pool().attachRuntimeChild(tenants, "x", ledger, -1) } },
            { assertFailsWith<IllegalArgumentException> { pool().detachRuntimeChild(tenants, "", ledger) } },
            { assertFailsWith<IllegalArgumentException> { pool().detachRuntimeChild(listOf("acme"), "x", ledger) } },
        )
    }

    @Test
    fun reattachAfterDetachMintsAFreshGeneration() {
        val first = attached(bootFrom(pool()), "x")
        val detach = pool().detachRuntimeChild(tenants, "x", first)
        val detached = applyClean(detach, first)
        // Behind the ledger on purpose: the floor, not the caller, keeps the id fresh.
        val again = pool().attachRuntimeChild(tenants, "x", detached, generation = 0)
        val reattached = applyClean(again, detached)
        assertAll(
            { assertEquals(listOf(Close(e("root/tenants/!x@2:1#1"), runtime("x")), Retire(e("root/tenants/!x@2:1#1"), runtime("x"))), detach) },
            { assertEquals(Lifecycle.RETIRED, detached.lifecycle(e("root/tenants/!x@2:1#1")), "the rig retired the first edge") },
            { assertEquals(emptyList(), pool().detachRuntimeChild(tenants, "x", detached)) },
            { assertEquals(listOf(Prepare(record("root/tenants/!x@2:1#2", runtime("x"), 2)), Activate(e("root/tenants/!x@2:1#2"), runtime("x"))), again) },
            { assertEquals(listOf(e("root/tenants/!x@2:1#2")), reattached.liveInto(runtime("x"))) },
            { assertEquals(emptyList(), reattached.validate()) },
        )
    }

    @Test
    fun detachWhileServingRetiresOnceTheWorkDrains() {
        val busy = charged(attached(bootFrom(pool()), "x"), runtime("x"), amount = 4, spent = 1)
        val detach = pool().detachRuntimeChild(tenants, "x", busy)
        val draining = applyAll(detach, busy)
        // Re-attached before the drain: the replacement waits behind the old edge's retire.
        val again = pool().attachRuntimeChild(tenants, "x", draining.ledger, generation = 0)
        val waiting = applyAll(again, draining.ledger)
        val drained = drainAll(waiting.ledger)
        val finished = applyClean(pool().attachRuntimeChild(tenants, "x", drained, generation = 0), drained)
        assertAll(
            { assertEquals(listOf(runtime("x")), draining.refused, "the retire waits for the three open units") },
            { assertEquals(listOf(Retire(e("root/tenants/!x@2:1#1"), runtime("x"))), pool().detachRuntimeChild(tenants, "x", draining.ledger)) },
            {
                assertEquals(
                    listOf(
                        Prepare(record("root/tenants/!x@2:1#2", runtime("x"), 2)),
                        Retire(e("root/tenants/!x@2:1#1"), runtime("x")),
                        Activate(e("root/tenants/!x@2:1#2"), runtime("x")),
                    ),
                    again,
                )
            },
            { assertEquals(Lifecycle.PREPARED, waiting.ledger.lifecycle(e("root/tenants/!x@2:1#2"))) },
            // A prepared replacement is the record that the child is wanted: a reload keeps it.
            { assertEquals(listOf(Retire(e("root/tenants/!x@2:1#1"), runtime("x")), Activate(e("root/tenants/!x@2:1#2"), runtime("x"))), Topology.diff(pool(), waiting.ledger, 0)) },
            { assertEquals(listOf(e("root/tenants/!x@2:1#2")), finished.liveInto(runtime("x"))) },
            { assertEquals(emptyList(), finished.validate()) },
        )
    }

    @Test
    fun detachDuringAReplacementLeavesNothingPrepared() {
        // A reweight caught mid-drain: the old edge is closing, its replacement only prepared.
        val busy = charged(attached(bootFrom(pool()), "x"), runtime("x"), amount = 4, spent = 1)
        val reweight = applyAll(Topology.diff(pool(marker = 3), busy, generation = 0), busy)
        val mid = reweight.ledger
        val detach = pool(marker = 3).detachRuntimeChild(tenants, "x", mid)
        val detached = applyAll(detach, mid)
        val drained = drainAll(detached.ledger)
        assertAll(
            { assertEquals(Lifecycle.CLOSING, mid.lifecycle(e("root/tenants/!x@2:1#1")), "the rig left the old edge draining") },
            { assertEquals(Lifecycle.PREPARED, mid.lifecycle(e("root/tenants/!x@3:1#2")), "and its replacement prepared") },
            {
                assertEquals(
                    listOf(
                        Close(e("root/tenants/!x@3:1#2"), runtime("x")),
                        Retire(e("root/tenants/!x@3:1#2"), runtime("x")),
                        Retire(e("root/tenants/!x@2:1#1"), runtime("x")),
                    ),
                    detach,
                )
            },
            { assertEquals(Lifecycle.RETIRED, detached.ledger.lifecycle(e("root/tenants/!x@3:1#2"))) },
            // Detached for good: a reload under the same marker does not bring it back.
            { assertEquals(listOf(Retire(e("root/tenants/!x@2:1#1"), runtime("x"))), Topology.diff(pool(marker = 3), drained, 0)) },
            { assertEquals(emptyList(), applyClean(Topology.diff(pool(marker = 3), drained, 0), drained).liveInto(runtime("x"))) },
            { assertEquals(emptyList(), drained.validate()) },
        )
    }

    /**
     * The window the one-unit contract on [TopologyStep] covers. A reweight whose prepare alone
     * landed leaves the child ACTIVE beside a PREPARED replacement; a detach then closes the
     * prepared edge before retiring it, and every prefix in between holds two live inbound edges.
     * The whole list leaves none, and each prefix re-detaches to exactly the rest — which rests on
     * a drained retire sorting first even once the abandoned edge is no longer PREPARED.
     */
    @Test
    fun detachingBesideAPreparedReplacementIsOneUnit() {
        val ledger = attached(bootFrom(pool()), "x")
        val half = applyClean(Topology.diff(pool(marker = 3), ledger, generation = 0).take(1), ledger)
        val p = e("root/tenants/!x@3:1#2")
        val a = e("root/tenants/!x@2:1#1")
        val detach = pool(marker = 3).detachRuntimeChild(tenants, "x", half)
        val removal = Topology.diff(pool(marker = null), half, generation = 0)
        fun dualLive(l: EntitlementLedger) = l.validate().filterIsInstance<LedgerConflict.DualActiveInbound>()
        assertAll(
            { assertEquals(Lifecycle.PREPARED, half.lifecycle(p), "the rig left the replacement prepared") },
            { assertEquals(Lifecycle.ACTIVE, half.lifecycle(a), "beside the active edge") },
            { assertEquals(listOf(Close(p, runtime("x")), Close(a, runtime("x")), Retire(a, runtime("x")), Retire(p, runtime("x"))), detach) },
            { assertEquals(detach, removal, "a marker going away takes the same steps") },
            *(1..2).map { cut ->
                { assertEquals(listOf(LedgerConflict.DualActiveInbound(runtime("x"))), dualLive(applyClean(detach.take(cut), half)), "cut $cut") }
            }.toTypedArray(),
            *(0..detach.size).map { cut ->
                { assertEquals(detach.drop(cut), pool(marker = 3).detachRuntimeChild(tenants, "x", applyClean(detach.take(cut), half)), "re-detach after cut $cut") }
            }.toTypedArray(),
            { assertEquals(emptyList(), applyClean(detach, half).validate()) },
            { assertEquals(emptyList(), applyClean(removal, half).validate()) },
        )
    }

    @Test
    fun attachAndDetachRefuseAnotherRoot() {
        val elsewhere = Topology("pool", listOf(Group("tenants", Weight.ONE, listOf(Lane("x")), runtimeChildren = RuntimeChildren(Weight.ONE))))
        val minted = EntitlementLedger.bootstrap(GroupId("pool"), mapOf(BOOT_HOLDER to BOOT_SUPPLY), "boot")
        // Another tree's edges with no mint at all: the edges name the root instead.
        var unminted = EntitlementLedger.ZERO
        for (record in elsewhere.lower()) unminted = unminted.piece(assertNotNull(unminted.prepare(record)))
        assertAll(
            { assertFailsWith<IllegalArgumentException> { pool().attachRuntimeChild(tenants, "x", bootFrom(elsewhere), 0) } },
            { assertFailsWith<IllegalArgumentException> { pool().detachRuntimeChild(tenants, "x", bootFrom(elsewhere)) } },
            { assertFailsWith<IllegalArgumentException> { pool().attachRuntimeChild(tenants, "x", minted, 0) } },
            { assertFailsWith<IllegalArgumentException> { pool().attachRuntimeChild(tenants, "x", unminted, 0) } },
            { assertFailsWith<IllegalArgumentException> { pool().detachRuntimeChild(tenants, "x", unminted) } },
            // An empty ledger belongs to no tree yet, as for the diff.
            { assertEquals(2, pool().attachRuntimeChild(tenants, "x", EntitlementLedger.ZERO, 0).size) },
        )
    }

    @Test
    fun diffLeavesRuntimeChildrenAlone() {
        val ledger = attached(attached(bootFrom(pool()), "c-7f3a"), "x")
        val runtimeEdges = listOf(runtime("c-7f3a"), runtime("x")).flatMap { ledger.liveInto(it) }
        // A reload that changes everything around them: acme reweighted, a lane added to tenants.
        val reload = Topology(
            "root",
            listOf(
                Group("acme", Weight.of(4), listOf(Lane("batch"))),
                Group("tenants", Weight.ONE, listOf(Lane("x"), Lane("y")), runtimeChildren = RuntimeChildren(Weight.of(2))),
            ),
        )
        val steps = Topology.diff(reload, ledger, generation = 0)
        val after = applyClean(steps, ledger)
        assertAll(
            { assertEquals(2, runtimeEdges.size, "the rig attached both") },
            { assertTrue(steps.isNotEmpty(), "the reload changes something") },
            { assertEquals(emptyList(), steps.filter { pathOf(it.child)?.runtime == true }) },
            { assertEquals(runtimeEdges, listOf(runtime("c-7f3a"), runtime("x")).flatMap { after.liveInto(it) }) },
            { assertEquals(emptyList(), after.validate()) },
        )
    }

    @Test
    fun runtimeChildSurvivesAReloadWithTraffic() {
        // Served, and still holding three units, when the parent group is reweighted.
        val busy = charged(attached(bootFrom(pool()), "c-7f3a"), runtime("c-7f3a"), amount = 4, spent = 1)
        val edge = e("root/tenants/!c-7f3a@2:1#1")
        val steps = Topology.diff(pool(tenantsWeight = 2), busy, generation = 0)
        val mid = applyAll(steps, busy)
        // Mid-drain the child is untouched and still charges: the open units are spent where they sit.
        val spentMid = mid.ledger.piece(assertNotNull(mid.ledger.spend(BOOT_HOLDER, runtime("c-7f3a"), 3), "spend mid-drain"))
        val finished = applyClean(Topology.diff(pool(tenantsWeight = 2), spentMid, generation = 0), spentMid)
        // And afterwards it is served through the group's new edge.
        val served = charged(finished, runtime("c-7f3a"), amount = 5, spent = 5)
        assertAll(
            { assertEquals(emptyList(), steps.filter { pathOf(it.child)?.runtime == true }) },
            { assertEquals(listOf(tenantsId), mid.refused, "the group's retire waits for the child's open units") },
            { assertEquals(Lifecycle.ACTIVE, mid.ledger.lifecycle(edge)) },
            { assertEquals(9L, served.edge(edge)?.spent) },
            { assertEquals(listOf(e("root/tenants@2:1#2"), edge), assertNotNull(served.lineageOf(runtime("c-7f3a")))) },
            { assertTrue(Reconcile(tenantsId) in Topology.diff(pool(tenantsWeight = 2), spentMid, 0), "the group's history moves") },
            { assertEquals(emptyList(), served.validate()) },
            { assertEquals(emptyList(), Topology.diff(pool(tenantsWeight = 2), served, generation = 0)) },
        )
    }

    @Test
    fun diffClosesRuntimeChildrenWhenTheMarkerGoes() {
        val ledger = attached(attached(bootFrom(pool()), "c-7f3a"), "x")
        val steps = Topology.diff(pool(marker = null), ledger, generation = 0)
        val after = applyClean(steps, ledger)
        // The whole group gone takes its runtime children with it, below the group's own retire.
        val gone = Topology("root", listOf(Group("acme", Weight.of(3), listOf(Lane("batch")))))
        val removal = Topology.diff(gone, ledger, generation = 0)
        assertAll(
            {
                assertEquals(
                    listOf(
                        Close(e("root/tenants/!c-7f3a@2:1#1"), runtime("c-7f3a")),
                        Close(e("root/tenants/!x@2:1#2"), runtime("x")),
                        Retire(e("root/tenants/!c-7f3a@2:1#1"), runtime("c-7f3a")),
                        Retire(e("root/tenants/!x@2:1#2"), runtime("x")),
                    ),
                    steps,
                )
            },
            { assertEquals(emptyList(), after.validate()) },
            // The marker coming back does not bring them back: a detached child stays detached.
            { assertEquals(emptyList(), Topology.diff(pool(), after, generation = 0)) },
            {
                assertEquals(
                    listOf(e("root/tenants/!c-7f3a@2:1#1"), e("root/tenants/!x@2:1#2"), e("root/tenants/x@1:1#0"), e("root/tenants@1:1#0")),
                    removal.filterIsInstance<Retire>().map { it.edge },
                )
            },
            { assertEquals(emptyList(), applyClean(removal, ledger).validate()) },
        )
    }

    @Test
    fun diffRegeneratesRuntimeChildrenWhenTheMarkerWeightChanges() {
        val ledger = attached(bootFrom(pool()), "x")
        val steps = Topology.diff(pool(marker = 3), ledger, generation = 0)
        val after = applyClean(steps, ledger)
        // With work open the replacement waits for the drain, and the child stays wanted throughout.
        val busy = charged(ledger, runtime("x"), amount = 4, spent = 1)
        val mid = applyAll(Topology.diff(pool(marker = 3), busy, generation = 0), busy)
        val drained = drainAll(mid.ledger)
        val finished = applyClean(Topology.diff(pool(marker = 3), drained, generation = 0), drained)
        assertAll(
            {
                assertEquals(
                    listOf(
                        Prepare(record("root/tenants/!x@3:1#2", runtime("x"), 3)),
                        Close(e("root/tenants/!x@2:1#1"), runtime("x")),
                        Retire(e("root/tenants/!x@2:1#1"), runtime("x")),
                        Activate(e("root/tenants/!x@3:1#2"), runtime("x")),
                    ),
                    steps,
                )
            },
            { assertEquals(listOf(e("root/tenants/!x@3:1#2")), after.liveInto(runtime("x"))) },
            { assertEquals(listOf(runtime("x")), mid.refused) },
            { assertEquals(listOf(e("root/tenants/!x@3:1#2")), finished.liveInto(runtime("x"))) },
            // Once the marker weighs 3, a fresh attach uses it too.
            { assertEquals(Weight.of(3), (pool(marker = 3).attachRuntimeChild(tenants, "y", after, 0).first() as Prepare).record.weight) },
            { assertEquals(emptyList(), finished.validate()) },
        )
    }

    @Test
    fun attachReadsTheFloorTheLedgerCarries() {
        // One runtime child at generation 7 over a tree at 0: the next attach is at 8, read from the
        // ledger's index rather than from a pass over every edge.
        val ledger = attached(bootFrom(pool()), "x", generation = 7)
        val steps = pool().attachRuntimeChild(tenants, "y", ledger, generation = 0)
        assertAll(
            { assertEquals(GenerationTop(7L, 1), ledger.generationTop()) },
            { assertEquals(GenerationTop(-1L, 0), EntitlementLedger.ZERO.generationTop()) },
            { assertEquals(e("root/tenants/!y@2:1#8"), (steps.first() as Prepare).record.id) },
        )
    }
}
