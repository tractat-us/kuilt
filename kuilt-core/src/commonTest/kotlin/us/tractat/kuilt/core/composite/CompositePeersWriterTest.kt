package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A [CompositeSeam]'s [Seam.peers] must converge on the plies' true joint reachability — the third strand of
 * the read-modify-write class this file has now seen three times (`state`/#1135, `capability`/#1712,
 * `peers`/#1784).
 *
 * `recomputePeers` snapshotted the reachable set under the lock and published it **outside** the lock, so
 * snapshots were totally ordered and publishes were not. Two things had to change, and either alone leaves
 * the wedge reachable — hence one test each:
 *
 *  1. **[aStalePeersSnapshotCannotLandAfterANewerOne]** — the publish must be serialised onto one writer, or
 *     a caller preempted between its lock release and its write lands an older snapshot **last**. There is no
 *     periodic backstop (the fold fires only on an `Announce`, a ply membership change, or a detach), so that
 *     one inverted publish wedges `peers` for the life of the seam.
 *  2. **[aPlyReturningToItsLastDeliveredPeerSetStillLeavesPeersCorrect]** — the fold must read each ply's
 *     **mirrored** peer set, never a live `seam.peers.value`. A `StateFlow` conflates per collector against
 *     *that collector's* last-emitted value, so a ply whose peer set round-trips `X → Y → X` while its pump
 *     is descheduled delivers nothing and requests nothing. The single writer cannot help: the lost thing is
 *     the *trigger*, not the *update*, and there is no request to serialise.
 *
 * ### The harness
 * [DrivenPeersSeam] splits a ply's peer-set movement into [DrivenPeersSeam.set] (move the value, deliver
 * nothing — a pump that has not been dispatched) and [DrivenPeersSeam.runPump] (one iteration of
 * `StateFlowImpl.collect`: deliver only if the value differs from the one this collector last delivered).
 * That is a faithful model of `StateFlow`, not a weakened one, which is what makes a `runPump()` returning
 * `false` legitimate evidence rather than an invented dropped notification.
 *
 * `runPump` delivers by driving the pump's continuation **inline on the calling thread** (`startCoroutine`
 * with no interceptor in the completion's context), so it can be called from a non-suspending callback in the
 * middle of a fold. Any dispatcher — unconfined included — would queue the resumption instead and run it
 * *after* the fold that must be interrupted, which is precisely the interleaving under test. The driver
 * asserts the body completed rather than suspending, so a change that made a pump suspend cannot turn this
 * into a silent no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositePeersWriterTest {

    /**
     * A newer peers snapshot published while an older one is still in flight must win.
     *
     * The interleaving is driven through the one callback the fold makes into a value it does not own: the
     * `transportId in <ply peer set>` membership test. The fold reads ply A, then reads ply B — and B's set
     * is instrumented so that, at that instant, ply A loses a transport peer and completes an **entire**
     * recompute of its own. Pre-fix that inner recompute publishes the correct `{self, via-B}` and the outer
     * one then overwrites it with the `{self, via-A, via-B}` it snapshotted before A's loss, leaving `peers`
     * advertising a peer that is gone with no trigger left to correct it — `sendTo` throwing
     * `PeerNotConnected` for a peer `peers` calls reachable, forever.
     *
     * The instant differs from the production race (a real preemption lands between the lock release and the
     * `_peers` write, not mid-fold) but the property is identical and is the whole of the defect: **publish
     * order need not follow snapshot order when the publish is not serialised.** A single writer owns the
     * whole read-modify-write, so the inner request becomes a queued fold that runs strictly after this one —
     * and the last publish reflects the last snapshot at *every* such instant, not merely at this one.
     */
    @Test
    fun aStalePeersSnapshotCannotLandAfterANewerOne() = runTest {
        val reads = mutableListOf<String>()
        var onPlyBRead: (() -> Unit)? = null
        fun instrumented(label: String, peers: Set<PeerId>): Set<PeerId> = HookedPeerSet(peers) {
            reads += label
            val hook = onPlyBRead
            if (label == PLY_B && hook != null) {
                onPlyBRead = null
                hook()
            }
        }

        val plyA = DrivenPeersSeam(A_LOCAL, setOf(A_LOCAL))
        val plyB = DrivenPeersSeam(B_LOCAL, setOf(B_LOCAL))
        // Preload each ply's Announce so the (plyId, transportId) → compositeId mapping is learned in ply
        // order — A's entry first, which is the order the fold below depends on and asserts.
        plyA.delegate.deliver(A_REMOTE, PlyFrame.encode(PlyFrame.Announce(VIA_A)))
        plyB.delegate.deliver(B_REMOTE, PlyFrame.encode(PlyFrame.Announce(VIA_B)))

        val composite = CompositeLoom(
            plies = listOf(PlyId(PLY_A) to OnePlyLoom(plyA), PlyId(PLY_B) to OnePlyLoom(plyB)),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ).host(Pattern("host"))

        // Both remotes join their transports, so both composite peers are reachable.
        plyA.set(instrumented(PLY_A, setOf(A_LOCAL, A_REMOTE)))
        plyA.runPump()
        plyB.set(instrumented(PLY_B, setOf(B_LOCAL, B_REMOTE)))
        plyB.runPump()
        val both = setOf(composite.selfId, VIA_A, VIA_B)
        assertEquals(
            both,
            await(composite) { it == both },
            "precondition: both composite peers are reachable, one through each ply",
        )

        // Arm the interleaving: while the next fold is between its read of ply A and its read of ply B, ply A
        // loses its remote and completes a recompute of its own.
        reads.clear()
        onPlyBRead = {
            plyA.set(instrumented(PLY_A, setOf(A_LOCAL)))
            assertTrue(plyA.runPump(), "the inner recompute must actually be driven, or nothing is interleaved")
        }
        // A re-`Announce` is the surviving trigger — the composite re-announces on every Woven transition and
        // to every newcomer, so an inbound Announce driving a recompute is the ordinary case.
        plyB.delegate.deliver(B_REMOTE, PlyFrame.encode(PlyFrame.Announce(VIA_B)))
        runCurrent()

        val reachable = setOf(composite.selfId, VIA_B)
        val observed = await(composite) { it == reachable }
        assertAll(
            {
                assertEquals(
                    listOf(PLY_A, PLY_B),
                    reads.take(2),
                    "precondition: the interrupted fold must read ply A before ply B, or it is not holding a " +
                        "stale value for A when the interleaving lands and this test proves nothing",
                )
            },
            { assertTrue(onPlyBRead == null, "precondition: the interleaving hook must have fired") },
            {
                assertEquals(
                    reachable,
                    observed,
                    "a peers snapshot taken BEFORE ply A lost its remote was published AFTER the recompute " +
                        "that observed the loss. Nothing recomputes peers again — the fold has no periodic " +
                        "backstop — so peers advertises an unreachable peer for the life of the seam. Every " +
                        "snapshot→publish pair must run on the single peers writer.",
                )
            },
        )
        composite.close(CloseReason.Normal)
    }

    /**
     * A ply whose peer set returns to the value its own pump last delivered emits **nothing** — and the
     * composite must still be right, because no request for that transition exists or is owed.
     *
     * Here the lagging ply gains a remote while its pump is descheduled and loses it again before the pump
     * ever runs, so it delivers zero values (asserted). A fold that re-reads `seam.peers.value` catches the
     * transient on a *different* ply's trigger and publishes a peer that is not reachable; the surviving
     * request set is then empty, so nothing corrects it. Serialising the writer cannot help — there is no
     * request to serialise. Only mirroring each ply's *delivered* peer set onto its handle makes the silence
     * harmless, and it does so structurally: a delivery is suppressed exactly when the mirror already holds
     * the right value.
     */
    @Test
    fun aPlyReturningToItsLastDeliveredPeerSetStillLeavesPeersCorrect() = runTest {
        val lagging = DrivenPeersSeam(A_LOCAL, setOf(A_LOCAL))
        val prompt = DrivenPeersSeam(B_LOCAL, setOf(B_LOCAL))
        lagging.delegate.deliver(A_REMOTE, PlyFrame.encode(PlyFrame.Announce(VIA_A)))
        prompt.delegate.deliver(B_REMOTE, PlyFrame.encode(PlyFrame.Announce(VIA_B)))

        val composite = CompositeLoom(
            plies = listOf(PlyId(PLY_A) to OnePlyLoom(lagging), PlyId(PLY_B) to OnePlyLoom(prompt)),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ).host(Pattern("host"))

        // Both mappings are learned, but neither remote is on its transport yet, so only self is reachable.
        val alone = setOf(composite.selfId)
        assertEquals(alone, await(composite) { it == alone }, "precondition: no remote is on its transport yet")

        // The lagging ply's remote arrives — but its pump has not been dispatched, so nothing is delivered.
        lagging.set(setOf(A_LOCAL, A_REMOTE))
        // The prompt ply's remote arrives and its pump runs. This edge is the ONLY trigger in flight, and a
        // fold that re-reads the plies still sees the lagging ply's remote here: it has not left yet.
        prompt.set(setOf(B_LOCAL, B_REMOTE))
        prompt.runPump()

        // Now the lagging ply's remote leaves again. Its value is back to what its pump last delivered, so a
        // real StateFlow collector emits NOTHING — asserted, so this test cannot pass by delivering a trigger
        // the production pump would never have received.
        lagging.set(setOf(A_LOCAL))
        assertFalse(
            lagging.runPump(),
            "the lagging pump must deliver nothing — otherwise this test is not modelling StateFlow's " +
                "per-collector conflation and proves nothing",
        )

        val reachable = setOf(composite.selfId, VIA_B)
        assertEquals(
            reachable,
            await(composite) { it == reachable },
            "peers stranded on a peer whose transport membership round-tripped past a descheduled pump — the " +
                "fold read a live ply value that no surviving request would ever re-read. The fold must read " +
                "the peer set the ply's own pump last DELIVERED, mirrored onto its handle.",
        )
        composite.close(CloseReason.Normal)
    }

    /**
     * Await a composite peer set matching [predicate] under virtual time, returning the observed value (or
     * the current one if it never matched, so the caller's `assertEquals` names the strand).
     */
    private suspend fun await(composite: Seam, predicate: (Set<PeerId>) -> Boolean): Set<PeerId> {
        withTimeoutOrNull(AWAIT_MILLIS) { composite.peers.first { predicate(it) } }
        return composite.peers.value
    }

    /** A [Loom] that hands back the one prebuilt ply [Seam] a test drives. */
    private class OnePlyLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam
        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    /**
     * A permanently-`Woven` ply seam whose `peers` is driven in two separable steps, so a test can reproduce
     * a descheduled collector exactly:
     *
     *  - [set] moves the ply's transport peer set (and therefore `peers.value`) with **no** delivery;
     *  - [runPump] runs one iteration of `StateFlowImpl.collect` — re-read the latest value and deliver it
     *    **only if it differs from the value this collector last delivered** — and does so *inline on the
     *    calling thread*, so it can be driven from inside a fold.
     */
    private class DrivenPeersSeam(
        selfId: PeerId,
        initial: Set<PeerId>,
        val delegate: FakeSeam = FakeSeam(selfId = selfId, initialPeers = initial),
    ) : Seam by delegate {
        private var current = initial
        private var lastDelivered: Set<PeerId>? = null
        private var pump: FlowCollector<Set<PeerId>>? = null

        override val peers: StateFlow<Set<PeerId>> = object : StateFlow<Set<PeerId>> {
            override val value: Set<PeerId> get() = current
            override val replayCache: List<Set<PeerId>> get() = listOf(current)
            override suspend fun collect(collector: FlowCollector<Set<PeerId>>): Nothing {
                check(pump == null) { "DrivenPeersSeam models a single-collection peers pump" }
                pump = collector
                // A real collector always delivers its first value (`oldState == null`).
                lastDelivered = current
                collector.emit(current)
                awaitCancellation()
            }
        }

        /** Move the ply's transport peer set without delivering — models a pump that has not been dispatched. */
        fun set(peers: Set<PeerId>) {
            current = peers
        }

        /** Run one collect-loop iteration; returns whether a value was actually delivered. */
        fun runPump(): Boolean {
            val collector = checkNotNull(pump) { "the composite has not subscribed to this ply's peers yet" }
            val latest = current
            if (latest == lastDelivered) return false
            lastDelivered = latest
            var outcome: Result<Unit>? = null
            val delivery: suspend () -> Unit = { collector.emit(latest) }
            // No ContinuationInterceptor in the completion's context, so `intercepted()` is a no-op and the
            // body runs inline on this thread instead of being dispatched.
            delivery.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
            checkNotNull(outcome) { "the peers pump suspended; this driver only models a non-suspending one" }
                .getOrThrow()
            return true
        }
    }

    /**
     * A peer set that reports every membership test to [onContains] — the composite's only callback into a
     * value it does not own, and therefore the one place a test can interleave inside a peers fold.
     *
     * `equals`/`hashCode` delegate so this compares equal to the plain set it wraps: `runPump`'s
     * conflation check and the tests' assertions must see set equality, not instance identity.
     */
    private class HookedPeerSet(
        private val delegate: Set<PeerId>,
        private val onContains: () -> Unit,
    ) : Set<PeerId> by delegate {
        override fun contains(element: PeerId): Boolean {
            onContains()
            return delegate.contains(element)
        }

        override fun equals(other: Any?): Boolean = delegate == other
        override fun hashCode(): Int = delegate.hashCode()
        override fun toString(): String = delegate.toString()
    }

    private companion object {
        const val PLY_A = "ply-a"
        const val PLY_B = "ply-b"
        const val AWAIT_MILLIS = 2_000L
        val A_LOCAL = PeerId("ply-a-local")
        val A_REMOTE = PeerId("ply-a-remote")
        val B_LOCAL = PeerId("ply-b-local")
        val B_REMOTE = PeerId("ply-b-remote")
        val VIA_A = PeerId("composite-reachable-via-a")
        val VIA_B = PeerId("composite-reachable-via-b")
    }
}
