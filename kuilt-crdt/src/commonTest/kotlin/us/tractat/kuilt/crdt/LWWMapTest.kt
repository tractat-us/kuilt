package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LWWMapTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyMap() {
        assertEquals(emptyMap<String, String>(), LWWMap.empty<String, String>().entries)
    }

    @Test
    fun setReturnsTheValue() {
        val m = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        assertEquals("en", m["lang"])
        assertEquals(mapOf("lang" to "en"), m.entries)
    }

    @Test
    fun perKeyLwwSemantics_laterWins() {
        val m1 = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val m2 = LWWMap.empty<String, String>().set(b, 20L, "lang", "fr")
        assertEquals("fr", m1.piece(m2)["lang"])
        assertEquals("fr", m2.piece(m1)["lang"]) // commutative
    }

    @Test
    fun differentKeysComposeIndependently() {
        val m1 = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val m2 = LWWMap.empty<String, String>().set(b, 5L, "tz", "UTC")
        val merged = m1.piece(m2)
        assertEquals("en", merged["lang"])
        assertEquals("UTC", merged["tz"])
    }

    @Test
    fun missingKeyReturnsNull() {
        assertNull(LWWMap.empty<String, String>()["nope"])
    }

    @Test
    fun roundTripsThroughJson() {
        val m = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en")
            .set(b, 20L, "tz", "UTC")
        val ser = LWWMap.serializer(String.serializer(), String.serializer())
        assertEquals(m, Json.decodeFromString(ser, Json.encodeToString(ser, m)))
    }
}
