package us.tractat.kuilt.nw

import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Real-clock weave deadline for the two loopback [us.tractat.kuilt.conformance.SeamConformanceSuite]
 * runs — `NwLoopbackConformanceTest` (appleTest) and `NwBridgeLoopbackConformanceTest` (jvmTest) — to
 * inject into every [NwLoom] they build. **120 s.**
 *
 * Both suites used to inherit production's shipped 30 s ([NwLoom.DEFAULT_WEAVE_TIMEOUT] =
 * [us.tractat.kuilt.core.LoomDefaults.WEAVE_TIMEOUT]) as a *test gate*, which made a contended host
 * indistinguishable from a broken fabric (#2386). This is the test-only value; the shipped default is
 * deliberately left alone, because it is consumer-facing UX and a separate decision.
 *
 * ## This is NOT `TEST_WEDGE_BACKSTOP`'s argument
 *
 * [us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP] guards a **virtual**-time trajectory: the quantity of work
 * is fixed on every run and load can change only the *rate* at which it is retired, so a wall-clock cap
 * there asserts nothing about the code. Here the trajectory itself is load-dependent — real
 * `127.0.0.1` sockets, a real TLS-PSK handshake, real GCD queues — so the deadline is a genuine
 * property of the run, and widening it is a real (if modest) loss of sensitivity rather than a free win.
 * That is why the number below is derived from a budget instead of picked generously.
 *
 * ## The measurement it is derived from
 *
 * Nightly run `32315043065`, 3 vCPU / 7 GB at **3.34 load/core**, 30 tests per lane:
 *
 * | lane                                          | total  | max test   |
 * |-----------------------------------------------|--------|------------|
 * | local `macosArm64Test` (16-core @ 0.27/core)  | 4.10 s | 2.09 s     |
 * | **runner `macosArm64Test`**                   | 8.34 s | **5.14 s** |
 * | runner `iosSimulatorArm64Test`                | 7.48 s | 3.05 s     |
 * | runner `jvmTest` bridge                       | 4.44 s | 3.07 s     |
 *
 * Runner/local is ≈2.5× on the max test for a ~12× per-core load increase — sublinear. 120 s is ~23×
 * the worst observed single test, i.e. a deadline a *healthy* fabric cannot plausibly reach.
 *
 * ## Why 120 s is affordable, and 60 s would not have been
 *
 * The two Kotlin/Native lanes run **sequentially** (their XML timestamps are ~10 min apart), so a
 * genuinely broken fabric pays the deadline **60 times, not 30**:
 *
 * ```text
 * job timeout        60 min
 * lane already uses ~23 min   (K/N 20m05s + spike 55s + bridge 34s + setup)
 * headroom          ~37 min = 2220 s
 * worst case         60 × backstop
 * ⇒ max safe BARE backstop ≈ 37 s      (the value being replaced: 30 s)
 * ```
 *
 * So a *bare* widening past ~37 s is unshippable. At 60 s bare the worst case is 60 min on its own: the
 * job is killed at the timeout, the `always()` artifact upload never runs, and a genuine breakage yields
 * **no artifact at all** — strictly worse than the false red being fixed. What makes 120 s affordable is
 * [LoopbackWeaveFailFast], not the number: with it the worst case is `1 × 120 s + 59 fast failures` ≈
 * 2 min. **Do not raise this without redoing that arithmetic** — and do not raise it *at all* if the
 * fail-fast has been removed, because then the ~37 s bare ceiling is the real one again.
 */
val LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP: Duration = 120.seconds

/**
 * Build the host/joiner [Loom] pair a loopback conformance suite hands back from `newLoomPair()` — the
 * **only** way either suite builds one.
 *
 * ## Why a factory rather than two call sites
 *
 * The first cut of #2386 injected [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP] at two `NwLoom(…)` call sites,
 * one per suite, and *nothing reds if one of them drops the argument*: the suites pass at the shipped
 * 30 s on a healthy box, which is the entire premise of #2386. The fix was silently un-pinned on the two
 * places it was applied. Concentrating construction here leaves one call site, and
 * [LoopbackConformanceBackstopTest] drives **this function** rather than a hand-rolled loom — so the
 * test is a statement about what the suites actually do.
 *
 * It owns three things a suite must not be able to forget:
 *  - the [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP] deadline, in place of production's shipped 30 s;
 *  - the `Random(0)`/`Random(1)` symmetry-breaking seeds, so the pair's dedup tiebreak is deterministic
 *    and the two ends cannot be handed the same seed;
 *  - the [failFast] arming, without which the backstop is unaffordable — see [LoopbackWeaveFailFast].
 *
 * **Honest limit:** this makes omission *harder*, not impossible. A suite that constructs [NwLoom]
 * directly still compiles and still goes green at 30 s. What is gone is the *silent drift* of two
 * call sites; a bypass is now a visible, deliberate edit rather than a missing named argument.
 *
 * @param weaveDispatcher the context `weave` is dispatched on — **required**, never defaulted, per this
 *   repo's rule that a helper owning dispatch makes the caller choose. [NwLoom] captures its seam scope
 *   from `currentCoroutineContext()`, so the real suites pass `Dispatchers.Default` (their real sockets
 *   cannot be driven by a test scheduler) while a virtual-time test passes
 *   [kotlin.coroutines.EmptyCoroutineContext] to stay on its own scheduler. A wrong value here fails
 *   loudly and immediately, which is why it is a parameter rather than a fourth thing this owns.
 */
fun loopbackLoomPair(
    failFast: LoopbackWeaveFailFast,
    serviceType: String,
    hostApi: NwApi,
    joinerApi: NwApi,
    weaveDispatcher: CoroutineContext,
): Pair<Loom, Loom> {
    failFast.failIfAlreadyBroken()
    val host = NwLoom(
        hostApi,
        serviceType = serviceType,
        random = Random(0),
        weaveTimeout = LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP,
    )
    val joiner = NwLoom(
        joinerApi,
        serviceType = serviceType,
        random = Random(1),
        weaveTimeout = LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP,
    )
    return failFast.dispatching(host, weaveDispatcher) to failFast.dispatching(joiner, weaveDispatcher)
}

/**
 * Wrap [delegate] so `weave` is dispatched on [weaveDispatcher] and a timeout arms this latch.
 *
 * The two are one wrapper because they wrap the same call: this is the single point every weave in a
 * loopback suite passes through, which is what lets the latch see a fabric failure at all.
 */
private fun LoopbackWeaveFailFast.dispatching(delegate: Loom, weaveDispatcher: CoroutineContext): Loom =
    object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            withContext(weaveDispatcher) {
                recordingWeaveFailure { delegate.weave(rendezvous) }
            }

        override fun capability(): TransportCapability = delegate.capability()
    }

/**
 * Suite-scoped latch that turns the *first* loopback weave failure into an immediate failure of every
 * remaining test in the same suite run — the half of #2386 that makes
 * [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP] affordable at all.
 *
 * ## The trade-off, stated plainly
 *
 * This deliberately converts **one** contention-induced timeout into **30 reds**. That is intended, not a
 * side effect: a broken fabric otherwise pays the backstop once per test, and the two K/N lanes running
 * sequentially means 60 payments against ~37 min of headroom under a 60-minute job timeout. Without this,
 * the largest deadline the budget allows is ~37 s, which is barely above the 30 s that caused the problem.
 *
 * The cost is paid entirely in *reading* the result, so [failIfAlreadyBroken]'s message is the deliverable:
 * it says the fabric already failed to weave, that this test was never attempted, and carries the original
 * failure — which since #2386 includes the formation state that explains it. A reader must never be left
 * thinking thirty things broke.
 *
 * ## Why a companion-object instance, and why these primitives
 *
 * `kotlin.test` constructs a **fresh test-class instance per test method**, so an instance field would
 * reset between tests and latch nothing. The suites therefore hold one of these in their companion object.
 *
 * [firstFailure] is written from a `weave` running on `Dispatchers.Default` and read from the test thread,
 * so it is [Volatile]. [pairsBuilt] is a plain volatile increment rather than an atomic because it has
 * exactly **one** writer — [failIfAlreadyBroken], called only from `newLoomPair` on the test-runner
 * thread, and test methods run sequentially. The concurrent access to it is read-only.
 *
 * @param suiteName named in the fast-failure message, since neither `kotlin.test` nor the conformance
 *   suite exposes the *current* test's name portably — the ordinal plus the original failure is what a
 *   reader gets instead.
 */
class LoopbackWeaveFailFast(private val suiteName: String) {

    @Volatile
    private var pairsBuilt: Int = 0

    @Volatile
    private var firstFailurePair: Int = 0

    @Volatile
    private var firstFailure: NwUnreachableException? = null

    /**
     * Throws immediately if an earlier weave in this suite run already timed out, so this test does not
     * spend another [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP].
     *
     * Called by [loopbackLoomPair], which is early enough: the suites build their `NwApi`s first, but
     * neither starts a listener or a browser until `weave`, so a fast-failed test does no real IO. It is
     * deliberately *not* left for each suite to call — that is the same two-call-sites-pinned-by-nothing
     * shape the factory exists to remove.
     */
    fun failIfAlreadyBroken() {
        val first = firstFailure
        if (first != null) {
            throw IllegalStateException(
                "the loopback fabric already failed to weave in $suiteName (loom pair #$firstFailurePair " +
                    "of this suite run); this test was NOT attempted. Every remaining test fails " +
                    "immediately rather than each spending its own " +
                    "LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP=$LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP — see " +
                    "LoopbackWeaveFailFast for why. This is ONE failure reported many times, not many " +
                    "broken things. The original: ${first.message}",
                first,
            )
        }
        pairsBuilt++
    }

    /**
     * Wrap the delegate `weave` so a timeout arms the latch. Catches [NwUnreachableException]
     * **specifically** — a plain assertion failure in one test must not poison the other 29, and a broad
     * `Throwable` catch here would also see (and record) a cancellation.
     *
     * Always rethrows: the test that actually hit the fabric failure reports it normally, with the full
     * formation state #2386 folded into the message.
     */
    suspend fun <T> recordingWeaveFailure(block: suspend () -> T): T =
        try {
            block()
        } catch (unreachable: NwUnreachableException) {
            if (firstFailure == null) {
                // Ordered: a reader that sees a non-null `firstFailure` is guaranteed to see the ordinal
                // written before it.
                firstFailurePair = pairsBuilt
                firstFailure = unreachable
            }
            throw unreachable
        }
}
