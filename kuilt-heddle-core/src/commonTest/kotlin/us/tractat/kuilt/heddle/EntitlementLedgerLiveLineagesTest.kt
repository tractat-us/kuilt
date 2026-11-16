package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two memoized lineage lifts against the per-group oracles they replace (#3173, plan Task L1):
 * `withLiveLineages` against [EntitlementLedger.lineageOf], and `withDelegableLineages`, the one a
 * [LocalHeddle] pick lifts its waiting and eligible sets with, against the predicate `admit` checks.
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

    /**
     * `withDelegableLineages`' oracle, per group: a group a delegation from the root can end at has a
     * non-empty lineage, every edge of it ACTIVE, and is a leaf. The same predicate `admit` checks.
     */
    private fun delegableOracle(ledger: EntitlementLedger, groups: Collection<GroupId>, root: GroupId): Set<GroupId> =
        groups.flatMapTo(HashSet()) { g ->
            val lineage = ledger.lineageOf(g) ?: return@flatMapTo emptyList()
            if (lineage.isEmpty() || !ledger.isLeaf(g) || lineage.any { ledger.lifecycle(it) != Lifecycle.ACTIVE }) return@flatMapTo emptyList()
            if (checkNotNull(ledger.record(lineage.first())).parent != root) return@flatMapTo emptyList()
            listOf(g) + lineage.map { checkNotNull(ledger.record(it)).parent }
        }

    /**
     * [g] would be delegable but for where its lineage starts: a leaf whose ACTIVE lineage hangs from
     * a group with no inbound edge that is not [root]. The case a refused reload leaves behind.
     */
    private fun orphaned(ledger: EntitlementLedger, g: GroupId, root: GroupId): Boolean {
        val lineage = ledger.lineageOf(g) ?: return false
        if (lineage.isEmpty() || !ledger.isLeaf(g) || lineage.any { ledger.lifecycle(it) != Lifecycle.ACTIVE }) return false
        return checkNotNull(ledger.record(lineage.first())).parent != root
    }

    /** A leaf below a group with no inbound edge is not delegable from the root, nor is that group. */
    @Test
    fun theDelegableLiftDropsALeafHangingBelowAGroupThatIsNotTheRoot() {
        val root = GroupId("root")
        val orphan = GroupId("orphan")
        val lane = GroupId("lane")
        val l = shapedLedger(root, active = listOf(edgeOf(root, GroupId("b")), edgeOf(orphan, lane)))
        val asked = listOf(GroupId("b"), lane)
        assertAll(
            { assertEquals(setOf(root, GroupId("b")), l.withDelegableLineages(asked, root)) },
            { assertEquals(delegableOracle(l, asked, root), l.withDelegableLineages(asked, root)) },
            { assertTrue(orphaned(l, lane, root), "the rig: lane's lineage is live but starts at orphan") },
        )
    }

    @Test
    fun theDelegableLiftDropsClosingPathsAndNonLeaves() {
        val root = GroupId("root")
        val p = GroupId("p")
        val t = GroupId("t")
        val closing = edgeOf(t, GroupId("a"))
        var l = shapedLedger(
            root,
            active = listOf(edgeOf(root, p), edgeOf(p, GroupId("b")), edgeOf(root, t), closing, edgeOf(p, GroupId("lane")), edgeOf(GroupId("lane"), GroupId("x"))),
        )
        l = l.piece(checkNotNull(l.close(closing.id)).delta)
        val asked = listOf("a", "b", "lane", "t", "root", "#3").map(::GroupId)
        assertAll(
            { assertEquals(delegableOracle(l, asked, root), l.withDelegableLineages(asked, root)) },
            {
                assertEquals(
                    setOf("root", "p", "b", "t").map(::GroupId).toSet(),
                    l.withDelegableLineages(asked, root),
                    "a is behind a closing edge, lane has an active child, and the root is never an end; t is a leaf now",
                )
            },
            { assertEquals(setOf("root", "p", "b", "t", "a", "lane").map(::GroupId).toSet(), l.withLiveLineages(asked) - GroupId("#3"), "the live lift keeps them") },
        )
    }

    @Test
    fun theDelegableLiftAgreesWithItsOracleOnRandomForests() {
        var cases = 0
        var differsFromLive = 0
        var withAnOrphan = 0
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
                        if (rng.nextBoolean()) l = l.piece(checkNotNull(l.close(r.id)).delta)
                    }
                }
            }
            val asked = groups.filter { rng.nextBoolean() }
            assertEquals(delegableOracle(l, asked, root), l.withDelegableLineages(asked, root), "seed $seed")
            cases++
            if (l.withDelegableLineages(asked, root) != l.withLiveLineages(asked)) differsFromLive++
            if (asked.any { orphaned(l, it, root) }) withAnOrphan++
        }
        assertAll(
            { assertEquals(300, cases) },
            { assertTrue(differsFromLive > 50, "the forests reach groups the two lifts disagree on: $differsFromLive") },
            { assertTrue(withAnOrphan > ORPHANED_FLOOR, "the forests ask for a leaf hanging below a group that is not the root: $withAnOrphan") },
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

    private companion object {
        /**
         * Measured on the JVM: 57 of the 300 forests ask for a leaf whose ACTIVE lineage hangs below a
         * group that is not the root. The floor is half that, so the arm reds when the generator stops
         * building orphans, not when an edit to it moves the count a little.
         */
        const val ORPHANED_FLOOR = 28
    }
}
