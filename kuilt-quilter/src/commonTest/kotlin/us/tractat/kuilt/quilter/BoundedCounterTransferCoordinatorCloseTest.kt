@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import us.tractat.kuilt.conformance.CloseableLifecycleConformanceSuite
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import kotlin.time.Duration.Companion.seconds

private val CLOSE_TEST_SELF = ReplicaId("self")
private val CLOSE_TEST_DONOR = ReplicaId("donor")
private val CLOSE_TEST_SELF_PEER = PeerId("self")
private val CLOSE_TEST_DONOR_PEER = PeerId("donor")

/**
 * Lifecycle conformance for [BoundedCounterTransferCoordinator].
 *
 * This used to be a hand-rolled three-test copy of the suite, kept local because the coordinator
 * was an `AutoCloseable` rather than a [ScopedCloseable]. The copy was missing exactly one of the
 * suite's properties — [CloseableLifecycleConformanceSuite.closeCancelsTheJobTheInstanceOwnsInTheGivenScope],
 * whose KDoc names this failure verbatim — and the defect it names lived in the coordinator the
 * whole time it was green (#2502). The three surviving tests all quantified over
 * `backgroundJobsForTest`, the one list the escaped coroutine is by construction absent from.
 *
 * ## The fixture is starved on purpose, and that is load-bearing
 *
 * `create` hands back a coordinator whose replica is **at** the low-water threshold with a donor
 * holding surplus, so the quota observer fires on its first emission and a borrow is already
 * launched and parked in its retry backoff by the time the constructor returns.
 *
 * A healthy replica would be the natural-looking fixture and would make the suite's strongest
 * property vacuous here. That property snapshots the children of the scope `create` was handed, so
 * it can only see a coroutine that exists *at construction time* — its own KDoc says so, calling a
 * lazily-launched coroutine the residual it cannot cover. The borrow is precisely that shape:
 * launched reactively, not in the constructor. Starving the fixture is what drags it inside the
 * snapshot's reach, and it converts the suite from "asserts nothing about #2502" into a test that
 * reds when the borrow is re-parented to the caller's scope. Verified by mutation rather than
 * assumed: re-parenting the borrow while leaving everything else fixed reds
 * `closeCancelsTheJobTheInstanceOwnsInTheGivenScope`, and with a healthy fixture the same mutation
 * passes.
 *
 * Nothing but the coordinator goes into the given scope — [FakeSeam] and a plain
 * [MutableStateFlow] launch no coroutines — as [CloseableLifecycleConformanceSuite.create]
 * requires: a fixture coroutine parked there is indistinguishable from one the instance leaked.
 */
class BoundedCounterTransferCoordinatorCloseTest : CloseableLifecycleConformanceSuite() {

    override fun create(scope: CoroutineScope): ScopedCloseable = BoundedCounterTransferCoordinator(
        coordSeam = FakeSeam(
            selfId = CLOSE_TEST_SELF_PEER,
            initialPeers = setOf(CLOSE_TEST_SELF_PEER, CLOSE_TEST_DONOR_PEER),
        ),
        // quota(self) == 0 == lowWaterThreshold, donor in surplus: a borrow launches immediately.
        state = MutableStateFlow(
            BoundedCounter.init(mapOf(CLOSE_TEST_SELF to 0L, CLOSE_TEST_DONOR to 100L)),
        ),
        self = CLOSE_TEST_SELF,
        applyTransfer = {},
        scope = scope,
        config = BoundedCounterTransferConfig(
            lowWaterThreshold = 0L,
            requestedAmount = 10L,
            surplusFloor = 0L,
            maxRetries = 3,
            // Long enough that the borrow is still parked in backoff when the suite closes it —
            // the suite never advances virtual time, so this only has to exceed zero, but a
            // multi-second value keeps the intent legible.
            initialRetryDelay = 2.seconds,
        ),
    )

    override fun backgroundJobsOf(instance: ScopedCloseable): List<Job> =
        (instance as BoundedCounterTransferCoordinator).backgroundJobsForTest
}
