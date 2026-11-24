package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [LocalHeddle.topology] is the tree last asked for: the one booted, the one [LocalHeddle.apply] was
 * last given whatever it refused, and after a restore the persisted one. [LocalHeddle.attach] and
 * [LocalHeddle.detach] leave it as it is, and reading it does not move [LocalHeddle.revision].
 *
 * [LocalHeddle.persist] writes this same property as [LocalHeddleSnapshot.lastApplied], so the two
 * are equal by construction; the assertions that can fail here are the ones naming the tree each
 * step should leave.
 */
class LocalHeddleTopologyTest {
    private val policy = LocalPolicy(defaultReservation = 3L)

    private val farm = draft("farm") {
        group("web", Weight.of(3)) { lane("builds") }
        group("mobile") { lane("builds") }
        group("forks") { template() }
    }

    /** web drops to two parts and mobile's lane is removed: a reload with something in it to apply. */
    private val reshaped = draft("farm") {
        group("web", Weight.of(2)) { lane("builds") }
        group("forks") { template() }
    }

    @Test
    fun itIsTheTreeBootedAndThenTheTreeEachReloadWasGiven() {
        val heddle = LocalHeddle.fromTopology(farm, policy, epoch = 0L).heddle
        val booted = heddle.topology
        val reload = heddle.apply(reshaped, generation = 1L)
        assertAll(
            { assertEquals(farm, booted) },
            { assertEquals(emptyList(), reload.refusals, "the rig: the reload lands whole") },
            { assertEquals(reshaped, heddle.topology) },
            { assertEquals(heddle.topology, heddle.persist().lastApplied) },
        )
    }

    @Test
    fun attachAndDetachLeaveItAsItIs() {
        val heddle = LocalHeddle.fromTopology(farm, policy, epoch = 0L).heddle
        val before = heddle.revision
        val attached = heddle.attach(listOf("forks"), "ana", generation = 1L)
        val afterAttach = heddle.topology
        val detached = heddle.detach(listOf("forks"), "ana")
        assertAll(
            { assertEquals(emptyList(), attached.refusals, "the rig: ana is attached") },
            { assertTrue(attached.applied.isNotEmpty(), "the rig: the attach applied a step") },
            { assertTrue(detached.applied.isNotEmpty(), "the rig: the detach applied a step") },
            { assertTrue(heddle.revision > before, "the rig: both calls changed the engine") },
            { assertEquals(farm, afterAttach) },
            { assertEquals(farm, heddle.topology) },
        )
    }

    /** A reload the weight budget refuses part of still becomes the running tree, as [LocalHeddle.persist] stores it. */
    @Test
    fun aReloadThatLeavesAGroupOutIsStillTheTreeAskedFor() {
        val small = draft("pool") { lane("small") }
        val huge = draft("pool") {
            lane("small")
            group("huge", Weight.of(1L shl 62)) { lane("own") }
        }
        val heddle = LocalHeddle.fromTopology(small, policy, epoch = 0L).heddle
        val reload = heddle.apply(huge, generation = 1L)
        assertAll(
            { assertTrue(reload.refusals.isNotEmpty(), "the rig: the reload left a group out") },
            { assertTrue(reload.refusals.all { it is StepRefusal.Refused }, "as Refused: ${reload.refusals}") },
            { assertEquals(huge, heddle.topology) },
        )
    }

    @Test
    fun aBootThatLeavesAGroupOutIsStillTheTreeBooted() {
        val tree = draft("farm") {
            group("builds") {
                lane("fast")
                lane("fine", Weight.of(1, 30_720))
            }
            lane("docs")
        }
        val (heddle, booted) = LocalHeddle.fromTopology(tree, policy, epoch = 0L)
        assertAll(
            { assertTrue(booted.refusals.isNotEmpty(), "the rig: the boot left a group out") },
            { assertEquals(tree, heddle.topology) },
        )
    }

    @Test
    fun aReloadThatThrowsLeavesItAsItWas() {
        val heddle = LocalHeddle.fromTopology(farm, policy, epoch = 0L).heddle
        val otherRoot = draft("elsewhere") { lane("builds") }
        assertFailsWith<IllegalArgumentException> { heddle.apply(otherRoot, generation = 1L) }
        assertEquals(farm, heddle.topology)
    }

    @Test
    fun aRestoredEngineRunsThePersistedTree() {
        val heddle = LocalHeddle.fromTopology(farm, policy, epoch = 0L).heddle
        heddle.apply(reshaped, generation = 1L)
        val restored = LocalHeddle.restore(heddle.persist(), policy, epoch = 1L)
        assertAll(
            { assertNotEquals(farm, reshaped, "the rig: the persisted tree is not the booted one") },
            { assertEquals(reshaped, restored.topology) },
        )
    }

    /** A page's edit, read and diffed from the engine, applies to exactly the tree it showed. */
    @Test
    fun anEditStartedFromItAppliesTheChangesItListed() {
        val heddle = LocalHeddle.fromTopology(farm, policy, epoch = 0L).heddle
        val startedFrom = heddle.topology
        val document = startedFrom.toDocument()
        val edited = document.copy(children = document.children.filterNot { it.name == "mobile" })
        val candidate = assertIs<TopologyParse.Valid>(Topology.from(edited)).topology
        val listed = startedFrom.changesTo(candidate)
        heddle.apply(candidate, generation = 1L)
        assertAll(
            { assertEquals(listOf("mobile"), listed.filterIsInstance<TopologyChange.Removed>().map { it.node.name }) },
            { assertEquals(1, listed.size, "only mobile: $listed") },
            { assertEquals(candidate, heddle.topology) },
            { assertEquals(emptyList(), heddle.topology.changesTo(candidate), "nothing left to change") },
        )
    }
}
