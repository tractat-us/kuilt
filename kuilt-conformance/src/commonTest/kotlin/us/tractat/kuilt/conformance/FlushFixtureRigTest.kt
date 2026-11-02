package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.fabric.HoldableConnection
import us.tractat.kuilt.test.fabric.connectionPair
import us.tractat.kuilt.test.fabric.identifiedLoomPair
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The **positive control** for [FlushFixture]: proof that its properties can go red, on the failure
 * this contract exists to prevent, which is a seam claiming a drain nobody checked.
 *
 * Every lie is paired with a control on the same harness with the lie removed, so a rig that failed
 * for some other reason cannot pass for a demonstration. It drives the `internal` bodies inside one
 * `runTest`, for the reason [ObligationDeclarationRigTest] gives, and its harnesses are anonymous for
 * the same reason.
 */
class FlushFixtureRigTest {

    /** The harness says the fabric cannot confirm a drain; the fabric confirms one anyway. */
    @Test
    fun aSeamClaimingADrainItsHarnessCallsUnsupportedIsRefused(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val failure = assertFailsWith<AssertionError> {
            harness(pair(InMemoryLoom()), FlushFixture.Unsupported(CapabilityGaps.FLUSH)).runFlushDeclarationIsHonest(this)
        }
        assertTrue("declares FlushFixture.Unsupported" in failure.message.orEmpty(), "red on the arm: ${failure.message}")
    }

    /** The harness says the fabric confirms a drain; the fabric cannot tell. */
    @Test
    fun aSeamThatCannotTellUnderAnArmClaimingItCanIsRefused(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val failure = assertFailsWith<AssertionError> {
            harness(pair(UnawareLoom(InMemoryLoom())), FlushFixture.CannotHold("a reason")).runFlushDeclarationIsHonest(this)
        }
        assertTrue("confirms a drain" in failure.message.orEmpty(), "red on the arm: ${failure.message}")
    }

    /** Control for both rows above: the same two fabrics, under the arm that describes them. */
    @Test
    fun eachFabricUnderTheArmThatDescribesItPasses(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        harness(pair(InMemoryLoom()), FlushFixture.CannotHold("a reason")).runFlushDeclarationIsHonest(this)
        harness(pair(UnawareLoom(InMemoryLoom())), FlushFixture.Unsupported(CapabilityGaps.FLUSH))
            .runFlushDeclarationIsHonest(this)
    }

    /** The role-split arm is a claim about each end, so a harness whose host confirms a drain is refused. */
    @Test
    fun hostUnsupportedOnAHostThatConfirmsIsRefused(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val failure = assertFailsWith<AssertionError> {
            harness(pair(InMemoryLoom()), FlushFixture.HostUnsupported(CapabilityGaps.FLUSH)).runFlushDeclarationIsHonest(this)
        }
        assertTrue("HostUnsupported" in failure.message.orEmpty(), "red on the arm: ${failure.message}")
    }

    /** The lie the reference cannot show: a seam answering `Drained` with a send still stuck. */
    @Test
    fun aSeamThatClaimsADrainWithoutWaitingIsCaught(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val failure = assertFailsWith<AssertionError> { holdableHarness(lying = true).runFlushWaitsForAHeldSend(this) }
        assertTrue("flush returned while the frame was still held" in failure.message.orEmpty(), "got: ${failure.message}")
    }

    /** Control for the row above: the same held link, with an honest seam. */
    @Test
    fun anHonestSeamOverTheSameHeldLinkPasses(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        holdableHarness(lying = false).runFlushWaitsForAHeldSend(this)
    }

    private fun pair(loom: Loom): () -> Pair<Loom, Loom> = { loom to loom }

    private fun harness(looms: () -> Pair<Loom, Loom>, fixture: FlushFixture): SeamConformanceSuite =
        object : SeamConformanceSuite() {
            override fun newLoomPair(): Pair<Loom, Loom> = looms()
            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
            override fun capabilityGaps(): Map<String, String> = emptyMap()
            override fun joinerRosterOrigin(): JoinerRosterOrigin =
                JoinerRosterOrigin.FilledByConstruction("a rig: no property here reads the roster's origin")
            override fun flushFixture(): FlushFixture = fixture
        }

    private fun holdableHarness(lying: Boolean): SeamConformanceSuite =
        object : SeamConformanceSuite() {
            private var link: Pair<HoldableConnection, HoldableConnection>? = null

            override fun newLoomPair(): Pair<Loom, Loom> {
                val (hostEnd, joinerEnd) = connectionPair()
                val held = HoldableConnection(hostEnd) to HoldableConnection(joinerEnd)
                link = held
                val (hostLoom, joinerLoom) = identifiedLoomPair(held)
                return if (lying) LyingLoom(hostLoom) to LyingLoom(joinerLoom) else hostLoom to joinerLoom
            }

            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
            override fun capabilityGaps(): Map<String, String> = emptyMap()
            override fun joinerRosterOrigin(): JoinerRosterOrigin =
                JoinerRosterOrigin.FilledByConstruction("a rig: no property here reads the roster's origin")

            override fun flushFixture(): FlushFixture {
                val (hostEnd, joinerEnd) = requireNotNull(link) { "read after the pair is woven" }
                return FlushFixture.Holdable(hostEnd, joinerEnd)
            }
        }

    /** Every seam it weaves answers `Drained`, whatever is queued. */
    private class LyingLoom(private val inner: Loom) : Loom {
        override fun capability(): TransportCapability = inner.capability()
        override suspend fun weave(rendezvous: Rendezvous): Seam = LyingSeam(inner.weave(rendezvous))
    }

    private class LyingSeam(private val inner: Seam) : Seam by inner {
        override suspend fun flush(timeout: Duration): FlushOutcome = FlushOutcome.Drained
    }

    /** Every seam it weaves was written before `flush` existed, so it inherits the default. */
    private class UnawareLoom(private val inner: Loom) : Loom {
        override fun capability(): TransportCapability = inner.capability()
        override suspend fun weave(rendezvous: Rendezvous): Seam = UnawareSeam(inner.weave(rendezvous))
    }

    private class UnawareSeam(private val inner: Seam) : Seam {
        override val selfId get() = inner.selfId
        override val peers get() = inner.peers
        override val state get() = inner.state
        override val incoming get() = inner.incoming
        override suspend fun broadcast(payload: ByteArray) = inner.broadcast(payload)
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = inner.sendTo(peer, payload)
        override suspend fun close(reason: CloseReason) = inner.close(reason)
    }
}
