package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #542 — a ply that tears between the composite's `Torn` filter and the actual send must not
 * fail a [CompositeSeam.broadcast] that another live ply can carry. Modelled by a ply whose
 * `state` reports [SeamState.Woven] (passes the filter) but whose `broadcast` throws (the
 * `Seam` contract: a send while `Torn` throws [IllegalStateException]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeSendToctouTest {
    @Test
    fun broadcastSucceedsWhenOnePlyTearsMidSendAndAnotherCarriesIt() = runTest {
        val good = InMemoryLoom()
        val loom = CompositeLoom(
            listOf(
                PlyId("good") to good,
                PlyId("bad") to TearOnSendLoom(),
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        host.peers.first { it.size == 2 } // reconciliation over the good ply

        // The bad ply throws on send; the good ply must still carry the frame and broadcast
        // must not propagate the bad ply's failure to the caller.
        host.broadcast(byteArrayOf(7, 8, 9))

        val got = withTimeoutOrNull(2_000) { joiner.incoming.first() }
        assertNotNull(got, "good ply should have delivered the frame despite the bad ply tearing")
        assertTrue(byteArrayOf(7, 8, 9).contentEquals(got.payload))
    }

    @Test
    fun sendToFallsThroughToSecondPlyWhenPreferredTearsMidSend() = runTest {
        val target = PeerId("target-composite")
        val recorder = SendRecorder()
        val preferred = ReachablePlyLoom("preferred", PeerId("t-pref"), target, failOnSend = true, recorder)
        val backup = ReachablePlyLoom("backup", PeerId("t-backup"), target, failOnSend = false, recorder)
        val loom = CompositeLoom(
            listOf(PlyId("preferred") to preferred, PlyId("backup") to backup),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val host = loom.host(Pattern("host"))
        // Announce AFTER weave() returns, so each ply is already registered in the composite's
        // live map when its mapping lands (a real fabric delivers Announce asynchronously).
        preferred.seam.announce()
        backup.seam.announce()
        host.peers.first { target in it } // both plies now map `target`

        // The most-preferred ply tears on send; sendTo must fall through to the backup ply
        // rather than propagating the failure to the caller (#542).
        host.sendTo(target, byteArrayOf(1, 2, 3))

        assertEquals(
            listOf("backup"),
            recorder.successes,
            "backup ply should have carried the frame after the preferred ply tore",
        )
        host.close(CloseReason.Normal)
    }

    @Test
    fun sendToThrowsPeerNotConnectedWhenNoPlyReachesPeer() = runTest {
        val target = PeerId("target-composite")
        val recorder = SendRecorder()
        val only = ReachablePlyLoom("only", PeerId("t-only"), target, failOnSend = false, recorder)
        val loom = CompositeLoom(
            listOf(PlyId("only") to only),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val host = loom.host(Pattern("host"))
        only.seam.announce()
        host.peers.first { target in it } // the ply maps `target`, but not `stranger`

        // No ply maps `stranger`, so resolution finds no candidate — the contract is to throw.
        assertFailsWith<PeerNotConnected> { host.sendTo(PeerId("stranger"), byteArrayOf(1)) }
        assertTrue(recorder.successes.isEmpty(), "no ply should have been asked to send")
        host.close(CloseReason.Normal)
    }

    /** Records the labels of plies that completed a `sendTo` successfully. */
    private class SendRecorder {
        val successes: MutableList<String> = mutableListOf()
    }

    /** A [Loom] whose woven [Seam] advertises [advertises] reachable via [transportPeer]. */
    private class ReachablePlyLoom(
        private val label: String,
        private val transportPeer: PeerId,
        private val advertises: PeerId,
        private val failOnSend: Boolean,
        private val recorder: SendRecorder,
    ) : Loom {
        lateinit var seam: ReachablePlySeam
            private set

        override suspend fun weave(rendezvous: Rendezvous): Seam =
            ReachablePlySeam(label, transportPeer, advertises, failOnSend, recorder).also { seam = it }

        override fun availability(): FabricAvailability = FabricAvailability.Available
    }

    /**
     * A [Seam] that reports [SeamState.Woven] and lists [transportPeer] among its `peers`. Calling
     * [announce] emits a [PlyFrame.Announce] mapping `transportPeer → advertises` so the composite's
     * idMap resolves [advertises] to this ply; the test calls it after `weave()` returns so the ply is
     * already registered in the composite's live map (mirroring async Announce delivery). When
     * [failOnSend] it throws on `sendTo` (the `Torn`-send contract); otherwise it records [label].
     */
    private class ReachablePlySeam(
        private val label: String,
        private val transportPeer: PeerId,
        advertises: PeerId,
        private val failOnSend: Boolean,
        private val recorder: SendRecorder,
    ) : Seam {
        override val selfId: PeerId = PeerId("$label-self")
        override val peers: StateFlow<Set<PeerId>> =
            MutableStateFlow(setOf(selfId, transportPeer)).asStateFlow()
        override val state: StateFlow<SeamState> =
            MutableStateFlow<SeamState>(SeamState.Woven).asStateFlow()
        private val inbound = MutableSharedFlow<Swatch>(extraBufferCapacity = 4)
        override val incoming: Flow<Swatch> = inbound
        private val announceFrame =
            Swatch(payload = PlyFrame.encode(PlyFrame.Announce(advertises)), sender = transportPeer)

        /** Deliver the Announce frame to the composite. */
        fun announce() {
            check(inbound.tryEmit(announceFrame)) { "announce buffer full" }
        }

        override suspend fun broadcast(payload: ByteArray) = Unit

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (failOnSend) throw IllegalStateException("Seam for $selfId is closed")
            recorder.successes += label
        }

        override suspend fun close(reason: CloseReason) = Unit
    }

    /** A [Loom] whose woven [Seam] reports [SeamState.Woven] but throws on every send. */
    private class TearOnSendLoom : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = TearOnSendSeam()
        override fun availability(): FabricAvailability = FabricAvailability.Available
    }

    private class TearOnSendSeam : Seam {
        override val selfId: PeerId = PeerId("bad-self")
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId)).asStateFlow()
        override val state: StateFlow<SeamState> = MutableStateFlow<SeamState>(SeamState.Woven).asStateFlow()
        override val incoming: Flow<Swatch> = emptyFlow()

        override suspend fun broadcast(payload: ByteArray): Unit =
            throw IllegalStateException("Seam for $selfId is closed")

        override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit =
            throw IllegalStateException("Seam for $selfId is closed")

        override suspend fun close(reason: CloseReason) = Unit
    }
}
