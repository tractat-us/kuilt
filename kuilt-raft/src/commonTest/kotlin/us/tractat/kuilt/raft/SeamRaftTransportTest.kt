package us.tractat.kuilt.raft

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


@OptIn(ExperimentalCoroutinesApi::class)
class SeamRaftTransportTest {
    @Test
    fun selfIdMapsFromPeerId() = raftRunTest {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("test"))
        val transport = SeamRaftTransport(seam)
        assertEquals(NodeId(seam.selfId.value), transport.selfId)
    }

    @Test
    fun deliversMessageToSender() = raftRunTest {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("joiner"))
        val tA = SeamRaftTransport(seamA)
        val tB = SeamRaftTransport(seamB)
        val payload = byteArrayOf(9, 8, 7)
        var got: RaftEnvelope? = null
        val job = launch { got = tB.incoming.first() }
        tA.sendTo(tB.selfId, payload)
        job.join()
        assertAll(
            { assertEquals(tA.selfId, got?.from) },
            { assertContentEquals(payload, got?.bytes) },
        )
    }

    @Test
    fun peersReflectsSeamPeers() = raftRunTest {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("test"))
        val seamB = loom.join(InMemoryTag("joiner"))
        val tA = SeamRaftTransport(seamA)
        val tB = SeamRaftTransport(seamB)
        assertAll(
            { assertTrue(NodeId(seamB.selfId.value) in tA.peers.value) },
            { assertTrue(NodeId(seamA.selfId.value) in tB.peers.value) },
        )
    }

    @Test
    fun sendToAbsentPeerSilentlyDropsPerContract() = raftRunTest {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("test"))
        val transport = SeamRaftTransport(seam)
        // No peer named "ghost" is connected, so the underlying Seam.sendTo throws
        // PeerNotConnected. The transport contract says sendTo "may silently drop if peer
        // is unreachable" — a Raft voter dropping off the fabric must NOT crash the engine.
        transport.sendTo(NodeId("ghost"), byteArrayOf(1, 2, 3))
        // Reaching here without an exception is the assertion.
    }

    /**
     * The seam's budget is republished **unchanged** (#2069). This transport hands `message` to
     * [Seam.sendTo] exactly as the engine minted it and adds no bytes of its own, so there is
     * nothing to subtract — unlike `RoutedRaftTransport`, which wraps each frame in a `RaftRelay`
     * envelope and therefore reports the delegate's limit less its own `headerBudget`.
     */
    @Test
    fun publishesTheSeamsBudgetUnchanged() = raftRunTest {
        val loom = InMemoryLoom()
        val seam = BudgetedSeam(loom.host(Pattern("test")), budget = 4096)
        assertEquals(4096, SeamRaftTransport(seam).maxPayloadBytes)
    }

    /**
     * A seam that names no ceiling produces a transport that names none either.
     *
     * Asserted as its own case rather than left to the base default: `RaftTransport.maxPayloadBytes`
     * happens to default to `null` today, so *deleting* the override would keep this green — which
     * is exactly why the non-null direction above is asserted beside it. Together they pin
     * delegation; either alone pins a constant.
     */
    @Test
    fun publishesNullWhenTheSeamNamesNoCeiling() = raftRunTest {
        val loom = InMemoryLoom()
        val seam = BudgetedSeam(loom.host(Pattern("test")), budget = null)
        assertNull(SeamRaftTransport(seam).maxPayloadBytes)
    }

    /**
     * The reading stays live. [Seam.maxPayloadBytes] is "a reading, not a lease" — a mesh reports the
     * minimum across its live links, so a peer attaching over a tighter transport lowers it. A `val`
     * initialiser here would freeze whatever the seam happened to report at construction; only a
     * `get()` re-reads it, and this is the assertion that tells the two apart.
     */
    @Test
    fun tracksTheSeamsBudgetWhenItMovesDown() = raftRunTest {
        val loom = InMemoryLoom()
        val seam = BudgetedSeam(loom.host(Pattern("test")), budget = 4096)
        val transport = SeamRaftTransport(seam)
        val before = transport.maxPayloadBytes
        seam.maxPayloadBytes = 512
        assertAll(
            { assertEquals(4096, before) },
            { assertEquals(512, transport.maxPayloadBytes, "the budget is re-read per access, not snapshotted") },
        )
    }
}

/** A [Seam] that delegates everything but its (mutable) payload budget — see [SeamRaftTransportTest]. */
private class BudgetedSeam(inner: Seam, budget: Int?) : Seam by inner {
    override var maxPayloadBytes: Int? = budget
}
