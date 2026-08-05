/**
 * [Quilter.mutateOrSkip] — the declining read-modify-write (#2090).
 *
 * The property under test is a **wire** property, not an internal one: a transform that
 * returns `null` must leave no trace a peer can observe. Counting frames at a plain [Seam]
 * joined to the same [InMemoryLoom] is what makes that checkable — an assertion on
 * [Quilter.state] alone would pass for the identity-patch workaround too, because the
 * identity join *is* a no-op on the state. The empty `Delta` frame it broadcasts, and the
 * sequence number it burns, are the two things that are not.
 *
 * All tests run under `UnconfinedTestDispatcher` with [QuilterConfig.expectVirtualTime],
 * per `docs/testing-coroutine-determinism.md`, and advance virtual time only with bounded
 * [kotlinx.coroutines.test.TestCoroutineScheduler.runCurrent] — never `advanceUntilIdle`,
 * whose re-arming anti-entropy timer is not the subject here.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val MUTATE_OR_SKIP_CONFIG = QuilterConfig(expectVirtualTime = true)
private fun mutateOrSkipSer() = QuiltMessage.serializer(GCounter.serializer())

private fun counterQuilter(seam: Seam, scope: CoroutineScope) = Quilter(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = GCounter.ZERO,
    messageSerializer = mutateOrSkipSer(),
    scope = scope,
    config = MUTATE_OR_SKIP_CONFIG,
)

class QuilterMutateOrSkipTest {

    /**
     * A declined transform publishes **nothing on the wire** and **burns no sequence number**.
     *
     * The seq assertion is the half that survives a naive fix: an implementation that skips the
     * broadcast but still bumps `nextSeq` would leave the observer's next real `Delta` at `seq = 2`
     * with `seq = 1` never sent — a permanent gap that drives the receiver into `Resend` recovery.
     * Both halves are read off frames the observer actually received.
     */
    @Test
    fun declinedMutationEmitsNoFrameAndBurnsNoSeq() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("mutate-or-skip"))
        val observer = loom.join(InMemoryTag("observer"))

        val seen = mutableListOf<QuiltMessage<GCounter>>()
        observer.incoming
            .onEach { seen += it.decode(Cbor, mutateOrSkipSer()) }
            .launchIn(backgroundScope)

        val quilter = counterQuilter(seam, backgroundScope)
        testScheduler.runCurrent()
        // Drop the first-contact FullState/RootDigest beacons; only local mutations matter here.
        seen.clear()

        val declined = quilter.mutateOrSkip { null }
        testScheduler.runCurrent()

        assertAll(
            { assertFalse(declined, "mutateOrSkip reports that it published nothing") },
            {
                assertTrue(
                    seen.none { it is QuiltMessage.Delta },
                    "a declined mutation must broadcast no Delta frame; observer saw $seen",
                )
            },
            { assertEquals(GCounter.ZERO.value, quilter.state.value.value, "state is untouched") },
        )

        val published = quilter.mutateOrSkip { it.inc(quilter.replica, 1L) }
        testScheduler.runCurrent()

        val deltas = seen.filterIsInstance<QuiltMessage.Delta<GCounter>>()
        assertAll(
            { assertTrue(published, "an accepted transform reports that it published") },
            { assertEquals(1, deltas.size, "exactly one Delta frame reached the observer") },
            {
                assertEquals(
                    1L,
                    deltas.single().seq,
                    "the declined mutation must not have burned seq 1 — a skipped seq is a " +
                        "permanent gap in the receiver's delta cursor",
                )
            },
            { assertEquals(1L, quilter.state.value.value, "the accepted patch did land") },
        )
    }

    /**
     * An accepted transform is [Quilter.mutate] — same read-modify-write against the state its
     * patch lands on, same replication. Pinned against a second replica so the assertion is
     * convergence, not just the local state.
     */
    @Test
    fun acceptedMutationReplicatesLikeMutate() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("mutate-or-skip-accept"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = counterQuilter(seamA, backgroundScope)
        val repB = counterQuilter(seamB, backgroundScope)
        testScheduler.runCurrent()

        repeat(3) { repA.mutateOrSkip { it.inc(repA.replica, 2L) } }
        // Interleave a decline: it must not disturb the accepted run's sequencing.
        repA.mutateOrSkip { null }
        repA.mutateOrSkip { it.inc(repA.replica, 1L) }
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(7L, repA.state.value.value, "four accepted increments landed locally") },
            { assertEquals(7L, repB.state.value.value, "and replicated to the peer") },
        )
    }
}
