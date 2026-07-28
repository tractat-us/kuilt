package us.tractat.kuilt.core.composite

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.SeamStateGate
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.coroutines.CoroutineContext

/**
 * One ply woven by [CompositeLoom] before `weave()` returns: its [id], the woven [seam], and the
 * static [roles] of the [Loom] that wove it.
 *
 * [roles] travels with the seam rather than being looked up from the desired set later, because the
 * desired set is caller-mutable and `weave` suspends — see [CompositeLoom.weave] and, for why the
 * capability fold must not read anything live, `CompositeSeam.publishCapability`.
 */
internal class InitialPly(
    val id: PlyId,
    val seam: Seam,
    val roles: Set<TransportRole>,
)

/**
 * **Diagnostic only.** A consistent snapshot of the three things `CompositeSeam.publishPeers` folds,
 * taken under one lock acquisition. Produced by `CompositeSeam.peersStrandOrNull` for the real-threaded
 * concurrency probes' on-timeout report (#1784); no library code consumes it.
 *
 * @property idMap the learned `(plyId, transport peer) → composite peer` mappings.
 * @property livePlies the plies still attached — `close()` clears these without purging [idMap].
 * @property wouldPublish what a recompute would publish *right now*, from the real fold — whose inputs are
 *   each ply's MIRRORED peer set, not a live seam read (#1784). Compare against `Seam.peers`: a peer here
 *   but not there means a recompute is owed; the publish is serialised, so a persistent gap is a lost
 *   trigger or a dead writer, never a lost publish.
 */
internal class PeersStrand(
    val idMap: Map<Pair<PlyId, PeerId>, PeerId>,
    val livePlies: Set<PlyId>,
    val wouldPublish: Set<PeerId>,
)

/**
 * The composite `Seam` woven by [CompositeLoom]. Presents a single peer set,
 * `incoming` flow, and send surface over a set of constituent plies that may
 * change while the session is live.
 *
 * **Dynamic plies:** [initial] is the set woven by [CompositeLoom] before
 * `weave()` returns. Thereafter the composite collects [desired] and reconciles:
 * a [PlyId] that appears is woven and attached; one that disappears is closed and
 * detached. The static (fixed-list) case is the degenerate one where [desired]
 * never changes after its first value.
 *
 * **Identity:** Each peer mints a composite [selfId] once from [initial] and never
 * recomputes it, so it is stable across attach/detach. On each ply reaching
 * [SeamState.Woven] the peer broadcasts a [PlyFrame.Announce] so the far side can
 * map `(plyId, transportId) → compositeId`.
 *
 * **Send:** [broadcast] wraps the payload in a [PlyFrame.Data] envelope and sends
 * over every live, non-torn ply. [sendTo] resolves the composite id to every
 * reachable `(ply, transportId)` in send-preference order and tries them in turn,
 * falling through to the next when a ply tears mid-send.
 *
 * **Receive:** Inbound [PlyFrame.Data] frames are de-duplicated and reordered per
 * origin by a [PlyInboundGate]; application payloads emerge as [Swatch] values. A frame that cannot be
 * processed — a malformed [PlyFrame] from a peer being the reachable case — is **dropped and reported**
 * through [onPlyFailure]; it never kills the ply's inbound pump (see [attachPly], #1788).
 *
 * **Thread-safety.** This type is correct under a *multi-threaded* dispatcher — the
 * injected [dispatcher] is only the scope for the internal coroutines (the reconcile,
 * rollup, announce, per-ply inbound and peers pumps); it is **not** a mutual-exclusion
 * mechanism. The mutable state shared between caller threads (`broadcast`/`sendTo`) and
 * those pumps — the live-ply map, the learned `(plyId, transportId) → compositeId`
 * mapping, and the per-origin [PlyInboundGate] (itself documented single-collection) — is
 * guarded by a single [reentrantLock]. The outbound sequence is an atomic counter and the terminal
 * lifecycle runs through a [SeamStateGate]: the rollup pump publishes the derived aggregate via
 * `update()` and [close] latches `Torn` via `tear()` (single-shot, subsuming the old `closed` atomic),
 * so no in-flight rollup write can clobber the terminal state and teardown ordering is irrelevant to
 * state correctness. Suspending ply calls (`Seam.broadcast`/`sendTo`/`close`) are NEVER invoked while
 * the lock is held: callers snapshot the target plies under the lock, release, then send/close outside it.
 *
 * **Each derived flow has exactly one writer.** [state], [capability] and [peers] are all
 * *snapshot-then-publish* over lock-guarded state, and the publish cannot happen under the lock (emitting
 * to a `StateFlow` can resume an unconfined collector inline, running consumer code under a lock this class
 * treats as non-reentrant). Snapshots are totally ordered by the lock; publishes are not — so each strand
 * needs a serialising writer or a stale publish lands last and, with no periodic backstop, wedges the flow
 * permanently. All three now have one: [SeamStateGate] for [state] (#1135), [capabilityWriter] for
 * [capability] (#1712), [peersWriter] for [peers] (#1784). The writer is only half of it: each fold must
 * also read **mirrored** [PlyHandle] fields rather than a live foreign `StateFlow`, or a suppressed
 * `StateFlow` delivery loses the *trigger* and there is no request for a writer to serialise — see
 * [publishCapability] and [publishPeers].
 *
 * **Inbound backpressure.** Application payloads are delivered through a [Spool] whose
 * capacity and overflow behaviour are governed by [policy] (default [DeliveryPolicy.Reliable]).
 * The unbounded `Channel.UNLIMITED` inbox is gone; unbounded inbound queues are structurally
 * unrepresentable per the fabric-backpressure epic. [Spool.deliver] is called OUTSIDE the lock
 * — it may suspend under a SUSPEND-overflow policy, and a [reentrantLock] must never be held
 * across a suspension point.
 *
 * @param policy Governs the inbound [Spool]'s capacity and overflow behaviour.
 *   Defaults to [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 * @param dispatcher The scope for the seam's internal coroutines (scheduling only — see the
 *   thread-safety note above). Production callers pass `Dispatchers.Default`; test callers
 *   pass a dispatcher derived from the test scheduler so the seam's pumps share the same
 *   virtual clock as the test, driving reconciliation eagerly.
 * @param onPlyFailure Raised whenever one ply fails to attach, detach, or process an inbound frame —
 *   see [reconcile], [attachPly] and [PlyReconcileException]. Best-effort and non-suspending; defaults
 *   to a silent absorb.
 */
internal class CompositeSeam(
    initial: List<InitialPly>,
    private val rendezvous: Rendezvous,
    private val desired: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val onPlyFailure: (PlyReconcileException) -> Unit = {},
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = PlyInboundGate()

    // Outbound envelope sequence. Stamped by concurrent broadcast/sendTo callers.
    private val outSeq = atomic(0L)

    // Single lock guarding `live`, `idMap`, and the inbound `gate`. Every read and mutation
    // of those happens under it; suspending ply calls are always done OUTSIDE it (snapshot
    // under the lock, act after releasing).
    private val lock = reentrantLock()

    // Minted once from the initial set; never recomputed, so it survives ply churn.
    override val selfId: PeerId = mintCompositeId(initial)

    // Terminal-latching state holder. The rollup pump feeds derived aggregates through update():
    // a fully-degraded composite (empty OR every ply torn) publishes recoverable Weaving and reverts
    // to Woven when a ply re-attaches (#1367). close() latches the unconditionally-terminal Torn via
    // tear() (single-shot) — the only producer of Torn here, so no derived/revivable Torn exists.
    private val stateGate = SeamStateGate(SeamState.Weaving)
    override val state: StateFlow<SeamState> = stateGate.state

    private val _plies = MutableStateFlow<Map<PlyId, SeamState>>(emptyMap())
    override val plies: StateFlow<Map<PlyId, SeamState>> = _plies.asStateFlow()

    // Live capability rollup: the union of the constituent Looms' roles (captured per ply at attach) for
    // currently-Woven plies, folded with those plies' announced Seam availabilities. Seeded roleless/Unknown
    // — before the first recomputeCapability() no ply has been consulted, so a confident verdict here would
    // be a fabrication for the whole pre-recompute window (#1712).
    private val _capability = MutableStateFlow(
        TransportCapability(emptySet(), FabricAvailability.Unknown("composite capability not yet computed")),
    )
    override val capability: StateFlow<TransportCapability> = _capability.asStateFlow()

    // Recompute requests, drained by the single [capabilityWriter] coroutine. CONFLATED because every
    // recompute reads current state, so only the latest request matters and a burst may collapse.
    // This is the single-writer serialisation that keeps snapshot→publish atomic — see [publishCapability].
    private val capabilityRecomputes = Channel<Unit>(Channel.CONFLATED)

    // (plyId, transport id) -> composite id; built as Announce frames arrive. Guarded by [lock].
    private val idMap = mutableMapOf<Pair<PlyId, PeerId>, PeerId>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Peers recompute requests, drained by the single [peersWriter] coroutine. CONFLATED for the same
    // reason as [capabilityRecomputes]: every recompute reads current state, so only the latest request
    // matters. This is the single-writer serialisation that keeps snapshot→publish atomic — see
    // [publishPeers] (#1784).
    private val peersRecomputes = Channel<Unit>(Channel.CONFLATED)

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // PlyId -> live ply, in send-preference (insertion) order. A LinkedHashMap so
    // broadcast/sendTo iterate most-preferred-first. Guarded by [lock].
    private val live = LinkedHashMap<PlyId, PlyHandle>()

    /**
     * One live ply, and the complete set of inputs the [capability] rollup and the [peers] fold read —
     * nothing either fold reads lives anywhere else.
     *
     * [woven], [availability] and [transportPeers] are **mirrored**: the values this ply's own pumps last
     * *delivered*, never a live re-read of [seam]. All three are guarded by [lock] and written only by this
     * ply's pumps in [attachPly]. [roles] is captured **once at attach** and immutable thereafter — a ply's
     * medium does not change under it, so its Loom's roles are static by contract and there is nothing to
     * re-read.
     *
     * See [publishCapability] for why the capability fold must read only these, and never the seam, the
     * [Loom], or the caller-mutable desired set; [publishPeers] applies the identical argument to the peers
     * fold, which is why [transportPeers] exists at all rather than the fold re-reading `seam.peers.value`.
     */
    private class PlyHandle(
        val seam: Seam,
        val job: Job,
        val roles: Set<TransportRole>,
        var woven: Boolean,
        var availability: FabricAvailability,
        var transportPeers: Set<PeerId>,
    )

    // The SINGLE capability writer. Every snapshot→publish pair runs here, so no two can interleave and
    // no stale publish can land last (#1712). NOT a `limitedParallelism(1)` confinement crutch — this is
    // the dedicated-writer-draining-a-Channel pattern, and it owns the whole read-modify-write, not just
    // the write. Dies with [scope] on close.
    private val capabilityWriter: Job

    // The SINGLE peers writer — the identical pattern, for the identical reason, on the third strand of the
    // same class (#1784; `state` was fixed by [SeamStateGate] in #1135 and `capability` by
    // [capabilityWriter] in #1712). Unlike [capabilityWriter] it is NOT left to die with [scope]:
    // [collapsePeers] cancelAndJoins it first, because [_peers] has no gate to make a late publish a no-op
    // (#1816).
    private val peersWriter: Job

    init {
        // Started FIRST, and from `init` rather than a property initializer, so that "before any ply
        // attaches" is enforced by statement order here instead of by this property happening to be
        // declared below every field publishCapability touches — a declaration reorder must not be able
        // to make the writer observe an uninitialised field.
        //
        // UNGUARDED BY DESIGN, AND THAT IS A CONSTRAINT ON THE BODY, NOT AN OVERSIGHT: no consumer-authored
        // call may enter this loop. An escaping throw kills the flow's ONLY writer, and because [scope] is a
        // SupervisorJob nothing else dies and nothing restarts it — [capability] then freezes at its last
        // value for the life of the seam with a lone stderr trace, the silent-death mode `4f93c843` had to
        // guard the reconcile pump against. Safe today precisely because [publishCapability] folds mirrored
        // handle state and makes no foreign call; keep it that way rather than adding a catch.
        capabilityWriter = scope.launch {
            for (request in capabilityRecomputes) publishCapability()
        }

        // Same contract, same ordering argument: started before any ply attaches, so no pump can enqueue a
        // recompute request that has no writer to drain it. The no-consumer-call constraint above applies
        // identically here — [publishPeers] folds mirrored state only.
        peersWriter = scope.launch {
            for (request in peersRecomputes) publishPeers()
        }

        // Aggregate state is derived from the per-ply map: any ply Woven => Woven, else Weaving
        // (empty or all-torn are both recoverable Weaving, #1367). A derived write via update():
        // no-ops once close() has latched the terminal Torn, so a late rollup can never clobber it.
        _plies
            .onEach { stateGate.update(rollup(it.values.toList())) }
            .launchIn(scope)

        // Seed the initial plies (already woven by CompositeLoom, which captured each one's Loom roles
        // from the same snapshot it wove the seam from — see [InitialPly]). attachPly's registered/declined
        // verdict is trivially "registered" here: `state` cannot be Torn before the constructor returns.
        initial.forEach { ply -> attachPly(ply.id, ply.seam, ply.roles) }

        // Reconcile on every desired-set change. The first emission equals the
        // initial set, so it produces no attach/detach.
        desired
            .onEach { reconcile(it) }
            .launchIn(scope)
    }

    /**
     * Reconcile the live ply set against [desiredSet]: detach what is no longer desired, weave and attach
     * what newly is.
     *
     * ### This must not throw (#1784)
     * It runs on the seam's **single long-lived reconcile collector** (`desired.onEach { reconcile(it) }`
     * in `init`), and every ply call it makes — `Loom.capability()`, `Loom.weave()`, the ply `Seam`'s
     * `close()` — is *consumer-authored*. An escaping exception cancels that collector, and because
     * [scope] is a [SupervisorJob] it takes nothing with it and nothing restarts it: the seam never
     * attaches or detaches a ply again, while `state` stays cheerfully `Woven` and [plies] keeps reporting
     * the stale set. Nothing observable says reconciliation has stopped — the whole trace is a stack trace
     * on stderr, which is exactly how it survived (a *passing* `CompositeSeamCloseTornConcurrencyTest` run
     * emitted ~3,100 of them). A `Loom` is consumer-authored; a library that stops reconciling forever
     * because one consumer's `weave` threw is the defect, not the consumer.
     *
     * So each ply is guarded **independently**: one failure neither stops its siblings in the same pass
     * nor reaches the collector, and it is raised through [onPlyFailure] with the ply's identity and the
     * exception rather than absorbed in silence.
     *
     * ### `runCatchingCancellable` is not enough — the callee's OWN cancellation (#1784)
     * The guard here deliberately catches [Throwable] and then re-checks *this* coroutine, rather than
     * using `runCatchingCancellable`. That helper rethrows **every**
     * [kotlin.coroutines.cancellation.CancellationException], including one the callee threw of its own
     * accord rather than one signalling this coroutine's cancellation — and the natural way to write a
     * dialling `Loom` is `withTimeout(dialTimeout) { dial(rendezvous) }`. `withTimeout` throws
     * `TimeoutCancellationException` (a `CancellationException`) **to its caller**, without cancelling
     * that caller's job. Rethrown from here it escapes the collector on a live, non-`Torn` composite, and
     * because the escaping throwable *is* a `CancellationException` the collector is **cancelled, not
     * failed**: [onPlyFailure] is never invoked and there is not even a stack trace on stderr. That is
     * this very defect with its one remaining diagnostic thread cut — a dial timeout being the single
     * likeliest way a ply fails to come up.
     *
     * `currentCoroutineContext().ensureActive()` is the discriminator: it rethrows this job's own
     * cancellation when the coroutine really was cancelled (structured concurrency preserved) and falls
     * through otherwise, so a fabric's dial timeout becomes an ordinary ply failure. `NwLoom` in
     * `:kuilt-nw` defuses its own timeout by converting it to a plain `NwUnreachableException`, but that
     * is one fabric's convention; `Loom.weave` is consumer-authored and this class's whole premise is
     * surviving whatever a consumer's `Loom` does. (The obligation is now stated on `Loom.weave` too —
     * a convention only one fabric knows about is not a convention.)
     *
     * ### A failed ply is retried, not blacklisted
     * A ply that fails to attach is simply left un-live, so the next [desired] emission tries it again. A
     * failure ledger would need an invalidation rule and every plausible rule wedges — a fabric that is
     * merely unavailable *right now* (radio off, permission not yet granted) would be locked out of the
     * composite forever. Retrying cannot spin either: [desired] is a `StateFlow`, so a retry needs a *new*
     * desired value, not merely a failed ply.
     */
    private suspend fun reconcile(desiredSet: List<Pair<PlyId, Loom>>) {
        val desiredIds = desiredSet.map { it.first }.toSet()
        // Detach: live plies no longer desired.
        val liveIds = lock.withLock { live.keys.toList() }
        for (id in liveIds) {
            if (id in desiredIds) continue
            try {
                detachPly(id)
            } catch (failure: Throwable) {
                // Genuinely our own cancellation → rethrow; anything else (including a
                // CancellationException the callee minted itself) is this ply's failure. See the KDoc.
                currentCoroutineContext().ensureActive()
                raisePlyFailure(id, PlyReconcileException.Phase.DETACH, failure)
            }
        }
        // Attach: desired plies not yet live — weave their loom now.
        for ((id, loom) in desiredSet) {
            if (lock.withLock { id in live }) continue
            // Read the terminal state AFTER the `live` check, and per ply — the order is the correctness
            // argument, not a style choice. [close] latches `Torn` BEFORE it takes [lock] to drain `live`,
            // so `live`'s lock release happens-before this read: a ply that looks un-live *because of that
            // drain* is guaranteed to observe `Torn` here, and the pass stops. Reading the two the other
            // way round would let a stale not-`Torn` read pair with a post-drain `live` read.
            //
            // This is the cheap outer guard, not the safety net — [attachPly] fuses the same check with the
            // registration, so dropping this one would not corrupt state. It would mean actually dialling a
            // fresh transport (a socket, a radio) for a seam already dead, only to close it again; before
            // BOTH checks existed the pass re-wove the entire desired set onto the corpse (#1784).
            if (state.value is SeamState.Torn) return
            try {
                attachDesiredPly(id, loom)
            } catch (failure: Throwable) {
                // Genuinely our own cancellation → rethrow; anything else (including a
                // CancellationException the callee minted itself — a dial `withTimeout` is the common
                // case) is this ply's failure. See the KDoc.
                currentCoroutineContext().ensureActive()
                raisePlyFailure(id, PlyReconcileException.Phase.ATTACH, failure)
                // attachPly registers the handle BEFORE launching its pumps, so a throw partway through
                // can leave a half-built ply in `live`. Purge it — itself best-effort, since the purge
                // closes a consumer seam — so the next emission retries from a clean slate.
                try {
                    detachPly(id)
                } catch (_: Throwable) {
                    // Same discriminator; the purge's own failure is already reported by [detachPly].
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }

    /**
     * Weave one newly-desired ply and attach it.
     *
     * Roles are read from the [Loom] **before** weaving. The order is load-bearing: both calls are
     * consumer-authored and either may throw, and reading roles first leaves a throwing `capability()` no
     * already-woven transport to orphan. Roles are static by contract, so one read is the whole story and
     * the capability fold never calls back into the [Loom] (#1712).
     *
     * `weave` suspends, so [close] may latch the terminal `Torn` and drain `live` while it runs. The
     * freshly woven seam is then **closed rather than attached**: [close] is single-shot and has already
     * returned, so a ply attached after it is a live transport nothing will ever tear down. That window is
     * not theoretical — [close] cancels the reconcile collector *asynchronously*, and a reconcile pass need
     * hit no further cancellable suspension point, so the pass genuinely runs to completion against a
     * cleared `live` map and re-weaves the entire desired set onto a dead seam (#1784).
     */
    private suspend fun attachDesiredPly(id: PlyId, loom: Loom) {
        // Roles are read from the Loom HERE, once, and captured onto the handle — the fold never calls
        // back into this consumer-authored method. Static by contract, so once is enough (#1712).
        val roles = loom.capability().roles
        val seam = loom.weave(rendezvous)
        if (!attachPly(id, seam, roles)) discardOrphanedPly(id, seam)
    }

    /**
     * Tear down a ply that was woven but could not be attached, because [close] latched `Torn` first.
     *
     * **`NonCancellable` is load-bearing, not belt-and-braces.** The only thing that makes [attachPly]
     * decline is [close] having latched `Torn` — and [close] runs `tear()` → drain `live` →
     * `scope.cancel()` with no suspension point after the drain, so by the time this runs, this coroutine
     * is almost always *already cancelled*. `Seam.close` is a suspending call into a consumer-authored
     * transport and suspends on any real one (a WebSocket close handshake, a `Mutex`, a channel send), so
     * without the shield its first cancellable suspension point would throw and skip the close — leaking
     * precisely the live transport this method exists to reclaim, with [close] single-shot and already
     * returned. The same idiom, for the same reason, is in `NwLoom.weave`.
     */
    private suspend fun discardOrphanedPly(id: PlyId, seam: Seam) {
        withContext(NonCancellable) {
            try {
                seam.close(CloseReason.Normal)
            } catch (failure: Throwable) {
                // Inside the shield there is no "our own cancellation" left to preserve — this block's Job
                // is parented to [NonCancellable] — so `ensureActive` cannot fire and every throwable,
                // including a `CancellationException` the consumer's `close` minted itself (a close
                // handshake `withTimeout`), is this ply's failure. `runCatchingCancellable` here would
                // instead rethrow that one case straight past the guard. See [reconcile].
                currentCoroutineContext().ensureActive()
                // SALVAGE, not DETACH: this ply never entered `live` and never appeared in [plies], so
                // DETACH's "its pumps are stopped and it is out of the composite" would be a false report
                // on the consumer's own logger. Which ply, doing what, and why *is* the diagnosis.
                raisePlyFailure(id, PlyReconcileException.Phase.SALVAGE, failure)
            }
        }
    }

    /**
     * Raise one ply's failure to the consumer. Best-effort: a throwing observer is absorbed — **including**
     * one that throws a [kotlin.coroutines.cancellation.CancellationException].
     *
     * That total absorption is why this is `catch (Throwable)` and not `runCatchingCancellable` (#1788).
     * [onPlyFailure] is a **non-suspending** consumer callback, invoked outside any cancellation contract:
     * there is no cancellation of ours for it to be reporting, so a `CancellationException` arriving from it
     * can only be one it minted itself and there is nothing to preserve by rethrowing. Rethrowing is
     * actively harmful — this is called from inside the inbound pump's own guard, so the rethrow escapes
     * that guard, and a `CancellationException` escaping an `onEach` body **cancels the coroutine
     * silently**: the pump dies, the ply stays `Woven`, and nothing is reported. That is the very defect
     * this hook was added to make observable, reached through the hook itself.
     */
    private fun raisePlyFailure(id: PlyId, phase: PlyReconcileException.Phase, cause: Throwable) {
        try {
            onPlyFailure(PlyReconcileException(id, phase, cause))
        } catch (_: Throwable) {
            // Deliberately total — see the KDoc. A consumer's logger must never be able to kill a pump.
        }
    }

    /**
     * Register [seam] as ply [id] and start its pumps. Returns whether it was registered — `false` means
     * [close] has already latched `Torn`, and the caller owns closing the seam it just wove.
     */
    private fun attachPly(id: PlyId, seam: Seam, roles: Set<TransportRole>): Boolean {
        // Per-ply pumps run under a child Job so detach cancels exactly this ply.
        val job = SupervisorJob(scope.coroutineContext[Job])
        val plyScope = CoroutineScope(scope.coroutineContext + job)

        // Register in `live` BEFORE launching the pumps. The inbound pump can process an Announce
        // the instant it starts (a fabric may deliver one as its first/buffered frame, and on a
        // multi-threaded dispatcher the pump runs concurrently with this method) — if `live` were
        // not yet populated, recomputePeers would store the mapping in idMap but drop it from the
        // reachable set, leaving the peer unreachable until some later trigger. There is no
        // suspension point between here and the launches, and attach/detach are serialized through
        // the single reconcile collector, so no pump can observe a half-built or stale handle.
        //
        // The terminal check is FUSED with the registration, in the same critical section [close] drains
        // `live` in, so attach and close cannot interleave: `tear()` latches `Torn` before [close] takes
        // this lock, so a registration that wins the lock is inside close()'s snapshot and gets torn down
        // with the rest, while one that loses it observes `Torn` and declines. Checking outside the lock
        // would be check-then-act — the very race [SeamStateGate] exists to remove.
        // Seeded from the ply's current values, read BEFORE taking the lock. These three are
        // consumer-authored property getters, and this class's rule is that no foreign code runs while the
        // lock is held — a pathological getter would otherwise stall every sender. Hoisting is free here:
        // all three pumps below deliver their first value unconditionally (a StateFlow collector always
        // emits once — `oldState == null`), so a seed that goes stale between this read and the
        // registration is superseded either way, and the seeded window is a *pending* delivery, never a
        // swallowed one.
        val seedWoven = seam.state.value is SeamState.Woven
        val seedAvailability = seam.capability.value.availability
        val seedTransportPeers = seam.peers.value
        lock.withLock {
            if (state.value is SeamState.Torn) return false
            live[id] = PlyHandle(
                seam = seam,
                job = job,
                // Captured once — static by contract, so no pump mirrors this and nothing re-reads it.
                roles = roles,
                woven = seedWoven,
                availability = seedAvailability,
                transportPeers = seedTransportPeers,
            )
        }

        seam.state
            .onEach { s ->
                // Mirror what THIS pump observed onto the handle BEFORE requesting the fold, so the fold
                // never reads state no trigger announced — see [publishCapability].
                lock.withLock { live[id]?.woven = s is SeamState.Woven }
                _plies.update { it + (id to s) }
                // A ply changing Woven state changes which Looms' roles union in — request a recompute.
                recomputeCapability()
            }
            .launchIn(plyScope)

        // A ply's own capability is a LIVE value (an nw ply follows its path monitor), so the rollup
        // must SUBSCRIBE, not merely sample at attach/detach/state-change. Without this pump a ply whose
        // device path drops while its state stays Woven — exactly the #1478 grace window — would leave
        // the composite publishing a stale, confident Available (#1712). Same shape as the state pump; the
        // request is serialised onto the single capabilityWriter, so concurrent pumps cannot interleave.
        //
        // This pump's DELIVERED value is the rollup's input (mirrored onto the handle). It is not merely a
        // wakeup for a fresh read of the seam — that distinction is the whole of [publishCapability]'s
        // correctness argument.
        seam.capability
            .onEach { cap ->
                lock.withLock { live[id]?.availability = cap.availability }
                recomputeCapability()
            }
            .launchIn(plyScope)

        // Re-announce on every Woven transition (cold start + recovery). Best-effort: the
        // ply may tear between this Woven emission and the send (the Seam contract throws
        // IllegalStateException on a Torn send), and the far side re-learns the mapping on
        // the next Woven/peers event regardless — so swallow a failed announce (#535).
        seam.state
            .onEach {
                if (it is SeamState.Woven) {
                    runCatchingCancellable { seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
                }
            }
            .launchIn(plyScope)

        // The inbound pump — GUARDED, because this is the one pump whose input is bytes from another
        // peer. "What if this throws" is therefore not a question about consumer code but about anything
        // any peer can put on the wire: [PlyFrame.decode] rejects a malformed frame, the gate is keyed on
        // peer-chosen origins and sequences, and `spool.deliver` can fail on a closing seam.
        //
        // An escape here was not merely a dead pump (the ply staying `Woven` while the composite kept
        // advertising it as a send target). `plyScope`'s job is a [SupervisorJob], and suppressing PARENT
        // propagation is exactly what routes an unhandled throw to the global handler — so on
        // Kotlin/Native, where kuilt installs no `setUnhandledExceptionHook`, the runtime default
        // **aborted the process**. A single 2-byte frame from any peer crashed a shipped iOS app (#1788).
        //
        // So a malformed frame is a DROPPED frame: reported through [onPlyFailure], never fatal, and the
        // ply keeps delivering the frames after it. Dropping rather than tearing the ply is deliberate —
        // tearing would hand any peer a one-frame way to remove a ply from someone else's composite.
        // `catch (Throwable)` + `ensureActive()` and NOT `runCatchingCancellable`, the same discriminator
        // for the same reason as [reconcile]: that helper rethrows a `CancellationException` the callee
        // minted itself (a consumer `Seam`'s own `withTimeout`), which on a long-lived pump means the pump
        // is *cancelled, not failed* — dead silently, with [onPlyFailure] never invoked and not even a
        // stack trace left behind.
        //
        // **The `onEach` guard alone is not the whole pump.** `.onEach { … }.launchIn(scope)` desugars to
        // `scope.launch { flow.onEach { … }.collect() }`, so the `try` below is INSIDE the collector and
        // sees only what [onPlyFrame] throws. A throw raised by `seam.incoming` **itself** propagates out
        // of `collect`, out of the `launch` body, and straight down the abort route above. `incoming` is
        // the likeliest of a ply's five flows to do it, being the only one that is not a `StateFlow` but an
        // arbitrary consumer-authored `Flow<Swatch>` (`MuxClientLoom` has the shape in tree:
        // `flow { emitAll(current().incoming) }` over a `?: error(…)`). Hence the [catch] — an upstream
        // throw legitimately ENDS the flow, which is what an upstream throw means, but it ends it with a
        // diagnosis instead of a `SIGABRT`. [catch] needs no `ensureActive` of its own: `catchImpl`
        // rethrows when the throwable is this coroutine's own cancellation cause and catches otherwise —
        // the same discriminator, already built in.
        seam.incoming
            .onEach { swatch ->
                try {
                    onPlyFrame(id, swatch)
                } catch (failure: Throwable) {
                    // Genuinely our own cancellation (detach, or the seam closing) → rethrow so the pump
                    // stops as structured concurrency intends; anything else is this frame's failure.
                    currentCoroutineContext().ensureActive()
                    raisePlyFailure(id, PlyReconcileException.Phase.INBOUND, failure)
                }
            }
            .catch { failure -> raisePlyFailure(id, PlyReconcileException.Phase.INBOUND, failure) }
            .launchIn(plyScope)

        // Mirror this ply's peer set and request a fold — and NOTHING ELSE, least of all anything that
        // suspends. Its DELIVERED value is the peers fold's input (mirrored onto the handle BEFORE the
        // request, exactly as the two pumps above do for the capability rollup). It is not merely a wakeup
        // for a fresh read of `seam.peers` — that distinction is the whole of [publishPeers]'s correctness
        // argument, and getting it wrong leaves the wedge reachable even with the writer in place (#1784).
        //
        // ### Why the re-announce is a SEPARATE collector, and must stay one (#1784)
        // `onEach` is sequential and `Seam.broadcast` "suspends until accepted by the local transport" —
        // unbounded on a backpressured or black-holing transport, with no timeout here. Fused into this
        // collector, emission N's parked send queues emission N+1's **mirror write** behind it. Because the
        // fold reads only the mirror, that freezes the fold's ONLY input: no trigger anywhere can observe
        // this ply's true peer set until the send returns, so `peers` keeps advertising a departed peer while
        // [resolveSendTargets] — live-reading, correctly — finds no candidate, and `sendTo` throws
        // [PeerNotConnected] for a peer `peers` calls reachable. Bounded by the send in general, absorbing
        // under a transport that black-holes without tearing. So the mirror stays in a collector with no
        // suspension point in it, and the send lives below — the same split `seam.state` already has between
        // its mirror pump and its Woven re-announce pump.
        seam.peers
            .onEach { newPeers ->
                lock.withLock { live[id]?.transportPeers = newPeers }
                recomputePeers()
            }
            .launchIn(plyScope)

        // Re-announce to newcomers, isolated exactly as the Woven re-announce above is, and for the same
        // reason: it makes a suspending consumer-authored call. Best-effort — swallow a torn-ply send (#535).
        //
        // Collector order versus the mirror pump above is irrelevant: the frame carries only [selfId], which
        // is immutable, and the `Woven` gate reads `seam.state` live (as it always did). The two collectors
        // conflate independently, so this one may skip an intermediate peers value the mirror pump saw —
        // already within contract, since the far side re-learns the mapping on the next Woven/peers event.
        seam.peers
            .onEach { newPeers ->
                if (newPeers.size > 1 && seam.state.value is SeamState.Woven) {
                    runCatchingCancellable { seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
                }
            }
            .launchIn(plyScope)

        // Request a fold of this ply's roles. Belt-and-braces: the two pumps above each fire on
        // subscription with the ply's current value and request one too, and the requests conflate.
        recomputeCapability()
        return true
    }

    /**
     * Detach ply [id]: stop its pumps, purge the state it contributed, and close its transport.
     *
     * ### Once the handle leaves `live`, the teardown is committed — hence `NonCancellable` (#1784)
     * Ownership passes at the `live.remove` below: after it, [close]'s drain cannot see this handle, so if
     * this method does not close the transport **nobody will**. Yet the very next statement,
     * `handle.job.cancelAndJoin()`, throws whenever this collector has been cancelled — which is exactly
     * the churn-across-[close] interleaving: reconcile wins `live.remove(id)`, [close] then latches `Torn`,
     * drains a snapshot *without* this ply and cancels the scope, and the unshielded join throws, leaking
     * the ply's transport with [close] single-shot and already returned. So the whole teardown runs under
     * [NonCancellable] — the identical argument, for the identical reason, as [discardOrphanedPly]. It is
     * bounded, not open-ended: the only wait is joining pumps that have just been cancelled.
     *
     * ### The foreign close is the guarded step, so the recomputes always run
     * `Seam.close` is consumer-authored and may throw. Letting it escape would skip [recomputePeers] and
     * [recomputeCapability] *after* `idMap` was already purged — so [peers] would keep advertising a
     * composite peer reachable only through this now-detached ply (`sendTo` throwing [PeerNotConnected]
     * for a peer `peers` calls reachable) and [capability] would keep this ply's roles in the union. Every
     * trigger that could correct either is gone with this ply's cancelled pumps, so both would stay stale
     * **indefinitely**. Absorbing the close and raising it through [onPlyFailure] keeps the recomputes on
     * the only path that always executes.
     */
    private suspend fun detachPly(id: PlyId) {
        // Remove from the live map under the lock; the suspending teardown runs outside it.
        val handle = lock.withLock { live.remove(id) } ?: return
        withContext(NonCancellable) {
            // Stop this ply's pumps FIRST so a resuming pump can't resurrect the
            // _plies/idMap entries we are about to purge.
            handle.job.cancelAndJoin()
            // Remove from the per-ply map (now safe) so the aggregate rolls up
            // without this ply — empty => Weaving, never a transient terminal Torn.
            _plies.update { it - id }
            // Purge this ply's learned mappings so a re-attach starts clean.
            lock.withLock { idMap.keys.removeAll { it.first == id } }
            // Guarded so the two recomputes below are unconditional — see the KDoc. `catch (Throwable)`
            // rather than `runCatchingCancellable`: inside the shield this block's Job is parented to
            // [NonCancellable], so a `CancellationException` arriving here can only be one the consumer's
            // `close` minted itself, and rethrowing it would skip the very recomputes this guard exists
            // for. `ensureActive` is kept as the discriminator for symmetry with [reconcile]; it cannot
            // fire here.
            try {
                handle.seam.close(CloseReason.Normal)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                raisePlyFailure(id, PlyReconcileException.Phase.DETACH, failure)
            }
        }
        recomputePeers()
        // This ply's roles no longer union in — request a recompute.
        recomputeCapability()
    }

    /**
     * Request a [capability] recompute. Non-blocking and safe to call from any pump or thread: the work
     * itself runs on the single [capabilityWriter] coroutine (see [capabilityRecomputes]).
     *
     * Callers may hold no lock — [publishCapability] re-takes the non-reentrant [lock] — but note the
     * request is **asynchronous**, so [capability] converges shortly after this returns rather than
     * during it.
     */
    private fun recomputeCapability() {
        // CONFLATED: a burst of triggers collapses to one recompute, which reads the LATEST state anyway.
        // trySend never blocks and never fails on a conflated channel, so a pump can fire this freely.
        capabilityRecomputes.trySend(Unit)
    }

    /**
     * Recompute the live [capability] over the currently-[SeamState.Woven] plies. Roles are the
     * constituent [Loom]'s, captured onto the [PlyHandle] at attach — a ply's medium does not change under
     * it, so roles are static and one read at attach is the whole story. **Availability comes from the
     * plies' own [Seam.capability]**, not their Looms: the Loom value is the static pre-connect claim, and
     * folding it here would launder an observer-less ply's claim into a confident live verdict (#1712).
     *
     * Because that source is live, [attachPly] **subscribes** to each ply's [Seam.capability] — sampling
     * only at attach/detach/state-change would miss a path drop that leaves the ply [SeamState.Woven].
     *
     * **Runs on the single [capabilityWriter] coroutine only.** Snapshot-then-publish is a read-modify-write
     * and the [_capability] write happens *outside* [lock] (emitting to a StateFlow can resume an unconfined
     * collector inline, and running arbitrary consumer code under [lock] risks a deadlock on a lock this
     * class treats as non-reentrant). Two concurrent recomputes could therefore interleave and let a STALE
     * publish land last. Serialising every snapshot→publish pair onto one coroutine removes the interleaving
     * by construction, which is why this is private and only [capabilityWriter] calls it; everything else
     * goes through [recomputeCapability].
     *
     * ### The fold reads the HANDLES, never anything live (#1712)
     * Every input folded here comes off the [PlyHandle] and nowhere else. [PlyHandle.woven] and
     * [PlyHandle.availability] are the values that ply's own pump last **delivered**, mirrored onto the
     * handle under [lock] before the pump requested the recompute; [PlyHandle.roles] was captured at attach
     * and cannot change. The fold must never re-read `seam.state.value` / `seam.capability.value`, and the
     * reason is not tidiness — it is the difference between converging and wedging forever:
     *
     * A `StateFlow` conflates emissions **per collector, against that collector's own last-emitted value**
     * (`StateFlowImpl.collect` re-reads `_state.value` after being dispatched, then emits only
     * `if (oldState == null || oldState != newState)`). So a ply whose availability round-trips `X → Y → X`
     * while its pump is descheduled delivers **nothing** — and issues no request. Serialising the writer
     * cannot help: this is a lost *trigger*, not a lost *update*; the request simply does not exist.
     *
     * Folding live ply values then wedges permanently. Some earlier request — possibly conflated, possibly
     * another ply's — is drained by a fold whose *joint* read of all plies lands between their transitions,
     * publishing a verdict already stale for the silent ply. Nothing re-reads it. Because the fold below is
     * any-`Available`-wins, that frozen verdict is a confident `Available` for a path that has already
     * dropped: an absorbing state, observed on real threads as `CompositeCapabilityConcurrencyTest` wedging
     * with `composite=Available` while both plies report `Unavailable`.
     *
     * Mirroring makes the silence harmless, and does so *structurally* rather than making it rarer:
     *  - a delivery is suppressed **exactly** when the ply's value equals the pump's last-delivered value —
     *    which is precisely when the mirror already holds the right value, so no fold is owed;
     *  - any other change differs from the last-delivered value and therefore **must** be delivered, and
     *    each delivery writes the mirror before calling [recomputeCapability];
     *  - a request always yields a later drain (a conflated channel drops the *oldest*, so after the final
     *    `trySend` the cell is non-empty and the writer's next receive — hence its next lock acquisition —
     *    happens-after that mirror write).
     *
     * So once the plies quiesce, some fold runs strictly after the final mirror write and reads every ply's
     * true value. The composite can lag only by a *pending* delivery, never by a *swallowed* one.
     *
     * The same argument is why [PlyHandle.roles] is captured at attach instead of resolved from the desired
     * set at fold time. That set is a caller-mutable `StateFlow` as well, so a list flapping
     * `[a,b] → [a] → [a,b]` past a descheduled `reconcile` collector nets to no attach and no detach — hence
     * requests no recompute — while a fold that ran mid-flap published roles missing `b`'s with nothing left
     * to correct them. Milder than the availability strand (roles are descriptive, not a reachability claim)
     * but the identical lost-trigger shape, and the claim above only holds if the fold reads *no* live value.
     *
     * A corollary: the fold makes **no foreign call**. `Loom.capability()` is consumer-authored and this
     * class treats [lock] as non-reentrant, so resolving roles here at all meant keeping that call outside
     * the locked section to avoid deadlocking — or merely stalling every sender — behind an arbitrarily slow
     * callee. Capturing at attach removes the call from this path, so there is nothing left to keep out.
     */
    private fun publishCapability() {
        // ONLY the handles are read, and every input comes off them: roles captured at attach, availability
        // mirrored by the plies' own pumps. Availability comes from the plies' SEAMS, not their Looms — the
        // Loom value is the static pre-connect claim, and folding it would launder an observer-less ply's
        // claim into a confident live verdict. No foreign call and no live StateFlow read happens here, so
        // the whole snapshot is taken under the lock with nothing left to hoist out of it.
        val (roles, availabilities) = lock.withLock {
            val woven = live.values.filter { it.woven }
            woven.flatMap { it.roles }.toSet() to woven.map { it.availability }
        }
        // Three-way lattice fold over the woven plies' announced availabilities (mirrors
        // CompositeLoom.capability): any Available ⇒ Available; else any Unknown ⇒ Unknown
        // (best-effort — don't collapse an unproven ply to Unavailable); else Unavailable.
        val availability = when {
            availabilities.any { it is FabricAvailability.Available } -> FabricAvailability.Available
            availabilities.any { it is FabricAvailability.Unknown } ->
                FabricAvailability.Unknown("no ply available; some unknown")
            // "no ply woven" was accurate while the fold read the Looms' static claims — the only way to
            // reach this branch was an empty woven set. Since the fold reads what the plies THEMSELVES
            // reported it is also reached with plies woven but every one of them Unavailable, so the reason
            // has to cover both (#1712).
            else -> FabricAvailability.Unavailable("no woven ply reports an available path")
        }
        _capability.value = TransportCapability(roles = roles, availability = availability)
    }

    // Any-live ⇒ Woven; otherwise Weaving. A fully-degraded composite — empty OR every ply currently
    // torn — is recoverable [SeamState.Weaving], NEVER a derived terminal [SeamState.Torn] (#1367): a
    // later ply re-attach brings the aggregate back to Woven. `Torn` is reserved for the close
    // decision (`tear()`) and self-driven transport death, and is unconditionally terminal.
    private fun rollup(states: List<SeamState>): SeamState =
        when {
            states.any { it is SeamState.Woven } -> SeamState.Woven
            else -> SeamState.Weaving
        }

    private suspend fun onPlyFrame(plyId: PlyId, swatch: Swatch) {
        when (val frame = PlyFrame.decode(swatch.toByteArray())) {
            is PlyFrame.Announce -> {
                // Announce keys idMap by (plyId, transport sender) → composite id.
                val sender = swatch.sender ?: return
                lock.withLock { idMap[plyId to sender] = frame.compositeId }
                recomputePeers()
            }
            is PlyFrame.Data -> {
                // Data uses the in-frame originId — the transport sender may be a gateway.
                // The gate is single-collection by contract; the lock restores that invariant
                // across the concurrent per-ply inbound pumps. Snapshot under the lock and
                // deliver OUTSIDE it — spool.deliver may suspend (SUSPEND-overflow policy)
                // and must never be called while holding a reentrantLock.
                val payloads = lock.withLock { gate.accept(frame) }
                payloads.forEach { payload ->
                    spool.deliver(Swatch(payload = payload, sender = frame.originId))
                }
            }
        }
    }

    /**
     * Request a [peers] recompute. Non-blocking and safe to call from any pump or thread: the work itself
     * runs on the single [peersWriter] coroutine (see [peersRecomputes]).
     *
     * Callers may hold no lock — [publishPeers] re-takes the non-reentrant [lock] — but note the request is
     * **asynchronous**, so [peers] converges shortly after this returns rather than during it.
     */
    private fun recomputePeers() {
        // CONFLATED: a burst of triggers collapses to one recompute, which reads the LATEST state anyway.
        // trySend never blocks and never fails on a conflated channel, so a pump can fire this freely.
        peersRecomputes.trySend(Unit)
    }

    /**
     * Recompute the reachable composite [peers]: self, plus every composite id the learned
     * `(plyId, transportId) → compositeId` mapping resolves to whose transport id is still a member of that
     * ply's peer set.
     *
     * **Runs on the single [peersWriter] coroutine only** — hence private, with everything else going
     * through [recomputePeers]. Snapshot-then-publish is a read-modify-write and the [_peers] write happens
     * *outside* [lock] (emitting to a `StateFlow` can resume an unconfined collector inline, and running
     * arbitrary consumer code under [lock] risks a deadlock on a lock this class treats as non-reentrant).
     * Snapshots taken under the lock are totally ordered; publishes made outside it are **not**. Before
     * #1784 up to 16 callers per session setup raced here — each of four plies' `seam.peers` pumps firing
     * twice, plus the `Announce`-driven calls from the four inbound pumps — and a caller preempted in the
     * handful of instructions between its lock release and its write published a snapshot taken *before* the
     * joiner existed, landing `{selfId}` **last**. Nothing then recomputes: this fold has no periodic
     * backstop, firing only on an `Announce`, a ply membership change, or a detach, so `peers` wedged at
     * size 1 permanently with every coroutine legitimately suspended and every dispatcher worker parked.
     * Serialising every snapshot→publish pair onto one coroutine removes the interleaving by construction —
     * the last publish reflects the last snapshot. This is the third strand of one class in this file:
     * `state` ([SeamStateGate], #1135), `capability` ([capabilityWriter], #1712), and now `peers`.
     *
     * ### The fold reads the HANDLES, never anything live (#1784)
     * The single writer alone is **not sufficient**, and this is the half that is easy to omit. Until #1784
     * the fold resolved reachability from `seam.peers.value` — a live foreign `StateFlow` — and
     * [publishCapability]'s lost-trigger argument applies verbatim: a `StateFlow` conflates emissions per
     * collector against *that collector's* last-emitted value, so a ply whose peer set round-trips
     * `X → Y → X` while its pump is descheduled delivers **nothing** and requests nothing. Serialising the
     * writer cannot help, because there is no request to serialise — the lost thing is the *trigger*, not
     * the *update*. A surviving trigger (another ply's edge, an `Announce`) then drives a fold whose joint
     * read of every ply lands mid-flap and publishes a reachability verdict already stale for the silent
     * ply, with nothing left to correct it. Both directions wedge and both are absorbing: a stale
     * *inclusion* leaves [peers] advertising a peer only [sendTo] can disprove (throwing [PeerNotConnected]
     * for a peer [peers] calls reachable — the same defect [detachPly] guards against), and a stale
     * *omission* hides a peer that is in fact reachable, which is the `host.peers.first { it.size == 2 }`
     * stall itself.
     *
     * Mirroring [PlyHandle.transportPeers] makes that silence harmless *structurally*, on the identical
     * three-step argument spelled out in [publishCapability]: a delivery is suppressed exactly when the
     * ply's value equals the pump's last-delivered value — precisely when the mirror already holds the right
     * value and no fold is owed; any other change must be delivered, and each delivery writes the mirror
     * before requesting; and a request always yields a later drain, so once the plies quiesce some fold runs
     * strictly after the final mirror write. The composite can lag by a *pending* delivery, never by a
     * *swallowed* one.
     *
     * **What bounds "pending" is a design constraint, not an accident.** A pending delivery is only harmless
     * while the mirror pump can actually run, so that pump is kept free of suspension points: it mirrors,
     * requests, and returns. Anything suspending in it — above all a consumer-authored `Seam.broadcast`,
     * contractually "suspends until accepted by the local transport" — makes the lag last as long as that
     * call, and a transport that black-holes without tearing (#1655) makes it permanent, because the mirror is
     * the fold's *only* input. That is why [attachPly] collects `seam.peers` twice and the re-announce lives
     * in the second collector; the pinning test is
     * `CompositePeersWriterTest.aPlyWhoseReAnnounceNeverReturnsStillLetsTheMirrorAdvance`.
     *
     * ### Liveness in [reachablePeersLocked] is `live[plyId] != null` only — it does NOT screen a torn ply
     * A ply that has latched `Torn` but is not yet detached still contributes its mirror to that fold, where
     * [resolveSendTargets] filters it. **That is now safe by contract, not by convention (#1816):**
     * [Seam.peers] requires a `Torn` seam's roster to be exactly `{ selfId }`, so a torn ply's mirrored peer
     * set contributes nothing but its own transport id, which is not in [idMap] as a remote. Every fabric is
     * checked against it by `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn` rather than trusted — a
     * fabric that cannot honour it yet declares `collapsesPeersOnTear = false` with a tracking issue, so the
     * exposure is enumerated instead of unknown.
     *
     * [CompositeSeam] was itself the in-tree violator — [close] left [_peers] frozen at the pre-close roster
     * forever, and a composite is type-legal as a *ply* of another composite ([CompositeLoom] is a [Loom]).
     * Fixed in [collapsePeers], which explains why the naive `_peers.value = setOf(selfId)` races this
     * writer.
     *
     * Adding `handle.woven` to [reachablePeersLocked]'s predicate looks like a further hardening and **is
     * not**: the pump that mirrors [PlyHandle.woven] requests only a *capability* recompute, never a peers
     * one, so `woven` would become an input to that fold with no trigger — a fresh instance of the
     * lost-trigger defect above (a ply reaching `Woven` after its peers were mirrored would stay
     * stale-*exclusive*, permanently). Taking it safely means also requesting a peers recompute from the
     * state pump, which is a behaviour change this fold's tests do not cover. The contract obligation is the
     * durable fix; do not smuggle the predicate change in alongside it.
     *
     * ### Why [resolveSendTargets] still reads the live ply peers
     * Deliberate, not an oversight. The lost-trigger argument bites on a **published derived value with no
     * backstop**; [sendTo] resolves its candidates afresh on every call, so it is its own backstop and can
     * never strand. A live read is also strictly the better input there — a candidate the transport has just
     * dropped is skipped rather than attempted, and a stale-optimistic one falls through to the next
     * candidate by design (#542).
     */
    private fun publishPeers() {
        // The fold itself lives in [reachablePeersLocked] so the diagnostic in [peersStrandOrNull] evaluates
        // the SAME predicate rather than a restatement that could drift (#1804). Snapshot under the lock,
        // publish outside it.
        val reachable = lock.withLock { reachablePeersLocked() }
        _peers.value = reachable
    }

    /**
     * The composite peers [publishPeers] would publish from the current state. Call under [lock].
     *
     * Extracted so the diagnostic in [peersStrandOrNull] can call the **same** fold rather than restate
     * it. A restatement drifts: the two conditions below — the ply must still be live, *and* the transport
     * peer must still be in that ply's **mirrored** peer set — are each a reason an entry in [idMap] is
     * *correctly* absent from [peers], and a diagnostic that mirrors only one of them reports a lost publish
     * where there is none.
     *
     * **The input is the mirror, never the live seam (#1784).** Reachability is decided from
     * [PlyHandle.transportPeers] — the value that ply's own peers pump last *delivered* — and deliberately
     * **not** from `live[plyId]?.seam?.peers?.value`. Re-reading the seam here reintroduces the lost-trigger
     * wedge: a ply whose peer set round-trips `X → Y → X` while its pump is descheduled delivers nothing, so
     * no recompute is requested, so a fold driven by some *other* trigger can publish a verdict already stale
     * for the silent ply with nothing left to correct it. [publishPeers] carries the full argument. Anyone
     * "simplifying" this line back to a live read should read that first.
     */
    private fun reachablePeersLocked(): Set<PeerId> = buildSet {
        add(selfId)
        idMap.forEach { (key, compositeId) ->
            val (plyId, transportId) = key
            val handle = live[plyId]
            if (handle != null && transportId in handle.transportPeers) add(compositeId)
        }
    }

    /**
     * **Diagnostic only.** Everything [reachablePeersLocked] folds, captured under one [lock] acquisition, or
     * `null` if the lock was busy. Read by the real-threaded concurrency probes' on-timeout snapshot; it
     * is `internal`, takes no part in any code path, and nothing in the library calls it.
     *
     * It exists because it is the **only** observable that decides why a composite's [peers] can stall
     * short of the expected set (#1784). The fold has **no periodic backstop** — it fires only on an
     * `Announce`, a ply membership change, or a detach — so very different failures present identically, as
     * total quiescence with every worker parked:
     *  - [PeersStrand.wouldPublish] contains a peer [peers] does not ⇒ a recompute is **owed**. Since the
     *    publish is now serialised on [peersWriter], a *lost publish* is no longer representable (that was
     *    the #1784 defect, fixed): a persistent divergence therefore means the **request** never happened or
     *    can never be served — a lost *trigger* (some fold input advanced without a `trySend`), or
     *    [peersWriter] itself is dead. Read once and it may simply be a request still in flight; read twice,
     *    unchanged, and it is one of those two. **Discount a torn seam before reading it that way:** on a
     *    seam [close] has run, [collapsePeers] cancels the writer deliberately, so "the writer is dead" is
     *    the normal terminal state there and says nothing — check [state] first (#1816).
     *  - [PeersStrand.wouldPublish] **equals** [peers] while `peers` is short of what the test expects ⇒ the
     *    fold's own **inputs** are wrong, not its publishing — exhaustively, since the fold is total over its
     *    three inputs. Any of: a ply's *mirrored* peer set ([PlyHandle.transportPeers]) never advanced (its
     *    pump not yet dispatched, or blocked); [PeersStrand.idMap] lacks the expected
     *    `(plyId, transportId)` entry; or that ply is absent from [PeersStrand.livePlies]. Compare against
     *    the ply seams' live `peers` to tell the first from the rest.
     *  - [PeersStrand.idMap] lacks the expected `(plyId, transportId)` entry — **empty** in the limit ⇒ the
     *    `Announce` was never recorded, so the failure is upstream of the peers strand entirely. A *partial*
     *    `idMap` is genuinely reachable, not just the empty case: both announce sends are best-effort and
     *    swallowed ([attachPly]), so one ply can learn a mapping its sibling never did.
     *
     * Neither the mesh membership of the underlying plies nor either composite's [peers] can tell those
     * apart — the mesh reads as formed in both.
     *
     * **Why this returns [wouldPublish] and not just [idMap].** An entry in `idMap` absent from `peers`
     * is *correct*, not lost, whenever [reachablePeersLocked]'s predicate rejects it — the ply is no
     * longer live (`close()` clears `live` without purging `idMap` or recomputing) or the transport peer
     * has left that ply's peer set (the far composite closed its ply seams). Both happen by design in the
     * close-heavy probes, so a diagnostic comparing `idMap` against `peers` directly would announce a lost
     * publish on nearly every post-close render. Handing back the real fold's output makes the comparison
     * correct by construction instead of by restatement.
     *
     * [tryLock] rather than [withLock] deliberately: this is called from a **failure reporting path**
     * while the system under test is wedged. A blocking read that met a permanently-held lock would
     * consume the diagnostic it was written to produce, so a busy lock degrades to `null` — itself a
     * reportable fact — rather than to a second hang.
     */
    internal fun peersStrandOrNull(): PeersStrand? {
        if (!lock.tryLock()) return null
        return try {
            PeersStrand(
                idMap = idMap.toMap(),
                livePlies = live.keys.toSet(),
                wouldPublish = reachablePeersLocked(),
            )
        } finally {
            lock.unlock()
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq.getAndIncrement(), payload))
        // Snapshot the live, non-torn plies under the lock, then send OUTSIDE it.
        val targets = lock.withLock { live.values.toList() }
        targets
            .filter { it.seam.state.value !is SeamState.Torn }
            // Best-effort per ply: a ply can tear between the filter and the send (the Seam
            // contract throws on a Torn send), and the point of bonding plies is that one
            // tearing must not fail a broadcast another ply can carry (#542).
            .forEach { runCatchingCancellable { it.seam.broadcast(bytes) } }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq.getAndIncrement(), payload))
        // Resolve every (ply, transportId) that can reach `peer`, in send-preference order under the
        // lock; send OUTSIDE it. A candidate can tear between the resolve and the send (the Seam
        // contract throws on a Torn send) — the point of bonding plies is that one ply tearing must
        // not fail a sendTo another ply can carry, so fall through to the next candidate (#542).
        val candidates = lock.withLock { resolveSendTargets(peer) }
        for ((handle, transportId) in candidates) {
            val sent = runCatchingCancellable {
                handle.seam.sendTo(transportId, bytes)
                true
            }.getOrDefault(false)
            if (sent) return
        }
        throw PeerNotConnected(peer)
    }

    /**
     * Every live, non-torn ply that can reach [peer], in send-preference order. Call under [lock].
     *
     * Reads the plies' **live** `state`/`peers` on purpose, where [publishPeers] must not — see that
     * method's last section for why a per-call resolution is its own backstop and a published derived
     * value is not.
     */
    private fun resolveSendTargets(peer: PeerId): List<Pair<PlyHandle, PeerId>> =
        buildList {
            for ((plyId, handle) in live) {
                if (handle.seam.state.value is SeamState.Torn) continue
                val transportId = idMap.entries
                    .firstOrNull { (k, v) -> k.first == plyId && v == peer }
                    ?.key?.second
                if (transportId != null && transportId in handle.seam.peers.value) {
                    add(handle to transportId)
                }
            }
        }

    /**
     * Publish the terminal [SeamState.Torn] and tear down every ply.
     *
     * **Correct by construction, not by ordering.** This seam *derives* its aggregate [state] from a
     * live collector — the `init` block's `_plies.onEach { stateGate.update(rollup(...)) }`. The
     * [SeamStateGate] makes teardown ordering irrelevant to state correctness: `tear()` latches `Torn`
     * atomically, and any rollup collector still in flight (or resumed after the cancel below)
     * publishes through `update()`, which is a **no-op once latched**. So the #1135 lost-terminal-Torn
     * race — an in-flight rollup write clobbering the terminal `Torn` — is unrepresentable regardless
     * of whether the scope is cancelled before or after, joined or not. The tactical `cancelAndJoin`
     * the earlier point-fix needed is therefore gone: the scope is cancelled (non-joining) purely to
     * release the pump coroutines. `tear()` also subsumes the old single-shot `closed` atomic.
     *
     * **[peers] is the exception, and it *is* ordering-sensitive** — [_peers] has no gate, so
     * [collapsePeers] must stop the single [peersWriter] before it publishes `{ selfId }`, or an
     * in-flight publish holding a pre-`clear()` snapshot lands last. See that method (#1816).
     */
    override suspend fun close(reason: CloseReason) {
        // Single-shot: tear() latches Torn and returns false for a loser, so teardown runs once.
        if (!stateGate.tear(reason)) return
        // Snapshot the plies to close under the lock; perform the suspending closes outside it.
        val toClose = lock.withLock {
            val snapshot = live.values.toList()
            live.clear()
            snapshot
        }
        collapsePeers()
        // The gate guarantees state correctness; cancel is a plain resource release (non-joining).
        scope.coroutineContext[Job]?.cancel()
        spool.close()
        toClose.forEach { it.seam.close(reason) }
    }

    /**
     * Collapse [peers] to `{ selfId }` — the [Seam.peers] obligation that a `Torn` seam advertises no
     * reachable peer (#1816). Before this, a closed composite reported its pre-close roster forever, and
     * a [CompositeLoom] is a [Loom], so a composite nested as a *ply* of another composite made this
     * seam the in-tree instance of the very defect [publishPeers]' fold is written to survive.
     *
     * ### The one-liner races the writer — this is not `_peers.value = setOf(selfId)`
     * [publishPeers] snapshots under [lock] and writes **outside** it. A writer preempted in that gap
     * holds a snapshot taken *before* `live.clear()` and, resuming after a bare assignment here, lands
     * the pre-close roster **last** — with no periodic backstop and no trigger left, permanently. That is
     * #1784's defect approached from the other side, so the fix is the same shape: stop the single
     * writer *first*. [Job.cancelAndJoin], never `cancel()` — the **join** is what turns "no publish is
     * in flight" from likely into true. It is bounded: the writer's only suspension point is the
     * conflated-channel receive, and [publishPeers] itself never suspends, so the join waits at most for
     * one fold.
     *
     * Any [recomputePeers] request that arrives after this is harmless twice over — the channel has no
     * reader, and `live` is already cleared, so the fold it would run now returns `{ selfId }` anyway.
     *
     * ### Why the shield
     * [close] has already latched the terminal `Torn` by the time this runs, so a caller cancelled
     * mid-teardown would leave the seam permanently `Torn` **and** permanently advertising peers — the
     * exact contract violation, in the window that matters most (a best-effort teardown is usually
     * running *because* something is being cancelled). Both statements inside are bounded and make no
     * consumer-authored call, so shielding them costs nothing.
     */
    private suspend fun collapsePeers() {
        withContext(NonCancellable) {
            peersWriter.cancelAndJoin()
            _peers.value = setOf(selfId)
        }
    }

    private companion object {
        fun mintCompositeId(initial: List<InitialPly>): PeerId =
            PeerId("composite-" + initial.joinToString("-") { it.seam.selfId.value })
    }
}
