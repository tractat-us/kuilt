package us.tractat.kuilt.heddle

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A replicated ledger never holds a fold of forgotten edges (#3192, design §5.5, §10 invariant 2),
 * so a [HeddleNode] refuses one: in a frame a peer sends, which it drops as undecodable and carries
 * on, in a control-log witness, which reads as unreadable, and as the ledger it starts from.
 *
 * Without the refusal at decode, a folded frame reaches [EntitlementLedger.piece], which throws on a
 * fold that is not the receiver's own, inside the replicator's long-lived pump.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleNodeRefusesAFoldTest {

    private val root = GroupId("root")
    private val rogue = ReplicaId("rogue")

    /** A ledger that has forgotten an edge: what only a single-writer engine ever holds. */
    private val folded = EntitlementLedger.of(forgottenLeafSpend = mapOf(rogue to 1L), forgottenGenerationFloor = 0L)

    private fun config() = HeddleConfig(
        policy = PolicyConfig(quantum = 10L),
        maxHoldingsPerPeer = 1_000L,
        demandTtl = 30.seconds,
        quilter = QuilterConfig(antiEntropyInterval = 100.milliseconds, fullStateRetryLimit = 0, expectVirtualTime = true),
        heartbeat = us.tractat.kuilt.liveness.HeartbeatConfig(),
        random = Random(7),
    )

    @Test
    fun aFoldedFrameIsDroppedAndTheNodeCarriesOn() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("heddle-fold"))
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val self = ReplicaId(seam.selfId.value)
        val node = backgroundScope.heddleStatic(seam, self, root, mint = mapOf(self to 100L), clock = clock, config = config(), epoch = 0L)

        // The peer that sends the fold: a bare seam speaking the ledger channel's frames by hand.
        val peer = loom.join(InMemoryTag("rogue"))
        val ledgerChannel = NamedMux(peer, backgroundScope).channel(HeddleNode.LEDGER_CHANNEL)
        backgroundScope.launch { ledgerChannel.incoming.collect { } }
        advanceTimeBy(200L)
        runCurrent()

        val messages = QuiltMessage.serializer(EntitlementLedger.serializer())
        suspend fun send(message: QuiltMessage<EntitlementLedger>) {
            ledgerChannel.sendTo(seam.selfId, Cbor.encodeToByteArray(messages, message))
            advanceTimeBy(200L)
            runCurrent()
        }
        val before = node.ledger.value
        send(QuiltMessage.FullState(rogue, folded))
        send(QuiltMessage.Delta(rogue, 1L, folded))
        val afterFolds = node.ledger.value
        // The same sequence number the folded delta used: a dropped frame never advanced it.
        send(QuiltMessage.Delta(rogue, 1L, EntitlementLedger.ZERO.mint(MintId("rogue#0"), root, rogue, 5L).delta))

        assertAll(
            { assertTrue(folded.hasForgotten(), "the rig: the frames carry a fold") },
            { assertEquals(100L, before.mintedTotal(), "the rig: the node's own mint") },
            { assertEquals(before, afterFolds, "both folded frames were dropped") },
            { assertFalse(node.ledger.value.hasForgotten(), "the node holds no fold") },
            { assertEquals(105L, node.ledger.value.mintedTotal(), "an honest delta after them still lands: the pump is alive") },
        )
    }

    @Test
    fun aRetireWitnessCarryingAFoldIsUnreadable() {
        val edge = AttachmentId("root/a@1:1#0")
        val honest = ControlLogVectors.envelope(ControlCommand.Retire(edge, witness = EntitlementLedger.ZERO))
        val tainted = ControlLogVectors.envelope(ControlCommand.Retire(edge, witness = folded))
        val unreadable = assertIs<ControlEntry.Unreadable>(ControlLogCodec.decode(ControlLogCodec.encode(tainted)))
        assertAll(
            { assertEquals(ControlEntry.Act(honest), ControlLogCodec.decode(ControlLogCodec.encode(honest)), "the control: a witness with no fold reads") },
            { assertTrue("forgotten" in unreadable.failure, "unreadable for the fold, not something else: ${unreadable.failure}") },
        )
    }

    @Test
    fun aNodeCannotStartFromAFold() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = InMemoryLoom().host(Pattern("heddle-fold-start"))
        val failure = assertFailsWith<IllegalArgumentException> {
            HeddleNode(
                scope = backgroundScope,
                seam = seam,
                self = ReplicaId(seam.selfId.value),
                initialLedger = folded,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                config = config(),
                epoch = 0L,
            )
        }
        assertTrue("forgotten" in failure.message.orEmpty(), "refused for the fold: ${failure.message}")
    }
}
