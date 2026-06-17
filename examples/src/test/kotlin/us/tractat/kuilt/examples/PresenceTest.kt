@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.EphemeralMapTracker
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Example: live presence / "who's typing" indicator using [EphemeralMap] + [Quilter].
 *
 * Each peer publishes its own presence entry (e.g. which field it is currently editing).
 * A per-replica monotonic clock orders updates within a slot; a higher-clock entry
 * always wins. Entries expire on observers that stop receiving heartbeats within a TTL.
 *
 * ## Why EphemeralMap fits
 *
 * - Each peer writes only its own slot — no coordination or consensus is required.
 * - The clock ensures that a stale re-delivery never overwrites a fresher heartbeat.
 * - Graceful departure (null + higher clock) is explicitly modelled so a clean logout
 *   removes the entry immediately rather than waiting for the TTL to expire.
 * - The CRDT itself carries no real-clock timestamps; observers measure staleness
 *   against their own local receive time, so cross-peer clock skew cannot corrupt
 *   the data.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [Quilter] convenience factory with `EphemeralMap.serializer(String.serializer())`
 * - [Quilter.mutate] with [EphemeralMap.put] / [EphemeralMap.leave]
 * - [EphemeralMapTracker] with an injectable clock for deterministic TTL eviction
 */
class PresenceTest {

    private val replicatorCfg = QuilterConfig(expectVirtualTime = true)

    @Test
    fun `two peers publish presence and both entries are visible after sync`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("presence"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceReplica = ReplicaId(seamAlice.selfId.value)
            val bobReplica = ReplicaId(seamBob.selfId.value)

            val aliceMap = Quilter(
                seamAlice,
                EphemeralMap.empty<String>(),
                EphemeralMap.serializer(String.serializer()),
                backgroundScope,
                config = replicatorCfg,
            )
            val bobMap = Quilter(
                seamBob,
                EphemeralMap.empty<String>(),
                EphemeralMap.serializer(String.serializer()),
                backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice publishes her presence: she is editing the "title" field.
            aliceMap.mutate { Patch(it.put(aliceReplica, "editing:title", clock = 1L)) }

            // Bob publishes his presence: he is editing the "body" field.
            bobMap.mutate { Patch(it.put(bobReplica, "editing:body", clock = 1L)) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas must see both presence entries in the raw CRDT state.
            val aliceEntries = aliceMap.state.value.entries
            val bobEntries = bobMap.state.value.entries
            assertEquals(2, aliceEntries.size)
            assertEquals(2, bobEntries.size)
            assertEquals("editing:title", aliceEntries[aliceReplica]?.value)
            assertEquals("editing:body", bobEntries[bobReplica]?.value)
        }

    @Test
    fun `graceful departure removes peer from live view immediately`() {
        // Time-free CRDT properties can be verified without the network layer.
        val aliceReplica = ReplicaId("alice")
        val bobReplica = ReplicaId("bob")

        // Both peers are present.
        val withBoth = EphemeralMap.empty<String>()
            .put(aliceReplica, "editing:title", clock = 1L)
            .put(bobReplica, "editing:body", clock = 1L)

        // Bob leaves gracefully: clock incremented to 2 with null value.
        val afterBobLeaves = withBoth.leave(bobReplica, clock = 2L)

        // Use a tracker with a controlled clock to check live() output.
        val clock = FakeClock(now = 1000L)
        val tracker = EphemeralMapTracker<String>(ttlMs = 30_000L, clock = clock::now)
        tracker.received(afterBobLeaves)

        val live = tracker.live()
        assertTrue(aliceReplica in live, "Alice must remain present")
        assertFalse(bobReplica in live, "Bob must be absent after graceful departure")
    }

    @Test
    fun `stale entry expires after TTL even without a graceful departure`() {
        val aliceReplica = ReplicaId("alice")
        val bobReplica = ReplicaId("bob")

        val map = EphemeralMap.empty<String>()
            .put(aliceReplica, "here", clock = 1L)
            .put(bobReplica, "here", clock = 1L)

        // Alice's update arrives at t=0; Bob's update arrives later at t=5000.
        val clock = FakeClock(now = 0L)
        val tracker = EphemeralMapTracker<String>(ttlMs = 10_000L, clock = clock::now)
        tracker.received(EphemeralMap.empty<String>().put(aliceReplica, "here", clock = 1L))
        clock.advance(5_000L)
        tracker.received(EphemeralMap.empty<String>().put(bobReplica, "here", clock = 1L))

        // At t=10000 Alice's entry has exactly hit the TTL boundary (exclusive → expired).
        // Bob's entry was received at t=5000 so it is still fresh (10000-5000 < 10000).
        clock.advance(5_000L)
        val live = tracker.live()
        assertFalse(aliceReplica in live, "Alice's entry must be expired at the TTL boundary")
        assertTrue(bobReplica in live, "Bob's entry must still be live")
    }

    // ---- helpers ----

    private class FakeClock(private var now: Long) {
        fun now(): Long = now
        fun advance(ms: Long) { now += ms }
    }
}
