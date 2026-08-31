@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nearby

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * What a **freshly woven** seam starts life believing, when its [NearbyLoom] has woven before
 * (#1878).
 *
 * A weave's roster is that weave's own business: a seam that has connected to nobody must report
 * `peers == { selfId }` and [SeamState.Weaving], no matter how many sessions the loom hosted
 * earlier. That obligation has never had a home. [us.tractat.kuilt.conformance.SeamConformanceSuite]
 * enumerates "the seams a `Loom` produces" **one weave at a time**, so *sequential* weaves on one
 * loom fall outside it entirely — which is why a seam that reports a phantom roster and false-latches
 * `Woven` passes every conformance obligation today.
 *
 * The residue is what makes it observable. Every weave adds its own id to the loom's roster and
 * nothing ever takes one out: `close()` deliberately does not write the loom-wide flow (that write
 * was #1850, a peer reaching across and editing its counterparty's membership), and `disconnectLoop`
 * — the only remaining eviction — is cancelled by the very tear that would need it to run. So a
 * closed weave's id outlives it, and the next weave inherits a roster with a stranger in it.
 */
class NearbyLoomRosterLifecycleTest {

    /**
     * The advertiser path is the one that can be driven alone: [NearbyLoom.host] starts advertising
     * and returns its seam immediately, and [FakeNearbyRadio] connects nobody until a discoverer
     * arrives. So the second seam here genuinely has zero connections, and every peer in its roster
     * is residue.
     */
    @Test
    fun aSeamWovenAfterAnEarlierSeamClosedStartsAloneAndWeaving() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = NearbyLoom(FakeNearbyApi(FakeNearbyRadio()))

            val first = loom.host(Pattern("device"))
            val firstId = first.selfId
            first.close()
            assertIs<SeamState.Torn>(first.state.value, "precondition: the first weave really is over")

            val second = loom.host(Pattern("device"))

            assertAll(
                {
                    assertFalse(
                        firstId in second.peers.value,
                        "a closed weave's id must not survive into the next weave's roster — nothing " +
                            "prunes the loom-wide flow, so `close()` leaves ${firstId.value} in it forever",
                    )
                },
                {
                    assertEquals(
                        setOf(second.selfId),
                        second.peers.value,
                        "a seam that has connected to nobody advertises exactly its own id (Seam.peers)",
                    )
                },
                {
                    assertIs<SeamState.Weaving>(
                        second.state.value,
                        "a seam with zero connections is Weaving — a phantom roster carrying a " +
                            "non-self id makes the roster watcher latch Woven at construction, and a " +
                            "consumer awaiting `state.first { it is Woven }` then proceeds to send " +
                            "over a seam with no endpoints",
                    )
                },
            )

            second.close()
        }

    /**
     * The same obligation with the earlier weave still **open** — the residue does not need a tear to
     * accumulate, it only needs a previous weave.
     *
     * Worth pinning separately because the two have different fixes available: the closed case could
     * be papered over by evicting a torn seam's id, and this one could not. Both are answered by a
     * roster that belongs to the weave rather than the loom.
     */
    @Test
    fun aSecondConcurrentWeaveDoesNotInheritTheFirstWeavesRoster() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = NearbyLoom(FakeNearbyApi(FakeNearbyRadio()))

            val first = loom.host(Pattern("device"))
            val second = loom.host(Pattern("device"))

            assertAll(
                {
                    assertEquals(
                        setOf(second.selfId),
                        second.peers.value,
                        "two weaves on one loom are two separate sessions — neither is a peer of the other",
                    )
                },
                {
                    assertIs<SeamState.Weaving>(
                        second.state.value,
                        "…and a weave that has connected to nobody is still Weaving",
                    )
                },
            )

            first.close()
            second.close()
        }
}
