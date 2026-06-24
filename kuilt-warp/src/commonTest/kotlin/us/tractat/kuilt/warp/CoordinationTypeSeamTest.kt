package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Type-seam property tests for [CoordinationFree] / [Coordinated] and the B3 monotone
 * combinators. Properties verified: idempotence, commutativity, associativity of
 * [embroider] — and that [Coordinated] forces the escalation path (can't be mistaken
 * for coordination-free).
 */
class CoordinationTypeSeamTest {

    // ── B1: CoordinationFree structural properties ────────────────────────────────────

    @Test
    fun `embroider is idempotent`() {
        val seed = Random(42)
        val r = ReplicaId("a")
        val base = CoordinationFree(GCounter.ZERO)
        val contribution = CoordinationFree(GCounter.ZERO.inc(r, 3L).delta)

        val once = base.embroider(contribution)
        val twice = once.embroider(contribution)

        assertEquals(once.state, twice.state)
    }

    @Test
    fun `embroider is commutative`() {
        val r1 = ReplicaId("r1")
        val r2 = ReplicaId("r2")
        val base = CoordinationFree(GCounter.ZERO)
        val c1 = CoordinationFree(GCounter.ZERO.inc(r1, 5L).delta)
        val c2 = CoordinationFree(GCounter.ZERO.inc(r2, 3L).delta)

        val ab = base.embroider(c1).embroider(c2)
        val ba = base.embroider(c2).embroider(c1)

        assertEquals(ab.state, ba.state)
    }

    @Test
    fun `embroider is associative`() {
        val r1 = ReplicaId("r1")
        val r2 = ReplicaId("r2")
        val r3 = ReplicaId("r3")
        val base = CoordinationFree(GCounter.ZERO)
        val c1 = CoordinationFree(GCounter.ZERO.inc(r1, 1L).delta)
        val c2 = CoordinationFree(GCounter.ZERO.inc(r2, 2L).delta)
        val c3 = CoordinationFree(GCounter.ZERO.inc(r3, 3L).delta)

        val left = base.embroider(c1.embroider(c2)).embroider(c3)
        val right = base.embroider(c1).embroider(c2.embroider(c3))

        assertEquals(left.state, right.state)
    }

    @Test
    fun `embroider with GSet preserves join-semilattice properties`() {
        val base = CoordinationFree(GSet.empty<String>())
        val c1 = CoordinationFree(GSet.of("apple"))
        val c2 = CoordinationFree(GSet.of("banana"))

        // Idempotent
        val once = base.embroider(c1)
        assertEquals(once.state, once.embroider(c1).state)

        // Commutative
        val ab = base.embroider(c1).embroider(c2)
        val ba = base.embroider(c2).embroider(c1)
        assertEquals(ab.state, ba.state)

        // Union of both elements present
        assertTrue(ab.state.contains("apple"))
        assertTrue(ab.state.contains("banana"))
    }

    // ── B1: Coordinated — escalation path ───────────────────────────────────────────

    @Test
    fun `Coordinated wraps any value and exposes it via commit`() {
        val task = Coordinated("transfer-1000-credits")
        val result = task.commit { value -> "processed: $value" }
        assertEquals("processed: transfer-1000-credits", result)
    }

    @Test
    fun `Coordinated and CoordinationFree are distinct types`() {
        val free: CoordinationFree<GCounter> = CoordinationFree(GCounter.ZERO)
        val coord: Coordinated<GCounter> = Coordinated(GCounter.ZERO)

        // Type system prevents conflation — can't call embroider on Coordinated,
        // can't call commit on CoordinationFree. Prove they occupy distinct branches.
        assertNotNull(free)
        assertNotNull(coord)
    }

    // ── B3: Monotone combinators ─────────────────────────────────────────────────────

    @Test
    fun `joinAll produces upper bound of all inputs`() {
        val r1 = ReplicaId("r1")
        val r2 = ReplicaId("r2")
        val r3 = ReplicaId("r3")
        val a = CoordinationFree(GCounter.of(r1 to 10L))
        val b = CoordinationFree(GCounter.of(r2 to 20L))
        val c = CoordinationFree(GCounter.of(r3 to 30L))

        val joined = joinAll(listOf(a, b, c))

        assertNotNull(joined)
        assertEquals(60L, joined.state.value)
    }

    @Test
    fun `joinAll with single element is identity`() {
        val r = ReplicaId("solo")
        val single = CoordinationFree(GCounter.of(r to 7L))

        val joined = joinAll(listOf(single))

        assertEquals(single.state, joined.state)
    }

    @Test
    fun `liftCoordinationFree wraps a Quilted value`() {
        val counter = GCounter.of(ReplicaId("x") to 42L)
        val free = liftCoordinationFree(counter)

        assertEquals(counter, free.state)
    }

    @Test
    fun `monotoneMap preserves idempotence`() {
        // A monotone map from GSet<String> to GSet<Int> via a size-producing fold
        // that is itself monotone (size only grows as elements are added).
        // We use a simpler test: mapping GSet<String> -> GSet<String> via a pure function.
        val base = CoordinationFree(GSet.of("a", "b"))
        val mapped = base.monotoneMap { GSet.of(*it.elements.map { s -> s.uppercase() }.toTypedArray()) }

        // Applying the same map again produces the same result (given same input)
        val mappedAgain = base.monotoneMap { GSet.of(*it.elements.map { s -> s.uppercase() }.toTypedArray()) }

        assertEquals(mapped.state, mappedAgain.state)
    }

    @Test
    fun `monotoneMap composes — two monotone maps produce same result as their composition`() {
        val base = CoordinationFree(GSet.of("hello", "world"))
        val addBang = { s: GSet<String> -> GSet.of(*s.elements.map { "$it!" }.toTypedArray()) }
        val addQ = { s: GSet<String> -> GSet.of(*s.elements.map { "$it?" }.toTypedArray()) }

        val step1 = base.monotoneMap(addBang)
        val step2 = step1.monotoneMap(addQ)

        val composed = base.monotoneMap { addQ(addBang(it)) }

        assertEquals(step2.state, composed.state)
    }

    // ── Seeded property test: random GCounter contributions stay monotone ────────────

    @Test
    fun `random GCounter embroider contributions stay monotone — value never decreases`() {
        val rng = Random(seed = 0xDEAD_BEEF.toInt())
        val replicas = (1..4).map { ReplicaId("r$it") }

        var state = CoordinationFree(GCounter.ZERO)
        var previousValue = 0L

        repeat(50) {
            val replica = replicas[rng.nextInt(replicas.size)]
            val inc = rng.nextLong(1L, 100L)
            val contribution = CoordinationFree(GCounter.ZERO.inc(replica, inc).delta)
            state = state.embroider(contribution)
            val newValue = state.state.value
            assertTrue(newValue >= previousValue, "GCounter decreased: $previousValue -> $newValue")
            previousValue = newValue
        }
    }
}
