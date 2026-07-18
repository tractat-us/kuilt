package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
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
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
 * There is a **third** gating mechanism alongside *core (ungated)* and *capability-gated*:
 * **harness-hook-gated**. [incomingCompletesOnInjectedMidSessionDeath] runs only when a harness
 * overrides [injectMidSessionDeath] to actually drop the transport — a capability of the *harness*,
 * not the *fabric*. Its silent-skip is made accountable exactly as a capability gap is: an
 * un-overridden harness must declare a tracking URL via [midSessionDeathGap], enforced by
 * [midSessionDeathObligationIsTrackedWhenUnproven]. The same shape governs [injectMembershipDrain]
 * (a peer leaving without a tear) and [injectSelfDial] (a peer dialling its own advertisement, the
 * #1466 class) — each opt-in, each tracked-by-default via its own `*Gap()` and `*IsTrackedWhenUnproven`
 * meta-test rather than a required abstract every fabric would have to implement.
 *
 * ## Continuous contract monitor
 *
 * [connectedPair] launches a background collector that asserts `selfId ∈ peers` on a live (not
 * [SeamState.Torn]) seam for the whole test — every obligation test is thereby also a monitor.
 * [peersReportsSelfIdAndAtLeastTwoAfterJoin] only samples the invariant once at the end of a join; the
 * monitor watches it across the whole test. **Honest limit:** `peers` is a [kotlinx.coroutines.flow.StateFlow],
 * so the collector observes the *latest* value at each resumption, not every intermediate write — a
 * **persistent** `selfId ∉ peers` on a live seam (the #1466 failure — a survivor's roster collapsing to
 * {theOtherPeer} while it stays Woven) is reliably caught, but a purely transient sub-scheduling drop that
 * is overwritten before the collector resumes may be missed. There is no stronger primitive against a
 * StateFlow; the monitor raises the floor from "sampled once" to "sampled continuously".
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
     * Inject a **mid-session transport death** under **both** [host] and [joiner] — the way a real
     * fabric dies when the underlying connection drops rather than being closed by the application.
     * Return `true` if the harness performed the injection; `false` (the default) means "this harness
     * cannot inject death", and [incomingCompletesOnInjectedMidSessionDeath] early-returns without
     * asserting.
     *
     * This is a **harness** capability, not a fabric [SeamCapabilities] flag — only a harness with a
     * handle on the transport under the seam (e.g. an in-memory mesh over a `connectionPair`) can drop
     * it out from under a live session. An overriding harness drops the underlying connection(s) so
     * **each** side observes a remote disconnect (not a local `close()`), then returns `true`. Because
     * the seam is peer-symmetric, a 2-peer transport death is symmetric: both ends latch
     * [SeamState.Torn] and both `incoming` flows complete — which is what
     * [incomingCompletesOnInjectedMidSessionDeath] asserts. A harness that overrides this to `true`
     * MUST also override [midSessionDeathGap] to return `null` (the obligation is now proven).
     */
    public open suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean = false

    /**
     * Tracking URL for **why this harness does not prove the mid-session-death obligation** — the
     * accountability analog of [capabilityGaps] for the [injectMidSessionDeath] hook.
     *
     * The base default is a **non-null** umbrella URL ([CapabilityGaps.MID_SESSION_DEATH]): an
     * un-overridden harness (one that leaves [injectMidSessionDeath] at its default `false`) is
     * *tracked by default*, never silently green. A harness that overrides [injectMidSessionDeath]
     * to actually drop the transport **proves** the obligation and MUST override this to return
     * `null`/blank (no gap). [midSessionDeathObligationIsTrackedWhenUnproven] enforces the pairing:
     * hook-returns-`false` ⇒ this must be non-blank.
     */
    public open fun midSessionDeathGap(): String? = CapabilityGaps.MID_SESSION_DEATH

    /**
     * Inject a **mid-session membership drain**: drop [joiner] from [host]'s peer set mid-session
     * **without** tearing [host]'s seam — `host.peers` shrinks while `host.state` stays
     * [SeamState.Woven]. Return `true` if the harness performed the injection; `false` (the default)
     * means "this harness cannot inject a drain", and [peersDrainWithoutTearOnInjectedMembershipDrain]
     * early-returns without asserting.
     *
     * This is the **distinct event** from [injectMidSessionDeath] (#1466): a transport *tear* latches
     * [SeamState.Torn] under **both** ends; a membership *drain* leaves the survivor [SeamState.Woven]
     * and only shrinks its roster. A strictly-2-peer mesh cannot honour this — losing its only link
     * IS a tear — so only an **N-peer** harness whose shared roster survives one peer leaving (e.g. the
     * reference `InMemoryLoom`, where a leaver's `close()` removes it from the shared `peers` while the
     * other seam stays Woven) can prove it. An overriding harness drops [joiner] from the roster,
     * leaving [host] Woven, then returns `true`, and MUST also override [membershipDrainGap] to `null`.
     */
    public open suspend fun injectMembershipDrain(host: Seam, joiner: Seam): Boolean = false

    /**
     * Tracking URL for **why this harness does not prove the membership-drain obligation** — the
     * accountability analog of [midSessionDeathGap] for the [injectMembershipDrain] hook.
     *
     * The base default is a non-null umbrella URL ([CapabilityGaps.MEMBERSHIP_DRAIN]): an un-overridden
     * harness (one that leaves [injectMembershipDrain] at its default `false`) is tracked by default,
     * never silently green. A harness that overrides [injectMembershipDrain] to actually drain a peer
     * **proves** the obligation and MUST override this to return `null`/blank.
     * [membershipDrainObligationIsTrackedWhenUnproven] enforces the pairing.
     */
    public open fun membershipDrainGap(): String? = CapabilityGaps.MEMBERSHIP_DRAIN

    /**
     * Inject a **self-dial**: make [host] resolve a connection whose remote identity is its OWN
     * [Seam.selfId] — the #1466 class. A symmetric advertise+browse fabric is delivered its own
     * advertisement (real Bonjour/mDNS/`NWBrowser` returns a device's own service to its own browser),
     * dials it, and the resulting connection resolves to `selfId`. The seam's self-connection guard
     * MUST drop it. Return `true` if the harness performed the injection; `false` (the default) means
     * "this harness cannot inject a self-dial", and [selfDialIsRejected] early-returns without asserting.
     *
     * This is a **harness** capability, not a fabric [SeamCapabilities] flag — mirroring
     * [injectMidSessionDeath]. Only a harness that can make a live seam see a connection to its own
     * `selfId` (e.g. the `FakeNwRadio` self-endpoint delivery added for #1485) can prove it; a
     * relay/2-peer harness with no self-discovery cannot self-dial at all and leaves this `false`.
     * It is deliberately **opt-in** rather than a required abstract: a required hook would force every
     * fabric subclass to implement a self-dial many structurally cannot perform. Accountability is
     * preserved exactly as [injectMidSessionDeath]'s is — an un-overriding harness is *tracked*, never
     * silently green, via [selfDialGap] and [selfDialObligationIsTrackedWhenUnproven].
     *
     * A harness that overrides this to `true` MUST also override [selfDialGap] to return `null`.
     */
    public open suspend fun injectSelfDial(host: Seam): Boolean = false

    /**
     * Tracking URL for **why this harness does not prove the self-dial obligation** — the
     * accountability analog of [midSessionDeathGap] for the [injectSelfDial] hook.
     *
     * The base default is a non-null umbrella URL ([CapabilityGaps.SELF_DIAL]): an un-overridden
     * harness (one that leaves [injectSelfDial] at its default `false`) is tracked by default, never
     * silently green. A harness that overrides [injectSelfDial] to genuinely inject a self-dial
     * **proves** the obligation and MUST override this to return `null`/blank.
     * [selfDialObligationIsTrackedWhenUnproven] enforces the pairing.
     */
    public open fun selfDialGap(): String? = CapabilityGaps.SELF_DIAL

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
            // Continuous contract monitor (#1490): a LIVE seam must include its own selfId in its roster
            // for the whole test, not just the single snapshot `peersReportsSelfIdAndAtLeastTwoAfterJoin`
            // checks. This is the #1466 upgrade — the self-connection bug shrank a survivor's roster to
            // {theOtherPeer} while it stayed Woven. `peers` is a StateFlow, so the collector catches a
            // *persistent* self-eviction on a live seam, not necessarily a transient sub-scheduling drop
            // (see the class KDoc's "Continuous contract monitor" for the honest limit).
            val monitors = listOf(
                launch { monitorSelfAlwaysInPeers(host) },
                launch { monitorSelfAlwaysInPeers(joiner) },
            )
            try {
                block(host, joiner)
            } finally {
                monitors.forEach { it.cancel() }
                // Tear BOTH seams down at test end. A fabric that treats peer loss as *recoverable*
                // (e.g. NwSeam since #1513 re-forms Woven→Weaving instead of tearing) leaves the survivor
                // seam's background work re-arming — its redial loop keeps dialling the departed peer. If
                // that loop is not cancelled, runTest's terminal `advanceUntilIdle` spins on the re-arming
                // timer forever (an OOM/hang). close() cancels the seam's scope and is idempotent, so this
                // is safe for a seam a test already closed.
                //
                // BOUNDED best-effort cleanup: `withContext(NonCancellable)` shields it from the outer
                // cancellation so it always runs (even if `block` failed); `withTimeoutOrNull(2s)` bounds a
                // close() that can WEDGE on a dead transport (e.g. a WS close handshake) so one bad fabric
                // can't hang every conformance test; `runCatchingCancellable` tolerates a close error. A
                // timeout or error here is deliberately swallowed — teardown is best-effort, and any real
                // close bug surfaces in the dedicated close-obligation tests, not here.
                withContext(NonCancellable) {
                    withTimeoutOrNull(2.seconds) { runCatchingCancellable { host.close() } }
                    withTimeoutOrNull(2.seconds) { runCatchingCancellable { joiner.close() } }
                }
            }
        }
    }

    /**
     * Assert `selfId ∈ peers` on a live (non-[SeamState.Torn]) [seam] for the whole test. Because `peers`
     * is a [kotlinx.coroutines.flow.StateFlow], this observes the latest value at each resumption, not
     * every write — a *persistent* live-seam self-eviction is reliably caught; a transient drop overwritten
     * before the collector resumes may be missed (see the class KDoc). Scoped to non-Torn: a Torn seam has
     * left the session, and a fabric whose `peers` is a shared mesh-registry view (e.g. the reference
     * `InMemoryLoom`) legitimately drops the departed member — `close()` there latches Torn *before*
     * removing `selfId` from the shared roster, so the scope is exact, not a fudge.
     */
    private suspend fun monitorSelfAlwaysInPeers(seam: Seam) {
        seam.peers.collect { peers ->
            if (seam.state.value !is SeamState.Torn) {
                assertTrue(
                    seam.selfId in peers,
                    "a live seam's peers must ALWAYS contain its own selfId (${seam.selfId.value}); " +
                        "got ${peers.map { it.value }}",
                )
            }
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

    // ── (6) availability returns a known FabricAvailability variant ─────────

    internal fun runAvailabilityReturnsAKnownVariant() {
        val (hostLoom, _) = newLoomPair()
        val availability = hostLoom.availability()

        assertTrue(
            availability is FabricAvailability.Available ||
                availability is FabricAvailability.Unavailable ||
                availability is FabricAvailability.Unknown,
            "availability() must return Available, Unavailable, or Unknown, got $availability",
        )
    }

    @Test
    public fun availabilityReturnsAKnownVariant() {
        runAvailabilityReturnsAKnownVariant()
    }

    // ── (6b) a Woven Seam reports Available live capability ─────────────────

    internal suspend fun runWovenSeamReportsAvailableCapability(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            assertTrue(
                host.capability.value.availability is FabricAvailability.Available,
                "a Woven Seam must report Available capability, got ${host.capability.value}",
            )
        }

    @Test
    public fun wovenSeamReportsAvailableCapability(): TestResult =
        runTest { runWovenSeamReportsAvailableCapability(this) }

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
    // Gated on `terminatesIncomingOnClose` for a future fabric that can't honour it; WebRTC was
    // the historical non-conformer (#335), since fixed — every fabric in-tree passes this today.

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
    // Gated on `throwsOnSendToTorn` for a future fabric that can't honour it; the Multipeer JVM
    // bridge and the Gossip overlay's `broadcast` were the historical non-conformers (#1390),
    // since fixed — every fabric in-tree passes this today.

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

    // ── (13b) incoming completes when a mid-session transport death is injected ──
    //
    // Contract from Seam.incoming KDoc: `incoming` completes once the seam reaches Torn — "whether via
    // local close OR a remote disconnect." The `incomingCompletesWhenSeamCloses` obligation covers the
    // local-close half; this covers the remote-disconnect half — the transport dies under a live
    // session, with no local close() call. A conforming fabric must latch Torn AND complete `incoming`
    // atomically (never state-terminal-but-incoming-open).
    //
    // Gated on a HARNESS hook, not a SeamCapabilities flag: only a harness with a handle on the
    // transport under the seam can inject death. The default `injectMidSessionDeath` returns false, so
    // every fabric whose harness cannot inject death early-returns (a silent skip); only a harness that
    // overrides the hook to actually kill the transport runs the assertion.

    @Test
    public fun incomingCompletesOnInjectedMidSessionDeath(): TestResult =
        runTest {
            connectedPair { host, joiner ->
                val injected = injectMidSessionDeath(host, joiner)
                if (!injected) return@connectedPair // harness cannot inject death — nothing to assert.

                // Both symmetric ends must reach Torn on the injected transport death (bounded — no
                // unbounded advance) AND both `incoming` flows must complete (a late collector on the
                // drained spool terminates). The suspend collection happens first; the terminal-state
                // checks are batched through assertAll so both failures surface at once.
                val hostTorn = withTimeout(5.seconds) { host.state.first { it is SeamState.Torn } }
                withTimeout(5.seconds) { host.incoming.toList() }
                val joinerTorn = withTimeout(5.seconds) { joiner.state.first { it is SeamState.Torn } }
                withTimeout(5.seconds) { joiner.incoming.toList() }

                assertAll(
                    { assertIs<SeamState.Torn>(hostTorn, "host must latch Torn on injected mid-session transport death") },
                    { assertIs<SeamState.Torn>(joinerTorn, "joiner must latch Torn on injected mid-session transport death") },
                )
            }
        }

    // ── (13c) a membership drain shrinks peers WITHOUT tearing the survivor ───
    //
    // The distinct event from a transport tear (#1466): a peer leaves `Seam.peers` while the
    // survivor's `state` stays Woven — no `close()`, no Torn latch. `incomingCompletesOnInjectedMid
    // SessionDeath` covers the TEAR (both ends latch Torn, both `incoming` complete); this covers
    // the DRAIN (survivor stays Woven, roster shrinks). #1466 shipped green precisely because the
    // harness could only inject a tear, so a consumer that only woke on a tear suspended forever on
    // a drain — invisible to every suite obligation. This makes the drain a first-class injectable
    // event so a survivor-side obligation can assert on it.
    //
    // Gated on a HARNESS hook, not a SeamCapabilities flag: only an N-peer harness whose shared
    // roster survives one peer leaving (e.g. InMemoryLoom) can inject a drain-without-tear; a
    // strictly-2-peer mesh must tear when its only link drops. The default `injectMembershipDrain`
    // returns false, so such harnesses early-return (a silent skip tracked by `membershipDrainGap`).

    @Test
    public fun peersDrainWithoutTearOnInjectedMembershipDrain(): TestResult =
        runTest {
            connectedPair { host, joiner ->
                val drainedPeer = joiner.selfId
                val injected = injectMembershipDrain(host, joiner)
                if (!injected) return@connectedPair // harness cannot inject a drain — nothing to assert.

                // Bounded: the survivor observes the drained peer leave its roster (no unbounded advance).
                val peersAfter = withTimeout(5.seconds) { host.peers.first { drainedPeer !in it } }

                // The defining invariant of a DRAIN (vs a tear): the survivor's state stays Woven.
                assertAll(
                    { assertFalse(drainedPeer in peersAfter, "drained peer must leave the survivor's peers") },
                    {
                        assertIs<SeamState.Woven>(
                            host.state.value,
                            "a membership drain must NOT tear the survivor — state stays Woven (distinct from a transport tear)",
                        )
                    },
                )
            }
        }

    // ── (13d) a self-dial is REJECTED — self never joins the roster, never echoes ──
    //
    // The #1466 class as a first-class obligation: a symmetric advertise+browse fabric dials its own
    // advertisement (real Bonjour/mDNS returns a device its own service), so a live seam sees a
    // connection whose remote resolves to `selfId`. The self-connection guard MUST drop it — registering
    // self is exactly what wedged #1466: `selfId` lands in the registry, and when that self-link later
    // fails the close-loop evicts *self*, collapsing the survivor's roster to {theOtherPeer} while it
    // stays Woven (no Torn) — invisible to every consumer keying on `peers`/`host`/`Torn`.
    //
    // A rejected self-dial is probed two complementary ways, because a broken guard can manifest in
    // either of two shapes depending on the fabric:
    //   (a) **roster self-eviction** — self is registered as a remote, then the self-link fails and its
    //       close evicts *self* from `peers`, collapsing the survivor's roster to {theOtherPeer} while it
    //       stays Woven. This is the literal #1466 signature. Caught by the `peers`-unchanged assertion
    //       below (and independently by [connectedPair]'s continuous monitor).
    //   (b) **live self-loopback** — self is registered AND its link stays live, so the host's own
    //       broadcast is delivered back to it stamped `sender == selfId` (a healthy seam never loops a
    //       peer's own broadcast to itself). Caught by the broadcast-echo assertion below.
    // Both are asserted so the obligation has teeth regardless of which shape a given fabric produces. A
    // *passive* "no self-frame arrives" check (no broadcast) would be near-vacuous — a self-link's only
    // frames are the identity `NwHello`s, consumed as identity and never surfaced to `incoming` — so we
    // broadcast to force a live self-loopback (shape b) to reveal itself. (For the reference `NwSeam`
    // fake a self-dial's two connection ends share one radio link and tear each other, so a broken guard
    // there surfaces as shape (a), the roster eviction; a fabric whose self-link is a durable loopback
    // surfaces as shape (b).) State-stays-Woven backstops both (no re-flip, no tear).
    //
    // Gated on a HARNESS hook, not a SeamCapabilities flag: only a harness that can make a live seam see
    // a connection to its own `selfId` (e.g. the `FakeNwRadio` self-endpoint path, #1485) can inject it.
    // The default [injectSelfDial] returns false, so harnesses that cannot self-dial early-return (a
    // silent skip tracked by [selfDialGap]). `incoming` is single-collection (ADR-034) and [connectedPair]
    // does NOT collect `host.incoming`, so the collector below is its sole reader.

    @Test
    public fun selfDialIsRejected(): TestResult =
        runTest {
            connectedPair { host, _ ->
                val peersBefore = host.peers.value
                val injected = injectSelfDial(host)
                if (!injected) return@connectedPair // harness cannot inject a self-dial — nothing to assert.

                // Subscribe the sole `incoming` collector first, then let the injected self-dial fully
                // resolve-or-drop, then broadcast the probe. A self-registered link echoes the broadcast
                // back attributed to selfId (non-null ⇒ fail); a healthy seam never does (window elapses
                // ⇒ null ⇒ pass).
                val selfEcho = async {
                    withTimeoutOrNull(2.seconds) { host.incoming.first { it.sender == host.selfId } }
                }
                delay(100.milliseconds) // let the injected self-dial resolve/drop and the collector subscribe
                host.broadcast(byteArrayOf(0x5E.toByte(), 0x1F))
                val echo = selfEcho.await()

                assertAll(
                    {
                        assertNull(
                            echo,
                            "a rejected self-dial must not register a self-link: the host's own broadcast must " +
                                "never loop back to it attributed to selfId",
                        )
                    },
                    { assertEquals(peersBefore, host.peers.value, "a rejected self-dial must not change peers (self never registered as a remote)") },
                    {
                        assertIs<SeamState.Woven>(
                            host.state.value,
                            "a rejected self-dial must not re-flip Weaving→Woven nor tear the seam — state stays Woven",
                        )
                    },
                )
            }
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

    // ── (16) an un-proven mesh-death obligation must be tracked, not silently skipped ──
    //
    // The harness-hook analog of [everyFalseCapabilityDeclaresAGap]. [injectMidSessionDeath]'s own
    // return value is the "is it proven?" proxy: a harness that cannot inject death (hook returns
    // `false`) MUST declare a non-blank [midSessionDeathGap], so the silent early-return of
    // [incomingCompletesOnInjectedMidSessionDeath] is tracked rather than invisibly green. A harness
    // that proves the obligation (hook returns `true`) may leave the gap `null`. The base default
    // gap is non-null, so an un-overridden harness passes this by being tracked by the umbrella issue.

    @Test
    public fun midSessionDeathObligationIsTrackedWhenUnproven(): TestResult =
        runTest {
            connectedPair { host, joiner ->
                val proven = injectMidSessionDeath(host, joiner)
                if (!proven) {
                    assertFalse(
                        midSessionDeathGap().isNullOrBlank(),
                        "an un-proven mesh-death obligation must be tracked: override midSessionDeathGap() " +
                            "with a tracking URL, or override injectMidSessionDeath to prove it",
                    )
                }
            }
        }

    // ── (17) an un-proven membership-drain obligation must be tracked, not silently skipped ──
    //
    // The harness-hook analog of [midSessionDeathObligationIsTrackedWhenUnproven] for the
    // [injectMembershipDrain] hook. A harness that cannot inject a drain (hook returns `false`) MUST
    // declare a non-blank [membershipDrainGap], so the silent early-return of
    // [peersDrainWithoutTearOnInjectedMembershipDrain] is tracked rather than invisibly green. A
    // harness that proves the obligation (hook returns `true`) may leave the gap `null`.

    @Test
    public fun membershipDrainObligationIsTrackedWhenUnproven(): TestResult =
        runTest {
            connectedPair { host, joiner ->
                val proven = injectMembershipDrain(host, joiner)
                if (!proven) {
                    assertFalse(
                        membershipDrainGap().isNullOrBlank(),
                        "an un-proven membership-drain obligation must be tracked: override membershipDrainGap() " +
                            "with a tracking URL, or override injectMembershipDrain to prove it",
                    )
                }
            }
        }

    // ── (18) an un-proven self-dial obligation must be tracked, not silently skipped ──
    //
    // The harness-hook analog of [midSessionDeathObligationIsTrackedWhenUnproven] for the
    // [injectSelfDial] hook. A harness that cannot inject a self-dial (hook returns `false`) MUST
    // declare a non-blank [selfDialGap], so the silent early-return of [selfDialIsRejected] is tracked
    // rather than invisibly green. A harness that proves the obligation (hook returns `true`) may leave
    // the gap `null`.

    @Test
    public fun selfDialObligationIsTrackedWhenUnproven(): TestResult =
        runTest {
            connectedPair { host, _ ->
                val proven = injectSelfDial(host)
                if (!proven) {
                    assertFalse(
                        selfDialGap().isNullOrBlank(),
                        "an un-proven self-dial obligation must be tracked: override selfDialGap() " +
                            "with a tracking URL, or override injectSelfDial to prove it",
                    )
                }
            }
        }
}
