@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Example: collaborative text editing replicated across two peers using [Rga]
 * + [Quilter].
 *
 * Two peers insert and delete characters at **arbitrary positions** concurrently.
 * The RGA sequence CRDT resolves concurrent inserts at the same position
 * deterministically: the element with the higher [RgaId] wins the earlier slot.
 * Both replicas converge to the same text regardless of which peer delivered
 * which operation first.
 *
 * ## Why Rga fits (and how it differs from ChatTest)
 *
 * [ChatTest] uses [Rga] as a standalone value object (offline, no network):
 * it shows append-only chat messages merged by hand via [Rga.piece]. This
 * example shows the **live, networked** use case:
 *
 * - Insert at any position, not just HEAD — peers type at the cursor, not the end.
 * - Delete via [Rga.removeAt] — backing-up and correcting a character.
 * - Operations propagate automatically over the [us.tractat.kuilt.core.Seam]
 *   via [Quilter]; neither peer calls [Rga.piece] manually.
 *
 * ## Serializer note
 *
 * [Rga] requires a **custom wire serializer** when used with [Quilter].
 * The compiler-generated `Rga.serializer(...)` defaults to
 * `PolymorphicSerializer(Any::class)` for the element type [V] in
 * [RgaOp.Insert.value], which CBOR cannot encode. Use [Rga.wireSerializer]
 * (backed by [us.tractat.kuilt.crdt.RgaOpSerializer]) and pass the result to
 * `QuiltMessage.serializer(...)` to obtain the [messageSerializer] for
 * the full [Quilter] constructor.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [Quilter] full constructor with a custom [messageSerializer]
 * - [Rga.insertAt] to insert at an arbitrary visible position
 * - [Rga.removeAt] to delete a character and broadcast the tombstone
 * - [Quilter.apply] with `Patch(Rga.empty<Char>().apply(op))` — the
 *   delta-broadcast pattern for op-based CRDTs
 * - [Rga.toList] to read the converged character sequence
 */
class RgaCollabEditTest {

    private val replicatorCfg = QuilterConfig(expectVirtualTime = true)
    private val msgSerializer = QuiltMessage.serializer(Rga.wireSerializer(serializer<Char>()))

    private fun makeReplicator(seam: us.tractat.kuilt.core.Seam, scope: kotlinx.coroutines.CoroutineScope) =
        Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = Rga.empty<Char>(),
            messageSerializer = msgSerializer,
            scope = scope,
            config = replicatorCfg,
        )

    /**
     * Inserts [char] at visible position [index] on [rep] and broadcasts the delta.
     * Returns the [RgaId] of the newly inserted element so callers can reference it.
     */
    private fun Quilter<Rga<Char>>.insertAt(index: Int, char: Char): RgaId {
        val (_, op) = state.value.insertAt(replica, index, char)
        apply(Patch(Rga.empty<Char>().apply(op)))
        return op.id
    }

    /**
     * Removes the visible element at [index] on [rep] and broadcasts the tombstone.
     */
    private fun Quilter<Rga<Char>>.removeAt(index: Int) {
        val result = state.value.removeAt(index) ?: return
        val (_, op) = result
        apply(Patch(Rga.empty<Char>().apply(op)))
    }

    @Test
    fun `concurrent inserts at different positions converge to the same text`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("collab-edit"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val alice = makeReplicator(seamAlice, backgroundScope)
            val bob = makeReplicator(seamBob, backgroundScope)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice types 'h', 'i' — produces "hi"
            alice.insertAt(0, 'h')
            alice.insertAt(1, 'i')

            // Bob concurrently starts his own sequence 'b', 'y', 'e' — produces "bye"
            bob.insertAt(0, 'b')
            bob.insertAt(1, 'y')
            bob.insertAt(2, 'e')

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas must hold the same characters (order is deterministic but
            // depends on RgaId comparison — the test verifies convergence, not a specific order).
            assertEquals(alice.state.value.toList(), bob.state.value.toList())
            assertEquals(5, alice.state.value.toList().size)
        }

    @Test
    fun `a deletion on one peer propagates and is reflected on the other`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("collab-delete"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val alice = makeReplicator(seamAlice, backgroundScope)
            val bob = makeReplicator(seamBob, backgroundScope)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice types "cat"
            alice.insertAt(0, 'c')
            alice.insertAt(1, 'a')
            alice.insertAt(2, 't')

            delay(10) // let "cat" propagate to Bob

            assertEquals(listOf('c', 'a', 't'), bob.state.value.toList())

            // Alice corrects: delete 'c', prepend 'b' → "bat"
            alice.removeAt(0)
            alice.insertAt(0, 'b')

            delay(10) // let the edit propagate

            assertEquals(listOf('b', 'a', 't'), alice.state.value.toList())
            assertEquals(alice.state.value.toList(), bob.state.value.toList())
        }

    @Test
    fun `concurrent inserts at the same position resolve deterministically`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("collab-concurrent"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val alice = makeReplicator(seamAlice, backgroundScope)
            val bob = makeReplicator(seamBob, backgroundScope)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Both peers insert at position 0 concurrently (both see an empty document).
            // RGA resolves the tie by RgaId comparison — the larger id wins position 0.
            alice.insertAt(0, 'A')
            bob.insertAt(0, 'B')

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas converge to the same two-character sequence.
            val aliceResult = alice.state.value.toList()
            val bobResult = bob.state.value.toList()
            assertEquals(aliceResult, bobResult, "Concurrent inserts at the same position must converge")
            assertEquals(2, aliceResult.size)
        }
}
