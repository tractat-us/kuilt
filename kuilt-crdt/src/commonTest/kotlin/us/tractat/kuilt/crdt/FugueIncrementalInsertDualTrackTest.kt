package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import us.tractat.kuilt.test.assertAll

/**
 * Differential regression tests for #1211: the incremental [Fugue.insertAt]
 * fast path (threaded [FugueSeqState], no per-insert tree rebuild) must be
 * observationally identical to the pre-#1211 rebuild-per-insert
 * implementation, which is preserved verbatim as
 * [Fugue.insertOpViaRebuildOracle].
 *
 * Three properties are pinned across randomized multi-replica workloads
 * (local inserts at head/middle/tail, local removes, staggered remote
 * delivery — so the fast path constantly alternates between threading its
 * state forward and rebuilding after a remote apply):
 *
 * 1. **Minted-op identity** — every op produced by the incremental path
 *    equals the oracle's op field-for-field ([FugueId], parent, side,
 *    rightOrigin). Ops are what get serialized, so this is byte-stability of
 *    everything the CRDT ever puts on the wire.
 * 2. **Resolved-order identity** — after every local edit, the threaded
 *    state's sequence equals a from-scratch rebuild of the same op-log
 *    ([Fugue.fromOps]), which is the pre-#1211 materialisation path.
 * 3. **Convergence + byte-stability** — after full delivery all replicas
 *    resolve the same list and serialize to identical bytes.
 */
class FugueIncrementalInsertDualTrackTest {

    private val serializer = Fugue.wireSerializer(Int.serializer())
    private val json = Json { encodeDefaults = true }

    @Test
    fun incrementalInsertMatchesRebuildOracleAcrossRandomizedWorkloads() {
        for (seed in 0L until 40L) {
            runWorkload(Random(seed), steps = 60, label = "seed=$seed")
        }
    }

    @Test
    fun appendAfterTrailingTombstoneMatchesOracle() {
        val a = ReplicaId("A")
        var f = Fugue.empty<Int>()
        val ops = mutableListOf<FugueOp<Int>>()
        for (i in 0 until 3) {
            val (next, op) = f.insertAt(a, i, i)
            f = next
            ops += op
        }
        // Tombstone the tail element — the next append's left origin is then NOT
        // the last node of the full traversal (the tombstoned node still follows
        // it), so the fast path may not take the trivial end-of-list shortcut.
        val (removed, removeOp) = checkNotNull(f.removeAt(2))
        f = removed
        ops += removeOp

        val oracleOp = f.insertOpViaRebuildOracle(a, 2, 3)
        val (appended, insertOp) = f.insertAt(a, 2, 3)
        ops += insertOp

        // A second replica absorbing the raw op stream must converge with the
        // incrementally-maintained originator.
        val replay = ops.fold(Fugue.empty<Int>()) { acc, op -> acc.apply(op) }
        assertAll(
            { assertEquals(oracleOp, insertOp, "op after trailing tombstone must match rebuild oracle") },
            { assertEquals(listOf(0, 1, 3), appended.toList()) },
            {
                assertEquals(
                    appended.toList(),
                    Fugue.fromOps(appended.ops, appended.lamport).toList(),
                    "threaded state must match from-scratch rebuild",
                )
            },
            { assertEquals(appended.toList(), replay.toList(), "op replay must converge") },
            { assertEquals(replay.toList(), replay.apply(insertOp).toList(), "duplicate delivery stays idempotent") },
        )
    }

    private fun runWorkload(random: Random, steps: Int, label: String) {
        val replicas = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))
        val states = MutableList(replicas.size) { Fugue.empty<Int>() }
        val globalOps = mutableListOf<FugueOp<Int>>()
        val cursors = IntArray(replicas.size)
        var nextValue = 0

        repeat(steps) { step ->
            val r = random.nextInt(replicas.size)
            when (random.nextInt(10)) {
                in 0..4 -> {
                    val f = states[r]
                    val index = pickInsertIndex(random, f.size)
                    val value = nextValue++
                    val oracleOp = f.insertOpViaRebuildOracle(replicas[r], index, value)
                    val (next, op) = f.insertAt(replicas[r], index, value)
                    assertEquals(
                        oracleOp,
                        op,
                        "$label step=$step: incremental op must equal rebuild-oracle op",
                    )
                    assertEquals(
                        Fugue.fromOps(next.ops, next.lamport).toList(),
                        next.toList(),
                        "$label step=$step: threaded sequence must equal from-scratch rebuild",
                    )
                    states[r] = next
                    globalOps += op
                }
                in 5..6 -> {
                    val f = states[r]
                    if (f.size > 0) {
                        val (next, op) = checkNotNull(f.removeAt(random.nextInt(f.size)))
                        assertEquals(
                            Fugue.fromOps(next.ops, next.lamport).toList(),
                            next.toList(),
                            "$label step=$step: post-remove sequence must equal from-scratch rebuild",
                        )
                        states[r] = next
                        globalOps += op
                    }
                }
                else -> {
                    if (cursors[r] < globalOps.size) {
                        states[r] = states[r].apply(globalOps[cursors[r]])
                        cursors[r]++
                    }
                }
            }
        }

        // Full delivery: every replica absorbs the whole op stream (self-ops are
        // idempotent re-applies), then all three must agree byte-for-byte.
        for (r in replicas.indices) {
            while (cursors[r] < globalOps.size) {
                states[r] = states[r].apply(globalOps[cursors[r]])
                cursors[r]++
            }
        }
        val lists = states.map { it.toList() }
        val encodings = states.map { json.encodeToString(serializer, it) }
        assertAll(
            { assertEquals(lists[0], lists[1], "$label: A and B must converge") },
            { assertEquals(lists[1], lists[2], "$label: B and C must converge") },
            { assertEquals(encodings[0], encodings[1], "$label: A and B bytes must match") },
            { assertEquals(encodings[1], encodings[2], "$label: B and C bytes must match") },
            {
                assertEquals(
                    Fugue.fromOps(states[0].ops, states[0].lamport).toList(),
                    lists[0],
                    "$label: converged sequence must equal from-scratch rebuild",
                )
            },
        )
    }

    /** Bias towards appends (the #1211 pathological workload), with head + middle mixed in. */
    private fun pickInsertIndex(random: Random, size: Int): Int = when {
        size == 0 -> 0
        random.nextInt(10) < 5 -> size                     // append
        random.nextInt(10) < 3 -> 0                        // prepend
        else -> random.nextInt(size + 1)                   // anywhere
    }
}
