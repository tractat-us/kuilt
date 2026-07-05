package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for stack-safe sequence materialisation (#1206, #1207).
 *
 * A long, append-only insertion chain — each element inserted immediately after
 * the previous one — produces a maximally-degenerate tree: a single spine of
 * depth == chain length. Recursive tree traversals then recurse once per element,
 * and a real persisted `Rga<LogRecord>` at ~2,700 elements crashed iOS with a
 * native stack overflow (SIGBUS). The materialisation must be iterative so a chain
 * of any length is safe on every platform.
 *
 * These tests run on all targets once merged, but the failing (pre-fix) proof is
 * only observed on JVM: a native stack overflow is a hard crash, not a catchable
 * failure, and would take the whole test binary down rather than failing one test.
 */
class DeepChainStackSafetyTest {

    private val a = ReplicaId("a")

    // Well above the default JVM thread-stack depth for these frames, yet cheap to
    // materialise once the traversal is iterative (O(n)).
    private val chainLength = 50_000

    @Test
    fun rgaMaterialisesLongAppendOnlyChain() {
        // Build a linear chain directly from ops (folding insertAfter would be O(n^2)
        // due to persistent-set copies): each insert's `after` is the previous id.
        val ops = ArrayList<RgaOp<Int>>(chainLength)
        var prev = RgaId.HEAD
        for (i in 0 until chainLength) {
            val id = RgaId(lamport = (i + 1).toLong(), replicaId = a, seq = (i + 1).toLong())
            ops.add(RgaOp.Insert(id = id, value = i, after = prev))
            prev = id
        }
        val rga = Rga.fromOps(ops.toSet(), lamport = chainLength.toLong())

        val list = rga.toList()

        assertEquals(chainLength, list.size)
        assertEquals((0 until chainLength).toList(), list)
    }

    @Test
    fun fugueMaterialisesLongAppendOnlyChain() {
        // Append-only chain: each element is the right child of the previous one,
        // producing a single right spine of depth == chainLength.
        val ops = ArrayList<FugueOp<Int>>(chainLength)
        var prev = FugueId.HEAD
        for (i in 0 until chainLength) {
            val id = FugueId(lamport = (i + 1).toLong(), replicaId = a, seq = (i + 1).toLong())
            ops.add(FugueOp.Insert(id = id, value = i, parent = prev, side = FugueSide.Right, rightOrigin = null))
            prev = id
        }
        val fugue = Fugue.fromOps(ops.toSet(), lamport = chainLength.toLong())

        val list = fugue.toList()

        assertEquals(chainLength, list.size)
        assertEquals((0 until chainLength).toList(), list)
    }
}
