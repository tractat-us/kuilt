package us.tractat.kuilt.conformance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
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
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
 * absent-peer throw, self-send refusal, close-does-not-mint-a-cancellation) are **ungated** — no
 * capability flag can suppress them. That
 * structural guarantee is pinned by [SeamConformanceUngatedCoreTest], which drives the
 * core obligations through a harness whose [capabilities] would betray any read.
 *
 * [wovenSeamCapabilityIsHonest] is a **flag-selected** obligation — a third kind. It reads
 * [SeamCapabilities.reportsLiveCapability] (so it is not core) but never early-returns: the flag
 * picks which assertion applies, so no capability value can make it vacuous.
 *
 * Only the capability-specific obligations gate in-body on their **own** flag:
 *  - [incomingCompletesWhenSeamCloses] ↔ [SeamCapabilities.terminatesIncomingOnClose]
 *  - [stateStaysTornAfterClose] ↔ [SeamCapabilities.staysTornAfterClose]
 *  - [sendOnTornSeamThrows] ↔ [SeamCapabilities.throwsOnSendToTorn]
 *  - [sendToDeliversToNamedPeer] ↔ [SeamCapabilities.supportsSendTo]
 *  - [peersCollapseToSelfIdWhenTorn] ↔ [SeamCapabilities.collapsesPeersOnTear]
 *  - [survivorStopsAdvertisingADepartedPeer] ↔ [SeamCapabilities.reportsPeerLoss]
 *
 * Every flag on [SeamCapabilities] now appears in that list or is a selector ([SeamCapabilities.reportsLiveCapability],
 * above) — with one deliberate exception, [SeamCapabilities.securesTransport], which is a standing
 * *declaration* no property can read because the suite has no wire tap. Its own KDoc argues that and
 * names what holds a fabric to it instead. Keeping the set closed is the point: publishing a
 * capability value subscribes a fabric to every case selected on it, so a flag with **zero** cases
 * charges the [everyFalseCapabilityDeclaresAGap] toll, delivers no coverage, and invites a reader
 * auditing the matrix to infer coverage that does not exist. That is what #2304 found on three flags,
 * one of which ([SeamCapabilities] no longer declares it) was actively contradicted by ungated core.
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
 * A **fourth** selects on a value the fabric already publishes rather than on any declaration:
 * [payloadOfExactlyTheBudgetIsCarried] and [overBudgetAddressedSendIsRefusedNotLeaked] run exactly
 * when [Seam.maxPayloadBytes] is non-null, because a fabric reporting `null` has made no promise to
 * keep. That is the one gating input a fabric cannot get wrong by *declaring* wrong — but it can
 * still stay silent while enforcing a ceiling internally, so the same tracked-by-default umbrella
 * applies ([payloadBudgetGap] / [payloadBudgetObligationIsTrackedWhenUnpublished], #2069). Unusually,
 * that pairing binds in **both** directions: publishing a budget requires the gap to be *cleared*,
 * so the declaration cannot be left behind as an opt-out.
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
     * Tracking URL for **why this fabric names no frame ceiling** — the accountability analog of
     * [midSessionDeathGap] for [us.tractat.kuilt.core.Seam.maxPayloadBytes] (#2069).
     *
     * The base default is a **non-null** umbrella ([CapabilityGaps.PAYLOAD_BUDGET]), so a fabric
     * that publishes nothing is *declared*, never silently green; a fabric that publishes a number
     * MUST override this to `null`/blank, and is then held to that number by
     * [payloadOfExactlyTheBudgetIsCarried] and [overBudgetAddressedSendIsRefusedNotLeaked].
     * [payloadBudgetObligationIsTrackedWhenUnpublished] enforces the pairing in **both**
     * directions.
     *
     * **This is deliberately not a [SeamCapabilities] flag**, though #2069 first asked for one.
     * Every `false` flag there is a contract *shortfall* for which [everyFalseCapabilityDeclaresAGap]
     * demands a per-fabric issue URL — but `maxPayloadBytes == null` is the *honest* answer from a
     * fabric with no wire limit to name, so a flag would have demanded a permanently-open issue from
     * nearly every subclass of this suite. The hook's shared umbrella default costs those fabrics
     * nothing while still making the one real hazard — a fabric enforcing a ceiling it does not
     * publish — a declaration somebody has to write down.
     */
    public open fun payloadBudgetGap(): String? = CapabilityGaps.PAYLOAD_BUDGET

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
                // can't hang every conformance test; the `try`/`catch` tolerates a close error. A timeout or
                // error here is deliberately swallowed — teardown is best-effort, and any real close bug
                // surfaces in the dedicated close-obligation tests, not here.
                //
                // The guard sits OUTSIDE the bound and is a plain `catch (Throwable)`, not a
                // `runCatchingCancellable` inside it (#1803). `withTimeoutOrNull` already absorbs its OWN
                // timeout; what it rethrows is a `CancellationException` the fabric's `close` minted itself
                // (`e.coroutine !== coroutine`). Inside the shield ours is never cancelled, so that is the
                // only kind reachable — and letting it out would escape the whole `NonCancellable` block,
                // skip the second close, and throw from this `finally`, masking the test's real failure.
                withContext(NonCancellable) {
                    try {
                        withTimeoutOrNull(2.seconds) { host.close() }
                    } catch (_: Throwable) {
                        // Best-effort: the joiner below must still be torn down.
                    }
                    try {
                        withTimeoutOrNull(2.seconds) { joiner.close() }
                    } catch (_: Throwable) {
                        // Best-effort: teardown must never fail the test it is cleaning up after.
                    }
                }
            }
        }
    }

    /**
     * Assert `selfId ∈ peers` on a live (non-[SeamState.Torn]) [seam] for the whole test. Because `peers`
     * is a [kotlinx.coroutines.flow.StateFlow], this observes the latest value at each resumption, not
     * every write — a *persistent* live-seam self-eviction is reliably caught; a transient drop overwritten
     * before the collector resumes may be missed (see the class KDoc).
     *
     * **Scoped to non-Torn, and that scope is now narrower than it looks.** A Torn seam has left the
     * session, so this monitor says nothing about it — but [peersCollapseToSelfIdWhenTorn] does, and it
     * requires the *stronger* thing: a torn seam's roster is exactly `{ selfId }`, so `selfId` is in it
     * there too. What the scope actually buys is only that a fabric whose `peers` is a shared
     * mesh-registry view may drop the departed member from **other** seams' rosters, and that the two
     * obligations do not fight over the instant of the tear. (Until #1849 the reference `InMemoryLoom`
     * dropped `selfId` from its own torn seam's roster and this scope was what tolerated it; it no
     * longer does, and every fabric declaring `collapsesPeersOnTear = false` is a tracked bug.)
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
    //
    // UNGATED CORE, and there is deliberately **no capability flag** for it (#2304). There used to be
    // one — `SeamCapabilities.ordersDelivery`, "FIFO to a single collector" — read by nothing, while
    // this obligation sat here in the ungated block. The contradiction was not merely cosmetic: a
    // fabric that declared `ordersDelivery = false` and supplied a gap URL, the whole documented
    // workflow for a shortfall, was still held to this property and still failed — so the flag's only
    // legitimate value was unreachable, and declaring it bought a permanently-open tracking issue and
    // nothing else. The flag was deleted rather than this obligation moved out of core, because
    // ordering is a property of the `Seam.incoming` CONTRACT rather than a transport-shaped
    // limitation a fabric may honestly lack: `incoming` is one flow of frames from one session, and a
    // consumer that must reorder them has been handed a different data structure. No in-tree fabric
    // declared it `false`. A fabric that cannot deliver in order is non-conforming, full stop.

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

    // ── (10a) sendTo SELF is refused with IllegalArgumentException ───────────
    //
    // Contract from `Seam.sendTo`'s KDoc (#2428): an addressed send to this peer's own id is
    // refused with `IllegalArgumentException`. A self-send has no meaning at this layer —
    // `broadcast` is the loop-back surface — and the refusal is `require`, not [PeerNotConnected],
    // because `selfId` IS in `peers`: reporting the peer as absent would state something false.
    //
    // **The complement of `runSendToAbsentPeerThrows`, and the reason it did not cover this.** That
    // obligation addresses an id no fabric has ever heard of, so every implementation reaches its
    // roster miss and reports it. `selfId` passes the roster check on every fabric that keeps one,
    // and what happened next was whatever the implementation happened to do — three fabrics
    // (including the reference `InMemoryLoom`) refused with `require`, the rest did anything from a
    // misdelivery to the *other* peer (`LinkSeam`, `WebRTCPeerLink` — a 2-peer link resolves
    // "somebody" to the remote) to a false [PeerNotConnected] on a peer `peers` names. Both readings
    // were defensible against a contract that did not say, and nothing in this suite asked, so the
    // fabrics drifted apart in silence for as long as there have been fabrics.
    //
    // UNGATED CORE, deliberately, and #2428 turned down a capability flag for it. Every `false` flag
    // is an *opt-out*, and an opt-out is exactly the shape that let this drift: the first fabric that
    // found the guard inconvenient would declare the gap and the divergence would be back, tracked
    // instead of fixed. There is also no transport for which refusing is impossible — the check
    // reads two ids the seam already holds and never touches the wire.
    //
    // **Both ends are checked.** A role-split fabric is two different implementations behind one
    // suite (websocket hosts a `MeshSeam` and joins a `LinkSeam`), so asserting only on `host`
    // proves at most half of what the harness under test actually ships.
    //
    // **The preconditions are asserted, not assumed.** A refusal is a *throw*, and a seam that is
    // not live throws for reasons of its own — a `Torn` seam throws `IllegalStateException` from the
    // state check, and a harness whose pair never wove could satisfy this assertion without the
    // self-check existing at all. Pinning `Woven` and `selfId ∈ peers` first makes the rig prove it
    // fired.

    internal suspend fun runSendToSelfIsRefused(scope: TestScope): Unit =
        scope.connectedPair { host, joiner ->
            // Preconditions FIRST, and separately from the obligation: a green below means the
            // refusal was reached on a live seam whose roster really does name `selfId`.
            assertIs<SeamState.Woven>(
                host.state.first { it is SeamState.Woven },
                "precondition: the host must be Woven — a Torn seam refuses every send with " +
                    "IllegalStateException, which would satisfy nothing this obligation asks",
            )
            assertIs<SeamState.Woven>(
                joiner.state.first { it is SeamState.Woven },
                "precondition: the joiner must be Woven, for the same reason as the host",
            )
            assertAll(
                {
                    assertTrue(
                        host.selfId in host.peers.value,
                        "precondition: selfId must be IN peers (Seam.peers' initial-value invariant) — " +
                            "that is what makes this refusal distinct from PeerNotConnected; got " +
                            "${host.peers.value.map { it.value }}",
                    )
                },
                {
                    assertTrue(
                        joiner.selfId in joiner.peers.value,
                        "precondition: the joiner's selfId must be IN peers too; got " +
                            "${joiner.peers.value.map { it.value }}",
                    )
                },
            )

            // Captured rather than wrapped in `assertFailsWith`: [assertAll] takes plain (non-inline,
            // non-suspend) lambdas, so the suspending send has to happen out here. That is the better
            // shape anyway — a seam that *completes* the self-send yields `null`, which reads as
            // "completed successfully" instead of as an opaque wrong-type mismatch.
            val hostFailure = failureOf { host.sendTo(host.selfId, byteArrayOf(1)) }
            val joinerFailure = failureOf { joiner.sendTo(joiner.selfId, byteArrayOf(2)) }

            assertAll(
                {
                    assertIs<IllegalArgumentException>(
                        hostFailure,
                        "sendTo(selfId) must be REFUSED with IllegalArgumentException on the host — " +
                            "not delivered, not dropped, and not reported as PeerNotConnected " +
                            "(selfId IS in peers, so that would state something false). Use broadcast " +
                            "to loop back. Got: ${hostFailure ?: "no exception — the send completed"}",
                    )
                },
                {
                    assertIs<IllegalArgumentException>(
                        joinerFailure,
                        "sendTo(selfId) must be REFUSED with IllegalArgumentException on the joiner " +
                            "too — a role-split fabric ships a different Seam on each end, so the " +
                            "host's guard proves nothing about this one. Got: " +
                            "${joinerFailure ?: "no exception — the send completed"}",
                    )
                },
            )
        }

    /**
     * Run [block] and hand back whatever it threw, or `null` if it completed.
     *
     * `ensureActive()` is the discriminator the repo's exception discipline asks for: it rethrows
     * only when *this* coroutine really is cancelled, and falls through on a `CancellationException`
     * the seam minted itself — which `Seam.sendTo` forbids, and which must therefore be *reported*
     * as the wrong exception type rather than silently cancelling the suite.
     */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            failure
        }

    @Test
    public fun sendToSelfIsRefused(): TestResult =
        runTest { runSendToSelfIsRefused(this) }

    // ── (10b) close must not report a failure as a cancellation ──────────────
    //
    // Contract from `Seam.close`'s KDoc (#1826), the same obligation `sendTo`/`broadcast`/`Loom.weave`
    // carry: an implementation must not let a `CancellationException` out of `close` unless it is
    // signalling the CALLER's own cancellation. A caller cannot tell the two apart by type, so a
    // callee-minted one *cancels* the caller rather than failing it — no handler, no stack trace — and
    // every best-effort teardown loop in the tree stops at the first such peer, leaking the rest.
    //
    // UNGATED CORE: there is no fabric for which minting a cancellation is a legitimate limitation. The
    // trap is a *choice* (`withTimeout` instead of `withTimeoutOrNull` plus an explicit throw), not a
    // property of a transport, so no capability flag may excuse it.
    //
    // **Honest limit:** this proves the obligation on the paths a 2-peer harness can reach — a live
    // close, the idempotent second close, and a close whose remote has already gone (the one most
    // likely to be bounded by a timeout). It cannot reach a close racing a genuinely wedged transport.

    internal suspend fun runCloseDoesNotReportFailureAsCancellation(scope: TestScope): Unit =
        scope.connectedPair { host, joiner ->
            assertNoMintedCancellation("close() on a live seam") { host.close() }
            assertNoMintedCancellation("a second, idempotent close()") { host.close() }
            // The joiner's remote is already gone: bounding teardown on a peer that will never answer
            // is exactly where an implementation reaches for `withTimeout`.
            assertNoMintedCancellation("close() after the remote end has gone") { joiner.close() }
        }

    @Test
    public fun closeDoesNotReportFailureAsCancellation(): TestResult =
        runTest { runCloseDoesNotReportFailureAsCancellation(this) }

    /**
     * Run [op] and fail — rather than be cancelled — if it reports its failure as a cancellation.
     *
     * The discrimination is the whole point, and it is done by *state*, not by type: a
     * `CancellationException` caught here is this coroutine's own only if this coroutine is in fact
     * cancelled, which [kotlinx.coroutines.ensureActive] is the exact test for. If it is ours it
     * propagates (never swallow a structured-concurrency cancel); if it is not, [op] minted it, and
     * rethrowing would inflict on this test precisely the damage the obligation forbids — a silent
     * cancel with no assertion failure and no stack trace. So it is converted to a failure instead.
     */
    private suspend inline fun assertNoMintedCancellation(what: String, op: () -> Unit) {
        try {
            op()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            fail(
                "$what must not report failure as a cancellation (Seam.close, #1826): a caller cannot " +
                    "distinguish it from its own cancellation, so it CANCELS the caller instead of " +
                    "failing it — every best-effort teardown loop stops there. Convert it before it " +
                    "escapes (withTimeoutOrNull plus an explicit throw). Got: $e",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Flag-SELECTED obligation — reads a capability flag, but NEVER skips.
    //
    //  A third kind, distinct from both neighbours. Unlike a core obligation it
    //  *does* consult `capabilities()`, so it cannot live in the ungated block
    //  above (whose invariant — pinned by the hostile harness in
    //  [SeamConformanceUngatedCoreTest] — is that those bodies never read the
    //  flags at all). Unlike a capability-gated obligation it never early-returns:
    //  the flag chooses WHICH assertion applies, so no capability value can make
    //  it vacuous, and it is correspondingly absent from that test's `runAllCore`.
    // ─────────────────────────────────────────────────────────────────────────

    // ── (6b) live capability is honest about whether it is observed ─────────

    internal suspend fun runWovenSeamCapabilityIsHonest(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            if (capabilities().reportsLiveCapability) {
                // A fabric claiming a live observer must REACH a real verdict, so AWAIT one rather than
                // sample: a real OS path monitor (`NWPathMonitor`) reports asynchronously from a cold
                // "nothing observed yet", so the value at this instant may legitimately still be the
                // Unknown floor. What must not happen is that it stays there forever.
                //
                // **The await IS the assertion, and the timeout is how it fails.** There is deliberately no
                // trailing assert: `first { }` already guarantees its predicate, so any assert after it
                // would be unreachable. A fabric whose observer never fires hangs here until `runTest`'s
                // own 60 s wall-clock timeout fails the test.
                //
                // That bound is implicit ON PURPOSE — an explicit `withTimeout` here would be WRONG. The
                // suite body runs on `runTest`'s virtual clock, so a virtual-time timeout fast-forwards and
                // fires without any real time passing, spuriously failing exactly the fabric this branch
                // exists for (a real monitor delivering on an OS queue). Waiting unbounded is what lets real
                // time elapse; `stateIsWovenAfterConnect` awaits the same way for the same reason. Only
                // `NwLoopbackConformanceTest` reaches this against a real observer, so a stalled macOS
                // runner is the worst case: one test, 60 s, then a hard failure — not a silent pass.
                host.capability.first { it.availability !is FabricAvailability.Unknown }
            } else {
                // No observer ⇒ the floor is the answer NOW; there is nothing to wait for, and a sample
                // is what catches a fabric fabricating a verdict it cannot have.
                val availability = host.capability.value.availability
                assertTrue(
                    availability is FabricAvailability.Unknown,
                    "a fabric with no live path observer must report Unknown, not a fabricated verdict, got $availability",
                )
            }
        }

    @Test
    public fun wovenSeamCapabilityIsHonest(): TestResult =
        runTest { runWovenSeamCapabilityIsHonest(this) }

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
    //
    // That last clause used to be worth exactly as much as the harness coverage behind it, and it
    // was wrong for months (#2444): #1390 fixed the Multipeer *JVM* bridge, and `MultipeerConformanceTest`
    // is a `jvmTest`, so the Apple `MCSessionLink` that actually ships to iPhones was never reached
    // by any harness and never got the guard. It read "every fabric passes" while meaning "every
    // fabric a harness can see passes". Binding the Apple link (#2441) is what made the claim
    // testable, and #2444 is what made it true again — the #1871 shape.
    //
    // Note also what this obligation cannot distinguish on a fabric whose `sendTo` reports an absent
    // addressee with `PeerNotConnected`: that type IS an `IllegalStateException`, so the second
    // assertion below passes on a seam that blames the peer for its own death. Where the two are
    // worth telling apart, a fabric-local test has to assert the identity of the throwable
    // (`MCSessionLinkTornSendTest` is the in-tree example).

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

    // ── (13e) a Torn seam's peers collapses to { selfId } ────────────────────
    //
    // Contract from `Seam.peers`' KDoc (#1816): a torn fabric can reach nobody, so a `Torn` seam's
    // `peers` must be exactly `{ selfId }`. Until this was stated, what closed the gap was a
    // *convention* — `LinkSeam` and `MeshSeam` happen to collapse before latching, so `CompositeSeam`'s
    // reachability fold (whose only liveness test is "is this ply still attached") never saw a torn
    // member contributing peers. A fabric that tears without collapsing leaves the composite
    // advertising a peer only `sendTo` can disprove, by throwing `PeerNotConnected` for a peer `peers`
    // calls reachable.
    //
    // **Only the terminal value is asserted, deliberately.** The contract asks for the collapse to be
    // published before (or atomically with) the `Torn` latch, so a consumer woken by the terminal state
    // already sees it — but `peers` is a StateFlow, which conflates against each collector's
    // last-observed value, so no collector can reliably witness which write landed first. An ordering
    // assertion here would be unreliable in both directions; the terminal value is what the fold
    // actually reads, and it is exactly observable.
    //
    // The two ways a fabric deviates are asserted SEPARATELY, because they have different causes and
    // different fixes: still advertising a remote peer (a frozen pre-tear roster) versus having dropped
    // `selfId` (a shared session registry the closing seam removes itself from, or a collapse to
    // `emptySet()`). A single set-equality assertion would report both as one opaque mismatch.
    //
    // Gated on `collapsesPeersOnTear`; every `false` is a tracked bug, not a by-design gap.

    @Test
    public fun peersCollapseToSelfIdWhenTorn(): TestResult =
        runTest {
            if (!capabilities().collapsesPeersOnTear) return@runTest
            connectedPair { host, _ ->
                assertTrue(host.peers.value.size >= 2, "precondition: the pair must be connected before the tear")

                host.close()
                assertIs<SeamState.Torn>(host.state.value, "precondition: close() must latch Torn")

                val peers = host.peers.value
                assertAll(
                    {
                        assertEquals(
                            emptySet(),
                            peers - host.selfId,
                            "a Torn seam must advertise NO reachable remote peer — a torn fabric can reach " +
                                "nobody, and a decorator folding this seam (CompositeSeam) reads what is left " +
                                "here as still reachable until the member is detached",
                        )
                    },
                    {
                        assertTrue(
                            host.selfId in peers,
                            "a Torn seam's collapsed roster is { selfId }, not empty: peers always includes " +
                                "this peer's own id, so a seam that drops selfId on tear has collapsed too far " +
                                "(got ${peers.map { it.value }})",
                        )
                    },
                )
            }
        }

    // ── (13f) a survivor stops advertising a peer that has departed ──────────
    //
    // The obligation `SeamCapabilities.reportsPeerLoss` names ("peer-drop reflected in peers/state"),
    // which until #2303/#2304 no property read — the flag was a free declaration every fabric set
    // `true` while nothing held anyone to it. It is also the one departure event **every** harness can
    // reach with no injection at all: `peersDrainWithoutTearOnInjectedMembershipDrain` needs the
    // `injectMembershipDrain` hook, which 3 of 19 subclasses override — all three of them in-process
    // shared-roster harnesses — so every real fabric skipped the only neighbouring obligation.
    // `peersCollapseToSelfIdWhenTorn` does not cover it either: that closes the seam **being
    // inspected**. Nothing closed one end and then looked at the other while it was still live.
    //
    // **The assertion is a disjunction, and the naive form is wrong.** Three conforming shapes exist:
    // an N-peer mesh drops the peer and stays Woven; a strictly-2-peer link latches Torn (losing its
    // only link IS a tear); `NwSeam` treats peer loss as *recoverable* and re-forms Woven→Weaving
    // rather than tearing (see `connectedPair`'s teardown comment). "Drops the peer **or** latches
    // Torn" would red-light the third for behaving correctly. What all three share is the negative,
    // and it is what `Seam.peers`' own KDoc already requires — *a peer in `peers` must be addressable
    // by `sendTo`* — so a survivor must not sit at Woven advertising a peer `sendTo` would refuse.
    //
    // **The precondition is what stops the disjunction being satisfied by the state it started in.**
    // A survivor advertising no remote peer at all would satisfy the roster arm from the first instant,
    // and one that was never Woven would satisfy the state arm — in both cases green without the
    // departure having caused anything. Asserting both up front makes the only way to satisfy the
    // disjunction a transition the departure produced.
    //
    // **Which peer must leave is decided from the survivor's own roster, and two naive answers are
    // both wrong.** Keying the arm to `joiner.selfId` red-lights a fabric that labels a peer
    // provisionally and reconciles the real id asynchronously: `WebRTC`'s host advertises a
    // locally-minted `peer-…` until the ID exchange completes, so `joiner.selfId in host.peers` is
    // simply false at the instant the pair connects. Keying it instead to "the survivor advertises no
    // remote at all" red-lights a fabric whose roster is legitimately wider than the joiner:
    // `TieredSeam`'s union spans two disjoint tiers, so its host keeps a sibling server after the
    // joiner departs, and must.
    //
    // So the target is the strongest statement the survivor's own roster supports — `{joiner.selfId}`
    // when the survivor names the joiner by that id, and otherwise the whole set of remotes it was
    // advertising, of which at least one must go. That conditional can only *tighten* the obligation,
    // never loosen it, which is why it is not a knob choosing the vacuous case: the weak arm is taken
    // exactly when the strong arm is unstatable. Every in-tree harness lands on an exact obligation —
    // the multi-remote one (tiered) names the joiner, and the provisionally-labelling one (webrtc) has
    // exactly one remote, so "some remote left" *is* "the joiner left".
    //
    // The residual hole is a fabric that is BOTH provisionally-labelling AND multi-remote: it could
    // satisfy the arm by dropping the wrong peer. None exists in tree, and N-peer peer-leave is
    // `MeshConformanceSuite`'s coverage rather than this suite's, which ADR-001 fixes at two Looms.
    //
    // **Unbounded, and that is the shape of its red.** Like every other delivery obligation here
    // (`payloadOfExactlyTheBudgetIsCarried`, `wovenSeamCapabilityIsHonest`'s live arm), the await IS
    // the assertion and `runTest`'s ceiling is the backstop. An inner `withTimeout` would be measured
    // in VIRTUAL time, which a real-IO fabric does not advance: the clock would jump the whole bound
    // while the socket was still carrying the disconnect, failing a fabric that was working fine.
    //
    // **What it cannot detect,** stated because the arms hide different things:
    //  - it is an *eventual* obligation, so it cannot bound how long a survivor may advertise a
    //    departed peer — only that it must stop;
    //  - the state arm accepts *any* departure from Woven, so a fabric that leaves Woven and keeps the
    //    departed peer in `peers` forever would pass. That is the price of not red-lighting a
    //    recoverable re-form, and it is narrow rather than free: all three conforming shapes reach a
    //    STABLE satisfying value (the two mesh shapes drop the peer, the 2-peer shape latches Torn), so
    //    the `combine` below cannot lose the verdict to StateFlow conflation either.
    //
    // Gated on `reportsPeerLoss`; every `false` is a tracked bug, not a by-design gap — a fabric that
    // keeps a departed peer reachable-looking is lying to every consumer that reads `peers`.
    //
    // ## Mutation receipt (#2304)
    //
    // JVM, `--rerun`/source-changed so every row EXECUTED. "pre-existing" is the same mutation with
    // this property removed from the picture — it measures the HOLE rather than asserting it. **real**
    // = a defect that could ship; **rig** = a mutation of this obligation itself, checking each arm is
    // load-bearing.
    //
    // | # | Mutation | Kind | this property | pre-existing suite |
    // |---|----------|------|---------------|--------------------|
    // | 1 | `InMemoryLoom.remove` stops removing the peer from the shared roster | real (reference) | RED — in-memory, tiered, composite (roster arm) | RED — `peersDrainWithoutTear…` ×2, `MeshConformanceSuite.peerLeaveUpdatesSurvivorRosters` ×2 |
    // | 2 | `BridgePeerLink` keeps the peer on `.notConnected` | real (fabric) | RED — multipeer | RED — 4 × `MultipeerPeerLinkFactoryJvmTerminalDropTest` |
    // | 3 | the multipeer fake's `mc_session_close` back to `= Unit` (its pre-#2304 body) | real (harness) | **RED — multipeer, 1 of 67** | **green — all 66** |
    // | 4 | `MeshSeam.publishRosters` makes `peers` grow-only | real (fabric) | **green** | RED — 12 across `:kuilt-core` + `:kuilt-conformance` |
    // | 5 | the mux-hub harness un-declares its `reportsPeerLoss` gap | rig | RED in 0.004 s — the Torn precondition, naming the cause | green |
    // | 6 | roster arm deleted from the disjunction | rig | RED — in-memory, tiered, composite, gossip | green |
    // | 7 | state arm deleted from the disjunction | rig | **green everywhere** | green |
    //
    // **Row 3 is the argument.** A fake whose session-close told nobody could not represent a peer
    // departure at all, and the entire pre-existing suite was green against it — the hole this
    // obligation closes, measured rather than claimed. Rows 1 and 2 are blast radius, not diagnosis:
    // both defects were already caught elsewhere, and the rows say so.
    //
    // **The green cells are the interesting ones, and rows 4 and 7 are one fact seen twice.** The
    // state arm never carries a verdict in tree (row 7), because every in-tree fabric that tears on
    // peer loss also collapses its roster — so a roster-only defect on a *tearing* fabric is invisible
    // here (row 4). That is correct rather than a shortfall: a survivor that latches Torn while
    // freezing its pre-tear roster is exactly `SeamCapabilities.collapsesPeersOnTear`'s shortfall, and
    // dropping the arm would make this obligation red for a gap another flag already owns and another
    // property (`peersCollapseToSelfIdWhenTorn`) already asserts. N-peer roster shrinkage is
    // `MeshConformanceSuite`'s, which row 4 shows holding.
    //
    // Two more assertions have no red in any row: the two "before" preconditions. That is correct and
    // not a gap — neither describes behaviour under test, and the only thing either can catch is a
    // harness handing back a pair that is not actually connected, which is exactly what they exist
    // for. The third precondition is the one that does describe behaviour, and row 5 is its red.

    @Test
    public fun survivorStopsAdvertisingADepartedPeer(): TestResult =
        runTest {
            if (!capabilities().reportsPeerLoss) return@runTest
            connectedPair { host, joiner ->
                val remotesBefore = host.peers.value - host.selfId
                assertAll(
                    {
                        assertTrue(
                            remotesBefore.isNotEmpty(),
                            "precondition: the survivor must advertise a remote peer BEFORE the departure, " +
                                "or the roster arm below is satisfied from the first instant and asserts " +
                                "nothing (host peers: ${host.peers.value.map { it.value }}, " +
                                "host selfId: ${host.selfId.value})",
                        )
                    },
                    {
                        assertIs<SeamState.Woven>(
                            host.state.value,
                            "precondition: the survivor must be Woven BEFORE the departure, or the state arm " +
                                "below is satisfied from the first instant and asserts nothing",
                        )
                    },
                )

                joiner.close()

                // The THIRD precondition, and the one that turns this obligation's worst red — an
                // unbounded wedge — into a named diagnosis. The two above establish the survivor's
                // starting state; this establishes that the event under test actually happened. A seam
                // whose `close()` is a purely local unsubscribe never reaches the transport, so no peer
                // departs, and the survivor is then *correctly* still advertising it — the await below
                // would hang to `runTest`'s ceiling reporting nothing but a timeout. A mux CHANNEL VIEW
                // is exactly that seam (`MuxBase.ChannelView.close` closes its own spool while `state`
                // and `peers` keep delegating to the live base), which is why the mux-hub harness
                // declares this obligation a gap rather than passing it.
                //
                // It is an assertion rather than an early-return on purpose: "did the joiner tear?" is
                // precisely the condition a fabric could use to opt out of the whole property, and
                // `closeDrivesStateTornNormal` already makes it UNGATED CORE — so a joiner that does not
                // latch Torn here is non-conforming twice over, not exempt.
                assertIs<SeamState.Torn>(
                    joiner.state.value,
                    "precondition: the departing peer must genuinely have left — close() latches Torn " +
                        "(ungated core, closeDrivesStateTornNormal). This joiner did not, so its close() " +
                        "was a local unsubscribe the survivor could never observe and nothing below " +
                        "would be testing the survivor's reaction to a departure",
                )

                // `{joiner.selfId}` when the survivor names the departing peer by that id, otherwise
                // every remote it was advertising — see the block comment: the conditional takes the
                // weaker target only where the stronger one is unstatable, so it can never loosen the
                // obligation for a fabric that could have met the stronger one.
                val mustLeave =
                    if (joiner.selfId in remotesBefore) setOf(joiner.selfId) else remotesBefore

                host.peers.combine(host.state) { peers, state ->
                    mustLeave.any { it !in peers } || state !is SeamState.Woven
                }.first { it }
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

    // ── (19) a published payload budget is a promise, and it is kept at BOTH edges ──
    //
    // Value-selected, not capability-gated: the selector is `maxPayloadBytes` itself. A fabric that
    // reports `null` has made no promise, so there is nothing here to assert — its accountability is
    // [payloadBudgetGap], below. A fabric that reports a number is held to it exactly, on both sides
    // of the edge, because each side catches a different lie: too-large-a-number is caught by the
    // at-budget send, and published-but-unenforced by the over-budget one (#2069).

    /**
     * A payload of **exactly** [Seam.maxPayloadBytes] crosses. Sent with `broadcast` so the
     * obligation does not also depend on [SeamCapabilities.supportsSendTo].
     *
     * This is the edge a fabric gets wrong by publishing a number bigger than its wire really takes
     * — the frame is then refused by the fabric's own machinery, at a limit the caller could not see
     * and did not agree to. `null` means unknown, so a fabric that names nothing skips: it promised
     * nothing to break.
     */
    @Test
    public fun payloadOfExactlyTheBudgetIsCarried(): TestResult =
        runTest {
            connectedPair { host, joiner ->
                val budget = host.maxPayloadBytes ?: return@connectedPair
                val atBudget = ByteArray(budget) { (it % PAYLOAD_FILL_MODULUS).toByte() }
                val received = async { joiner.incoming.first() }

                host.broadcast(atBudget)

                // Unbounded, like every other delivery obligation here: `runTest`'s own ceiling is the
                // backstop. An inner `withTimeout` would be measured in VIRTUAL time, which a real-IO
                // fabric does not advance — the clock jumps the whole bound while the socket is still
                // carrying the frame, and the obligation fails on a fabric that was working fine.
                val swatch = received.await()
                assertAll(
                    {
                        assertEquals(
                            budget,
                            swatch.payloadSize,
                            "a payload of exactly maxPayloadBytes ($budget B) must cross whole — the " +
                                "number is a promise, not a hint",
                        )
                    },
                    {
                        // The fill is non-uniform, so a truncate-and-zero-pad cannot pass the size
                        // check above by accident: the last byte is the one such a fabric loses.
                        assertEquals(
                            atBudget[budget - 1],
                            swatch.byteAt(budget - 1),
                            "the payload's last byte must survive, not be zero-padded back to length",
                        )
                    },
                )
            }
        }

    /**
     * A payload **one byte over** the budget is refused by the seam with
     * [us.tractat.kuilt.core.PayloadTooLarge], not leaked as the fabric's own frame error.
     *
     * The distinction is the whole point of publishing a budget: [PayloadTooLarge] names the number
     * the caller should have respected, whereas a fabric-level error names a limit the caller had no
     * way to read — and, in the two in-tree fabric seams before #2069, arrived only *after* the send
     * had reported success, having torn the seam down or evicted a healthy peer on the way.
     *
     * Gated on [SeamCapabilities.supportsSendTo]: `broadcast` is best-effort and *drops* an
     * over-budget payload by contract, so only the addressed send has a refusal to observe.
     */
    @Test
    public fun overBudgetAddressedSendIsRefusedNotLeaked(): TestResult =
        runTest {
            connectedPair { host, joiner ->
                val budget = host.maxPayloadBytes ?: return@connectedPair
                if (!capabilities().supportsSendTo) return@connectedPair
                // A budget at Int.MAX_VALUE has no representable "one byte over" to test.
                if (budget == Int.MAX_VALUE) return@connectedPair

                val refusal = assertFailsWith<PayloadTooLarge>(
                    "a seam that publishes maxPayloadBytes ($budget B) must refuse one byte more " +
                        "with PayloadTooLarge, not let its fabric's own frame error out",
                ) {
                    host.sendTo(joiner.selfId, ByteArray(budget + 1))
                }
                assertEquals(
                    budget,
                    refusal.budgetBytes,
                    "the refusal must name the same budget the seam publishes",
                )
            }
        }

    // ── (20) an unpublished payload budget must be declared, not silently absent ──
    //
    // The accountability analog of [midSessionDeathObligationIsTrackedWhenUnproven], and the reason
    // the two obligations above may skip. It binds in BOTH directions, which is what stops the gap
    // from becoming a way to opt out: a fabric that publishes nothing must name a tracking URL, and
    // a fabric that publishes a number must NOT — so a gap left in place while a budget appears
    // fails here rather than quietly excusing a fabric already under obligation.

    @Test
    public fun payloadBudgetObligationIsTrackedWhenUnpublished(): TestResult =
        runTest {
            connectedPair { host, _ ->
                if (host.maxPayloadBytes == null) {
                    assertFalse(
                        payloadBudgetGap().isNullOrBlank(),
                        "a fabric that names no frame ceiling must be declared: override " +
                            "payloadBudgetGap() with a tracking URL, or publish Seam.maxPayloadBytes",
                    )
                } else {
                    assertTrue(
                        payloadBudgetGap().isNullOrBlank(),
                        "this fabric publishes maxPayloadBytes (${host.maxPayloadBytes}), so it is " +
                            "under obligation, not tracked as a gap: override payloadBudgetGap() to null",
                    )
                }
            }
        }

    private companion object {
        /** Non-uniform fill for the at-budget payload, so a truncation cannot pass as a zero-fill. */
        const val PAYLOAD_FILL_MODULUS = 251
    }
}
