package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dense per-author [RgaId.seq] (#262): minted contiguously from the op-log,
 * orthogonal to [RgaId.lamport]'s ordering role, and wire-round-tripped.
 */
class RgaIdSeqTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test
    fun seqIsDenseAndContiguousPerAuthor() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (r2, op2) = r1.insertAfter(a, op1.id, "y")
        val (_, op3) = r2.insertAfter(a, op2.id, "z")
        assertEquals(listOf(1L, 2L, 3L), listOf(op1.id.seq, op2.id.seq, op3.id.seq))
    }

    @Test
    fun seqIsIndependentPerAuthor() {
        val (r1, opA) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "a1")
        val (r2, opB) = r1.insertAfter(b, opA.id, "b1") // b's first op
        val (_, opA2) = r2.insertAfter(a, opB.id, "a2") // a's second op
        assertEquals(1L, opA.id.seq)
        assertEquals(1L, opB.id.seq, "each author counts from 1 independently")
        assertEquals(2L, opA2.id.seq)
    }

    @Test
    fun nextSeqIsDerivedFromOpLog_notExternalState() {
        // A replica reconstructed purely from ops mints the correct next seq.
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val rebuilt = Rga.empty<String>().apply(op1) // only the op, no minting history
        val (_, op2) = rebuilt.insertAfter(a, op1.id, "y")
        assertEquals(2L, op2.id.seq, "next seq derives from the op-log's highest seq for the author")
    }

    @Test
    fun seqDoesNotAffectOrdering() {
        // Lamport (not seq) orders; two ids differing only in lamport order by it.
        val low = RgaId(lamport = 1L, replicaId = a, seq = 99L)
        val high = RgaId(lamport = 2L, replicaId = a, seq = 1L)
        assertTrue(low < high, "higher lamport wins regardless of seq")
    }

    @Test
    fun dotExposesAuthorAndSeq() {
        val (_, op) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        assertEquals(Dot(a, 1L), op.id.dot)
    }

    @Test
    fun seqRoundTripsThroughTheWire() {
        val op = RgaOp.Insert(
            id = RgaId(lamport = 5L, replicaId = a, seq = 7L),
            value = "x",
            after = RgaId.HEAD,
        )
        val ser = RgaOp.serializer(serializer<String>())
        val decoded = Json.decodeFromString(ser, Json.encodeToString(ser, op)) as RgaOp.Insert
        assertEquals(7L, decoded.id.seq)
        assertEquals(op, decoded)
    }

    @Test
    fun applyDoesNotResurrectACompactedId() {
        // I is inserted, removed, and compacted; a late raw apply of I must not re-add it.
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (a1, _) = a0.removeAt(0)!!
        val stable = VersionVector.of(mapOf(a to opI.id.seq))
        val (compacted, _) = a1.compact(stableCut = stable, frontierMax = stable, delivered = stable)!!
        assertEquals(emptySet(), compacted.tombstones)

        // Late raw re-delivery of the purged Insert (and its Remove) must be no-ops.
        val afterReapply = compacted.apply(opI).apply(RgaOp.Remove(opI.id))
        assertEquals(emptyList(), afterReapply.toList(), "apply consults compactedIds — no re-inflation")
        assertEquals(emptySet(), afterReapply.tombstones)
        assertEquals(compacted, afterReapply, "apply of a compacted id is a no-op (agrees with piece)")
    }
}
