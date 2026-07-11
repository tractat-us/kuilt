package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reusable contract test suite for [Loom] implementations.
 *
 * Subclass and implement [newLoomPair] to bind any fabric under test.
 * Every [Test] in this class encodes a required invariant of the seam
 * contract — a conforming implementation must pass all of them.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`)
 * so every fabric adapter can subclass it from its own test source set —
 * realising the "one conformance suite, every fabric passes it" invariant.
 *
 * Provide a fresh host/joiner pair per test via [newLoomPair]:
 *  - `.first`  hosts via [Loom.host] (i.e. `weave(Rendezvous.New(pattern))`).
 *  - `.second` joins via [Loom.join] with [joinTag] (i.e. `weave(Rendezvous.Existing(joinTag()))`).
 *
 * In-process radio fabrics return the **same** instance twice: `(loom, loom)`.
 * Role-split fabrics (websocket, mdns, webrtc, multipeer) return **distinct**
 * host/joiner Looms wired to reach each other.
 *
 * ## Capabilities & gaps
 *
 * Not every fabric can honor every corner of the contract — a browser WebRTC data
 * channel cannot throw synchronously on a torn send, a relay-only fabric never
 * delivers peer-to-peer. Rather than let each fabric carry bespoke `@Ignore`
 * overrides, every subclass declares one [SeamCapabilities] value via [capabilities]
 * and, for every `false` flag, an issue-tracking URL via [capabilityGaps].
 *
 * There is **no skip API** in common `kotlin-test` — an obligation that early-returns
 * reports **PASS**, which is worse than a JVM-visible `@Ignore`. So a gap is *not* made
 * loud by skipping. Two things make it loud instead:
 *  - [everyFalseCapabilityDeclaresAGap] fails the suite if any `false` flag has no URL.
 *  - Task 1.8's rendered capability matrix surfaces the declared gaps.
 *
 * The **core** obligations (host yields a usable seam, broadcast delivery, order,
 * peers≥2, close-idempotency, availability, both Woven-state invariants, close→Torn,
 * absent-peer throw) are **ungated** — no capability flag can suppress them. That
 * structural guarantee is pinned by [SeamConformanceUngatedCoreTest], which drives the
 * core obligations through a harness whose [capabilities] would betray any read.
 *
 * Only the capability-specific obligations gate in-body on their **own** flag:
 *  - [incomingCompletesWhenSeamCloses] ↔ [SeamCapabilities.terminatesIncomingOnClose]
 *  - [stateStaysTornAfterClose] ↔ [SeamCapabilities.staysTornAfterClose]
 *  - [sendOnTornSeamThrows] ↔ [SeamCapabilities.throwsOnSendToTorn]
 *  - [sendToDeliversToNamedPeer] ↔ [SeamCapabilities.supportsSendTo]
 *
 * This suite is deliberately fixed at **two** Looms (ADR-001) and has no positive
 * N-peer/mesh obligation; roster convergence, sender-attributed broadcast, directed
 * routing, peer-leave, and dial dedup across three or more peers are covered by the
 * sibling `MeshConformanceSuite`, which every [SeamCapabilities.meshDelivery] fabric
 * supporting ≥3 peers must also subclass.
 *
 * ## Weaving timing invariant
 *
 * The invariant "a frame sent while [SeamState.Weaving] is not silently dropped"
 * is **not** asserted in this suite because all current harnesses produce
 * instant-[SeamState.Woven] seams: relay fabrics (WebSocket, InMemory) weave at
 * construction, and the Multipeer fake fires its peer-connected callback
 * synchronously during `weave()`, so no harness actually starts [SeamState.Weaving]
 * by the time [newLoomPair] returns. Asserting a `Weaving` precondition here would
 * produce a vacuously-passing test on every fabric.
 *
 * The enforcement point for this invariant is [DelayedWovenLoomTest], which uses
 * [DelayedWovenLoom] — a test-only harness that holds the seam in [SeamState.Weaving]
 * until [DelayedWovenSeam.markWoven] is called explicitly — to reproduce the
 * radio-fabric timing window deterministically. Radio fabric conformance harnesses
 * that fire their connected event asynchronously should run their own equivalent of
 * [DelayedWovenLoomTest] to confirm frames are not dropped in the window.
 */
public abstract class SeamConformanceSuite {

    /**
     * Provide a fresh host/joiner Loom pair per test.
     *  - `.first`  hosts via host(pattern)  (weave(Rendezvous.New(pattern)))
     *  - `.second` joins via join(joinTag()) (weave(Rendezvous.Existing(joinTag())))
     * In-process radio fabrics return the SAME instance twice: (loom, loom).
     * Role-split fabrics return DISTINCT host/joiner Looms wired to reach each other.
     */
    public abstract fun newLoomPair(): Pair<Loom, Loom>

    /**
     * This fabric's declared behaviour against the seam contract.
     *
     * **Abstract on purpose — there is no `FULL` default.** Every fabric must declare
     * its capabilities intentionally (a fabric that is fully conforming declares
     * [SeamCapabilities.FULL] explicitly). A `false` flag here MUST carry a matching
     * issue URL in [capabilityGaps] or [everyFalseCapabilityDeclaresAGap] fails.
     */
    public abstract fun capabilities(): SeamCapabilities

    /**
     * Issue-tracking URL for every `false` flag in [capabilities], keyed by the
     * capability's canonical name (see [SeamCapabilities.falseFlags]).
     *
     * A fully-conforming fabric returns `emptyMap()`. A fabric with a gap MUST list a
     * URL for each `false` flag — the gap is declared and trackable, never silent.
     */
    public abstract fun capabilityGaps(): Map<String, String>

    /**
     * Scope-aware variant used by every [runTest]-based test below. Stateless fabrics
     * ignore [testScope] — the default delegates to the no-arg [newLoomPair].
     *
     * **Started/stateful seam implementations override this** to start their background
     * work (inbound loops, timers, view managers) on `testScope.backgroundScope`, whose
     * coroutines are cancelled before `runTest`'s terminal time-advance. Starting such a
     * seam on the test's structured scope would either block the test (structured-
     * concurrency join) or spin its terminal `advanceUntilIdle` on a re-arming timer; the
     * `backgroundScope` is the only correct home. A seam started here may also read virtual
     * time from `testScope.testScheduler`.
     */
    public open fun newLoomPair(testScope: TestScope): Pair<Loom, Loom> = newLoomPair()

    /** The advertisement the joiner uses. Defaults to the in-memory tag. */
    public open fun joinTag(): Tag = InMemoryTag("joiner")

    /**
     * Drive [newLoomPair] to a connected host/joiner pair and hand both live [Seam]s to
     * [block]. Hosts and joins **concurrently** — a role-split server Loom's host() suspends
     * until a joiner connects, so the two must run at once; in-process (loom, loom) fabrics
     * are unaffected. [block] runs inside the connecting `coroutineScope`.
     */
    protected suspend fun TestScope.connectedPair(
        block: suspend CoroutineScope.(host: Seam, joiner: Seam) -> Unit,
    ) {
        val (hostLoom, joinerLoom) = newLoomPair(this)
        coroutineScope {
            val hostDeferred = async { hostLoom.host(Pattern("host")) }
            val joinerDeferred = async { joinerLoom.join(joinTag()) }
            val host = hostDeferred.await()
            val joiner = joinerDeferred.await()
            block(host, joiner)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Core obligations — UNGATED. No capability flag may suppress any of these.
    //
    //  Each is split into an `internal` body helper + a thin `@Test` wrapper. The
    //  body helper carries the assertions; the meta-test in the ungated-core test
    //  invokes the bodies against a hostile-`capabilities()` harness to prove the
    //  bodies never consult `capabilities()`. Keeping the helpers as plain
    //  `suspend fun (scope: TestScope)` (not member extensions) lets the meta-test
    //  compose them all inside ONE `runTest`, which is correct on wasmJs/JS where a
    //  bare per-obligation `runTest` returns an un-awaited Promise.
    // ─────────────────────────────────────────────────────────────────────────

    // ── (1) host yields a usable Seam with a non-empty selfId ───────────────

    internal suspend fun runHostYieldsUsableSeam(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            assertFalse(host.selfId.value.isEmpty(), "selfId must be non-empty")
        }

    @Test
    public fun hostYieldsUsableSeamWithNonEmptySelfId(): TestResult =
        runTest { runHostYieldsUsableSeam(this) }

    // ── (2) broadcast from host delivers to a joined peer ───────────────────

    internal suspend fun runBroadcastDeliversToJoinedPeer(scope: TestScope): Unit =
        scope.connectedPair { host, joiner ->
            val received = async { joiner.incoming.take(1).toList() }

            val payload = byteArrayOf(10, 20, 30)
            host.broadcast(payload)

            val frames = received.await()
            assertEquals(1, frames.size)
            assertTrue(frames[0].toByteArray().contentEquals(payload), "payload must match")
            assertEquals(host.selfId, frames[0].sender)
        }

    @Test
    public fun broadcastFromHostDeliversToJoinedPeer(): TestResult =
        runTest { runBroadcastDeliversToJoinedPeer(this) }

    // ── (3) incoming preserves send order to a single collector ─────────────

    internal suspend fun runIncomingPreservesSendOrder(scope: TestScope): Unit =
        scope.connectedPair { host, joiner ->
            val received = async { joiner.incoming.take(5).toList() }

            repeat(5) { i -> host.broadcast(byteArrayOf(i.toByte())) }

            val frames = received.await()
            assertEquals(5, frames.size)
            frames.forEachIndexed { i, f -> assertTrue(f.toByteArray().contentEquals(byteArrayOf(i.toByte())), "frame $i payload") }
        }

    @Test
    public fun incomingPreservesSendOrderToSingleCollector(): TestResult =
        runTest { runIncomingPreservesSendOrder(this) }

    // ── (4) peers reports selfId and ≥2 after a join ────────────────────────

    internal suspend fun runPeersReportsSelfIdAndAtLeastTwo(scope: TestScope): Unit =
        scope.connectedPair { host, joiner ->
            assertTrue(host.selfId in host.peers.value, "host peers must include its own selfId")
            assertTrue(joiner.selfId in joiner.peers.value, "joiner peers must include its own selfId")
            assertTrue(host.peers.value.size >= 2, "peer set must have ≥2 after join")
        }

    @Test
    public fun peersReportsSelfIdAndAtLeastTwoAfterJoin(): TestResult =
        runTest { runPeersReportsSelfIdAndAtLeastTwo(this) }

    // ── (5) close is idempotent — calling twice must not throw ──────────────

    internal suspend fun runCloseIsIdempotent(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            host.close()
            host.close() // must not throw
        }

    @Test
    public fun closeIsIdempotent(): TestResult =
        runTest { runCloseIsIdempotent(this) }

    // ── (6) availability returns Available or Unavailable ───────────────────

    internal fun runAvailabilityReturnsAKnownVariant() {
        val (hostLoom, _) = newLoomPair()
        val availability = hostLoom.availability()

        assertTrue(
            availability is FabricAvailability.Available || availability is FabricAvailability.Unavailable,
            "availability() must return Available or Unavailable, got $availability",
        )
    }

    @Test
    public fun availabilityReturnsAKnownVariant() {
        runAvailabilityReturnsAKnownVariant()
    }

    // ── (7) state is Woven after host and joiner both return ─────────────────

    internal suspend fun runStateIsWovenAfterConnect(scope: TestScope): Unit =
        scope.connectedPair { host, joiner ->
            // Both sides must reach Woven — await in case a radio fabric needs a tick.
            assertIs<SeamState.Woven>(
                host.state.first { it is SeamState.Woven },
                "host state must be Woven",
            )
            assertIs<SeamState.Woven>(
                joiner.state.first { it is SeamState.Woven },
                "joiner state must be Woven",
            )
        }

    @Test
    public fun stateIsWovenAfterConnect(): TestResult =
        runTest { runStateIsWovenAfterConnect(this) }

    // ── (8) host state is Woven even before any peer joins ───────────────────

    internal suspend fun runHostStateIsWovenEvenAlone(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            // Relay fabrics are Woven at construction; radio fabrics on first connect.
            // Either way, after the connection completes, host must be Woven.
            val hostState = host.state.first { it is SeamState.Woven }
            assertIs<SeamState.Woven>(hostState, "host state must be Woven")
        }

    @Test
    public fun hostStateIsWovenEvenAlone(): TestResult =
        runTest { runHostStateIsWovenEvenAlone(this) }

    // ── (9) close drives state to Torn(Normal) ──────────────────────────────

    internal suspend fun runCloseDrivesStateTornNormal(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            host.close()

            assertIs<SeamState.Torn>(host.state.value, "state must be Torn after close()")
        }

    @Test
    public fun closeDrivesStateTornNormal(): TestResult =
        runTest { runCloseDrivesStateTornNormal(this) }

    // ── (10) sendTo an absent peer throws PeerNotConnected ───────────────────

    internal suspend fun runSendToAbsentPeerThrows(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            val phantom = PeerId("phantom-peer-not-in-session")
            assertFailsWith<PeerNotConnected> {
                host.sendTo(phantom, byteArrayOf(1))
            }
        }

    @Test
    public fun sendToAbsentPeerThrowsPeerNotConnected(): TestResult =
        runTest { runSendToAbsentPeerThrows(this) }

    // ─────────────────────────────────────────────────────────────────────────
    //  Capability-gated obligations — each gates in-body on its OWN flag only.
    //
    //  A `false` flag makes the obligation early-return (a silent PASS — common
    //  kotlin-test has no skip API). The gap is NOT loud here; it is made loud by
    //  [everyFalseCapabilityDeclaresAGap] requiring a URL for every false flag,
    //  plus the rendered matrix. This replaces the old per-fabric `@Ignore`/empty
    //  override mechanism — the gating now lives in the suite body.
    // ─────────────────────────────────────────────────────────────────────────

    // ── (9b) state STAYS Torn after close, even under post-close churn ────────
    //
    // `closeDrivesStateTornNormal` proves the state is Torn the instant close() returns — a single
    // observation. It does NOT prove the terminal state *stays* Torn: a seam whose internal pump
    // writes the state flow (a composite's rollup, a tiered union's combine) can overwrite the
    // terminal Torn with a stale non-terminal value, wedging every `state.first { it is Torn }`
    // waiter forever (the lost-terminal-transition class this suite exists to keep dead). This test
    // closes the host, then drives whatever churn the fabric supports AFTER close — joiner still
    // active, frames in flight toward the torn host, then the joiner closing — and re-asserts the
    // state is unchanged.
    //
    // **Honest limit:** under this suite's single-threaded virtual-time harness this is a
    // deterministic ordering check, necessary but not sufficient — it cannot reproduce the genuine
    // multi-threaded race. The stress-grade coverage lives in the `-Pconcurrency.stress.tests`
    // real-threaded probes (`:kuilt-core`'s `*ConcurrencyTest`). This obligation's value is the
    // *contract*: it protects out-of-tree fabrics that subclass this suite and cannot use the
    // in-tree `SeamStateGate`, converting "the next seam ships the bug silently" into a red test.

    @Test
    public fun stateStaysTornAfterClose(): TestResult =
        runTest {
            if (!capabilities().staysTornAfterClose) return@runTest
            connectedPair { host, joiner ->
                host.close()
                val torn = host.state.value
                assertIs<SeamState.Torn>(torn, "state must be Torn immediately after close()")

                // Post-close churn: the conditions under which a multi-writer seam could clobber Torn.
                repeat(5) { i -> tolerateTornChurn { joiner.broadcast(byteArrayOf(i.toByte())) } }
                tolerateTornChurn { joiner.close() }

                val after = host.state.value
                assertIs<SeamState.Torn>(after, "state must STAY Torn after post-close churn")
                assertEquals(torn.reason, after.reason, "the terminal Torn reason must not change under churn")
            }
        }

    /**
     * Run best-effort post-close [op], swallowing the closed-seam / dropped-peer signals a torn
     * fabric legitimately raises (a role-split fabric may already have torn the joiner when the host
     * closed). Cancellation is always rethrown — never swallow a structured-concurrency cancel.
     */
    private suspend inline fun tolerateTornChurn(op: () -> Unit) {
        try {
            op()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // closed seam / absent peer / torn transport — expected under post-close churn.
        }
    }

    // ── (11) directed send DELIVERS to the named peer ────────────────────────
    //
    // The positive counterpart to `sendToAbsentPeerThrowsPeerNotConnected`: a `sendTo` addressed to
    // a peer that IS in the session delivers exactly that payload to that peer, attributed to the
    // sender. Gated on `supportsSendTo` because a fabric without directed addressing cannot honour it.

    @Test
    public fun sendToDeliversToNamedPeer(): TestResult =
        runTest {
            if (!capabilities().supportsSendTo) return@runTest
            connectedPair { host, joiner ->
                val received = async { joiner.incoming.take(1).toList() }

                val payload = byteArrayOf(5, 6, 7)
                host.sendTo(joiner.selfId, payload)

                val frames = received.await()
                assertEquals(1, frames.size, "directed send must deliver exactly one frame")
                assertTrue(frames[0].toByteArray().contentEquals(payload), "directed payload must match")
                assertEquals(host.selfId, frames[0].sender, "sender must be the directed-send originator")
            }
        }

    // ── (12) incoming completes when the seam reaches Torn ───────────────────
    //
    // Contract from Seam.incoming KDoc: the flow terminates once the seam is Torn,
    // whether via local close() or remote disconnect. Consumers (e.g. Quilter)
    // rely on this to self-close via onCompletion without requiring an explicit caller.
    //
    // Gated on `terminatesIncomingOnClose`; WebRTC does not honour it yet — see #335.

    @Test
    public fun incomingCompletesWhenSeamCloses(): TestResult =
        runTest {
            if (!capabilities().terminatesIncomingOnClose) return@runTest
            connectedPair { host, joiner ->
                // Collect host.incoming in the background; it should complete once host closes.
                val collectingJob = async {
                    withTimeout(5.seconds) {
                        host.incoming.toList()
                    }
                }

                host.close()

                // If the fabric honours the contract, toList() completes (flow terminated).
                // withTimeout(5s) guards against fabrics that hang instead of completing.
                collectingJob.await()
                assertIs<SeamState.Torn>(host.state.value, "host state must be Torn after close()")
            }
        }

    // ── (13) send on a Torn seam throws IllegalStateException ────────────────
    //
    // Contract from Seam KDoc: once the seam is Torn, `broadcast`/`sendTo` reject the frame with
    // an `IllegalStateException` rather than silently dropping it — a torn transport cannot deliver,
    // and swallowing the send hides the failure from the caller. Every core fabric enforces this
    // with `check(state !is Torn)` (LinkSeam, MeshSeam, CompositeSeam, TieredSeam, InMemoryLoom,
    // RoomHubSeam). This assertion exists so no fabric can silently regress to a warn-drop.
    //
    // Gated on `throwsOnSendToTorn`; known non-conformers today: the Multipeer JVM bridge and the
    // Gossip overlay's `broadcast`.

    @Test
    public fun sendOnTornSeamThrows(): TestResult =
        runTest {
            if (!capabilities().throwsOnSendToTorn) return@runTest
            connectedPair { host, joiner ->
                host.close()
                assertIs<SeamState.Torn>(host.state.value, "host must be Torn after close()")

                assertFailsWith<IllegalStateException>("broadcast on a Torn seam must throw") {
                    host.broadcast(byteArrayOf(1))
                }
                assertFailsWith<IllegalStateException>("sendTo on a Torn seam must throw") {
                    host.sendTo(joiner.selfId, byteArrayOf(2))
                }
            }
        }

    // ── (14) send while Weaving is best-effort — never throws ────────────────
    //
    // Contract (issue #1367 sub-decision 1): a send while [SeamState.Weaving] is best-effort — it
    // must NOT throw; delivery is simply not guaranteed until [SeamState.Woven]. Only [SeamState.Torn]
    // sends throw. This pins the reconciled contract so no fabric revives the old "Weaving send is an
    // error" behaviour.
    //
    // [newLoomPair]'s harnesses are all instant-[SeamState.Woven] (see the class KDoc's "Weaving timing
    // invariant"), so a genuinely-Weaving seam is only available from [DelayedWovenLoom] — the reference
    // Weaving harness in this module. The assertion is therefore driven through it rather than the fabric
    // under test, mirroring how [DelayedWovenLoomTest] already owns the Weaving-delivery invariant.

    @Test
    public fun sendWhileWeavingDoesNotThrow(): TestResult =
        runTest {
            val loom = DelayedWovenLoom()
            val host = loom.host(Pattern("host")) as DelayedWovenSeam
            val joiner = loom.join(InMemoryTag("joiner")) as DelayedWovenSeam

            assertIs<SeamState.Weaving>(host.state.value, "host must be Weaving before markWoven()")
            assertIs<SeamState.Weaving>(joiner.state.value, "joiner must be Weaving before markWoven()")

            // Best-effort: neither send may throw while the seam is still Weaving.
            host.broadcast(byteArrayOf(1))
            host.sendTo(joiner.selfId, byteArrayOf(2))
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  Meta-test — every declared gap is trackable.
    // ─────────────────────────────────────────────────────────────────────────

    // ── (15) every `false` capability declares a gap URL ─────────────────────
    //
    // Inherited by every fabric. A fully-conforming fabric declares no false flags and passes
    // vacuously; the instant a fabric flips a flag off without recording an issue URL, this fails.
    // This is the loud-gap guarantee: a capability gap can never be silent.

    @Test
    public fun everyFalseCapabilityDeclaresAGap() {
        val gaps = capabilityGaps()
        val undeclared = capabilities().falseFlags().filter { gaps[it].isNullOrBlank() }
        assertTrue(
            undeclared.isEmpty(),
            "every false capability must declare a non-blank gap URL in capabilityGaps(); undeclared: $undeclared",
        )
    }
}
