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
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a member roster whose values are themselves CRDTs, replicated across two peers using
 * [ORMap]`<String, GCounter>` + [Quilter].
 *
 * The outer [ORMap] maintains observed-remove membership (add-wins on the key), while the inner
 * [GCounter] per member accumulates per-replica contributions — for example, a tally of
 * check-ins or action counts attributed to that member. Both layers merge independently and
 * correctly under concurrent updates.
 *
 * ## Why ORMap fits
 *
 * - **Add-wins semantics**: a concurrent `put` of a key survives a concurrent `remove` of the same
 *   key — the member is never inadvertently deleted by a stale tombstone.
 * - **Nested value merge**: the `GCounter` values on each side are joined via their own `piece`,
 *   so contributions from both replicas are summed, not overwritten.
 * - **Safe remove**: a `remove` only tombstones the exact presence tags the remover observed. A
 *   tag minted by a concurrent `put` was not observed and therefore survives.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [Quilter] convenience factory with a two-type-param serializer
 * - `apply(Patch(...))` to broadcast `put` and `remove` mutations (ORMap returns new state, not Patch)
 * - [Quilter.state] (`StateFlow<ORMap<String, GCounter>>`) to read the current roster
 */
class MemberRosterORMapTest {

    private val replicatorCfg = QuilterConfig(expectVirtualTime = true)
    private val mapSerializer = ORMap.serializer(String.serializer(), GCounter.serializer())

    @Test
    fun `two peers add different members and both appear in the converged roster`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("member-roster"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceRoster = Quilter(seamAlice, ORMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)
            val bobRoster = Quilter(seamBob, ORMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice adds herself with a check-in count of 1.
            aliceRoster.apply(Patch(aliceRoster.state.value.put(aliceRoster.replica, "alice", GCounter.of(aliceRoster.replica to 1L))))

            // Bob adds himself with a check-in count of 2.
            bobRoster.apply(Patch(bobRoster.state.value.put(bobRoster.replica, "bob", GCounter.of(bobRoster.replica to 2L))))

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas converge to the same roster with both members present.
            assertTrue("alice" in aliceRoster.state.value.keys)
            assertTrue("bob" in aliceRoster.state.value.keys)
            assertEquals(aliceRoster.state.value.keys, bobRoster.state.value.keys)

            // The nested GCounter values are also present.
            assertEquals(1L, aliceRoster.state.value["alice"]?.value)
            assertEquals(2L, bobRoster.state.value["bob"]?.value)
        }

    @Test
    fun `nested GCounter values from both peers merge when both hold the same key`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("shared-counter"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceRoster = Quilter(seamAlice, ORMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)
            val bobRoster = Quilter(seamBob, ORMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Both peers put "shared-task" with their own GCounter slot — these merge via GCounter.piece.
            aliceRoster.apply(Patch(aliceRoster.state.value.put(aliceRoster.replica, "shared-task", GCounter.of(aliceRoster.replica to 3L))))
            bobRoster.apply(Patch(bobRoster.state.value.put(bobRoster.replica, "shared-task", GCounter.of(bobRoster.replica to 5L))))

            delay(10) // advance virtual time so all delta broadcasts deliver

            // The nested GCounters are joined: 3 (alice) + 5 (bob) = 8.
            assertEquals(8L, aliceRoster.state.value["shared-task"]?.value)
            assertEquals(aliceRoster.state.value["shared-task"]?.value, bobRoster.state.value["shared-task"]?.value)
        }

    @Test
    fun `a concurrent put survives a concurrent remove — add-wins on the key`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("add-wins-roster"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceRoster = Quilter(seamAlice, ORMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)
            val bobRoster = Quilter(seamBob, ORMap.empty(), mapSerializer, backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice puts "charlie" on her replica.
            aliceRoster.apply(Patch(aliceRoster.state.value.put(aliceRoster.replica, "charlie", GCounter.of(aliceRoster.replica to 1L))))

            delay(10) // let the put propagate to Bob

            // Bob observes "charlie" and then removes it.
            assertTrue("charlie" in bobRoster.state.value.keys)
            bobRoster.apply(Patch(bobRoster.state.value.remove("charlie")))

            // Alice concurrently puts "charlie" again with a fresh tag — Bob hasn't seen this tag.
            aliceRoster.apply(Patch(aliceRoster.state.value.put(aliceRoster.replica, "charlie", GCounter.of(aliceRoster.replica to 2L))))

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Alice's concurrent re-add used a fresh presence tag not seen by Bob's remove,
            // so the add-wins: "charlie" survives on both replicas.
            assertTrue("charlie" in aliceRoster.state.value.keys)
            assertTrue("charlie" in bobRoster.state.value.keys)

            // A member that was only removed (not re-added) stays absent.
            assertFalse("nobody" in aliceRoster.state.value.keys)
        }
}
