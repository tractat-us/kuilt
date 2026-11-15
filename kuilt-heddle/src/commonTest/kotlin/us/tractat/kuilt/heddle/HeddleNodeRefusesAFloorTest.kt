package us.tractat.kuilt.heddle

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A gauge carrying a v0.7.4 `floor` is refused on the two paths a gauge reaches a [HeddleNode] by
 * from outside: in a ledger frame a peer sends, which the node drops as undecodable and carries on,
 * and in a control-log witness, which reads as unreadable.
 *
 * Read without its floor, such a gauge would put its edge at the position its ticks alone assert,
 * lower than the one it was written at. Every floored payload here is an honest one with a floor
 * put into its bytes, and the same honest payload is sent or decoded too, so a refusal can be for
 * the floor and nothing else.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class HeddleNodeRefusesAFloorTest {

    private val root = GroupId("root")
    private val rogue = ReplicaId("rogue")
    private val edge = AttachmentId("root/a@1:1#0")
    private val gauge = Gauge(folded = 0L, ticksLow = 16L)

    /** A ledger whose one gauge is honest: what a peer of this release sends. */
    private val gauged = EntitlementLedger.of(gauges = mapOf(edge to gauge))

    private fun config() = HeddleConfig(
        policy = PolicyConfig(quantum = 10L),
        maxHoldingsPerPeer = 1_000L,
        demandTtl = 30.seconds,
        quilter = QuilterConfig(antiEntropyInterval = 100.milliseconds, fullStateRetryLimit = 0, expectVirtualTime = true),
        heartbeat = us.tractat.kuilt.liveness.HeartbeatConfig(),
        random = Random(7),
    )

    @Test
    fun aFrameWhoseGaugeCarriesAFloorIsDroppedAndTheNodeCarriesOn() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("heddle-floor"))
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val self = ReplicaId(seam.selfId.value)
        val node = backgroundScope.heddleStatic(seam, self, root, mint = mapOf(self to 100L), clock = clock, config = config(), epoch = 0L)

        // The peer that sends the floor: a bare seam speaking the ledger channel's frames by hand.
        val peer = loom.join(InMemoryTag("rogue"))
        val ledgerChannel = NamedMux(peer, backgroundScope).channel(HeddleNode.LEDGER_CHANNEL)
        backgroundScope.launch { ledgerChannel.incoming.collect { } }
        advanceTimeBy(200L)
        runCurrent()

        val messages = QuiltMessage.serializer(EntitlementLedger.serializer())
        suspend fun send(bytes: ByteArray) {
            ledgerChannel.sendTo(seam.selfId, bytes)
            advanceTimeBy(200L)
            runCurrent()
        }
        val fullState = Cbor.encodeToByteArray(messages, QuiltMessage.FullState(rogue, gauged))
        val delta = Cbor.encodeToByteArray(messages, QuiltMessage.Delta(rogue, 1L, gauged))
        val before = node.ledger.value
        send(withLegacyFloor(fullState))
        send(withLegacyFloor(delta))
        val afterFloors = node.ledger.value
        // The same delta with no floor, at the same sequence number: a dropped frame never advanced it.
        send(delta)

        assertAll(
            { assertNull(before.gauge(edge), "the rig: the node holds no gauge on the edge") },
            { assertEquals(before, afterFloors, "both floored frames were dropped") },
            { assertEquals(gauge, node.ledger.value.gauge(edge), "the same frame with no floor lands: the pump is alive") },
        )
    }

    @Test
    fun aWitnessWhoseGaugeCarriesAFloorIsUnreadable() {
        val honest = ControlLogVectors.envelope(ControlCommand.Retire(edge, witness = gauged))
        val bytes = ControlLogCodec.encode(honest)
        val unreadable = assertIs<ControlEntry.Unreadable>(ControlLogCodec.decode(withLegacyFloor(bytes)))
        assertAll(
            { assertEquals(ControlEntry.Act(honest), ControlLogCodec.decode(bytes), "the control: the witness with no floor reads") },
            { assertTrue("floor" in unreadable.failure, "unreadable for the floor, not something else: ${unreadable.failure}") },
        )
    }

    private companion object {
        private const val INDEFINITE_MAP = 0xBF
        private const val SMALL_MAP_MIN = 0xA0
        private const val SMALL_MAP_MAX = 0xB7

        /**
         * [cbor] with a v0.7.4 `floor` of [floor] units put into every gauge in it, ahead of the
         * gauge's first key, `folded`, which only a gauge has. The map header just before it is the
         * gauge's own; a definite-length one is counted up by the entry added. The same splice as
         * `:kuilt-heddle-core`'s gauge fixtures, which this module's tests cannot see.
         */
        fun withLegacyFloor(cbor: ByteArray, floor: Long = 70L): ByteArray {
            val folded = Cbor.encodeToByteArray(String.serializer(), "folded")
            val entry = Cbor.encodeToByteArray(String.serializer(), "floor") +
                Cbor.encodeToByteArray(RationalSerializer, Rational.of(floor))
            val out = ArrayList<Byte>(cbor.size + 32)
            var spliced = 0
            for (i in cbor.indices) {
                if (i > 0 && cbor.startsWithAt(folded, i)) {
                    val header = out.removeAt(out.lastIndex).toInt() and 0xFF
                    out += when (header) {
                        INDEFINITE_MAP -> header.toByte()
                        in SMALL_MAP_MIN until SMALL_MAP_MAX -> (header + 1).toByte()
                        else -> error("the rig: a gauge map header ${header.toString(16)} this splice does not handle")
                    }
                    out += entry.toList()
                    spliced++
                }
                out += cbor[i]
            }
            check(spliced > 0) { "the rig: no gauge to put a floor into" }
            return out.toByteArray()
        }

        private fun ByteArray.startsWithAt(prefix: ByteArray, at: Int): Boolean =
            at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }
    }
}
