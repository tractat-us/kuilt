package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.conformance.samples.identityInjectionHarness
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Controls for the shared obligation runner; these do not prove a fabric refused an identity. */
class IdentityObligationRigTest {
    @Test
    fun sampleDeclarationsAgreeWithTheirHooks() = runTest {
        val sample = identityInjectionHarness()
        sample.runIdentityDeclaration(this, sample.duplicatePeerIdDeclaration(), sample::injectDuplicatePeerId)
        sample.runIdentityDeclaration(this, sample.blankPeerIdDeclaration(), sample::injectBlankPeerId)
    }

    @Test
    fun provenCannotSkipInjection() = runTest {
        val harness = harness()
        val failure = assertFailsWith<AssertionError> {
            harness.runIdentityObligation(this, ObligationDeclaration.Proven) { _, _ -> false }
        }
        assertTrue(failure.message.orEmpty().contains("identity injection must actually fire"))
    }

    @Test
    fun incumbentDeliveryLossRedsOnAnAssertion() = runTest {
        val harness = harness(dropJoinerBroadcast = true)
        val failure = assertFailsWith<AssertionError> {
            harness.runIdentityObligation(this, ObligationDeclaration.Proven) { _, _ -> true }
        }
        assertTrue(failure.message.orEmpty().contains("incumbent delivery"))
    }

    @Test
    fun intactPairPassesTheSharedRunner() = runTest {
        harness().runIdentityObligation(this, ObligationDeclaration.Proven) { _, _ -> true }
    }

    @Test
    fun declarationsCannotHideAnInjectableEvent() = runTest {
        for (declaration in listOf(
            ObligationDeclaration.Gap(CapabilityGaps.DUPLICATE_PEER_ID),
            ObligationDeclaration.NotApplicable.NotConstructible("no device handle"),
        )) {
            assertFailsWith<AssertionError> {
                harness().runIdentityDeclaration(this, declaration) { _, _ -> true }
            }
        }
    }

    @Test
    fun unprovenDeclarationsRequireTheirExplanation() = runTest {
        for (declaration in listOf(
            ObligationDeclaration.Gap(""),
            ObligationDeclaration.NotApplicable.NotConstructible(""),
        )) {
            assertFailsWith<AssertionError> {
                harness().runIdentityDeclaration(this, declaration) { _, _ -> false }
            }
        }
    }

    @Test
    fun provenDeclarationRequiresInjection() = runTest {
        assertFailsWith<AssertionError> {
            harness().runIdentityDeclaration(this, ObligationDeclaration.Proven) { _, _ -> false }
        }
    }

    @Test
    fun contractDiffersMustDemonstrateADeviation() = runTest {
        assertFailsWith<AssertionError> {
            harness().runIdentityDeclaration(this,
                ObligationDeclaration.NotApplicable.ContractDiffers("deliberately different")) { _, _ -> true }
        }
    }

    private fun harness(dropJoinerBroadcast: Boolean = false): SeamConformanceSuite =
        object : SeamConformanceSuite() {
            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
            override fun capabilityGaps(): Map<String, String> = emptyMap()
            override fun joinerRosterOrigin(): JoinerRosterOrigin =
                JoinerRosterOrigin.FilledByConstruction("shared in-memory roster in this control")

            override fun newLoomPair(): Pair<Loom, Loom> {
                val loom = InMemoryLoom()
                val joiner = object : Loom {
                    override suspend fun weave(rendezvous: Rendezvous): Seam {
                        val seam = loom.weave(rendezvous)
                        if (!dropJoinerBroadcast) return seam
                        return object : Seam by seam {
                            override suspend fun broadcast(payload: ByteArray) = Unit
                        }
                    }
                }
                return loom to joiner
            }
        }
}
