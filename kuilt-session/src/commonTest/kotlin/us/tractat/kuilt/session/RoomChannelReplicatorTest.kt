@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.ReplicatorMessage
import us.tractat.kuilt.quilter.SeamReplicator
import us.tractat.kuilt.quilter.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration: a [SeamReplicator] running over [Room.channel] converges correctly
 * in a 2-peer room, and the admit-gating property holds — the replicator never
 * directs FullState, Ack, or Resend to unadmitted peers.
 *
 * ## Exact admit-gating guarantee
 *
 * [Room.channel] exposes a [us.tractat.kuilt.core.Seam] whose
 * [us.tractat.kuilt.core.Seam.peers] is the **admitted roster** (not raw transport
 * peers). The [SeamReplicator] uses `Seam.peers` for its membership book
 * (`knownPeers`), so:
 *
 * - **FullState** — sent via `seam.sendTo` only to peers in `knownPeers` (admitted
 *   roster). An unadmitted transport peer never receives FullState.
 * - **Ack / Resend** — also `sendTo` gated on known peers; never directed at
 *   unadmitted peers.
 * - **Delta** — broadcast via `seam.broadcast`, which reaches **all** connected
 *   transport peers regardless of admission. This is not a bug: convergence is
 *   driven by FullState (withheld from unadmitted peers); broadcast Deltas are
 *   harmless noise to an unadmitted peer since it has no FullState base to apply
 *   them to. The guarantee is membership-gating + FullState confidentiality, **not**
 *   wire-level broadcast confidentiality for deltas.
 *
 * This class tests both the indirect property (unadmitted peer absent from
 * `channel.peers`) and the direct property (FullState is never delivered to an
 * unadmitted peer's raw transport seam).
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

    private fun collectIncoming(seam: Seam, scope: CoroutineScope): Pair<MutableList<Swatch>, Job> {
        val frames = mutableListOf<Swatch>()
        val job = scope.launch { seam.incoming.collect { frames += it } }
        return frames to job
    }

    /**
     * Strip 3-byte channel framing and decode as [ReplicatorMessage].
     * Returns null if the bytes are not a recognizable channel-framed replicator message.
     */
    private fun decodeChannelFrame(payload: ByteArray): ReplicatorMessage<GCounter>? {
        if (!RoomChannel.isChannelFrame(payload)) return null
        val inner = payload.copyOfRange(3, payload.size)
        return runCatching { Cbor.decodeFromByteArray(messageSer, inner) }.getOrNull()
    }

    // ── Convergence ───────────────────────────────────────────────────────────

    /**
     * Host and joiner each increment their GCounter slot; after admit handshake +
     * delta exchange both replicas must agree on the total.
     */
    @Test
    fun `GCounter converges over room channel in 2-peer room`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val testClock: () -> kotlin.time.Instant = { kotlin.time.Instant.fromEpochMilliseconds(0L) }
            val hostRoom = SeamRoomFactory(loom, backgroundScope, testClock).host(Pattern("Alice"))
            val joinerRoom = SeamRoomFactory(loom, backgroundScope, testClock).join(InMemoryTag("Bob"))

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
     * An unadmitted peer connected to the underlying transport must NOT appear in
     * `channel.peers` — asserted both before and after the replicator applies a mutation.
     *
     * This is the indirect property: the replicator uses `channel.peers` to build its
     * membership book, so absence here means no `sendTo` call targets the unadmitted peer.
     */
    @Test
    fun `replicator never targets unadmitted peers via channel peers`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val testClock: () -> kotlin.time.Instant = { kotlin.time.Instant.fromEpochMilliseconds(0L) }
            val hostRoom = SeamRoomFactory(loom, backgroundScope, testClock).host(Pattern("Alice"))

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

    /**
     * Direct wire-level assertion: an unadmitted transport peer receives broadcast
     * Delta frames (see class KDoc for why that is acceptable) but NEVER receives a
     * FullState, Ack, or Resend frame from the replicator.
     *
     * FullState is the base that enables a peer to apply Deltas; withholding it from
     * unadmitted peers is the critical admission-gate guarantee. This test would fail if
     * `channel.peers` were changed to include raw transport peers, because the replicator
     * would then call `sendFullStateTo` for the unadmitted peer.
     */
    @Test
    fun `replicator never delivers FullState to unadmitted transport peer`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val testClock: () -> kotlin.time.Instant = { kotlin.time.Instant.fromEpochMilliseconds(0L) }
            val hostRoom = SeamRoomFactory(loom, backgroundScope, testClock).host(Pattern("Alice"))

            // Connect a raw seam that never sends Hello (never admitted).
            val unadmitted = loom.join(InMemoryTag("Unadmitted"))

            // Start collecting all frames arriving on the unadmitted seam's transport channel.
            val (rawFrames, collectJob) = collectIncoming(unadmitted, backgroundScope)

            val rep = gcounterReplicator(hostRoom, backgroundScope)
            rep.apply(rep.state.value.inc(rep.replica, 7L))
            testScheduler.advanceUntilIdle()
            collectJob.cancel()

            val replicatorMessages = rawFrames.mapNotNull { decodeChannelFrame(it.payload) }

            // Deltas are broadcast and therefore do arrive — that is the documented behaviour.
            assertTrue(
                replicatorMessages.any { it is ReplicatorMessage.Delta },
                "at least one broadcast Delta should be observable on the raw transport seam",
            )

            // FullState, Ack, and Resend are sendTo operations gated on admitted peers.
            // None of them should ever reach an unadmitted transport peer.
            assertFalse(
                replicatorMessages.any { it is ReplicatorMessage.FullState },
                "FullState must never be sent to an unadmitted peer: ${replicatorMessages.filterIsInstance<ReplicatorMessage.FullState<GCounter>>()}",
            )
            assertFalse(
                replicatorMessages.any { it is ReplicatorMessage.Ack },
                "Ack must never be sent to an unadmitted peer",
            )
            assertFalse(
                replicatorMessages.any { it is ReplicatorMessage.Resend },
                "Resend must never be sent to an unadmitted peer",
            )

            unadmitted.close()
            hostRoom.leave()
        }
}
