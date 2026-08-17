@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `Seam.sendTo` refuses `sendTo(selfId)` with `IllegalArgumentException` (#2428). [ManagedSeam] is
 * the one in-tree decorator that must carry that itself for **two** independent reasons, and either
 * alone would be enough:
 *
 *  1. Its [ManagedSeam.selfId] is a *constructor parameter* — the client's stable id, deliberately
 *     unchanged across reconnects — while the backing seam it swaps in has an id of the fabric's
 *     own choosing. Delegating the check would test the caller's argument against the wrong identity.
 *  2. It wraps the backing send in `runCatchingCancellable { … }.onFailure { log.debug { … } }`, so
 *     even a perfectly conforming backing seam's refusal would be swallowed into a debug line and
 *     the caller told nothing.
 *
 * Both are pinned below, and the second is the one a `git grep` for `require(peer != selfId)` would
 * have marked "inherited, no change needed".
 */
class ManagedSeamSelfSendTest {

    @Test
    fun selfSendIsRefusedAgainstTheMANAGEDIdEvenThoughTheBackingSeamHasAnother() =
        runTest(UnconfinedTestDispatcher()) {
            val stableId = PeerId("stable-client")
            val managed = ManagedSeam(scope = backgroundScope, selfId = stableId)
            // Read BEFORE the swap: see the note on `peers` below.
            val selfIsInPeersInitially = stableId in managed.peers.value
            val backing = InMemoryLoom().host(Pattern("cluster"))
            managed.swap(backing)

            assertAll(
                {
                    assertNotEquals(
                        stableId,
                        backing.selfId,
                        "precondition: the managed id and the backing seam's id must DIFFER, or this " +
                            "test cannot tell an own-identity check from a delegated one",
                    )
                },
                {
                    // Sampled pre-swap deliberately. `swap` overwrites `_peers` with the BACKING
                    // seam's roster, which names the backing seam's id and not this one — so
                    // post-swap `peers` does not contain `selfId` at all, in breach of the
                    // `Seam.peers` invariant. That is a separate defect (reported alongside #2428),
                    // not this test's subject, and asserting the invariant where it currently holds
                    // keeps this test pinned to the self-send refusal rather than red for a reason
                    // it does not own.
                    assertTrue(
                        selfIsInPeersInitially,
                        "precondition: selfId is IN peers, which is what makes this refusal distinct " +
                            "from PeerNotConnected",
                    )
                },
            )

            assertFailsWith<IllegalArgumentException>(
                "sendTo(selfId) must be refused against the MANAGED id — the identity this " +
                    "seam publishes, and the only one a caller can address it by",
            ) {
                managed.sendTo(stableId, byteArrayOf(1))
            }
        }

    /**
     * The swallow arm, isolated: with **no** backing seam at all there is nothing to delegate to and
     * every send is dropped with a debug line. A self-send must still throw — otherwise the refusal
     * would be contingent on whether a reconnect happened to be in flight.
     */
    @Test
    fun selfSendIsRefusedBeforeTheFirstSwapWhereEveryOtherSendIsSilentlyDropped() =
        runTest(UnconfinedTestDispatcher()) {
            val stableId = PeerId("stable-client")
            val managed = ManagedSeam(scope = backgroundScope, selfId = stableId)

            // Control: with no backing seam an ordinary addressed send is dropped, not thrown.
            managed.sendTo(PeerId("some-server"), byteArrayOf(1))

            assertFailsWith<IllegalArgumentException>(
                "a self-send must be refused even where every other send is a silent drop",
            ) {
                managed.sendTo(stableId, byteArrayOf(2))
            }
        }
}
