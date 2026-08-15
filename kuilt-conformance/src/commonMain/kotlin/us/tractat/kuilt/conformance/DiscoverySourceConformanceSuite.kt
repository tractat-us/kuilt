package us.tractat.kuilt.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a source's leave signal is, for the one peer this suite drives through arrival and
 * departure.
 *
 * Two arms rather than a nullable "cause a departure" hook, because the absent case is not an
 * *absence* of information — it is a claim, and a checkable one. A `null` hook would say "I cannot
 * reach this state" in a value nobody has to defend, and every property keyed on it would go green
 * by not running: the vacuity would simply move one level up, where it is harder to see. Declaring
 * [NoLeaveSignal] instead subscribes the source to an obligation of its own — it must then emit
 * **nothing**, ever, including when a peer arrives.
 *
 * Modelled on `BoltConformanceSuite`'s `DurabilityFixture`. As there, both arms are states a source
 * may legitimately be in: a transport with no leave signal is not broken, it is limited, and that
 * limitation is exactly what `discoveryRoster`'s ghost caveat is about.
 */
public sealed interface DepartureFixture {

    /**
     * The source has a real leave signal, and [cause] makes **the peer that
     * [DiscoverySourceConformanceSuite.causeArrival] brings in** leave.
     *
     * The two must name the same peer: this suite's headline property compares the key
     * `departures()` emits against the [Tag.peerKey] `discoveries()` emitted, and a fixture whose
     * arrival and departure are different peers would fail it for a reason that has nothing to do
     * with the source under test.
     *
     * [cause] is called after [DiscoverySourceConformanceSuite.causeArrival] **returns**, and must
     * rest only on state `causeArrival` itself established.
     *
     * It must **not** rest on the arrival having been *observed*. It is tempting to think it can —
     * `departureKeyEqualsThePeerKeyThatWasDiscovered` does hold a `discoveries()` collector open
     * across the call — but `departuresEmitsWithNoConcurrentDiscoveriesCollector` deliberately
     * collects nothing else, so there the discovered [Tag] never exists. A fixture that reaches for
     * a handle only the arrival *callback* produces reds that property for a reason unrelated to
     * the defect it tests, with a message blaming the source. A binding that needs a peer handle to
     * withdraw one should have [DiscoverySourceConformanceSuite.causeArrival] capture it.
     */
    public class Emits(public val cause: suspend () -> Unit) : DepartureFixture

    /**
     * The source genuinely has no leave signal — a fixed-roster fake, a platform stub, a browse API
     * that only ever reports arrivals — and its `departures()` is therefore `emptyFlow()`.
     *
     * This arm is a **declaration, not an exemption**. It is what
     * [DiscoverySourceConformanceSuite.anArrivalIsNeverReportedAsADeparture] holds the source to,
     * and a source that declares it while emitting anything at all fails there.
     */
    public data object NoLeaveSignal : DepartureFixture
}

/**
 * Reusable contract test suite for [PeerDiscoverySource] implementations, aimed squarely at
 * `departures()` — the half of the contract that used to carry an inherited `emptyFlow()` default
 * and that four of this repo's implementations silently took.
 *
 * Subclass and implement [newSource], [causeArrival] and [departureFixture] to bind any discovery
 * source. Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`) so every
 * discovery backend can subclass it from its own test source set.
 *
 * ## The four properties, and where each is discharged
 *
 * | Property | Arm | Test |
 * |---|---|---|
 * | a departure key equals the [Tag.peerKey] `discoveries()` emitted for that peer | [DepartureFixture.Emits] | [departureKeyEqualsThePeerKeyThatWasDiscovered] |
 * | `departures()` emits with **no** concurrent `discoveries()` collector | [DepartureFixture.Emits] | [departuresEmitsWithNoConcurrentDiscoveriesCollector] |
 * | cancelling the collector's scope completes the collection, and the source stays re-collectable | both | [cancellingTheCollectorScopeCompletesDepartures] |
 * | an arrival is never reported as a departure — for a [DepartureFixture.NoLeaveSignal] source, nothing ever is | both | [anArrivalIsNeverReportedAsADeparture] |
 *
 * **Adding a property here is an edit to this file *and* to two others.** Most bindings subclass
 * this suite concretely and pick a new `@Test` up for free. The two in `:kuilt-multipeer` —
 * `kuilt-multipeer/src/jvmTest/…/MultipeerDiscoverySourceConformanceTest.kt` and
 * `kuilt-multipeer/src/appleTest/…/MultipeerAppleDiscoverySourceConformanceTest.kt` — subclass it
 * *abstractly* and invoke each property by hand, which is what lets them pin the ones they fail (see
 * below). A property added here therefore runs on neither of them, **silently, with nothing red**.
 * Add the call to both in the same change.
 *
 * The first two are the ones with teeth, and each was written against a real defect:
 *
 * - **Emitting *something* is not enough.** `discoveryRoster` removes by exact key, so a source
 *   that emits a display name, a socket address, or another transport's handle leaves the same
 *   ghost as a source that emits nothing — while looking, in a log, like it works. Asserting
 *   equality against the key `discoveries()` actually published is the only form of this property
 *   that tells the two apart.
 * - **A leave signal that only runs while somebody is watching arrivals is not a leave signal.** A
 *   source whose browse session is opened by `discoveries()`, and whose departure feed is a
 *   `replay = 0` hot flow fed from that session, delivers nothing to a lone `departures()`
 *   collector. That is not a contrived collector either: `discoveryRoster` merges the two feeds,
 *   and `merge` subscribes to inner flows in separately-launched coroutines, so even a consumer
 *   collecting both can attach to the departure feed a turn late and lose the event.
 *
 * ## What this suite does not say
 *
 * Nothing here asserts *latency* — only that a departure arrives at all. Nothing observes a
 * transport listener directly, so "does not leak a listener" is checked through the only handle
 * common code has: a cancelled collection must complete, and a fresh collection afterwards must
 * still start. A source that keeps a dead registration alive but tolerates a second one passes.
 *
 * And the [DepartureFixture.NoLeaveSignal] arm proves only that the source is **honest** about
 * having no leave signal. It says nothing whatever about departures the underlying transport could
 * have reported and this implementation throws away — which is the more common and more expensive
 * bug, and the one every silent `emptyFlow()` in this repo actually is. Read that arm as "declared,
 * and not lying", never as "covered".
 *
 * ## Wiring
 *
 * ```kotlin
 * class MyDiscoverySourceConformanceTest : DiscoverySourceConformanceSuite() {
 *     override fun newSource(): PeerDiscoverySource = MySource()
 *
 *     override suspend fun causeArrival(source: PeerDiscoverySource) {
 *         (source as MySource).advertise("alice")
 *     }
 *
 *     override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
 *         DepartureFixture.Emits { (source as MySource).withdraw("alice") }
 * }
 * ```
 */
public abstract class DiscoverySourceConformanceSuite {

    /**
     * A fresh, unused source for one test.
     *
     * Non-nullable and non-defaulted on purpose: there is no "this backend cannot be built in a
     * test" opt-out, because a source nobody can construct is a source nobody can hold to any of
     * this. A binding that needs real hardware belongs behind a `-P`-gated test, not behind a hook
     * that hands back nothing.
     */
    protected abstract fun newSource(): PeerDiscoverySource

    /**
     * Make one peer appear to [source], such that its `discoveries()` emits a [Tag] for it.
     *
     * Called **after** the suite has begun collecting, so a hot source never has to replay.
     *
     * Suspending because it must not return until the peer is genuinely visible to [source] —
     * a real-I/O binding has to await its own advertising machinery here, not fire-and-forget.
     * [DepartureFixture.Emits.cause] runs immediately afterwards, and in
     * [departuresEmitsWithNoConcurrentDiscoveriesCollector] nothing else is watching, so an
     * arrival still in flight when this returns means the withdrawal races it: the property reds
     * on the fixture's own timing while reading as a fault in the source's leave signal.
     *
     * Non-nullable for the same reason as [newSource]: every property here starts from an arrival,
     * so a source that cannot be made to discover anything makes all of them vacuous — and would
     * do it *silently*, which is the shape this suite exists to remove.
     */
    protected abstract suspend fun causeArrival(source: PeerDiscoverySource)

    /**
     * Declare whether [source] has a real leave signal, and if so how to fire it — see
     * [DepartureFixture].
     *
     * Non-nullable, and a sealed two-armed value rather than a nullable lambda. A `null` would be
     * an opt-out that costs nothing to write and that no reader has to defend; the second arm is a
     * claim the suite then checks. That difference is the whole point of the fixture: an absent
     * leave signal must not be able to hide a bug by making the properties not run.
     */
    protected abstract fun departureFixture(source: PeerDiscoverySource): DepartureFixture

    /**
     * How long the suite waits for an expected event before failing with what it saw — **virtual**
     * time, `null` to wait unbounded.
     *
     * Virtual is the point: a binding whose source runs on the test scheduler takes an identical
     * trajectory on every run, so a bound over it is deterministic, while a wall-clock bound would
     * assert "this box is fast", which is false exactly when it is busy (#1739 / #1891).
     *
     * **Override to `null` if your harness does real I/O.** A source backed by a real socket or a
     * real radio does not advance the virtual clock, so `runTest` fast-forwards the whole budget
     * while the packet is still in flight and a working backend fails — kuilt #2069 / #2115. Such
     * a harness takes `runTest`'s own [TEST_WEDGE_BACKSTOP] as its backstop, losing the named
     * failure, which is the honest trade.
     *
     * That trade is sound for waits that end when the thing arrives — the backstop still bounds
     * them. It is **not** sound for the one wait that ends only when nothing arrives, so setting
     * this to `null` does not silently shorten that wait to zero: it makes [awaitQuiescence] fail
     * until you override it. See that hook.
     */
    public open val awaitBudget: Duration? = 5.seconds

    /**
     * Wait long enough that a source which was going to emit a spurious departure would have.
     *
     * This is the negative window behind [anArrivalIsNeverReportedAsADeparture], and it is a hook
     * of its own rather than a use of [awaitBudget] because the two waits fail in opposite
     * directions. A positive wait that is too short *reds* a working source — annoying, loud,
     * self-correcting. A **negative** wait that is too short *greens* a broken one, and a
     * zero-length one greens every source there is. That is the same "obligation that returns
     * silently reports PASS" shape `RoomConformanceSuite`'s #2306 closed, and it would have
     * re-entered here through a tuning knob instead of an early return: the default implementation
     * used to be `awaitBudget?.let { … }`, so the very setting this suite *prescribes* for real-I/O
     * harnesses removed the whole obligation of the [DepartureFixture.NoLeaveSignal] arm without
     * anything anywhere saying so.
     *
     * So the degradation has to be **declared**. With a virtual budget the default is right and
     * costs no wall-clock time; with [awaitBudget] `null` there is no virtual clock to burn and
     * this fails, telling the harness to supply a real one. A real-I/O override must use a
     * genuinely real-time wait — a bare `delay` under `runTest` is virtual whatever dispatcher it
     * runs on, so it would reintroduce the zero-length window it was overridden to fix:
     *
     * ```kotlin
     * // ALLOW-realDispatcher: a negative window must be real time; virtual time is fast-forwarded.
     * override suspend fun awaitQuiescence() {
     *     withContext(Dispatchers.Default) { delay(2.seconds) }
     * }
     * ```
     */
    protected open suspend fun awaitQuiescence() {
        val budget = awaitBudget ?: fail(
            "DiscoverySourceConformanceSuite: override awaitQuiescence() — a real-I/O harness " +
                "(awaitBudget == null) must supply a real-time silence window. Without one the " +
                "negative window in anArrivalIsNeverReportedAsADeparture is zero-length, and that " +
                "property — the whole obligation of the DepartureFixture.NoLeaveSignal arm — would " +
                "pass for every source, including one emitting a spurious departure a moment later.",
        )
        delay(budget)
    }

    // ── (1) the key must be the one `discoveries()` published ────────────────

    /**
     * After an arrival and then a departure, `departures()` emits a key **equal to the
     * [Tag.peerKey] of the [Tag] `discoveries()` emitted** for that peer.
     *
     * Asserts its own preconditions rather than assuming them: `discoveries()` must have produced
     * a [Tag] (else there is no key to compare against and the property means nothing), and
     * `departures()` must have produced something (else the comparison is never reached and a
     * silent source passes).
     *
     * Both feeds stay collected across the departure, deliberately. A `discoveries()` collection
     * that ended at the first [Tag] would tear down whatever session the source opened, and this
     * property would then red on a source whose only fault is the *next* one — conflating the two
     * and leaving [departuresEmitsWithNoConcurrentDiscoveriesCollector] with nothing of its own to
     * catch.
     */
    @Test
    public fun departureKeyEqualsThePeerKeyThatWasDiscovered(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val source = newSource()
            when (val fixture = departureFixture(source)) {
                is DepartureFixture.Emits -> {
                    val discoveries = watch(source.discoveries(), "discoveries()")
                    val departures = watch(source.departures(), "departures()")
                    causeArrival(source)
                    val tag = discoveries.firstValue.awaitOrFail(
                        "discoveries() emitted no Tag after causeArrival",
                        "Without a discovered Tag there is no key to compare a departure against, so " +
                            "this obligation cannot run — fix causeArrival or discoveries() first.",
                    )
                    fixture.cause()
                    val key = departures.firstValue.awaitOrFail(
                        "departures() emitted nothing after the fixture's cause ran",
                        "The fixture declared DepartureFixture.Emits, which claims a real leave signal; " +
                            "a source with none must declare DepartureFixture.NoLeaveSignal instead.",
                    )
                    assertEquals(
                        tag.peerKey,
                        key,
                        "departures() must emit the SAME key discoveries() published for that peer — " +
                            "discoveryRoster removes by exact key, so any other identifier leaves the " +
                            "peer in the roster forever while looking like a working departure",
                    )
                    discoveries.stop()
                    departures.stop()
                }

                DepartureFixture.NoLeaveSignal -> assertArrivalIsNotADeparture(source, NO_SIGNAL_ARM)
            }
        }

    // ── (2) the leave signal is not parasitic on the arrival collector ───────

    /**
     * `departures()` emits when it is the **only** thing being collected — no concurrent
     * `discoveries()` collector anywhere in the test.
     *
     * A source whose departure feed is fed from a session that `discoveries()` opens passes every
     * other property here and still delivers nothing to a lone collector.
     */
    @Test
    public fun departuresEmitsWithNoConcurrentDiscoveriesCollector(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val source = newSource()
            when (val fixture = departureFixture(source)) {
                is DepartureFixture.Emits -> {
                    val departures = watch(source.departures(), "departures()")
                    causeArrival(source)
                    fixture.cause()
                    departures.firstValue.awaitOrFail(
                        "departures() emitted nothing when collected on its own",
                        "Nothing collected discoveries() during this test. A leave signal that only runs " +
                            "while an arrival collector holds a session open is invisible to " +
                            "discoveryRoster, whose merge subscribes to the two feeds separately.",
                    )
                    departures.stop()
                }

                DepartureFixture.NoLeaveSignal -> assertArrivalIsNotADeparture(source, NO_SIGNAL_ARM)
            }
        }

    // ── (3) cancellation ends the collection and leaves the source usable ────

    /**
     * Cancelling the collector's scope completes the collection, and the source can then be
     * collected again.
     *
     * The second round is the only handle common code has on "does not leak a listener": a source
     * that left a registration behind usually refuses or wedges the next subscription. It cannot
     * see a registration that is merely orphaned but tolerant of a sibling — see the class KDoc.
     *
     * Runs on both arms unbranched. An `emptyFlow()` source completes before the cancellation
     * rather than because of it, which satisfies this trivially; that is not a gap, it is what
     * "has no leave signal" means, and that arm's real obligation is
     * [anArrivalIsNeverReportedAsADeparture].
     */
    @Test
    public fun cancellingTheCollectorScopeCompletesDepartures(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val source = newSource()
            assertCollectionEndsWithItsScope(source, "first")
            assertCollectionEndsWithItsScope(source, "second")
        }

    // ── (4) an arrival is not a departure ────────────────────────────────────

    /**
     * A peer *arriving* must not surface on `departures()`.
     *
     * On the [DepartureFixture.Emits] arm this catches a source that pipes its browse callbacks
     * into the wrong feed. On the [DepartureFixture.NoLeaveSignal] arm it is the whole of that
     * arm's obligation: a source declaring it has no leave signal must emit nothing, including
     * while something is happening.
     *
     * The negative window is [awaitQuiescence] — by default [awaitBudget] of **virtual** time,
     * which is cheap and deterministic. A harness that has no virtual clock to burn must override
     * that hook with a real one; it cannot end up with a zero-length window by omission.
     */
    @Test
    public fun anArrivalIsNeverReportedAsADeparture(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val source = newSource()
            val arm = when (departureFixture(source)) {
                is DepartureFixture.Emits -> "DepartureFixture.Emits"
                DepartureFixture.NoLeaveSignal -> NO_SIGNAL_ARM
            }
            assertArrivalIsNotADeparture(source, arm)
        }

    // ── shared assertions ────────────────────────────────────────────────────

    /** Drive an arrival past a live `departures()` collector and require silence. */
    private suspend fun assertArrivalIsNotADeparture(source: PeerDiscoverySource, arm: String) {
        val seen = mutableListOf<String>()
        val scope = childScope()
        val started = CompletableDeferred<Unit>()
        val collecting = scope.launch {
            source.departures().onStart { started.complete(Unit) }.collect { seen += it }
        }
        started.awaitOrFail(
            "departures() never began collecting",
            "The flow suspended or threw before its first collection, so nothing below could run.",
        )
        causeArrival(source)
        // The window the source has to misbehave in. Non-optional by construction — see the hook.
        awaitQuiescence()
        scope.cancel()
        collecting.join()
        assertTrue(
            seen.isEmpty(),
            "departures() emitted $seen after an arrival and no departure. An arrival is never a " +
                "departure; this source declared $arm.",
        )
    }

    /** One round of: collect `departures()`, cancel the collector's scope, require completion. */
    private suspend fun assertCollectionEndsWithItsScope(source: PeerDiscoverySource, which: String) {
        val scope = childScope()
        val started = CompletableDeferred<Unit>()
        val collecting = scope.launch {
            source.departures().onStart { started.complete(Unit) }.collect { }
        }
        started.awaitOrFail(
            "the $which departures() collection never began",
            if (which == "first") {
                "departures() must be collectable at all."
            } else {
                "departures() is a cold flow: a cancelled collection must leave the source collectable " +
                    "again, and a leaked listener registration is what usually stops it."
            },
        )
        scope.cancel()
        val budget = awaitBudget
        val ended = if (budget == null) {
            collecting.join()
            true
        } else {
            withTimeoutOrNull(budget) { collecting.join() } != null
        }
        assertTrue(
            ended && collecting.isCompleted,
            "the $which departures() collection did not complete when its scope was cancelled",
        )
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /**
     * A child scope of the running test, cancellable on its own without ending the test.
     *
     * Parented to the test's job rather than free-standing, so a failure inside a collector fails
     * the test with its own exception instead of drifting off to an uncaught-exception handler.
     */
    private suspend fun childScope(): CoroutineScope =
        CoroutineScope(coroutineContext + Job(coroutineContext[Job]))

    /**
     * A background collection that keeps running after its first element, so a source whose feed
     * is scoped to an open collection stays open. [stop] ends it; every success path must call it,
     * because the collection is a child of the test's own job and `runTest` waits for it. A failing
     * path needs no `finally`: a test body that throws cancels its children on the way out.
     */
    private class Watch<T>(val firstValue: Deferred<T>, private val scope: CoroutineScope) {
        fun stop() {
            scope.cancel()
        }
    }

    /**
     * Begin collecting [flow] in a child scope, returning once collection has actually started.
     *
     * Waiting for the start is what keeps a `replay = 0` source from dropping the event the caller
     * is about to cause: [onStart] runs immediately before the upstream is subscribed, with no
     * suspension between, so under the test scheduler the caller cannot resume until that
     * subscription has happened.
     */
    private suspend fun <T> watch(flow: Flow<T>, what: String): Watch<T> {
        val started = CompletableDeferred<Unit>()
        val firstValue = CompletableDeferred<T>()
        val scope = childScope()
        // `launch`, not `async`: nobody awaits this handle, so a flow that throws must fail the
        // test through the job hierarchy rather than sit unread inside a Deferred.
        scope.launch { flow.onStart { started.complete(Unit) }.collect { firstValue.complete(it) } }
        started.awaitOrFail(
            "$what never began collecting",
            "The flow suspended or threw before its first collection.",
        )
        return Watch(firstValue, scope)
    }

    private suspend fun <T> Deferred<T>.awaitOrFail(headline: String, hint: String): T {
        val budget = awaitBudget ?: return await()
        return withTimeoutOrNull(budget) { await() } ?: fail(
            buildString {
                appendLine("DiscoverySourceConformanceSuite: $headline")
                appendLine("  waited $budget of VIRTUAL time (DiscoverySourceConformanceSuite.awaitBudget)")
                append("  $hint")
            },
        )
    }

    private companion object {
        const val NO_SIGNAL_ARM = "DepartureFixture.NoLeaveSignal"
    }
}
