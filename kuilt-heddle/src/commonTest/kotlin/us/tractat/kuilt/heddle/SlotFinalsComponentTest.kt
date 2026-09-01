package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One field of [SlotFinals], named exactly as the constructor parameter is, paired with a
 * `SlotFinals` in which **only** that field is set to a non-default value.
 *
 * See [SLOT_FINALS_COMPONENTS] for why this exists.
 */
internal class SlotFinalsComponent(val name: String, val finals: SlotFinals) {
    /** This entry's fields, by name — the map [SlotFinalsComponentTest]'s rig reads. */
    fun values(): Map<String, Any> = valuesOf(finals)

    companion object {
        fun valuesOf(f: SlotFinals): Map<String, Any> = mapOf(
            "issued" to f.issued,
            "returned" to f.returned,
            "leafSpent" to f.leafSpent,
            "rollupSpent" to f.rollupSpent,
            "transfers" to f.transfers,
        )
    }
}

/**
 * **The** enumeration of [SlotFinals]'s fields — the sibling of `LEDGER_COMPONENTS`, and the hole
 * this PR itself opened.
 *
 * [SlotFinals.join] is **hand-written**, and every field it names is optional at the call site: the
 * class's own `transfers` carries a default, so a `join` that simply forgot to name it would
 * compile, run, and silently return `emptyMap()` for it. That is the same silent-loss shape as a
 * component missing from `EntitlementLedger.equals` (#2366) one method over, and it is *worse* in
 * one respect — a dropped ack final is not merely un-replicated, it is a promise the fence recorded
 * and then quietly lowered, which the join contract explicitly forbids ("a re-ack never lowers a
 * final").
 *
 * `equals`/`hashCode`/`toString` are compiler-generated here (it is a `data class`), so they cannot
 * drift and are not walked. `join` is the whole exposure.
 *
 * Adding a constructor parameter without adding its entry here is caught rather than trusted:
 * `EntitlementLedgerComponentCoverageTest` (jvmTest) derives the declared field set by reflection
 * and asserts it equals the names below.
 */
internal val SLOT_FINALS_COMPONENTS: List<SlotFinalsComponent> = listOf(
    SlotFinalsComponent("issued", SlotFinals(issued = 1L, returned = 0L, leafSpent = 0L, rollupSpent = 0L)),
    SlotFinalsComponent("returned", SlotFinals(issued = 0L, returned = 2L, leafSpent = 0L, rollupSpent = 0L)),
    SlotFinalsComponent("leafSpent", SlotFinals(issued = 0L, returned = 0L, leafSpent = 3L, rollupSpent = 0L)),
    SlotFinalsComponent("rollupSpent", SlotFinals(issued = 0L, returned = 0L, leafSpent = 0L, rollupSpent = 4L)),
    SlotFinalsComponent(
        "transfers",
        SlotFinals(
            issued = 0L,
            returned = 0L,
            leafSpent = 0L,
            rollupSpent = 0L,
            transfers = mapOf(ReplicaId("slot-finals-recipient") to 5L),
        ),
    ),
)

/** Every field of [SlotFinals] must survive its hand-written [SlotFinals.join]. */
class SlotFinalsComponentTest {

    /**
     * The headline property: joining a single-field `SlotFinals` with [SlotFinals.ZERO] must
     * reproduce it, in **both** argument positions. A field the join forgets to name defaults
     * instead — silently lowering a recorded final, which is exactly what the join contract forbids.
     */
    @Test
    fun everyFieldSurvivesTheJoin() {
        for (c in SLOT_FINALS_COMPONENTS) {
            assertEquals(
                c.finals,
                SlotFinals.ZERO.join(c.finals),
                "`${c.name}` does not survive SlotFinals.join — the field is optional at the " +
                    "constructor, so a join that omits it compiles and silently lowers a recorded " +
                    "ack final to its default",
            )
            assertEquals(
                c.finals,
                c.finals.join(SlotFinals.ZERO),
                "`${c.name}` is dropped when it is on the RECEIVER side of SlotFinals.join",
            )
        }
    }

    /**
     * The join is a per-slot **max**, not a pick-a-side: a re-ack may only ever raise a final. Held
     * per field, because a hand-written join can name a field and still take the wrong one of the
     * two values — a defect [everyFieldSurvivesTheJoin] cannot see, since one side is always ZERO
     * there and max and either-side agree on that input.
     */
    @Test
    fun theJoinTakesThePerFieldMaximum() {
        val lower = SlotFinals(issued = 1L, returned = 1L, leafSpent = 1L, rollupSpent = 1L, transfers = mapOf(R to 1L))
        val higher = SlotFinals(issued = 9L, returned = 9L, leafSpent = 9L, rollupSpent = 9L, transfers = mapOf(R to 9L))
        assertEquals(higher, lower.join(higher), "join must take the higher final, whichever side it is on")
        assertEquals(higher, higher.join(lower), "…and symmetrically — a re-ack may never lower a final")
    }

    /**
     * The rig for the walk above: each entry really does set exactly one field, so a red is
     * attributable to the named one. Read off the fields directly — `SlotFinals` is `internal`, so
     * this file can see them, and the reflection guard in jvmTest is what keeps the accessor map
     * from drifting away from the declaration.
     */
    @Test
    fun eachEntrySetsExactlyOneField() {
        val zero = SlotFinalsComponent.valuesOf(SlotFinals.ZERO)
        for (c in SLOT_FINALS_COMPONENTS) {
            val differing = c.values().filter { (name, v) -> v != zero.getValue(name) }.keys
            assertEquals(
                setOf(c.name),
                differing,
                "rig: `${c.name}`'s entry must set exactly one field and it must be that one — " +
                    "it differs from ZERO in $differing",
            )
        }
    }

    private companion object {
        val R = ReplicaId("slot-finals-recipient")
    }
}
