package us.tractat.kuilt.cluster

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Formation-timeout teardown for [assembleVoterMesh] — deterministic, virtual time, **no sockets**.
 *
 * [assembleVoterMesh] launches a persistent accept-pump per voter on a mesh lifecycle scope *before*
 * formation, then awaits the full K_M roster under a `formationTimeout`. If a voter never completes its
 * roster (a crashed or stalled peer), formation throws — and the caller never receives a [VoterMesh], so
 * it holds **no handle** with which to close the mesh scope. Its failure path therefore owes two things,
 * and this suite pins one per test so a red names which obligation was dropped:
 *
 * 1. `meshScope.cancel()` — the persistent accept-pumps are torn down, not left draining forever.
 * 2. `meshes.values.forEach { it.close() }` — the partially-formed seams are closed. Each `MeshSeam`
 *    runs on its own **unparented** `SupervisorJob` scope, so (1) provably cannot achieve this; without
 *    the explicit close the already-established inter-server sessions stay live and peers hold this
 *    voter as a zombie.
 *
 * ## Why this lives in commonTest rather than only over WebSockets
 *
 * The teardown is entirely transport-agnostic — it lives here, in [assembleVoterMesh], and
 * `voterMeshOverWebSockets` is a thin wrapper supplying a dial and a set of accept-sources. Pinning it
 * against a **real** loopback dial made the assertion depend on an HTTP upgrade completing in time,
 * which a saturated box can fail: the dial threw first, and the test reported a bare exception-type
 * mismatch that named the timeout logic for a failure which never reached it (#2226). Here the stall is
 * constructed rather than raced, so a red is always about the teardown. The real-socket
 * `WebSocketVoterMeshFormationTimeoutTest` is now an opt-in smoke over the same rig.
 *
 * ## How formation is stalled — and what each fixture knob switches off
 *
 * Three voters, of which only the highest-ranked ([STALLED]) is starved: its accept-source is a
 * [NeverYieldingConnectionSource], so its roster never grows past itself and the two dials aimed at it
 * hang mid-`MeshHello`. The other two voters connect to each other normally, so when formation fails the
 * mesh is genuinely **partial** — one live link, two stalled ones — which is what makes obligation (2)
 * observable at all. Two voters would leave no established link and could only ever pin (1).
 *
 * - [FORMATION_TIMEOUT] — the bound under test. Structurally the only one that can escape
 *   [assembleVoterMesh] here: `handshakeTimeout` fires inside a pump's own child coroutine, and
 *   `dialTimeout` belongs to the redial supervisors, which are started only *after* formation succeeds.
 * - [BOUND_HEADROOM] — keeps `handshakeTimeout` clear of [FORMATION_TIMEOUT] so the one handshake that
 *   is *meant* to succeed (the [LOWER]→[MIDDLE] link) cannot be truncated before it does. Setting it to
 *   or below 1 would switch off obligation (2)'s precondition by leaving no established link.
 * - [OBSERVE_WINDOW] — the RED/GREEN pivot for both tests. Teardown is instantaneous in virtual time
 *   when it happens at all, so any positive window is generous; it MUST expire on code that skips the
 *   obligation, which is what makes waiting on it an assertion rather than a formality.
 */
class VoterMeshFormationTimeoutTest {

    @Test
    fun formationTimeoutCancelsThePersistentAcceptPumps() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = stallFormation()

            // Precondition: the pump must actually have been running. Without this a green would be
            // indistinguishable from a rig that never fired — `cancelled` is reachable only from inside
            // the `accept()` the pump never entered, so both would simply be absent.
            rig.stalledSource.accepting.awaitOrFail("the starved voter's accept-pump entered accept()")
            rig.stalledSource.cancelled.awaitOrFail("the starved voter's accept-pump was cancelled")
        }

    @Test
    fun formationTimeoutClosesThePartiallyFormedSeams() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = stallFormation()
            val (dialerEnd, acceptorEnd) = rig.fabric.endsOf(VoterEdge(LOWER, MIDDLE))

            // Precondition: this edge really did go live. A dialed-but-unanswered edge is un-closed too,
            // so without this the assertions below could not tell "the seam failed to close a live link"
            // from "there was never a live link to close".
            dialerEnd.answered.awaitOrFail("$LOWER→$MIDDLE was answered (the link went live)")
            acceptorEnd.answered.awaitOrFail("$MIDDLE's end of $LOWER→$MIDDLE was answered")

            dialerEnd.closed.awaitOrFail("$LOWER's seam closed its end of the $LOWER→$MIDDLE link")
            acceptorEnd.closed.awaitOrFail("$MIDDLE's seam closed its end of the $LOWER→$MIDDLE link")
        }

    /** What [stallFormation] hands back: the observable link ends, and the starved voter's source. */
    private class StalledFormation(
        val fabric: CloseRecordingVoterFabric,
        val stalledSource: NeverYieldingConnectionSource,
    )

    /**
     * Drive [assembleVoterMesh] to a formation timeout over an in-memory fabric, asserting on the way
     * that it failed for the reason this suite is about, and return the probes.
     */
    private suspend fun TestScope.stallFormation(): StalledFormation {
        val fabric = CloseRecordingVoterFabric(VOTERS)
        val stalledSource = NeverYieldingConnectionSource()
        assertFailsWith<TimeoutCancellationException> {
            backgroundScope.assembleVoterMesh(
                voters = VOTERS,
                // Only the highest-ranked voter is starved; the other two accept normally, so the
                // LOWER→MIDDLE link forms and the failed mesh is partial rather than empty.
                sourceOf = { voter -> if (voter == STALLED) stalledSource else fabric.sourceOf(voter) },
                dial = fabric::dial,
                dispatcher = StandardTestDispatcher(testScheduler),
                raftConfig = voterMeshSimConfig(),
                random = Random(VOTER_MESH_SIM_SEED),
                handshakeTimeout = FORMATION_TIMEOUT * BOUND_HEADROOM,
                dialTimeout = FORMATION_TIMEOUT * BOUND_HEADROOM,
                formationTimeout = FORMATION_TIMEOUT,
                backoffBase = 20.milliseconds,
                backoffCap = 200.milliseconds,
            )
        }
        return StalledFormation(fabric, stalledSource)
    }

    /**
     * Await [this] within [OBSERVE_WINDOW] of virtual time, failing with what was being waited for. The
     * bare [withTimeout] this replaces reds with "Timed out after 20s of virtual time" and a line
     * number — the same shape of uninformative red #2226 was filed about.
     */
    private suspend fun CompletableDeferred<Unit>.awaitOrFail(expectation: String) {
        try {
            withTimeout(OBSERVE_WINDOW) { await() }
        } catch (_: TimeoutCancellationException) {
            fail("formation-failure teardown incomplete: $expectation did not happen within $OBSERVE_WINDOW")
        }
    }

    private companion object {
        /** Ranked low→high; [assembleVoterMesh] sorts by id, so the lower id dials the higher. */
        val LOWER = NodeId("voter-a")
        val MIDDLE = NodeId("voter-b")
        val STALLED = NodeId("voter-c")
        val VOTERS = listOf(LOWER, MIDDLE, STALLED)

        /** Virtual-time bound on formation. Short: the starved source makes it fire on schedule. */
        val FORMATION_TIMEOUT: Duration = 2.seconds

        /** Factor by which the handshake/dial ceilings clear [FORMATION_TIMEOUT] (see the class KDoc). */
        const val BOUND_HEADROOM: Int = 5

        /** Virtual-time bound on observing a post-failure obligation — the RED/GREEN pivot. */
        val OBSERVE_WINDOW: Duration = 20.seconds
    }
}
