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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
                    is Rendezvous.New -> rendezvous.pattern.sessionName
                    is Rendezvous.Existing -> rendezvous.tag.sessionName
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

    /**
     * A long-lived collector that captured `handle.state` **before** a heal must follow the
     * generation swap and observe the post-heal `Woven` — not stay pinned at the pre-heal
     * generation's terminal `Torn` (#1387). `state` is a hot [StateFlow]; a naive delegating
     * getter re-reads `current()` only at property access, so a captured flow never switches.
     */
    @Test
    fun longLivedStateCollectorFollowsHeal() = runTest(UnconfinedTestDispatcher()) {
        val mesh = InMemoryLoom()
        val client = muxClientLoom(CountingLoom(mesh), backgroundScope)

        val chan = client.join(InMemoryTag("a"))

        // Capture the state flow ONCE and start a long-lived collector BEFORE the heal.
        val capturedState = chan.state
        val observed = mutableListOf<SeamState>()
        backgroundScope.launch { capturedState.collect { observed.add(it) } }
        assertTrue(observed.last() is SeamState.Woven, "collector sees the pre-heal Woven")

        // Drive a same-instance heal: tear the base, then re-weave the same name.
        client.closeBase()
        assertTrue(observed.last() is SeamState.Torn, "collector sees this generation tear")
        client.join(InMemoryTag("a"))

        // The captured, long-lived collector must follow the swap to the fresh base's Woven.
        // Buggy code leaves `capturedState` pinned at the pre-heal generation's terminal Torn,
        // so this `first { Woven }` never completes and the timeout fails the test.
        withTimeout(1.seconds) { capturedState.first { it is SeamState.Woven } }
        assertTrue(observed.last() is SeamState.Woven, "long-lived collector follows the heal to the post-heal Woven")
    }

    /**
     * A long-lived collector of `handle.peers` captured before a heal must (a) **follow** the
     * generation swap — observing the post-heal generation's live peer updates — and (b) honour the
     * [StateFlow] contract that a collector never receives two consecutive equal values, even across
     * the swap (#1387, review B2/B5). A scripted base is used because the swap point is where the
     * pre-heal generation's collapsed peer set and the post-heal generation's initial peer set are
     * deliberately equal — the exact case a naive `flatMapLatest` (no `distinctUntilChanged`) would
     * deliver twice.
     */
    @Test
    fun longLivedPeersCollectorFollowsHealWithoutConsecutiveDuplicates() = runTest(UnconfinedTestDispatcher()) {
        val shared = PeerId("shared")
        val extra = PeerId("extra")
        val gen1 = ScriptedSeam(PeerId("gen-1")).apply { peersFlow.value = setOf(shared) }
        val gen2 = ScriptedSeam(PeerId("gen-2")).apply { peersFlow.value = setOf(shared) }
        val client = MuxClientLoom(
            base = ScriptedLoom(listOf(gen1, gen2)),
            baseRendezvous = Rendezvous.New(Pattern("base")),
            scope = backgroundScope,
            nameOf = { "a" },
        )

        val chan = client.join(InMemoryTag("a"))
        val capturedPeers = chan.peers
        val observed = mutableListOf<Set<PeerId>>()
        backgroundScope.launch { capturedPeers.collect { observed.add(it) } }
        assertEquals(setOf(shared), observed.last(), "collector sees the pre-heal peer set")

        // Heal onto gen2, whose initial peer set equals gen1's — the duplicate-emission trap.
        client.closeBase()
        client.join(InMemoryTag("a"))

        // A live update on the POST-heal generation proves the captured collector followed the swap.
        gen2.peersFlow.value = setOf(shared, extra)
        withTimeout(1.seconds) { capturedPeers.first { it == setOf(shared, extra) } }

        assertAll(
            { assertEquals(setOf(shared, extra), observed.last(), "long-lived peers collector follows the heal") },
            {
                val consecutiveDup = observed.zipWithNext().firstOrNull { (a, b) -> a == b }
                assertTrue(consecutiveDup == null, "StateFlow contract: no consecutive duplicate across the swap, saw $observed")
            },
        )
    }

    /** A base [Loom] that hands out a scripted sequence of [ScriptedSeam]s, one per [weave]. */
    private class ScriptedLoom(private val seams: List<Seam>) : Loom {
        private var next = 0
        override suspend fun weave(rendezvous: Rendezvous): Seam = seams[next++]
    }

    /** A [Seam] with directly drivable [state]/[peers] flows and a never-completing [incoming]. */
    private class ScriptedSeam(override val selfId: PeerId) : Seam {
        val peersFlow: MutableStateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
        val stateFlow: MutableStateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
        override val peers: StateFlow<Set<PeerId>> get() = peersFlow
        override val state: StateFlow<SeamState> get() = stateFlow
        override val incoming: Flow<Swatch> = MutableSharedFlow()
        override suspend fun broadcast(payload: ByteArray) {}
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {}
        override suspend fun close(reason: CloseReason) { stateFlow.value = SeamState.Torn(reason) }
    }
}
