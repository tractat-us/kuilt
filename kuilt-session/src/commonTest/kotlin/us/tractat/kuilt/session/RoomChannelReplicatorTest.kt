@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.replicator.ReplicatorMessage
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Integration: a [SeamReplicator] running over [Room.channel] converges correctly
 * in a 2-peer room, and the admit-gating property holds — the replicator never
 * sends frames to unadmitted peers.
 *
 * ## Admit-gating correctness
 *
 * [Room.channel] exposes a [us.tractat.kuilt.core.Seam] whose
 * [us.tractat.kuilt.core.Seam.peers] is the **admitted roster** (not raw transport
 * peers). Therefore a [SeamReplicator] over [Room.channel] will only ever send
 * FullState or deltas to peers that have completed the admit handshake. An
 * unadmitted peer that connects at the transport layer but never sends Hello is
 * absent from `channel.peers` and receives no replicator traffic.
 */
class RoomChannelReplicatorTest {

    private val replicatorConfig = SeamReplicatorConfig(expectVirtualTime = true)
    private val messageSer = ReplicatorMessage.serializer(GCounter.serializer())

    private fun gcounterReplicator(room: Room, scope: CoroutineScope): SeamReplicator<GCounter> =
        SeamReplicator(
            replica = ReplicaId(room.selfId.value),
            seam = room.channel("crdt-test"),
            initial = GCounter.ZERO,
            messageSerializer = messageSer,
            scope = scope,
            config = replicatorConfig,
        )

    // ── Convergence ───────────────────────────────────────────────────────────

    /**
     * Host and joiner each increment their GCounter slot; after admit handshake +
     * delta exchange both replicas must agree on the total.
     */
    @Test
    fun `GCounter converges over room channel in 2-peer room`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val hostRoom = SeamRoomFactory(loom, backgroundScope).host(Pattern("Alice"))
            val joinerRoom = SeamRoomFactory(loom, backgroundScope).join(InMemoryTag("Bob"))

            // Wait for admit handshake on both sides
            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val repHost = gcounterReplicator(hostRoom, backgroundScope)
            val repJoiner = gcounterReplicator(joinerRoom, backgroundScope)

            repHost.apply(repHost.state.value.inc(repHost.replica, 3L))
            repJoiner.apply(repJoiner.state.value.inc(repJoiner.replica, 5L))

            testScheduler.advanceUntilIdle()

            assertEquals(8L, repHost.state.value.value, "host counter must converge to 8")
            assertEquals(8L, repJoiner.state.value.value, "joiner counter must converge to 8")

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── Admit gating ──────────────────────────────────────────────────────────

    /**
     * An unadmitted peer connected to the underlying transport must NOT receive
     * replicator traffic. Asserted by verifying that `channel.peers` does not include
     * the unadmitted peer — the replicator only targets peers visible in `Seam.peers`.
     */
    @Test
    fun `replicator never targets unadmitted peers via channel peers`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val hostRoom = SeamRoomFactory(loom, backgroundScope).host(Pattern("Alice"))

            // Connect a raw seam that never sends Hello (never admitted)
            val unadmitted = loom.join(InMemoryTag("Unadmitted"))
            testScheduler.advanceUntilIdle()

            val hostChannel = hostRoom.channel("crdt-test")

            assertFalse(
                hostChannel.peers.value.contains(unadmitted.selfId),
                "unadmitted peer must not be in channel peers: ${hostChannel.peers.value}",
            )

            // After running the replicator and applying a mutation, the unadmitted peer
            // still must not appear — replicator uses channel.peers to target sends.
            val rep = gcounterReplicator(hostRoom, backgroundScope)
            rep.apply(rep.state.value.inc(rep.replica, 7L))
            testScheduler.advanceUntilIdle()

            assertFalse(
                hostChannel.peers.value.contains(unadmitted.selfId),
                "unadmitted peer must not appear in channel peers after replication",
            )

            unadmitted.close()
            hostRoom.leave()
        }
}
