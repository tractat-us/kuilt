@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A frame the engine cannot decode is **dropped**, not fatal (#2051).
 *
 * `RaftEngine`'s inbound pump decodes every frame before handing it to the actor, and that decode is
 * the one point on the inbound path where the frame is still *bytes* — upstream of every guard the
 * refusal machinery covers. A throw there escapes the `transport.incoming.collect` lambda, escapes
 * the `launch` around it, and lands in the node's constructor-injected scope, taking the node — and
 * whatever else the consumer structured under that scope — down with it. That is the failure mode
 * #1818's "never `throw` on a remote-frame-controlled path" rule exists for, honoured everywhere
 * downstream of dispatch and, before this, skipped at the one point upstream of it.
 *
 * ### The trigger is version skew, not an attacker
 *
 * `raftCbor` is `Cbor { ignoreUnknownKeys = true }`, which buys forward-compatibility for an unknown
 * *field* — a new peer adding `RequestVote.leadershipTransfer` does not break an old one. It buys
 * nothing for an unknown **sealed-class discriminator**: a peer on a newer build sending a
 * `RaftMessage` variant this build's hierarchy has never heard of decodes to a
 * `SerializationException`, and that is an ordinary rolling upgrade across a voter set, not a
 * forgery. [aFrameTypeThisBuildDoesNotKnow] is exactly that shape — a real CBOR polymorphic envelope
 * whose discriminator names a subclass that does not exist here.
 *
 * The corrupt-bytes cases are the same defect reached the cheap way, and they earn their place
 * because the decoder does not throw one type: the unknown discriminator surfaces as a plain
 * `SerializationException` from the subclass lookup, while truncation and a bad major type surface
 * as `CborDecodingException` from the parser. Catching one named type is therefore not a fix, and
 * `CborDecodingException` is `internal` to kotlinx-serialization-cbor so it cannot even be named
 * here. The guard has to be `Throwable`-shaped (with the cancellation carve-out), and these three
 * cases are what holds it that way.
 *
 * ### Why the assertion is a scope, not a `try`
 *
 * [soloNode] hosts the node in a scope shaped like a consumer's — a plain [Job] (no supervisor) with
 * a [CoroutineExceptionHandler] — so an escaping throwable is *captured and named* rather than
 * merely failing the test from somewhere in the runner. Progress after the injection is then a
 * second, independent assertion: a node whose scope was cancelled stops committing, so the two
 * together say both "nothing escaped" and "the node is still there".
 */
class UndecodableFrameTest {

    /** The peer the undecodable frames arrive from — a voter on a newer build, or a corrupt link. */
    private val ghost = NodeId("peer-on-a-newer-build")

    // ── The malformed frames ──────────────────────────────────────────────────

    /**
     * A well-formed CBOR polymorphic envelope whose discriminator names a `RaftMessage` subclass this
     * build does not have — the honest version-skew frame.
     *
     * Built by encoding a *different* sealed hierarchy ([FutureWire]) through the same codec: the
     * envelope kotlinx-serialization writes for a sealed base is `{"type": <serialName>, "value": …}`
     * whichever base it is, so these bytes are what a newer kuilt would put on the wire for a
     * `RaftMessage.Rejoin` only it knows about. Decoding them as `RaftMessage` fails at the subclass
     * lookup — precisely the failure `ignoreUnknownKeys` does not cover.
     */
    private fun aFrameTypeThisBuildDoesNotKnow(): ByteArray =
        futureWireCodec.encodeToByteArray(FutureWire.serializer(), FutureWire.Rejoin(term = 7L, reason = "resync"))

    /** A real frame cut off mid-structure — a torn transport read. */
    private fun aTruncatedFrame(): ByteArray =
        aFrameTypeThisBuildDoesNotKnow().let { it.copyOfRange(0, it.size / 2) }

    /** Bytes that are not CBOR at all. */
    private fun garbageBytes(): ByteArray = ByteArray(32) { (it * 31 + 0xF7).toByte() }

    // ── The tests ─────────────────────────────────────────────────────────────

    @Test
    fun aFrameTypeThisBuildDoesNotKnow_doesNotKillTheNode() = raftRunTest {
        assertSurvives(aFrameTypeThisBuildDoesNotKnow())
    }

    @Test
    fun aTruncatedFrame_doesNotKillTheNode() = raftRunTest {
        assertSurvives(aTruncatedFrame())
    }

    @Test
    fun garbageBytes_doNotKillTheNode() = raftRunTest {
        assertSurvives(garbageBytes())
    }

    /**
     * Drive a single-voter node to a committed proposal, inject [bad] from [ghost], and require both
     * that nothing escaped into the node's scope and that the node is still leading and committing.
     *
     * The pre-injection commit is what makes the post-injection one meaningful: it establishes the
     * node was already making progress, so a failure to commit afterwards can only be the frame.
     */
    private suspend fun TestScope.assertSurvives(bad: ByteArray) {
        val solo = soloNode()
        try {
            solo.node.awaitLeadership()
            solo.node.propose(byteArrayOf(1))
            solo.harness.awaitCommit(2L) // the leader's no-op lands at 1, this proposal at 2

            solo.network.deliver(from = ghost, to = solo.self, bytes = bad)
            repeat(SETTLE_YIELDS) { yield() }

            assertTrue(
                solo.escapes.isEmpty(),
                "an undecodable frame must not escape into the node's scope, but ${solo.escapes} did",
            )
            solo.node.propose(byteArrayOf(2))
            solo.harness.awaitCommit(3L)
            assertEquals(RaftRole.Leader, solo.node.role.value, "node must still be leading after a bad frame")
        } finally {
            solo.scope.cancel()
        }
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    /**
     * A single-voter node, the [InMemoryRaftNetwork] behind it so a test can inject raw bytes, and
     * the [escapes] its scope's handler captured.
     *
     * [singleVoterNode] builds its own network and does not expose it; this keeps that fixture
     * untouched while giving this suite the two things it needs — a handle to
     * [InMemoryRaftNetwork.deliver] and a scope that reports rather than swallows. [harness] is the
     * same [SingleVoterHarness] so the bounded `awaitCommit` is reused rather than re-derived
     * (issue #192 harness discipline).
     */
    private class Solo(
        val node: RaftNode,
        val network: InMemoryRaftNetwork,
        val self: NodeId,
        val harness: SingleVoterHarness,
        val scope: CoroutineScope,
        val escapes: List<Throwable>,
    )

    /**
     * A node on a scope shaped like a consumer's: a plain [Job] — *not* a `SupervisorJob`, because
     * the damage under test is that the node's own failure cancels its siblings — plus a
     * [CoroutineExceptionHandler] that records instead of crashing the runner. `backgroundScope` is
     * deliberately not used: its supervisor would mask exactly the cancellation this is about.
     */
    private fun TestScope.soloNode(): Solo {
        val escapes = mutableListOf<Throwable>()
        val nodeScope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + Job() + CoroutineExceptionHandler { _, e -> escapes += e },
        )
        val self = NodeId("solo")
        val network = InMemoryRaftNetwork()
        val storage = InMemoryRaftStorage()
        val node = nodeScope.raftNode(
            ClusterConfig(voters = setOf(self)),
            network.transport(self),
            storage,
            fastRaftConfig(),
        )
        return Solo(node, network, self, SingleVoterHarness(node, storage), nodeScope, escapes)
    }

    private companion object {
        /** Enough dispatch turns for the inbound pump to pick the injected frame up and act on it. */
        const val SETTLE_YIELDS = 10

        /** Mirrors `RaftEngine.raftCbor`, so the reproducer's bytes are the ones that codec produces. */
        val futureWireCodec = Cbor { ignoreUnknownKeys = true }
    }
}

/**
 * A sealed hierarchy standing in for a *newer* build's `RaftMessage`.
 *
 * Only its wire shape matters: kotlinx-serialization writes the same `{"type", "value"}` envelope for
 * any sealed base, so encoding this produces the bytes a peer one version ahead would send for a
 * frame type the local hierarchy does not declare. The `@SerialName` is the fully-qualified name such
 * a subclass would carry, so the discriminator in the bytes is the real thing rather than an
 * obviously-foreign string.
 */
@Serializable
private sealed interface FutureWire {
    @Serializable
    @SerialName("us.tractat.kuilt.raft.internal.RaftMessage.Rejoin")
    data class Rejoin(val term: Long, val reason: String) : FutureWire
}
