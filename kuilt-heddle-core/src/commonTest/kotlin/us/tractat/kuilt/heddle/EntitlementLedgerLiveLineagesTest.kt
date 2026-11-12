package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `withLiveLineages`, the memoized lift a [LocalHeddle] pick uses, against the oracle it replaces:
 * [EntitlementLedger.lineageOf] once per group (#3173, plan Task L1).
 */
class EntitlementLedgerLiveLineagesTest {

    private fun oracle(ledger: EntitlementLedger, groups: Collection<GroupId>): Set<GroupId> =
        groups.flatMapTo(HashSet()) { g ->
            val lineage = ledger.lineageOf(g) ?: return@flatMapTo emptyList()
            listOf(g) + lineage.map { checkNotNull(ledger.record(it)).parent }
        }

    @Test
    fun agreesWithLineageOfOnEveryQuarantine() {
        val root = GroupId("root")
        val p = GroupId("p")
        val d = GroupId("d")
        val closing = edgeOf(p, GroupId("f"))
        var l = shapedLedger(
            root,
            active = listOf(
                edgeOf(root, p), edgeOf(p, GroupId("a")), edgeOf(p, GroupId("b")),
                edgeOf(root, d), edgeOf(p, d), edgeOf(d, GroupId("e")), closing,
            ),
            prepared = listOf(edgeOf(root, GroupId("q")), edgeOf(GroupId("q"), GroupId("c"))),
        )
        l = l.piece(checkNotNull(l.close(closing.id)).delta)
        val asked = listOf("a", "b", "c", "e", "f", "d", "#3", "root").map(::GroupId)
        val lifted = l.withLiveLineages(asked)
        assertAll(
            { assertEquals(oracle(l, asked), lifted) },
            { assertEquals(setOf("root", "p", "a", "b", "f", "#3").map(::GroupId).toSet(), lifted, "the dual-inbound d, its child e, and the prepared c are out") },
        )
    }

    @Test
    fun agreesWithLineageOfOnRandomForests() {
        var cases = 0
        var withAQuarantine = 0
        for (seed in 0 until 300) {
            val rng = Random(seed)
            val groups = List(10) { GroupId("g$it") }
            val root = groups[0]
            var l = EntitlementLedger.bootstrap(root, emptyMap(), nonce = "genesis")
            repeat(14) { k ->
                val child = groups[1 + rng.nextInt(groups.size - 1)]
                val parent = groups[rng.nextInt(groups.size)]
                if (parent == child) return@repeat
                val r = AttachmentRecord(AttachmentId("e$k"), parent, child, Weight.ONE)
                l = l.piece(checkNotNull(l.prepare(r)).delta)
                when (rng.nextInt(4)) {
                    0 -> Unit
                    1 -> l = l.piece(checkNotNull(l.activate(r.id)).delta)
                    else -> {
                        l = l.piece(checkNotNull(l.activate(r.id)).delta)
                        l = l.piece(checkNotNull(l.close(r.id)).delta)
                        if (rng.nextBoolean()) l = l.piece(checkNotNull(l.retire(r.id)).delta)
                    }
                }
            }
            val asked = groups.filter { rng.nextBoolean() }
            val want = oracle(l, asked)
            assertEquals(want, l.withLiveLineages(asked), "seed $seed")
            cases++
            if (asked.any { l.lineageOf(it) == null }) withAQuarantine++
        }
        assertAll(
            { assertEquals(300, cases) },
            { assertTrue(withAQuarantine > 50, "the forests reach quarantined groups: $withAQuarantine") },
        )
    }
}
