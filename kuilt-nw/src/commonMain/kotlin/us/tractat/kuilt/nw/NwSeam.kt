package us.tractat.kuilt.nw

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as withMutex
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.stream.DEFAULT_MAX_FRAME_SIZE
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger("us.tractat.kuilt.nw.NwSeam")

/**
 * Monotonic milliseconds from an arbitrary fixed origin — the production default for [NwSeam]'s
 * `nowMillis`. Mirrors `:kuilt-quilter`'s `SystemMonotonicMillis`: time is a dependency, so the seam
 * takes a provider and a test passes a controlled counter rather than racing a wall clock.
 */
private val seamMonotonicMillis: () -> Long = kotlin.time.TimeSource.Monotonic.markNow().let { origin ->
    { origin.elapsedNow().inWholeMilliseconds }
}

/**
 * Full-mesh [Seam] over an [NwApi] — one peer's symmetric view of an N-peer
 * Network.framework session.
 *
 * ## Architecture B — one demux loop, identity resolved inline
 * [NwApi.bytesReceived] is a SINGLE flow multiplexing every connection. `NwSeam`
 * collects it **exactly once** (one receive loop) and demuxes by [NwConnectionId] —
 * there is no per-connection machine/collector. The seam owns the connection lifecycle
 * from [NwApi.connectionOpened] onward; discovery + dialling belong to `NwLoom` (Task 2.7).
 *
 * Four collectors, all launched [CoroutineStart.UNDISPATCHED] at construction so they
 * subscribe **before** `NwLoom` triggers advertise/browse/dial (subscribe-before-trigger,
 * since [NwApi]'s flows are hot with no replay). They — and the seam's three other coroutines —
 * start from the `init` block at the **foot** of this class; see it for why that position is
 * load-bearing rather than stylistic (#2462).
 *
 *  1. **connectionOpened** — sends our identity frame (a [NwFrameType.Hello] carrying an [NwHello]: this
 *     peer's [PeerId] plus this connection's per-connection dedup nonce).
 *  2. **bytesReceived** — the demux + inline handshake. Each decoded frame is classified by its leading
 *     [NwFrameType] byte (#2425): a [NwFrameType.Hello] on an unresolved connection resolves identity, a
 *     [NwFrameType.Data] on a resolved one is stamped with that sender and handed to the bounded
 *     [deliveryStage] (drained by [deliveryDrainLoop] into [incoming]) so a slow local consumer never
 *     wedges this shared loop's reads for other connections' handshakes (#1415), and a
 *     [NwFrameType.Goodbye] on a draining link ends that drain. **Either type in the wrong position is refused**,
 *     which is what the type byte bought: classification used to be POSITIONAL ("the first frame is the
 *     hello"), so a duplicate hello reached the consumer as data and an early data frame was fed to
 *     `NwHello.decode`.
 *  3. **connectionClosed** — the fast, reason-carrying close EVENT path: evicts the peer (conn-identity
 *     guarded so a deduped loser's close can't evict the survivor) and, when the last remote drops,
 *     **re-forms to [SeamState.Weaving] rather than latching [SeamState.Torn]** (#1513) — peer loss is
 *     recoverable, not terminal.
 *  4. **connectionStates** — the ONE drop-tolerant per-connection [NwConnState] STATE signal (#1539),
 *     reconciled by [reconcileStates], which unifies the former separate viability (#1509) and
 *     closed-markers (#1522) collectors. Each emission is a snapshot of every tracked connection's latest
 *     state; the reconcile dispatches per entry:
 *      - **[NwConnState.PathLost]** — a `ready → waiting` path loss (#1478) that fires NO
 *        [NwApi.connectionClosed]. Arms a per-connection grace timer ([wovenPathGrace]); if the path does not
 *        recover before it expires the connection is evicted (last-remote ⇒ re-form to [SeamState.Weaving],
 *        #1513 — NOT [SeamState.Torn]). The timer lives HERE, not in [NwApi], because only the seam owns an
 *        injectable [scope] (the test dispatcher under `runTest`) — `RealNwApi` runs on a GCD queue with no
 *        injectable clock.
 *      - **[NwConnState.Viable]** — a recovery (or the healthy steady state); cancels any armed grace timer.
 *      - **[NwConnState.Closed]** — the drop-tolerant TEARDOWN authority backstopping collector 3. The
 *        [NwApi.connectionClosed] event is a lossy `tryEmit`; a dropped `failed`/`cancelled` close would strand
 *        a permanent zombie peer. `Closed` is terminal + monotone + dominant STATE, so the seam tears any
 *        still-tracked connection whose state is `Closed` IMMEDIATELY (no grace timer), reusing [removeByConn]
 *        verbatim (tombstone, grace-timer cancel, conn-identity guard, last-remote re-form). Double-fire with
 *        collector 3 is safe: whichever runs first removes + tombstones the conn, the other sees `cs == null` →
 *        unknown-conn no-op.
 *     Reconciling the latest value (rather than reacting to transitions) means a dropped/coalesced signal can
 *     never strand an armed timer, miss a loss, or lose a close; the seam acts ONLY on a state's PRESENCE,
 *     NEVER on a key's absence.
 *
 * ## Duplicate-dial dedup (canonical-nonce rule, direction-free)
 * A full mesh double-dials each pair, producing two connections to the same peer. Each [ConnState]
 * mints a random [nonce][ConnState.nonce] **once, when it is created** (in the `getOrPut` factory —
 * so the nonce is available no matter which of the two loops observes the connection first). Each
 * end sends its nonce in its [NwHello]. When a connection to `remoteId` resolves and another to the
 * same `remoteId` already exists, the survivor is the one with the **smaller canonical link nonce**
 * — `canonicalLinkNonce(myNonce, remoteNonce)`, an order-independent function of the two nonces
 * (sort their hex, join `"lo:hi"`). Because both ends see the same two nonces, both compute the same
 * canonical value and pick the same survivor with **no coordination and no dependence on dial
 * direction or collector ordering**; the loser is **drained** (below) and its later close is a no-op
 * (conn-identity guard). This is a port of `:kuilt-core`'s `MeshSeam` rule — the old direction-based
 * rule could wedge a pair to zero under a multi-threaded dispatcher (direction was written by one
 * collector and read by another with no happens-before); the nonce rule cannot.
 *
 * ## The graceful displacement drain (#2425)
 * The loser used to be dropped from [conns] and cancelled in the same breath. That is the field defect:
 * the seam publishes a peer on the first link to resolve, may rebind it ~10 ms later, and the abrupt
 * cancel destroys whatever the consumer wrote into that window — measured on hardware as 182 bytes lost
 * with `peers` still naming the peer and `state` still [SeamState.Woven] throughout. Since #2425 a
 * displaced link is instead marked **draining** ([draining], keyed by connId because redial churn can
 * produce concurrent drains to one peer):
 *
 *  - it **stays in [conns] and stays resolved**, so its inbound [NwFrameType.Data] is still attributed to
 *    its peer — the #1528 misparse hazard does not apply, because we know whose it is;
 *  - it is **never selected for a send**: [broadcast]/[sendTo] route through [registry], which names the
 *    winner, and [auditRegistryLocked] reports a [registry] entry that ever names a draining connId;
 *  - it is sent exactly **one [NwFrameType.Goodbye]**, outside [lock], as the last thing written to it;
 *  - it terminates on the remote's `GOODBYE` | a terminal close/receive error | the injected [drainBound],
 *    and only THEN is it disconnected, removed from [conns] and tombstoned. Every #1528 tombstone
 *    obligation is relocated to drain-end, not removed.
 *
 * **Both dedup arms drain.** The keep arm also dropped its loser abruptly, and since both ends dedup onto
 * the same physical link, *our* keep-arm loser is the *remote's* replace-arm loser — with its window
 * frames in flight toward us. Fixing only the replace arm would leave the bug half-fixed in a way no
 * single-ended test can show.
 *
 * The `GOODBYE` is what makes the drain sound rather than an optimisation of it. #2467 proved the TLS-PSK
 * binding surfaces no TCP FIN, so terminating on "the remote cancelled" is self-defeating: that signal
 * exists only while the remote still cancels abruptly, and once both ends drain neither sends it — every
 * formation's drain would then run to the full bound and the ordering hold below would stall the healthy
 * path for that entire bound. [drainBound] is a **zombie-link backstop**, not the mechanism.
 *
 * ## The receiver ordering hold (#2425)
 * [Seam.incoming] promises frames in send order. The remote's stream is strictly loser-then-winner — it
 * writes on the link it published, rebinds, then writes on the winner — but *cross-link* ordering is not a
 * transport guarantee, so draining without a hold would trade silent loss for silent reordering. So a
 * per-peer hold ([orderingHolds]) is armed at the dedup and released at that peer's last drain-end;
 * while it is armed, frames arriving on the peer's WINNER link are buffered and frames arriving on its
 * DRAINING link are delivered immediately.
 *
 * **The hold buffers and continues; it can never suspend the demux loop.** Both links' bytes arrive
 * through the single [bytesReceivedLoop], so a hold that suspended that loop on a winner frame could
 * never process the `GOODBYE` that would release it — the seam would wedge permanently. [stageInboundData]
 * is therefore append-and-return whenever the hold is armed and has room; the only lock it takes is
 * [stageMutex], whose other holders ([armOrderingHold], [releaseOrderingHold]) wait on nothing the demux
 * loop produces.
 *
 * **Sequence is stamped at release time**, inside [stageMutex], so stamped order is delivery order.
 * The buffer is bounded ([orderingHoldCapacity]); on overflow it **releases early with a WARN and accepts
 * the reorder** rather than backpressuring, which would reintroduce the deadlock by another route.
 *
 * ## Thread-safety
 * The [registry] and [conns] maps are shared across the four lifecycle collectors (each `collect` is
 * internally sequential, but they run concurrently) and the caller-driven [broadcast]/[sendTo].
 * All map access is guarded by one [reentrantLock] (atomicfu). **No `suspend`/`api.*` call ever
 * runs under the lock** — targets are snapshotted under the lock, then sent/disconnected/delivered
 * outside it. Correct under a multi-threaded dispatcher; no single-thread-confinement crutch.
 *
 * ## Peer loss is recoverable — re-form, don't tear (#1513)
 * A dropped remote (a clean [NwApi.connectionClosed], a send-failure eviction, or a #1478 grace-timer
 * expiry) is NOT terminal: when the last remote leaves, the seam transitions [SeamState.Woven] →
 * [SeamState.Weaving] and resets [peers] to `{selfId}`, keeping [incoming] open and waiting for a peer
 * to (re)connect (`NwLoom` redials — #1513). `SeamState` blesses `Woven → Weaving` as the recoverable
 * "re-forming" transition; a later peer add flips it back to [SeamState.Woven] via [addRemotePeer].
 * [SeamState.Torn] now latches on ONLY two paths — an explicit consumer [close] and the initial
 * `NwLoom.weave` timeout (which routes through [close] as [CloseReason.Unreachable]); it is never a
 * consequence of peer loss.
 *
 * ## The wedge watchdog — diagnostics for a formation that fails SILENTLY (#2420)
 * This fabric's characteristic failure is silence: a seam that has resolved a peer and cannot move a byte
 * over it emits nothing at all, so a wedged device and an idle one produce identical logs. Three signals
 * attack that, and the LEVEL SPLIT between them is deliberate — ERROR for a contract violation, WARN for
 * a condition. Do not flatten them:
 *
 *  - **[auditRegistryLocked] — ERROR.** A [registry] entry naming a connId absent from [conns]. That is
 *    contract-impossible per this class's own model, so it can only be a host-side bookkeeping bug. Run in
 *    every critical section that removes from [conns], plus periodically from the watchdog.
 *  - **[sweepInboundSilence] — WARN.** A settled peer whose live link has carried no inbound frame for at
 *    least one [inboundSilenceProbe]. A *condition*, not a bug — an application with nothing to say looks
 *    the same — reported once per silence episode per link.
 *  - **`nw.seam.publish-swap` — WARN**, from [resolveIdentity]'s dedup-replace arm. The peer was published
 *    on one connection, the consumer could see and write to it, and the seam then moved it to another and
 *    closed the first. It carries how wide that window was and how many frames were written into it. This
 *    is the one of the three that a two-sided byte ledger has actually implicated in a field wedge.
 *
 * Both log identities and state (connIds, peer ids, the dialled endpoint, `state`, `peers`, the whole
 * `peer→conn` registry), never sizes: a count says that something changed, the identities say what.
 * Neither ever tears, redials or remediates — the consuming application's own bounds are authoritative and
 * nothing here may race them. This is observability.
 *
 * ## [settledEndpoints] — the redial signal for `NwLoom`
 * `NwLoom`'s redial loop must know which discovered endpoints still need dialling. The seam is the only
 * layer that resolves a browse-time endpoint to a stable [PeerId] (via the [NwHello] handshake), so it
 * publishes [settledEndpoints]: the set of endpoint ids that need no (further) dial — either the endpoint
 * of a currently-connected peer, or an endpoint that resolved to `selfId` (a self-dial the guard drops).
 * The mapping is learned from whichever connection to a peer carried a non-null [NwConnectionOpened.endpoint]
 * (the outbound dial), so even when the surviving link is the *inbound* one, the peer's endpoint is still
 * known — this is what stops the double-dial dedup loser's close from provoking an endless redial storm.
 *
 * @param selfId this peer's stable identity, sent (with a per-connection nonce) as the first framed
 *   message on each connection.
 * @param api    the transport moving raw bytes over open connections.
 * @param scope  coroutine scope hosting the collectors; cancelled on teardown.
 * @param random source of per-connection dedup nonces; production defaults to [Random.Default], tests
 *   inject a seeded [Random] so the dedup tiebreak is deterministic.
 * @param policy delivery policy for the inbound [Spool] (default [DeliveryPolicy.Reliable]).
 * @param wovenPathGrace how long a path-lost (`ready → waiting`) connection is given to recover before
 *   the seam evicts it — re-forming to [SeamState.Weaving] if it was the last remote (#1478/#1513).
 *   Production default [DEFAULT_WOVEN_PATH_GRACE] (10s); tests inject a small value. Injected via
 *   [scope]'s (test) dispatcher, so it advances under virtual time.
 * @param inboundSilenceProbe how often the wedge watchdog sweeps ([inboundSilenceLoop]) — the granularity
 *   of the `nw.seam.inbound-silent` WARN and of the periodic [auditRegistryLocked] re-assertion (#2420).
 *   [Duration.ZERO] or negative disables the sweep entirely (the audit still runs at every mutation site).
 *   Production default [DEFAULT_INBOUND_SILENCE_PROBE]; tests inject a small value and drive it with
 *   `advanceTimeBy`.
 * @param drainBound how long a deduplicated loser is drained before the seam gives up on the remote's
 *   `GOODBYE` and disconnects it anyway (#2425). A **zombie-link backstop**, never the mechanism: the
 *   healthy path terminates in-band on the goodbye, milliseconds after the swap, with no timer involved.
 *   Production default [DEFAULT_DRAIN_BOUND]; tests inject a small value and drive it with `advanceTimeBy`.
 *   Injected via [scope]'s (test) dispatcher, so it advances under virtual time.
 * @param orderingHoldCapacity how many winner-link frames the per-peer ordering hold will buffer for a
 *   peer with a drain in progress before it releases early, WARNs, and accepts the reorder (#2425).
 *   Bounded, never unbounded; backpressuring instead would put the demux loop back in the deadlock the
 *   hold exists to avoid. Production default [DEFAULT_ORDERING_HOLD_CAPACITY] — the same shape as
 *   [DELIVERY_STAGING_CAPACITY], since it absorbs the same kind of transient.
 * @param nowMillis monotonic-milliseconds provider, injected because time is a dependency. Read only by
 *   the `nw.seam.publish-swap` diagnostic, to measure how long a peer was published on a link before the
 *   seam moved it (#2420); production default [seamMonotonicMillis], tests pass a controlled counter.
 * @param maxFrameBytes the largest FRAME this seam's framing will carry, enforced by both edges of the
 *   wire — [encodeFrame] on send and each connection's [NwFramer] on receive. One number, threaded to
 *   both, so the ceiling this seam *publishes* is by construction the ceiling it *enforces* (#2069);
 *   previously each edge reached for [DEFAULT_MAX_FRAME_SIZE] independently and the seam published
 *   nothing. Since #2425 the published [maxPayloadBytes] is this less [NwWire.TYPE_BYTES], because the
 *   frame's leading type byte is spent out of the caller's budget rather than added to the wire.
 *   Production default [DEFAULT_MAX_FRAME_SIZE] (16 MiB); tests inject a small value so an over-budget
 *   payload costs bytes rather than megabytes.
 */
internal class NwSeam(
    override val selfId: PeerId,
    private val api: NwApi,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val wovenPathGrace: Duration = DEFAULT_WOVEN_PATH_GRACE,
    private val inboundSilenceProbe: Duration = DEFAULT_INBOUND_SILENCE_PROBE,
    private val drainBound: Duration = DEFAULT_DRAIN_BOUND,
    private val orderingHoldCapacity: Int = DEFAULT_ORDERING_HOLD_CAPACITY,
    private val nowMillis: () -> Long = seamMonotonicMillis,
    // ROLES ONLY — deliberately NOT a TransportCapability. The loom's `availability()` answers "is this
    // fabric usable on this runtime" (a platform question); routing it in here is what let a seam publish
    // a confident path verdict it had never observed (#1712). Narrowing the type makes that
    // structurally impossible rather than merely discouraged: there is no availability to launder.
    private val staticRoles: Set<TransportRole> = NwLoom.NW_ROLES,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_SIZE,
) : Seam {

    /**
     * Per-connection mutable state. All fields read/written only under [lock]; [framer] is driven
     * only by the single bytes loop. [nonce] is minted once at creation and never mutated — it is
     * this connection's contribution to the canonical dedup nonce.
     */
    private class ConnState(val nonce: ByteArray, maxFrameBytes: Int) {
        val framer: NwFramer = NwFramer(maxFrameBytes)
        var resolvedPeerId: PeerId? = null

        /**
         * The browse-time endpoint this connection was dialled to, from [NwConnectionOpened.endpoint];
         * `null` for an inbound (accepted) connection. Used to learn a peer's [NwEndpoint.id] so
         * [settledEndpoints] can tell `NwLoom` which endpoints still need (re)dialling (#1513).
         */
        var endpoint: NwEndpoint? = null

        /**
         * How many DATA frames have arrived on this connection since it resolved (#2420). Counted under
         * [lock] on the [FrameOutcome.Data] arm, so it is the count of frames actually attributed to a
         * peer — the identity handshake is deliberately not counted, because "the `NwHello` arrived" is
         * already said by `nw.seam.resolved.first` and the question the watchdog answers is whether
         * anything traversed the link AFTERWARDS.
         */
        var inboundFrames: Long = 0

        /** [inboundFrames] as of the previous watchdog sweep; the two differ iff a frame arrived in between. */
        var framesAtLastSweep: Long = 0

        /** Consecutive watchdog sweeps that observed no change in [inboundFrames]. Reset by any arrival. */
        var silentSweeps: Int = 0

        /** Whether the current silence episode has already been reported — one WARN per episode, not per sweep. */
        var silenceReported: Boolean = false

        /**
         * Frames this seam has HANDED TO THE TRANSPORT on this connection (#2420) — counted under [lock] in
         * [broadcast]/[sendTo] at the moment the connId is chosen. Handed, not delivered, and the distinction
         * is not pedantry: on the Apple binding a send failure is not reported back to the seam at all, so a
         * count taken here is the only measure of "the consumer wrote this much to this link" that exists.
         * It is what turns `nw.seam.publish-swap` from a window into a quantity.
         */
        var outboundFrames: Long = 0
    }

    /**
     * The live connection carrying a resolved peer, the canonical nonce both ends agreed on, and WHEN the
     * peer was published on this connection ([nowMillis]) — the start of the publish-then-swap window that
     * `nw.seam.publish-swap` measures.
     */
    private data class Winner(
        val connId: NwConnectionId,
        val canonicalNonce: String,
        val publishedAtMillis: Long,
    )

    private val lock = reentrantLock()

    /** Resolved remote identity → the live connection carrying it (+ its canonical link nonce). */
    private val registry = mutableMapOf<PeerId, Winner>()

    /** Every connection this seam has seen → its [ConnState]. */
    private val conns = mutableMapOf<NwConnectionId, ConnState>()

    /**
     * Connections currently under a path-loss grace timer (#1478): connId → the pending tear [Job].
     * Armed by [reconcileStates] when a connection's latest state is [NwConnState.PathLost], cancelled when
     * it reconciles back to [NwConnState.Viable] (recovery) or on any close/eviction of the connection.
     * Guarded by [lock] like [conns]/[registry].
     */
    private val graceJobs = mutableMapOf<NwConnectionId, Job>()

    /** Which dedup arm displaced a link — the only thing that differs between the two drains (#2425). */
    private enum class DrainArm(val label: String) {
        /** The peer was PUBLISHED on this link and has been moved off it: the publish-then-swap window. */
        Replace("replace"),

        /** This link resolved second and lost; the peer was never published on it locally. */
        Keep("keep"),
    }

    /** How a drain ended (#2425) — the field that says whether the mechanism worked or was backstopped. */
    private enum class DrainOutcome(val label: String) {
        /** The remote's in-band `GOODBYE` arrived: everything it wrote on this link is now behind us. */
        Goodbye("goodbye"),

        /** The link died under the drain — a close event, a `Closed` state, a send failure, a refused frame. */
        LinkLoss("error"),

        /** [drainBound] expired with no goodbye and no close: a zombie link, backstopped. */
        Bound("bound"),
    }

    /**
     * A deduplicated loser being drained rather than cancelled (#2425).
     *
     * Everything the `nw.seam.publish-swap` WARN reports is captured HERE, at the swap, and reported at
     * drain END — because the outcome is the half of that report a field capture could not previously
     * get, and a window measured at the swap is meaningless without knowing whether the drain that
     * followed it actually delivered anything.
     */
    private class Drain(
        val peer: PeerId,
        val arm: DrainArm,
        /** The winner the peer is (or stayed) bound to, for the report's `now-on=`. */
        val winner: NwConnectionId,
        val loserDialled: String,
        val winnerDialled: String,
        /** How long the peer was published on the drained link before it was displaced; 0 on [DrainArm.Keep]. */
        val visibleForMillis: Long,
        /** Frames this seam handed to the drained link before the drain began — the window's writes. */
        val framesWritten: Long,
    ) {
        /** The [drainBound] backstop. Set once, immediately after the drain is registered. */
        var boundJob: Job? = null

        /** DATA frames the remote delivered on this link AFTER the drain began — what the drain saved. */
        var drainedFrames: Long = 0
    }

    /**
     * Connections currently DRAINING (#2425): connId → its [Drain]. Keyed by connId, never by peer —
     * redial churn can leave two drains to one peer in flight at once, and a peer-keyed map would let the
     * second silently discard the first's bookkeeping. Guarded by [lock] like [conns]/[registry].
     *
     * A connId in here is still in [conns] and still resolved; it is never in [registry].
     */
    private val draining = mutableMapOf<NwConnectionId, Drain>()

    /**
     * Bounded FIFO of connIds recently removed from [conns] (#1528). A late/buffered data frame can arrive
     * on a connection AFTER it was evicted (dedup loser, self-connection drop, close, [removeByConn]); without
     * this set [getOrCreateConnForBytes] would RESURRECT a fresh [ConnState] (`resolvedPeerId == null`) and
     * misparse that DATA frame as an [NwHello] — throwing out of the decode (killing the receive loop) or
     * registering a phantom peer. Every removal site records the connId here; the bytes loop then DROPS a
     * frame for a connId that is not in [conns] but IS tombstoned. Genuinely-new connIds (never seen, never
     * tombstoned) still create a [ConnState] — preserving the #1509 lost-wakeup reconcile where the bytes loop
     * is the first to observe a new connection. A [LinkedHashSet] gives O(1) membership + insertion order for
     * pruning; capped at [TOMBSTONE_CAP] so a long-lived churny seam can't grow it without bound (in-flight
     * buffered frames arrive within milliseconds of removal, so a modest cap is ample). Guarded by [lock].
     */
    private val tombstones = LinkedHashSet<NwConnectionId>()

    /** Record [connId] as recently-removed, pruning the oldest tombstone past [TOMBSTONE_CAP]. Called under [lock]. */
    private fun tombstoneLocked(connId: NwConnectionId) {
        if (tombstones.add(connId) && tombstones.size > TOMBSTONE_CAP) {
            val oldest = tombstones.iterator().next()
            tombstones.remove(oldest)
        }
    }

    /**
     * Sticky map of resolved remote [PeerId] → the [NwEndpoint.id] a connection to it was dialled on
     * (learned from any connection to that peer that carried a non-null [NwConnectionOpened.endpoint],
     * winner or dedup-loser). Guarded by [lock]. Feeds [settledEndpoints] (#1513).
     */
    private val peerEndpoint = mutableMapOf<PeerId, String>()

    /** Endpoint ids that resolved to [selfId] (a self-dial the guard drops) — never redial these. Under [lock]. */
    private val selfEndpointIds = mutableSetOf<String>()

    /**
     * Peers whose [registry] entry has already been reported as an orphan by [auditRegistryLocked], so one
     * bookkeeping bug costs one ERROR rather than one per mutation and one per watchdog sweep. Pruned to
     * [registry]'s keys on every audit, so it is bounded by the roster and an entry cannot outlive its peer.
     * Guarded by [lock].
     */
    private val reportedOrphans = mutableSetOf<PeerId>()

    private val _settledEndpoints = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The set of discovered-endpoint ids that need no (further) dial: the endpoint of every currently
     * connected peer, plus every endpoint that resolved to [selfId]. `NwLoom`'s redial loop dials the
     * *complement* — a discovered endpoint absent from this set — with backoff (#1513). Recomputed under
     * [lock] on every membership/identity change ([refreshSettledLocked]).
     */
    internal val settledEndpoints: StateFlow<Set<String>> = _settledEndpoints.asStateFlow()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Weaving until the first remote peer resolves, then Woven; re-forms Woven→Weaving on last-remote
    // loss (recoverable, #1513); latches Torn ONLY on close()/weave-timeout, never on peer loss.
    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    // Live capability (#1541/#1554): ROLES seeded from [staticRoles] and thereafter driven by the injected
    // path monitor ([NwApi.pathState]). The monitor moves [TransportCapability.availability] as the real-world
    // path goes up/down or the Local-Network permission is denied, AND folds the live interface type into the
    // ROLES — a peer-to-peer AWDL path adds [TransportRole.WifiDirect], an infrastructure path adds
    // [TransportRole.WifiLan], atop the fabric's base Discovery+Data ([NwLoom.NW_ROLES]). A MutableStateFlow so
    // the write from the single [pathStateLoop] collector is thread-safe (CAS) under any dispatcher.
    //
    // AVAILABILITY starts at [unobservedCapability]'s Unknown and is ONLY ever set from an observed path
    // (#1712): nothing here can report a verdict the monitor has not supplied. [staticRoles] carries no
    // availability to fall back on, by construction.
    private val _capability = MutableStateFlow(unobservedCapability)
    override val capability: StateFlow<TransportCapability> = _capability.asStateFlow()

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    /**
     * Bounded per-seam staging channel decoupling the single demux/receive loop from delivery
     * backpressure (#1415). [bytesReceivedLoop] classifies a frame and hands the resulting DATA [Swatch]
     * here; the single [deliveryDrainLoop] pulls from it and calls the (SUSPEND-under-[DeliveryPolicy.Reliable])
     * [Spool.deliver]. Without this stage the demux loop suspends INSIDE [Spool.deliver] whenever ONE peer's
     * consumer is slow and its [spool] is full — wedging reads for EVERY connection, including the identity
     * handshakes of newly-arriving peers (per-link-loop fabrics like `MeshSeam` don't suffer this because each
     * link has its own loop). With the stage, a slow consumer backs up its own delivery while the shared loop
     * keeps flowing, so a later connection's handshake still resolves.
     *
     * It is **bounded** ([DELIVERY_STAGING_CAPACITY]) — no unbounded growth, which would be the contract
     * violation this design must not introduce — so a consumer that stays slow *forever* still eventually
     * backpressures the demux loop, but only after this headroom fills, and only DATA frames queue (identity
     * frames resolve inline, never through the spool). Overflow is always SUSPEND (never lossy) regardless of
     * [policy]: the [spool] alone applies the delivery policy; this stage only moves WHERE the backpressure is
     * felt (the drain, not the shared loop). The single dedicated reader is the sanctioned FIFO pattern, not a
     * single-thread-confinement crutch: sequence order is preserved because [bytesReceivedLoop] stamps each
     * [Swatch]'s sequence in arrival order and enqueues it in that order.
     */
    private val deliveryStage: Channel<Swatch> = Channel(capacity = DELIVERY_STAGING_CAPACITY)

    /**
     * Serialises **every** write to [deliveryStage] with the ordering-hold decision that precedes it
     * (#2425), so a flush and a fresh arrival can never interleave into the stage out of order.
     *
     * A coroutine [Mutex], not the seam-wide [lock]: the guarded region contains [Channel.send], which
     * suspends when the (bounded) stage is full. That is the exact opposite of [lock]'s no-suspend rule,
     * and the reason the two are separate primitives rather than one. **[lock] is never held while this
     * is acquired, and this is never held while [lock] is acquired** — the two are strictly disjoint, so
     * there is no ordering to invert.
     *
     * It cannot deadlock the demux loop against itself: [stageInboundData] is called only from
     * [bytesReceivedLoop], and the only other holders ([armOrderingHold], [releaseOrderingHold]) wait on
     * nothing but [deliveryDrainLoop]'s consumption — which the demux loop is not needed for.
     */
    private val stageMutex = Mutex()

    /**
     * Peers whose winner-link frames are being held pending a drain-end (#2425): peer → the frames
     * buffered so far, in arrival order. **Guarded by [stageMutex] only** — never by [lock] — so the
     * hold decision and the [deliveryStage] write it guards are one atomic step.
     *
     * A peer's presence as a key IS the armed flag; the list may be empty. Bounded by
     * [orderingHoldCapacity], after which the hold releases early (see [stageInboundData]).
     */
    private val orderingHolds = mutableMapOf<PeerId, MutableList<ByteArray>>()

    // Single latch flag, read/written across every path (receive/close/send).
    private val closed = atomic(false)

    // Stamped onto every delivered Swatch; incremented from the single bytes loop but atomic for safety.
    private val seq = atomic(0L)

    private val closedMessage get() = "NwSeam for ${selfId.value} is closed"

    // The seam's seven coroutines are started by the `init` block at the BOTTOM of this class, not from
    // property initialisers here. See that block for why — it is a correctness constraint (#2462), not
    // a style choice.

    // ── loop 1: connectionOpened ────────────────────────────────────────────────

    private suspend fun connectionOpenedLoop() {
        api.connectionOpened.collect { event ->
            if (closed.value) {
                log.debug { "nw.seam.opened.ignored connId=${event.connectionId.value} self=${selfId.value} (seam closed)" }
                return@collect
            }
            val connId = event.connectionId
            // Get-or-create the ConnState (minting its nonce once) and snapshot the nonce under the
            // lock; send the identity frame OUTSIDE the lock (best-effort).
            val (cs, created) = getOrCreateConn(connId)
            // Record the dialled endpoint (outbound only; null inbound) so the peer's endpoint id can feed
            // [settledEndpoints] (#1513). Set under the lock like every other ConnState mutation.
            if (event.endpoint != null) lock.withLock { cs.endpoint = event.endpoint }
            // #1509/#1522 lost-wakeup guard: a [NwConnState] observed for this connId BEFORE it entered
            // `conns` was skipped (PathLost arm-skipped, Closed filtered), and connectionStates is
            // latest-value STATE that will not re-emit an unchanged value. Now that `conns` has caught up,
            // re-reconcile the LATEST map so a pending path loss is armed (else the #1478 zombie returns: no
            // `connectionClosed` ever fires for a path-lost connection) AND a conn that closed before we
            // tracked it is torn on registration. reconcileStates re-reads the freshest state under the lock
            // (#1566) — we no longer capture `connectionStates.value` here on the caller's thread, where it
            // could go stale before the locked reconcile ran.
            if (created) reconcileStates()
            log.debug { "nw.seam.opened connId=${connId.value} self=${selfId.value} → sending NwHello" }
            runCatchingCancellable { api.send(connId, encodeFrame(NwWire.encodeHello(selfId, cs.nonce), maxFrameBytes)) }
                .onFailure { log.debug { "nw.seam.identity-send-failed connId=${connId.value} self=${selfId.value}: ${it.message}" } }
        }
    }

    // ── loop 2: bytesReceived — demux + inline handshake ────────────────────────

    private suspend fun bytesReceivedLoop() {
        api.bytesReceived.collect { event ->
            if (closed.value) return@collect
            val connId = event.connectionId
            // Snapshot (get-or-create, minting its nonce once) the ConnState under the lock; decode OUTSIDE it.
            // A frame for a TOMBSTONED (recently-removed) connId is DROPPED — not resurrected (#1528).
            val (cs, created) = getOrCreateConnForBytes(connId) ?: run {
                log.debug { "nw.seam.bytes.dropped-tombstoned connId=${connId.value} self=${selfId.value} (removed conn)" }
                return@collect
            }
            // Same #1509/#1522 lost-wakeup guard as connectionOpenedLoop: if bytes are the first thing that
            // puts this connId into `conns`, re-reconcile the latest connectionStates so a pending path loss
            // is not stranded and a conn that closed before we tracked it is torn on registration. The read
            // happens under the lock inside reconcileStates (#1566), not on this caller's thread.
            if (created) reconcileStates()
            // The framer is single-reader (only this loop touches it), so decoding outside the lock is safe.
            // #1528 finding 1: NwFramer.decode throws FrameTooLargeException on a bad 4-byte length prefix
            // (negative or > maxFrameSize). Guard it so a corrupt/hostile chunk on ANY live conn routes through
            // the SAME corrupt-inbound backstop instead of escaping the collector and killing the receive loop.
            // A real structured-concurrency cancel is always re-thrown, never swallowed.
            val frames = try {
                cs.framer.decode(event.bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                evictCorruptConn(connId, "framer decode failed: ${e.message}")
                return@collect
            }
            for (frame in frames) {
                processFrame(connId, cs, frame)
            }
        }
    }

    // ── delivery drain: single reader decoupling receive from spool backpressure (#1415) ─────────

    /**
     * The single dedicated reader draining [deliveryStage] into the [spool]. Moving the (SUSPEND-under-
     * [DeliveryPolicy.Reliable]) [Spool.deliver] here is what keeps a slow local consumer from wedging the
     * shared [bytesReceivedLoop] (#1415): a full spool suspends THIS loop, not the demux loop, so other
     * connections' reads — including newly-arriving peers' identity handshakes — keep flowing. FIFO is
     * preserved: [bytesReceivedLoop] stamps each [Swatch]'s sequence in arrival order and enqueues it in that
     * order, and this reader delivers in the same order. Cancelled with [scope] at teardown like every other
     * loop; a [Spool.deliver] suspended on a full-and-closing spool unwinds on cancellation (or the spool's
     * own [Spool.deliver] drop when [Spool.close] races it).
     */
    private suspend fun deliveryDrainLoop() {
        for (swatch in deliveryStage) {
            spool.deliver(swatch)
        }
    }

    /**
     * Get-or-create the [ConnState] for [connId] under [lock], returning it plus whether it was **newly
     * inserted** into [conns]. The creation flag drives the #1509 lost-wakeup guard: whenever a connId
     * first enters [conns], its owning loop re-reconciles the latest viability state so a `viable=false`
     * that arrived (and was arm-skipped) before the connection was tracked is not silently lost.
     *
     * The bytes loop uses the tombstone-aware [getOrCreateConnForBytes] instead, so a late frame on an
     * evicted connId cannot resurrect it (#1528); this variant is the [connectionOpenedLoop] path, where a
     * connId is genuinely (re)opening and must always get a [ConnState].
     */
    private fun getOrCreateConn(connId: NwConnectionId): Pair<ConnState, Boolean> = lock.withLock {
        val existing = conns[connId]
        if (existing != null) {
            existing to false
        } else {
            ConnState(random.nextBytes(NONCE_BYTES), maxFrameBytes).also { conns[connId] = it } to true
        }
    }

    /**
     * Bytes-loop variant of [getOrCreateConn] (#1528): returns the [ConnState] plus whether it was newly
     * inserted, or `null` when a frame arrives for a connId that is NOT in [conns] but IS [tombstones]-marked
     * (recently removed). Returning `null` makes [bytesReceivedLoop] DROP the frame rather than RESURRECT the
     * evicted connection and misparse its late/buffered DATA as a fresh [NwHello] (a phantom peer, or a decode
     * throw that kills the loop). A genuinely-new connId — never seen AND never tombstoned — still creates a
     * [ConnState], preserving the #1509 lost-wakeup reconcile where the bytes loop is the first to observe a
     * new connection. Only tombstoned connIds are dropped; the never-seen case is unchanged.
     */
    private fun getOrCreateConnForBytes(connId: NwConnectionId): Pair<ConnState, Boolean>? = lock.withLock {
        val existing = conns[connId]
        when {
            existing != null -> existing to false
            connId in tombstones -> null // evicted conn — drop the late/buffered frame, do not resurrect
            else -> ConnState(random.nextBytes(NONCE_BYTES), maxFrameBytes).also { conns[connId] = it } to true
        }
    }

    /** What identity resolution asks the caller to do OUTSIDE [lock] (#2425). */
    private sealed interface ResolveAction {
        /**
         * Disconnect [connId] outright, with no drain: the self-connection guard's link. There is no peer
         * whose frames could be in flight on it and nothing that could ever attribute them, so draining it
         * would only keep a socket alive that nobody can read.
         */
        data class Disconnect(val connId: NwConnectionId) : ResolveAction

        /** [connId] lost the dedup and is now DRAINING toward [peer] (#2425): send its `GOODBYE`, arm its bound. */
        data class Drain(val connId: NwConnectionId, val peer: PeerId) : ResolveAction
    }

    /** Outcome of classifying one frame under [lock]; the suspend action runs OUTSIDE the lock. */
    private sealed interface FrameOutcome {
        /**
         * A [NwFrameType.Data] frame on an already-resolved connection, attributed to [sender].
         *
         * [fromDrainingLink] decides whether the ordering hold applies: a frame on the DRAINING link is
         * part of the tail the hold exists to let through first, so it is delivered immediately; a frame
         * on the peer's live link is what gets buffered (#2425).
         */
        data class Data(val sender: PeerId, val fromDrainingLink: Boolean) : FrameOutcome

        /** Just-resolved identity: [action] (if any) is what the caller must do outside [lock]. */
        data class Resolved(val action: ResolveAction?) : FrameOutcome

        /**
         * A [NwFrameType.Goodbye] arrived — the remote has finished writing on this link (#2425).
         *
         * [endsDrain] is true when this connection is one we are draining, which is the case the frame
         * exists for: it is FIFO behind every byte the remote wrote into the publish-then-swap window, so
         * its arrival is the in-band proof that the tail is complete. On any other link it is reported and
         * otherwise ignored — see the arm in [processFrame] for why that is not a defect.
         */
        data class Goodbye(val peer: PeerId?, val endsDrain: Boolean) : FrameOutcome

        /**
         * A [NwFrameType.Hello] on a connection that has ALREADY resolved (#2425 slice 1).
         *
         * Positionally this was indistinguishable from data, so a duplicate preamble was handed to the
         * CONSUMER as an application frame. It is a protocol violation by the remote and the connection
         * is refused. ([RealNwApi] has its own guard against re-arming a receive loop and double-sending
         * a hello, so this reports the remote's behaviour, not ours.)
         */
        data class HelloOnResolved(val resolved: PeerId, val claimed: PeerId) : FrameOutcome

        /**
         * A [NwFrameType.Data] before any hello (#2425 slice 1).
         *
         * Positionally this occupied the hello slot, so it was fed to `NwHello.decode` — which either
         * threw or, worse, parsed and registered a phantom peer. There is no identity to attribute it to,
         * so the connection is refused rather than the frame silently dropped: a peer that talks before
         * it identifies itself will keep doing so.
         */
        data class DataBeforeHello(val payloadBytes: Int) : FrameOutcome

        /**
         * The connection is no longer the live one when classified (#1528 finding 2): [getOrCreateConnForBytes]
         * and [processFrame] are two lock acquisitions, so a removal path can tombstone/replace the connId
         * between them. Resolving identity on a dead conn would register `registry[peer] = Winner(deadConnId)`
         * — an unevictable zombie. Detected under the lock (`conns[connId] !== cs` or the connId is tombstoned)
         * and the frame is simply DROPPED.
         */
        object Dropped : FrameOutcome
    }

    /**
     * Handle ONE decoded frame, classified by its **type byte** rather than by its position (#2425).
     *
     * The body's leading [NwFrameType] is decoded FIRST, outside the lock — it depends on nothing this
     * seam owns, and doing it here rather than under the lock also takes `NwHello.decode` off the shared
     * demux loop's critical section. A body this build cannot classify (unknown type, unknown wire
     * version, truncated) is refused by name through [evictCorruptConn]; the specific reason is the
     * point, because on a version break a generic "malformed frame" is the wrong diagnosis and the one
     * a reader would act on.
     *
     * The `resolvedPeerId` check and the [resolveIdentity] mutation then happen in the SAME critical
     * section, so [connectionClosedLoop] cannot interleave between them and re-register a peer on an
     * already-closed connection (the identity-resolution race). The suspend actions ([Spool.deliver],
     * [NwApi.disconnect]) run OUTSIDE the lock.
     */
    private suspend fun processFrame(connId: NwConnectionId, cs: ConnState, frame: ByteArray) {
        // #1528 part B: a body this build cannot read must NOT throw out of the collector and kill the
        // receive loop. Narrowly wrap ONLY the decode; a real structured-concurrency cancel is always
        // re-thrown, never swallowed.
        val wire = try {
            NwWire.decode(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NwWireFormatException) {
            // One line per refusal reason, each with its tag written as a LITERAL so the field-trail
            // guard can see the level it is emitted at. A `"$tag …"` interpolation reads identically to
            // a human and is invisible to that guard — which is how these lines would quietly drift to
            // DEBUG and vanish from a release capture (#2420).
            when (e) {
                is NwUnsupportedWireVersionException -> log.warn {
                    "nw.seam.wire.version-mismatch connId=${connId.value} self=${selfId.value} " +
                        "remote-version=${e.remoteVersion} local-version=${e.localVersion} " +
                        "→ disconnect + evict (loop preserved); the remote is on the other side of the " +
                        "#2425 wire flag day and the two cannot form a session"
                }
                // The offending byte is NOT repeated as a field: `e.code` is a signed `Byte`, so it would
                // render 0xFF as `-1` right beside the message's own `0xff`, and a reader would have to
                // work out which of the two to believe.
                is NwUnknownFrameTypeException -> log.warn {
                    "nw.seam.wire.unknown-type connId=${connId.value} self=${selfId.value} : ${e.message} " +
                        "→ disconnect + evict (loop preserved)"
                }
                is NwTruncatedFrameException -> log.warn {
                    "nw.seam.wire.truncated connId=${connId.value} self=${selfId.value} " +
                        "frame-bytes=${frame.size} : ${e.message} → disconnect + evict (loop preserved)"
                }
            }
            disconnectAndEvictConn(connId)
            return
        } catch (e: Exception) {
            evictCorruptConn(connId, "body decode failed: ${e.message}")
            return
        }
        val outcome = lock.withLock {
            val resolved = cs.resolvedPeerId
            // #1528 finding 2: getOrCreateConnForBytes and this classify are two lock acquisitions, so a
            // removal path can tombstone/replace [connId] between them. Resolving identity on a dead conn
            // would register registry[peer] = Winner(deadConnId) — an unevictable zombie. If this cs is no
            // longer the live one (replaced) or its connId was tombstoned, DROP the frame.
            if (conns[connId] !== cs || connId in tombstones) {
                FrameOutcome.Dropped
            } else {
                when (wire) {
                    is NwWireFrame.Hello -> if (resolved != null) {
                        FrameOutcome.HelloOnResolved(resolved = resolved, claimed = wire.hello.peerId)
                    } else {
                        // #2420: [resolveIdentity] is the ONLY thing on this path that writes `registry`
                        // or `conns`, so the audit belongs HERE and not after the `when`. Hoisted from
                        // there because the outer position also ran it on `Data` — i.e. on every data
                        // frame on every connection, inside the shared demux loop's critical section, on
                        // an arm that mutates two counters and is structurally unable to break the
                        // invariant. This is a byte-moving library; that loop is the one thing #1415 says
                        // must not be made expensive. Nothing is lost: a binding that goes stale anywhere
                        // else is still caught at `closed`/`removeByConn`, or by the watchdog within one
                        // probe.
                        FrameOutcome.Resolved(resolveIdentity(connId, cs, wire.hello.peerId, wire.hello.nonce))
                            .also { auditRegistryLocked("classify") }
                    }

                    NwWireFrame.Data -> if (resolved == null) {
                        FrameOutcome.DataBeforeHello(payloadBytes = frame.size - NwWire.TYPE_BYTES)
                    } else {
                        // #2420: the wedge watchdog's input. Counted HERE — under the same lock that
                        // classifies the frame — so it is exactly "frames attributed to a peer", and so it
                        // can never race the sweep that reads it.
                        cs.inboundFrames += 1
                        // An arrival ends a reported silence episode, so the link becomes reportable again
                        // and the (parked) watchdog has to be told. Only on the transition — a chatty link
                        // must not wake it on every frame.
                        if (cs.silenceReported) {
                            cs.silenceReported = false
                            wakeWatchdogLocked()
                        }
                        // #2425: count what the drain actually SAVED, so the report is a quantity rather
                        // than a claim that a mechanism ran.
                        val drain = draining[connId]?.also { it.drainedFrames += 1 }
                        FrameOutcome.Data(resolved, fromDrainingLink = drain != null)
                    }

                    NwWireFrame.Goodbye -> FrameOutcome.Goodbye(resolved, endsDrain = connId in draining)
                }
            }
        }
        when (outcome) {
            // Data frame — hand OFF to the bounded staging channel (#1415), never call the SUSPEND-under-
            // Reliable Spool.deliver inline here: that would wedge this shared demux loop (and every other
            // connection's handshake) whenever THIS sender's consumer is slow. The single deliveryDrainLoop
            // owns Spool.deliver. Sequence is stamped in arrival order here, so FIFO is preserved across the
            // stage. Runs OUTSIDE the lock; deliveryStage.send only suspends when the (bounded) stage is full.
            //
            // `dropFirst` strips the type byte as a ZERO-COPY view (the pattern `Swatch` documents for
            // framing layers) — the alternative, materialising the body in NwWire.decode, would copy every
            // received payload a second time on this loop.
            is FrameOutcome.Data -> stageInboundData(outcome.sender, frame, outcome.fromDrainingLink)
            // Whatever the resolution decided, acted on OUTSIDE the lock.
            is FrameOutcome.Resolved -> when (val action = outcome.action) {
                null -> Unit
                // A self-connection: disconnect it outright. Its ConnState was just removed from `conns`
                // in resolveIdentity; cancel any grace timer armed for it too, for symmetry with
                // connectionClosedLoop/removeByConn. Normally the loser's own connectionClosed would
                // cancel it; doing it here means a dropped close can't leave a stray timer that later
                // fires a no-op disconnect/eviction against a connId the seam no longer tracks.
                is ResolveAction.Disconnect -> {
                    val graceJob = lock.withLock { graceJobs.remove(action.connId) }
                    graceJob?.cancel()
                    runCatchingCancellable { api.disconnect(action.connId) }
                        .onFailure { log.debug { "nw.dedup disconnect failed connId=${action.connId.value}" } }
                }
                // The dedup loser: drained, not cancelled (#2425).
                is ResolveAction.Drain -> startDrain(action.connId, action.peer)
            }
            // The remote has finished writing on this link (#2425).
            is FrameOutcome.Goodbye -> if (outcome.endsDrain) {
                endDrain(connId, DrainOutcome.Goodbye)
            } else {
                // A goodbye on a link we are NOT draining. Both ends dedup onto the same link, so this is
                // the remote having deduped before us: our own second hello has not arrived yet, so from
                // here this link is still the live one. Deliberately no action — rebinding on the remote's
                // say-so would mean acting on a decision we cannot yet verify, and the remote keeps READING
                // this link for its whole drain, so writes we make in the interval still land. Our own
                // dedup runs when the second hello arrives, milliseconds later, and reaches the same
                // verdict by construction. INFO because it is bounded (at most one per connection) and it
                // is the one line that would explain a formation where the two ends' dedups disagreed.
                log.info {
                    "nw.seam.drain.goodbye-unmatched connId=${connId.value} self=${selfId.value} " +
                        "peer=${outcome.peer?.value} state=${_state.value} " +
                        "→ the remote is draining this link and this seam has not deduped it yet (#2425)"
                }
            }
            // A frame whose TYPE contradicts the connection's state. Both arms log a LITERAL tag for the
            // same reason the decode-failure arms above do.
            is FrameOutcome.HelloOnResolved -> {
                log.warn {
                    "nw.seam.wire.hello-on-resolved connId=${connId.value} self=${selfId.value} " +
                        "resolved=${outcome.resolved.value} claimed=${outcome.claimed.value} " +
                        "→ disconnect + evict (loop preserved); a settled link must never carry a second " +
                        "identity preamble, and positional classification used to hand these bytes to the " +
                        "consumer as an application frame (#2425)"
                }
                disconnectAndEvictConn(connId)
            }
            is FrameOutcome.DataBeforeHello -> {
                log.warn {
                    "nw.seam.wire.data-before-hello connId=${connId.value} self=${selfId.value} " +
                        "payload-bytes=${outcome.payloadBytes} → disconnect + evict (loop preserved); " +
                        "there is no identity to attribute it to (#2425)"
                }
                disconnectAndEvictConn(connId)
            }
            // Stale/dead conn at classify time (#1528 finding 2) — nothing to do; the frame is dropped.
            is FrameOutcome.Dropped ->
                log.debug { "nw.seam.classify.dropped-stale connId=${connId.value} self=${selfId.value} (conn removed/tombstoned before classify)" }
        }
    }

    /**
     * Backstop for an inbound on [connId] this seam cannot parse at all (#1528): [NwFramer.decode] threw
     * on a bad length prefix (in [bytesReceivedLoop]), or a body decode failed for a reason
     * [NwWireFormatException] does not name.
     *
     * Reports the condition, then hands off to [disconnectAndEvictConn]. The typed-wire refusals in
     * [processFrame] log their own line and call that directly, so each refusal reason reaches a field
     * capture under its own greppable tag — see the comment there for why the tag must be a literal.
     */
    private suspend fun evictCorruptConn(connId: NwConnectionId, reason: String) {
        log.warn { "nw.seam.corrupt-inbound connId=${connId.value} self=${selfId.value}: $reason → disconnect + evict (loop preserved)" }
        disconnectAndEvictConn(connId)
    }

    /**
     * The MECHANISM behind every refusal, separated from the reporting so each caller owns its own log
     * line: best-effort disconnect the connection OUTSIDE the lock, then drive the local eviction via
     * [removeByConn] — which removes it from [conns], records a [tombstoneLocked], and evicts its peer if
     * it was the live link (so a refused frame on a *resolved* conn doesn't strand a zombie in
     * [registry]). A single refused frame can therefore never kill the receive loop. Suspends only
     * OUTSIDE the lock, preserving the no-suspend-under-lock rule.
     */
    private suspend fun disconnectAndEvictConn(connId: NwConnectionId) {
        runCatchingCancellable { api.disconnect(connId) }
            .onFailure { log.debug { "nw.seam.refused.disconnect-failed connId=${connId.value}: ${it.message}" } }
        removeByConn(connId)
    }

    /**
     * Resolve [connId]'s identity to [remoteId] under [lock]. Returns the [ResolveAction] the caller must
     * perform outside the lock, or `null`. Adds the peer + flips Weaving→Woven when this is the first
     * connection to [remoteId]; on a duplicate, keeps the canonical survivor (the smaller
     * [canonicalLinkNonce] of the two connections' nonces) — the peer set is unchanged either way.
     * Direction-free: both ends see the same two nonces and pick the same survivor with no coordination.
     *
     * Since #2425 **both** duplicate arms return [ResolveAction.Drain] rather than dropping the loser:
     * the losing link stays in [conns], stays resolved, and is disposed of only at drain-end. Only the
     * self-connection guard still returns [ResolveAction.Disconnect].
     */
    private fun resolveIdentity(
        connId: NwConnectionId,
        cs: ConnState,
        remoteId: PeerId,
        remoteNonce: ByteArray,
    ): ResolveAction? {
        // Self-connection guard (#1466). A peer's own advertisement appears in its own browse results
        // (real mDNS returns it, #1485), so `NwLoom` can dial it — a connection whose remote resolves to
        // `selfId`. Historically the election mesh made this routine, because every peer advertised the
        // SAME `Rendezvous.New` service name; ADR-005 (#2416) gives each peer its own instance name, so
        // the pre-dial filter now catches self even before TXT resolves and this guard is the backstop
        // rather than the workhorse. It still fires where the loom's `selfId` and the advertiser's TXT id
        // diverge (the JVM bridge, #2419). It must NEVER be registered:
        // registering self puts `selfId` in `registry`, and when that connection later fails,
        // `connectionClosedLoop` evicts its peer — self — dropping this peer from its own roster
        // (`peers → {theOtherPeer}`, `state` stays `Woven`, no `Torn`), which silently wedges every
        // consumer keying on `peers`/`host`/`Torn`. Leave `resolvedPeerId` null, drop the ConnState, and
        // return the connId so the caller disconnects the self-link (its later close is then a no-op).
        if (remoteId == selfId) {
            // Remember this endpoint resolved to self so NwLoom stops redialing it (#1513); the self-dial
            // via Rendezvous.New is otherwise indistinguishable from a real peer at the loom's name check.
            //
            // But settle it ONLY when the dialled id can actually BE ours (#2416). Identity and dial target
            // are keyed on different things: the id comes from the TXT record, while the dial goes to a
            // Bonjour NAME that mDNS re-resolves at connect time — so whenever two advertisers hold one
            // name, a dial armed FOR a real peer can land here, until conflict resolution renames one of
            // them. ADR-005 stops THIS peer from ever being one of those two advertisers, but not a pair of
            // older-build peers on a shared session name, so the guard stays. `identityResolved` is exactly
            // the provenance flag that tells the two apart (`RealNwApi.onBrowseResult`: it is `true` iff the
            // id came from a TXT record):
            //   - NOT resolved  → the id is the fallback serviceName, which may well be ours. Settling is
            //     the #1709/#1513 behaviour and is safe: the key is a name, not a peer identity, and a
            //     later resolved sighting arms a fresh redialer.
            //   - resolved      → the id is a real PeerId. Ours only if it equals `selfId`; anything else is
            //     ANOTHER peer's stable id, reached by a misresolved dial.
            // Recording another peer's id here is not untidy, it is fatal: `selfEndpointIds` feeds
            // `settledEndpoints`, `NwLoom.redialLoop` parks on a settled endpoint until it un-settles, and
            // this set is cleared only on full teardown — never per entry. So one mDNS race would blacklist
            // a reachable peer for the seam's entire lifetime. Observed on hardware 2026-08-15: two phones
            // 30 cm apart at -27 dBm, mutually discovered, that could never form a session.
            val dialled = cs.endpoint
            val dialledIsOurs = dialled != null && (!dialled.identityResolved || dialled.id == selfId.value)
            if (dialled != null && dialledIsOurs) {
                selfEndpointIds += dialled.id
                refreshSettledLocked()
            }
            conns.remove(connId)
            tombstoneLocked(connId) // #1528: a late frame on the dropped self-conn must not resurrect it
            log.info {
                "nw.seam.self-connection connId=${connId.value} self=${selfId.value} " +
                    "dialled=${dialled?.id} resolved=${dialled?.identityResolved} settled-as-self=$dialledIsOurs " +
                    "→ dropped (dialed own endpoint)"
            }
            return ResolveAction.Disconnect(connId)
        }
        cs.resolvedPeerId = remoteId
        // Learn this peer's endpoint from ANY connection that carried one (winner OR dedup-loser), so the
        // peer's endpoint is known even when the surviving link is inbound (endpoint == null) — #1513.
        cs.endpoint?.let { dialled ->
            peerEndpoint[remoteId] = dialled.id
            // The SUCCESS-path twin of the self-connection guard below: we dialled a name that mDNS
            // re-resolved, and the peer that answered is not the one whose id we dialled. #2417 made the
            // self case say so; this makes the general case say so too, which is ADR-005's `peerEndpoint[B]
            // = C` sibling — B's endpoint is recorded under C, so C is reported settled while un-connected
            // and its redialer parks. Deliberately a WARNING and NOT a guard: ADR-005 rejects guarding here
            // (the endpoint-id and PeerId spaces do not coincide on the JVM bridge, #2419, so a guard would
            // refuse a legitimate endpoint), and removes the ambiguity at the source instead. Bounded — at
            // most one line per connection resolution.
            //
            // KNOWN FALSE POSITIVE, stated in the message rather than suppressed: on the JVM bridge BOTH
            // operands are wrong in the same direction — `BridgeNwApi` cannot marshal `identityResolved`
            // across the ABI so it defaults to `true`, and the endpoint-id space is the dylib's selfId
            // space while `remoteId` is the loom's — so EVERY healthy bridge connection trips this. There
            // is no sound way to tell the two apart from here (that is exactly what #2419 would fix), and a
            // heuristic that guessed could hide a REAL mismatch on the Apple path, where this line is the
            // incident. So the caveat is carried in the text: a reader on the bridge sees at once that it
            // is expected there, and a reader on a device sees a warning that means what it says.
            if (dialled.identityResolved && dialled.id != remoteId.value) {
                log.warn {
                    "nw.seam.dialled-mismatch connId=${connId.value} self=${selfId.value} " +
                        "dialled=${dialled.id} answered=${remoteId.value} → recorded peerEndpoint" +
                        "[${remoteId.value}]=${dialled.id}; the dial resolved to a different device " +
                        "(#2416) — EXPECTED on the JVM bridge until #2419 threads one selfId across the ABI"
                }
            }
        }
        val canonical = canonicalLinkNonce(cs.nonce, remoteNonce)
        val existing = registry[remoteId]
        if (existing == null) {
            registry[remoteId] = Winner(connId, canonical, nowMillis())
            addRemotePeer(remoteId) // refreshes settledEndpoints
            // INFO, and carrying the DIALLED endpoint next to the peer that answered. Every other verdict
            // on this path is already INFO (self-connection, closed, torn), so leaving the SUCCESS verdict
            // at debug meant a device capture recorded only bad news: "reached the wrong peer" and "reached
            // nobody" looked identical. `dialled=<inbound>` marks a link the far end opened — for those the
            // question does not arise, since we chose no target.
            log.info {
                "nw.seam.resolved.first connId=${connId.value} remote=${remoteId.value} self=${selfId.value} " +
                    "dialled=${cs.endpoint?.id ?: "<inbound>"} resolved=${cs.endpoint?.identityResolved} nonce=$canonical"
            }
            return null
        }
        // Peer already registered; we may have just learned its endpoint from this (possibly loser) link.
        refreshSettledLocked()
        if (existing.connId == connId) {
            return null // idempotent; same connection re-resolving
        }
        // Duplicate link to remoteId. Keep the SMALLER canonical nonce; DRAIN the loser (#2425).
        //
        // BOTH verdicts are INFO, and the level is the whole point (#2420). This pair records WHICH of the
        // two links to a peer each end kept, and the direction it kept it in — the only way to compare two
        // devices' accounts of one pair after the fact. At DEBUG they reach NEITHER log channel on a release
        // iPhone build: a field capture of a wedged session held 664 INFO / 7 WARN / 1 ERROR and **zero**
        // DEBUG records in a store that had not wrapped, so DEBUG was never captured rather than evicted —
        // which is why the #2425 investigation could not read them and had to be settled from the two phones'
        // Apple unified logs instead. (Those logs since established that the two ends' dedup AGREED and that
        // the surviving link carried traffic both ways throughout, so this pair is the RECORD of the decision,
        // not evidence of a disagreement.)
        return if (canonical < existing.canonicalNonce) {
            // THE PUBLISH-THEN-SWAP WINDOW (#2425). This arm is the one that MOVES a peer that the consumer
            // can already see: `resolved.first` published it on `existing.connId`, `peers` went `Woven`, and
            // the consumer was free to write to it — and now that link is being displaced. Measured on
            // hardware: ~10 ms wide, and 182 bytes were written into it, to a socket the far end had already
            // closed. Since #2425 the displaced link is DRAINED rather than cancelled, so those writes are
            // carried instead of destroyed; read its outbound count here, at the swap, because that count is
            // what makes the report a quantity rather than a hypothesis.
            val displaced = conns[existing.connId]
            // ONE reading of the clock, used for both the window that is closing and the one that is
            // opening — so the two cannot disagree by however long this critical section takes.
            val swappedAtMillis = nowMillis()
            val visibleForMillis = swappedAtMillis - existing.publishedAtMillis
            val writtenToDisplaced = displaced?.outboundFrames ?: 0
            registry[remoteId] = Winner(connId, canonical, swappedAtMillis) // new winner; peer stays present
            // The FOURTH wake site, and the one it is easiest to miss: this rebinds an EXISTING peer onto
            // a fresh ConnState whose `silenceReported` is false, so `watchdogPendingLocked` flips false →
            // true without going through [addRemotePeer] (the peer was already present) or the
            // [FrameOutcome.Data] arm (the frame that drove this resolve took the `resolved == null`
            // branch). Without it, a peer that had already been reported silent and parked the watchdog
            // gets a brand-new link that is NEVER watched — which is precisely the post-swap wedge this
            // whole class exists to make legible.
            wakeWatchdogLocked()
            // NOT removed from `conns` and NOT tombstoned (#2425): the displaced link keeps its ConnState so
            // its inbound frames stay attributed to this peer, and both obligations move to drain-end.
            draining[existing.connId] = Drain(
                peer = remoteId,
                arm = DrainArm.Replace,
                winner = connId,
                loserDialled = displaced?.endpoint?.id ?: INBOUND_LINK,
                winnerDialled = cs.endpoint?.id ?: INBOUND_LINK,
                visibleForMillis = visibleForMillis,
                framesWritten = writtenToDisplaced,
            )
            log.info {
                "nw.seam.dedup.replace remote=${remoteId.value} self=${selfId.value} " +
                    "winner=${connId.value}(nonce=$canonical, dialled=${cs.endpoint?.id ?: INBOUND_LINK}) " +
                    "loser=${existing.connId.value}(nonce=${existing.canonicalNonce}) → drain loser"
            }
            ResolveAction.Drain(existing.connId, remoteId)
        } else {
            // The KEEP arm drains too (#2425). It looks like the harmless half — the peer was never
            // published on this link locally, so there is no local window — but both ends dedup onto the
            // SAME physical link, so this loser is the REMOTE's replace-arm loser, with its window frames in
            // flight toward us. Cancelling it here destroys exactly the bytes the remote's drain is trying
            // to hand over.
            draining[connId] = Drain(
                peer = remoteId,
                arm = DrainArm.Keep,
                winner = existing.connId,
                loserDialled = cs.endpoint?.id ?: INBOUND_LINK,
                winnerDialled = conns[existing.connId]?.endpoint?.id ?: INBOUND_LINK,
                // The peer was never published here, so there is no local window to measure and nothing was
                // ever routed to this link. Zeroes are the honest reading, not placeholders.
                visibleForMillis = 0,
                framesWritten = 0,
            )
            log.info {
                "nw.seam.dedup.keep remote=${remoteId.value} self=${selfId.value} " +
                    "winner=${existing.connId.value}(nonce=${existing.canonicalNonce}, " +
                    "dialled=${conns[existing.connId]?.endpoint?.id ?: INBOUND_LINK}) " +
                    "loser=${connId.value}(nonce=$canonical) → drain loser"
            }
            ResolveAction.Drain(connId, remoteId)
        }
    }

    // ── the graceful displacement drain (#2425) ─────────────────────────────────

    /**
     * Begin draining [connId], the link [peer]'s dedup just displaced. Runs OUTSIDE [lock].
     *
     * Three things happen, in this order, and the order is load-bearing:
     *  1. **arm the ordering hold** for [peer], so the very next winner-link frame this demux loop
     *     classifies is already buffered rather than delivered ahead of the drained link's tail;
     *  2. **arm [drainBound]**, the zombie-link backstop — attached to the [Drain] under [lock] so a drain
     *     that already ended (a close that raced this) cannot leave a stray timer behind;
     *  3. **write exactly one [NwFrameType.Goodbye]**, the last thing this seam ever puts on this link. It
     *     is FIFO behind every window frame, which is what makes it a sound end-of-tail marker one layer
     *     above a transport that (#2467) will not give us a FIN at all.
     *
     * A goodbye the transport refuses outright means the link is already gone, so the drain ends
     * immediately rather than waiting out a bound on a link that can never deliver anything.
     */
    private suspend fun startDrain(connId: NwConnectionId, peer: PeerId) {
        armOrderingHold(peer)
        val bound = scope.launch(start = CoroutineStart.LAZY) {
            delay(drainBound)
            endDrain(connId, DrainOutcome.Bound)
        }
        val armed = lock.withLock {
            val drain = draining[connId] ?: return@withLock false
            drain.boundJob = bound
            true
        }
        if (!armed) {
            // The drain ended between resolveIdentity releasing the lock and this line — a close event or a
            // Closed state on the loser. Nothing to arm; the timer would fire against a tombstoned connId.
            bound.cancel()
            log.debug { "nw.seam.drain.start-raced connId=${connId.value} self=${selfId.value} (drain already ended)" }
            return
        }
        bound.start()
        val sent = runCatchingCancellable {
            api.send(connId, encodeFrame(NwWire.encodeGoodbye(), maxFrameBytes))
        }
        if (sent.isFailure) {
            log.info {
                "nw.seam.drain.goodbye-refused connId=${connId.value} self=${selfId.value} peer=${peer.value} " +
                    ": ${sent.exceptionOrNull()} → ending the drain now (#2425)"
            }
            endDrain(connId, DrainOutcome.LinkLoss)
        }
    }

    /**
     * What a just-ended drain still owes, computed under [lock] and settled outside it — the same
     * compute-under-the-lock / act-outside-it shape every other path in this class uses.
     */
    private class EndedDrain(
        val connId: NwConnectionId,
        val drain: Drain,
        val outcome: DrainOutcome,
        /** No OTHER drain to this peer is still running, so the peer's ordering hold may be released. */
        val lastForPeer: Boolean,
    )

    /**
     * Remove any drain on [connId] under [lock], returning what the caller must settle outside it, or
     * `null` when [connId] was not draining. **Called under [lock].**
     *
     * [EndedDrain.lastForPeer] is computed here rather than at release time because redial churn can leave
     * two drains to one peer in flight: releasing the hold on the first would deliver winner frames ahead
     * of the second drain's tail, which is the reordering the hold exists to prevent.
     */
    private fun takeDrainLocked(connId: NwConnectionId, outcome: DrainOutcome): EndedDrain? {
        val drain = draining.remove(connId) ?: return null
        return EndedDrain(connId, drain, outcome, lastForPeer = draining.values.none { it.peer == drain.peer })
    }

    /**
     * End the drain of [connId] and dispose of the link: remove it from [conns], tombstone it (the #1528
     * obligation, relocated here from the dedup arms), disconnect it, and release the peer's ordering hold.
     *
     * Reached from the two terminators that own the link's disposal — the remote's `GOODBYE` and
     * [drainBound]. A drain whose link died under it does NOT come through here: [removeByConn] and
     * [connectionClosedLoop] already remove and tombstone the connection on every arm, so they take the
     * drain in their own critical section and call [settleDrain] directly.
     *
     * ## The residual this cannot close, recorded rather than hidden
     * Disposing on the remote's goodbye can still destroy bytes of OUR OWN that the transport has accepted
     * but not yet put on the wire: `nw_connection_cancel` discards them, and `RealNwApi.send` is
     * fire-and-forget, so the seam has no send-completion to wait on. It is a far smaller window than the
     * one this fix closes — today's cancel lands ~1 ms after publish, this one lands after a full goodbye
     * exchange — but it is not zero, and the honest bound on it is the transport's, not ours.
     */
    private suspend fun endDrain(connId: NwConnectionId, outcome: DrainOutcome) {
        var graceJob: Job? = null
        val ended = lock.withLock {
            val taken = takeDrainLocked(connId, outcome) ?: return@withLock null
            graceJob = graceJobs.remove(connId) // any pending path-loss timer on the drained link is moot
            conns.remove(connId)
            tombstoneLocked(connId) // #1528, relocated: late bytes on the drained link must not resurrect it
            auditRegistryLocked("drain-end")
            taken
        } ?: return
        graceJob?.cancel()
        runCatchingCancellable { api.disconnect(connId) }
            .onFailure { log.debug { "nw.seam.drain.disconnect-failed connId=${connId.value}: ${it.message}" } }
        settleDrain(ended)
    }

    /**
     * Settle an [EndedDrain] outside [lock]: cancel its backstop, release the peer's ordering hold, report.
     *
     * The bound job is cancelled only when it is not the job currently running — the same deadline-race
     * guard [onGraceExpired] uses. Cancelling ourselves here would abort the rest of this function, which
     * is precisely the reporting and the hold release the [DrainOutcome.Bound] path exists to perform.
     */
    private suspend fun settleDrain(ended: EndedDrain) {
        val thisJob = currentCoroutineContext()[Job]
        ended.drain.boundJob?.takeIf { it !== thisJob }?.cancel()
        val released = if (ended.lastForPeer) releaseOrderingHold(ended.drain.peer) else -1
        val drain = ended.drain
        // The uniform mechanism receipt, on BOTH arms and at INFO: `drained=` is what proves the drain
        // carried something rather than merely ran, and `via=` says which of the three terminators fired.
        // A field capture with no line here at all is a drain that never ended — the one shape this
        // mechanism can fail in.
        log.info {
            "nw.seam.drain-end connId=${ended.connId.value} self=${selfId.value} peer=${drain.peer.value} " +
                "arm=${drain.arm.label} via=${ended.outcome.label} drained=${drain.drainedFrames} " +
                "hold-released=${if (released < 0) "no(another drain to this peer is still running)" else released} " +
                "live-link=${drain.winner.value}(dialled=${drain.winnerDialled}) " +
                "state=${_state.value} peers=${_peers.value.map { it.value }}"
        }
        if (drain.arm != DrainArm.Replace) return
        // WARN, and separate from the verdict above, because it reports a CONDITION rather than a decision:
        // the decision is routine and correct, the window it opens is the hazard. Emitted at DRAIN-END
        // rather than at the swap so it carries the outcome: a window measured without knowing whether the
        // drain that followed it delivered anything is the half of the report a field capture already had.
        // Always emitted on this arm — a zero count records a benign window and its width, which is what
        // makes a non-zero one legible as the anomaly it is. Bounded: one per peer per re-resolution.
        log.warn {
            "nw.seam.publish-swap self=${selfId.value} peer=${drain.peer.value} " +
                "published-on=${ended.connId.value}(dialled=${drain.loserDialled}) " +
                "now-on=${drain.winner.value}(dialled=${drain.winnerDialled}) " +
                "visible-for=${drain.visibleForMillis}ms frames-written-to-published-link=${drain.framesWritten} " +
                "drained=${drain.drainedFrames} via=${ended.outcome.label} " +
                "state=${_state.value} peers=${_peers.value.map { it.value }} " +
                "→ the peer was moved off a link it was reachable on; that link was DRAINED rather than " +
                "cancelled, so what was written into the window was carried across (#2425)"
        }
    }

    // ── the receiver ordering hold (#2425) ──────────────────────────────────────

    /**
     * Arm [peer]'s ordering hold: from now until its last drain ends, frames arriving on its LIVE link are
     * buffered instead of delivered. Idempotent — a second concurrent drain to one peer keeps the first
     * hold and its contents rather than starting a fresh, empty one.
     */
    private suspend fun armOrderingHold(peer: PeerId) {
        stageMutex.withMutex { orderingHolds.getOrPut(peer) { mutableListOf() } }
    }

    /**
     * Release [peer]'s ordering hold, flushing everything it buffered into [deliveryStage] in arrival
     * order, and return how many frames that was (0 if no hold was armed).
     *
     * The flush happens under [stageMutex], so a frame arriving on the demux loop mid-flush queues behind
     * the buffer rather than overtaking it. That is the entire reason the mutex exists.
     */
    private suspend fun releaseOrderingHold(peer: PeerId): Int = stageMutex.withMutex {
        val held = orderingHolds.remove(peer) ?: return@withMutex 0
        for (frame in held) sendStagedLocked(peer, frame)
        held.size
    }

    /**
     * The ONE path from [bytesReceivedLoop] to [deliveryStage], and the place the ordering hold is applied.
     *
     * ## Buffer-and-continue, never suspend — the deadlock this shape exists to make impossible
     * Both links' bytes arrive through the single [bytesReceivedLoop]. A hold that SUSPENDED that loop on a
     * winner frame could never process the `GOODBYE` that would release it, and the seam would wedge
     * permanently. So when the hold is armed and has room this appends and returns; it never waits on
     * anything the demux loop is itself responsible for producing.
     *
     * A frame from the DRAINING link is never held — it is the tail the hold is waiting for.
     *
     * ## Overflow releases EARLY rather than backpressuring
     * A bounded buffer must do something at the bound, and backpressure is the one option that is not
     * available: suspending here would reintroduce the deadlock by another route. So the hold is released
     * early, the reorder is accepted, and it is reported at WARN — a loud, bounded admission that
     * [Seam.incoming]'s send-order promise was traded for liveness on this peer, in preference to a silent
     * wedge.
     *
     * ## Sequence is stamped HERE, at release time
     * [Swatch.sequence] is assigned inside [stageMutex] immediately before the stage write, so stamped
     * order is delivery order for held and unheld frames alike. Stamping at classify time (as this used to)
     * would have numbered a held frame ahead of the winner frames delivered while it waited.
     */
    private suspend fun stageInboundData(sender: PeerId, frame: ByteArray, fromDrainingLink: Boolean) {
        var overflowed = 0
        stageMutex.withMutex {
            val hold = if (fromDrainingLink) null else orderingHolds[sender]
            if (hold != null) {
                if (hold.size < orderingHoldCapacity) {
                    hold += frame
                    return@withMutex
                }
                overflowed = hold.size
                orderingHolds.remove(sender)
                for (buffered in hold) sendStagedLocked(sender, buffered)
            }
            sendStagedLocked(sender, frame)
        }
        if (overflowed > 0) {
            log.warn {
                "nw.seam.drain.hold-overflow self=${selfId.value} peer=${sender.value} " +
                    "buffered=$overflowed capacity=$orderingHoldCapacity " +
                    "→ the drained link's tail has not arrived within one hold's worth of live-link frames; " +
                    "releasing early and DELIVERING OUT OF SEND ORDER for this peer rather than " +
                    "backpressuring the shared receive loop, which would wedge it (#2425)"
            }
        }
    }

    /**
     * Stamp [frame] and hand it to [deliveryStage]. **Called only under [stageMutex]**, which is what makes
     * the stamp order and the stage order the same order.
     *
     * `dropFirst` strips the type byte as a ZERO-COPY view (the pattern [Swatch] documents for framing
     * layers) — the alternative, materialising the body in [NwWire.decode], would copy every received
     * payload a second time on this loop.
     */
    private suspend fun sendStagedLocked(sender: PeerId, frame: ByteArray) {
        deliveryStage.send(
            Swatch(payload = frame, sender = sender, sequence = seq.incrementAndGet())
                .dropFirst(NwWire.TYPE_BYTES),
        )
    }

    /** Add [remoteId] to the peer set and flip Weaving→Woven. Called under [lock]. */
    private fun addRemotePeer(remoteId: PeerId) {
        _peers.update { it + remoteId }
        val wove = _state.value is SeamState.Weaving
        if (wove) _state.value = SeamState.Woven
        refreshSettledLocked()
        wakeWatchdogLocked() // a newly-settled link has never been reported — see [inboundSilenceLoop]
        log.debug { "nw.seam.peer-added remote=${remoteId.value} self=${selfId.value} peers=${_peers.value.map { it.value }} state=${_state.value}${if (wove) " (Weaving→Woven)" else ""}" }
    }

    /**
     * Recompute [settledEndpoints] from the current membership: the endpoint of every live registered
     * peer (via the sticky [peerEndpoint] map) plus every self-resolved endpoint. Called under [lock]
     * on every identity/membership change. Idempotent — writing an unchanged set to the [StateFlow]
     * emits nothing.
     *
     * Every CHANGE to the set is logged at INFO with per-entry provenance, because this set is what
     * parks `NwLoom.redialLoop`: an entry here silences that endpoint's dials until it leaves again, so
     * a wrong entry is a peer starved for the seam's lifetime (#2416/#2417). "Which endpoints are
     * settled, and why each one settled" was unanswerable from a hardware capture — the set was written
     * silently and only its *consequence* (a device that never forms a session) was observable.
     * Comparing against the current value first keeps the line to real transitions; the write itself was
     * already a no-op when unchanged, so nothing about the published flow changes.
     */
    private fun refreshSettledLocked() {
        val next = buildSet {
            addAll(selfEndpointIds)
            for (peer in registry.keys) peerEndpoint[peer]?.let { add(it) }
        }
        val previous = _settledEndpoints.value
        if (next == previous) return
        _settledEndpoints.value = next
        log.info {
            "nw.seam.settled self=${selfId.value} " +
                "added=${(next - previous).map { "$it(${settledProvenanceLocked(it)})" }} " +
                "removed=${previous - next} " +
                "now=${next.map { "$it(${settledProvenanceLocked(it)})" }}"
        }
    }

    /**
     * Why [endpointId] is in [settledEndpoints] — `self` (a dial that resolved to this peer, so it is our
     * own advertisement) or `peer=<id>` (the endpoint a connected peer was reached on). The distinction is
     * the whole diagnosis of a stuck formation: `self` on an endpoint that is really another device is the
     * #2416 failure, while `peer=` on a peer that is not in [peers] is ADR-005's sibling. Called under
     * [lock]; read-only.
     */
    private fun settledProvenanceLocked(endpointId: String): String = when {
        endpointId in selfEndpointIds -> "self"
        else -> registry.keys.firstOrNull { peerEndpoint[it] == endpointId }?.let { "peer=${it.value}" } ?: "?"
    }

    /**
     * Evict [peer] from the roster and, if it was the LAST remote after having woven, re-form
     * [SeamState.Woven] → [SeamState.Weaving] (peers → `{selfId}`) rather than latching [SeamState.Torn]
     * — peer loss is recoverable (#1513); `NwLoom` redials the endpoint. Called under [lock] from both
     * [connectionClosedLoop] (a clean remote close) and [removeByConn] (send-failure / #1478 grace
     * eviction), so the terminal-vs-recoverable decision is identical on every peer-loss path. [incoming]
     * stays open; the seam only completes it on a true [close]/weave-timeout tear.
     */
    private fun evictPeerLocked(peer: PeerId) {
        registry.remove(peer)
        _peers.update { it - peer }
        refreshSettledLocked()
        if (registry.isEmpty() && _state.value is SeamState.Woven) {
            _state.value = SeamState.Weaving
        }
    }

    /** Log line for a completed [evictPeerLocked]; reads post-eviction [peers]/[state] under [lock]. */
    private fun evictVerdict(peer: PeerId): String =
        "evicted peer=${peer.value} → peers=${_peers.value.map { it.value }} state=${_state.value} (reform, not tear)"

    // ── loop 3: connectionClosed ────────────────────────────────────────────────

    private suspend fun connectionClosedLoop() {
        api.connectionClosed.collect { event ->
            if (closed.value) {
                log.debug { "nw.seam.closed.ignored connId=${event.connectionId.value} self=${selfId.value} (seam already torn)" }
                return@collect
            }
            // Classify the close under the lock; log the verdict after releasing it. A peer loss NEVER
            // tears (#1513): [evictPeerLocked] re-forms Woven→Weaving when the last remote drops.
            var verdict = "no-op"
            var graceJob: Job? = null
            var endedDrain: EndedDrain? = null
            lock.withLock {
                // `run` so every early exit still falls through to the #2420 audit below: this block
                // removes from `conns` on EVERY arm, including the two that deliberately do not evict, so
                // it is the site where a registry entry could be orphaned.
                run {
                    graceJob = graceJobs.remove(event.connectionId) // any pending path-loss timer is moot now
                    // #2425: a drain whose link died under it. Taken here rather than left to expire on its
                    // own bound — this block removes and tombstones the connection on every arm, so a drain
                    // left behind would hold the peer's ordering hold open for the whole bound with nothing
                    // left that could ever release it.
                    endedDrain = takeDrainLocked(event.connectionId, DrainOutcome.LinkLoss)
                    val cs = conns.remove(event.connectionId)
                    tombstoneLocked(event.connectionId) // #1528: a closed conn is dead — drop any late/buffered bytes on it
                    if (cs == null) { verdict = "unknown-conn"; return@run }
                    val peer = cs.resolvedPeerId
                    if (peer == null) { verdict = "unresolved-conn (no peer to evict)"; return@run }
                    // Conn-identity guard: only evict the peer if the LIVE connection is this one — a
                    // stale/deduped-loser close must not evict the surviving connection to the same peer.
                    if (registry[peer]?.connId != event.connectionId) {
                        verdict = "stale/loser-close for peer=${peer.value} (live conn=${registry[peer]?.connId?.value}) — NOT evicting"
                        return@run
                    }
                    evictPeerLocked(peer)
                    verdict = evictVerdict(peer)
                }
                auditRegistryLocked("closed")
            }
            graceJob?.cancel()
            log.info { "nw.seam.closed connId=${event.connectionId.value} self=${selfId.value}: $verdict" }
            endedDrain?.let { settleDrain(it) }
        }
    }

    // ── loop 4: connectionStates — the #1478 grace timer + #1522 teardown, unified (#1539) ──

    /**
     * The ONE drop-tolerant per-connection [NwConnState] STATE signal ([NwApi.connectionStates]), unifying
     * the former separate viability (#1509) and closed-markers (#1522) collectors (#1539). Each emission is a
     * snapshot of every tracked connection's latest state, so we [reconcileStates] rather than react to
     * individual transitions. Because the LATEST value per connection is never lost (only intermediate
     * transitions may coalesce) and [NwConnState.Closed] is monotone+dominant, a dropped recovery can never
     * strand an armed timer (spurious tear), a dropped loss can never leave a zombie peer, and a close can
     * never be conflated away.
     */
    private suspend fun connectionStatesLoop() {
        api.connectionStates.collect {
            if (closed.value) return@collect
            // The emission still DRIVES the reconcile (every state change fires it); reconcileStates re-reads
            // the freshest `api.connectionStates.value` under the lock (#1566), so the emitted value is not used.
            reconcileStates()
        }
    }

    // ── loop 5: pathState — the #1541 reactive-capability driver ─────────────────

    /**
     * Fold the transport's live [NwApi.pathState] (an `NWPathMonitor` on `RealNwApi`) into [capability].
     * A `null` path state means "unknown" — the binding has not wired a real monitor (the JVM bridge, or the
     * default fake) — so we publish [unobservedCapability]: the fabric's ROLES with an honest
     * [FabricAvailability.Unknown], never a guessed verdict (#1712). A non-null state supplies the availability
     * via [NwPathState.toAvailability] AND drives the ROLES (#1554): the base fabric roles
     * ([staticRoles] = [NwLoom.NW_ROLES] = Discovery+Data) plus the live medium role — [TransportRole.WifiDirect]
     * for a peer-to-peer AWDL path, [TransportRole.WifiLan] for an infrastructure path ([NwPathState.interfaceRoles],
     * driven by the [classifyWifiInterface] BSD-name heuristic). A non-Wi-Fi path (cellular/wired/down) adds no
     * medium role, so the roles revert to the base set. The write goes to the seam-owned [_capability]
     * MutableStateFlow, so this single collector is the sole writer — no lock needed. Terminates with [scope]
     * on close (this loop holds no per-connection state).
     */
    private suspend fun pathStateLoop() {
        api.pathState.collect { path ->
            _capability.value =
                if (path == null) {
                    unobservedCapability
                } else {
                    TransportCapability(
                        roles = staticRoles + path.interfaceRoles(),
                        availability = path.toAvailability(),
                    )
                }
        }
    }

    /**
     * The capability of a seam with **no live path reading**: the fabric's static roles, but an honest
     * [FabricAvailability.Unknown] availability.
     *
     * A `null` [NwApi.pathState] means the binding wired no `NWPathMonitor` (the JVM dylib bridge) or the
     * monitor has not yet reported ground truth. Either way this seam does not know whether its path is up
     * and must say so. It cannot fall back on `NwLoom.availability()` even by accident: [staticRoles] carries
     * roles only, because that value answers a *platform-support* question and reusing it as a live verdict
     * was the #1712 defect. Note the file already answers the equivalent question this way one level down:
     * [NwPathState.toAvailability] maps [NwPathStatus.Invalid] ("monitor has not reported ground truth") to
     * `Unknown`, and a `null` path state is strictly less informative than `Invalid`.
     */
    private val unobservedCapability: TransportCapability
        get() = TransportCapability(
            roles = staticRoles,
            availability = FabricAvailability.Unknown("no path monitor has reported on this binding"),
        )

    /**
     * Reconcile the transport's per-connection latest [NwConnState] map — the drop-tolerant #1539
     * unification of the former `reconcileViability` (#1509) and `reconcileClosed` (#1522). On every emission
     * we re-derive the outcome from the LATEST value per connection, so a coalesced/lost intermediate
     * transition can never strand a timer, miss a loss, or lose a close. For each reported connection we
     * dispatch on its single current state:
     *  - **[NwConnState.Closed]** on a still-tracked connection → tear IMMEDIATELY via [removeByConn] (reused
     *    verbatim, so closure teardown is identical to the send-failure / grace-expiry paths: tombstone,
     *    grace-timer cancel, the conn-identity guard that spares a dedup-loser's survivor, and the last-remote
     *    re-form to [SeamState.Weaving] per #1513). Closure is TERMINAL — no grace timer, matching
     *    [connectionClosedLoop]. The `it in conns` pre-filter keeps steady-state re-reconciles silent and makes
     *    double-fire with loop 3 safe: whichever runs first removes the conn, the other filters it out.
     *  - **[NwConnState.Viable]** (path up / recovered) → cancel any armed grace timer (a no-op if none).
     *  - **[NwConnState.PathLost]** on a still-tracked connection → arm a grace timer if not already armed.
     *
     * Because [NwConnState.Closed] is monotone+dominant at the producer, a connection reported `Closed` can
     * never subsequently reappear as `Viable`/`PathLost`, so a torn peer never resurrects. Idempotent: an
     * already-armed loss, an already-clear recovery, or an already-removed close is a steady-state no-op.
     * Arm/cancel/remove decisions are taken under [lock] (so the [graceJobs] mutation is atomic); the [Job]
     * `start`/`cancel` side effects and [removeByConn] run OUTSIDE the lock (the latter re-acquires it),
     * preserving the seam-wide "no non-trivial call under the lock" rule. Safe to call concurrently from the
     * states collector AND the [conns]-insertion sites (#1509/#1522 lost-wakeup guard): the check-then-arm is
     * one lock acquisition, and `start()` on a lazily-armed job a concurrent reconcile already cancelled is a
     * harmless no-op.
     *
     * ## Reads the freshest state UNDER the lock (#1566)
     * The [NwConnState] map is re-read from [NwApi.connectionStates] `value` INSIDE [lock], **not** taken as a
     * caller-supplied parameter. The lost-wakeup catch-up sites ([connectionOpenedLoop]/[bytesReceivedLoop])
     * used to capture `api.connectionStates.value` on the caller's thread and pass it in; that snapshot could
     * go stale before this locked section ran (a concurrent states emission the collector already reconciled),
     * and because a [StateFlow] never re-emits an unchanged value the stale outcome was FINAL — a stale
     * [NwConnState.PathLost] armed a grace timer that tore a healthy conn, and a stale [NwConnState.Viable]
     * cancelled a legitimately-armed timer, stranding a permanent zombie peer. Re-reading `value` under the
     * same lock that makes the arm/cancel/remove decision means whichever reconcile runs LAST operates on a
     * map at least as fresh as any earlier one, closing the hole in both directions. The read is a
     * non-suspending [StateFlow] field access — it introduces no lock ordering, and no suspend under the lock.
     *
     * Acts ONLY on a state's PRESENCE — a connId absent from the reconciled map is NEVER inferred to be closed.
     *
     * ## Accepted trade-off: a flap does not restart the grace clock
     * A `PathLost → Viable → PathLost` flap does NOT restart the grace clock whenever the fresh read already
     * shows the second loss: the reconcile driven by the `Viable` emission reads `value`, sees `PathLost`, and
     * takes the `connId in graceJobs` no-op branch — so the recovery never cancels the timer and the second loss
     * INHERITS the first timer's remaining time rather than arming a fresh full-length one. This holds
     * unconditionally, not just for a burst that `StateFlow` conflated: since #1566 the reconcile acts on the
     * freshest `value` rather than the emitted map, so the window is "the reconcile lagged the state change at
     * all", not "the emissions were conflated away". (Before #1566 a *delivered* `Viable` emission did cancel
     * the timer, and the following `PathLost` armed a full-length one.)
     *
     * This is the deliberate **level-triggered anti-flap** semantic: the timer tracks "how long has this path
     * been down", not "how long since the most recent down-edge", so a rapidly flapping path cannot indefinitely
     * postpone its own eviction by briefly recovering. It can only ever UNDER-grant grace to a path whose LATEST
     * state is down; it can never strand a timer on, or tear, a path whose latest state is `Viable` (a recovery
     * that is still the latest value when the reconcile runs always cancels).
     *
     * We chose NOT to restore full-grace-on-recovery: doing so would require tracking the last-acted-on state
     * per connection and re-arming on an observed `Viable → PathLost` EDGE — reintroducing edge-triggered state
     * into a deliberately level-triggered reconcile, for a strictly weaker liveness guarantee. Noted here so it
     * isn't rediscovered as a "bug".
     */
    private suspend fun reconcileStates() {
        val toCancel = mutableListOf<Pair<NwConnectionId, Job>>()
        val armed = mutableListOf<Pair<NwConnectionId, Job>>()
        val armSkipped = mutableListOf<NwConnectionId>()
        val toRemove = mutableListOf<NwConnectionId>()
        lock.withLock {
            // #1566: read the FRESHEST state under the lock, never a caller-captured snapshot that may have
            // gone stale before we acquired it. A non-suspending StateFlow field access.
            val states = api.connectionStates.value
            for ((connId, st) in states) {
                when (st) {
                    is NwConnState.Closed -> if (connId in conns) toRemove += connId // terminal — tear immediately
                    NwConnState.Viable -> graceJobs.remove(connId)?.let { toCancel += connId to it }
                    NwConnState.PathLost -> when {
                        connId !in conns -> armSkipped += connId // conn closed/evicted or not yet tracked
                        connId in graceJobs -> Unit // already armed — reconcile steady state, no-op
                        else -> {
                            val job = scope.launch(start = CoroutineStart.LAZY) {
                                delay(wovenPathGrace)
                                onGraceExpired(connId)
                            }
                            graceJobs[connId] = job
                            armed += connId to job
                        }
                    }
                }
            }
        }
        for ((connId, job) in toCancel) {
            job.cancel()
            log.info { "nw.seam.viability.recovered connId=${connId.value} self=${selfId.value} → grace cancelled" }
        }
        for ((connId, job) in armed) {
            log.info { "nw.seam.viability.lost connId=${connId.value} self=${selfId.value} → grace armed ($wovenPathGrace)" }
            job.start()
        }
        for (connId in armSkipped) {
            log.debug { "nw.seam.viability.arm-skipped connId=${connId.value} self=${selfId.value} reason=not-in-conns" }
        }
        for (connId in toRemove) {
            log.info { "nw.seam.closed-state connId=${connId.value} self=${selfId.value} → removeByConn (drop-tolerant teardown)" }
            removeByConn(connId)
        }
    }

    /**
     * The grace timer for [connId] expired without recovery: the peer is unreachable. Best-effort
     * [NwApi.disconnect] the dead connection and drive the local eviction via [removeByConn] — reusing
     * the send-failure eviction path. Since #1513 a last-remote loss re-forms to [SeamState.Weaving]
     * (recoverable), NOT [SeamState.Torn]: `NwLoom` redials the endpoint, so a path that comes back is
     * rejoined. Driving the eviction locally is deliberate: the transport emits no close for a `waiting`
     * connection, so we cannot wait for a looped-back [NwApi.connectionClosed].
     *
     * ## Deadline-race identity guard
     * [delay] cannot be cancelled once it has resumed, and the only later suspension ([NwApi.disconnect])
     * carries no cancellation point — so a `viable=true`/close/re-arm that lands in the same virtual
     * instant as the expiry cannot stop this job from running. We therefore remove-and-proceed ONLY if
     * we still own the timer (`graceJobs[connId] === this job`): a losing recovery already replaced/
     * removed our entry, so we abort — tearing nothing and, crucially, NOT evicting a `connId` whose
     * entry now belongs to a *second* path-loss's freshly-armed timer (which would silently give that
     * loss ~0s grace). A genuinely-late recovery still tears via its own owning job.
     */
    private suspend fun onGraceExpired(connId: NwConnectionId) {
        val thisJob = currentCoroutineContext()[Job]
        val owned = lock.withLock {
            if (graceJobs[connId] !== thisJob) return@withLock false
            graceJobs.remove(connId)
            true
        }
        if (!owned) {
            log.debug { "nw.seam.grace.expired.stale connId=${connId.value} self=${selfId.value} → superseded by recovery/close/re-arm; no tear" }
            return
        }
        log.info { "nw.seam.grace.expired connId=${connId.value} self=${selfId.value} → disconnect + evict (reform to Weaving)" }
        runCatchingCancellable { api.disconnect(connId) }
            .onFailure { log.debug { "nw.seam.grace.disconnect-failed connId=${connId.value}: ${it.message}" } }
        removeByConn(connId)
    }

    // ── the wedge watchdog: diagnostics for a silent formation (#2420/#2425) ──

    /**
     * Assert this seam's own bookkeeping invariant under [lock]: **every [registry] entry names a connId
     * that is still in [conns]**, and log an ERROR naming the offending identities when one does not.
     *
     * ## Why ERROR, and why it is worth asserting at all
     * A violation is impossible per `NwSeam`'s own model. Every removal path — [connectionClosedLoop],
     * [removeByConn], the dedup arms of [resolveIdentity], [close] — either evicts the peer alongside the
     * connection or first proves (via the conn-identity guard) that the connection was not the live one.
     * So this cannot be a *condition*; if it fires, host-side bookkeeping is wrong, and the seam is
     * routing a peer over a connection it no longer tracks. That is exactly the report a reader of a field
     * capture needs, and it is why it is **ERROR** where its sibling [sweepInboundSilence] is WARN: one
     * names a contract violation, the other a condition. Do not flatten them.
     *
     * ## WHERE it is evaluated, and why that position is the load-bearing part
     * A stale binding is not created at insert time — it is created when a connection LEAVES [conns] and
     * the [registry] entry naming it is not repointed or evicted. So auditing only where [registry] is
     * WRITTEN would structurally miss it. This runs in **every critical section that removes from [conns]**,
     * which is the complete set of moments the invariant can break:
     *
     *  - `site=classify` — [processFrame]'s locked section, the one path that writes [registry]. It covers
     *    [resolveIdentity]'s dedup arms, which remove the losing connection in the same breath as they
     *    rebind (or fail to rebind) the peer. A replace that dropped the incumbent from [conns] without
     *    moving the peer onto the winner is reported here, in the same critical section that did it.
     *  - `site=closed` / `site=removeByConn` — [connectionClosedLoop] and [removeByConn], on **every** arm
     *    including `unknown-conn` and the two conn-identity arms that deliberately do not evict. Those arms
     *    are exactly where a binding that went stale EARLIER is first observable, so they fall through to
     *    the audit rather than returning past it.
     *  - `site=watchdog` — [sweepInboundSilence], periodically. The seam's failure mode is silence: a peer
     *    can be left bound to a dead connection and then nothing further happens at all, so a purely
     *    event-driven audit would never run again. This is the arm that reports a wedge nobody poked.
     *
     * The `site=` field is itself diagnostic: it says whether the dedup broke the binding (`classify`), a
     * later close revealed an already-broken one (`closed`), or nothing but the clock found it (`watchdog`).
     *
     * ## It did NOT happen in #2425 — this is a backstop, not that bug's explanation
     * Stated because the opposite was believed for a day and is the kind of claim that outlives its
     * evidence. A stale binding was the leading reading of the 2026-08-17 wedge — the host's peer settled
     * on `nw-2`, `nw-2` left [conns], and its close 1 ms later read `unknown-conn` — and a byte-level
     * correlation of both devices then **refuted** it: 742 bytes crossed the surviving link in each
     * direction during the eight seconds, application-layer pongs included, so the binding was correct and
     * live throughout. The real defect there was the publish-then-swap window (see [resolveIdentity]'s
     * replace arm and `nw.seam.publish-swap`), not this.
     *
     * So the value of this check is that it is CHEAP and DECIDABLE, and that its absence is now evidence:
     * a future capture showing `nw.seam.inbound-silent` with no `nw.seam.registry.orphan` beside it has
     * ruled out local bookkeeping in one line, which is exactly the step that cost a session to establish
     * by hand. Do not read a firing here as "the #2425 bug"; read it as its own, unrelated defect.
     *
     * Identities, not sizes: the offending peer and the connId the registry NAMES, the live [conns] key
     * set (so the surviving link is named directly), the whole `peer→conn` registry, whether the connId
     * was tombstoned (which says the removal ran and the eviction did not), plus `state`/`peers`. A count
     * would say that something is wrong; these say what, and which link to look at next.
     *
     * Bounded by [reportedOrphans]: one ERROR per peer per episode, however many audits run.
     * Logging under [lock] is the established pattern in this file ([refreshSettledLocked],
     * [addRemotePeer]) — `log.error` neither suspends nor calls back into the seam.
     */
    private fun auditRegistryLocked(site: String) {
        reportedOrphans.retainAll(registry.keys)
        for ((peer, winner) in registry) {
            // Two ways one binding can be unusable, reported through one bound (#2420/#2425): the connId is
            // not tracked at all, or it IS tracked but is DRAINING — a link this seam has already told the
            // remote it has finished writing on. The second is what makes "a draining conn is never
            // selected for sends" an enforced property rather than an argument about [registry] and
            // [draining] being disjoint by construction: [broadcast]/[sendTo] route through exactly this
            // map, so an entry naming a draining link would send into a goodbye'd socket.
            val binding = when {
                winner.connId !in conns -> "not-tracked"
                winner.connId in draining -> "draining"
                else -> null
            }
            if (binding == null) {
                reportedOrphans.remove(peer)
                continue
            }
            if (!reportedOrphans.add(peer)) continue
            log.error {
                "nw.seam.registry.orphan self=${selfId.value} peer=${peer.value} connId=${winner.connId.value} " +
                    "site=$site binding=$binding nonce=${winner.canonicalNonce} " +
                    "tombstoned=${winner.connId in tombstones} " +
                    "state=${_state.value} peers=${_peers.value.map { it.value }} " +
                    "registry=${registry.map { (p, w) -> "${p.value}→${w.connId.value}" }} " +
                    "conns=${conns.keys.map { it.value }} draining=${draining.keys.map { it.value }} " +
                    "→ CONTRACT VIOLATION: this seam still routes ${peer.value} over a connection it no " +
                    "longer tracks, so every send to it is written to a link nothing owns (#2420)"
            }
        }
    }

    /**
     * Bumped whenever something happens that could make [watchdogPendingLocked] true — a peer registering,
     * an inbound frame re-arming a reported link. Purely a wake signal; the value carries no meaning. A
     * [MutableStateFlow] because its write must be safe from any thread and must not suspend under [lock].
     */
    private val watchdogWake = MutableStateFlow(0L)

    /** Signal the watchdog that state changed. Called under [lock]; a non-suspending atomic write. */
    private fun wakeWatchdogLocked() {
        watchdogWake.value = watchdogWake.value + 1
    }

    /**
     * Is there anything left for the watchdog to report? True iff some [registry] entry either has a live
     * [ConnState] whose silence has not yet been reported, or has NO `ConnState` and has not yet been
     * reported as an orphan. False means every live link has already had its say, and the loop may sleep
     * with **no timer armed** — which is the whole reason this predicate exists (see [inboundSilenceLoop]).
     * Called under [lock].
     */
    private fun watchdogPendingLocked(): Boolean = registry.any { (peer, winner) ->
        val cs = conns[winner.connId]
        if (cs == null) peer !in reportedOrphans else !cs.silenceReported
    }

    /**
     * The wedge watchdog (#2420). Sweeps every [inboundSilenceProbe] **while there is something left to
     * report**, then parks on [watchdogWake] with no pending timer until state changes again.
     *
     * ## Why it parks rather than re-arming forever — this shape is load-bearing, not an optimisation
     * A perpetually re-arming timer is incompatible with `runTest`: after the body returns, `runTest`
     * advances virtual time until idle, and a timer that always re-arms is never idle — the test does not
     * fail, it **HANGS**, burning CPU in virtual time. `SeamConformanceSuite` documents exactly this hazard
     * for `NwLoom`'s redial loop and defends against it by closing both seams; but any consumer test that
     * leaves a woven seam open on the test's own job would hang on this timer, and three `:kuilt-nw` test
     * tasks did precisely that when this loop was first written as an unconditional `while (true) { delay }`.
     *
     * Parking fixes it structurally rather than by convention: when every settled link has been reported
     * there is no scheduled work, so `advanceUntilIdle` completes. It also makes the diagnostic quieter,
     * for free — an idle-but-healthy session emits its one line per link and then goes silent, instead of
     * waking every probe forever to decide it has nothing to say.
     *
     * The wake sites are the only FOUR places [watchdogPendingLocked] can go from false to true:
     * [addRemotePeer] (a peer arriving on a link that has never been reported), the [FrameOutcome.Data] arm
     * (an arrival re-arming an already-reported link), [resolveIdentity]'s dedup-REPLACE arm (an existing
     * peer rebound onto a fresh, never-reported link — the one that goes through neither of the first two,
     * because the peer is not new and the driving frame took the identity branch), and
     * [dropConnWithoutEvictingForAuditRig].
     *
     * A missed wake parks the watchdog early and the diagnostic is then silently absent — the worst failure
     * this class can have, since it looks exactly like a healthy session. That is why the predicate is
     * derived from the very fields the sweep mutates rather than from a separate flag that could drift, and
     * why each site has a test that reds when its wake is removed.
     *
     * [Duration.ZERO] (or negative) disables it entirely: the loop returns and the seam carries no timer at
     * all, which is what a test wanting no watchdog output passes.
     */
    private suspend fun inboundSilenceLoop() {
        if (inboundSilenceProbe <= Duration.ZERO) return
        while (true) {
            // Parks here — and a park schedules nothing, so a terminal `advanceUntilIdle` can complete.
            watchdogWake.first { lock.withLock { watchdogPendingLocked() } }
            delay(inboundSilenceProbe)
            if (closed.value) return
            for (line in sweepInboundSilence()) log.warn { line }
        }
    }

    /**
     * One watchdog sweep: build (under [lock]) the WARN lines for every settled peer whose live link has
     * carried no inbound frame across a full [inboundSilenceProbe], and return them for the caller to log
     * outside the lock.
     *
     * ## The line is a CONDITION, not a bug — hence WARN, not ERROR
     * `nw.seam.resolved.first` then nothing is the whole observable signature of #2425: a resolved, live
     * roster over which no 2PC frame traverses for 8 s. But a quiet link is not by itself a defect — an
     * application with nothing to say produces the same silence — so this reports a condition and the
     * reader decides. What makes it worth a WARN anyway is that on this fabric it is the ONLY thing that
     * distinguishes a wedged device from an idle one: a parked seam emits nothing for its whole lifetime,
     * so a wedge and a healthy quiet session have byte-identical logs.
     *
     * ## Two silent sweeps, not one
     * A connection settles part-way through a sweep interval, so the first sweep after it settles covers
     * a window that starts before the link existed and would report a silence shorter than the probe.
     * Requiring [SILENT_SWEEPS_TO_WARN] consecutive silent sweeps makes the reported silence at least one
     * full probe interval and at most two — a bound the message states, so a reader never has to infer it.
     *
     * ## Edge-triggered, so it cannot spam
     * One WARN per silence episode per link. An arriving frame clears [ConnState.silenceReported], so a
     * link that goes quiet again is reported again; a link that stays quiet is not. An idle-but-healthy
     * session therefore costs one line per link, once — the accepted price of the wedge being legible.
     *
     * ## What it deliberately does NOT do
     * No tear, no redial, no remediation of any kind (#2420). The consuming app's own bounds are
     * authoritative today and nothing here may race them; this is observability.
     *
     * `dialled=` records which end opened the link this seam settled on. Put the two devices' lines side
     * by side and you can see directly whether they kept the SAME link — a fact that had to be recovered
     * from both phones' Apple unified logs during #2425, because kuilt's own account of it was at DEBUG
     * and therefore absent from the capture. (It turned out they did agree; the field is here so the next
     * reader can establish that in one line rather than by correlating two system logs.)
     */
    private fun sweepInboundSilence(): List<String> {
        val lines = mutableListOf<String>()
        lock.withLock {
            // Iterating [registry] is what confines this to WINNERS: a draining loser (#2425) is in [conns]
            // but never in [registry], so it is structurally out of scope here — which is right, because a
            // link this seam has already said goodbye on is EXPECTED to go quiet, and reporting it would
            // manufacture a wedge warning out of the fix for one.
            for ((peer, winner) in registry) {
                // A registry entry with no ConnState is an ORPHAN, not a silent link — auditRegistryLocked
                // below owns that report, and reporting it here too would say "quiet" about a connection
                // this seam cannot even read.
                val cs = conns[winner.connId] ?: continue
                if (cs.inboundFrames != cs.framesAtLastSweep) {
                    cs.framesAtLastSweep = cs.inboundFrames
                    cs.silentSweeps = 0
                    cs.silenceReported = false
                    continue
                }
                cs.silentSweeps += 1
                if (cs.silentSweeps < SILENT_SWEEPS_TO_WARN || cs.silenceReported) continue
                cs.silenceReported = true
                lines += "nw.seam.inbound-silent self=${selfId.value} peer=${peer.value} " +
                    "connId=${winner.connId.value} dialled=${cs.endpoint?.id ?: "<inbound>"} " +
                    "resolved=${cs.endpoint?.identityResolved} frames-in=${cs.inboundFrames} " +
                    "silent-for>=$inboundSilenceProbe state=${_state.value} " +
                    "peers=${_peers.value.map { it.value }} " +
                    "registry=${registry.map { (p, w) -> "${p.value}→${w.connId.value}" }} " +
                    "→ the link is settled and reported live, and nothing has traversed it (#2425)"
            }
            auditRegistryLocked("watchdog")
        }
        return lines
    }

    /**
     * TEST-ONLY rig for [auditRegistryLocked]'s positive control (#2420): drop [peer]'s live connection from
     * [conns] WITHOUT touching [registry] — precisely the bookkeeping bug the audit exists to name. Returns
     * the connId it forgot, so the caller can assert the report names that link and not some other one, or
     * `null` if [peer] is not registered (which a test should treat as its rig having failed to fire).
     *
     * It exists because no [NwApi] input can produce that state (see [auditRegistryLocked]: every removal
     * path either evicts the peer or proves the connection was not the live one), and a check that is never
     * made to fire is green by absence — it passes identically whether or not it works. Rigging the failure
     * is the only way the ERROR arm is ever observed, so the rig is the difference between a tested guard
     * and a decorative one.
     *
     * `internal`, and named so that its only plausible caller is a test. Nothing in production calls it, and
     * nothing should: it deliberately leaves this seam in the state the audit reports as a contract violation.
     */
    internal fun dropConnWithoutEvictingForAuditRig(peer: PeerId): NwConnectionId? = lock.withLock {
        val connId = registry[peer]?.connId ?: return@withLock null
        conns.remove(connId)
        // The rig creates an orphan without going through a mutation site, so it owes the watchdog the same
        // wake a real state change would give it — otherwise the loop stays parked and the rig proves nothing.
        wakeWatchdogLocked()
        connId
    }

    // ── send ────────────────────────────────────────────────────────────────────

    /**
     * The largest payload this seam will carry (#2069) — [maxFrameBytes] less the one byte of
     * [NwWire.TYPE_BYTES] the self-describing body spends on its frame type (#2425).
     *
     * The FRAME ceiling is unchanged and stays the one number enforced at both edges of the wire
     * ([encodeFrame] on send, each connection's [NwFramer] on receive), because the type byte rides
     * *inside* the payload those two agree on. What the type byte costs is therefore taken out of the
     * caller's budget rather than added to the wire — which is why it is reported as `reservedBytes`
     * on a refusal ([oversizeOrNull]) and not hidden. Widening the frame ceiling by one instead would
     * have made a maximal `:kuilt-nw` frame one byte larger than a maximal `:kuilt-stream` one.
     *
     * Publishing it is a **promise to carry** a payload of that size, not merely to refuse above it, and
     * that promise was withheld until #2134. Both `NwApi` implementations used to hand received bytes off
     * lossily — a bounded `tryEmit` on Apple, a 64-slot `DROP_OLDEST` channel on the JVM bridge — while the
     * transport delivers at most 64 KiB per receive. A 16 MiB frame is 256+ chunks against a 64-slot
     * buffer, so chunks vanished silently, the frame never completed, and a length-prefixed stream cannot
     * resynchronize after a gap. The receive path now applies real backpressure instead (the re-arm waits
     * on the consumer), so the promise is one the fabric can keep, and the TCK's
     * `payloadOfExactlyTheBudgetIsCarried` — the case that found the defect — is what holds it.
     */
    override val maxPayloadBytes: Int = maxFrameBytes - NwWire.TYPE_BYTES

    /**
     * Refuse [payload] if it cannot be framed, rather than letting [encodeFrame] throw from inside a
     * `runCatchingCancellable` whose `onFailure` means *dead link* (#2069). That is what turned one
     * mis-sized payload into an evicted healthy peer — and, when it was the last remote, a roster
     * collapsed to `{selfId}` by [evictPeerLocked]'s re-form — while the throwable was swallowed and
     * the caller was told the send had been accepted.
     *
     * Returns the refusal for the caller to raise or ignore, per the two methods' differing
     * contracts, so both read the ceiling exactly once and in the same way. The ceiling read is
     * [maxPayloadBytes] — the number this seam PUBLISHES — never [maxFrameBytes]: with a byte of the
     * frame now spent on the type discriminator the two differ, and checking the frame ceiling here
     * would accept exactly the one payload [encodeFrame] then throws on.
     */
    private fun oversizeOrNull(payload: ByteArray): PayloadTooLarge? =
        if (payload.size > maxPayloadBytes) {
            PayloadTooLarge(
                payloadBytes = payload.size,
                budgetBytes = maxPayloadBytes,
                reservedBytes = NwWire.TYPE_BYTES,
            )
        } else {
            null
        }

    override suspend fun broadcast(payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        // Best-effort by contract: an over-budget payload is DROPPED, not reported. Every link under
        // this seam shares one ceiling, so — unlike a mesh of independently-framed links — there is no
        // subset that could still carry it, and the drop is whole rather than per link.
        if (oversizeOrNull(payload) != null) {
            log.debug { "nw.seam.broadcast.over-budget self=${selfId.value} payload=${payload.size}B budget=${maxPayloadBytes}B → dropped (best-effort)" }
            return
        }
        // #2420: count what is handed to each link in the SAME locked snapshot that chooses the targets, so
        // the count and the routing decision cannot disagree. The over-budget drop already returned above,
        // so nothing counted here is a frame this seam declined to send.
        //
        // #2425: the snapshot is taken from [registry], which names WINNERS only — a draining loser is in
        // [conns] but never here, so it can never be selected. [auditRegistryLocked] asserts that rather
        // than leaving it to this comment. The residual it does NOT close is the one this seam cannot:
        // a broadcast whose targets were snapshotted just before a dedup can still put one frame on a link
        // a concurrent [startDrain] then says goodbye on, so that frame races past the goodbye. Its
        // ordering was undefined anyway — it was issued concurrently with the swap — and the drain still
        // carries it, so the frame is delivered; only its position relative to the goodbye is unpinned.
        val targets = lock.withLock {
            registry.values.map { winner ->
                conns[winner.connId]?.let { it.outboundFrames += 1 }
                winner.connId
            }
        }
        val frame = encodeFrame(NwWire.encodeData(payload), maxFrameBytes)
        for (connId in targets) {
            runCatchingCancellable { api.send(connId, frame) }
                .onFailure {
                    log.info { "nw.seam.broadcast.send-failed connId=${connId.value} self=${selfId.value}: ${it.message} → removeByConn" }
                    removeByConn(connId)
                }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        // `registry` keys remotes only, so without this a self-send fell out as PeerNotConnected —
        // false for an id this seam's own `peers` names (#2428).
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        // Addressed and reporting, per contract: the over-budget refusal is decided BEFORE the encode, so
        // the caller learns the number it should have respected and the link below is never implicated.
        // COMPUTED here and THROWN below, so `PeerNotConnected` keeps its precedence over `PayloadTooLarge`
        // for a peer that is both absent and over-budget — moving the throw up here would silently swap
        // which of the two refusals a caller sees.
        val oversize = oversizeOrNull(payload)
        // #2420: the routing decision and the outbound count are taken in ONE critical section, the rule
        // [broadcast] already states. Split across two acquisitions they can disagree: this seam must be
        // correct under a multi-threaded dispatcher, so the bytes loop can run [resolveIdentity]'s dedup-
        // REPLACE arm in between — `conns[connId]` is then gone, the increment silently no-ops, and the
        // frame still goes out on the displaced link. That would drop precisely the send that
        // `frames-written-to-published-link` exists to count: the one stranded by the swap.
        val connId = lock.withLock {
            val winner = registry[peer]?.connId ?: return@withLock null
            // Count only what is actually handed to the transport, never a frame this seam refuses.
            if (oversize == null) conns[winner]?.let { it.outboundFrames += 1 }
            winner
        } ?: throw PeerNotConnected(peer)
        oversize?.let { throw it }
        runCatchingCancellable { api.send(connId, encodeFrame(NwWire.encodeData(payload), maxFrameBytes)) }
            .onFailure {
                log.info { "nw.seam.sendTo.send-failed peer=${peer.value} connId=${connId.value} self=${selfId.value}: ${it.message} → removeByConn" }
                removeByConn(connId)
            }
    }

    /**
     * Drop a connection after a send failure or a path-loss grace expiry, evicting its peer only if it
     * is still the live link. Shares [connectionClosedLoop]'s rule via [evictPeerLocked]: a last-remote
     * loss re-forms Woven→Weaving (recoverable, #1513) rather than tearing — [incoming] stays open and
     * `NwLoom` redials. The decision is computed under [lock]; any pending grace timer for [connId] is
     * cancelled after releasing it.
     *
     * Suspending since #2425, because a connection being torn here may be one that is DRAINING — a
     * displaced link whose #1478 grace timer expired, whose send failed, or whose frame was refused — and
     * ending that drain releases the peer's ordering hold, which writes to [deliveryStage].
     */
    private suspend fun removeByConn(connId: NwConnectionId) {
        var verdict = "no-op"
        var graceJob: Job? = null
        var endedDrain: EndedDrain? = null
        lock.withLock {
            // `run` for the same reason as [connectionClosedLoop]'s: every arm removes from `conns`, so
            // every arm must reach the #2420 audit.
            run {
                graceJob = graceJobs.remove(connId)
                // #2425: the drain's link is being torn out from under it — see [connectionClosedLoop] for
                // why this cannot be left to the bound. `onGraceExpired` → here is the path that made this
                // mandatory: a grace timer armed on a now-draining connection must END the drain, not
                // orphan it.
                endedDrain = takeDrainLocked(connId, DrainOutcome.LinkLoss)
                val cs = conns.remove(connId)
                tombstoneLocked(connId) // #1528: this conn is being torn — drop any late/buffered bytes on it
                if (cs == null) { verdict = "unknown-conn"; return@run }
                val peer = cs.resolvedPeerId
                if (peer == null) { verdict = "unresolved-conn"; return@run }
                if (registry[peer]?.connId != connId) {
                    verdict = "stale/loser conn for peer=${peer.value} — NOT evicting"
                    return@run
                }
                evictPeerLocked(peer)
                verdict = evictVerdict(peer)
            }
            auditRegistryLocked("removeByConn")
        }
        graceJob?.cancel()
        log.info { "nw.seam.removeByConn connId=${connId.value} self=${selfId.value}: $verdict" }
        endedDrain?.let { settleDrain(it) }
    }

    // ── close ─────────────────────────────────────────────────────────────────

    override suspend fun close(reason: CloseReason) {
        log.debug { "nw.seam.close.requested self=${selfId.value} reason=$reason state=${_state.value}" }
        // Single-shot: if a self-driven Torn (last-peer drop) already fired, this no-ops.
        if (!latchTorn(reason)) return
        val targets = lock.withLock {
            // Draining losers are torn down too — they are live sockets this seam owns, and `registry` (the
            // winners) does not name them. Their bound jobs die with [scope] in [latchTorn]; their ordering
            // holds die with the [spool] the same call closes, so nothing is left to release them to.
            val snapshot = registry.values.map { it.connId } + draining.keys
            registry.clear()
            draining.clear()
            conns.keys.forEach { tombstoneLocked(it) } // #1528: every cleared conn is dead — no resurrection on a late frame
            conns.clear()
            graceJobs.clear() // scope cancellation (in latchTorn) stops the jobs; just drop the refs
            peerEndpoint.clear()
            selfEndpointIds.clear()
            _settledEndpoints.value = emptySet()
            _peers.value = setOf(selfId)
            snapshot
        }
        for (connId in targets) {
            runCatchingCancellable { api.disconnect(connId) }
        }
        // #1419 (I3): the seam owns the close lifecycle and holds the [api] that `NwLoom.weave` started
        // advertising + browsing on — so tearing the connections is not enough. Stop the advertiser and the
        // browser too, AFTER the connections are down. On a real device an un-stopped `NWListener`/`NWBrowser`
        // keeps the Bonjour advertiser and AWDL up after the seam closes, and its ObjC block handlers capture
        // `RealNwApi` — leaking the listener/browser/queue/`RealNwApi` per weave. `RealNwApi` already implements
        // cancel-first-then-drop for both handles (and both are documented no-ops if not started); it just was
        // never told to. Best-effort: a stop failure must not mask the close.
        runCatchingCancellable { api.stopListening() }
            .onFailure { log.debug { "nw.seam.close.stopListening-failed self=${selfId.value}: ${it.message}" } }
        runCatchingCancellable { api.stopBrowsing() }
            .onFailure { log.debug { "nw.seam.close.stopBrowsing-failed self=${selfId.value}: ${it.message}" } }
    }

    /**
     * Terminal teardown, latched exactly once via [closed]. Publishes [SeamState.Torn], completes
     * [incoming] by closing the [spool], and cancels [scope] (stopping all collectors). Returns `false`
     * if teardown already ran. Since #1513 its ONLY caller is [close] — an explicit consumer close or the
     * `NwLoom.weave` timeout (which routes through [close] as [CloseReason.Unreachable]). Peer loss no
     * longer tears (it re-forms to [SeamState.Weaving] via [evictPeerLocked]).
     *
     * The terminal `_state = Torn` write is taken UNDER [lock] so it serializes against the locked
     * state-machine writers ([evictPeerLocked]'s `Woven → Weaving`, [addRemotePeer]'s `Weaving → Woven`).
     * Without the lock, a writer could read `is Woven`/`is Weaving`, be preempted by this Torn write, then
     * complete its read-then-write and **clobber terminal Torn back to Weaving/Woven** — un-tearing a seam
     * whose [incoming] is already completed and scope cancelled (breaking `stateStaysTornAfterClose`). Under
     * the lock, a writer that runs after this reads `Torn` (neither `Woven` nor `Weaving`) and makes no
     * transition. Both writes are non-suspend, so this respects the no-suspend-under-lock rule; the [closed]
     * CAS stays outside the lock as the single-latch gate. [scope] cancellation runs after releasing it.
     */
    private fun latchTorn(reason: CloseReason): Boolean {
        if (!closed.compareAndSet(expect = false, update = true)) {
            log.debug { "nw.seam.latchTorn.noop self=${selfId.value} (already torn) reason=$reason" }
            return false
        }
        log.info { "nw.seam.TORN self=${selfId.value} reason=$reason peers-were=${_peers.value.map { it.value }}" }
        lock.withLock {
            _state.value = SeamState.Torn(reason)
            spool.close()
        }
        scope.coroutineContext[Job]?.cancel()
        return true
    }

    /**
     * Start the seam's seven coroutines. **This block must stay LAST in the class body**, below every
     * property declaration — that position is load-bearing (#2462), not a style preference.
     *
     * ## Why
     * Kotlin runs property initialisers and `init` blocks **in declaration order**, so a coroutine
     * started from a property initialiser can only safely touch state declared *above* it. #2451 broke
     * that: it added the watchdog as `private val silenceJob = scope.launch { inboundSilenceLoop() }`
     * beside the other loops near the top of the class, and the [watchdogWake] flow the loop reads 840
     * lines below. Anything that ran the body before the constructor finished then dereferenced a `null`
     * backing field — an NPE on the JVM, a SIGSEGV on Kotlin/Native — *inside a `launch`*, so it reached
     * the scope's exception handler rather than the caller: the seam was constructed successfully with
     * its watchdog silently already dead, which is the exact failure mode #2420 exists to remove.
     *
     * ## Why this position rather than moving one declaration
     * Hoisting [watchdogWake] above the launch also fixes the instance, and leaves a **pairwise**
     * constraint standing: every launch must sit below every field it *transitively* reads, re-checked
     * on every future edit, with nothing to catch a violation — which is how the defect arrived in the
     * first place. Starting everything from one terminal `init` collapses that to a single constraint on
     * a single, obviously-terminal block, and makes the property that actually matters
     * — *nothing runs until construction is complete* — hold for a field added **anywhere** in the class.
     * (A `companion object` declares no instance state, so following this block it initialises nothing
     * and does not weaken the position.)
     *
     * `NwSeamConstructionOrderTest` is the executable half: it constructs a seam on an eager dispatcher,
     * where a launched body runs inline at the launch site, and fails on any coroutine that throws during
     * construction. A regression here reddens there deterministically.
     *
     * ## What the individual launches mean
     * The first four are `UNDISPATCHED` so all four collectors subscribe **synchronously at
     * construction** — before any `connectionOpened`/`bytes`/`close`/`state` event can be emitted
     * (subscribe-before-trigger). Moving them from mid-constructor to end-of-constructor does not weaken
     * that: no property initialiser between the two positions touches [api], so no event can be emitted
     * in the interval, and the subscription is still in place before this constructor returns.
     */
    init {
        // A frame ceiling that cannot hold the type byte would publish a zero-or-negative
        // [maxPayloadBytes] — a budget no caller can satisfy, discovered as a refusal on every send
        // rather than as a construction error. Fail fast instead.
        require(maxFrameBytes > NwWire.TYPE_BYTES) {
            "maxFrameBytes=$maxFrameBytes leaves no room for the ${NwWire.TYPE_BYTES}-byte frame type"
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionOpenedLoop() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { bytesReceivedLoop() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionClosedLoop() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionStatesLoop() }
        // The single dedicated reader draining [deliveryStage] into the [spool] (#1415). Cancelled with
        // [scope] at teardown. It is the ONLY caller of [Spool.deliver], so the (possibly-suspending)
        // delivery backpressure lives here, off the shared demux loop.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { deliveryDrainLoop() }
        // #1541: fold the live network-path state into [capability]. Mirrors the other collectors —
        // UNDISPATCHED so it subscribes (and reads the current path value) synchronously at construction,
        // and cancelled with [scope] on [close]/[latchTorn].
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pathStateLoop() }
        // #2420: the wedge watchdog. Deliberately NOT UNDISPATCHED — it subscribes to nothing, so there is
        // no subscribe-before-trigger obligation to honour, and its first act is a park. Cancelled with
        // [scope]. This is the launch #2462 was: on a real (or otherwise eager) dispatcher its body could
        // run before [watchdogWake] existed.
        scope.launch { inboundSilenceLoop() }
    }

    internal companion object {
        /** How a link the far end opened is named in a diagnostic — it carries no dialled endpoint. */
        const val INBOUND_LINK: String = "<inbound>"

        /** Default grace given a path-lost (`ready → waiting`) connection to recover before the seam tears it (#1478). */
        val DEFAULT_WOVEN_PATH_GRACE: Duration = 10.seconds

        /**
         * Default zombie-link backstop for the displacement drain (#2425).
         *
         * It is deliberately NOT the mechanism: the healthy path ends in-band on the remote's
         * [NwFrameType.Goodbye], milliseconds after the swap, with no timer consulted at all. This only
         * bounds the pathological case — a link that has stopped delivering in the drained direction and
         * will never produce that goodbye.
         *
         * Two seconds, because the drain holds two things open while it runs: a socket, and the peer's
         * receiver ordering hold. Frame-scale transport hiccups are milliseconds, so this never truncates a
         * real drain; and it is a small fraction of the ~8 s bound a consuming application applies to
         * formation (#2425), so a zombie link cannot consume a meaningful share of that budget.
         */
        val DEFAULT_DRAIN_BOUND: Duration = 2.seconds

        /**
         * Default depth of a peer's receiver ordering hold (#2425) — the same shape as
         * [DELIVERY_STAGING_CAPACITY], because it absorbs the same kind of transient: how far the live link
         * may run ahead of a drained link's tail before the seam gives up on ordering the two.
         *
         * Bounded, never unbounded. Backpressuring at the bound is the one option that is not available —
         * it would suspend the shared demux loop on a release only that loop can perform.
         */
        const val DEFAULT_ORDERING_HOLD_CAPACITY: Int = DeliveryPolicy.DEFAULT_CAPACITY

        /**
         * Default sweep interval for the wedge watchdog (#2420).
         *
         * With [SILENT_SWEEPS_TO_WARN] the reported silence is at least one interval and at most two, so
         * the `nw.seam.inbound-silent` WARN lands between 3 s and 6 s after a link settles quiet. That is
         * chosen to fall STRICTLY INSIDE the ~8 s bound a consuming application applies to formation today
         * (#2425), so the fabric's own account of the link is already in the trail when the consumer's
         * WARN fires, and the two can be read as one story rather than correlated by guesswork. It is a
         * LOGGING cadence and nothing else: the watchdog never tears, redials or otherwise acts, so it
         * cannot race that bound however it is set.
         */
        val DEFAULT_INBOUND_SILENCE_PROBE: Duration = 3.seconds

        /**
         * Consecutive silent watchdog sweeps before `nw.seam.inbound-silent` fires.
         *
         * Two, not one, because a connection settles part-way through a sweep interval: the first sweep
         * after it settles covers a window that began before the link existed, so reporting on it would
         * claim a silence the seam cannot vouch for. Two makes the claim sound — the reported silence is
         * in `[probe, 2 × probe)` — which is why the message says `silent-for>=<probe>` rather than a
         * number it would have to compute from a clock it does not have.
         */
        const val SILENT_SWEEPS_TO_WARN: Int = 2

        /**
         * Depth of the [deliveryStage] staging channel (#1415) — the headroom by which the shared demux loop
         * may run ahead of a slow local consumer before backpressure reaches reads. Bounded (never unbounded,
         * which would be a contract violation); sized to the [spool]'s default capacity so a transient consumer
         * stall of up to a spool's worth of frames is absorbed without touching the shared receive loop.
         */
        const val DELIVERY_STAGING_CAPACITY: Int = DeliveryPolicy.DEFAULT_CAPACITY

        /**
         * Upper bound on the [tombstones] FIFO of recently-removed connIds (#1528). A late/buffered frame
         * races its connection's eviction by at most a handful of milliseconds, so retaining the last
         * [TOMBSTONE_CAP] removed connIds is far more than enough to catch every in-flight straggler while
         * keeping the set from growing without bound on a long-lived, churny seam.
         */
        const val TOMBSTONE_CAP: Int = 1024
    }
}
