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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * since [NwApi]'s flows are hot with no replay):
 *
 *  1. **connectionOpened** — sends our identity frame ([NwHello]: this peer's [PeerId] plus this
 *     connection's per-connection dedup nonce).
 *  2. **bytesReceived** — the demux + inline handshake: the first decoded frame on an unresolved
 *     connection is the remote's [NwHello] (id + nonce); every later frame is data, stamped with that
 *     sender and handed to the bounded [deliveryStage] (drained by [deliveryDrainLoop] into [incoming]) so a
 *     slow local consumer never wedges this shared loop's reads for other connections' handshakes (#1415).
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
 * direction or collector ordering**; the loser is disconnected and its later close is a no-op
 * (conn-identity guard). This is a port of `:kuilt-core`'s `MeshSeam` rule — the old direction-based
 * rule could wedge a pair to zero under a multi-threaded dispatcher (direction was written by one
 * collector and read by another with no happens-before); the nonce rule cannot.
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
 * @param maxFrameBytes the largest payload this seam's framing will carry, published as
 *   [maxPayloadBytes] and enforced by both edges of the wire — [encodeFrame] on send and each
 *   connection's [NwFramer] on receive. One number, threaded to both, so the ceiling this seam
 *   *publishes* is by construction the ceiling it *enforces* (#2069); previously each edge reached
 *   for [DEFAULT_MAX_FRAME_SIZE] independently and the seam published nothing. Production default
 *   [DEFAULT_MAX_FRAME_SIZE] (16 MiB); tests inject a small value so an over-budget payload costs
 *   bytes rather than megabytes.
 */
internal class NwSeam(
    override val selfId: PeerId,
    private val api: NwApi,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val wovenPathGrace: Duration = DEFAULT_WOVEN_PATH_GRACE,
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
    }

    /** The live connection carrying a resolved peer, plus the canonical nonce both ends agreed on. */
    private data class Winner(val connId: NwConnectionId, val canonicalNonce: String)

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

    // Single latch flag, read/written across every path (receive/close/send).
    private val closed = atomic(false)

    // Stamped onto every delivered Swatch; incremented from the single bytes loop but atomic for safety.
    private val seq = atomic(0L)

    private val closedMessage get() = "NwSeam for ${selfId.value} is closed"

    // UNDISPATCHED so all four collectors subscribe synchronously at construction — before any
    // connectionOpened/bytes/close/state event can be emitted (subscribe-before-trigger).
    private val openedJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionOpenedLoop() }
    private val bytesJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { bytesReceivedLoop() }
    private val closedJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionClosedLoop() }
    private val statesJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionStatesLoop() }

    // The single dedicated reader draining [deliveryStage] into the [spool] (#1415). Launched like every
    // other loop and cancelled with [scope] at teardown. It is the ONLY caller of [Spool.deliver], so the
    // (possibly-suspending) delivery backpressure lives here, off the shared demux loop.
    private val drainJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { deliveryDrainLoop() }

    // #1541: fold the live network-path state into [capability]. Mirrors the other collectors — UNDISPATCHED
    // so it subscribes (and reads the current path value) synchronously at construction, and cancelled with
    // [scope] on [close]/[latchTorn] (the same launch/cancel lifecycle as [closedJob]).
    private val pathJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { pathStateLoop() }

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
            runCatchingCancellable { api.send(connId, encodeFrame(NwHello.encode(selfId, cs.nonce), maxFrameBytes)) }
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

    /** Outcome of classifying one frame under [lock]; the suspend action runs OUTSIDE the lock. */
    private sealed interface FrameOutcome {
        /** Already-resolved connection: [frame] is data attributed to [sender]. */
        data class Data(val sender: PeerId) : FrameOutcome

        /** Just-resolved identity: [loser] (if any) is the dedup loser to disconnect. */
        data class Resolved(val loser: NwConnectionId?) : FrameOutcome

        /**
         * The first frame on an unresolved connection failed to decode as an [NwHello] (#1528 part B):
         * routed through the shared [evictCorruptConn] backstop OUTSIDE the lock rather than letting the
         * decode throw escape [processFrame] and kill [bytesReceivedLoop]. Bounds the worst symptom even if
         * a tombstone is missed.
         */
        object DecodeFailed : FrameOutcome

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
     * Handle ONE decoded frame: the first on an unresolved connection is identity; the rest are data.
     *
     * The `resolvedPeerId == null` check and the [resolveIdentity] mutation happen in the SAME
     * critical section, so [connectionClosedLoop] cannot interleave between them and re-register a
     * peer on an already-closed connection (the identity-resolution race). The suspend actions
     * ([Spool.deliver], [NwApi.disconnect]) run OUTSIDE the lock.
     */
    private suspend fun processFrame(connId: NwConnectionId, cs: ConnState, frame: ByteArray) {
        var decodeError: Throwable? = null
        val outcome = lock.withLock {
            val resolved = cs.resolvedPeerId
            when {
                // #1528 finding 2: getOrCreateConnForBytes and this classify are two lock acquisitions, so a
                // removal path can tombstone/replace [connId] between them. Resolving identity on a dead conn
                // would register registry[peer] = Winner(deadConnId) — an unevictable zombie. If this cs is no
                // longer the live one (replaced) or its connId was tombstoned, DROP the frame.
                conns[connId] !== cs || connId in tombstones -> FrameOutcome.Dropped
                resolved != null -> FrameOutcome.Data(resolved)
                else -> {
                    // #1528 part B: a corrupt/undecodable first frame must NOT throw out of the collector and
                    // kill the receive loop. Narrowly wrap ONLY the decode (never resolveIdentity); a real
                    // structured-concurrency cancel is always re-thrown, never swallowed.
                    val hello = try {
                        NwHello.decode(frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        decodeError = e
                        null
                    }
                    if (hello == null) {
                        FrameOutcome.DecodeFailed
                    } else {
                        FrameOutcome.Resolved(resolveIdentity(connId, cs, hello.peerId, hello.nonce))
                    }
                }
            }
        }
        when (outcome) {
            // Data frame — hand OFF to the bounded staging channel (#1415), never call the SUSPEND-under-
            // Reliable Spool.deliver inline here: that would wedge this shared demux loop (and every other
            // connection's handshake) whenever THIS sender's consumer is slow. The single deliveryDrainLoop
            // owns Spool.deliver. Sequence is stamped in arrival order here, so FIFO is preserved across the
            // stage. Runs OUTSIDE the lock; deliveryStage.send only suspends when the (bounded) stage is full.
            is FrameOutcome.Data ->
                deliveryStage.send(Swatch(payload = frame, sender = outcome.sender, sequence = seq.incrementAndGet()))
            // Dedup loser (if any) — disconnect OUTSIDE the lock (best-effort). The loser's ConnState was
            // just removed from `conns` in resolveIdentity; cancel any grace timer armed for it too, for
            // symmetry with connectionClosedLoop/removeByConn. Normally the loser's own connectionClosed
            // would cancel it; doing it here means a dropped close can't leave a stray timer that later
            // fires a no-op disconnect/eviction against a connId the seam no longer tracks.
            is FrameOutcome.Resolved -> outcome.loser?.let { loserId ->
                val graceJob = lock.withLock { graceJobs.remove(loserId) }
                graceJob?.cancel()
                runCatchingCancellable { api.disconnect(loserId) }
                    .onFailure { log.debug { "nw.dedup disconnect failed connId=${loserId.value}" } }
            }
            // Undecodable first frame (#1528 part B) — routed through the shared corrupt-inbound backstop.
            is FrameOutcome.DecodeFailed ->
                evictCorruptConn(connId, "hello-decode failed: ${decodeError?.message}")
            // Stale/dead conn at classify time (#1528 finding 2) — nothing to do; the frame is dropped.
            is FrameOutcome.Dropped ->
                log.debug { "nw.seam.classify.dropped-stale connId=${connId.value} self=${selfId.value} (conn removed/tombstoned before classify)" }
        }
    }

    /**
     * Shared backstop for a corrupt inbound on [connId] that cannot be parsed (#1528): either [NwFramer.decode]
     * threw on a bad length prefix (in [bytesReceivedLoop]) or [NwHello.decode] threw on an unresolved conn (in
     * [processFrame]). Best-effort disconnect the connection OUTSIDE the lock, then drive the local eviction via
     * [removeByConn] — which removes it from [conns], records a [tombstoneLocked], and evicts its peer if it was
     * the live link (so a corrupt chunk on a *resolved* conn doesn't strand a zombie in [registry]). A single
     * corrupt chunk can therefore never kill the receive loop. Suspends only OUTSIDE the lock, preserving the
     * no-suspend-under-lock rule.
     */
    private suspend fun evictCorruptConn(connId: NwConnectionId, reason: String) {
        log.warn { "nw.seam.corrupt-inbound connId=${connId.value} self=${selfId.value}: $reason → disconnect + evict (loop preserved)" }
        runCatchingCancellable { api.disconnect(connId) }
            .onFailure { log.debug { "nw.seam.corrupt-inbound.disconnect-failed connId=${connId.value}: ${it.message}" } }
        removeByConn(connId)
    }

    /**
     * Resolve [connId]'s identity to [remoteId] under [lock]. Returns the connId to disconnect (a
     * dedup loser) or `null`. Adds the peer + flips Weaving→Woven when this is the first connection
     * to [remoteId]; on a duplicate, keeps the canonical survivor (the smaller [canonicalLinkNonce]
     * of the two connections' nonces) — the peer set is unchanged either way. Direction-free: both
     * ends see the same two nonces and pick the same survivor with no coordination.
     */
    private fun resolveIdentity(
        connId: NwConnectionId,
        cs: ConnState,
        remoteId: PeerId,
        remoteNonce: ByteArray,
    ): NwConnectionId? {
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
            return connId
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
            registry[remoteId] = Winner(connId, canonical)
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
        // Duplicate link to remoteId. Keep the SMALLER canonical nonce; disconnect the loser.
        return if (canonical < existing.canonicalNonce) {
            registry[remoteId] = Winner(connId, canonical) // new winner; peer stays present
            conns.remove(existing.connId) // drop the displaced incumbent's state
            tombstoneLocked(existing.connId) // #1528: late bytes on the displaced link must not resurrect it
            log.debug {
                "nw.seam.dedup.replace remote=${remoteId.value} winner=${connId.value}(nonce=$canonical) " +
                    "loser=${existing.connId.value}(nonce=${existing.canonicalNonce}) → disconnect loser"
            }
            existing.connId // disconnect the displaced incumbent
        } else {
            conns.remove(connId) // drop this loser's state
            tombstoneLocked(connId) // #1528: late bytes on this loser link must not resurrect it
            log.debug {
                "nw.seam.dedup.keep remote=${remoteId.value} winner=${existing.connId.value}(nonce=${existing.canonicalNonce}) " +
                    "loser=${connId.value}(nonce=$canonical) → disconnect loser"
            }
            connId // this connection loses; disconnect it, incumbent stays
        }
    }

    /** Add [remoteId] to the peer set and flip Weaving→Woven. Called under [lock]. */
    private fun addRemotePeer(remoteId: PeerId) {
        _peers.update { it + remoteId }
        val wove = _state.value is SeamState.Weaving
        if (wove) _state.value = SeamState.Woven
        refreshSettledLocked()
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
            lock.withLock {
                graceJob = graceJobs.remove(event.connectionId) // any pending path-loss timer is moot now
                val cs = conns.remove(event.connectionId)
                tombstoneLocked(event.connectionId) // #1528: a closed conn is dead — drop any late/buffered bytes on it
                if (cs == null) { verdict = "unknown-conn"; return@withLock }
                val peer = cs.resolvedPeerId
                if (peer == null) { verdict = "unresolved-conn (no peer to evict)"; return@withLock }
                // Conn-identity guard: only evict the peer if the LIVE connection is this one — a
                // stale/deduped-loser close must not evict the surviving connection to the same peer.
                if (registry[peer]?.connId != event.connectionId) {
                    verdict = "stale/loser-close for peer=${peer.value} (live conn=${registry[peer]?.connId?.value}) — NOT evicting"
                    return@withLock
                }
                evictPeerLocked(peer)
                verdict = evictVerdict(peer)
            }
            graceJob?.cancel()
            log.info { "nw.seam.closed connId=${event.connectionId.value} self=${selfId.value}: $verdict" }
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
    private fun reconcileStates() {
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

    // ── send ────────────────────────────────────────────────────────────────────

    /**
     * The largest payload this seam will carry (#2069) — [maxFrameBytes], the one number enforced at both
     * edges of the wire: [encodeFrame] on send and each connection's [NwFramer] on receive.
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
    override val maxPayloadBytes: Int = maxFrameBytes

    /**
     * Refuse [payload] if it cannot be framed, rather than letting [encodeFrame] throw from inside a
     * `runCatchingCancellable` whose `onFailure` means *dead link* (#2069). That is what turned one
     * mis-sized payload into an evicted healthy peer — and, when it was the last remote, a roster
     * collapsed to `{selfId}` by [evictPeerLocked]'s re-form — while the throwable was swallowed and
     * the caller was told the send had been accepted.
     *
     * Returns the refusal for the caller to raise or ignore, per the two methods' differing
     * contracts, so both read the ceiling exactly once and in the same way.
     */
    private fun oversizeOrNull(payload: ByteArray): PayloadTooLarge? =
        if (payload.size > maxFrameBytes) {
            PayloadTooLarge(payloadBytes = payload.size, budgetBytes = maxFrameBytes, reservedBytes = 0)
        } else {
            null
        }

    override suspend fun broadcast(payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        // Best-effort by contract: an over-budget payload is DROPPED, not reported. Every link under
        // this seam shares one ceiling, so — unlike a mesh of independently-framed links — there is no
        // subset that could still carry it, and the drop is whole rather than per link.
        if (oversizeOrNull(payload) != null) {
            log.debug { "nw.seam.broadcast.over-budget self=${selfId.value} payload=${payload.size}B budget=${maxFrameBytes}B → dropped (best-effort)" }
            return
        }
        val targets = lock.withLock { registry.values.map { it.connId } }
        val frame = encodeFrame(payload, maxFrameBytes)
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
        val connId = lock.withLock { registry[peer]?.connId } ?: throw PeerNotConnected(peer)
        // Addressed and reporting, per contract: raise PayloadTooLarge BEFORE the encode, so the
        // caller learns the number it should have respected and the link below is never implicated.
        oversizeOrNull(payload)?.let { throw it }
        runCatchingCancellable { api.send(connId, encodeFrame(payload, maxFrameBytes)) }
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
     */
    private fun removeByConn(connId: NwConnectionId) {
        var verdict = "no-op"
        var graceJob: Job? = null
        lock.withLock {
            graceJob = graceJobs.remove(connId)
            val cs = conns.remove(connId)
            tombstoneLocked(connId) // #1528: this conn is being torn — drop any late/buffered bytes on it
            if (cs == null) { verdict = "unknown-conn"; return@withLock }
            val peer = cs.resolvedPeerId
            if (peer == null) { verdict = "unresolved-conn"; return@withLock }
            if (registry[peer]?.connId != connId) {
                verdict = "stale/loser conn for peer=${peer.value} — NOT evicting"
                return@withLock
            }
            evictPeerLocked(peer)
            verdict = evictVerdict(peer)
        }
        graceJob?.cancel()
        log.info { "nw.seam.removeByConn connId=${connId.value} self=${selfId.value}: $verdict" }
    }

    // ── close ─────────────────────────────────────────────────────────────────

    override suspend fun close(reason: CloseReason) {
        log.debug { "nw.seam.close.requested self=${selfId.value} reason=$reason state=${_state.value}" }
        // Single-shot: if a self-driven Torn (last-peer drop) already fired, this no-ops.
        if (!latchTorn(reason)) return
        val targets = lock.withLock {
            val snapshot = registry.values.map { it.connId }
            registry.clear()
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

    internal companion object {
        /** Default grace given a path-lost (`ready → waiting`) connection to recover before the seam tears it (#1478). */
        val DEFAULT_WOVEN_PATH_GRACE: Duration = 10.seconds

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
