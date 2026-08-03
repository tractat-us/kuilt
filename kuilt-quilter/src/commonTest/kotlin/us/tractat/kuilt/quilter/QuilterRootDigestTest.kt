@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Receive-side behaviour of the #1955 digest exchange, driven by injecting frames into a
 * [FakeSeam] and reading back what the [Quilter] sent via [FakeSeam.directed].
 *
 * The send side still ships `FullState` at this point in the plan, so nothing here depends on
 * the anti-entropy tick.
 */
class QuilterRootDigestTest {

    private val valueSer = GSet.serializer(String.serializer())
    private val msgSer = QuiltMessage.serializer(valueSer)
    private val self = PeerId("self")
    private val peer = PeerId("peer-1")
    private val peerReplica = ReplicaId("peer-1")

    private fun encode(msg: QuiltMessage<GSet<String>>): ByteArray =
        Cbor.encodeToByteArray(msgSer, msg)

    private fun decoded(bytes: ByteArray): QuiltMessage<GSet<String>> =
        Cbor.decodeFromByteArray(msgSer, bytes)

    /**
     * Must mirror `Quilter.stateRoot()` exactly: the root is FNV-1a over the state encoded inside a
     * synthetic `FullState` with [ReplicaId.Bottom] and `upThrough = 0L`, because the class holds no
     * `KSerializer<S>`. Hashing the bare state here instead would silently take the mismatch branch.
     */
    private fun expectedRoot(state: GSet<String>): Long = fnv1a64(
        Cbor.encodeToByteArray(msgSer, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)),
    )

    private fun quilterOn(seam: FakeSeam, scope: kotlinx.coroutines.CoroutineScope, initial: GSet<String>) =
        Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = initial,
            messageSerializer = msgSer,
            scope = scope,
            config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
        )

    @Test
    fun matchingRootShipsNoState() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam, backgroundScope, GSet.of("x"))
        testScheduler.runCurrent()

        val before = seam.directed.size
        seam.deliver(peer, encode(QuiltMessage.RootDigest(peerReplica, expectedRoot(GSet.of("x")), upThrough = 0L)))
        testScheduler.runCurrent()

        val sentAfter = seam.directed.drop(before).map { decoded(it.second) }
        assertAll(
            { assertTrue(sentAfter.none { it is QuiltMessage.FullState }, "a matched root must not ship state") },
            { assertTrue(sentAfter.none { it is QuiltMessage.FullStateRequest }, "a matched root must not request state") },
            { assertEquals(setOf("x"), quilter.state.value.elements, "state untouched") },
        )
    }

    @Test
    fun mismatchedRootRequestsFullState() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        quilterOn(seam, backgroundScope, GSet.of("x"))
        testScheduler.runCurrent()

        val before = seam.directed.size
        seam.deliver(peer, encode(QuiltMessage.RootDigest(peerReplica, root = 0xDEADBEEFL, upThrough = 0L)))
        testScheduler.runCurrent()

        val requests = seam.directed.drop(before)
            .map { it.first to decoded(it.second) }
            .filter { it.second is QuiltMessage.FullStateRequest }
        assertEquals(1, requests.size, "a mismatched root must request exactly one full state")
        assertEquals(peer, requests.single().first, "the request goes to the digest's sender")
    }

    @Test
    fun solicitedRequestShipsStateAndUnsolicitedDoesNot() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam, backgroundScope, GSet.of("x"))
            testScheduler.runCurrent()

            // Unsolicited: we have sent this peer no RootDigest, so the request is a no-op.
            var before = seam.directed.size
            seam.deliver(peer, encode(QuiltMessage.FullStateRequest<GSet<String>>(peerReplica, ReplicaId(self.value))))
            testScheduler.runCurrent()
            val unsolicited = seam.directed.drop(before).map { decoded(it.second) }

            // Solicited: arm the flag the way the anti-entropy tick does, then request.
            before = seam.directed.size
            quilter.sendRootDigestForTest(peer)
            testScheduler.runCurrent()
            before = seam.directed.size
            seam.deliver(peer, encode(QuiltMessage.FullStateRequest<GSet<String>>(peerReplica, ReplicaId(self.value))))
            testScheduler.runCurrent()
            val solicited = seam.directed.drop(before).map { decoded(it.second) }

            assertAll(
                {
                    assertTrue(
                        unsolicited.none { it is QuiltMessage.FullState },
                        "an unsolicited FullStateRequest must not pull state — that is a 3.5 MB amplification lever",
                    )
                },
                { assertTrue(solicited.any { it is QuiltMessage.FullState }, "a solicited request must ship state") },
            )
        }
}
