package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `heddleStatic` takes its whole starting tree at once, so a tree that is not a tree is refused
 * there, loudly, naming the records at fault (#3172) — rather than booting a ledger with a record
 * silently dropped, or with a quarantined lineage that only a later `validate()` would mention.
 *
 * Each refusal test asserts *which* fault was reported, not merely that something threw: the
 * duplicate-id case names both colliding records, and the two topology cases name the
 * [LedgerConflict] the rigged shape produces and exclude the one it should not.
 */
class HeddleStaticBootstrapTest {

    private val root = GroupId("root")
    private val g1 = GroupId("g1")
    private val g2 = GroupId("g2")
    private val g3 = GroupId("g3")
    private val alice = ReplicaId("alice")
    private val mint = mapOf(alice to 100L)

    private fun edge(id: String, parent: GroupId, child: GroupId): AttachmentRecord =
        AttachmentRecord(AttachmentId(id), parent, child, Weight.ONE)

    /**
     * The control the refusals below are measured against: a well-formed tree, handed over out of
     * id order, boots with every record present and active and nothing for `validate()` to report.
     * Without it, a bootstrap that refused everything would pass all three refusal tests.
     */
    @Test
    fun wellFormedTreeBootsWithEveryRecordActive() {
        val topology = listOf(
            edge("e3", root, g3),
            edge("e1", root, g1),
            edge("e2", g1, g2),
        )

        val ledger = buildInitialLedger(root, mint, topology)

        assertAll(
            { assertEquals(emptyList(), ledger.validate()) },
            *topology.map { record ->
                {
                    assertAll(
                        { assertEquals(record, ledger.record(record.id)) },
                        { assertEquals(Lifecycle.ACTIVE, ledger.lifecycle(record.id)) },
                    )
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun duplicateAttachmentIdIsRefusedNamingBothRecords() {
        val first = edge("e1", root, g1)
        val second = edge("e1", root, g2)

        val failure = assertFailsWith<IllegalArgumentException> {
            buildInitialLedger(root, mint, listOf(first, second))
        }

        val message = failure.message.orEmpty()
        assertAll(
            { assertTrue(first.toString() in message, "names the record already prepared: $message") },
            { assertTrue(second.toString() in message, "names the refused record: $message") },
        )
    }

    @Test
    fun cycleIsRefusedAsALineageCycleNamingItsRecords() {
        val down = edge("e1", g1, g2)
        val back = edge("e2", g2, g1)

        val failure = assertFailsWith<IllegalArgumentException> {
            buildInitialLedger(root, mint, listOf(down, back))
        }

        val message = failure.message.orEmpty()
        assertAll(
            { assertTrue(LedgerConflict.LineageCycle(g1).toString() in message, message) },
            { assertTrue(LedgerConflict.LineageCycle(g2).toString() in message, message) },
            { assertTrue(down.toString() in message, "names $down: $message") },
            { assertTrue(back.toString() in message, "names $back: $message") },
            { assertFalse("DualActiveInbound" in message, "a pure cycle has no double parent: $message") },
        )
    }

    @Test
    fun selfParentedRecordIsRefusedAsALineageCycle() {
        val loop = edge("e1", g1, g1)

        val failure = assertFailsWith<IllegalArgumentException> {
            buildInitialLedger(root, mint, listOf(loop))
        }

        val message = failure.message.orEmpty()
        assertAll(
            { assertTrue(LedgerConflict.LineageCycle(g1).toString() in message, message) },
            { assertTrue(loop.toString() in message, "names $loop: $message") },
        )
    }

    @Test
    fun doubleParentIsRefusedAsDualActiveInboundNamingBothParents() {
        val viaG1 = edge("e3", g1, g3)
        val viaG2 = edge("e4", g2, g3)
        val topology = listOf(edge("e1", root, g1), edge("e2", root, g2), viaG1, viaG2)

        val failure = assertFailsWith<IllegalArgumentException> {
            buildInitialLedger(root, mint, topology)
        }

        val message = failure.message.orEmpty()
        assertAll(
            { assertTrue(LedgerConflict.DualActiveInbound(g3).toString() in message, message) },
            { assertTrue(viaG1.toString() in message, "names $viaG1: $message") },
            { assertTrue(viaG2.toString() in message, "names $viaG2: $message") },
            { assertFalse("LineageCycle" in message, "a double parent is not a cycle: $message") },
        )
    }

    /**
     * The refusal reaches the public entry point, before a node exists: nothing is launched on the
     * scope, so there is no half-started node for the caller to tear down.
     */
    @Test
    fun heddleStaticRefusesAMalformedTopologyBeforeStarting() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val seam = InMemoryLoom().host(Pattern("w3172-static-bootstrap"))
        val self = ReplicaId(seam.selfId.value)
        val first = edge("e1", root, g1)
        val second = edge("e1", root, g2)

        val failure = assertFailsWith<IllegalArgumentException> {
            backgroundScope.heddleStatic(
                seam = seam,
                self = self,
                root = root,
                mint = mapOf(self to 100L),
                topology = listOf(first, second),
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                config = HeddleConfig(policy = PolicyConfig(quantum = 10L), maxHoldingsPerPeer = 1_000L),
                epoch = 0L,
            )
        }

        assertTrue(second.toString() in failure.message.orEmpty(), failure.message)
    }
}
