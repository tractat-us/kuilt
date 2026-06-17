@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a concert-seat reservation system that NEVER oversells, even under concurrent
 * bookings from two box-office replicas, using [BoundedCounter] + [Quilter].
 *
 * A fixed pool of seats (e.g. 10) is pre-divided into per-replica quotas. Each box office
 * can book seats up to its local quota without coordinating with the other. If one office
 * runs low it can transfer quota from the other. Concurrent reservations from different
 * offices converge without exceeding the global pool — the invariant is maintained locally
 * at each replica, so no coordination round-trip is required for the common case.
 *
 * ## Why BoundedCounter fits
 *
 * - A plain PN-counter allows each replica to go negative independently — two offices
 *   each booking 7 of 10 seats converge to -4. [BoundedCounter] denies the over-quota
 *   spend locally before it can be applied.
 * - Quota is pre-split so the hot path (book a seat) never requires cross-replica
 *   coordination. Only quota redistribution ([BoundedCounter.transfer]) talks to peers,
 *   and that is still an offline-safe, optimistic operation.
 * - [Quilter] broadcasts deltas automatically; once both replicas have seen each
 *   other's spends, `totalSpent` and `totalBudget` converge to the same values.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [BoundedCounter.init] to seed a fixed pool split across two [ReplicaId]s
 * - [BoundedCounter.trySpend] — returns a [us.tractat.kuilt.crdt.Patch] or `null` when over-quota
 * - [Quilter.apply] to commit a spend patch and broadcast it
 * - [BoundedCounter.transfer] to move quota between replicas
 * - [Quilter.state] (`StateFlow<BoundedCounter>`) to read the converged view
 */
class SeatReservationTest {

    private val replicatorCfg = QuilterConfig(expectVirtualTime = true)

    private val north = ReplicaId("north-box-office")
    private val south = ReplicaId("south-box-office")

    /** 10 seats split evenly: 5 per box office. */
    private fun initialPool() = BoundedCounter.init(mapOf(north to 5L, south to 5L))

    @Test
    fun `concurrent reservations from two box offices converge without overselling`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamNorth = loom.host(Pattern("seat-reservation"))
            val seamSouth = loom.join(InMemoryTag("south"))

            val northCounter = Quilter(
                seamNorth, initialPool(), BoundedCounter.serializer(), backgroundScope, config = replicatorCfg,
            )
            val southCounter = Quilter(
                seamSouth, initialPool(), BoundedCounter.serializer(), backgroundScope, config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // North office books 3 seats; south office books 2 seats — both within quota.
            val northPatch = northCounter.state.value.trySpend(north, 3L)
            assertNotNull(northPatch, "North should be able to book 3 seats from its quota of 5")
            northCounter.apply(northPatch)

            val southPatch = southCounter.state.value.trySpend(south, 2L)
            assertNotNull(southPatch, "South should be able to book 2 seats from its quota of 5")
            southCounter.apply(southPatch)

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas converge: 3 + 2 = 5 seats sold, 5 remaining.
            assertEquals(5L, northCounter.state.value.totalSpent)
            assertEquals(5L, northCounter.state.value.totalBudget)
            assertEquals(northCounter.state.value.totalSpent, southCounter.state.value.totalSpent)
            assertEquals(northCounter.state.value.totalBudget, southCounter.state.value.totalBudget)
        }

    @Test
    fun `an over-quota reservation is denied locally without any network round-trip`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamNorth = loom.host(Pattern("seat-reservation-deny"))
            val seamSouth = loom.join(InMemoryTag("south"))

            val northCounter = Quilter(
                seamNorth, initialPool(), BoundedCounter.serializer(), backgroundScope, config = replicatorCfg,
            )
            val southCounter = Quilter(
                seamSouth, initialPool(), BoundedCounter.serializer(), backgroundScope, config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // North tries to book all 10 seats at once — only has quota for 5.
            val denied = northCounter.state.value.trySpend(north, 10L)
            assertNull(denied, "North must not be able to exceed its local quota of 5")

            // South books its full quota of 5.
            val southPatch = southCounter.state.value.trySpend(south, 5L)
            assertNotNull(southPatch)
            southCounter.apply(southPatch)

            delay(10)

            // Total sold = 5 (south only); north's denial left the state unchanged.
            assertEquals(5L, northCounter.state.value.totalSpent)
            assertEquals(5L, northCounter.state.value.totalBudget)
        }

    @Test
    fun `quota transfer lets a busy replica borrow capacity from an idle one`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamNorth = loom.host(Pattern("seat-reservation-transfer"))
            val seamSouth = loom.join(InMemoryTag("south"))

            val northCounter = Quilter(
                seamNorth, initialPool(), BoundedCounter.serializer(), backgroundScope, config = replicatorCfg,
            )
            val southCounter = Quilter(
                seamSouth, initialPool(), BoundedCounter.serializer(), backgroundScope, config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // North sells its 5-seat quota immediately — now empty.
            val firstBatch = northCounter.state.value.trySpend(north, 5L)
            assertNotNull(firstBatch)
            northCounter.apply(firstBatch)

            // South has sold nothing; it transfers 3 of its 5 quota to north.
            val transferPatch = southCounter.state.value.transfer(from = south, to = north, amount = 3L)
            assertNotNull(transferPatch, "South should have quota to transfer")
            southCounter.apply(transferPatch)

            delay(10) // let both deltas propagate

            // After convergence: north has 3 quota from the transfer.
            val anotherBatch = northCounter.state.value.trySpend(north, 3L)
            assertNotNull(anotherBatch, "North should now hold 3 transferred quota")
            northCounter.apply(anotherBatch)

            delay(10)

            // Total sold = 5 (original north) + 3 (after transfer) = 8; budget = 2 (south's residual 2).
            assertEquals(8L, northCounter.state.value.totalSpent)
            assertEquals(2L, northCounter.state.value.totalBudget)
            assertEquals(northCounter.state.value.totalSpent, southCounter.state.value.totalSpent)
        }
}
