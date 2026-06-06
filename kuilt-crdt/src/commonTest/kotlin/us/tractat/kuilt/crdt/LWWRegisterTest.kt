package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LWWRegisterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyHasNoValue() {
        assertNull(LWWRegister.empty<String>().value)
    }

    @Test
    fun setThenRead() {
        assertEquals("x", LWWRegister.empty<String>().set(a, 10L, "x").value)
    }

    @Test
    fun laterTimestampWins() {
        val r1 = LWWRegister.empty<String>().set(a, 10L, "x")
        val r2 = LWWRegister.empty<String>().set(b, 20L, "y")
        assertEquals("y", r1.piece(r2).value)
        assertEquals("y", r2.piece(r1).value) // commutative
    }

    @Test
    fun tieBreaksOnReplicaIdLexicographically() {
        // Same timestamp; B > A → "y" wins.
        val r1 = LWWRegister.empty<String>().set(a, 10L, "x")
        val r2 = LWWRegister.empty<String>().set(b, 10L, "y")
        assertEquals("y", r1.piece(r2).value)
        assertEquals("y", r2.piece(r1).value)
    }

    @Test
    fun mergeIsIdempotent() {
        val r = LWWRegister.empty<String>().set(a, 10L, "x")
        assertEquals(r, r.piece(r))
    }

    @Test
    fun roundTripsThroughJson() {
        val r = LWWRegister.empty<String>().set(a, 10L, "x")
        val ser = LWWRegister.serializer(String.serializer())
        assertEquals(r, Json.decodeFromString(ser, Json.encodeToString(ser, r)))
    }
}
