package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The scale row for the lease model (#3173, plan Task C): what one acquire and one complete cost on
 * [LeasePrototype] when a lane carries 5,000 runtime-style children and 50,000 retired edges. It
 * decides whether L1 needs a ledger index.
 *
 * **Gated at the task level**: excluded from every test task unless `-Pheddle.scale.probe=true`
 * (`kuilt-heddle-core/build.gradle.kts`). Timing is printed as `LEASE-SCALE` lines and never
 * asserted — it measures the box's load as much as the code. The counts are asserted.
 *
 * The retired population is built through the ledger's own mutators, in two kinds:
 * - **served** — activate → delegate → spend → close → retire. Retirement needs outstanding 0, so a
 *   served child's net inflow equals its spend, and its parent's holdings must keep subtracting it;
 * - **bare** — prepare → close → retire, with no counters at all.
 *
 * Served children are spent one at a time while the ledger holds only them: their spends share the
 * lane edge's roll-up slot, so they cannot be built as independent deltas and joined.
 */
class LeaseScaleProbe {

    @Test
    fun acquireAtFiveThousandChildrenAndFiftyThousandRetiredEdges() {
        listOf(
            scaleRow(active = 5_000, retired = 50_000, served = 5_000),
            scaleRow(active = 5_000, retired = 50_000, served = 0),
            scaleRow(active = 5_000, retired = 0, served = 0),
            scaleRow(active = 50, retired = 0, served = 0),
        ).forEach { println(it) }
    }

    private fun scaleRow(active: Int, retired: Int, served: Int): String {
        val clock = TimeSource.Monotonic
        val built = clock.markNow()
        val self = LeasePrototype.SELF
        val lane = GroupId("lane")
        val laneEdge = AttachmentRecord(AttachmentId("root>lane"), ROOT, lane, Weight.ONE)
        val children = (0 until active).map { k ->
            AttachmentRecord(AttachmentId("lane>c-$k"), lane, GroupId("lane/c-$k"), Weight.ONE)
        }
        val gone = (0 until retired).map { k ->
            AttachmentRecord(AttachmentId("lane>gone-$k"), lane, GroupId("lane/gone-$k"), Weight.ONE)
        }
        val servedRecords = gone.take(served)
        val bareRecords = gone.drop(served)

        var l = EntitlementLedger.bootstrap(ROOT, emptyMap(), nonce = "genesis")
        l = l.piece(checkNotNull(l.prepare(laneEdge)).delta)
        l = l.piece(checkNotNull(l.activate(laneEdge.id)).delta)

        // Served retired children, first, while the ledger is small.
        if (served > 0) {
            val history = served * SERVED_COST
            l = l.piece(l.mint(MintId("history"), ROOT, self, history).delta)
            l = l.piece(checkNotNull(l.delegate(self, laneEdge.id, history)).delta)
            val prepared = l.piece(joinAll(servedRecords.map { checkNotNull(l.prepare(it)).delta }))
            val activated = prepared.piece(joinAll(servedRecords.map { checkNotNull(prepared.activate(it.id)).delta }))
            // Each delegation reads the lane's holdings, which cover all of them together.
            var funded = activated.piece(joinAll(servedRecords.map { checkNotNull(activated.delegate(self, it.id, SERVED_COST)).delta }))
            for (r in servedRecords) {
                funded = funded.piece(checkNotNull(funded.spendCaptured(self, listOf(laneEdge.id, r.id), SERVED_COST)).delta)
            }
            val closed = funded.piece(joinAll(servedRecords.map { checkNotNull(funded.close(it.id)).delta }))
            l = closed.piece(joinAll(servedRecords.map { checkNotNull(closed.retire(it.id)) { "retire ${it.id.value}" }.delta }))
        }

        val base = l
        val prepared = base.piece(joinAll((children + bareRecords).map { checkNotNull(base.prepare(it)).delta }))
        val shaped = prepared.piece(
            joinAll(
                children.map { checkNotNull(prepared.activate(it.id)).delta } +
                    bareRecords.map { checkNotNull(prepared.close(it.id)).delta },
            ),
        )
        val ledger = shaped.piece(joinAll(bareRecords.map { checkNotNull(shaped.retire(it.id)).delta }))
        val buildTime = built.elapsedNow()

        val parentOf = HashMap<GroupId, GroupId>().apply {
            put(lane, ROOT)
            children.forEach { put(it.child, lane) }
        }
        val proto = LeasePrototype(ledger, ROOT, parentOf, watermark = WATERMARK, mintChunk = WATERMARK)
        val everyone = children.mapTo(HashSet()) { it.child }

        val seatMark = clock.markNow()
        val first = proto.acquire(everyone, everyone, reservation = 3L)
        val seatTime = seatMark.elapsedNow()
        val acquireTimes = (0 until TIMED).map {
            val mark = clock.markNow()
            val out = proto.acquire(everyone, everyone, reservation = 3L)
            mark.elapsedNow().also { assertTrue(out is PrototypeAcquire.Acquired, "timed acquire $it: $out") }
        }

        // Where an acquire's time goes: each ledger primitive it leans on, timed alone on the same ledger.
        val probe = proto.ledger
        val someChild = children.first().child
        val primitives = listOf(
            "activeChildren" to { probe.activeChildren(lane).size.toLong() },
            "holdings" to { probe.holdings(lane, self) },
            "lineageOf" to { probe.lineageOf(someChild)?.size?.toLong() ?: -1L },
            "isLeaf" to { if (probe.isLeaf(someChild)) 1L else 0L },
            "piece" to { probe.piece(probe.mint(MintId("probe"), ROOT, self, 1L).delta).mintedTotal() },
        ).joinToString(" ") { (name, call) ->
            val mark = clock.markNow()
            repeat(PRIMITIVE_REPS) { call() }
            "$name=${mark.elapsedNow().inWholeMicroseconds / PRIMITIVE_REPS}"
        }
        val completeTimes = proto.openLeases().map { lease ->
            val mark = clock.markNow()
            proto.complete(lease.id, actual = 2L)
            mark.elapsedNow()
        }

        val after = proto.ledger
        val leases = TIMED + 1
        assertAll(
            { assertTrue(first is PrototypeAcquire.Acquired, "the seating acquire: $first") },
            { assertEquals(active, after.activeChildren(lane).size, "active children") },
            { assertEquals(retired, gone.count { after.lifecycle(it.id) == Lifecycle.RETIRED }, "retired edges") },
            { assertEquals(served, servedRecords.count { after.edge(it.id)?.spent == SERVED_COST }, "served retired edges carry their spend") },
            { assertEquals(active, children.count { after.gauge(it.id) != null }, "the first acquire seated every newborn") },
            { assertEquals(TIMED, acquireTimes.size, "timed acquires") },
            { assertEquals(leases, completeTimes.size, "every lease completed") },
            { assertEquals(served * SERVED_COST + leases * 2L, after.leafSpentTotal(), "history plus each lease's 2 units") },
            // The retired children's spend is still subtracted at the lane, or this would read `served × cost`.
            { assertEquals(0L, after.holdings(lane, self), "the lane holds nothing once every lease is done") },
            {
                assertEquals(
                    after.mintedTotal(),
                    after.holdings(ROOT, self) + after.holdings(lane, self) + after.leafSpentTotal(),
                    "conservation: every unit minted is held at the root or spent",
                )
            },
        )
        return "LEASE-SCALE active=$active retired=$retired served=$served buildMs=${buildTime.inWholeMilliseconds} " +
            "firstAcquireMs=${seatTime.inWholeMilliseconds} " +
            "acquireUs[mean=${acquireTimes.meanUs()} min=${acquireTimes.minOf { it.inWholeMicroseconds }} max=${acquireTimes.maxOf { it.inWholeMicroseconds }}] " +
            "completeUs[mean=${completeTimes.meanUs()} max=${completeTimes.maxOf { it.inWholeMicroseconds }}] " +
            "primitiveUs[$primitives] mints=${proto.mints}"
    }

    /** Join many small deltas pairwise, so building `n` edges costs `n log n` map copies rather than `n²`. */
    private fun joinAll(deltas: List<EntitlementLedger>): EntitlementLedger {
        var layer = deltas
        while (layer.size > 1) {
            layer = layer.chunked(2).map { pair -> if (pair.size == 2) pair[0].piece(pair[1]) else pair[0] }
        }
        return layer.singleOrNull() ?: EntitlementLedger.ZERO
    }

    private fun List<Duration>.meanUs(): Long = sumOf { it.inWholeMicroseconds } / size

    private companion object {
        const val TIMED = 10
        const val PRIMITIVE_REPS = 5
        const val SERVED_COST = 3L
        const val WATERMARK = 40L
        val ROOT = GroupId("root")
    }
}
