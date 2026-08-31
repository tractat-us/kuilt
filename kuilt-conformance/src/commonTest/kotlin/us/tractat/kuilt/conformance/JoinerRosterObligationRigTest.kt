package us.tractat.kuilt.conformance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The **positive control** for #2591: proof that the two mechanisms it adds to
 * [SeamConformanceSuite] can actually go red, and that they go red on the thing they name.
 *
 * A conformance property that passes everywhere on the day it lands has proven nothing — a green
 * suite is otherwise indistinguishable from an assertion that cannot fire. Both halves are rigged
 * here, each against a harness broken in exactly one way, and each paired with a **control arm** on
 * the same harness with the break removed. The control arm is what separates "the assertion caught
 * the defect" from "this rig was red for some unrelated reason": without it, a rig that failed to
 * connect at all would look like a successful demonstration.
 *
 * Driving the `internal` obligation body ([SeamConformanceSuite.runPeersReportsSelfIdAndAtLeastTwo])
 * rather than the inherited `@Test` wrapper follows [SeamConformanceUngatedCoreTest]: it composes
 * inside one `runTest`, which is what wasmJs/JS needs (a bare nested `runTest` returns an un-awaited
 * Promise), and the harnesses are **anonymous** objects because a named concrete subclass would be
 * collected by the JUnit4 (Android) runner as its own test class.
 *
 * ## What this rig is, and what it is not
 *
 * [aJoinerBlindToItsPeerFailsTheObligation]'s harness reproduces the
 * shipped `:kuilt-nearby` **observable** — a joiner whose `peers` never leaves `{ selfId }` — by
 * decorating the reference joiner seam, not by breaking a join path. That is deliberate: the
 * assertion under test reads `joiner.peers` and nothing else, so a decorator is a faithful stimulus
 * and a hand-written fabric would only add surface that could itself be wrong. It does mean the rig
 * demonstrates the *assertion*, not the *fixture* half — which is what the second pair is for.
 */
class JoinerRosterObligationRigTest {

    // ── half 1: the symmetric assertion reds on a joiner that never learns its peer ──

    @Test
    fun aJoinerBlindToItsPeerFailsTheObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose peers never leaves { selfId } must FAIL the peers obligation — this is " +
                "the shipped :kuilt-nearby observable (#2591), and it was green here for as long as " +
                "the ≥2 assertion was written on the host alone",
        ) {
            blindJoinerHarness().runPeersReportsSelfIdAndAtLeastTwo(this)
        }

        val message = failure.message.orEmpty()
        assertAll(
            {
                // The SHAPE of the red, not merely its presence: a rig that reddened on the host arm,
                // or on the self-id arm, would tick a "did it go red?" box while proving nothing about
                // the assertion #2591 adds.
                assertTrue(
                    "JOINER must advertise at least one remote peer" in message,
                    "the red must come from the JOINER arm specifically; got: $message",
                )
            },
            {
                assertTrue(
                    "1 assertion(s) failed" in message,
                    "exactly ONE of the four arms may red — the host still sees the joiner, and both " +
                        "ends still contain their own selfId, so a broader red means the rig broke " +
                        "the pair rather than blinding the joiner; got: $message",
                )
            },
        )
    }

    @Test
    fun theSameHarnessWithASightedJoinerPasses(): TestResult = runTest {
        // Control arm: identical wiring, decorator removed. If this were also red the rig would be
        // measuring its own breakage rather than the defect.
        sightedJoinerHarness().runPeersReportsSelfIdAndAtLeastTwo(this)
    }

    // ── half 2: a fixture declaration that costs nothing is refused, on both arms ──

    @Test
    fun aBlankJoinPathMechanismIsRefused() {
        // `how` is the WHOLE of TheJoinPath's accountability — there is no sound machine refutation
        // (see joinerRosterOriginIsDeclaredAndHonest), so an empty string would buy back exactly the
        // silence JoinerRosterOrigin exists to remove.
        assertFailsWith<AssertionError>(
            "TheJoinPath must name the mechanism that admits the remote",
        ) {
            declaring(JoinerRosterOrigin.TheJoinPath(" ")).joinerRosterOriginIsDeclaredAndHonest()
        }
    }

    @Test
    fun anHonestJoinPathDeclarationPasses() {
        declaring(JoinerRosterOrigin.TheJoinPath("NearbySeam.admitRemote, from the joiner's own handshake"))
            .joinerRosterOriginIsDeclaredAndHonest()
    }

    @Test
    fun aBlankFilledByConstructionReasonIsRefused() {
        assertFailsWith<AssertionError>(
            "FilledByConstruction must cost a sentence — a blank reason tells a reader auditing a " +
                "green joiner assertion nothing",
        ) {
            declaring(JoinerRosterOrigin.FilledByConstruction("  ")).joinerRosterOriginIsDeclaredAndHonest()
        }
    }

    @Test
    fun anHonestFilledByConstructionDeclarationPasses() {
        // Control arm, and the shape every in-process harness in tree actually declares.
        declaring(JoinerRosterOrigin.FilledByConstruction("one InMemoryLoom registry, read by both ends"))
            .joinerRosterOriginIsDeclaredAndHonest()
    }

    // ── harnesses ────────────────────────────────────────────────────────────

    /**
     * The reference mesh with the joiner's roster forced to `{ selfId }`. Distinct `Loom` objects, so
     * the declaration below is about the *wrapper*; what matters to the obligation is the observable.
     */
    private fun blindJoinerHarness(): SeamConformanceSuite = harnessOver(BlindJoinerLoom(InMemoryLoom()))

    /** Identical, undecorated. */
    private fun sightedJoinerHarness(): SeamConformanceSuite = InMemoryLoom().let { harnessOver(it) }

    private fun harnessOver(loom: Loom): SeamConformanceSuite = object : SeamConformanceSuite() {
        override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
        override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
        override fun capabilityGaps(): Map<String, String> = emptyMap()
        override fun joinerRosterOrigin(): JoinerRosterOrigin =
            JoinerRosterOrigin.FilledByConstruction("a rig over one InMemoryLoom (#2591 positive control)")
    }

    private fun declaring(origin: JoinerRosterOrigin): SeamConformanceSuite =
        object : SeamConformanceSuite() {
            private val loom = InMemoryLoom()
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
            override fun capabilityGaps(): Map<String, String> = emptyMap()
            override fun joinerRosterOrigin(): JoinerRosterOrigin = origin
        }

    /**
     * Blinds only the **joiner**: `weave(Rendezvous.Existing)` hands back a seam whose `peers` is
     * pinned at `{ selfId }`. Implemented rather than delegated (`Loom by inner`) on purpose —
     * [Loom.host] and [Loom.join] are *default* members, so delegation would forward them to the
     * inner loom's own `weave` and route straight past this override.
     */
    private class BlindJoinerLoom(private val inner: Loom) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = inner.weave(rendezvous)
            return if (rendezvous is Rendezvous.Existing) SeamBlindToItsPeers(seam) else seam
        }

        override fun capability(): TransportCapability = inner.capability()
    }

    /**
     * The shipped `:kuilt-nearby` observable: a live, otherwise-working seam whose roster never names
     * anyone but itself. Everything else — `state`, `incoming`, `close` — is the real seam's, so the
     * pair still connects, still weaves and still tears down; only the roster lies.
     */
    private class SeamBlindToItsPeers(private val delegate: Seam) : Seam by delegate {
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(delegate.selfId))
    }

    /** Guards the rig's own premise: the decorator must blind the joiner and leave the host alone. */
    @Test
    fun theRigBlindsTheJoinerAndOnlyTheJoiner(): TestResult = runTest {
        val loom = BlindJoinerLoom(InMemoryLoom())
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("joiner"))
        try {
            assertAll(
                { assertEquals(setOf(joiner.selfId), joiner.peers.value, "the joiner must be blinded") },
                {
                    assertTrue(
                        joiner.selfId in host.peers.value,
                        "the HOST must still see the joiner — otherwise the obligation would red on " +
                            "the host arm too and the rig would be proving the wrong thing",
                    )
                },
            )
        } finally {
            host.close()
            joiner.close()
        }
    }
}
