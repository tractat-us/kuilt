package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [CompositeSeam]'s reconcile pump must survive everything a **consumer-authored** [Loom] does to it
 * (#1784).
 *
 * ### The hazard this pins
 * `CompositeSeam.init` reconciles the desired ply set from a single long-lived collector —
 * `desired.onEach { reconcile(it) }.launchIn(scope)`. `reconcile` calls straight out into consumer code
 * (`Loom.capability()`, `Loom.weave()`, and, on teardown, `Seam.close()`). An exception escaping any of
 * those escapes the collector, and because `scope` is a [kotlinx.coroutines.SupervisorJob] the failure
 * takes nothing with it and nothing restarts it: **that seam never attaches or detaches a ply again**,
 * while `state` stays cheerfully `Woven` and `plies` keeps reporting the stale set. Nothing observable
 * says the composite has stopped reconciling — the only trace is a stack trace on stderr, which is
 * precisely why this survived (a passing `CompositeSeamCloseTornConcurrencyTest` run emitted ~3,100 of
 * them and still reported success).
 *
 * ### Why these are unit tests and not only a stress probe
 * The real-threaded probes reach these interleavings only stochastically, and — as above — absorb the
 * evidence into `system-err` rather than failing. Here each interleaving is **driven**, so the defect is
 * pinned deterministically, in virtual time, on every target.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeReconcilePumpTest {

    @Test
    fun aThrowingLoomNeitherBlocksItsSiblingsNorKillsTheReconcilePump() = runTest {
        val initial = OneSeamLoom("initial")
        val thrower = FlakyLoom("thrower")
        val sibling = OneSeamLoom("sibling")
        val later = OneSeamLoom("later")
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = desired,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))

        // One reconciliation carrying a ply whose weave throws, followed by a healthy sibling. The order
        // is the point: an unguarded throw aborts the pass, so the sibling never gets woven either.
        desired.value = listOf(
            PlyId(INITIAL) to initial,
            PlyId("thrower") to thrower,
            PlyId("sibling") to sibling,
        )
        runCurrent()
        assertAll(
            {
                assertEquals(
                    setOf(PlyId(INITIAL), PlyId("sibling")),
                    composite.plies.value.keys,
                    "one ply's throwing weave must not stop its siblings in the same reconciliation attaching",
                )
            },
            // Absorbed is not the same as silent: kuilt-core is logger-free, so the failure must reach the
            // consumer carrying the ply's identity and the exception, never a bare count.
            { assertEquals(listOf(PlyId("thrower")), raised.map { it.plyId }, "the failure must be raised") },
            { assertEquals(listOf(PlyReconcileException.Phase.ATTACH), raised.map { it.phase }) },
            { assertEquals(FLAKY_MESSAGE, raised.single().cause.message, "the cause must be the Loom's own") },
        )

        // And the pump must still be alive: a LATER desired emission still reconciles.
        desired.value = listOf(PlyId(INITIAL) to initial, PlyId("sibling") to sibling, PlyId("later") to later)
        runCurrent()
        assertEquals(
            setOf(PlyId(INITIAL), PlyId("sibling"), PlyId("later")),
            composite.plies.value.keys,
            "the reconcile pump died with the throwing weave — this seam can never attach or detach again",
        )

        composite.close(CloseReason.Normal)
    }

    @Test
    fun aPlyThatFailedToWeaveIsRetriedOnTheNextEmission() = runTest {
        val initial = OneSeamLoom("initial")
        val flaky = FlakyLoom("flaky")
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val composite = CompositeLoom(desired, UnconfinedTestDispatcher(testScheduler)).host(Pattern("host"))

        desired.value = listOf(PlyId(INITIAL) to initial, PlyId("flaky") to flaky)
        runCurrent()
        assertEquals(setOf(PlyId(INITIAL)), composite.plies.value.keys, "precondition: the flaky ply failed to weave")

        // A failed ply is left un-live rather than recorded as failed, so a later desired emission simply
        // retries it. A failure ledger would need an invalidation rule, and a fabric that is merely
        // unavailable *right now* (radio off, permission not yet granted) would be locked out forever.
        flaky.heal()
        desired.value = listOf(PlyId(INITIAL) to initial)
        runCurrent()
        desired.value = listOf(PlyId(INITIAL) to initial, PlyId("flaky") to flaky)
        runCurrent()

        assertAll(
            {
                assertEquals(
                    setOf(PlyId(INITIAL), PlyId("flaky")),
                    composite.plies.value.keys,
                    "a ply that failed to weave must be retried on the next desired emission, not blacklisted",
                )
            },
            { assertEquals(2, flaky.weaveAttempts, "the retry must re-enter weave()") },
        )

        composite.close(CloseReason.Normal)
    }

    @Test
    fun aReconcilePassResumingAfterCloseAttachesNothingAndOrphansNothing() = runTest {
        val initial = OneSeamLoom("initial")
        val gated = GatedLoom("gated")
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val composite = CompositeLoom(desired, UnconfinedTestDispatcher(testScheduler)).host(Pattern("host"))

        // Park the reconcile pass inside the consumer's weave().
        desired.value = listOf(PlyId(INITIAL) to initial, PlyId("gated") to gated)
        runCurrent()
        val parked = assertNotNull(gated.parked, "precondition: the reconcile pass is parked inside weave()")

        composite.close(CloseReason.Normal)
        assertIs<SeamState.Torn>(composite.state.value, "precondition: close() latched the terminal Torn")

        // The consumer's weave now returns. It resumes through a plain `suspendCoroutine`, which is
        // *atomic* — a resume that ignores cancellation — so the pass runs to completion after close()
        // has already drained `live` and (asynchronously) cancelled the scope. That is not a contrivance:
        // a reconcile pass need hit no further cancellable suspension point, which is exactly how the
        // real probe re-wove its whole desired set onto a dead seam.
        parked.resume(Unit)
        runCurrent()

        assertAll(
            {
                assertIs<SeamState.Torn>(
                    gated.seam.state.value,
                    "a ply woven after close() must be closed, not attached: close() is single-shot and has " +
                        "already returned, so nothing will ever tear this transport down",
                )
            },
            { assertIs<SeamState.Torn>(composite.state.value, "the composite must stay terminal") },
            { assertEquals(1, gated.weaveAttempts, "the ply must be woven exactly once") },
        )
    }

    /**
     * The blocking case: a `Loom` whose dial **times out**.
     *
     * `runCatchingCancellable` rethrows *every* [kotlin.coroutines.cancellation.CancellationException],
     * including one the callee minted itself rather than one signalling the caller's cancellation. The
     * natural dialling `Loom` is `withTimeout(dialTimeout) { dial() }`, and `withTimeout` throws
     * [TimeoutCancellationException] **to its caller** without cancelling that caller's job. Guarded only
     * by `runCatchingCancellable`, that rethrow escapes the reconcile collector on a live, non-`Torn`
     * composite — and because the escaping throwable *is* a `CancellationException`, the collector is
     * **cancelled, not failed**: `onPlyFailure` never fires and there is not even a stack trace on stderr.
     * The original #1784 defect with its one remaining diagnostic thread cut, reached by the single
     * likeliest way a ply fails to come up.
     *
     * `NwLoom` defuses its own timeout by converting it to a plain `NwUnreachableException`, but that is
     * one fabric's convention; `Loom.weave` is consumer-authored, so the composite must be robust to a
     * `Loom` that does the natural thing.
     */
    @Test
    fun aDialThatTimesOutIsAPlyFailureAndNotAPumpKill() = runTest {
        val initial = OneSeamLoom("initial")
        val timingOut = TimingOutLoom("timeout")
        val sibling = OneSeamLoom("sibling")
        val later = OneSeamLoom("later")
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = desired,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))

        desired.value = listOf(
            PlyId(INITIAL) to initial,
            PlyId(TIMEOUT) to timingOut,
            PlyId(SIBLING) to sibling,
        )
        // The dial must actually time out: `withTimeout` runs on the test scheduler's virtual clock.
        advanceTimeBy(DIAL_TIMEOUT * 2)
        runCurrent()

        assertAll(
            {
                assertEquals(
                    setOf(PlyId(INITIAL), PlyId(SIBLING)),
                    composite.plies.value.keys,
                    "a ply whose dial timed out must not stop its siblings in the same reconciliation attaching",
                )
            },
            // The whole point: a rethrown CancellationException cancels the collector SILENTLY, so the
            // observer firing is the difference between a diagnosable failure and none at all.
            {
                assertEquals(
                    listOf(PlyId(TIMEOUT)),
                    raised.map { it.plyId },
                    "a dial timeout must be raised as a ply failure, not silently cancel the reconcile pump",
                )
            },
            { assertEquals(listOf(PlyReconcileException.Phase.ATTACH), raised.map { it.phase }) },
            {
                assertIs<TimeoutCancellationException>(
                    raised.firstOrNull()?.cause,
                    "the cause must be the fabric's own dial timeout",
                )
            },
        )

        // And the pump must still be alive: a LATER desired emission still reconciles.
        desired.value = listOf(PlyId(INITIAL) to initial, PlyId(SIBLING) to sibling, PlyId(LATER) to later)
        runCurrent()
        assertEquals(
            setOf(PlyId(INITIAL), PlyId(SIBLING), PlyId(LATER)),
            composite.plies.value.keys,
            "the reconcile pump was cancelled by the dial timeout — this seam can never attach or detach again",
        )

        composite.close(CloseReason.Normal)
    }

    /**
     * A detached ply's `close()` throwing must not skip the peers/capability recompute.
     *
     * `detachPly` purges `idMap` and *then* closes the transport. If that foreign close escapes, the
     * recomputes below it are skipped while the mapping is already gone — so `peers` keeps advertising a
     * composite peer reachable only through the detached ply (`sendTo` throwing for a peer `peers` calls
     * reachable) and `capability` keeps that ply's roles in the union. Every trigger that could correct
     * either is gone with the ply's cancelled pumps, so both stay stale indefinitely.
     */
    @Test
    fun aPlyWhoseCloseThrowsStillLeavesPeersAndCapabilityRecomputed() = runTest {
        val initial = OneSeamLoom("initial")
        val doomed = UncloseableLoom("doomed", REMOTE_TRANSPORT)
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = desired,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))

        desired.value = listOf(PlyId(INITIAL) to initial, PlyId(DOOMED) to doomed)
        runCurrent()
        // Learn a composite peer reachable ONLY through the doomed ply, and fold in its distinctive role.
        doomed.fake.deliver(REMOTE_TRANSPORT, PlyFrame.encode(PlyFrame.Announce(REMOTE_COMPOSITE)))
        runCurrent()
        assertAll(
            {
                assertEquals(
                    setOf(composite.selfId, REMOTE_COMPOSITE),
                    composite.peers.value,
                    "precondition: the remote composite peer is reachable only through the doomed ply",
                )
            },
            {
                assertEquals(
                    setOf(TransportRole.Data, TransportRole.ServerRelay),
                    composite.capability.value.roles,
                    "precondition: the doomed ply's roles are in the union",
                )
            },
        )

        // Detach it. Its close() throws.
        desired.value = listOf(PlyId(INITIAL) to initial)
        runCurrent()

        assertAll(
            {
                assertEquals(
                    setOf(composite.selfId),
                    composite.peers.value,
                    "peers must drop a peer reachable only through the detached ply even when that ply's " +
                        "close() threw — otherwise peers advertises reachable while sendTo throws, forever",
                )
            },
            {
                assertEquals(
                    setOf(TransportRole.Data),
                    composite.capability.value.roles,
                    "capability must drop the detached ply's roles even when that ply's close() threw",
                )
            },
            {
                assertEquals(
                    listOf(PlyId(DOOMED) to PlyReconcileException.Phase.DETACH),
                    raised.map { it.plyId to it.phase },
                    "the throwing close must still be raised, absorbed is not silent",
                )
            },
        )

        composite.close(CloseReason.Normal)
    }

    /**
     * A detach that races `close()` must still close the transport it took ownership of.
     *
     * `detachPly` takes ownership at `live.remove(id)` — after it, `close()`'s drain cannot see the
     * handle — and only closes the transport several statements later, past a `cancelAndJoin`. That join
     * throws the moment the reconcile collector is cancelled, which is precisely the churn-across-`close()`
     * interleaving: reconcile wins the remove, `close()` drains a snapshot *without* this ply and cancels
     * the scope, the join throws, and **nobody ever closes this ply's transport** — `close()` being
     * single-shot and already returned. The stress probe cannot see this: the rethrow bypasses the failure
     * observer, so `onPlyFailure` never fires either.
     */
    @Test
    fun aDetachRacingCloseStillClosesTheTransportItTookOwnershipOf() = runTest {
        val initial = OneSeamLoom("initial")
        val slow = SlowStoppingLoom("slow")
        val desired = MutableStateFlow(
            listOf(PlyId(INITIAL) to initial as Loom, PlyId(SLOW) to slow as Loom),
        )
        val composite = CompositeLoom(desired, UnconfinedTestDispatcher(testScheduler)).host(Pattern("host"))
        runCurrent()

        // Drop the slow ply: reconcile wins `live.remove`, then parks in cancelAndJoin — the ply's inbound
        // pump holds the join open with a NonCancellable cleanup, as a real transport's teardown does.
        desired.value = listOf(PlyId(INITIAL) to initial)
        runCurrent()
        assertTrue(
            slow.parkedInTeardown.isCompleted,
            "precondition: the detach is parked inside cancelAndJoin, before the transport close",
        )

        // close() now drains a snapshot that no longer contains the slow ply and cancels the scope. The
        // parked detach is the only thing that can still close that transport.
        composite.close(CloseReason.Normal)
        assertIs<SeamState.Torn>(composite.state.value, "precondition: close() latched the terminal Torn")

        slow.releasePumps()
        runCurrent()

        assertIs<SeamState.Torn>(
            slow.seam.state.value,
            "the detached ply's transport was never closed: cancelAndJoin threw when close() cancelled the " +
                "collector, and close() had already drained this handle out of its own snapshot",
        )
    }

    /**
     * `weave()` failing must not leave the plies that already came up open.
     *
     * Starting is all-or-nothing — one ply failing fails the whole `weave()` — but `CompositeLoom` is the
     * only holder of the seams it wove, and a throwing `weave` hands the caller no [Seam] to close them
     * with. Dropping them leaks a live transport per ply, in the one type whose reason to exist is not
     * leaking transports across a failed attach.
     */
    @Test
    fun aFailedInitialWeaveClosesThePliesThatDidComeUp() = runTest {
        val first = OneSeamLoom("first")
        val second = OneSeamLoom("second")
        val thrower = FlakyLoom("thrower")
        val loom = CompositeLoom(
            plies = listOf(
                PlyId("first") to first as Loom,
                PlyId("second") to second,
                PlyId("thrower") to thrower,
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val failure = assertFailsWith<IllegalStateException> { loom.host(Pattern("host")) }

        assertAll(
            { assertEquals(FLAKY_MESSAGE, failure.message, "weave() must still fail — starting is all-or-nothing") },
            {
                assertIs<SeamState.Torn>(
                    first.seam.state.value,
                    "a ply woven before the failure must be closed on the way out: weave() throws, so the " +
                        "caller never receives a Seam and has no handle to close this transport with",
                )
            },
            { assertIs<SeamState.Torn>(second.seam.state.value, "…and so must every other ply that came up") },
        )
    }

    /**
     * The salvage of a ply woven after `close()` is its own [PlyReconcileException.Phase].
     *
     * `discardOrphanedPly` is reached only from the *attach* path, for a ply that never entered the live
     * set and never appeared in `plies`. Reporting `DETACH` would tell the consumer's logger that "its
     * pumps are stopped and it is out of the composite" — neither of which ever happened.
     */
    @Test
    fun anOrphanedPlyThatCannotBeClosedIsRaisedAsSalvage() = runTest {
        val initial = OneSeamLoom("initial")
        val gated = GatedLoom("gated", closeFailure = SALVAGE_MESSAGE)
        val desired = MutableStateFlow(listOf(PlyId(INITIAL) to initial as Loom))
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = desired,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))

        desired.value = listOf(PlyId(INITIAL) to initial, PlyId(GATED) to gated)
        runCurrent()
        val parked = assertNotNull(gated.parked, "precondition: the reconcile pass is parked inside weave()")

        composite.close(CloseReason.Normal)
        parked.resume(Unit)
        runCurrent()

        assertEquals(
            listOf(PlyId(GATED) to PlyReconcileException.Phase.SALVAGE),
            raised.map { it.plyId to it.phase },
            "a ply that never entered the live set and never appeared in `plies` was not DETACHed",
        )
    }

    /** A [Loom] that weaves one seam, counting its dials. */
    private open class OneSeamLoom(id: String) : Loom {
        open val seam: Seam = FakeSeam(selfId = PeerId("ply-$id"))
        var weaveAttempts: Int = 0
            private set

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            weaveAttempts++
            return seam
        }

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    /** A [Loom] whose `weave` throws until [heal] is called — a consumer fabric that cannot dial yet. */
    private class FlakyLoom(id: String) : OneSeamLoom(id) {
        private var healthy = false

        fun heal() {
            healthy = true
        }

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val woven = super.weave(rendezvous)
            check(healthy) { FLAKY_MESSAGE }
            return woven
        }
    }

    /**
     * A [Loom] whose dial times out — written the natural way, `withTimeout(dialTimeout) { dial() }`.
     *
     * `withTimeout` throws [TimeoutCancellationException] — a `CancellationException` — **to its caller**,
     * without cancelling that caller's job. Nothing in the [Loom] contract used to forbid it, and no
     * previous test covered it, which is why the composite's `runCatchingCancellable`-only guard let it
     * through as "our own cancellation".
     */
    private class TimingOutLoom(id: String) : OneSeamLoom(id) {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            super.weave(rendezvous)
            return withTimeout(DIAL_TIMEOUT) { awaitCancellation() }
        }
    }

    /**
     * A [Loom] whose ply seam refuses to close, and which already sees [remote] as a transport peer — the
     * detach path's exception-safety case. Its role is deliberately distinct from every other ply's so the
     * capability union shows whether the recompute ran.
     */
    private class UncloseableLoom(id: String, remote: PeerId) : Loom {
        val fake: FakeSeam = FakeSeam(
            selfId = PeerId("ply-$id"),
            initialPeers = setOf(PeerId("ply-$id"), remote),
        )
        private val seam: Seam = object : Seam by fake {
            override suspend fun close(reason: CloseReason): Unit = throw IllegalStateException(UNCLOSEABLE_MESSAGE)
        }

        override suspend fun weave(rendezvous: Rendezvous): Seam = seam

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.ServerRelay), FabricAvailability.Available)
    }

    /**
     * A [Loom] whose ply pumps take time to stop: the inbound flow's cleanup runs under
     * [NonCancellable] and parks until [releasePumps], so a detach suspends inside `cancelAndJoin`.
     *
     * Not a contrivance — that is what a real transport's teardown does (a close handshake, a channel
     * drain). It is the only way to hold the detach at the one statement `close()` can cancel it at.
     */
    private class SlowStoppingLoom(id: String) : Loom {
        private val fake: FakeSeam = FakeSeam(selfId = PeerId("ply-$id"))
        private val stopped = CompletableDeferred<Unit>()

        /** Completes once the ply's inbound pump is mid-teardown — i.e. the detach is parked in the join. */
        val parkedInTeardown: CompletableDeferred<Unit> = CompletableDeferred()

        val seam: Seam = object : Seam by fake {
            override val incoming: Flow<Swatch> = flow {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        parkedInTeardown.complete(Unit)
                        stopped.await()
                    }
                }
            }
        }

        fun releasePumps() {
            stopped.complete(Unit)
        }

        override suspend fun weave(rendezvous: Rendezvous): Seam = seam

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    /**
     * A [Loom] whose `weave` parks until the test resumes it, modelling a consumer fabric mid-dial.
     *
     * The park is a plain [suspendCoroutine] — deliberately **not** cancellable — because the hazard
     * under test is a reconcile pass that keeps running after its scope was cancelled. A cancellable
     * park would simply throw on resume and prove nothing.
     *
     * @param closeFailure When non-null, the woven seam's `close()` throws with this message — the
     *   salvage-failed case, which is what makes the raised [PlyReconcileException.Phase] observable.
     */
    private class GatedLoom(id: String, closeFailure: String? = null) : OneSeamLoom(id) {
        /**
         * A seam whose `close()` suspends before tearing — as every real transport's does (a WebSocket
         * close handshake, a `Mutex`, a channel send). `FakeSeam.close` is entirely non-suspending, so a
         * plain `FakeSeam` here could never observe the cancellation that `close()` has already issued,
         * and the test would pass while the production salvage was skipped.
         */
        override val seam: SlowClosingSeam = SlowClosingSeam(PeerId("ply-$id"), closeFailure)

        var parked: Continuation<Unit>? = null
            private set

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val woven = super.weave(rendezvous)
            suspendCoroutine<Unit> { parked = it }
            return woven
        }
    }

    /** A [FakeSeam] that yields — a real, cancellable suspension point — before tearing down. */
    private class SlowClosingSeam(
        selfId: PeerId,
        private val closeFailure: String? = null,
        private val delegate: FakeSeam = FakeSeam(selfId = selfId),
    ) : Seam by delegate {
        override suspend fun close(reason: CloseReason) {
            yield()
            if (closeFailure != null) throw IllegalStateException(closeFailure)
            delegate.close(reason)
        }
    }

    private companion object {
        const val INITIAL = "initial"
        const val SIBLING = "sibling"
        const val LATER = "later"
        const val TIMEOUT = "timeout"
        const val DOOMED = "doomed"
        const val SLOW = "slow"
        const val GATED = "gated"
        const val FLAKY_MESSAGE = "flaky fabric cannot dial yet"
        const val UNCLOSEABLE_MESSAGE = "this fabric refuses to close"
        const val SALVAGE_MESSAGE = "this fabric refuses to be salvaged"
        val DIAL_TIMEOUT = 50.milliseconds
        val REMOTE_TRANSPORT = PeerId("remote-transport")
        val REMOTE_COMPOSITE = PeerId("remote-composite")
    }
}
