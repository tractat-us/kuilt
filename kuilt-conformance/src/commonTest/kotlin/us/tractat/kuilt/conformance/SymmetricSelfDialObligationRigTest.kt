package us.tractat.kuilt.conformance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The **positive control** for #2601's last row: proof that the three joiner arms
 * [SeamConformanceSuite.selfDialIsRejected] gained can actually go red, and that each reds on the arm
 * it names rather than somewhere else in the same obligation.
 *
 * `Seam` is *one peer's symmetric view of a session*, but a role-split fabric ships two different
 * `Seam` implementations behind one harness — websocket hosts a `MeshSeam` and joins a `LinkSeam` —
 * so an obligation asserted on `host` alone proves at most half of what the harness under test ships,
 * and the other half is what a joining phone runs.
 *
 * ## Why this row landed alone
 *
 * The other fourteen rows the #2601 sweep found could be asserted on the joiner with no signature
 * change. This one could not: its stimulus is *injected*, and [SeamConformanceSuite.injectSelfDial]
 * took only a host — so a joiner arm written against the old hook would have asserted three
 * properties of a seam nothing had dialled. That is not a weak test, it is a green that means
 * nothing, and it is the exact failure mode #2601 names. The hook now takes both ends.
 *
 * ## The vacuity question this row answers differently
 *
 * `JoinerRosterOrigin` exists because a shared registry can *supply* a joiner's roster, and #2675
 * found a harness whose fixture even supplied the roster collapse a torn seam needs. **No fixture can
 * supply a self-dial**, so the analogous question here is not "did the fixture do it for us" but "did
 * the harness dial the end it was handed" — and that one has no observable answer, because a
 * correctly guarded self-dial leaves no trace at either end by construction. The suite says so at
 * [SeamConformanceSuite.injectSelfDial] rather than pretending otherwise. What this rig demonstrates
 * is therefore the *assertions*: given an end that really was dialled and really is broken, each arm
 * catches its own defect and no other.
 *
 * ## The shape, copied deliberately from [SymmetricRefusalObligationRigTest]
 *
 * Every row gets a broken harness that must red and a **control arm** with the break removed. The
 * control is what separates "the assertion caught the defect" from "this rig was red for some
 * unrelated reason". The broken end is always the **joiner**, and always by *decorating* the
 * reference seam ([DecoratingJoinerLoom]) rather than hand-writing a fabric. Each red is checked by
 * **message shape** and by **arm count** — a rig that reddened on a host arm would tick a box while
 * proving nothing.
 *
 * The control harness's [SeamConformanceSuite.injectSelfDial] returns `true` having done **nothing
 * observable**, which is not a cheat: that is precisely what a conforming fabric looks like from
 * outside — it was dialled, and its guard dropped the connection. A control whose hook returned
 * `false` would have early-returned the whole obligation and been green by absence.
 */
class SymmetricSelfDialObligationRigTest {

    // ── (1) the live self-loopback — shape (b) of a broken guard ─────────────

    /**
     * Self is registered AND its link stays live, so the joiner's own broadcast comes back stamped
     * `sender == selfId`. A healthy seam never loops a peer's own broadcast to itself, and a consumer
     * that keys on `sender` sees a phantom second participant on the joining device.
     */
    @Test
    fun aJoinerThatLoopsItsOwnBroadcastBackFailsTheSelfEchoArm(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose own broadcast returns to it attributed to its own selfId must FAIL — that " +
                "is a self-link the guard was supposed to drop",
        ) {
            brokenJoinerHarness(::SeamThatLoopsItsOwnBroadcastBack).runSelfDialIsRejected(this)
        }
        assertAll(
            { assertRedOn("must never loop back to it attributed to selfId either", failure) },
            { assertArmCount(1, failure) },
        )
    }

    // ── (2) the roster change — shape (a) of a broken guard ──────────────────

    /**
     * Self registered as a *remote*: the joiner's roster grows an entry for the self-link, which is
     * the literal thing "self never registered as a remote" forbids.
     *
     * **The direction is deliberate, and the literal #1466 direction cannot be used here.** #1466
     * collapses a roster by evicting *self* — and [SeamConformanceSuite.connectedPair]'s continuous
     * `selfId ∈ peers` monitor reds on that before this arm is ever reached, so a rig using it would
     * report the monitor's message and prove nothing about the arm under test. Growing the roster
     * instead leaves `selfId` in place, keeps the monitor quiet, and still violates the same property:
     * the roster moved because of a self-dial.
     */
    @Test
    fun aJoinerThatRegistersTheSelfLinkAsARemoteFailsTheRosterArm(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose roster gains an entry for its own self-link must FAIL",
        ) {
            brokenJoinerHarness(::SeamThatRegistersTheSelfLinkAsARemote).runSelfDialIsRejected(this)
        }
        assertAll(
            { assertRedOn("must not change the JOINER's peers either", failure) },
            { assertArmCount(1, failure) },
        )
    }

    // ── (3) the state re-flip — what backstops both shapes ───────────────────

    /**
     * The self-dial is treated as a real remote arriving, so the joiner re-drives its weave and
     * reports `Weaving` again. A consumer waiting on `state` sees a session that never settles.
     * `Weaving` rather than `Torn` on purpose: a torn joiner would also collapse its roster and red
     * two arms, which would blunt the count this rig asserts.
     */
    @Test
    fun aJoinerThatReWeavesOnASelfDialFailsTheStateArm(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that re-flips Woven→Weaving because it was handed its own identity must FAIL",
        ) {
            brokenJoinerHarness(::SeamThatReWeavesOnASelfDial).runSelfDialIsRejected(this)
        }
        assertAll(
            { assertRedOn("the JOINER's state must stay Woven through a rejected self-dial too", failure) },
            { assertArmCount(1, failure) },
        )
    }

    // ── the controls ─────────────────────────────────────────────────────────

    /** An intact pair, dialled at both ends: every one of the six arms holds. */
    @Test
    fun anIntactPairPassesTheSelfDialObligationAtBothEnds(): TestResult = runTest {
        reference().runSelfDialIsRejected(this)
    }

    /**
     * The row's **honest limit**, demonstrated rather than claimed: a harness that does not declare
     * [ObligationDeclaration.Proven] runs nothing, so a joiner this rig has proven broken is green
     * here. That silent skip is the price of an opt-in hook, and it is not left unattended —
     * [SeamConformanceSuite.selfDialDeclarationIsHonest] is what makes it accountable.
     *
     * **This harness is deliberately inconsistent** — it declares a `Gap` while its hook *can* inject
     * — because that is the only way to reach the declaration gate with the injection gate open, and
     * an unpinned gate is a GREEN mutation row: delete the declaration check and this test must red.
     * The inconsistency is itself illegal, and the meta-test's `Gap` arm is what forbids it.
     */
    @Test
    fun theDeclarationGateSkipsEveryArmBeforeAnythingIsInjected(): TestResult = runTest {
        brokenJoinerHarness(::SeamThatLoopsItsOwnBroadcastBack, gap = true).runSelfDialIsRejected(this)
    }

    /**
     * The second gate, pinned on its own: a hook that returns `false` must skip every arm **even
     * though it perturbed something**. That is the case [SeamConformanceSuite.injectSelfDial]'s KDoc
     * names — a harness that can dial one end and not the other must return `false` — and a row that
     * asserted anyway would judge a fabric on a half-applied injection. Delete the `injected` check
     * and this test reds.
     *
     * **This control was vacuous when first written and its own mutation row caught it.** The harness
     * used to return `false` *without* touching the joiner, so removing the gate left the row
     * asserting against an intact pair, and M7 came back green — a control proving nothing. The
     * stimulus has to be "dialled AND reported false", not "not dialled".
     */
    @Test
    fun theInjectionGateSkipsEveryArmEvenWhenSomethingWasDialled(): TestResult = runTest {
        brokenJoinerHarness(::SeamThatLoopsItsOwnBroadcastBack, injects = false).runSelfDialIsRejected(this)
    }

    /**
     * The accountability half: a harness that declares [ObligationDeclaration.Proven] while its hook
     * returns `false` has claimed a row it never ran, and the meta-test says so. Without this the
     * previous test would document a hole with nothing watching it.
     */
    @Test
    fun aProvenClaimWithoutAnInjectionRedsTheDeclarationMetaTest(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "declaring Proven while injectSelfDial returns false must FAIL — the obligation " +
                "early-returned and asserted nothing at either end",
        ) {
            provenButUninjectableHarness().runSelfDialDeclarationIsHonest(this)
        }
        assertTrue(
            "early-returned and asserted nothing" in failure.message.orEmpty(),
            "the red must name the un-run obligation; got: ${failure.message}",
        )
    }

    // ── the rig's own premise ────────────────────────────────────────────────

    /**
     * Guards what every row above rests on: [DecoratingJoinerLoom] breaks the **joiner** and leaves the
     * host alone, and the harness's hook really reaches the decorated end.
     *
     * Asserted behaviourally rather than by reading a field: after the injection the joiner loops its
     * own broadcast back and the host does not. Without this, a rig that had somehow decorated the
     * host would still produce reds — on the wrong end — and every message-shape assertion above would
     * be true for the wrong reason.
     */
    @Test
    fun theRigBreaksTheJoinerAndOnlyTheJoiner(): TestResult = runTest {
        val broken = mutableListOf<RespondsToASelfDial>()
        val loom = DecoratingJoinerLoom(InMemoryLoom()) { seam ->
            SeamThatLoopsItsOwnBroadcastBack(seam).also { broken += it }
        }
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("joiner"))
        try {
            assertEquals(1, broken.size, "exactly one end — the joiner — may be decorated")
            broken.forEach { it.selfDialled() }
            assertAll(
                {
                    assertTrue(
                        joiner is RespondsToASelfDial,
                        "the JOINER must be the broken end; got ${joiner::class.simpleName}",
                    )
                },
                {
                    assertTrue(
                        host !is RespondsToASelfDial,
                        "the HOST must be left undecorated; got ${host::class.simpleName}",
                    )
                },
            )
        } finally {
            host.close()
            joiner.close()
        }
    }

    // ── harnesses ────────────────────────────────────────────────────────────

    /**
     * A conforming pair whose hook reports a self-dial at both ends and changes nothing — what a
     * fabric whose guard dropped the connection looks like from outside the seam.
     */
    private fun reference(): SeamConformanceSuite = rigHarness(InMemoryLoom(), injects = true)

    private fun brokenJoinerHarness(
        decorate: (Seam) -> Seam,
        injects: Boolean = true,
        gap: Boolean = false,
    ): SeamConformanceSuite {
        val broken = mutableListOf<RespondsToASelfDial>()
        val loom = DecoratingJoinerLoom(InMemoryLoom()) { seam ->
            decorate(seam).also { if (it is RespondsToASelfDial) broken += it }
        }
        return rigHarness(loom, injects = injects, gap = gap, onInject = { broken.forEach { it.selfDialled() } })
    }

    /** Claims the row ran while its hook injects nothing — what the declaration meta-test must catch. */
    private fun provenButUninjectableHarness(): SeamConformanceSuite =
        rigHarness(InMemoryLoom(), injects = false)

    private fun rigHarness(
        loom: Loom,
        injects: Boolean,
        gap: Boolean = false,
        onInject: () -> Unit = {},
    ): SeamConformanceSuite = object : SeamConformanceSuite() {
        override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
        override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
        override fun capabilityGaps(): Map<String, String> = emptyMap()
        override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER

        override fun selfDialDeclaration(): ObligationDeclaration =
            if (gap) ObligationDeclaration.Gap(GAP_URL) else ObligationDeclaration.Proven

        // The perturbation runs whatever [injects] says, so a `false` return models the harness that
        // dialled one end and could not dial the other — the shape the row's second gate exists for.
        // Returning early instead would leave the pair intact and make that gate's control vacuous.
        override suspend fun injectSelfDial(host: Seam, joiner: Seam): Boolean {
            onInject()
            return injects
        }
    }

    // ── the broken joiners, one per arm ──────────────────────────────────────

    /** A decorated joiner that can be told a self-dial has landed on it. */
    private interface RespondsToASelfDial {
        fun selfDialled()
    }

    /**
     * Shape (b): the self-link is registered and stays live, so every later broadcast is delivered
     * back to the sender stamped with its own `selfId`.
     *
     * `replay = 1` rather than a bare buffer: the obligation subscribes its collector and then waits
     * before broadcasting, and a `merge`d [MutableSharedFlow] subscribes a turn late — a dropped emit
     * would make this rig green by a scheduling accident rather than by the guard being intact.
     */
    private class SeamThatLoopsItsOwnBroadcastBack(private val delegate: Seam) :
        Seam by delegate, RespondsToASelfDial {

        private val selfLink = MutableSharedFlow<Swatch>(replay = 1, extraBufferCapacity = 8)
        private var registered = false

        override fun selfDialled() {
            registered = true
        }

        override val incoming: Flow<Swatch> get() = merge(delegate.incoming, selfLink)

        override suspend fun broadcast(payload: ByteArray) {
            delegate.broadcast(payload)
            if (registered) selfLink.emit(Swatch(payload, sender = delegate.selfId))
        }
    }

    /** Shape (a): the self-link takes a seat in the roster, as any other remote would. */
    private class SeamThatRegistersTheSelfLinkAsARemote(private val delegate: Seam) :
        Seam by delegate, RespondsToASelfDial {

        private val withSelfLink = MutableStateFlow(emptySet<PeerId>())
        private var registered = false

        override fun selfDialled() {
            withSelfLink.value = delegate.peers.value + SELF_LINK
            registered = true
        }

        override val peers: StateFlow<Set<PeerId>> get() = if (registered) withSelfLink else delegate.peers
    }

    /** The backstop's defect: a self-dial re-drives the weave, so the session never settles. */
    private class SeamThatReWeavesOnASelfDial(private val delegate: Seam) :
        Seam by delegate, RespondsToASelfDial {

        private val reWeaving = MutableStateFlow<SeamState>(SeamState.Weaving)
        private var registered = false

        override fun selfDialled() {
            registered = true
        }

        override val state: StateFlow<SeamState> get() = if (registered) reWeaving else delegate.state
    }

    private companion object {
        private const val GAP_URL = "https://github.com/tractat-us/kuilt/issues/2601"

        /** The roster entry a fabric that failed to recognise its own dial would mint for it. */
        private val SELF_LINK = PeerId("self-link")

        private val RIG_ROSTER = JoinerRosterOrigin.FilledByConstruction(
            "a rig over one InMemoryLoom (#2601 positive control): the shared registry fills both " +
                "rosters at weave time, which is fine here — no fixture can supply a self-dial, so " +
                "every arm this rig drives rests on the joining seam's own guard",
        )
    }
}
