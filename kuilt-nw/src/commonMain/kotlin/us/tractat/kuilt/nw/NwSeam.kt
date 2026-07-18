package us.tractat.kuilt.nw

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
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
 *     connection is the remote's [NwHello] (id + nonce); every later frame is data, delivered to
 *     [incoming] stamped with that sender.
 *  3. **connectionClosed** — evicts the peer (conn-identity guarded so a deduped loser's close
 *     can't evict the survivor) and, when the last remote drops, **re-forms to [SeamState.Weaving]
 *     rather than latching [SeamState.Torn]** (#1513) — peer loss is recoverable, not terminal.
 *  4. **connectionViability** — the #1478 path-loss timer, reconciled from drop-tolerant STATE (#1509).
 *     A Network.framework connection that loses its route goes `ready → waiting` (NOT `failed`), firing NO
 *     [NwApi.connectionClosed], so a dead peer would otherwise linger in [peers] forever.
 *     [NwApi.connectionViability] exposes each connection's LATEST viability as state (not a lossy event
 *     stream); [reconcileViability] arms a per-connection grace timer ([wovenPathGrace]) for a connection
 *     whose latest value is `false` and cancels it when the latest is `true`. If the path does not recover
 *     before the timer expires, the connection is evicted (last-remote ⇒ re-form to [SeamState.Weaving],
 *     #1513 — NOT [SeamState.Torn]). Reconciling the latest value (rather than reacting to transitions) means
 *     a dropped/coalesced signal can never strand an armed timer (a spurious tear) or miss a loss (a
 *     zombie peer). The timer lives HERE, not in [NwApi], because only the seam owns an injectable [scope]
 *     (the test dispatcher under `runTest`) — `RealNwApi` runs on a GCD queue with no injectable clock.
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
 * The [registry] and [conns] maps are shared across the three collectors (each `collect` is
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
 * @param scope  coroutine scope hosting the three collectors; cancelled on teardown.
 * @param random source of per-connection dedup nonces; production defaults to [Random.Default], tests
 *   inject a seeded [Random] so the dedup tiebreak is deterministic.
 * @param policy delivery policy for the inbound [Spool] (default [DeliveryPolicy.Reliable]).
 * @param wovenPathGrace how long a path-lost (`ready → waiting`) connection is given to recover before
 *   the seam evicts it — re-forming to [SeamState.Weaving] if it was the last remote (#1478/#1513).
 *   Production default [DEFAULT_WOVEN_PATH_GRACE] (10s); tests inject a small value. Injected via
 *   [scope]'s (test) dispatcher, so it advances under virtual time.
 */
internal class NwSeam(
    override val selfId: PeerId,
    private val api: NwApi,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val wovenPathGrace: Duration = DEFAULT_WOVEN_PATH_GRACE,
) : Seam {

    /**
     * Per-connection mutable state. All fields read/written only under [lock]; [framer] is driven
     * only by the single bytes loop. [nonce] is minted once at creation and never mutated — it is
     * this connection's contribution to the canonical dedup nonce.
     */
    private class ConnState(val nonce: ByteArray) {
        val framer: NwFramer = NwFramer()
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
     * Armed by [reconcileViability] when a connection's latest viability is `false`, cancelled when it
     * reconciles back to `true` (recovery) or on any close/eviction of the connection. Guarded by [lock]
     * like [conns]/[registry].
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

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // Single latch flag, read/written across every path (receive/close/send).
    private val closed = atomic(false)

    // Stamped onto every delivered Swatch; incremented from the single bytes loop but atomic for safety.
    private val seq = atomic(0L)

    private val closedMessage get() = "NwSeam for ${selfId.value} is closed"

    // UNDISPATCHED so all three collectors subscribe synchronously at construction — before any
    // connectionOpened/bytes/close event can be emitted (subscribe-before-trigger).
    private val openedJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionOpenedLoop() }
    private val bytesJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { bytesReceivedLoop() }
    private val closedJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionClosedLoop() }
    private val viabilityJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectionViabilityLoop() }

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
            // #1509 lost-wakeup guard: a `viable=false` observed for this connId BEFORE it entered `conns`
            // was arm-skipped, and viability is latest-value STATE that will not re-emit an unchanged value.
            // Now that `conns` has caught up, re-reconcile the LATEST map so a pending path loss is armed
            // (else the #1478 zombie returns: no `connectionClosed` ever fires for a path-lost connection).
            if (created) reconcileViability(api.connectionViability.value)
            log.debug { "nw.seam.opened connId=${connId.value} self=${selfId.value} → sending NwHello" }
            runCatchingCancellable { api.send(connId, encodeFrame(NwHello.encode(selfId, cs.nonce))) }
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
            // Same #1509 lost-wakeup guard as connectionOpenedLoop: if bytes are the first thing that puts
            // this connId into `conns`, re-reconcile the latest viability so a pending loss is not stranded.
            if (created) reconcileViability(api.connectionViability.value)
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
            ConnState(random.nextBytes(NONCE_BYTES)).also { conns[connId] = it } to true
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
            else -> ConnState(random.nextBytes(NONCE_BYTES)).also { conns[connId] = it } to true
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
            // Data frame — deliver OUTSIDE the lock (Spool.deliver suspends for backpressure).
            is FrameOutcome.Data ->
                spool.deliver(Swatch(payload = frame, sender = outcome.sender, sequence = seq.incrementAndGet()))
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
        // Self-connection guard (#1466). In the election mesh both peers advertise the SAME
        // `Rendezvous.New` service name, so a peer's own advertisement appears in its browse results and
        // `NwLoom` dials it — a connection whose remote resolves to `selfId`. It must NEVER be registered:
        // registering self puts `selfId` in `registry`, and when that connection later fails,
        // `connectionClosedLoop` evicts its peer — self — dropping this peer from its own roster
        // (`peers → {theOtherPeer}`, `state` stays `Woven`, no `Torn`), which silently wedges every
        // consumer keying on `peers`/`host`/`Torn`. Leave `resolvedPeerId` null, drop the ConnState, and
        // return the connId so the caller disconnects the self-link (its later close is then a no-op).
        if (remoteId == selfId) {
            // Remember this endpoint resolved to self so NwLoom stops redialing it (#1513); the self-dial
            // via Rendezvous.New is otherwise indistinguishable from a real peer at the loom's name check.
            cs.endpoint?.let { selfEndpointIds += it.id; refreshSettledLocked() }
            conns.remove(connId)
            tombstoneLocked(connId) // #1528: a late frame on the dropped self-conn must not resurrect it
            log.info { "nw.seam.self-connection connId=${connId.value} self=${selfId.value} → dropped (dialed own endpoint)" }
            return connId
        }
        cs.resolvedPeerId = remoteId
        // Learn this peer's endpoint from ANY connection that carried one (winner OR dedup-loser), so the
        // peer's endpoint is known even when the surviving link is inbound (endpoint == null) — #1513.
        cs.endpoint?.let { peerEndpoint[remoteId] = it.id }
        val canonical = canonicalLinkNonce(cs.nonce, remoteNonce)
        val existing = registry[remoteId]
        if (existing == null) {
            registry[remoteId] = Winner(connId, canonical)
            addRemotePeer(remoteId) // refreshes settledEndpoints
            log.debug { "nw.seam.resolved.first connId=${connId.value} remote=${remoteId.value} self=${selfId.value} nonce=$canonical" }
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
     */
    private fun refreshSettledLocked() {
        _settledEndpoints.value = buildSet {
            addAll(selfEndpointIds)
            for (peer in registry.keys) peerEndpoint[peer]?.let { add(it) }
        }
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

    // ── loop 4: connectionViability — the #1478 path-loss grace timer ────────────

    /**
     * A path-lost (`ready → waiting`) connection fires NO [NwApi.connectionClosed] (#1478). The transport
     * exposes viability as **drop-tolerant per-connection latest-value STATE** ([NwApi.connectionViability]),
     * not a lossy event stream (#1509): each emission is a snapshot of every live connection's latest
     * viability, so we [reconcileViability] rather than react to individual transitions. Because the LATEST
     * value per connection is never lost (only intermediate transitions may coalesce), a dropped recovery
     * can never strand an armed timer (spurious tear) and a dropped loss can never leave a zombie peer.
     */
    private suspend fun connectionViabilityLoop() {
        api.connectionViability.collect { state ->
            if (closed.value) return@collect
            reconcileViability(state)
        }
    }

    /**
     * Reconcile the transport's per-connection latest viability [state] against the armed grace timers
     * ([graceJobs]) — the drop-tolerant #1509 replacement for reacting to viability *events*. On every
     * emission we re-derive the armed set from the LATEST value per connection, so a coalesced/lost
     * intermediate transition can never strand a timer or miss a loss. For each reported connection:
     *  - latest `true` (path up / recovered) → cancel any armed grace timer (a no-op if none);
     *  - latest `false` (path lost) on a still-tracked connection → arm a grace timer if not already armed.
     * Idempotent: an already-armed loss or an already-clear recovery is a steady-state no-op. Arm/cancel
     * decisions are taken under [lock] (so the [graceJobs] mutation is atomic); the [Job] `start`/`cancel`
     * side effects run OUTSIDE the lock, matching the seam-wide "no non-trivial call under the lock" rule.
     * Safe to call concurrently from the viability collector AND the [conns]-insertion sites (#1509
     * lost-wakeup guard): the check-then-arm is one lock acquisition, and `start()` on a lazily-armed job
     * a concurrent reconcile already cancelled is a harmless no-op.
     *
     * ## Accepted conflation trade-off
     * Because this is conflated latest-value state, a `false → true → false` burst that all lands while the
     * collector is starved (on the order of the grace duration) delivers only the final `false`: the
     * intermediate `true` is conflated away, so the second loss does NOT restart the grace clock — it
     * inherits the first timer's remaining time. This can only ever UNDER-grant grace to a path that is
     * currently down (tearing a dead-then-recovered-then-dead-again peer slightly sooner); it can never
     * strand a timer on, or tear, a path whose latest value is `true`. Inherent to conflated state and
     * acceptable — noted here so it isn't rediscovered as a "bug".
     */
    private fun reconcileViability(state: Map<NwConnectionId, Boolean>) {
        val toCancel = mutableListOf<Pair<NwConnectionId, Job>>()
        val armed = mutableListOf<Pair<NwConnectionId, Job>>()
        val armSkipped = mutableListOf<NwConnectionId>()
        lock.withLock {
            for ((connId, viable) in state) {
                if (viable) {
                    graceJobs.remove(connId)?.let { toCancel += connId to it }
                } else {
                    when {
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

    override suspend fun broadcast(payload: ByteArray) {
        check(_state.value !is SeamState.Torn) { closedMessage }
        val targets = lock.withLock { registry.values.map { it.connId } }
        val frame = encodeFrame(payload)
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
        runCatchingCancellable { api.send(connId, encodeFrame(payload)) }
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
         * Upper bound on the [tombstones] FIFO of recently-removed connIds (#1528). A late/buffered frame
         * races its connection's eviction by at most a handful of milliseconds, so retaining the last
         * [TOMBSTONE_CAP] removed connIds is far more than enough to catch every in-flight straggler while
         * keeping the set from growing without bound on a long-lived, churny seam.
         */
        const val TOMBSTONE_CAP: Int = 1024
    }
}
