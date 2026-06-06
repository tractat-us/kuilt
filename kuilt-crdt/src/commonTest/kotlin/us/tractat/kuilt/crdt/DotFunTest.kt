package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DotFunTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    // JSON map keys are the structured Dot type, so allow structured keys.
    private val json = Json { allowStructuredMapKeys = true }

    @Test
    fun dotsAreTheKeys() {
        val f = DotFun(mapOf(Dot(a, 1L) to "red", Dot(b, 1L) to "blue"))
        assertEquals(setOf(Dot(a, 1L), Dot(b, 1L)), f.dots)
    }

    @Test
    fun concurrentWritesKeepBothValues() {
        // Alice writes "red" with (A,1); Bob writes "blue" with (B,1); concurrent.
        val alice = Causal(DotFun(mapOf(Dot(a, 1L) to "red")), DotContext.of(Dot(a, 1L)))
        val bob = Causal(DotFun(mapOf(Dot(b, 1L) to "blue")), DotContext.of(Dot(b, 1L)))
        val merged = alice.piece(bob)
        assertEquals(setOf("red", "blue"), merged.store.values.values.toSet())
    }

    @Test
    fun aWriteThatObservedBothReplacesThem() {
        // old: (A,1)->red, (B,1)->blue, both seen. New write observing both mints (A,2)->green.
        val old = Causal(
            DotFun(mapOf(Dot(a, 1L) to "red", Dot(b, 1L) to "blue")),
            DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        )
        val fresh = Causal(
            DotFun(mapOf(Dot(a, 2L) to "green")),
            DotContext.of(Dot(a, 1L), Dot(b, 1L), Dot(a, 2L)),
        )
        val merged = old.piece(fresh)
        assertEquals(mapOf(Dot(a, 2L) to "green"), merged.store.values)
    }

    @Test
    fun roundTripsThroughJson() {
        val f = Causal(DotFun(mapOf(Dot(a, 1L) to "red")), DotContext.of(Dot(a, 1L)))
        val ser = Causal.serializer(DotFun.serializer(String.serializer()))
        assertEquals(f, json.decodeFromString(ser, json.encodeToString(ser, f)))
    }
}
