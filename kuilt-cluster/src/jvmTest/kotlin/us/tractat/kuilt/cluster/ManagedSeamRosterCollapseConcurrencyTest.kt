@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency probe — ManagedSeam's per-swap roster tracker and its close()/swap() roster writes are serialised onto one thread by any test dispatcher, which is exactly what makes an unguarded read-then-write invisible.

package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real OS-thread concurrency probe — the tracker write and the tear-time collapse must run on genuinely different threads or the race under test cannot occur at all.
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.runConcurrencyStress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real-threaded roster probe for [ManagedSeam] (#2655).
 *
 * [ManagedSeam.close] collapses `peers` back to `{ selfId }` **outside** its lock, and its own
 * comment asserts that cancelling the per-swap roster tracker first is what makes that safe:
 *
 * > Cancel the tracker BEFORE collapsing, so no in-flight emission of the outgoing seam's roster
 * > can be published over the collapse.
 *
 * That is the claim #1879 records as false. `Job.cancel()` is **not** `join()`: it returns as soon
 * as the job is marked cancelled, and cancellation only takes effect at a *suspension point*. The
 * tracker's body — `_peers.value = reattributed(it, seam.selfId)` — contains none, so a tracker
 * that has already passed `StateFlow.collect`'s `ensureActive()` and entered the lambda runs to
 * completion regardless, and its write lands after the collapse. Nothing corrects it afterwards:
 * both writers have retired, so the seam goes on advertising a roster it has no route to.
 *
 * [ManagedSeam.swap] has the same shape one step earlier, and it is the arm that is on the
 * **production** path — `close()` has no in-tree caller at all (`buildClusterClient` ends the client
 * by cancelling its scope), whereas every cross-server failover swaps.
 *
 * Under virtual time neither arm can red: a test dispatcher serialises the tracker against the
 * collapse, and a check-then-act is *correct* whenever nothing runs between the read and the write.
 */
class ManagedSeamRosterCollapseConcurrencyTest {

    /** The MANAGED id — what this seam publishes and what a caller addresses it by. */
    private val stableId = PeerId("stable-client")

    /**
     * The race: a hot roster-emission stream on the live backing seam, and a `close()`.
     *
     * Each iteration gets a fresh seam because the collapse is single-shot — after one `close()`
     * there is no backing seam and no tracker left to race, so reusing one would race nothing after
     * the first iteration.
     *
     * The closer is released only once a churned roster has been observed on `managed.peers`, which
     * is the one fact that makes the arm non-vacuous: it proves the relay pipeline was **running**
     * at the moment `close()` landed. Released together instead, the closer wins outright — measured
     * on the first draft of this probe, where the producer got ~2 rounds away per iteration before
     * the seam was torn under it and the window was never entered. That draft is why the churn count
     * is a rig precondition here rather than an assumption.
     */
    @Test
    fun aRosterEmissionCaughtMidFlightCannotStandOverTheTearTimeCollapse() = runConcurrencyStress { stage ->
        val survivors = CopyOnWriteArrayList<String>()
        var completed = 0
        val relayHot = AtomicInteger()
        val churnRounds = AtomicLong()
        repeat(CLOSE_ITERATIONS) { iter ->
            val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val managed = ManagedSeam(scope = relayScope, selfId = stableId)
            val backing = ChurningBackingSeam(BACKING_SELF, initialRemotes())
            managed.swap(backing)

            stage.at("iter=$iter race") { "iter=$iter managed=${managed.peers.value.size} backing=${backing.peers.value.size}" }
            coroutineScope {
                val churner = churn(backing)
                // The rig gate: a CHURNED roster (not the one `swap` primed) has travelled the whole
                // relay and reached `managed.peers`, so the tracker is subscribed AND actively
                // publishing right now.
                val hot = withTimeoutOrNull(HOT_BUDGET) {
                    managed.peers.first { roster -> roster.any { it.value.startsWith(CHURN_PREFIX) } }
                } != null
                if (hot) relayHot.incrementAndGet()

                managed.close(CloseReason.Normal)
                churnRounds.addAndGet(churner.await().toLong())
            }

            // Join every writer before reading. `close()` only CANCELS the tracker, so without the
            // join a read here would be sampling a value that is still moving — and this arm's claim
            // is that the surviving value is PERMANENT, not that it was briefly wrong.
            stage.at("iter=$iter quiesce") { "iter=$iter managed=${managed.peers.value.size}" }
            relayScope.coroutineContext.job.cancelAndJoin()

            val settled = managed.peers.value
            if (settled != setOf(stableId)) {
                survivors += "iter=$iter peers=${settled.size} e.g. ${settled.take(3).map { it.value }}"
            }
            completed++
        }

        // Built and torn down UNRACED, so the two rig preconditions below interrogate one seam:
        // "close still collapses" and "the roster it collapsed from was genuinely populated" are two
        // claims about one teardown, and asserting them on two seams would leave open the case where
        // each holds only on its own.
        val unracedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val unraced = ManagedSeam(scope = unracedScope, selfId = stableId)
        unraced.swap(ChurningBackingSeam(BACKING_SELF, initialRemotes()))
        val unracedRosterBeforeClose = unraced.peers.value
        unraced.close(CloseReason.Normal)
        unracedScope.coroutineContext.job.cancelAndJoin()

        assertAll(
            {
                assertEquals(
                    CLOSE_ITERATIONS,
                    completed,
                    "rig precondition: the race loop must complete every iteration it claims. An arm " +
                        "asserting an ABSENCE has to prove it did the work, or a loop that never ran " +
                        "reads as a clean pass",
                )
            },
            {
                assertEquals(
                    CLOSE_ITERATIONS,
                    relayHot.get(),
                    "rig precondition: in every iteration a CHURNED roster must have reached " +
                        "`managed.peers` before close() was called. An iteration whose relay was not " +
                        "yet publishing has no second writer at the moment of the collapse, so it " +
                        "cannot clobber and its green is vacuous",
                )
            },
            {
                assertTrue(
                    churnRounds.get() >= CLOSE_ITERATIONS.toLong() * MIN_CHURN_ROUNDS,
                    "rig precondition: the churner must have published at least $MIN_CHURN_ROUNDS " +
                        "rosters per iteration on average; it published ${churnRounds.get()} across " +
                        "$CLOSE_ITERATIONS iterations. Each round is one more chance for the tracker " +
                        "to be inside its lambda when the collapse lands, and a churner the closer " +
                        "outruns drives no window at all",
                )
            },
            {
                assertEquals(
                    emptyList<String>(),
                    survivors.take(MAX_REPORTED_SURVIVORS),
                    "a roster emission caught mid-flight stood over close()'s collapse in " +
                        "${survivors.size} of $CLOSE_ITERATIONS iterations (#2655). Every writer has " +
                        "been joined, so this value is FINAL: the ManagedSeam has no backing seam, " +
                        "every broadcast/sendTo is dropped into a debug line, and `peers` still names " +
                        "servers nothing can reach. `relayJob.cancel()` is not a join — a tracker " +
                        "already inside its lambda has no suspension point left at which " +
                        "cancellation could take effect (#1879)",
                )
            },
            {
                assertEquals(
                    setOf(stableId) + initialRemotes(),
                    unracedRosterBeforeClose,
                    "rig precondition: the unraced seam must actually HOLD remotes before its close, " +
                        "or the collapse assertion below is asserting that `{ selfId }` stayed " +
                        "`{ selfId }` — the fixture configured into the one state at which it cannot fail",
                )
            },
            {
                assertEquals(
                    setOf(stableId),
                    unraced.peers.value,
                    "rig precondition: an unraced close() must still COLLAPSE the roster, so a green " +
                        "above cannot be explained by the collapse having been deleted",
                )
            },
        )
    }

    /**
     * The same defect one step earlier, and the arm reachable in production.
     *
     * [ManagedSeam.swap] cancels the outgoing tracker and then publishes the incoming seam's roster
     * under the lock. The cancel is again not a join, so the outgoing tracker's in-flight write can
     * land afterwards.
     *
     * **Two claims, counted separately, because they are separate consequences.** The *live-view*
     * claim is that no sample of `peers` taken after `swap` returns may name the endpoint the swap
     * left — a seam whose only route is now the new server, advertising the old one, which
     * `RoutedRaftTransport.playerServerHop` reads as two candidate hops where the wiring promises
     * exactly one, dropping every relayed send it makes in that window. The *permanence* claim is
     * that the seam does not **settle** there.
     *
     * They were expected to diverge sharply — the incoming seam's own tracker subscribes
     * microseconds later and republishes the correct roster, so the late write looked like it would
     * almost always be healed. It is not: measured on the unfixed code the two counts are within a
     * few percent of each other, because the incoming tracker's first publish is the value `swap`
     * already primed and `StateFlow` dedups it away — there is no second write left to do the
     * healing. Kept as two assertions anyway, so a future change that heals one without the other
     * cannot hide behind the merged count.
     */
    @Test
    fun anOutgoingTrackerCaughtMidFlightCannotRepublishTheEndpointTheSwapLeft() = runConcurrencyStress { stage ->
        val regressions = CopyOnWriteArrayList<String>()
        val settledStale = CopyOnWriteArrayList<String>()
        var completed = 0
        val relayHot = AtomicInteger()
        val churnRounds = AtomicLong()
        repeat(SWAP_ITERATIONS) { iter ->
            val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val managed = ManagedSeam(scope = relayScope, selfId = stableId)
            val outgoing = ChurningBackingSeam(BACKING_SELF, initialRemotes())
            val incoming = ChurningBackingSeam(INCOMING_SELF, setOf(INCOMING_REMOTE))
            managed.swap(outgoing)

            stage.at("iter=$iter race") { "iter=$iter managed=${managed.peers.value.size}" }
            coroutineScope {
                val churner = churn(outgoing)
                val hot = withTimeoutOrNull(HOT_BUDGET) {
                    managed.peers.first { roster -> roster.any { it.value.startsWith(CHURN_PREFIX) } }
                } != null
                if (hot) relayHot.incrementAndGet()

                managed.swap(incoming)
                // Sampled HERE, on this thread, after `swap` has returned — so every sample is
                // totally ordered after the swap and a hit is unambiguous.
                //
                // The first draft used a collector on another thread and treated `observed.size`,
                // read after the swap, as the landmark. It was wrong, and the fix caught it rather
                // than the other way round: the index bounds APPENDS, not publishes, so a collector
                // holding a value it read before the swap can append it afterwards. That instrument
                // reported 3 of 3 000 "post-swap" emissions against the FIXED code, where the guard
                // makes a late outgoing write structurally impossible — a false positive rate of
                // exactly the shape that gets read as a residual defect.
                var offending: Set<PeerId>? = null
                repeat(LIVE_SAMPLES) {
                    val sample = managed.peers.value
                    if (offending == null && sample.any { it.namesOldEndpoint() }) offending = sample
                }
                // The outgoing seam is no longer this seam's business, but the churner is still
                // hammering it; tearing it is what stops the churner and models the real failover,
                // where the old transport is already dead.
                outgoing.close(CloseReason.Normal)
                churnRounds.addAndGet(churner.await().toLong())

                stage.at("iter=$iter quiesce") { "iter=$iter managed=${managed.peers.value.size}" }
                relayScope.coroutineContext.job.cancelAndJoin()

                offending?.let {
                    regressions += "iter=$iter live view named the old endpoint after the swap " +
                        "returned, e.g. ${it.take(3).map { p -> p.value }}"
                }
                val settled = managed.peers.value
                if (settled.any { it.namesOldEndpoint() }) {
                    settledStale += "iter=$iter settled=${settled.take(3).map { it.value }}"
                }
            }
            completed++
        }

        assertAll(
            {
                assertEquals(
                    SWAP_ITERATIONS,
                    completed,
                    "rig precondition: the race loop must complete every iteration it claims",
                )
            },
            {
                assertEquals(
                    SWAP_ITERATIONS,
                    relayHot.get(),
                    "rig precondition: the OUTGOING relay must have been actively publishing when " +
                        "the swap landed, or there is no stale writer to clobber with",
                )
            },
            {
                assertTrue(
                    churnRounds.get() >= SWAP_ITERATIONS.toLong() * MIN_CHURN_ROUNDS,
                    "rig precondition: the churner must still have been publishing when the swap " +
                        "landed — at least $MIN_CHURN_ROUNDS rosters per iteration on average; it " +
                        "published ${churnRounds.get()} across $SWAP_ITERATIONS iterations. The gate " +
                        "above proves the relay was hot when it opened; this proves it stayed hot " +
                        "through the swap, which is when the stale write has to be in flight",
                )
            },
            {
                assertEquals(
                    emptyList<String>(),
                    settledStale.take(MAX_REPORTED_SURVIVORS),
                    "the seam SETTLED on the endpoint the swap left, in ${settledStale.size} of " +
                        "$SWAP_ITERATIONS iterations (#2655) — the permanent form: every writer has " +
                        "been joined and the incoming seam's roster never moves again, so nothing is " +
                        "left to correct it",
                )
            },
            {
                assertEquals(
                    emptyList<String>(),
                    regressions.take(MAX_REPORTED_SURVIVORS),
                    "the OUTGOING seam's tracker republished the endpoint the swap had already left " +
                        "in ${regressions.size} of $SWAP_ITERATIONS iterations (#2655). `swap` " +
                        "cancels that tracker and then publishes the incoming roster under the lock, " +
                        "but `cancel()` is not `join()` and the tracker's write has no suspension " +
                        "point at which the cancellation could take effect — so a live view of a " +
                        "seam whose only route is the new server goes on naming the old one, and " +
                        "`RoutedRaftTransport.playerServerHop` reads two candidate hops where the " +
                        "wiring promises exactly one",
                )
            },
        )
    }

    /**
     * Start the roster producer against [seam]: it cycles the pre-built [CHURN_ROSTERS] until the
     * seam refuses.
     *
     * **The rosters are pre-built, and that is the whole sensitivity of this probe.** The race
     * needs the tracker to be *inside* its lambda when the collapse lands; whenever it is suspended
     * at `StateFlow.collect`'s `awaitPending` instead, cancellation is prompt and nothing can go
     * wrong. So the ratio that matters is the tracker's work per emission against the producer's.
     * Two drafts got this backwards and were measured doing it:
     *
     * - building each roster inside the loop (256 interpolated strings and two set allocations per
     *   round) made the *producer* several times the more expensive side, so the tracker sat
     *   suspended and the arm reddened as thinly as 9 of 10 000 iterations — and once not at all;
     * - three concurrent producers made it *worse still* (12, 2, 2 of 10 000), because they take
     *   the CPU the tracker and the closer need rather than keeping a value pending.
     *
     * Pre-built, the producer is one volatile store while the tracker does `reattributed`'s two
     * [ROSTER_WIDTH]-sized set allocations, so the tracker is busy essentially always.
     */
    private fun CoroutineScope.churn(seam: ChurningBackingSeam): Deferred<Int> =
        async(Dispatchers.Default) {
            var round = 0
            while (round < CHURN_CAP && seam.publish(CHURN_ROSTERS[round % CHURN_ROSTERS.size])) round++
            round
        }

    /**
     * Is this id one only the OUTGOING seam could have contributed? Both of its roster generations
     * count — the one `swap` primed from and every churned one.
     */
    private fun PeerId.namesOldEndpoint(): Boolean =
        value.startsWith(CHURN_PREFIX) || value.startsWith(INITIAL_PREFIX)

    /** The roster the backing seam starts with — remotes, so the re-attributed set is not `{ selfId }`. */
    private fun initialRemotes(): Set<PeerId> =
        (0 until ROSTER_WIDTH).mapTo(mutableSetOf()) { PeerId("$INITIAL_PREFIX$it") }

    /**
     * A conforming backing [Seam] whose roster this probe drives from another thread.
     *
     * Purpose-built rather than reusing `FakeSeam`, for one reason: the churner keeps publishing
     * while `close()` runs, and `FakeSeam.addPeer` would happily drive a torn fake into `Torn` with
     * remotes — the exact state its own `init` block refuses to be constructed in. [publish] instead
     * **refuses** once torn and says so, which is both conforming and what stops the churner.
     */
    private class ChurningBackingSeam(
        override val selfId: PeerId,
        initialRemotes: Set<PeerId>,
    ) : Seam {
        private val _peers = MutableStateFlow(setOf(selfId) + initialRemotes)
        override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

        // ALLOW-bareSeamState: test double with a single writer, behind the `torn` CAS below.
        private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
        override val state: StateFlow<SeamState> = _state.asStateFlow()

        // Never emits and never completes: the relay's `incoming` pump needs something to collect,
        // but this probe asserts only on the roster.
        override val incoming: Flow<Swatch> = MutableSharedFlow()

        private val torn = AtomicBoolean(false)

        /**
         * Publish [roster] — which must already contain [selfId] — as this seam's live roster.
         * Returns `false` once torn, publishing nothing.
         *
         * It takes the finished set rather than the remotes precisely so the call is a single
         * volatile store; see `churn` for why the producer's cost is what decides this probe's
         * sensitivity.
         */
        fun publish(roster: Set<PeerId>): Boolean {
            if (torn.get()) return false
            _peers.value = roster
            return true
        }

        override suspend fun broadcast(payload: ByteArray) = Unit

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit

        override suspend fun close(reason: CloseReason) {
            if (!torn.compareAndSet(false, true)) return
            // Collapse before latching, per `Seam.peers` / #1816.
            _peers.value = setOf(selfId)
            _state.value = SeamState.Torn(reason)
        }
    }

    private companion object {
        /**
         * A **detection floor, not a timing assertion** — the wall-clock cap belongs to
         * `runConcurrencyStress`. Sized from the measured rate: with the producer pre-built (see
         * `churn`) the unfixed code clobbers ~40-55% of iterations, so this is two orders of
         * magnitude of margin rather than a number tuned to the last observed run.
         */
        const val CLOSE_ITERATIONS = 3_000

        /** The swap arm's window is the same shape; same floor. */
        const val SWAP_ITERATIONS = 3_000

        /**
         * Backstop only — the churner normally stops the moment the backing seam refuses a publish.
         * It exists so a close() that somehow failed to tear cannot spin a worker forever.
         */
        const val CHURN_CAP = 500_000

        /**
         * Distinct rosters the producer cycles through. More than one because `StateFlow` dedups an
         * equal value — a producer republishing one roster emits exactly once and the tracker goes
         * straight back to sleep.
         */
        const val CHURN_GENERATIONS = 64

        /**
         * Remotes per published roster. Wider than one deliberately: `reattributed` is
         * `roster - backingSelfId + selfId`, two set allocations whose cost scales with this, and
         * that cost IS the window between the tracker's read and its write.
         */
        const val ROSTER_WIDTH = 256

        /** Marks an id as coming from the churner rather than from `swap`'s priming write. */
        const val CHURN_PREFIX = "relay-churn-"

        /** Marks an id as belonging to the outgoing seam's FIRST roster, the one `swap` primed from. */
        const val INITIAL_PREFIX = "relay-initial-"

        /** The backing seam's own id — deliberately different from the managed one. */
        val BACKING_SELF = PeerId("fabric-minted-id")

        /**
         * The producer's rosters, built once for the whole class. Each already contains
         * [BACKING_SELF], so publishing one is a single volatile store — see `churn`.
         */
        val CHURN_ROSTERS: List<Set<PeerId>> = (0 until CHURN_GENERATIONS).map { gen ->
            (0 until ROSTER_WIDTH).mapTo(mutableSetOf(BACKING_SELF)) { PeerId("$CHURN_PREFIX$gen-$it") }
        }

        /**
         * Average churn rounds per iteration the rig demands. Low, because the gate above already
         * proves the relay was live; this only rules out the degenerate run in which the closer
         * outruns the churner so completely that essentially nothing was published.
         */
        const val MIN_CHURN_ROUNDS = 4

        val INCOMING_SELF = PeerId("incoming-fabric-id")
        val INCOMING_REMOTE = PeerId("incoming-relay")

        /**
         * Per-iteration budget for a churned roster to reach `managed.peers`. Generous — a wedge
         * backstop for the rig gate, not a timing assertion; a miss is counted and reported, never
         * silently tolerated.
         */
        val HOT_BUDGET = 10.seconds

        /**
         * Samples of the live `peers` view taken on the test thread immediately after `swap`
         * returns. A tight loop of volatile reads costs microseconds; the count only has to cover
         * the moment a stale write would land, which is nanoseconds after the swap.
         */
        const val LIVE_SAMPLES = 2_000

        /** Cap on the failure message only; the collected size still reports the true total. */
        const val MAX_REPORTED_SURVIVORS = 10
    }
}
