package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A lane beneath a group with no inbound edge: the state a 0.7.5 engine persisted when a reload
 * refused a new group's activation and still activated the lane's own edge beneath it (#3466). A
 * reload no longer builds it, so the rig lays the lane's edge by hand after the refusal. No
 * delegation from the root can reach the lane. `admit` used to read that group as a root, call the
 * lineage live, and throw `IllegalStateException` on the delegation the root could not make.
 */
class LocalHeddleAdmitRefusedLineageTest {

    private val policy = LocalPolicy(defaultReservation = 1_000L)
    private val small = draft("pool") { lane("small", Weight.ONE) }

    /** `huge` is too heavy beside `small`, so its activation is refused, and its lane with it. */
    private val withHuge = draft("pool") {
        lane("small", Weight.ONE)
        group("huge", Weight.of(1L shl 62)) { lane("own", Weight.ONE) }
    }
    private val huge = withHuge.groupId("huge")
    private val lane = withHuge.groupId("huge", "own")

    private fun engine(): LocalHeddle =
        LocalHeddle.bootWhole(small, policy, epoch = 1L).apply {
            reconcileCapacity(4_000L)
            val refused = apply(withHuge, generation = 1L).refusals.map { it.child }
            check(refused == listOf(huge, lane)) { "the rig: huge and its lane are refused, was $refused" }
            reshapeForTest { l ->
                val edge = AttachmentRecord(AttachmentId("own-as-0.7.5-left-it"), huge, lane, Weight.ONE)
                val prepared = l.piece(checkNotNull(l.prepare(edge)) { "prepare the lane's edge" }.delta)
                prepared.piece(checkNotNull(prepared.activate(edge.id)) { "activate the lane's edge" }.delta)
            }
            val active = snapshot().ledger.activeChildren(huge).size
            check(active == 1) { "the rig: huge's lane edge is active beneath it, was $active" }
        }

    /**
     * `admit` answers [AcquireOutcome.NoLiveLineage] for the lane and leaves the engine as the
     * refused `acquire(eligible = {lane})` leaves its twin: ledger, leases and wake memory alike.
     */
    @Test
    fun anAdmitBeneathARefusedGroupAnswersNoLiveLineageAsTheAcquireDoes() {
        val admits = engine()
        val acquires = engine()
        val waiting = setOf(lane)
        val admitted = admits.admit(lane, waiting, startedAt = 0L)
        val acquired = acquires.acquire(waiting, waiting, startedAt = 0L)
        assertAll(
            { assertIs<AcquireOutcome.NoLiveLineage>(admitted, "admit: $admitted") },
            { assertIs<AcquireOutcome.NoLiveLineage>(acquired, "acquire: $acquired") },
            { assertEquals(acquires.snapshot(), admits.snapshot(), "the engines agree after it") },
            { assertEquals(emptyList(), admits.snapshot().leases, "nothing opened") },
        )
    }

    /**
     * `explain` names the lane as having no live lineage, alone and beside a reachable sibling. It
     * used to throw in both: the descent found no leaf, and beside `small` the lane had no position.
     */
    @Test
    fun explainSaysTheLaneBeneathARefusedGroupHasNoLiveLineage() {
        val engine = engine()
        val both = setOf(lane, withHuge.groupId("small"))
        assertAll(
            { assertEquals(WaitExplanation.NoLiveLineage(lane), engine.explain(lane, waiting = setOf(lane), eligible = setOf(lane))) },
            { assertEquals(WaitExplanation.NoLiveLineage(lane), engine.explain(lane, waiting = both, eligible = both)) },
        )
    }

    /** The lane is entitled to nothing. It used to be entitled to every slot, as its parent's only child. */
    @Test
    fun theLaneBeneathARefusedGroupIsEntitledToNothing() {
        val engine = engine()
        val both = setOf(lane, withHuge.groupId("small"))
        assertAll(
            { assertEquals(Rational.ZERO, engine.entitlement(lane, setOf(lane), totalSlots = 4L)) },
            { assertEquals(Rational.ZERO, engine.entitlement(lane, both, totalSlots = 4L)) },
            { assertEquals(Rational.of(4L), engine.entitlement(withHuge.groupId("small"), both, totalSlots = 4L), "small has every slot") },
        )
    }
}
