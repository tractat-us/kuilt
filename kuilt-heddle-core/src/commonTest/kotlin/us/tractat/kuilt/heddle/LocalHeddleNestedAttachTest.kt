package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Nested attached children on a running engine (#3314): `kind → repository → author`, with both
 * the repositories and the authors attached while running. A repository is a group whose template
 * carries a template, so it is never leased itself; an author is.
 */
class LocalHeddleNestedAttachTest {

    private val policy = LocalPolicy(defaultReservation = 1L)

    private val authors = ChildTemplate(Weight.ONE, maxConcurrent = 1L)
    private val repos = ChildTemplate(Weight.of(2), template = authors)

    /** `root → ci {manual, template(repos → authors)}`. */
    private val t = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")), template = repos)))

    private val kuilt = GroupPath.Attached(listOf("ci"), "kuilt")
    private val alice = GroupPath.Nested(kuilt, "alice")
    private val bob = GroupPath.Nested(kuilt, "bob")
    private val kuiltId = checkNotNull(t.findGroupId(kuilt))
    private val aliceId = checkNotNull(t.findGroupId(alice))
    private val bobId = checkNotNull(t.findGroupId(bob))

    private fun engine(): LocalHeddle = LocalHeddle.bootWhole(t, policy, epoch = 0L).also { it.reconcileCapacity(4L) }

    private fun LocalHeddle.attached(path: GroupPath): LocalHeddle = also {
        val result = attach(path, generation = 1L)
        check(result.refusals.isEmpty()) { "the rig: attaching $path landed, refused ${result.refusals}" }
    }

    /** Whether [group] has a live lineage: an empty one is the root's, or a group with no edge left at all. */
    private fun LocalHeddle.live(group: GroupId): Boolean = snapshot().ledger.lineageOf(group)?.isNotEmpty() == true

    @Test
    fun attachingAnAuthorAttachesItsRepositoryAsOneUnit() {
        val h = engine()
        val result = h.attach(alice, generation = 1L)
        val lease = h.acquire(setOf(aliceId), setOf(aliceId), startedAt = 0L)
        assertAll(
            { assertEquals(emptyList(), result.refusals) },
            { assertEquals(setOf(kuiltId, aliceId), result.applied.map { it.child }.toSet()) },
            { assertTrue(h.live(kuiltId) && h.live(aliceId)) },
            { assertEquals(aliceId, assertIs<AcquireOutcome.Acquired>(lease).lease.leaf, "an author is leased") },
        )
    }

    /** The positive control for `Leasable.ByTopology.declares`: a repository is a leaf in the ledger until an author arrives, and still never leased. */
    @Test
    fun aRepositoryIsNeverLeased() {
        val h = engine().attached(kuilt)
        val ledger = h.snapshot().ledger
        val admitted = h.admit(kuiltId, waiting = setOf(kuiltId), startedAt = 0L)
        val acquired = h.acquire(setOf(kuiltId), setOf(kuiltId), startedAt = 0L)
        val share = h.entitlement(kuiltId, setOf(kuiltId), totalSlots = 4L)
        assertAll(
            { assertTrue(ledger.isLeaf(kuiltId) && ledger.lineageOf(kuiltId) != null, "the rig: attached, with nothing beneath it") },
            { assertEquals(AcquireOutcome.NoLiveLineage(kuiltId), admitted) },
            { assertIs<AcquireOutcome.NoLiveLineage>(acquired) },
            { assertEquals(Rational.ZERO, share) },
        )
    }

    @Test
    fun theAuthorTemplatesCeilingCapsEachAuthor() {
        val h = engine().attached(alice).attached(bob)
        val first = h.acquire(setOf(aliceId), setOf(aliceId), startedAt = 0L)
        val again = h.acquire(setOf(aliceId), setOf(aliceId), startedAt = 0L)
        val sibling = h.acquire(setOf(aliceId, bobId), setOf(aliceId, bobId), startedAt = 0L)
        assertAll(
            { assertEquals(aliceId, assertIs<AcquireOutcome.Acquired>(first).lease.leaf) },
            { assertEquals(AcquireOutcome.NothingEligible, again, "alice is at her own ceiling") },
            { assertEquals(bobId, assertIs<AcquireOutcome.Acquired>(sibling).lease.leaf, "bob is not") },
        )
    }

    @Test
    fun detachingARepositoryDrainsItsSubtreeAndBringsNoAuthorBack() {
        val h = engine().attached(alice).attached(bob)
        val lease = assertIs<AcquireOutcome.Acquired>(h.acquire(setOf(aliceId), setOf(aliceId), startedAt = 0L)).lease
        val draining = h.detach(kuilt)
        val whileDraining = h.acquire(setOf(aliceId, bobId), setOf(aliceId, bobId), startedAt = 1L)
        check(h.complete(lease.id, actualCost = 1L, waiting = emptySet()) is LeaseOutcome.Done)
        val drained = h.detach(kuilt)
        val back = h.attach(kuilt, generation = 2L)
        val reload = h.apply(t, generation = 2L)
        assertAll(
            { assertTrue(draining.refusals.isNotEmpty() && draining.refusals.all { it is StepRefusal.Draining }, "waits on the lease: ${draining.refusals}") },
            { assertEquals(setOf(aliceId, kuiltId), draining.refusals.map { it.child }.toSet(), "alice and the repository above her wait") },
            { assertTrue(bobId in draining.applied.filterIsInstance<TopologyStep.Retire>().map { it.child }, "bob, with nothing running, retires at once") },
            { assertTrue(whileDraining !is AcquireOutcome.Acquired, "nothing new is leased beneath a closing repository: $whileDraining") },
            { assertEquals(emptyList(), drained.refusals, "once drained, the rest lands") },
            { assertEquals(emptyList(), back.refusals) },
            { assertTrue(h.live(kuiltId), "the repository comes back") },
            { assertTrue(!h.live(aliceId) && !h.live(bobId), "with no author") },
            { assertEquals(emptyList(), reload.applied, "and a reload brings none back either") },
        )
    }

    /**
     * A repository detached while alice's lease ran, then drained, is attached again through a new
     * author. Its old edge retires in that unit, and so must alice's: she was closed by the detach,
     * and a closing edge left beneath a retired one is a child live beneath a dead parent.
     */
    @Test
    fun reattachingADrainedRepositoryRetiresWhatItsDetachClosed() {
        val h = engine().attached(alice)
        val lease = assertIs<AcquireOutcome.Acquired>(h.acquire(setOf(aliceId), setOf(aliceId), startedAt = 0L)).lease
        val draining = h.detach(kuilt)
        check(h.complete(lease.id, actualCost = 1L, waiting = emptySet()) is LeaseOutcome.Done)
        val result = h.attach(bob, generation = 2L)
        val ledger = h.snapshot().ledger
        assertAll(
            { assertTrue(draining.refusals.isNotEmpty() && draining.refusals.all { it is StepRefusal.Draining }, "the rig: the detach waited on alice") },
            { assertEquals(emptyList(), result.refusals) },
            { assertTrue(h.live(kuiltId) && h.live(bobId), "the repository and bob are attached") },
            { assertEquals(emptyList(), ledger.liveInboundEdges(aliceId), "alice's old edge is not left closing beneath the new repository edge") },
            { assertTrue(!h.live(aliceId), "and she is not back") },
            { assertEquals(emptyList(), ledger.validate(), "nor anything else wrong") },
        )
    }

    /**
     * Three attached levels, `a → b → x`, the whole chain detached while x's lease ran and then
     * drained. Attaching `a/b/y` re-attaches both `a` and `b`, and x, which the detach closed, must
     * retire exactly once in that list: a second retire of the same edge is refused by the ledger,
     * and the refusal takes the whole attach with it.
     */
    @Test
    fun reattachingADrainedChainRetiresWhatWasClosedOnce() {
        val deep = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")), template = ChildTemplate(Weight.of(2), template = ChildTemplate(Weight.ONE, template = ChildTemplate(Weight.ONE))))))
        val a = GroupPath.Attached(listOf("ci"), "a")
        val b = GroupPath.Nested(a, "b")
        val x = GroupPath.Nested(b, "x")
        val y = GroupPath.Nested(b, "y")
        val h = LocalHeddle.bootWhole(deep, policy, epoch = 0L).also { it.reconcileCapacity(4L) }
        check(h.attach(x, generation = 1L).refusals.isEmpty())
        val xId = checkNotNull(deep.findGroupId(x))
        val lease = assertIs<AcquireOutcome.Acquired>(h.acquire(setOf(xId), setOf(xId), startedAt = 0L)).lease
        val draining = h.detach(a)
        check(h.complete(lease.id, actualCost = 1L, waiting = emptySet()) is LeaseOutcome.Done)
        val steps = deep.attach(y, h.snapshot().ledger, generation = 5L)
        val result = h.attach(y, generation = 5L)
        val ledger = h.snapshot().ledger
        assertAll(
            { assertTrue(draining.refusals.isNotEmpty() && draining.refusals.all { it is StepRefusal.Draining }, "the rig: the detach waited on x") },
            { assertEquals(1, steps.count { it is TopologyStep.Retire && it.child == xId }, "x retires once: $steps") },
            { assertEquals(emptyList(), result.refusals) },
            { assertTrue(listOf(a, b, y).all { h.live(checkNotNull(deep.findGroupId(it))) }, "the chain and y are attached") },
            { assertTrue(!h.live(xId) && ledger.liveInboundEdges(xId).isEmpty(), "x is not back, nor left closing") },
            { assertEquals(emptyList(), ledger.validate()) },
        )
    }

    @Test
    fun aNestedTopologyPersistsAndRestores() {
        val h = engine().attached(alice)
        val text = Json.encodeToString(LocalHeddleSnapshot.serializer(), h.persist())
        val restored = LocalHeddle.restore(assertIs<RestoreResult.Restored>(LocalHeddle.decode(text, Json)).snapshot, policy, epoch = 1L)
        val lease = restored.acquire(setOf(aliceId), setOf(aliceId), startedAt = 0L)
        assertAll(
            { assertTrue("\"template\":{\"weight\":\"2\",\"template\":{\"weight\":\"1\",\"maxConcurrent\":1}}" in text, "stored: $text") },
            { assertEquals(t, restored.topology) },
            { assertEquals(aliceId, assertIs<AcquireOutcome.Acquired>(lease).lease.leaf) },
            { assertEquals(AcquireOutcome.NoLiveLineage(kuiltId), restored.admit(kuiltId, setOf(kuiltId), startedAt = 0L), "and still never leases the repository") },
        )
    }

    @Test
    fun theOneLevelCallsAreTheAttachedPath() {
        val one = engine()
        val other = engine()
        val byNames = one.attach(listOf("ci"), "kuilt", generation = 1L)
        val byPath = other.attach(kuilt, generation = 1L)
        assertAll(
            { assertEquals(byNames, byPath) },
            { assertEquals(one.detach(listOf("ci"), "kuilt"), other.detach(kuilt)) },
            { assertTrue(byNames.applied.isNotEmpty(), "the rig: the attach did something") },
            { assertTrue(!one.live(kuiltId)) },
        )
    }
}
