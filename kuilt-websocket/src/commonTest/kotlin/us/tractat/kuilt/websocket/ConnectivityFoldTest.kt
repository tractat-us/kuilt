package us.tractat.kuilt.websocket

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The [NetworkReachability] → [FabricAvailability] fold (#1725) — the half of the live-capability
 * lane that is pure, and therefore the half a test can pin exactly.
 */
class ConnectivityFoldTest {

    @Test
    fun `each reading folds to its own availability`() {
        assertAll(
            {
                assertEquals(
                    FabricAvailability.Available,
                    NetworkReachability.Reachable.toAvailability(),
                    "a platform that reports a usable path is the one case that earns Available",
                )
            },
            {
                assertIs<FabricAvailability.Unavailable>(
                    NetworkReachability.Unreachable.toAvailability(),
                    "a platform that reports no usable path is a definite Unavailable, not a shrug",
                )
            },
            {
                assertIs<FabricAvailability.Unknown>(
                    NetworkReachability.Indeterminate.toAvailability(),
                    "a signal that cannot confirm reachability must not be laundered into Available",
                )
            },
            {
                assertIs<FabricAvailability.Unknown>(
                    null.toAvailability(),
                    "no observer wired at all is the honest Unknown floor (#1712)",
                )
            },
        )
    }

    /**
     * The two `Unknown` arms must stay **distinguishable**, and not for tidiness: the seam's
     * capability view is a scope-free derived `StateFlow`, which is only a legitimate `StateFlow`
     * while the mapping is injective. Collapse these two onto one value and a transition between
     * them is conflated away — a consumer watching "did the browser start shrugging at me, or was
     * nothing ever watching?" would see nothing happen.
     */
    @Test
    fun `the two cannot-tell readings stay distinguishable`() {
        val indeterminate = NetworkReachability.Indeterminate.toAvailability()
        val unobserved = null.toAvailability()

        assertNotEquals(
            unobserved,
            indeterminate,
            "an observer that shrugged and no observer at all are different facts, so they cannot " +
                "share a reason string — conflation would erase the transition between them",
        )
    }

    /**
     * Every distinct reading folds to a distinct availability. Stated over the whole input set
     * rather than pairwise so a *fifth* [NetworkReachability] added later is covered without anyone
     * remembering to extend this test — the injectivity the derived `StateFlow` rests on is a
     * property of the enum, not of the four values that happen to exist today.
     */
    @Test
    fun `the fold is injective over every reading`() {
        val readings: List<NetworkReachability?> = NetworkReachability.entries + null
        val folded = readings.map { it.toAvailability() }

        assertEquals(
            readings.size,
            folded.toSet().size,
            "distinct readings must fold to distinct availabilities, got $folded",
        )
    }

    /** No reason string may be blank — a consumer surfaces this text to a human. */
    @Test
    fun `every non-Available reading explains itself`() {
        val reasons = (NetworkReachability.entries + null)
            .map { it.toAvailability() }
            .mapNotNull {
                when (it) {
                    is FabricAvailability.Unavailable -> it.reason
                    is FabricAvailability.Unknown -> it.reason
                    FabricAvailability.Available -> null
                }
            }

        assertTrue(reasons.isNotEmpty(), "the rig is non-vacuous: some readings are not Available")
        assertAll(*reasons.map { reason -> { assertTrue(reason.isNotBlank(), "blank reason: '$reason'") } }.toTypedArray())
    }
}
