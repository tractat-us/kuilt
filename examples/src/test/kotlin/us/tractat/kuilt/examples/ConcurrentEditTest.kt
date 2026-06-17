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
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Example: surfacing concurrent edits to a shared field using [MVRegister]
 * + [Quilter].
 *
 * A multi-value register retains **all** concurrently written values rather than
 * silently discarding the losers. The app sees a set with more than one element and
 * can prompt the user to pick a winner — a conflict-aware UX instead of a silent
 * last-write-wins drop.
 *
 * ## Why MVRegister fits
 *
 * - The field carries user-authored content (an annotation, a label, a choice) where
 *   silently losing a write is worse than surfacing a conflict.
 * - Concurrent writes are genuinely possible: two users both edit while offline and
 *   then reconnect.
 * - Once the user resolves the conflict with a fresh write, that write observes both
 *   prior values and MVRegister drops them cleanly — convergence is restored.
 *
 * ## Contrast with LWWRegister
 *
 * [us.tractat.kuilt.crdt.LWWRegister] also converges, but it silently picks the
 * largest timestamp and the other write is gone with no trace. [MVRegister] keeps
 * the evidence; [us.tractat.kuilt.crdt.LWWRegister] keeps the simplicity.
 *
 * ## API-surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [Quilter] convenience factory (value serializer, default replica)
 * - [Quilter.mutate] to write a value and broadcast the delta
 * - [Quilter.state] (`StateFlow<MVRegister<String>>`) to inspect the value set
 * - [MVRegister.values] — `Set<V>` with one element normally, multiple on conflict
 */
class ConcurrentEditTest {

    private val replicatorCfg = QuilterConfig(expectVirtualTime = true)

    @Test
    fun `concurrent writes from two peers are both retained until resolved`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("annotation"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceNote = Quilter(seamAlice, MVRegister.empty<String>(), MVRegister.serializer(String.serializer()), backgroundScope, config = replicatorCfg)
            val bobNote = Quilter(seamBob, MVRegister.empty<String>(), MVRegister.serializer(String.serializer()), backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice and Bob both write without seeing each other's edit — concurrent writes.
            aliceNote.mutate { Patch(it.set(aliceNote.replica, "Alice's annotation")) }
            bobNote.mutate { Patch(it.set(bobNote.replica, "Bob's annotation")) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas must converge to the same conflicted state: two values.
            assertEquals(setOf("Alice's annotation", "Bob's annotation"), aliceNote.state.value.values)
            assertEquals(aliceNote.state.value.values, bobNote.state.value.values)
        }

    @Test
    fun `a later write that observes the conflict resolves it to a single value`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("annotation-resolve"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceNote = Quilter(seamAlice, MVRegister.empty<String>(), MVRegister.serializer(String.serializer()), backgroundScope, config = replicatorCfg)
            val bobNote = Quilter(seamBob, MVRegister.empty<String>(), MVRegister.serializer(String.serializer()), backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Create concurrent writes on both sides.
            aliceNote.mutate { Patch(it.set(aliceNote.replica, "Alice's annotation")) }
            bobNote.mutate { Patch(it.set(bobNote.replica, "Bob's annotation")) }

            delay(10) // let the conflict propagate to both peers

            // Alice sees both values and resolves with a fresh write.
            // set() observes the current causal context, superseding every prior value.
            aliceNote.mutate { Patch(it.set(aliceNote.replica, "Resolved: shared note")) }

            delay(10) // let the resolution propagate

            // Both replicas must now converge to exactly one value.
            assertEquals(setOf("Resolved: shared note"), aliceNote.state.value.values)
            assertEquals(aliceNote.state.value.values, bobNote.state.value.values)
        }
}
