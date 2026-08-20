@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val BORROWER = ReplicaId("borrower")
private val DONOR = ReplicaId("donor")
private val BORROWER_PEER = PeerId("borrower")
private val DONOR_PEER = PeerId("donor")

/**
 * Low-water at 0 with a donor sitting on surplus, so a single emission at `quota == 0` is enough
 * to launch a borrow, and a multi-second backoff so the borrow is unambiguously *mid-retry* at
 * the moment the test acts on it. Three attempts means two sends are still owed after the first.
 */
private val BORROW_CONFIG = BoundedCounterTransferConfig(
    lowWaterThreshold = 0L,
    requestedAmount = 10L,
    surplusFloor = 0L,
    maxRetries = 3,
    initialRetryDelay = 2.seconds,
)

/** Healthy: above the low-water threshold, so the observer's first emission launches nothing. */
private fun healthy() = BoundedCounter.init(mapOf(BORROWER to 5L, DONOR to 100L))

/**
 * Starved: `quota(borrower) == 0`, at the threshold, with the donor holding surplus.
 * [donorQuota] varies only so a second emission is a genuinely different value and a
 * [MutableStateFlow] does not conflate it away.
 */
private fun starved(donorQuota: Long = 100L) =
    BoundedCounter.init(mapOf(BORROWER to 0L, DONOR to donorQuota))

private fun coordinator(
    seam: Seam,
    state: MutableStateFlow<BoundedCounter>,
    scope: kotlinx.coroutines.CoroutineScope,
) = BoundedCounterTransferCoordinator(
    coordSeam = seam,
    state = state,
    self = BORROWER,
    applyTransfer = {},
    scope = scope,
    config = BORROW_CONFIG,
)

/**
 * A [Seam] whose [sendTo] throws a [CancellationException] the **callee** minted — the shape a
 * consumer-authored seam produces when an internal `withTimeout` expires (see the repo's exception
 * discipline notes on #1834). The caller's job is untouched and perfectly alive; only the borrow
 * coroutine unwinds. `runCatchingCancellable` rethrows it by contract, so it escapes
 * `sendRequestWithRetries` and cancels the launched borrow.
 */
private class MintedCancellationSeam(delegate: FakeSeam) : Seam by delegate {
    var sendAttempts: Int = 0
        private set

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        sendAttempts++
        throw CancellationException("minted by the fabric; the caller's job is still alive")
    }
}

/**
 * The borrow coroutine's lifecycle — the half of [BoundedCounterTransferCoordinator]'s close
 * contract that quantifying over `backgroundJobsForTest` cannot see (#2502).
 *
 * Both properties here are about the coroutine `observeQuota` launches on a low-water event. It is
 * not in `backgroundJobs`, so every assertion of the form "all reported jobs are inactive" holds
 * whether or not the borrow is still running. These assert the *outcome* instead — what reaches the
 * fabric — so neither would pass if production cancelled the job and kept sending anyway.
 */
class BoundedCounterTransferCoordinatorBorrowLifecycleTest {

    /**
     * `close()`'s KDoc promises "no further transfer requests are sent". A borrow parked in its
     * retry backoff when `close()` lands must therefore never reach the fabric again.
     */
    @Test
    fun closeStopsAnInFlightBorrowMidBackoff() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val seam = FakeSeam(selfId = BORROWER_PEER, initialPeers = setOf(BORROWER_PEER, DONOR_PEER))
        val state = MutableStateFlow(healthy())
        val coordinator = coordinator(seam, state, backgroundScope)

        // Let the quota observer start and consume the healthy initial value.
        runCurrent()

        // Drop to the low-water threshold: this is what launches the borrow.
        state.value = starved()
        runCurrent()

        // The borrow has issued its first request and is now parked in `delay(initialRetryDelay)`.
        val sendsBeforeClose = seam.directed.size

        coordinator.close()

        // Past the entire remaining retry window (2 s + 4 s of backoff), in one bounded step.
        advanceTimeBy(30.seconds)
        runCurrent()

        assertAll(
            {
                // Positive control. Every assertion below is "nothing more was sent", which is
                // vacuously true of a borrow that never started — so a fixture that fails to
                // starve the replica would make this test green against the very defect it
                // exists to catch. Prove the rig fired before reading its result.
                assertTrue(
                    sendsBeforeClose > 0,
                    "rig did not fire: no TransferRequest was sent before close(), so there was " +
                        "no in-flight borrow to leak. Check that starved() is at or below " +
                        "lowWaterThreshold and that the donor still shows surplus.",
                )
            },
            {
                assertEquals(
                    sendsBeforeClose,
                    seam.directed.size,
                    "close() must stop the in-flight borrow: a request reached the seam after " +
                        "close() returned, so the retry loop survived — the borrow coroutine is " +
                        "parented to the caller's scope rather than to the job close() cancels",
                )
            },
        )
    }

    /**
     * The other end of the same coroutine's lifecycle: a borrow that ends *abnormally* must
     * release `requestInFlight`, or the coordinator can never borrow again.
     *
     * This is reachable on a live, un-closed coordinator — a callee-minted cancellation escapes
     * `sendRequestWithRetries` and cancels only the borrow, leaving the quota observer running and
     * the flag latched. Nothing subsequently lowers it, so every later low-water event is
     * swallowed and the replica silently stops asking for quota.
     */
    @Test
    fun aBorrowCancelledByTheFabricDoesNotWedgeLaterBorrows() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val seam = MintedCancellationSeam(
            FakeSeam(selfId = BORROWER_PEER, initialPeers = setOf(BORROWER_PEER, DONOR_PEER)),
        )
        val state = MutableStateFlow(healthy())
        val coordinator = coordinator(seam, state, backgroundScope)
        runCurrent()

        // First low-water event: the borrow launches, hits the fabric, and is cancelled by it.
        state.value = starved()
        runCurrent()
        val attemptsFromFirstBorrow = seam.sendAttempts

        // A second low-water event on a coordinator nobody closed.
        state.value = starved(donorQuota = 99L)
        runCurrent()

        coordinator.close()

        assertAll(
            {
                // Positive control, same reason as above: if the first borrow never reached the
                // fabric there was no cancellation, and the count below would be explained by
                // the rig rather than by the behaviour under test.
                assertEquals(
                    1,
                    attemptsFromFirstBorrow,
                    "rig did not fire: the first borrow did not reach MintedCancellationSeam, " +
                        "so no callee-minted cancellation was ever raised",
                )
            },
            {
                assertEquals(
                    2,
                    seam.sendAttempts,
                    "a second low-water event produced no borrow: the first borrow was cancelled " +
                        "before it could clear requestInFlight, so the flag is latched true and " +
                        "the coordinator can never request quota again",
                )
            },
        )
    }
}
