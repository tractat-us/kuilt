@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [Loom.capability]'s interface default is the floor for a loom that has established **nothing**,
 * and it must not be a confident verdict.
 *
 * `Seam.capability`'s default is a roleless [FabricAvailability.Unknown]
 * (`internal.StaticUnknownCapability`, #1712 Track A) because a fabric with no live path observer
 * asserting "the network is fine" becomes an authoritative false negative the moment a consumer
 * surfaces it. `Loom.capability()` used to default to the exact inverse — a confident roleless
 * [FabricAvailability.Available] — on the surface whose *entire* purpose (#1530) is to be trusted
 * for pre-connect user guidance. Two halves of one contract, defaulting in opposite directions
 * (#1746).
 *
 * The default is a floor, not a verdict: a loom that has genuinely established its answer says so
 * by overriding, and [InMemoryLoom] is the in-tree case where [FabricAvailability.Available] is a
 * static fact rather than a guess.
 */
class LoomCapabilityDefaultTest {

    /** A loom that overrides nothing but [Loom.weave] — i.e. one that has audited nothing. */
    private class UnauditedLoom : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            error("this loom is never woven — the test reads capability() only")
    }

    @Test
    fun aLoomThatEstablishesNothingReportsUnknownRatherThanAvailable() {
        val unknown = assertIs<FabricAvailability.Unknown>(
            UnauditedLoom().availability(),
            "a loom that has probed nothing must say 'I cannot tell', never a confident Available",
        )
        assertEquals(
            "this loom does not check whether the fabric is attemptable on this runtime",
            unknown.reason,
            "the floor's reason names what was not established, so a consumer can surface it",
        )
    }

    @Test
    fun theDefaultCarriesNoRoles() {
        assertEquals(
            emptySet(),
            UnauditedLoom().capability().roles,
            "a loom that has declared nothing claims no transport role either",
        )
    }

    /**
     * The regression pin for the audited half of #1746: flipping the interface default without
     * declaring [InMemoryLoom]'s verdict explicitly would silently demote a loom whose
     * [FabricAvailability.Available] is a static fact — it acquires no OS resource, needs no
     * permission and reaches no remote, so it really is usable.
     */
    @Test
    fun inMemoryLoomStaysAvailableBecauseItsVerdictIsAStaticFact() {
        assertEquals(FabricAvailability.Available, InMemoryLoom().availability())
    }

    /**
     * The same regression pin for the other in-tree loom whose `Available` is established by
     * construction: an **accept-side** loom has no remote to reach and acquires no OS resource at
     * weave time — it hands out room hubs over a [us.tractat.kuilt.core.fabric.ConnectionSource]
     * the caller already supplied. (`KtorServerLoom` in `:kuilt-websocket` is the same shape and
     * already declares `Available` explicitly.)
     */
    @Test
    fun muxServerLoomStaysAvailableBecauseAnAcceptSideLoomReachesNoRemote() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = MuxServerLoom(
                source = InMemoryConnectionSource(),
                scope = backgroundScope,
                selfId = PeerId("server"),
                authorizer = RoomAuthorizer.AllowAll,
                dispatcher = coroutineContext[ContinuationInterceptor]!!,
                random = Random(0L),
            )

            assertEquals(FabricAvailability.Available, loom.availability())

            loom.close()
        }
}
