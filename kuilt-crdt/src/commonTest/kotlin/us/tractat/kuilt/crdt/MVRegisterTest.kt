package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MVRegisterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val json = Json { allowStructuredMapKeys = true } // DotFun's keys are Dots

    @Test
    fun emptyHasNoValue() {
        assertEquals(emptySet(), MVRegister.empty<String>().values)
    }

    @Test
    fun setThenRead() {
        assertEquals(setOf("x"), MVRegister.empty<String>().set(a, "x").values)
    }

    @Test
    fun concurrentWritesKeepBothValues() {
        val base = MVRegister.empty<String>()
        val x = base.set(a, "x")
        val y = base.set(b, "y")
        assertEquals(setOf("x", "y"), x.piece(y).values)
    }

    @Test
    fun aLaterWriteResolvesTheConflict() {
        val base = MVRegister.empty<String>()
        val conflicted = base.set(a, "x").piece(base.set(b, "y")) // {x, y}
        val resolved = conflicted.set(a, "z") // observes both, supersedes
        assertEquals(setOf("z"), resolved.values)
    }

    @Test
    fun roundTripsThroughJson() {
        val r = MVRegister.empty<String>().set(a, "x")
        val ser = MVRegister.serializer(String.serializer())
        assertEquals(r, json.decodeFromString(ser, json.encodeToString(ser, r)))
    }
}
