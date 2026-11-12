package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ledger's topology reads, checked against a brute-force scan over seeded random histories
 * (#3173, plan Task L0).
 *
 * Every read here — [EntitlementLedger.lineageOf], [EntitlementLedger.isLeaf],
 * [EntitlementLedger.activeChildren], [EntitlementLedger.liveInboundEdges],
 * [EntitlementLedger.retiredInboundEdges] and [EntitlementLedger.holdings] — is recomputed by an
 * oracle that walks [EntitlementLedger.allEdges] and reads only per-edge records, lifecycles and
 * stored counter slots. The oracle has no index, so it cannot share a defect with one.
 *
 * ## What the histories reach
 *
 * - **Served-then-retired children**: prepare, activate, delegate, spend, release, close, retire,
 *   through the ledger's own mutators. A retired child's net inflow is its spend, so a parent's
 *   holdings must keep subtracting it.
 * - **A retired edge whose counters move afterwards**: a raw `issued`/`issuedRelocIn`/`returned`
 *   delta on any edge, retired ones included, as a relocation or a late delivery would write.
 * - **A RETIRED marker riding an undrained edge**, written raw.
 * - **Counters that arrive before their record**, and a **second, divergent record** under an
 *   existing id with a different parent and child.
 * - **Merge in both orders** of two replica states, each order checked on its own.
 * - **A JSON round trip**, which rebuilds every derived structure from the bytes.
 * - **A carry from the other operand**: before half the merges one side is replaced by a freshly
 *   decoded copy, so the order in which it comes first has to take its index from the other side.
 *
 * Each of these is counted where it happens, and the test asserts every count is positive.
 *
 * Reads are made after most steps, but not all: a state that is never read is pieced into the next
 * one without its derived structures ever having been built, and both routes have to agree.
 *
 * No transfers are generated, so the oracle's `transferNet` is zero by construction; the index does
 * not touch that term.
 */
class EntitlementLedgerIndexOracleTest {

    @Test
    fun indexedReadsEqualABruteForceScanOverSeededHistories() {
        val runs = (0 until SEEDS).map { seed ->
            Run(Random(seed * 7919 + 3173)).also { run -> repeat(STEPS) { run.step() } }
        }
        val checks = runs.sumOf { it.checks }
        val retiredWithInflow = runs.maxOf { it.maxRetiredWithInflow }
        val mergesChecked = runs.sumOf { it.mergesChecked }
        val roundTrips = runs.sumOf { it.roundTrips }
        val retiredCountersMoved = runs.sumOf { it.retiredCountersMovedUnderABuiltIndex }
        val divergent = runs.sumOf { it.divergentRecords }
        val countersFirst = runs.sumOf { it.recordsArrivingAfterTheirCounters }
        val carriedFromOther = runs.sumOf { it.carriesFromTheOtherOperand }
        val sharedTops = runs.sumOf { it.sharedTops }
        // The rig fired: each arm the KDoc promises was actually reached, counted where it happens.
        assertAll(
            { assertTrue(checks >= SEEDS * STEPS / 2, "oracle checks ran: $checks") },
            { assertTrue(retiredWithInflow > 0, "some retired child carried a nonzero net inflow") },
            { assertTrue(mergesChecked >= SEEDS * 10, "merges checked in both orders: $mergesChecked") },
            { assertTrue(roundTrips >= SEEDS * 5, "JSON round trips: $roundTrips") },
            {
                assertTrue(
                    retiredCountersMoved > 0,
                    "a counter moved on a RETIRED edge while the index was built (the carry's take-out/put-back)",
                )
            },
            { assertTrue(divergent > 0, "a second, divergent record landed under an existing id") },
            { assertTrue(countersFirst > 0, "a merge brought an edge's record to a state that held only its counters") },
            { assertTrue(carriedFromOther > 0, "a merge carried the index from the other operand") },
            { assertTrue(sharedTops > 0, "several edges shared the top generation") },
        )
    }

    private class Run(private val random: Random) {
        val states: MutableList<EntitlementLedger> = MutableList(REPLICAS.size) { EntitlementLedger.ZERO }

        /** The minted component of each state, mirrored, since the ledger exposes no per-holder read. */
        val mints: MutableList<Map<MintId, MintRecord>> = MutableList(REPLICAS.size) { emptyMap() }

        /** Every edge id this run has named, in creation order — the list draws walk. */
        val edges = ArrayList<AttachmentId>()
        var nextEdge = 0
        var nextMint = 0
        var checks = 0
        var maxRetiredWithInflow = 0
        var mergesChecked = 0
        var roundTrips = 0
        var retiredCountersMovedUnderABuiltIndex = 0
        var divergentRecords = 0
        var recordsArrivingAfterTheirCounters = 0
        var carriesFromTheOtherOperand = 0
        var sharedTops = 0

        fun step() {
            val i = random.nextInt(REPLICAS.size)
            val me = REPLICAS[i]
            when (random.nextInt(14)) {
                0 -> mint(i, me)
                1, 2 -> prepare(i)
                3 -> mutate(i) { l -> l.activate(edge() ?: return@mutate null)?.delta }
                4 -> mutate(i) { l -> l.close(edge() ?: return@mutate null)?.delta }
                5 -> mutate(i) { l -> l.retire(edge() ?: return@mutate null)?.delta }
                6 -> mutate(i) { l -> l.delegate(me, edge() ?: return@mutate null, random.nextLong(1L, 6L))?.delta }
                7 -> mutate(i) { l -> l.release(me, edge() ?: return@mutate null, random.nextLong(1L, 4L))?.delta }
                8 -> mutate(i) { l ->
                    val leaf = GROUPS.random(random)
                    val path = l.lineageOf(leaf)?.takeIf { it.isNotEmpty() } ?: return@mutate null
                    l.spendCaptured(me, path, random.nextLong(0L, 3L))?.delta
                }
                9 -> serveAndRetire(i, me)
                10 -> rawCounter(i)
                11 -> rawLifecycleOrDivergentRecord(i)
                12 -> merge()
                else -> roundTrip(i)
            }
        }

        private fun edge(): AttachmentId? = if (edges.isEmpty()) null else edges.random(random)

        private fun mint(i: Int, me: ReplicaId) {
            val id = MintId("m${nextMint++}")
            val amount = random.nextLong(1L, 40L)
            states[i] = states[i].piece(states[i].mint(id, ROOT, me, amount).delta)
            mints[i] = mints[i] + (id to MintRecord(ROOT, me, amount))
            maybeCheck(i)
        }

        private fun prepare(i: Int) {
            val record = newRecord()
            mutate(i) { l -> l.prepare(record)?.delta }
        }

        /**
         * Two ids in three are in the topology grammar, four to a generation so that several edges
         * share the top one; the third is not, and must never count. No draw is spent on it, so the
         * trajectory is the one the other reads were tuned on.
         */
        private fun nextId(): AttachmentId {
            val n = nextEdge++
            return AttachmentId(if (n % 3 == 0) "e$n" else "root/e$n@1:1#${n / 4}")
        }

        private fun newRecord(): AttachmentRecord {
            val id = nextId()
            edges += id
            val parent = GROUPS.random(random)
            val child = (GROUPS - parent).random(random)
            return AttachmentRecord(id, parent, child, Weight.of(random.nextLong(1L, 4L)))
        }

        /** A whole served life on one replica: the retired child's net inflow equals its spend. */
        private fun serveAndRetire(i: Int, me: ReplicaId) {
            val start = states[i]
            val parent = GROUPS.random(random)
            if (start.holdings(parent, me) < 2L || start.lineageOf(parent) == null) return
            val record = AttachmentRecord(nextId(), parent, GroupId("leaf-$nextEdge"), Weight.ONE)
            edges += record.id
            val granted = random.nextLong(1L, minOf(start.holdings(parent, me), 6L) + 1L)
            val spent = random.nextLong(0L, granted + 1L)
            var l = start
            l = l.piece(l.prepare(record)?.delta ?: return)
            l = l.piece(l.activate(record.id)?.delta ?: return)
            l = l.piece(l.delegate(me, record.id, granted)?.delta ?: return)
            val path = l.lineageOf(record.child) ?: return
            l = l.piece(l.spendCaptured(me, path, spent)?.delta ?: return)
            if (granted > spent) l = l.piece(l.release(me, record.id, granted - spent)?.delta ?: return)
            l = l.piece(l.close(record.id)?.delta ?: return)
            l = l.piece(l.retire(record.id)?.delta ?: return)
            states[i] = l
            maybeCheck(i)
        }

        /** A counter slot written straight through the factory, on any edge, retired ones included. */
        private fun rawCounter(i: Int) {
            val e = edge() ?: return
            val r = REPLICAS.random(random)
            val family = random.nextInt(3)
            val current = states[i].storedSlot(
                when (family) {
                    0 -> CounterFamily.ISSUED
                    1 -> CounterFamily.ISSUED_RELOC_IN
                    else -> CounterFamily.RETURNED
                },
                e,
                r,
            )
            val before = states[i]
            if (before.isIndexBuilt() && before.lifecycle(e) == Lifecycle.RETIRED && before.recordsOf(e).isNotEmpty()) {
                retiredCountersMovedUnderABuiltIndex++
            }
            val cell = mapOf(e to GCounter.of(r to current + random.nextLong(1L, 5L)))
            val delta = when (family) {
                0 -> EntitlementLedger.of(issued = cell)
                1 -> EntitlementLedger.of(issuedRelocIn = cell)
                else -> EntitlementLedger.of(returned = cell)
            }
            states[i] = states[i].piece(delta)
            maybeCheck(i)
        }

        private fun rawLifecycleOrDivergentRecord(i: Int) {
            val e = edge() ?: return
            val retire = random.nextBoolean()
            val delta = if (retire) {
                EntitlementLedger.of(lifecycle = mapOf(e to Lifecycle.RETIRED))
            } else {
                val parent = GROUPS.random(random)
                val child = (GROUPS - parent).random(random)
                EntitlementLedger.of(records = mapOf(e to setOf(AttachmentRecord(e, parent, child, Weight.ONE))))
            }
            val recordsBefore = states[i].recordsOf(e).size
            states[i] = states[i].piece(delta)
            if (!retire && recordsBefore > 0 && states[i].recordsOf(e).size > recordsBefore) divergentRecords++
            maybeCheck(i)
        }

        private fun mutate(i: Int, delta: (EntitlementLedger) -> EntitlementLedger?) {
            val d = delta(states[i]) ?: return
            states[i] = states[i].piece(d)
            maybeCheck(i)
        }

        /** Merge two replicas in both orders; each order is checked on its own, then both are kept. */
        private fun merge() {
            val a = random.nextInt(REPLICAS.size)
            val b = (a + 1 + random.nextInt(REPLICAS.size - 1)) % REPLICAS.size
            // Half the time one side arrives freshly decoded, its index not yet built, so one of the
            // two orders below has to carry the index from its other operand.
            if (random.nextBoolean()) {
                val side = if (random.nextBoolean()) a else b
                states[side] = decode(encode(states[side]))
            }
            if (states[a].isIndexBuilt() != states[b].isIndexBuilt()) carriesFromTheOtherOperand++
            recordsArrivingAfterTheirCounters += countersBeforeRecords(states[a], states[b]) +
                countersBeforeRecords(states[b], states[a])
            val ab = states[a].piece(states[b])
            val ba = states[b].piece(states[a])
            val mints = this.mints[a] + this.mints[b]
            check(ab, mints, "merge $a←$b")
            check(ba, mints, "merge $b←$a")
            mergesChecked++
            states[a] = ab
            states[b] = ba
            this.mints[a] = mints
            this.mints[b] = mints
        }

        /** Edges [mine] knows only by their counters and [theirs] holds the record for. */
        private fun countersBeforeRecords(mine: EntitlementLedger, theirs: EntitlementLedger): Int {
            val known = mine.allEdges()
            return edges.count { it in known && mine.recordsOf(it).isEmpty() && theirs.recordsOf(it).isNotEmpty() }
        }

        private fun encode(l: EntitlementLedger): String = Json.encodeToString(EntitlementLedger.serializer(), l)

        private fun decode(bytes: String): EntitlementLedger = Json.decodeFromString(EntitlementLedger.serializer(), bytes)

        private fun roundTrip(i: Int) {
            val decoded = decode(encode(states[i]))
            check(decoded, mints[i], "decoded")
            assertEquals(states[i].validate(), decoded.validate(), "validate after a round trip")
            roundTrips++
            states[i] = decoded
        }

        /** Two thirds of the time: so some states are pieced onward never having been read. */
        private fun maybeCheck(i: Int) {
            if (random.nextInt(3) != 0) check(states[i], mints[i], "replica $i")
        }

        fun check(l: EntitlementLedger, minted: Map<MintId, MintRecord>, label: String) {
            val oracle = Oracle(l, minted)
            for (g in GROUPS) {
                assertEquals(oracle.lineage(g), l.lineageOf(g), "$label: lineageOf($g)")
                assertEquals(oracle.isLeaf(g), l.isLeaf(g), "$label: isLeaf($g)")
                assertEquals(oracle.activeChildren(g), l.activeChildren(g), "$label: activeChildren($g)")
                assertEquals(oracle.inbound(g, live = true), l.liveInboundEdges(g), "$label: liveInboundEdges($g)")
                assertEquals(oracle.inbound(g, live = false), l.retiredInboundEdges(g), "$label: retiredInboundEdges($g)")
                for (r in REPLICAS) assertEquals(oracle.holdings(g, r), l.holdings(g, r), "$label: holdings($g, $r)")
            }
            val top = oracle.generationTop()
            assertEquals(top, l.generationTop(), "$label: generationTop")
            if (top.edges >= 2) sharedTops++
            maxRetiredWithInflow = maxOf(maxRetiredWithInflow, oracle.retiredChildrenWithInflow())
            checks++
        }
    }

    /** The scan every read is checked against: no index, only per-edge reads over [EntitlementLedger.allEdges]. */
    private class Oracle(private val l: EntitlementLedger, private val minted: Map<MintId, MintRecord>) {
        private val all: List<AttachmentId> = l.allEdges().sorted()

        private fun isLive(e: AttachmentId) = l.lifecycle(e).let { it == Lifecycle.ACTIVE || it == Lifecycle.CLOSING }

        private fun single(e: AttachmentId): AttachmentRecord? = l.recordsOf(e).singleOrNull()

        fun children(g: GroupId): List<AttachmentId> = all.filter { e -> l.recordsOf(e).any { it.parent == g } }

        /** The highest topology generation over every edge the ledger mentions, and how many carry it. */
        fun generationTop(): GenerationTop {
            val generations = all.mapNotNull { generationOf(it) }
            val top = generations.maxOrNull() ?: return GenerationTop(-1L, 0)
            return GenerationTop(top, generations.count { it == top })
        }

        fun isLeaf(g: GroupId): Boolean = children(g).none { l.lifecycle(it) == Lifecycle.ACTIVE }

        fun activeChildren(g: GroupId): List<EdgeSummary> =
            children(g).filter { l.lifecycle(it) == Lifecycle.ACTIVE }.mapNotNull { l.edge(it) }

        fun inbound(g: GroupId, live: Boolean): List<AttachmentId> = all.filter { e ->
            single(e)?.child == g && if (live) isLive(e) else l.lifecycle(e) == Lifecycle.RETIRED
        }

        fun lineage(g: GroupId): List<AttachmentId>? {
            val path = ArrayDeque<AttachmentId>()
            val seen = HashSet<GroupId>()
            var cur = g
            while (true) {
                if (!seen.add(cur)) return null
                val inbound = all.filter { e -> l.recordsOf(e).any { it.child == cur } }
                if (inbound.isEmpty()) return path.toList()
                val live = inbound.filter(::isLive)
                if (live.size != 1) return null
                val rec = single(live.single()) ?: return null
                path.addFirst(live.single())
                cur = rec.parent
            }
        }

        private fun slot(f: CounterFamily, e: AttachmentId, r: ReplicaId) = l.storedSlot(f, e, r)

        private fun netInflow(e: AttachmentId, r: ReplicaId): Long =
            slot(CounterFamily.ISSUED, e, r) + slot(CounterFamily.ISSUED_RELOC_IN, e, r) - slot(CounterFamily.RETURNED, e, r)

        fun holdings(g: GroupId, r: ReplicaId): Long {
            val lineage = lineage(g) ?: return 0L
            val f = lineage.lastOrNull()
            var acc = if (f == null) {
                minted.values.filter { it.holder == r && it.root == g }.sumOf { it.amount }
            } else {
                netInflow(f, r)
            }
            for (c in children(g)) acc -= netInflow(c, r)
            if (f != null) {
                acc -= slot(CounterFamily.LEAF_SPENT, f, r) + slot(CounterFamily.LEAF_RELOC_IN, f, r) -
                    slot(CounterFamily.LEAF_RELOC_OUT, f, r)
            }
            return acc
        }

        fun retiredChildrenWithInflow(): Int = all.count { e ->
            l.lifecycle(e) == Lifecycle.RETIRED && l.recordsOf(e).isNotEmpty() && REPLICAS.any { netInflow(e, it) != 0L }
        }
    }

    private companion object {
        const val SEEDS = 12
        const val STEPS = 300
        val ROOT = GroupId("root")
        val REPLICAS: List<ReplicaId> = listOf("R0", "R1", "R2").map(::ReplicaId)

        /** A small pool, so records collide into dual inbounds, cycles and multi-child parents. */
        val GROUPS: List<GroupId> = listOf("root", "a", "b", "a/x", "a/y", "b/z", "c").map(::GroupId)
    }
}
