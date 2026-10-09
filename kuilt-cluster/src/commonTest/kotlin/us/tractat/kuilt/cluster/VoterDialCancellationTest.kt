@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the cancellation-clean contract of the caller's dial, not cleanup inside kuilt: until
 * [InMemoryVoterFabric.dial] returns, assembly has no handle it could close. The fixture exposes
 * that window through [InMemoryVoterFabric.openLink], without starting a mesh or a Raft node.
 */
class VoterDialCancellationTest {
    @Test
    fun cancellationBeforeDialReturnsClosesBothEnds() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dialer = NodeId("dialer")
            val target = NodeId("target")
            val fabric = PausedVoterFabric(listOf(dialer, target))
            var returned = false
            var cancellationPropagated = false
            val dialing = launch {
                try {
                    fabric.dial(dialer, PeerId(target.value))
                    returned = true
                } catch (cancelled: CancellationException) {
                    cancellationPropagated = true
                    throw cancelled
                }
            }
            runCurrent()
            // Assert the rig fired before reading the pair; never await an un-reached fixture.
            assertAll(
                { assertTrue(fabric.created.isCompleted, "pair created before cancellation") },
                { assertFalse(fabric.resumeDial.isCompleted, "dial is suspended at the return gate") },
                { assertTrue(dialing.isActive, "dial is still in flight") },
                { assertFalse(returned, "caller has not received the connection") },
            )
            val (dialerEnd, acceptorEnd) = fabric.created.await()
            // A pair closes only its own outbound spool, so observing both incoming flows finish
            // proves both ends closed. No close counters or timeout can stand in for that evidence.
            val dialerIncoming = launch { dialerEnd.incoming.collect {} }
            val acceptorIncoming = launch { acceptorEnd.incoming.collect {} }
            try {
                runCurrent()
                assertAll(
                    { assertTrue(dialerIncoming.isActive, "dialer end starts open") },
                    { assertTrue(acceptorIncoming.isActive, "acceptor end starts open") },
                )
                // Queue a successful resumption, then cancel before the dispatcher can deliver it.
                // The await takes cancellation instead of returning the already-created pair.
                fabric.resumeDial.complete(Unit)
                dialing.cancel()
                runCurrent()
                assertAll(
                    { assertTrue(dialing.isCompleted, "cancelled dial finished") },
                    { assertTrue(cancellationPropagated, "dial propagated cancellation") },
                    { assertFalse(returned, "cancelled dial never handed off a connection") },
                    { assertTrue(dialerIncoming.isCompleted, "dialer end incoming closed") },
                    { assertTrue(acceptorIncoming.isCompleted, "acceptor end incoming closed") },
                )
            } finally {
                // Also release the deliberately leaky mutant after an assertion fails.
                withContext(NonCancellable) {
                    dialerEnd.close()
                    acceptorEnd.close()
                }
            }
        }

    /** A compliant caller-owned dial with an observable pause after allocation, before hand-off. */
    private class PausedVoterFabric(voters: List<NodeId>) : InMemoryVoterFabric(voters) {
        val created = CompletableDeferred<Pair<Connection, Connection>>()
        val resumeDial = CompletableDeferred<Unit>()

        override suspend fun openLink(edge: VoterEdge): Pair<Connection, Connection> {
            val pair = connectionPair()
            created.complete(pair)
            try {
                resumeDial.await()
                return pair
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    pair.first.close()
                    pair.second.close()
                }
                throw cancelled
            }
        }
    }
}
