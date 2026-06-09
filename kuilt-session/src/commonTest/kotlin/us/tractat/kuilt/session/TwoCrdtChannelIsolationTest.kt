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
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.replicator.ReplicatorMessage
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

/**
 * Integration: two distinct CRDTs replicate concurrently over **one** [Room] via
 * separate [Room.channel] ids, with zero cross-talk.
 *
 * - `channel("counter")` carries a [GCounter] — increments must not affect the map.
 * - `channel("map")` carries a [LWWMap]`<String, String>` — map entries must not affect the counter.
 *
 * This exercises the end-to-end [MuxSeam] / channel-framing isolation property:
 * frames tagged with one channel sub-id are stripped before delivery and are never
 * visible to a collector on a different channel.
 *
 * **Second hand-wired call site.** This test file is a second independent call site
 * that wires a `SeamReplicator` over a `Room.channel` (alongside
 * `RoomChannelReplicatorTest`). Its existence directly justifies issue #243's
 * proposed convenience wrapper — the boilerplate is non-trivial and already repeated
 * twice.
 */
class TwoCrdtChannelIsolationTest {

    private val replicatorConfig = SeamReplicatorConfig(expectVirtualTime = true)

    private fun gcounterReplicator(room: Room, scope: CoroutineScope): SeamReplicator<GCounter> =
        SeamReplicator(
            replica = ReplicaId(room.selfId.value),
            seam = room.channel("counter"),
            initial = GCounter.ZERO,
            messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
            scope = scope,
            config = replicatorConfig,
        )

    private fun lwwMapReplicator(room: Room, scope: CoroutineScope): SeamReplicator<LWWMap<String, String>> =
        SeamReplicator(
            replica = ReplicaId(room.selfId.value),
            seam = room.channel("map"),
            initial = LWWMap.empty(),
            messageSerializer = ReplicatorMessage.serializer(LWWMap.serializer(String.serializer(), String.serializer())),
            scope = scope,
            config = replicatorConfig,
        )

    /**
     * Host increments the GCounter and sets a map entry; joiner does the same.
     * After convergence:
     * - both counter replicas agree on the sum
     * - both map replicas agree on both entries
     * - counter value is unaffected by map operations
     * - map entries are unaffected by counter operations
     */
    @Test
    fun `GCounter and LWWMap converge independently over distinct channels`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()

            val hostRoom = SeamRoomFactory(loom, backgroundScope).host(Pattern("Host"))
            val joinerRoom = SeamRoomFactory(loom, backgroundScope).join(InMemoryTag("Joiner"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val hostCounter = gcounterReplicator(hostRoom, backgroundScope)
            val joinerCounter = gcounterReplicator(joinerRoom, backgroundScope)

            val hostMap = lwwMapReplicator(hostRoom, backgroundScope)
            val joinerMap = lwwMapReplicator(joinerRoom, backgroundScope)

            // Apply mutations on both CRDTs from both peers.
            val hostReplica = hostCounter.replica
            val joinerReplica = joinerCounter.replica

            hostCounter.apply(hostCounter.state.value.inc(hostReplica, 4L))
            joinerCounter.apply(joinerCounter.state.value.inc(joinerReplica, 6L))

            hostMap.apply(Patch(LWWMap.empty<String, String>().set(hostReplica, 1L, "color", "red")))
            joinerMap.apply(Patch(LWWMap.empty<String, String>().set(joinerReplica, 1L, "size", "large")))

            testScheduler.advanceUntilIdle()

            // ── Counter convergence ───────────────────────────────────────────
            assertAll(
                { assertEquals(10L, hostCounter.state.value.value, "host counter must converge to 10") },
                { assertEquals(10L, joinerCounter.state.value.value, "joiner counter must converge to 10") },
            )

            // ── Map convergence ────────────────────────────────────────────────
            val hostEntries = hostMap.state.value.entries
            val joinerEntries = joinerMap.state.value.entries

            assertAll(
                { assertEquals(hostEntries, joinerEntries, "both map replicas must converge to the same map") },
                { assertEquals("red", hostEntries["color"], "map must contain host's entry") },
                { assertEquals("large", hostEntries["size"], "map must contain joiner's entry") },
            )

            // ── No cross-talk: counter mutations did not write into the map ────
            assertAll(
                { assertNull(hostMap.state.value["counter"], "counter mutations must not appear in map") },
                { assertNull(joinerMap.state.value["counter"], "counter mutations must not appear in map") },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    /**
     * Channel isolation property: mutations on [channel("counter")] do not change
     * the replica state of [channel("map")] and vice versa.
     *
     * This is a direct end-to-end test of the MuxSeam framing isolation: if a frame
     * on one channel were to leak to another, the foreign CBOR payload would either
     * be decoded as the wrong type (causing a decode error that is swallowed) or
     * corrupt the state. Here we assert sizes remain as expected with no extra entries.
     */
    @Test
    fun `channel frames do not leak between counter and map channels`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()

            val hostRoom = SeamRoomFactory(loom, backgroundScope).host(Pattern("Host"))
            val joinerRoom = SeamRoomFactory(loom, backgroundScope).join(InMemoryTag("Joiner"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val hostCounter = gcounterReplicator(hostRoom, backgroundScope)
            val joinerCounter = gcounterReplicator(joinerRoom, backgroundScope)

            val hostMap = lwwMapReplicator(hostRoom, backgroundScope)

            // Apply many counter increments; map should stay empty.
            repeat(5) { i ->
                hostCounter.apply(hostCounter.state.value.inc(hostCounter.replica, (i + 1).toLong()))
            }
            joinerCounter.apply(joinerCounter.state.value.inc(joinerCounter.replica, 1L))

            testScheduler.advanceUntilIdle()

            // Map must remain empty — counter traffic must not corrupt it.
            assertEquals(
                emptyMap<String, String>(),
                hostMap.state.value.entries,
                "counter frames must not leak into map channel; map must stay empty",
            )

            // Counter must have converged correctly (1+2+3+4+5+1 = 16).
            assertEquals(16L, hostCounter.state.value.value, "counter must converge to 16")
            assertEquals(16L, joinerCounter.state.value.value, "joiner counter must match host")

            joinerRoom.leave()
            hostRoom.leave()
        }
}
