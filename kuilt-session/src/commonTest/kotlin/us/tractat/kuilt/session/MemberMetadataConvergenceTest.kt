@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dog-fooding test: [MemberMetadata]'s backing type ([LWWMap]`<PeerId, String>`)
 * converges **live** across two admitted peers via a [Quilter] wired over
 * [Room.channel]`("member-metadata")` — no explicit [MemberMetadata.merge] call
 * is needed.
 *
 * This is Slice C of the Seam-multiplexing chain:
 * - **A** `MuxSeam` — N-way Seam combinator (kuilt-core).
 * - **B** `Room.channel(id)` — roster-backed channel views (kuilt-session).
 * - **C** MemberMetadata live convergence — this test.
 *
 * ## Precondition: one replicator per (replica, CRDT type)
 *
 * [Quilter] requires **exactly one instance per `(replica, CRDT type)`
 * pair per process**. Running two `Quilter<LWWMap<PeerId, String>>`
 * instances with the same [ReplicaId] concurrently breaks the delta-GC protocol:
 * both mint deltas starting at `seq = 1`, the recipient cannot distinguish them,
 * and replicas diverge permanently. Each [Room.channel] call already returns the
 * same [us.tractat.kuilt.core.Seam] instance for the same id (idempotent); this
 * test mirrors that by constructing one replicator per peer.
 */
class MemberMetadataConvergenceTest {

    private val replicatorConfig = QuilterConfig(expectVirtualTime = true)

    private fun memberMetadataReplicator(
        room: Room,
        scope: CoroutineScope,
    ): Quilter<LWWMap<PeerId, String>> = Quilter(
        replica = ReplicaId(room.selfId.value),
        seam = room.channel("member-metadata"),
        initial = LWWMap.empty(),
        messageSerializer = QuiltMessage.serializer(LWWMap.serializer(PeerId.serializer(), String.serializer())),
        scope = scope,
        config = replicatorConfig,
    )

    @Test
    fun `display names set by each peer converge live without explicit merge`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val testClock: () -> kotlin.time.Instant = { kotlin.time.Instant.fromEpochMilliseconds(0L) }
            val hostRoom = SeamRoomFactory(loom, backgroundScope, testClock).host(Pattern("Alice"))
            val joinerRoom = SeamRoomFactory(loom, backgroundScope, testClock).join(InMemoryTag("Bob"))

            // Wait for the admit handshake on both sides before starting replication.
            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val repHost = memberMetadataReplicator(hostRoom, backgroundScope)
            val repJoiner = memberMetadataReplicator(joinerRoom, backgroundScope)

            // Each peer sets its own display name.
            // Derive ReplicaId from PeerId to match MemberMetadata.set's tie-break contract.
            val hostReplica = ReplicaId(hostRoom.selfId.value)
            val joinerReplica = ReplicaId(joinerRoom.selfId.value)
            val hostPatch = Patch(LWWMap.empty<PeerId, String>().set(hostReplica, 1L, hostRoom.selfId, "Alice"))
            val joinerPatch = Patch(LWWMap.empty<PeerId, String>().set(joinerReplica, 1L, joinerRoom.selfId, "Bob"))

            repHost.apply(hostPatch)
            repJoiner.apply(joinerPatch)

            testScheduler.advanceUntilIdle()

            // Both replicas must agree on both names — convergence via the replicator,
            // not via an explicit MemberMetadata.merge call.
            val hostEntries = repHost.state.value.entries
            val joinerEntries = repJoiner.state.value.entries

            assertEquals(hostEntries, joinerEntries, "both replicas must converge to the same map")
            assertEquals(2, hostEntries.size, "converged map must contain both peers' names")
            assertEquals("Alice", hostEntries[hostRoom.selfId], "host's name must be present")
            assertEquals("Bob", hostEntries[joinerRoom.selfId], "joiner's name must be present")

            joinerRoom.leave()
            hostRoom.leave()
        }
}
