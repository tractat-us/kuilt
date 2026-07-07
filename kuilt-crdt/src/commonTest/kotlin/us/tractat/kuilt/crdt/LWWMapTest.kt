package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import us.tractat.kuilt.test.assertAll

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
    fun removeHidesTheKeyFromReads() {
        val m = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en")
            .remove(a, 20L, "lang")
        assertAll(
            { assertNull(m["lang"]) },
            { assertEquals(emptyMap<String, String>(), m.entries) },
        )
    }

    @Test
    fun removeThenConcurrentPut_laterPutWins() {
        val base = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val removed = base.remove(a, 20L, "lang")
        val rewritten = base.set(b, 30L, "lang", "fr")
        assertAll(
            { assertEquals("fr", removed.piece(rewritten)["lang"]) },
            { assertEquals("fr", rewritten.piece(removed)["lang"]) },
        )
    }

    @Test
    fun putThenConcurrentRemove_laterRemoveWins() {
        val base = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val rewritten = base.set(b, 20L, "lang", "fr")
        val removed = base.remove(a, 30L, "lang")
        assertAll(
            { assertNull(removed.piece(rewritten)["lang"]) },
            { assertNull(rewritten.piece(removed)["lang"]) },
        )
    }

    @Test
    fun removeVsPutSameTimestamp_tieBreaksOnReplicaId() {
        // Same ts=20; B > A lexicographically, so B's remove beats A's put — both directions.
        val base = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val putByA = base.set(a, 20L, "lang", "fr")
        val removedByB = base.remove(b, 20L, "lang")
        assertAll(
            { assertNull(putByA.piece(removedByB)["lang"]) },
            { assertNull(removedByB.piece(putByA)["lang"]) },
        )
    }

    @Test
    fun removeOfAbsentKeyStillBeatsAnEarlierConcurrentPut() {
        // The remove must leave a tombstone even when the key was never set locally,
        // so a concurrent earlier-timestamped put arriving later still loses.
        val removed = LWWMap.empty<String, String>().remove(b, 20L, "lang")
        val put = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        assertAll(
            { assertNull(removed.piece(put)["lang"]) },
            { assertNull(put.piece(removed)["lang"]) },
        )
    }

    @Test
    fun mergeWithTombstonesIsIdempotentAndCommutative() {
        val m1 = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en")
            .remove(a, 20L, "lang")
        val m2 = LWWMap.empty<String, String>().set(b, 15L, "lang", "fr")
        assertAll(
            { assertEquals(m1, m1.piece(m1)) },
            { assertEquals(m1.piece(m2), m2.piece(m1)) },
            { assertNull(m1.piece(m2)["lang"]) },
        )
    }

    @Test
    fun deltaStateCarriesTheTombstone() {
        // A stale replica that only absorbs the post-remove state converges to removed.
        val base = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val removed = base.remove(a, 20L, "lang")
        assertNull(base.piece(removed)["lang"])
    }

    @Test
    fun tombstoneSurvivesJsonRoundTrip() {
        val m = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en")
            .remove(a, 20L, "lang")
        val ser = LWWMap.serializer(String.serializer(), String.serializer())
        val decoded = Json.decodeFromString(ser, Json.encodeToString(ser, m))
        val stale = LWWMap.empty<String, String>().set(b, 15L, "lang", "fr")
        assertAll(
            { assertEquals(m, decoded) },
            { assertNull(decoded.piece(stale)["lang"]) }, // tombstone still wins after the wire
        )
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
