package us.tractat.kuilt.cluster

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Formation-timeout teardown for [assembleVoterMesh] — deterministic, virtual time, **no sockets**.
 *
 * [assembleVoterMesh] launches a persistent accept-pump per voter on a mesh lifecycle scope *before*
 * formation, then awaits the full K_M roster under a `formationTimeout`. If a voter never completes
 * its roster (a crashed/stalled peer), formation throws — and the caller never receives a [VoterMesh],
 * so it has **no handle** to close the mesh scope. This test proves that on that failure path the
 * function itself tears down what it started: the accept-pumps are cancelled, so nothing is orphaned.
 *
 * ## Why this exists in commonTest rather than only over WebSockets
 *
 * The teardown under test is entirely transport-agnostic — it lives here, in [assembleVoterMesh], and
 * `voterMeshOverWebSockets` is a thin wrapper that supplies a dial and a set of accept-sources. Pinning
 * it against a **real** loopback dial made the assertion depend on a real HTTP upgrade completing in
 * time, which a saturated box can fail: the dial threw first and the test reported a bare exception-type
 * mismatch, naming the timeout logic for a failure that never reached it (#2226). Here the stall is
 * constructed, not raced — so a red is always about the teardown.
 *
 * ## How formation is stalled
 *
 * Both voters' accept-sources are [NeverYieldingConnectionSource], so neither roster is fed from the
 * inbound side, and [dial] hands the caller one end of a [connectionPair] whose **peer end is dropped**
 * — a live link whose `MeshHello` is never answered. Neither voter reaches the full peer set, so
 * `withTimeout(formationTimeout)` fires on the virtual clock, deterministically and independently of
 * host load. The higher voter's source completes [NeverYieldingConnectionSource.cancelled] in its
 * `accept()` cancellation `finally`, so "was the accept-pump cancelled?" is directly observable.
 *
 * On code that fails formation without cancelling the mesh scope the pump keeps draining forever and
 * that deferred never completes, so the bounded await below is the RED signal.
 */
class VoterMeshFormationTimeoutTest {

    @Test
    fun formationTimeoutCancelsThePersistentAcceptPumps() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val lower = NodeId("voter-a")
            val higher = NodeId("voter-b")
            val higherSource = NeverYieldingConnectionSource()
            val sources = mapOf(lower to NeverYieldingConnectionSource(), higher to higherSource)

            // Formation must fail via the formation timeout — the caller gets no VoterMesh handle back.
            assertFailsWith<TimeoutCancellationException> {
                backgroundScope.assembleVoterMesh(
                    voters = listOf(lower, higher),
                    sourceOf = sources::getValue,
                    // A live link the peer never answers: addLink's MeshHello exchange never completes,
                    // so the dial neither fails fast nor completes the roster — it simply stalls.
                    dial = { _, _ -> connectionPair().first },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    raftConfig = voterMeshSimConfig(),
                    random = Random(VOTER_MESH_SIM_SEED),
                    // Both ceilings are set far above FORMATION_TIMEOUT so the formation timeout is the
                    // only bound that can fire: a red here is never some other clock winning the race.
                    handshakeTimeout = FORMATION_TIMEOUT * BOUND_HEADROOM,
                    dialTimeout = FORMATION_TIMEOUT * BOUND_HEADROOM,
                    formationTimeout = FORMATION_TIMEOUT,
                    backoffBase = 20.milliseconds,
                    backoffCap = 200.milliseconds,
                )
            }

            // The pump must have been running (else "cancelled" would be vacuously reachable by never
            // having started), and then cancelled by the formation-failure teardown.
            withTimeout(OBSERVE_WINDOW) { higherSource.accepting.await() }
            withTimeout(OBSERVE_WINDOW) { higherSource.cancelled.await() }
        }

    private companion object {
        /** Virtual-time bound on formation. Short: the never-yielding sources make it fire on schedule. */
        val FORMATION_TIMEOUT: Duration = 2.seconds

        /** Factor by which the handshake/dial ceilings clear [FORMATION_TIMEOUT], so neither can preempt it. */
        const val BOUND_HEADROOM: Int = 5

        /**
         * Virtual-time bound on observing the post-failure cancellation — far longer than the teardown
         * needs, but it MUST expire on code that never cancels the pump, so it is the RED/GREEN pivot.
         */
        val OBSERVE_WINDOW: Duration = 20.seconds
    }
}
