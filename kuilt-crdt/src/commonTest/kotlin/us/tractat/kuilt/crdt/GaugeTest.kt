package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GaugeTest {

    private val r1 = ReplicaId("r1")
    private val r2 = ReplicaId("r2")
    private val r3 = ReplicaId("r3")

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    fun emptyGaugeHasNoObservation() {
        val gauge = Gauge.empty()
        assertAll(
            { assertNull(gauge.value) },
            { assertNull(gauge.timestamp) },
        )
    }

    @Test
    fun observeSetsValueAndTimestamp() {
        val gauge = Gauge.empty().piece(Gauge.empty().observe(r1, timestamp = 42L, value = 7.5))
        assertAll(
            { assertEquals(7.5, gauge.value) },
            { assertEquals(42L, gauge.timestamp) },
        )
    }

    @Test
    fun nonFiniteObservationsAreRejected() {
        val gauge = Gauge.empty()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { gauge.observe(r1, 1L, Double.NaN) } },
            { assertFailsWith<IllegalArgumentException> { gauge.observe(r1, 1L, Double.POSITIVE_INFINITY) } },
            { assertFailsWith<IllegalArgumentException> { gauge.observe(r1, 1L, Double.NEGATIVE_INFINITY) } },
        )
    }

    // ── Last-writer-wins convergence ──────────────────────────────────────────

    @Test
    fun laterObservationWinsUnderMergeInBothOrders() {
        val older = Gauge.empty().observe(r1, timestamp = 10L, value = 1.0)
        val newer = Gauge.empty().observe(r2, timestamp = 20L, value = 2.0)
        assertAll(
            { assertEquals(2.0, older.piece(newer).value) },
            { assertEquals(2.0, newer.piece(older).value) },
            { assertEquals(20L, older.piece(newer).timestamp) },
        )
    }

    @Test
    fun anOlderObservationNeverRegressesTheGauge() {
        val gauge = Gauge.empty().piece(Gauge.empty().observe(r1, timestamp = 20L, value = 2.0))
        val merged = gauge.piece(gauge.observe(r1, timestamp = 10L, value = 1.0))
        assertAll(
            { assertEquals(2.0, merged.value) },
            { assertEquals(20L, merged.timestamp) },
        )
    }

    @Test
    fun equalTimestampTieBreaksOnReplicaIdDeterministically() {
        val a = Gauge.empty().observe(r1, timestamp = 5L, value = 1.0)
        val b = Gauge.empty().observe(r2, timestamp = 5L, value = 2.0)
        // Lexicographically larger replica id ("r2") wins, in both merge orders.
        assertAll(
            { assertEquals(2.0, a.piece(b).value) },
            { assertEquals(2.0, b.piece(a).value) },
            { assertEquals(a.piece(b), b.piece(a)) },
        )
    }

    // ── CRDT laws ─────────────────────────────────────────────────────────────

    @Test
    fun pieceIsIdempotent() {
        val gauge = Gauge.empty().observe(r1, 3L, 9.0)
        assertEquals(gauge, gauge.piece(gauge))
    }

    @Test
    fun pieceIsCommutative() {
        val a = Gauge.empty().observe(r1, 1L, 1.0)
        val b = Gauge.empty().observe(r2, 2L, 2.0)
        assertEquals(a.piece(b), b.piece(a))
    }

    @Test
    fun pieceIsAssociative() {
        val a = Gauge.empty().observe(r1, 1L, 1.0)
        val b = Gauge.empty().observe(r2, 2L, 2.0)
        val c = Gauge.empty().observe(r3, 3L, 3.0)
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    @Test
    fun emptyIsTheMergeIdentity() {
        val gauge = Gauge.empty().observe(r1, 7L, 4.2)
        assertAll(
            { assertEquals(gauge, gauge.piece(Gauge.empty())) },
            { assertEquals(gauge, Gauge.empty().piece(gauge)) },
        )
    }

    /**
     * Seeded convergence: many observations spread across three replicas, merged
     * in several shuffled orders, all converge to the observation with the
     * largest `(timestamp, replicaId)` tag.
     */
    @Test
    fun shuffledMergeOrdersConvergeToTheLatestObservation() {
        val rng = Random(42)
        val replicas = listOf(r1, r2, r3)
        // Distinct timestamps per (replica, op) keep the tag-uniqueness contract.
        val observations = List(60) { i ->
            Triple(replicas[rng.nextInt(replicas.size)], i.toLong(), rng.nextDouble(-100.0, 100.0))
        }
        val perReplica = replicas.map { replica ->
            observations.filter { it.first == replica }
                .fold(Gauge.empty()) { acc, (r, ts, v) -> acc.piece(acc.observe(r, ts, v)) }
        }
        val expected = observations.maxBy { it.second }
        val orders = listOf(
            perReplica,
            perReplica.reversed(),
            perReplica.shuffled(Random(7)),
        )
        val merged = orders.map { order -> order.reduce { a, b -> a.piece(b) } }
        assertAll(
            { assertEquals(expected.third, merged[0].value) },
            { assertEquals(expected.second, merged[0].timestamp) },
            { assertEquals(merged[0], merged[1]) },
            { assertEquals(merged[0], merged[2]) },
        )
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        val gauge = Gauge.empty().observe(r1, 99L, 12.25)
        val encoded = Json.encodeToString(Gauge.serializer(), gauge)
        val decoded = Json.decodeFromString(Gauge.serializer(), encoded)
        assertAll(
            { assertEquals(gauge, decoded) },
            { assertEquals(gauge.value, decoded.value) },
            { assertEquals(gauge.timestamp, decoded.timestamp) },
        )
    }
}
