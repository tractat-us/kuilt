package us.tractat.kuilt.core.composite

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.coroutines.CoroutineContext

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
 * origin by a [PlyInboundGate]; application payloads emerge as [Swatch] values.
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
 */
internal class CompositeSeam(
    initial: List<Pair<PlyId, Seam>>,
    private val rendezvous: Rendezvous,
    private val desired: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
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

    // Live capability rollup: the union of the constituent Looms' roles for currently-Woven plies,
    // folded with those plies' live Seam availabilities. Seeded roleless/Unknown — before the first
    // recomputeCapability() no ply has been consulted, so a confident verdict here would be a
    // fabrication for the whole pre-recompute window (#1712).
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

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // PlyId -> live ply, in send-preference (insertion) order. A LinkedHashMap so
    // broadcast/sendTo iterate most-preferred-first. Guarded by [lock].
    private val live = LinkedHashMap<PlyId, PlyHandle>()

    /**
     * One live ply. [woven] and [availability] are the capability rollup's **mirrored** inputs: the values
     * this ply's own pumps last *delivered*, never a live re-read of [seam]. Both are guarded by [lock] and
     * written only by this ply's pumps in [attachPly]. See [publishCapability] for why the fold must not
     * read the seam directly.
     */
    private class PlyHandle(
        val seam: Seam,
        val job: Job,
        var woven: Boolean,
        var availability: FabricAvailability,
    )

    // The SINGLE capability writer. Every snapshot→publish pair runs here, so no two can interleave and
    // no stale publish can land last (#1712). Started before any ply attaches so no request is missed;
    // dies with [scope] on close. NOT a `limitedParallelism(1)` confinement crutch — this is the
    // dedicated-writer-draining-a-Channel pattern, and it owns the whole read-modify-write, not just
    // the write.
    private val capabilityWriter: Job = scope.launch {
        for (request in capabilityRecomputes) publishCapability()
    }

    init {
        // Aggregate state is derived from the per-ply map: any ply Woven => Woven, else Weaving
        // (empty or all-torn are both recoverable Weaving, #1367). A derived write via update():
        // no-ops once close() has latched the terminal Torn, so a late rollup can never clobber it.
        _plies
            .onEach { stateGate.update(rollup(it.values.toList())) }
            .launchIn(scope)

        // Seed the initial plies (already woven by CompositeLoom).
        initial.forEach { (id, seam) -> attachPly(id, seam) }

        // Reconcile on every desired-set change. The first emission equals the
        // initial set, so it produces no attach/detach.
        desired
            .onEach { reconcile(it) }
            .launchIn(scope)
    }

    private suspend fun reconcile(desiredSet: List<Pair<PlyId, Loom>>) {
        val desiredIds = desiredSet.map { it.first }.toSet()
        // Detach: live plies no longer desired.
        val liveIds = lock.withLock { live.keys.toList() }
        liveIds.forEach { id -> if (id !in desiredIds) detachPly(id) }
        // Attach: desired plies not yet live — weave their loom now.
        for ((id, loom) in desiredSet) {
            val alreadyLive = lock.withLock { id in live }
            if (!alreadyLive) attachPly(id, loom.weave(rendezvous))
        }
    }

    private fun attachPly(id: PlyId, seam: Seam) {
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
        lock.withLock {
            live[id] = PlyHandle(
                seam = seam,
                job = job,
                // Seeded from the ply's current values. Both pumps below deliver their first value
                // unconditionally (a StateFlow collector always emits once — `oldState == null`), so these
                // seeds are immediately superseded by delivered ones.
                woven = seam.state.value is SeamState.Woven,
                availability = seam.capability.value.availability,
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

        seam.incoming
            .onEach { swatch -> onPlyFrame(id, swatch) }
            .launchIn(plyScope)

        // Recompute peers on transport membership changes; re-announce to newcomers.
        seam.peers
            .onEach { newPeers ->
                recomputePeers()
                if (newPeers.size > 1 && seam.state.value is SeamState.Woven) {
                    // Best-effort re-announce to newcomers — swallow a torn-ply send (#535).
                    runCatchingCancellable { seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
                }
            }
            .launchIn(plyScope)

        // Request a fold of this ply's roles. Belt-and-braces: the two pumps above each fire on
        // subscription with the ply's current value and request one too, and the requests conflate.
        recomputeCapability()
    }

    private suspend fun detachPly(id: PlyId) {
        // Remove from the live map under the lock; the suspending teardown runs outside it.
        val handle = lock.withLock { live.remove(id) } ?: return
        // Stop this ply's pumps FIRST so a resuming pump can't resurrect the
        // _plies/idMap entries we are about to purge.
        handle.job.cancelAndJoin()
        // Remove from the per-ply map (now safe) so the aggregate rolls up
        // without this ply — empty => Weaving, never a transient terminal Torn.
        _plies.update { it - id }
        // Purge this ply's learned mappings so a re-attach starts clean.
        lock.withLock { idMap.keys.removeAll { it.first == id } }
        handle.seam.close(CloseReason.Normal)
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
     * Recompute the live [capability] over the currently-[SeamState.Woven] plies. Roles come from the
     * constituent [Loom]s (held in [desired]) — a ply's medium does not change under it, so roles are
     * static. **Availability comes from the plies' own [Seam.capability]**, not their Looms: the Loom
     * value is the static pre-connect claim, and folding it here would launder an observer-less ply's
     * claim into a confident live verdict (#1712).
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
     * ### The fold reads MIRRORS, never the plies (#1712)
     * Every input folded here — [PlyHandle.woven], [PlyHandle.availability] — is the value that ply's own
     * pump last **delivered**, mirrored onto its handle under [lock] before the pump requested the recompute.
     * The fold must never re-read `seam.state.value` / `seam.capability.value`, and the reason is not
     * tidiness — it is the difference between converging and wedging forever:
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
     */
    private fun publishCapability() {
        val snapshot = lock.withLock {
            val wovenEntries = live.entries.filter { it.value.woven }
            val wovenIds = wovenEntries.map { it.key }.toSet()
            // Roles ARE static on the Loom — a ply's medium does not change under it.
            val roles = desired.value.filter { (id, _) -> id in wovenIds }
                .flatMap { (_, loom) -> loom.capability().roles }.toSet()
            // Availability comes from the woven plies' SEAMS (mirrored above), not their Looms: the Loom
            // value is the static pre-connect claim, and folding it would launder an observer-less ply's
            // claim into a confident live verdict.
            val availabilities = wovenEntries.map { it.value.availability }
            roles to availabilities
        }
        // Three-way lattice fold over the woven plies' Seam availabilities (mirrors
        // CompositeLoom.capability): any Available ⇒ Available; else any Unknown ⇒ Unknown
        // (best-effort — don't collapse an unproven ply to Unavailable); else Unavailable.
        val availability = when {
            snapshot.second.any { it is FabricAvailability.Available } -> FabricAvailability.Available
            snapshot.second.any { it is FabricAvailability.Unknown } ->
                FabricAvailability.Unknown("no ply available; some unknown")
            // "no ply woven" was accurate while the fold read the Looms' static claims — the only way to
            // reach this branch was an empty woven set. Since the fold reads the plies' LIVE seams it is
            // also reached with plies woven but every one of them reporting Unavailable, so the reason has
            // to cover both (#1712).
            else -> FabricAvailability.Unavailable("no woven ply reports an available path")
        }
        _capability.value = TransportCapability(roles = snapshot.first, availability = availability)
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

    private fun recomputePeers() {
        val reachable = lock.withLock {
            buildSet {
                add(selfId)
                idMap.forEach { (key, compositeId) ->
                    val (plyId, transportId) = key
                    val seam = live[plyId]?.seam
                    if (seam != null && transportId in seam.peers.value) add(compositeId)
                }
            }
        }
        _peers.value = reachable
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

    /** Every live, non-torn ply that can reach [peer], in send-preference order. Call under [lock]. */
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
        // The gate guarantees state correctness; cancel is a plain resource release (non-joining).
        scope.coroutineContext[Job]?.cancel()
        spool.close()
        toClose.forEach { it.seam.close(reason) }
    }

    private companion object {
        fun mintCompositeId(initial: List<Pair<PlyId, Seam>>): PeerId =
            PeerId("composite-" + initial.joinToString("-") { it.second.selfId.value })
    }
}
