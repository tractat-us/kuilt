@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * #1994's done criterion: two spokes of a star fabric, each with a [Quilter] over the same
 * `Room.channel`, converge.
 *
 * Before the relay this was impossible. `RoomChannelSeam` publishes the **roster** as `Seam.peers`
 * but routed `sendTo` through the **transport**, and on a star those are different sets — so a
 * Quilter targeted co-members it could never address. `broadcast` did not save it either:
 * `MuxServerLoom.readLoop` spools a spoke's frame into the *host's* incoming and stops, pinned by
 * #1588's `spokeFramesReachOnlyTheHostNeverAnotherSpoke`.
 */
class StarQuilterConvergenceTest {

    /** Generous wedge backstop, not an assertion (#1739, #1891). */
    private val backstop = 30.seconds

    /**
     * Comfortably past the Quilter's anti-entropy interval. *Virtual* time, so this costs nothing —
     * unlike the wall-clock [backstop], this one is allowed to be a real bound.
     */
    private val convergenceBudget = 10.seconds

    private val quilterConfig = QuilterConfig(expectVirtualTime = true)

    private fun setReplicator(room: Room, scope: CoroutineScope): Quilter<GSet<String>> = Quilter(
        replica = ReplicaId(room.selfId.value),
        seam = room.channel("star-set"),
        initial = GSet.empty(),
        messageSerializer = QuiltMessage.serializer(GSet.serializer(String.serializer())),
        scope = scope,
        config = quilterConfig,
    )

    @Test
    fun `a mutation on one spoke becomes observable on the other`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar()

            // Quilter auto-starts — there is no start().
            val onA = setReplicator(star.joinerA.room, backgroundScope)
            val onB = setReplicator(star.joinerB.room, backgroundScope)
            testScheduler.runCurrent()

            onA.apply(onA.state.value.add("from-a"))
            onB.apply(onB.state.value.add("from-b"))

            // Bounded advancement — never advanceUntilIdle(); the Quilter's anti-entropy timer
            // re-arms forever, so the idle state is never reached.
            testScheduler.advanceTimeBy(convergenceBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertTrue(
                        "from-a" in onB.state.value.elements,
                        "A's mutation must reach B — the #1994 done criterion. " +
                            "Got ${onB.state.value.elements}",
                    )
                },
                {
                    assertTrue(
                        "from-b" in onA.state.value.elements,
                        "and symmetrically. Got ${onA.state.value.elements}",
                    )
                },
                // Positive control on the harness itself: a run where neither replicator saw its
                // OWN mutation would be measuring nothing at all.
                { assertTrue("from-a" in onA.state.value.elements) },
                { assertTrue("from-b" in onB.state.value.elements) },
            )
        }
}
