package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Quilter]'s state root is computed once per state value, not once per digest (#3617).
 *
 * Computing a root encodes the whole state inside the synthetic `FullState` framing whose sender
 * is [ReplicaId.Bottom]. [CountingFormat] counts exactly those encodes, so the assertions count
 * root computations rather than inferring them from timing or allocation.
 */
class QuilterStateRootCacheTest {
    private val msgSer = QuiltMessage.serializer(GSet.serializer(String.serializer()))
    private val self = PeerId("self")
    private val peer = PeerId("peer-1")

    private class CountingFormat(private val delegate: BinaryFormat = Cbor) : BinaryFormat {
        var rootEncodes = 0
            private set

        override val serializersModule: SerializersModule get() = delegate.serializersModule

        override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
            if (value is QuiltMessage.FullState<*> && value.sender == ReplicaId.Bottom) rootEncodes++
            return delegate.encodeToByteArray(serializer, value)
        }

        override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
            delegate.decodeFromByteArray(deserializer, bytes)
    }

    private fun rootDigest(root: Long): ByteArray =
        Cbor.encodeToByteArray(msgSer, QuiltMessage.RootDigest(sender = ReplicaId(peer.value), root = root, upThrough = 0L))

    /** The root an uncached computation gives: the production framing, written out independently. */
    private fun rootOf(state: GSet<String>): Long =
        fnv1a64(Cbor.encodeToByteArray(msgSer, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)))

    @Test
    fun `digests sent and received at one state encode the root once`() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val format = CountingFormat()
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = Quilter(
                replica = ReplicaId(self.value),
                seam = seam,
                initial = GSet.of("a", "b"),
                messageSerializer = msgSer,
                scope = backgroundScope,
                config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
                binaryFormat = format,
                random = Random(7),
            )
            testScheduler.runCurrent()

            quilter.sendRootDigestForTest(peer)
            quilter.sendRootDigestForTest(peer)
            seam.deliver(peer, rootDigest(rootOf(GSet.of("a", "b"))))
            seam.deliver(peer, rootDigest(rootOf(GSet.of("other"))))
            testScheduler.runCurrent()

            // Counted from construction: the first-contact digest to `peer` also asks for the root,
            // so without the cache this is five or more, one per digest.
            assertEquals(1, format.rootEncodes, "root encodes across every digest at one state")
        }

    @Test
    fun `a write replaces the cached root with the root of the new state`() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val format = CountingFormat()
            val quilter = Quilter(
                replica = ReplicaId(self.value),
                seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer)),
                initial = GSet.of("a"),
                messageSerializer = msgSer,
                scope = backgroundScope,
                config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
                binaryFormat = format,
                random = Random(7),
            )
            testScheduler.runCurrent()
            val rootBefore = quilter.stateRootForTest()

            quilter.apply(Patch(GSet.of("b")))
            val rootAfter = quilter.stateRootForTest()
            val rootAgain = quilter.stateRootForTest()

            assertAll(
                { assertEquals(rootOf(GSet.of("a")), rootBefore, "root before the write") },
                { assertEquals(rootOf(GSet.of("a", "b")), rootAfter, "root after the write is the new state's") },
                { assertEquals(rootAfter, rootAgain, "a second read at the same state") },
            )
        }
}
