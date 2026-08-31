package us.tractat.kuilt.heddle

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guard that makes [LEDGER_COMPONENTS] **unforgettable**: the component set is derived from
 * [EntitlementLedger]'s declared fields rather than trusted to a reader, so adding a constructor
 * parameter without extending the list fails here immediately, naming it.
 *
 * Without this, the walk in `EntitlementLedgerComponentTest` proves only that the components
 * *someone listed* are observable through `equals` — which is precisely the assurance that was
 * already available, and already false, when `transferRelocIn`/`transferRelocOut` were added
 * without extending `equals`/`hashCode`/`toString` (#2366).
 *
 * JVM-only because reflection is: the property is a statement about the one shared `commonMain`
 * declaration, so proving it on a single target proves it everywhere.
 */
class EntitlementLedgerComponentCoverageTest {

    @Test
    fun everyDeclaredLatticeComponentIsWalkedByTheComponentSuite() {
        val declared = EntitlementLedger::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            declared,
            LEDGER_COMPONENTS.map { it.name }.toSortedSet(),
            "EntitlementLedger's declared lattice components and LEDGER_COMPONENTS have diverged. " +
                "A new component must be added to LEDGER_COMPONENTS *and* to equals/hashCode/toString " +
                "— a component `equals` cannot see is a component whose patches Quilter silently " +
                "discards (#2366)",
        )
    }
}
