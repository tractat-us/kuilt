@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GamePresenceTest {

    /**
     * Two [GamePresence] replicas over connected seams converge: a host declaration
     * on one is visible on the other after Quilter delta delivery.
     */
    @Test
    fun hostDeclarationConverges() = runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val (s1, s2) = seats(loom, 2)

        val p1 = GamePresence(s1, backgroundScope)
        val p2 = GamePresence(s2, backgroundScope)

        p1.declareHost()

        testScheduler.advanceUntilIdle()

        assertContains(p2.declaredHosts(), ReplicaId(s1.selfId.value))
    }

    /**
     * A second [gameHost] on the same session detects the first host's declaration and
     * throws [DuplicateHostException] before bootstrapping Raft.
     *
     * Both hosts are launched concurrently so their presence channels are set up
     * simultaneously — the Quilter full-state exchange then propagates s1's declaration
     * to s2 before either checks, making the detection deterministic under virtual time.
     */
    @Test
    fun secondHostFailsFast() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val (s1, s2) = seats(loom, 2)
        val cfg = fastRaftConfig(seed = 1L)

        // supervisorScope prevents a failing async child from cancelling the test scope
        // before assertFailsWith can catch the DuplicateHostException.
        supervisorScope {
            val h1 = async { backgroundScope.gameHost(s1, peerCount = 2, raftConfig = cfg, clock = inertTestClock) }
            val h2 = async { backgroundScope.gameHost(s2, peerCount = 2, raftConfig = cfg, clock = inertTestClock) }

            val ex = assertFailsWith<DuplicateHostException> { h2.await() }
            assertTrue(ex.message!!.contains("host"), "message should mention 'host': ${ex.message}")

            h1.cancel()
        }
    }

    /**
     * Regression for #580: the duplicate-host check must wait for presence to *actually*
     * converge with the connected peers, not a fixed wall-clock window.
     *
     * Each peer's frames are delayed 50 ms (virtual time) via [FaultySeam] — far longer than
     * the old fixed 1 ms convergence window. Under that old code each host would check at
     * t≈1 ms, see no other declaration (it arrives at t≈50 ms), and both would bootstrap
     * singleton clusters that can never merge. The bounded convergence wait instead suspends
     * until it has heard every connected peer's presence declaration, so the second host's
     * declaration is observed and [DuplicateHostException] is thrown.
     */
    @Test
    fun secondHostDetectedDespiteDeliveryLatency() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val (raw1, raw2) = seats(loom, 2)
        val s1 = FaultySeam(raw1, backgroundScope, FaultProfile.DelayAll(50.milliseconds))
        val s2 = FaultySeam(raw2, backgroundScope, FaultProfile.DelayAll(50.milliseconds))
        val cfg = fastRaftConfig(seed = 1L)

        supervisorScope {
            val h1 = async { backgroundScope.gameHost(s1, peerCount = 2, raftConfig = cfg, clock = inertTestClock) }
            val h2 = async { backgroundScope.gameHost(s2, peerCount = 2, raftConfig = cfg, clock = inertTestClock) }

            assertFailsWith<DuplicateHostException> { h2.await() }
            h1.cancel()
        }
    }

    /**
     * #584: a *simultaneous* duplicate-host race is arbitrated deterministically by lowest
     * NodeId, rather than every declared host throwing.
     *
     * [seats] assigns `peer-1` to the host seat and `peer-2` to the next — so `s1` holds the
     * lower NodeId. With both peers calling [gameHost] concurrently, the lower-NodeId peer
     * (`s1`) must *win* (proceed to bootstrap, never throw) while the higher-NodeId peer
     * (`s2`) loses and fails fast with [DuplicateHostException]. Before #584 *both* threw,
     * leaving the session with no host at all.
     */
    @Test
    fun simultaneousHostsArbitrateToLowestNodeId() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val (s1, s2) = seats(loom, 2)
        val cfg = fastRaftConfig(seed = 1L)

        supervisorScope {
            val winner = async { backgroundScope.gameHost(s1, peerCount = 2, raftConfig = cfg, clock = inertTestClock) }
            val loser = async { backgroundScope.gameHost(s2, peerCount = 2, raftConfig = cfg, clock = inertTestClock) }

            // Higher-NodeId host loses arbitration and fails fast.
            assertFailsWith<DuplicateHostException> { loser.await() }
            // Lower-NodeId host won: it did not throw — it proceeded past the duplicate check
            // and is now bootstrapping (blocked awaiting the second voter that never joins).
            assertTrue(winner.isActive, "lowest-NodeId host must win arbitration and keep running")
            winner.cancel()
        }
    }

    /**
     * A NodeId whose value contains the marker delimiters (',' or ':sc') must round-trip
     * through the admission-closed encoding without corrupting the voter set.
     */
    @Test
    fun admissionClosedRoundTripsNodeIdsContainingDelimiters() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val s1 = seats(loom, 1).single()
            val presence = GamePresence(s1, backgroundScope)

            val voters = setOf(NodeId("peer,with,commas"), NodeId("ends:sc"), NodeId("plain"))
            presence.declareAdmissionClosed(voters)
            testScheduler.advanceUntilIdle()

            assertEquals(voters, presence.admissionClosed.value)
        }

    /**
     * #3689: reopening admission must not erase the spectators-closed mark.
     *
     * An eviction calls [GamePresence.declareAdmissionOpen], and the spectator manager publishes
     * `:sc` only once and then exits, so nothing would put the mark back. A spectator arriving
     * later would then wait out its backstop instead of being refused.
     */
    @Test
    fun admissionOpenKeepsSpectatorsClosedMark() = runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val presence = GamePresence(seats(loom, 1).single(), backgroundScope)

        presence.declareHost()
        presence.declareSpectatorsClosed()
        testScheduler.advanceUntilIdle()
        val beforeReopen = presence.selfSlot()

        presence.declareAdmissionOpen()
        testScheduler.advanceUntilIdle()
        val afterReopen = presence.selfSlot()

        assertAll(
            { assertEquals("host:sc", beforeReopen?.value, "precondition: the board must carry :sc before the reopen") },
            { assertEquals("host:sc", afterReopen?.value, "a reopen on an open, closed-to-spectators slot changes nothing") },
            { assertEquals(beforeReopen?.clock, afterReopen?.clock, "a no-op reopen must not publish a new declaration") },
            { assertTrue(presence.spectatorsClosed.value, "spectators must stay closed after the reopen") },
        )
    }

    /**
     * #3689, the eviction's whole sequence on the host slot: closed with `:sc`, reopened by the
     * eviction, then closed again with the refreshed voter set. The mark must survive both writes,
     * and the reopen must still reopen.
     */
    @Test
    fun spectatorsClosedMarkSurvivesReopenAndReclose() = runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val presence = GamePresence(seats(loom, 1).single(), backgroundScope)
        val first = setOf(NodeId("a"), NodeId("b"))
        val refreshed = setOf(NodeId("a"), NodeId("c"))

        presence.declareHost()
        presence.declareAdmissionClosed(first)
        presence.declareSpectatorsClosed()
        testScheduler.advanceUntilIdle()
        val closedSlot = presence.selfSlot()?.value

        presence.declareAdmissionOpen()
        testScheduler.advanceUntilIdle()
        val reopenedSlot = presence.selfSlot()?.value
        val reopenedAdmission = presence.admissionClosed.value
        val reopenedSpectators = presence.spectatorsClosed.value

        presence.declareAdmissionClosed(refreshed)
        testScheduler.advanceUntilIdle()

        assertAll(
            {
                assertTrue(
                    closedSlot != null && closedSlot.startsWith("admission-closed:") && closedSlot.endsWith(":sc"),
                    "precondition: the board must carry admission-closed with :sc before the reopen, was $closedSlot",
                )
            },
            { assertEquals("host:sc", reopenedSlot, "the reopen writes host and keeps :sc") },
            { assertEquals(null, reopenedAdmission, "the reopen must still reopen admission") },
            { assertTrue(reopenedSpectators, "spectators must stay closed while admission is open") },
            { assertEquals(refreshed, presence.admissionClosed.value, "the re-close publishes the refreshed voter set") },
            { assertTrue(presence.spectatorsClosed.value, "spectators must stay closed after the re-close") },
            { assertTrue(presence.selfSlot()?.value?.endsWith(":sc") == true, "the re-closed slot keeps :sc") },
        )
    }
}
