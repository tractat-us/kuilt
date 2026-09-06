package us.tractat.kuilt.conformance

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The **positive control** for #2601's delivery slice: proof that the joiner half each of three
 * [SeamConformanceSuite] obligations gained can actually go red, and that it reds on the arm it
 * names rather than somewhere else in the same obligation.
 *
 * `Seam` is *one peer's symmetric view of a session*, but a role-split fabric ships two different
 * `Seam` implementations behind one harness — websocket hosts a `MeshSeam` and joins a `LinkSeam` —
 * so an obligation asserted on `host` alone proves at most half of what the harness under test
 * ships, and the other half is what a joining phone runs. For the *delivery* rows that gap is a
 * whole direction: until now no harness had ever carried a frame **from** the joiner.
 *
 * ## Three rows, and why not the rest of the delivery family
 *
 * `broadcastFromHostDeliversToJoinedPeer`, `incomingPreservesSendOrderToSingleCollector` and
 * `sendToDeliversToNamedPeer` are the coherent "reverse direction nobody has exercised" set: each
 * one moves a frame joiner→host and asserts what arrived. The refusal rows in the same family
 * (`sendToAbsentPeerThrowsPeerNotConnected`, `sendOnTornSeamThrows`, the two payload-budget rows)
 * assert that a send is *rejected*, which is a different mechanism with a different vacuity
 * question, and they land separately — #2601 is explicit that splitting by row is what keeps a red
 * diagnosable.
 *
 * ## The shape, copied deliberately from [SymmetricLifecycleObligationRigTest]
 *
 * Every row gets **two** tests: a broken harness that must red, and a **control arm** on the same
 * harness with the break removed. The control is what separates "the assertion caught the defect"
 * from "this rig was red for some unrelated reason" — without it, a rig that failed to connect at
 * all would look like a successful demonstration.
 *
 * The broken end is always the **joiner**, and always by *decorating* the reference seam
 * ([DecoratingJoinerLoom]) rather than hand-writing a fabric: a decorator is a faithful stimulus for
 * "this end's outbound path is broken", and a hand-written fabric would add surface that could
 * itself be wrong. Each red is checked by **message shape** and by **arm count** — a rig that
 * reddened on the host arm, or on the reverse direction's precondition, would tick a "did it go
 * red?" box while proving nothing.
 *
 * ## The failure mode that WEDGES, and why it is proven here rather than asserted in the suite
 *
 * The obligation the issue names — *"a joiner that cannot broadcast to the host"*, *"a joiner whose
 * directed send never arrives"* — is a **silent drop**, and a silent drop does not red these
 * assertions: the collector simply never receives, so the obligation hangs until `runTest`'s wedge
 * backstop. That is not an oversight in the assertions, it is the price of the awaits being
 * *unbounded*, which they must be: a virtual-time bound is the trap #2601's lifecycle slice hit on
 * `NwBridgeLoopbackConformanceTest`, whose weave runs on a real dispatcher, where `runTest` spends
 * the whole budget while the collector waits on a real socket callback and reds a seam that was
 * never broken.
 *
 * So the drop is demonstrated rather than left as a claim: [aJoinerThatDropsEveryBroadcastWedges]
 * and [aJoinerThatDropsEverySendToWedges] bound the obligation *from outside*, on this rig's
 * `InMemoryLoom` harness where the clock is purely virtual and a bound is therefore exact. They
 * assert the failure is a `TimeoutCancellationException` — i.e. that a dropping joiner does **not**
 * pass. What they cannot make it is a *named* red; that limit is recorded on each obligation.
 *
 * Driving the `internal` obligation bodies rather than the inherited `@Test` wrappers follows
 * [SeamConformanceUngatedCoreTest] and [JoinerRosterObligationRigTest]: it composes inside one
 * `runTest`, which is what wasmJs/JS needs (a bare nested `runTest` returns an un-awaited Promise),
 * and the harnesses are **anonymous** objects because a named concrete subclass would be collected
 * by the JUnit4 (Android) runner as its own test class.
 *
 * ## What this rig is not
 *
 * It demonstrates the *assertions*, not the *fixtures*. Whether a given in-tree harness could ever
 * reach these joiner-side failures is the separate question [JoinerRosterOrigin] exists for, and
 * these rows have their own per-row answer — recorded in each obligation's comment in
 * [SeamConformanceSuite], because it differs per obligation.
 */
class SymmetricDeliveryObligationRigTest {

    // ── (1) broadcastFromHostDeliversToJoinedPeer ────────────────────────────

    @Test
    fun aJoinerWhoseBroadcastTruncatesFailsTheDeliveryObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose broadcast does not carry the payload it was given must FAIL — the " +
                "joiner→host direction is a different write path from host→joiner on every " +
                "role-split fabric",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseBroadcastTruncates))
                .runBroadcastDeliversToJoinedPeer(this)
        }
        assertAll(
            { assertRedOn("the JOINER's broadcast must carry its payload to the host", failure) },
            // Three host arms and three joiner arms; only the joiner's payload arm may move.
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerWhoseBroadcastIsIntactPassesTheDeliveryObligation(): TestResult = runTest {
        reference().runBroadcastDeliversToJoinedPeer(this)
    }

    /**
     * The drop, which is the failure #2601 actually names for this row. It cannot red a named arm —
     * see this class's KDoc — so what is pinned is that it does not *pass*.
     */
    @Test
    fun aJoinerThatDropsEveryBroadcastWedges(): TestResult = runTest {
        // The TestScope has to be captured here: inside `withTimeout` the receiver is a plain
        // CoroutineScope, and the obligation bodies take the TestScope whose clock they run on.
        val scope = this
        assertFailsWith<TimeoutCancellationException>(
            "a joiner that silently drops every broadcast must not PASS this obligation",
        ) {
            withTimeout(WEDGE_PROBE) {
                harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatDropsEveryBroadcast))
                    .runBroadcastDeliversToJoinedPeer(scope)
            }
        }
    }

    // ── (2) incomingPreservesSendOrderToSingleCollector ──────────────────────

    @Test
    fun aJoinerThatReordersItsFirstTwoFramesFailsTheOrderObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner that emits its frames out of order must FAIL — a client's single write loop " +
                "and a server's per-peer fan-out are different code, and one can reorder while the " +
                "other stays FIFO",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatSwapsItsFirstTwoBroadcasts))
                .runIncomingPreservesSendOrder(this)
        }
        assertAll(
            { assertRedOn("joiner→host frame 0", failure) },
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun anOrderedJoinerPassesTheOrderObligation(): TestResult = runTest {
        reference().runIncomingPreservesSendOrder(this)
    }

    // ── (3) sendToDeliversToNamedPeer ────────────────────────────────────────

    @Test
    fun aJoinerWhoseDirectedSendCorruptsThePayloadFailsTheDeliveryObligation(): TestResult = runTest {
        val failure = assertFailsWith<AssertionError>(
            "a joiner whose directed send does not carry the payload it was given must FAIL",
        ) {
            harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseSendToCorruptsThePayload))
                .runSendToDeliversToNamedPeer(this)
        }
        assertAll(
            { assertRedOn("the JOINER's directed payload must match", failure) },
            { assertArmCount(1, failure) },
        )
    }

    @Test
    fun aJoinerWhoseDirectedSendIsIntactPassesTheDeliveryObligation(): TestResult = runTest {
        reference().runSendToDeliversToNamedPeer(this)
    }

    /** As [aJoinerThatDropsEveryBroadcastWedges], for the directed-send path. */
    @Test
    fun aJoinerThatDropsEverySendToWedges(): TestResult = runTest {
        val scope = this
        assertFailsWith<TimeoutCancellationException>(
            "a joiner that silently drops every directed send must not PASS this obligation",
        ) {
            withTimeout(WEDGE_PROBE) {
                harnessOver(DecoratingJoinerLoom(InMemoryLoom(), ::SeamThatDropsEverySendTo))
                    .runSendToDeliversToNamedPeer(scope)
            }
        }
    }

    /**
     * The gate is inside `runSendToDeliversToNamedPeer`, so a harness that does not support directed
     * addressing must run **nothing** — including the joiner arms this slice added. Without this, a
     * future edit hoisting the gate into the `@Test` wrapper would leave the body running against a
     * fabric that had opted out, and the two rig arms above would still be green.
     */
    @Test
    fun theDirectedSendObligationIsGatedInsideItsBody(): TestResult = runTest {
        val loom = DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseSendToCorruptsThePayload)
        object : SeamConformanceSuite() {
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(supportsSendTo = false)
            override fun capabilityGaps(): Map<String, String> = mapOf("supportsSendTo" to GAP_URL)
            override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
        }.runSendToDeliversToNamedPeer(this)
    }

    // ── the rig's own premise ────────────────────────────────────────────────

    /**
     * Guards what every arm above rests on: [DecoratingJoinerLoom] decorates the **joiner** and
     * leaves the host alone. Asserted *behaviourally* — the host's outbound frame arrives intact
     * while the joiner's arrives truncated — because for this slice the decoration is a property of
     * a send path, not of a field a reader could compare.
     */
    @Test
    fun theRigDecoratesTheJoinerAndOnlyTheJoiner(): TestResult = runTest {
        val loom = DecoratingJoinerLoom(InMemoryLoom(), ::SeamWhoseBroadcastTruncates)
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("joiner"))
        try {
            val atJoiner = async { joiner.incoming.take(1).toList() }
            host.broadcast(PROBE)
            val atJoinerFrames = atJoiner.await()

            val atHost = async { host.incoming.take(1).toList() }
            joiner.broadcast(PROBE)
            val atHostFrames = atHost.await()

            assertAll(
                {
                    assertTrue(
                        atJoinerFrames.firstOrNull()?.toByteArray()?.contentEquals(PROBE) == true,
                        "the HOST's outbound path must be left undecorated; got " +
                            "${atJoinerFrames.firstOrNull()?.toByteArray()?.toList()}",
                    )
                },
                {
                    assertTrue(
                        atHostFrames.firstOrNull()?.toByteArray()?.contentEquals(TRUNCATED_PROBE) == true,
                        "the JOINER must be the decorated end; got " +
                            "${atHostFrames.firstOrNull()?.toByteArray()?.toList()}",
                    )
                },
            )
        } finally {
            host.close()
            joiner.close()
        }
    }

    // ── harnesses ────────────────────────────────────────────────────────────

    private fun reference(): SeamConformanceSuite = harnessOver(InMemoryLoom())

    private fun harnessOver(loom: Loom): SeamConformanceSuite = object : SeamConformanceSuite() {
        override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
        override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL
        override fun capabilityGaps(): Map<String, String> = emptyMap()
        override fun joinerRosterOrigin(): JoinerRosterOrigin = RIG_ROSTER
    }

    // ── the broken joiners, one per row ──────────────────────────────────────

    /** A joiner whose `broadcast` reaches the wire, but a byte short. */
    private class SeamWhoseBroadcastTruncates(private val delegate: Seam) : Seam by delegate {
        override suspend fun broadcast(payload: ByteArray) {
            delegate.broadcast(payload.copyOf(maxOf(0, payload.size - 1)))
        }
    }

    /** A joiner whose `broadcast` returns success and sends nothing — the shipped shape of a warn-drop. */
    private class SeamThatDropsEveryBroadcast(delegate: Seam) : Seam by delegate {
        override suspend fun broadcast(payload: ByteArray) = Unit
    }

    /**
     * A joiner that holds its first frame back and releases it after the second, leaving the host to
     * observe `1, 0, 2, 3, 4`. Reordering *within* the fabric is what the obligation forbids, and
     * swapping exactly one adjacent pair is the smallest violation of it.
     */
    private class SeamThatSwapsItsFirstTwoBroadcasts(private val delegate: Seam) : Seam by delegate {
        private var held: ByteArray? = null
        private var seen = 0

        override suspend fun broadcast(payload: ByteArray) {
            seen++
            val pending = held
            when {
                seen == 1 -> held = payload
                pending != null -> {
                    delegate.broadcast(payload)
                    delegate.broadcast(pending)
                    held = null
                }
                else -> delegate.broadcast(payload)
            }
        }
    }

    /** A joiner whose directed send arrives, addressed correctly, carrying the wrong bytes. */
    private class SeamWhoseSendToCorruptsThePayload(private val delegate: Seam) : Seam by delegate {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            delegate.sendTo(peer, payload + CORRUPTION)
        }
    }

    /** A joiner whose directed send is accepted and never arrives. */
    private class SeamThatDropsEverySendTo(delegate: Seam) : Seam by delegate {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
    }

    private companion object {
        private const val GAP_URL = "https://github.com/tractat-us/kuilt/issues/2601"
        private const val CORRUPTION: Byte = 0x7F
        private val PROBE = byteArrayOf(1, 2, 3)
        private val TRUNCATED_PROBE = byteArrayOf(1, 2)

        /**
         * The bound the two wedge probes use. **Virtual** time on an [InMemoryLoom] pair, so it is
         * exact rather than load-sensitive — it is not a wall-clock ceiling and no host can inflate
         * it. It exists only to turn "hangs forever" into "fails now" inside this rig; the suite
         * itself deliberately carries no such bound (see this class's KDoc).
         */
        private val WEDGE_PROBE = 30.seconds

        private val RIG_ROSTER = JoinerRosterOrigin.FilledByConstruction(
            "a rig over one InMemoryLoom (#2601 positive control): the shared registry fills both " +
                "rosters at weave time, which is fine here — these rows are about what a frame " +
                "CARRIES, not about who is in the roster",
        )
    }
}
