/**
 * Tests for [MuxClientLoom] — the client mux Loom that weaves one base fabric and
 * returns named channel Seams over a single [NamedMux] (#948(a)).
 *
 * Covers: one base weave for N channels, channel isolation, per-channel close
 * leaving siblings live (#949 end-to-end), and client-side resume — a torn base
 * re-weaves once and every prior channel name heals onto the new base (#948 gap 4).
 *
 * Uses [UnconfinedTestDispatcher] so coroutine launches are eager inside [runTest].
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MuxClientLoomTest {

    /** A [Loom] that counts how many times [weave] is called, delegating to [delegate]. */
    private class CountingLoom(private val delegate: Loom) : Loom {
        var weaveCount: Int = 0
            private set

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            weaveCount++
            return delegate.weave(rendezvous)
        }
    }

    private fun muxClientLoom(base: Loom, scope: kotlinx.coroutines.CoroutineScope): MuxClientLoom =
        MuxClientLoom(
            base = base,
            baseRendezvous = Rendezvous.New(Pattern("base")),
            scope = scope,
            nameOf = { rendezvous ->
                when (rendezvous) {
                    is Rendezvous.New -> rendezvous.pattern.displayName
                    is Rendezvous.Existing -> rendezvous.tag.displayName
                }
            },
        )

    // ── One base weave for N channels ─────────────────────────────────────────

    @Test
    fun twoWeavesWeaveBaseOnce() = runTest(UnconfinedTestDispatcher()) {
        val counting = CountingLoom(InMemoryLoom())
        val client = muxClientLoom(counting, backgroundScope)

        val a = client.join(InMemoryTag("a"))
        val b = client.join(InMemoryTag("b"))

        assertAll(
            { assertEquals(1, counting.weaveCount, "base must weave exactly once for two channel weaves") },
            { assertTrue(a !== b, "distinct channel names must produce distinct channel Seams") },
        )
    }

    @Test
    fun sameNameWeaveReturnsSameSeam() = runTest(UnconfinedTestDispatcher()) {
        val counting = CountingLoom(InMemoryLoom())
        val client = muxClientLoom(counting, backgroundScope)

        val first = client.join(InMemoryTag("lobby"))
        val second = client.join(InMemoryTag("lobby"))

        assertAll(
            { assertSame(first, second, "weaving the same name twice must return the same channel Seam") },
            { assertEquals(1, counting.weaveCount, "repeat weave of a known name must not re-weave the base") },
        )
    }

    /** host(New) and join(Existing) for the same logical tag must land on the same channel. */
    @Test
    fun hostAndJoinMapToSameChannelName() = runTest(UnconfinedTestDispatcher()) {
        val counting = CountingLoom(InMemoryLoom())
        val client = muxClientLoom(counting, backgroundScope)

        val viaHost = client.host(Pattern("table-7"))
        val viaJoin = client.join(InMemoryTag("table-7"))

        assertSame(viaHost, viaJoin, "New and Existing with the same nameOf must resolve to one channel")
    }

    // ── Channel isolation: frames on "a" never appear on "b" ──────────────────

    @Test
    fun framesOnAneverAppearOnB() = runTest(UnconfinedTestDispatcher()) {
        val mesh = InMemoryLoom()
        val client = muxClientLoom(CountingLoom(mesh), backgroundScope)
        val peerB = NamedMux(mesh.join(InMemoryTag("peer-b")), backgroundScope)

        val bOnA = peerB.channel("a").incoming.produceIn(this)
        val bOnB = peerB.channel("b").incoming.produceIn(this)

        val sentinel = async { peerB.channel("a").incoming.first() }
        client.join(InMemoryTag("a")).broadcast(byteArrayOf(1))
        sentinel.await()

        assertAll(
            { assertTrue(bOnB.tryReceive().isFailure, "a frame sent on \"a\" must not appear on \"b\"") },
        )
        bOnA.cancel()
        bOnB.cancel()
    }

    // ── Per-channel close leaves siblings live (#949 end-to-end) ──────────────

    @Test
    fun closingAleavesBlive() = runTest(UnconfinedTestDispatcher()) {
        val mesh = InMemoryLoom()
        val counting = CountingLoom(mesh)
        val client = muxClientLoom(counting, backgroundScope)
        val peerB = NamedMux(mesh.join(InMemoryTag("peer-b")), backgroundScope)

        val chanA = client.join(InMemoryTag("a"))
        val chanB = client.join(InMemoryTag("b"))

        chanA.close()

        val received = async { peerB.channel("b").incoming.first() }
        chanB.broadcast(byteArrayOf(42))
        val onB = received.await()

        assertAll(
            { assertTrue(onB.toByteArray().contentEquals(byteArrayOf(42)), "channel \"b\" must still deliver after \"a\" closed") },
            { assertEquals(1, counting.weaveCount, "closing a channel must not re-weave the base") },
        )
    }

    @Test
    fun closingAchannelLeavesBaseLive() = runTest(UnconfinedTestDispatcher()) {
        val mesh = InMemoryLoom()
        val client = muxClientLoom(CountingLoom(mesh), backgroundScope)

        client.join(InMemoryTag("a")).close()
        // The base remains live: a fresh weave reuses it (no re-weave) and works.
        val b = client.join(InMemoryTag("b"))

        assertFalse(b.state.value is SeamState.Torn, "base Seam must remain live after a channel close")
    }

    // ── Client-side resume: torn base re-weaves once, all names heal ──────────

    @Test
    fun tornBaseReWeavesOnceAndChannelsHeal() = runTest(UnconfinedTestDispatcher()) {
        val mesh = InMemoryLoom()
        val counting = CountingLoom(mesh)
        val client = muxClientLoom(counting, backgroundScope)

        // Weave two channels over the first base.
        val chanA = client.join(InMemoryTag("a"))
        val chanB = client.join(InMemoryTag("b"))
        val selfBefore = chanA.selfId

        // Tear the base out from under the client.
        client.closeBase()

        // Re-weaving the same names must produce working channels over ONE new base.
        val healedA = client.join(InMemoryTag("a"))
        val healedB = client.join(InMemoryTag("b"))

        // A peer on the new mesh generation receives frames from the healed channels.
        val peerB = NamedMux(mesh.join(InMemoryTag("peer-b")), backgroundScope)
        val gotA = async { peerB.channel("a").incoming.first() }
        val gotB = async { peerB.channel("b").incoming.first() }
        healedA.broadcast(byteArrayOf(7))
        healedB.broadcast(byteArrayOf(9))
        val onA = gotA.await()
        val onB = gotB.await()

        assertAll(
            { assertEquals(2, counting.weaveCount, "torn base must re-weave exactly once — one new base for all channels") },
            { assertSame(chanA, healedA, "the same channel-name handle must heal, not a new one") },
            { assertSame(chanB, healedB, "the same channel-name handle must heal, not a new one") },
            { assertEquals(selfBefore, healedA.selfId, "selfId must stay stable across re-weave for server re-association") },
            { assertTrue(onA.toByteArray().contentEquals(byteArrayOf(7)), "channel \"a\" flows over the new base") },
            { assertTrue(onB.toByteArray().contentEquals(byteArrayOf(9)), "channel \"b\" flows over the new base") },
        )
    }
}
