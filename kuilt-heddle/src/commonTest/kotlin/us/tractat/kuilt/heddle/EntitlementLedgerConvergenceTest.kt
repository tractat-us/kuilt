package us.tractat.kuilt.heddle

import us.tractat.kuilt.conformance.lattice.LatticeLawHarness
import us.tractat.kuilt.conformance.lattice.LatticeLawSuite
import us.tractat.kuilt.conformance.lattice.LatticeOp
import us.tractat.kuilt.conformance.lattice.OpKind
import us.tractat.kuilt.conformance.lattice.VacuityFloors
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.random.Random

/** Ids that share one `String.hashCode`, plus one that does not. See the binding's KDoc. */
private val IDS: List<String> = listOf("Aa", "BB", "C#", "d")

private fun Random.edge(): AttachmentId = AttachmentId(IDS.random(this))

private fun Random.path(): PathKey = PathKey.of(edge())

/** A fresh slot value: max-joined, so drawing from a wide range keeps nearly every write effective. */
private fun Random.counter(replicaIndex: Int): GCounter =
    GCounter.of(ReplicaId("R$replicaIndex") to nextLong(1L, 1L shl 40))

/**
 * [EntitlementLedger] under the lattice and byte laws (#2715), which it had neither of: it rides a
 * `Quilter`, whose anti-entropy root is a hash over this encoding.
 *
 * ## What the knobs switch off
 *
 * - **[IDS].** The byte law can only fail where a map has more than one iteration order. `"Aa"`,
 *   `"BB"` and `"C#"` collide into one JVM `HashMap` bin, which keeps insertion order, so the fold
 *   order shows through on the JVM as well. Ordinary ids would leave this suite blind there.
 * - **`record`'s child.** Drawn per replica, so one edge id collects several records and the inner
 *   `Set` has an order to get wrong. A single child per edge would leave that set a singleton on
 *   every reachable state.
 * - **`transfer`'s donor.** Written per replica, so a path holds several donor rows and the inner map
 *   of each matrix is reached.
 *
 * These ops write slots straight through the internal factory instead of the economic mutators.
 * The join is what is under test, and it is a product of lattices over every one of these fields
 * whatever the economics would allow, so routing through `delegate` and `spend` would only narrow
 * which states the pool can reach.
 */
internal class EntitlementLedgerConvergenceTest : LatticeLawSuite<EntitlementLedger>() {
    override fun newHarness(): LatticeLawHarness<EntitlementLedger> = LatticeLawHarness(
        initial = EntitlementLedger.ZERO,
        alphabet = listOf(
            LatticeOp("record", OpKind.ASSERT) { state, replicaIndex, random ->
                val edge = random.edge()
                val record = AttachmentRecord(edge, GroupId("root"), GroupId("c$replicaIndex"), Weight.of(1L))
                state.piece(EntitlementLedger.of(records = mapOf(edge to setOf(record))))
            },
            LatticeOp("mint", OpKind.ASSERT) { state, replicaIndex, random ->
                val record = MintRecord(GroupId("root"), ReplicaId("R$replicaIndex"), random.nextLong(1L, 1L shl 40))
                state.piece(EntitlementLedger.of(minted = mapOf(MintId(IDS.random(random)) to record)))
            },
            LatticeOp("counter", OpKind.ASSERT) { state, replicaIndex, random ->
                val cell = mapOf(random.edge() to random.counter(replicaIndex))
                state.piece(
                    when (random.nextInt(9)) {
                        0 -> EntitlementLedger.of(issued = cell)
                        1 -> EntitlementLedger.of(returned = cell)
                        2 -> EntitlementLedger.of(leafSpent = cell)
                        3 -> EntitlementLedger.of(rollupSpent = cell)
                        4 -> EntitlementLedger.of(issuedRelocIn = cell)
                        5 -> EntitlementLedger.of(leafRelocIn = cell)
                        6 -> EntitlementLedger.of(leafRelocOut = cell)
                        7 -> EntitlementLedger.of(rollupRelocIn = cell)
                        else -> EntitlementLedger.of(rollupRelocOut = cell)
                    },
                )
            },
            LatticeOp("transfer", OpKind.ASSERT) { state, replicaIndex, random ->
                val row = mapOf(random.path() to mapOf(ReplicaId(IDS[replicaIndex]) to random.counter(replicaIndex)))
                state.piece(
                    when (random.nextInt(3)) {
                        0 -> EntitlementLedger.of(transfers = row)
                        1 -> EntitlementLedger.of(transferRelocIn = row)
                        else -> EntitlementLedger.of(transferRelocOut = row)
                    },
                )
            },
            LatticeOp("lifecycle", OpKind.ASSERT) { state, _, random ->
                val stage = Lifecycle.entries.random(random)
                state.piece(EntitlementLedger.of(lifecycle = mapOf(random.edge() to stage)))
            },
            LatticeOp("gauge", OpKind.ASSERT) { state, _, random ->
                val gauge = Gauge(Rational.of(random.nextLong(1L, 1L shl 20)), random.nextLong(1L, 1L shl 20))
                state.piece(EntitlementLedger.of(gauges = mapOf(random.edge() to gauge)))
            },
        ),
        // No RETIRE op: every field joins upward, and a lifecycle's RETIRED marks an edge rather than
        // taking any stored value back. See `LatticeOp.kt` for the definition this answers to.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = EntitlementLedger.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
