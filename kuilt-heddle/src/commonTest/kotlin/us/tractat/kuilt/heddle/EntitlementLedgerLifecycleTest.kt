package us.tractat.kuilt.heddle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * H2 lifecycle lattice + strict reconfiguration (#1605).
 *
 * First-commit TDD stub: asserts the lifecycle chain orders PREPARED < ACTIVE <
 * CLOSING < RETIRED. Fails to compile until [Lifecycle] exists — the failing test
 * that claims the phase.
 */
class EntitlementLedgerLifecycleTest {

    @Test
    fun lifecycleChainOrdersByMonotonePromotion() {
        assertEquals(
            listOf(Lifecycle.PREPARED, Lifecycle.ACTIVE, Lifecycle.CLOSING, Lifecycle.RETIRED),
            Lifecycle.entries.sorted(),
        )
    }
}
