package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A probe of W1 as design §7.1's step B entry words it (slice 5): does every weight set it admits
 * keep a pick inside 128 bits? The entry bounds a parent's virtual time by `2⁶⁴ · Σ⌈1/wᵢ⌉` over its
 * *current* children. A front carries its history across a change of weights, though: a child that
 * joins is seated at the front, so it inherits the virtual time a finer, since-closed sibling ran up.
 *
 * The bridge: `fine` (`1/2²⁰`) runs up the front, `mid` (`2²²`) joins it there, `fine` leaves, and
 * `heavy` (`2⁵⁴`) joins `mid`. Each set passes W1 and the `Long` products on its own; `heavy` is
 * seated at `V · 2⁵⁴ · 16 = 2¹³⁹` ticks with `V = 2⁸¹`, past 128 bits.
 */
class W1BridgeProbeTest {

    private val fine = Weight.of(1L, 1L shl 20)
    private val mid = Weight.of(1L shl 22)
    private val heavy = Weight.of(1L shl 54)

    private fun tree(vararg lanes: Pair<String, Weight>) = draft("p") { for ((name, w) in lanes) lane(name, w) }

    @Test
    fun everySetW1AdmitsKeepsAPickInside128BitsAcrossAChangeOfWeights() {
        val first = tree("fine" to fine)
        val h = LocalHeddle.bootWhole(first, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        val f = first.groupId("fine")
        val m = tree("mid" to mid).groupId("mid")
        val x = tree("heavy" to heavy).groupId("heavy")
        h.mintSupply(1L shl 62)
        h.cancel(h.lease(setOf(f)).id)
        // 2⁶¹ units of service at `fine`: its virtual time, and the front, reach 2⁶¹ · 2²⁰ = 2⁸¹.
        val service = 1L shl 61
        h.reshapeForTest { start ->
            var l = start
            val path = checkNotNull(l.lineageOf(f))
            for (edge in path) l = l.piece(checkNotNull(l.delegate(LOCAL, edge, service)).delta)
            l.piece(checkNotNull(l.spendCaptured(LOCAL, path, service)).delta)
        }
        val withMid = h.apply(tree("fine" to fine, "mid" to mid), generation = 1L)
        h.cancel(assertIsLease(h.acquire(setOf(f, m), setOf(m), startedAt = 0L)).id)
        val withoutFine = h.apply(tree("mid" to mid), generation = 2L)
        val withHeavy = h.apply(tree("mid" to mid, "heavy" to heavy), generation = 3L)
        val thrown = try {
            h.acquire(setOf(m, x), setOf(x), startedAt = 1L)
            null
        } catch (e: ArithmeticException) {
            e
        }
        assertAll(
            { assertTrue(weightsFitTheBudget(listOf(fine, mid)), "the rig: {fine, mid} passes W1 and the Long products") },
            { assertTrue(weightsFitTheBudget(listOf(mid, heavy)), "the rig: {mid, heavy} passes too") },
            { assertEquals(emptyList(), withMid.refusals, "the rig: mid joined fine") },
            { assertEquals(emptyList(), withoutFine.refusals, "the rig: fine left") },
            { assertEquals(emptyList(), withHeavy.refusals, "the rig: heavy joined mid") },
            { assertEquals(null, thrown, "a pick under weights W1 admitted threw") },
        )
    }

    private fun assertIsLease(out: AcquireOutcome): Lease {
        check(out is AcquireOutcome.Acquired) { "the rig: expected a lease, got $out" }
        return out.lease
    }
}
