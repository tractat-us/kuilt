package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

private val json = Json { allowStructuredMapKeys = true } // DotFun's keys are Dots

class ResettableCounterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    // ── Basic operations ─────────────────────────────────────────────────────

    @Test
    fun zeroOnCreation() {
        assertEquals(0L, ResettableCounter.ZERO.value)
    }

    @Test
    fun incrementIncreasesValue() {
        var counter = ResettableCounter.ZERO
        counter = counter.piece(counter.increment(a, 3L))
        assertEquals(3L, counter.value)
    }

    @Test
    fun incrementsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { ResettableCounter.ZERO.increment(a, 0L) }
    }

    @Test
    fun multipleReplicaIncrementsSum() {
        var counter = ResettableCounter.ZERO
        counter = counter.piece(counter.increment(a, 3L))
        counter = counter.piece(counter.increment(b, 5L))
        assertEquals(8L, counter.value)
    }

    @Test
    fun resetClearsToZero() {
        var counter = ResettableCounter.ZERO
        counter = counter.piece(counter.increment(a, 10L))
        counter = counter.piece(counter.reset())
        assertEquals(0L, counter.value)
    }

    // ── The key property: observed-reset semantics ────────────────────────────

    /**
     * Increment concurrent with reset survives: a reset removes only the
     * increments it causally observed; an increment concurrent with the reset
     * (i.e. minted on a replica that had not yet seen the reset) survives the merge.
     */
    @Test
    fun incrementConcurrentWithResetSurvives() {
        // Shared start: A has incremented 5
        val start = ResettableCounter.ZERO.piece(ResettableCounter.ZERO.increment(a, 5L))

        // B resets based on what it saw (the 5 from A)
        val afterReset = start.piece(start.reset())

        // Concurrently, A increments again (A hasn't seen B's reset yet)
        val concurrentIncrement = start.increment(a, 3L)
        val aWithConcurrentIncrement = start.piece(concurrentIncrement)

        // Merge: B's reset removes the 5 it saw, but A's +3 (which B never saw) survives
        val merged = afterReset.piece(aWithConcurrentIncrement)
        assertEquals(3L, merged.value)
    }

    @Test
    fun resetOnlyRemovesObservedIncrements() {
        // A increments to 10, B sees it and resets, C concurrently adds 7 (C hasn't seen the reset)
        var shared = ResettableCounter.ZERO
        shared = shared.piece(shared.increment(a, 10L))

        val bSaw = shared
        val afterReset = bSaw.piece(bSaw.reset())

        val cMissedReset = shared.piece(shared.increment(c, 7L))

        val merged = afterReset.piece(cMissedReset)
        assertEquals(7L, merged.value)
    }

    // ── Merge laws ────────────────────────────────────────────────────────────

    @Test
    fun pieceIsIdempotent() {
        var counter = ResettableCounter.ZERO
        counter = counter.piece(counter.increment(a, 5L))
        assertEquals(counter, counter.piece(counter))
    }

    @Test
    fun pieceIsCommutative() {
        val start = ResettableCounter.ZERO.piece(ResettableCounter.ZERO.increment(a, 5L))
        val left = start.piece(start.increment(b, 3L))
        val right = start.piece(start.reset())
        assertEquals(left.piece(right), right.piece(left))
    }

    @Test
    fun pieceIsAssociative() {
        val x = ResettableCounter.ZERO.piece(ResettableCounter.ZERO.increment(a, 1L))
        val y = ResettableCounter.ZERO.piece(ResettableCounter.ZERO.increment(b, 2L))
        val z = ResettableCounter.ZERO.piece(ResettableCounter.ZERO.increment(c, 3L))
        assertEquals(x.piece(y).piece(z), x.piece(y.piece(z)))
    }

    @Test
    fun resetAfterResetIsIdempotent() {
        var counter = ResettableCounter.ZERO
        counter = counter.piece(counter.increment(a, 5L))
        val reset1 = counter.piece(counter.reset())
        val reset2 = reset1.piece(reset1.reset())
        assertEquals(0L, reset1.value)
        assertEquals(0L, reset2.value)
    }

    // ── Delta minimality ─────────────────────────────────────────────────────

    /**
     * `increment` must return a minimal delta: one dot in the store, not the full prior state.
     * Anti-entropy carries the full state when a peer is behind; the per-op delta is
     * only the new information — O(1), not O(live-dots).
     */
    @Test
    fun incrementDeltaIsMinimal() {
        var counter = ResettableCounter.ZERO
        repeat(10) { counter = counter.piece(counter.increment(a, 1L)) }

        val delta = counter.increment(b, 5L)
        // The delta's store must contain exactly one dot — the freshly minted one.
        assertEquals(1, dotCount(delta.delta))
    }

    private fun dotCount(rc: ResettableCounter): Int = rc.causal.store.values.size

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        var counter = ResettableCounter.ZERO
        counter = counter.piece(counter.increment(a, 7L))
        counter = counter.piece(counter.increment(b, 3L))
        val encoded = json.encodeToString(ResettableCounter.serializer(), counter)
        val decoded = json.decodeFromString(ResettableCounter.serializer(), encoded)
        assertEquals(counter, decoded)
    }
}
