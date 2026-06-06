package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DotTest {

    private val a = ReplicaId("A")

    @Test
    fun seqMustBePositive() {
        assertFailsWith<IllegalArgumentException> { Dot(a, 0L) }
    }

    @Test
    fun dotsAreValueEqual() {
        assertEquals(Dot(a, 1L), Dot(ReplicaId("A"), 1L))
    }

    @Test
    fun dotRoundTripsThroughJson() {
        val dot = Dot(a, 7L)
        val encoded = Json.encodeToString(Dot.serializer(), dot)
        assertEquals(dot, Json.decodeFromString(Dot.serializer(), encoded))
    }
}
