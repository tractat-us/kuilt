package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** The replica [bootFrom] credits the root supply to. */
internal val BOOT_HOLDER: ReplicaId = ReplicaId("boot")

/** The root supply [bootFrom] mints, enough for a test to delegate and spend. */
internal const val BOOT_SUPPLY: Long = 1_000L

/**
 * A ledger that holds [t] lowered at [generation]: [BOOT_SUPPLY] minted to [BOOT_HOLDER] at the
 * root, then every edge prepared, then every edge activated, each pass from the top down.
 *
 * [Topology.lower] sorts by id, and `/` sorts before `@`, so its order puts a child ahead of its
 * parent. `prepare` and `activate` do not look at the parent, so either order would build the same
 * ledger; this one sorts by depth anyway, because it is the reference a diff is compared against,
 * and a diff must prepare from the top down.
 *
 * Each mutator must accept, so a refusal fails here rather than leaving a half-built tree.
 */
internal fun bootFrom(t: Topology, generation: Long = 0L): EntitlementLedger {
    var ledger = EntitlementLedger.bootstrap(t.groupId(), mapOf(BOOT_HOLDER to BOOT_SUPPLY), "boot")
    // An escaped segment never contains `/`, so counting them is the depth.
    val records = t.lower(generation).sortedWith(compareBy({ r -> r.child.value.count { it == '/' } }, { it.id }))
    for (record in records) {
        ledger = ledger.piece(checkNotNull(ledger.prepare(record)) { "prepare refused ${record.id.value}" })
    }
    for (record in records) {
        ledger = ledger.piece(checkNotNull(ledger.activate(record.id)) { "activate refused ${record.id.value}" })
    }
    return ledger
}

/** One active edge with its id left out, so two ledgers at different generations compare equal. */
internal data class ActiveTriple(val parent: GroupId, val child: GroupId, val weight: Weight)

/**
 * Every [Lifecycle.ACTIVE] edge as (parent, child, weight), sorted by child, then parent. Throws on
 * a divergent active edge rather than dropping it, so it cannot vanish from both sides of a
 * comparison.
 */
internal fun EntitlementLedger.activeTriples(): List<ActiveTriple> =
    allEdges()
        .filter { lifecycle(it) == Lifecycle.ACTIVE }
        .map { checkNotNull(record(it)) { "divergent active edge ${it.value}" } }
        .map { ActiveTriple(it.parent, it.child, it.weight) }
        .sortedWith(compareBy<ActiveTriple> { it.child }.thenBy { it.parent })
