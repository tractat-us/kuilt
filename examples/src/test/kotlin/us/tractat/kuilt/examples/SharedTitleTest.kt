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
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a shared document title replicated across two peers using [LWWRegister]
 * + [Quilter].
 *
 * The title is a single mutable string owned by whoever wrote last. [LWWRegister]
 * resolves conflicts deterministically by comparing `(timestamp, replicaId)` tags,
 * so the peer with the largest tag always wins — no coordinator needed.
 *
 * ## Why LWWRegister fits
 *
 * - There is one canonical value at any moment: the document title, a presence
 *   status, a game-lobby name — anything where "last writer wins" is the intended
 *   UX contract.
 * - Updates are infrequent relative to the replication window, so the probability
 *   of two peers writing the same field within the same millisecond is low.
 * - When conflicts do happen, the tie-break on `replicaId` ensures every replica
 *   converges to the same value — silence is never an option.
 *
 * ## Clock skew caveat
 *
 * LWW trusts the caller's timestamp. If a replica's clock lags behind another's,
 * an older write with a future timestamp can silently discard a newer one. Use a
 * Hybrid Logical Clock above this layer when skew is a concern, or prefer
 * [MVRegister] to surface conflicts explicitly rather than hide them.
 *
 * ## API-surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [Quilter] convenience factory (value serializer, default replica)
 * - [Quilter.mutate] to apply a timestamped write and broadcast the delta
 * - [Quilter.state] (`StateFlow<LWWRegister<String>>`) to read the winner
 */
class SharedTitleTest {

    private val replicatorCfg = QuilterConfig(expectVirtualTime = true)

    @Test
    fun `a later write overrides an earlier one and both replicas converge`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("doc-title"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTitle = Quilter(seamAlice, LWWRegister.empty<String>(), LWWRegister.serializer(String.serializer()), backgroundScope, config = replicatorCfg)
            val bobTitle = Quilter(seamBob, LWWRegister.empty<String>(), LWWRegister.serializer(String.serializer()), backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice sets an initial title.
            aliceTitle.mutate { Patch(it.set(aliceTitle.replica, 1L, "Untitled")) }

            // Bob renames it later (higher timestamp → wins).
            bobTitle.mutate { Patch(it.set(bobTitle.replica, 2L, "Meeting Notes")) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas must converge to Bob's later write.
            assertEquals("Meeting Notes", aliceTitle.state.value.value)
            assertEquals(aliceTitle.state.value.value, bobTitle.state.value.value)
        }

    @Test
    fun `when timestamps tie the replica-id lexicographic tiebreak is deterministic`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("doc-title-tie"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTitle = Quilter(seamAlice, LWWRegister.empty<String>(), LWWRegister.serializer(String.serializer()), backgroundScope, config = replicatorCfg)
            val bobTitle = Quilter(seamBob, LWWRegister.empty<String>(), LWWRegister.serializer(String.serializer()), backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Both peers write at the same logical timestamp — tiebreak on replicaId.
            // selfId is the Seam's peer id; whichever is lexicographically larger wins.
            val ts = 5L
            aliceTitle.mutate { Patch(it.set(aliceTitle.replica, ts, "Alice's Title")) }
            bobTitle.mutate { Patch(it.set(bobTitle.replica, ts, "Bob's Title")) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas must agree — one title survives, deterministically.
            assertEquals(aliceTitle.state.value.value, bobTitle.state.value.value)
        }
}
