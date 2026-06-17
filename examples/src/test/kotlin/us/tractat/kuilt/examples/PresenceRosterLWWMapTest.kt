@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a presence/roster map replicated across two peers using [LWWMap] + [SeamReplicator].
 *
 * Each peer updates its own member's status in the map. [LWWMap] resolves concurrent writes to
 * the *same key* by last-writer-wins (the write with the highest timestamp survives). Writes to
 * *different keys* are always independent and both survive — so each peer owning its own key slot
 * never conflicts with another peer.
 *
 * ## Why LWWMap fits
 *
 * - Presence state is naturally "last known wins": a status update always supersedes an older one,
 *   and concurrent edits to the same peer's status should resolve deterministically.
 * - Different roster members are independent: Alice updating her status has no effect on Bob's slot.
 * - The map grows and shrinks at the key level (via `set` — there is no remove; use a sentinel
 *   value like `"offline"` to mark absence), making it suitable for small, bounded rosters.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [SeamReplicator] convenience factory with a two-type-param serializer
 * - `apply(Patch(...))` to broadcast a `set` mutation (LWWMap.set returns a new state, not a Patch)
 * - [SeamReplicator.state] (`StateFlow<LWWMap<String, String>>`) to read the current roster
 *
 * ## Clock-skew note
 *
 * These tests use a monotonically increasing counter as the timestamp source. In production,
 * use a real monotonic clock per replica, and be aware that LWWMap's last-writer-wins resolution
 * is only meaningful when clocks are well-synchronized (see [LWWMap] KDoc for the caveat).
 */
class PresenceRosterLWWMapTest {

    private val replicatorCfg = SeamReplicatorConfig(expectVirtualTime = true)
    private val mapSerializer = LWWMap.serializer(String.serializer(), String.serializer())

    @Test
    fun `two peers update different members and both appear in the converged roster`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("presence-roster"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceRoster = SeamReplicator(seamAlice, LWWMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)
            val bobRoster = SeamReplicator(seamBob, LWWMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice marks herself as "online".
            aliceRoster.apply(Patch(aliceRoster.state.value.set(aliceRoster.replica, timestamp = 1L, key = "alice", value = "online")))

            // Bob marks himself as "busy".
            bobRoster.apply(Patch(bobRoster.state.value.set(bobRoster.replica, timestamp = 1L, key = "bob", value = "busy")))

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas converge to the same roster with both members.
            assertEquals("online", aliceRoster.state.value["alice"])
            assertEquals("busy", aliceRoster.state.value["bob"])
            assertEquals(aliceRoster.state.value.entries, bobRoster.state.value.entries)
        }

    @Test
    fun `a status update on the same key wins by higher timestamp and converges`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("status-update"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceRoster = SeamReplicator(seamAlice, LWWMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)
            val bobRoster = SeamReplicator(seamBob, LWWMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice sets a status with timestamp 5 — "away".
            aliceRoster.apply(Patch(aliceRoster.state.value.set(aliceRoster.replica, timestamp = 5L, key = "alice", value = "away")))

            // Bob sets the same key with a later timestamp 10 — "online" should win.
            bobRoster.apply(Patch(bobRoster.state.value.set(bobRoster.replica, timestamp = 10L, key = "alice", value = "online")))

            delay(10) // advance virtual time so all delta broadcasts deliver

            // The higher-timestamp write (ts=10, "online") wins on both replicas.
            assertEquals("online", aliceRoster.state.value["alice"])
            assertEquals("online", bobRoster.state.value["alice"])

            // A key that was never written stays absent.
            assertNull(aliceRoster.state.value["charlie"])
        }
}
