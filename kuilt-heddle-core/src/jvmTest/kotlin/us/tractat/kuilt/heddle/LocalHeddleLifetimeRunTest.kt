package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The run the lifetime limit was measured on (design §7.1, #3475), run to its end: two weight-1
 * lanes leased at [LocalHeddle.MAX_RESERVATION] and charged in full. Before the limit, 524,287
 * leases ran and the next acquire threw `ArithmeticException` from the mint. Now the root's minted
 * supply reaches the watermark, `Long.MAX_VALUE − LIFETIME_HEADROOM`, exactly, after
 * `watermark / MAX_RESERVATION` leases, and the next acquire answers the typed refusal.
 *
 * On the JVM only: it is half a million leases, and the rows of [LocalHeddleLifetimeTest] pin every
 * side of the limit on every target from rigged counters.
 */
class LocalHeddleLifetimeRunTest {

    @Test
    fun twoLanesAtTheLargestLeaseEndInTheTypedRefusal() {
        val topology = draft("run") {
            lane("a")
            lane("b")
        }
        val lanes = setOf(topology.groupId("a"), topology.groupId("b"))
        val max = LocalHeddle.MAX_RESERVATION
        val h = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = max), epoch = 0L).apply { reconcileCapacity(1L) }
        var leases = 0L
        var last: AcquireOutcome
        while (true) {
            last = h.acquire(lanes, lanes, startedAt = leases)
            if (last !is AcquireOutcome.Acquired) break
            h.complete(last.lease.id, actualCost = max, waiting = lanes)
            leases++
        }
        val ledger = h.snapshot().ledger
        assertAll(
            { assertEquals(LIFETIME_WATERMARK / max, leases, "the count the watermark gives") },
            { assertEquals(507_903L, leases) },
            { assertEquals(AcquireOutcome.LifetimeExhausted(topology.groupId()), last) },
            { assertEquals(LIFETIME_WATERMARK, ledger.mintedSupply(topology.groupId(), LocalHeddle.SELF), "the supply ends on the watermark") },
            { assertIs<AcquireOutcome.LifetimeExhausted>(h.acquire(lanes, lanes, startedAt = leases), "and stays refused") },
        )
    }
}
