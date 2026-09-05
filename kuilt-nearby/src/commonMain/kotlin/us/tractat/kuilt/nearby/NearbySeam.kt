package us.tractat.kuilt.nearby

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock as withReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerIdentityRegistry
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole

private val log = KotlinLogging.logger("us.tractat.kuilt.nearby.NearbySeam")

/**
 * [Seam] implementation backed by a Nearby Connections link.
 *
 * Receives data via [NearbyApi.payloadReceived], reassembles chunks with
 * per-endpoint [ChunkCodec.Reassembler]s, stamps each complete [Swatch] with
 * the remote's stable [PeerId] (exchanged during the connect handshake) and a
 * per-endpoint monotonic sequence number, then pushes it to [incoming].
 *
 * [broadcast] and [sendTo] chunk-encode the payload and call
 * [NearbyApi.sendBytesPayload] for each chunk.
 *
 * @param selfId              This peer's stable identity.
 * @param endpointPeers       Mutable map from Nearby endpointId → remote [PeerId],
 *                            pre-populated with the just-connected peer after handshake.
 *                            Shared with the owning [NearbyLoom] so later joiners update it.
 * @param endpointPeersMutex  The single [Mutex] that guards [endpointPeers]. Created once
 *                            by [NearbyLoom] and passed here so both sides serialise every
 *                            read and write on the same lock instance.
 * @param registry            The weave's peer-identity authority (#1821), keyed by endpoint ID and
 *                            shared with the [ConnectStateMachine] that admitted each id. It is what
 *                            makes an eviction identity-scoped: [disconnectLoop] evicts a peer from
 *                            [weavePeers] only when the departing endpoint is the one that actually
 *                            holds that id, so a departure can never take a different endpoint's peer
 *                            with it. [endpointPeers] stays as the derived reassembly/send index and
 *                            is only ever written where the registry has already said yes.
 * @param api                 The [NearbyApi] instance.
 * @param weavePeers          The roster of **this weave** — a [MutableStateFlow] created per
 *                            `weave` by [NearbyLoom] and seeded with [selfId], written by the
 *                            handshake as remotes join and by [disconnectLoop] as they leave.
 *                            Scoped to one weave rather than to the loom (#1878): a loom-wide flow
 *                            is never pruned of a finished weave's ids — [close] cannot write it
 *                            (that write was the #1850 cross-peer edit) and [disconnectLoop], the
 *                            only eviction, is cancelled by the very tear that would need it — so
 *                            the residue seeded the *next* weave, whose fresh seam then reported a
 *                            phantom roster and false-latched [SeamState.Woven] with zero
 *                            connections. Per-weave also makes the #1850 hazard unrepresentable
 *                            rather than merely avoided: there is no sibling seam on the other end
 *                            of this flow to edit. [peers] remains a per-seam *mirror* of it, so
 *                            the tear-time collapse stays local (see [collapseRoster]).
 * @param scope               Coroutine scope for the receive loop; cancelled on [close].
 * @param maxChunkPayload     Per-chunk payload cap forwarded to [ChunkCodec].
 * @param msgIdCounter        Shared monotonic counter for message IDs (use one per seam).
 * @param policy              Delivery policy for the inbound [Spool]. Defaults to
 *                            [DeliveryPolicy.Reliable] (bounded, backpressured).
 * @param staticRoles         The roles this seam holds no matter what the radios are doing
 *                            ([NearbyLoom.NEARBY_BASE_ROLES]). The live MEDIUM roles are folded on
 *                            top per reading from [NearbyApi.radioState] — see [radioStateLoop].
 *                            Deliberately carries **no** availability: a role set answers a
 *                            platform-support question, and reusing it as a live verdict is the
 *                            #1712 defect.
 */
internal class NearbySeam(
    override val selfId: PeerId,
    private val endpointPeers: MutableMap<String, PeerId>,
    private val endpointPeersMutex: Mutex,
    private val registry: PeerIdentityRegistry<String>,
    private val api: NearbyApi,
    private val weavePeers: MutableStateFlow<Set<PeerId>>,
    private val scope: CoroutineScope,
    private val maxChunkPayload: Int = ChunkCodec.MAX_CHUNK_PAYLOAD,
    private val msgIdCounter: MsgIdCounter,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val staticRoles: Set<TransportRole> = NearbyLoom.NEARBY_BASE_ROLES,
) : Seam {

    // Guards the _peers write (roster mirror) against the tear-time collapse, with the collapse
    // marker folded into the same critical section so a post-collapse mirror emission cannot
    // resurrect the roster.
    private val peersLock = reentrantLock()

    // Set by [collapseRoster], read by the mirror — both under [peersLock], never apart. NOT a
    // second lifecycle latch ([closed] is still the single-shot tear gate). It exists because the
    // collapse must be published BEFORE `Torn` becomes observable ([Seam.peers]), so the mirror can
    // no longer key its guard on `state`: in the window between the collapse and the latch this seam
    // is not yet Torn, and a mirror emission landing there would republish the roster. Guarding on a
    // marker set in the same critical section as the collapse makes that window unrepresentable
    // rather than narrow — check-and-write are one atomic step.
    private var collapsed = false

    // This seam's OWN view of the weave roster. NOT [weavePeers] itself: the collapse must be a
    // local edit, so that publishing a tear can never rewrite a roster anyone else reads — the shape
    // of the #1850 bug, in which `close()` dropped `selfId` from the counterparty's view. Since
    // #1878 the flow is per-weave, so there is no sibling reader left to damage; the mirror stays
    // because the collapse is still this seam's own terminal state, not a fact about the weave.
    // Mirrored from [weavePeers] until the collapse, then frozen at { selfId }.
    //
    // `+ selfId` is unconditional because [Seam.peers] is: "always including this peer's own id".
    // [NearbyLoom] seeds [weavePeers] with this seam's id before construction, but a departure
    // handler can still drop an id from the flow, and no such write may be observable as a roster
    // this seam has evicted itself from.
    private val _peers = MutableStateFlow(weavePeers.value + selfId)
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Starts Weaving; transitions to Woven when the first remote peer joins.
    // ALLOW-bareSeamState: the promotion is an `update {}` CAS whose lambda re-reads `closed` as well
    // as the state (#1879), so a tear landing in the window fails the CAS and the retry drops the
    // promotion. The flow is itself the mutual exclusion; a gate's lock would be a second one.
    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    // Live capability (#1543): ROLES seeded from [staticRoles] and thereafter driven by the injected
    // radio observer ([NearbyApi.radioState]). The observer moves [TransportCapability.availability]
    // as Bluetooth/Wi-Fi are toggled or the Play services runtime goes away, AND folds the live media
    // into the ROLES — an on Bluetooth radio adds [TransportRole.Bluetooth], an on Wi-Fi radio adds
    // [TransportRole.WifiDirect], atop the fabric's base Data role. A MutableStateFlow so the write
    // from the single [radioStateLoop] collector is thread-safe (CAS) under any dispatcher.
    //
    // AVAILABILITY starts at [unobservedCapability]'s Unknown and is ONLY ever set from an observed
    // radio reading (#1712): nothing here can report a verdict the observer has not supplied.
    // [staticRoles] carries no availability to fall back on, by construction.
    private val _capability = MutableStateFlow(unobservedCapability)
    override val capability: StateFlow<TransportCapability> = _capability.asStateFlow()

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // A single atomic flag, read/written across the receive, disconnect, send and close paths.
    private val closed = atomic(false)

    // Per-endpoint reassemblers and sequence counters — keyed by endpointId.
    // Accessed only within endpointPeersMutex, so no separate guard needed.
    private val reassemblers = mutableMapOf<String, ChunkCodec.Reassembler>()
    private val sequences = mutableMapOf<String, Long>()

    // UNDISPATCHED so both loops subscribe to their event flows synchronously at
    // construction — before any handshake/data events can be emitted.
    private val receiveJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { receiveLoop() }
    private val disconnectJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { disconnectLoop() }

    // UNDISPATCHED for the same reason, plus one specific to a StateFlow: subscribing synchronously
    // means the observer's CURRENT value is folded in before construction returns, so a seam woven
    // onto an already-reporting binding never transiently publishes the unobserved floor.
    private val radioStateJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { radioStateLoop() }

    // Mirror the loom-wide roster into this seam's own [_peers], and transition Weaving → Woven when
    // the first remote peer appears. UNDISPATCHED so the mirror is subscribed (and StateFlow's
    // current value already applied) before construction returns.
    private val rosterWatcher: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        weavePeers.collect { session ->
            peersLock.withReentrantLock { if (!collapsed) _peers.value = session + selfId }
            promoteWovenIfAnyRemoteIn(session)
        }
    }

    /**
     * Move `Weaving → Woven` if [session] carries anybody but this peer. A no-op from any other
     * state, so it can never revive a seam.
     *
     * **`update`, NOT `if (_state.value is Weaving) _state.value = Woven` (#1879).** The promotion is
     * a read-modify-write, and spelled as a check-then-act it can lose a terminal write: [latchTorn]
     * publishes `Torn` with a plain set, and *cancelling the caller does not stop a promotion already
     * in flight* — cancellation is cooperative and there is no suspension point between the read and
     * the write, so a cancelled-but-not-yet-suspended pump still completes it. The `Woven` would then
     * stand forever over a seam whose spool is closed and whose roster has collapsed, with the
     * watcher gone and no later emission able to correct it, and every consumer waiting on
     * `state.first { it is Torn }` would hang.
     *
     * [MutableStateFlow] is itself the mutual exclusion here, so this needs no lock: `update` retries
     * via `compareAndSet`, so a tear landing inside the window fails the CAS, and the retry re-reads
     * `Torn` and drops the promotion on the `is Weaving` guard. The retry terminates because `Torn`
     * is terminal — nothing ever writes the state back to `Weaving`.
     *
     * **[closed] is read inside the lambda, not just [SeamState.Weaving].** The `is Weaving` guard
     * alone leaves a narrower but real window *inside* [latchTorn]: it CASes [closed] and then
     * *blocks* on [peersLock] in [collapseRoster] before publishing `Torn`, so a promotion running in
     * that gap sees a state that is still `Weaving` and publishes `Woven` while `closed == true`. The
     * terminal `Torn` still lands immediately after — the seam does not wedge — but a consumer woken
     * by `state.first { it is Woven }` would call [broadcast] on a closed seam and get the
     * [checkNotClosed] `IllegalStateException`. Keying on the latch as well as the value makes the
     * "never revive a seam" promise true of the *decision* to tear, not merely of the published
     * state, and costs one volatile read. `closed` only ever moves `false → true`, so re-reading it
     * on each CAS retry is monotonic.
     */
    private fun promoteWovenIfAnyRemoteIn(session: Set<PeerId>) {
        _state.update { current ->
            if (!closed.value && current is SeamState.Weaving && session.any { it != selfId }) {
                SeamState.Woven
            } else {
                current
            }
        }
    }

    /**
     * Record a remote the handshake has just admitted — the roster write and this seam's [peers]
     * mirror as **one** step.
     *
     * [rosterWatcher] would reach the same value on its own, but only after a dispatch, and
     * [NearbyLoom.joinSession] returns as soon as the handshake completes: `join` promises a seam
     * that already reflects the connection it just made, so the mirror cannot be left owing a turn.
     * Before #1878 that held by accident — the host wrote the loom-wide flow *before* the joiner's
     * seam was constructed, so the value was folded in by the constructor and no dispatch was
     * needed. A per-weave roster necessarily learns its remote after construction, which is what
     * turns an accident into an obligation worth naming.
     *
     * **The roster mirror write** — and only that write — is guarded by [peersLock] and [collapsed],
     * exactly as [rosterWatcher]'s is, so an admit racing a tear cannot resurrect a collapsed roster
     * (#1816). The other two steps are deliberately outside that critical section: the [weavePeers]
     * write is a separate flow with its own atomicity, and [promoteWovenIfAnyRemoteIn] carries its
     * own latch check, so holding [peersLock] across either would buy nothing and would widen a lock
     * across a `StateFlow` write that can resume consumer code inline.
     *
     * **Callers hold [endpointPeersMutex] across this, which is load-bearing.** [disconnectLoop]
     * makes its last-endpoint tear decision (`endpointPeers.isEmpty() && _state.value is Woven`)
     * under that same mutex, so the promotion published here cannot interleave with it: the
     * "the last endpoint leaves while a promotion is in flight, and the seam is left `Woven` with no
     * endpoints and no tear" ordering is unrepresentable rather than merely unlikely. That is a
     * property of *where this is called from*, so a future caller that admits outside the mutex
     * would give it up silently.
     */
    internal fun admitRemote(peer: PeerId) {
        weavePeers.update { it + peer }
        peersLock.withReentrantLock { if (!collapsed) _peers.update { it + peer } }
        promoteWovenIfAnyRemoteIn(setOf(peer))
    }

    // ── receive ───────────────────────────────────────────────────────────────

    private suspend fun receiveLoop() {
        api.payloadReceived.collect { event ->
            // Snapshot (swatch) under the lock, then deliver OUTSIDE it so that a
            // SUSPEND-policy backpressure stall never holds endpointPeersMutex.
            val frame = endpointPeersMutex.withLock {
                if (closed.value) return@collect
                // Ignore payloads from unknown endpoints (e.g. not yet connected).
                val remotePeerId = endpointPeers[event.endpointId] ?: return@collect
                assembleFrame(event.endpointId, event.bytes, remotePeerId)
            } ?: return@collect
            spool.deliver(frame)
        }
    }

    /**
     * Reassemble a chunk and build the [Swatch] — called under [endpointPeersMutex].
     * Returns `null` if the chunk is incomplete or malformed.
     */
    private fun assembleFrame(endpointId: String, bytes: ByteArray, remotePeerId: PeerId): Swatch? {
        val chunk = ChunkCodec.decodeChunk(bytes) ?: return null
        val reassembler = reassemblers.getOrPut(endpointId) { ChunkCodec.Reassembler() }
        val payload = reassembler.feed(chunk) ?: return null
        val seq = nextSequence(endpointId)
        return Swatch(payload = payload, sender = remotePeerId, sequence = seq)
    }

    private fun nextSequence(endpointId: String): Long {
        val current = sequences.getOrElse(endpointId) { 0L }
        val next = current + 1
        sequences[endpointId] = next
        return next
    }

    // ── disconnect detection ──────────────────────────────────────────────────

    private suspend fun disconnectLoop() {
        api.endpointDisconnected.collect { event ->
            endpointPeersMutex.withLock {
                if (closed.value) return@collect
                val peerId = endpointPeers.remove(event.endpointId) ?: return@collect
                reassemblers.remove(event.endpointId)?.reset()
                sequences.remove(event.endpointId)
                // Identity-scoped: the loom-wide eviction happens only if THIS endpoint is the one
                // holding that id (#1821). `endpointPeers` and the registry agree today — nothing
                // writes the map without a successful bind — so this gates rather than changes the
                // eviction; what it removes is the possibility of them disagreeing later, when a
                // second endpoint on this weave holds an id the departing one merely announced.
                if (registry.unbind(peerId, event.endpointId)) {
                    weavePeers.update { it - peerId }
                }
                // Honour the Seam contract: `incoming` completes on a remote disconnect too.
                // The session is genuinely over only when a peer that had CONNECTED (state Woven)
                // just lost its LAST endpoint — not on a partial drop, and not while still Weaving
                // (never-connected; that terminates via close()). Latch Torn exactly once.
                if (endpointPeers.isEmpty() && _state.value is SeamState.Woven) {
                    latchTorn(CloseReason.RemoteRequested)
                }
            }
        }
    }

    // ── live capability — the #1543 reactive-capability driver ────────────────

    /**
     * Fold the transport's live [NearbyApi.radioState] (the Bluetooth/Wi-Fi state broadcasts on
     * `GmsNearbyApi`) into [capability].
     *
     * A `null` radio state means "unknown" — the binding has wired no observer (or the default
     * fake) — so we publish [unobservedCapability]: the fabric's base role with an honest
     * [FabricAvailability.Unknown], never a guessed verdict (#1712). A non-null state supplies the
     * availability via [NearbyRadioState.toAvailability] AND drives the ROLES: the base role
     * ([staticRoles] = [NearbyLoom.NEARBY_BASE_ROLES] = Data) plus whichever media are actually
     * powered ([NearbyRadioState.radioRoles]). A device with both radios off adds no medium role, so
     * the roles narrow back to the base set. The write goes to the seam-owned [_capability]
     * MutableStateFlow, so this single collector is the sole writer — no lock needed. Terminates
     * with [scope] on close (this loop holds no per-endpoint state).
     */
    private suspend fun radioStateLoop() {
        api.radioState.collect { radios ->
            _capability.value =
                if (radios == null) {
                    unobservedCapability
                } else {
                    TransportCapability(
                        roles = staticRoles + radios.radioRoles(),
                        availability = radios.toAvailability(),
                    )
                }
        }
    }

    /**
     * The capability of a seam with **no live radio reading**: the fabric's base role, but an honest
     * [FabricAvailability.Unknown] availability.
     *
     * A `null` [NearbyApi.radioState] means the binding wired no observer, or none has reported yet.
     * Either way this seam does not know whether its radios are up and must say so. It cannot fall
     * back on [NearbyApi.availability] even by accident: [staticRoles] carries roles only, because
     * that value answers a *platform-support* question ("is there a Nearby runtime here") and
     * reusing it as a live verdict was the #1712 defect. Note the file already answers the
     * equivalent question this way one level down: [NearbyRadioState.toAvailability] maps an
     * unreadable radio to `Unknown`, and a `null` radio state is strictly less informative than
     * that.
     */
    private val unobservedCapability: TransportCapability
        get() = TransportCapability(
            roles = staticRoles,
            availability = FabricAvailability.Unknown("no radio observer has reported on this binding"),
        )

    // ── send ──────────────────────────────────────────────────────────────────

    override suspend fun broadcast(payload: ByteArray) {
        checkNotClosed()
        val endpoints = endpointPeersMutex.withLock { endpointPeers.keys.toList() }
        if (endpoints.isEmpty()) {
            log.warn { "nearby.send dropped — no connected peers selfId=${selfId.value} bytes=${payload.size}" }
            return
        }
        sendToEndpoints(endpoints, payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkNotClosed()
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        val endpointId = endpointPeersMutex.withLock { endpointIdFor(peer) }
            ?: throw PeerNotConnected(peer)
        sendToEndpoints(listOf(endpointId), payload)
    }

    private suspend fun sendToEndpoints(endpoints: List<String>, payload: ByteArray) {
        val msgId = msgIdCounter.next()
        val chunks = ChunkCodec.encode(payload, msgId, maxChunkPayload)
        for (endpointId in endpoints) {
            for (chunk in chunks) {
                api.sendBytesPayload(endpointId, chunk)
            }
        }
    }

    private fun endpointIdFor(peer: PeerId): String? =
        endpointPeers.entries.firstOrNull { it.value == peer }?.key

    // ── close ─────────────────────────────────────────────────────────────────

    override suspend fun close(reason: CloseReason) {
        // Single-shot: if a self-driven Torn (last-peer disconnect) already fired, this no-ops.
        // The roster collapse rides inside latchTorn, ahead of the `Torn` write — see there.
        if (!latchTorn(reason)) return
        // Local close additionally tears the wire down; a remote-driven latch skips this (its
        // endpoints are already gone). Dropping this peer from [weavePeers] is deliberately NOT
        // done, and stays that way now the flow is per-weave (#1878): the counterparty learns of
        // this departure from its own transport — its `endpointDisconnected` fires from the
        // disconnects below and its own [disconnectLoop] evicts us — never from a peer reaching
        // across and editing its membership (#1850). What per-weave changes is that the reaching
        // across is no longer *possible*; what it does not change is that this seam's terminal
        // roster is [collapseRoster]'s local business.
        val endpoints = endpointPeersMutex.withLock { endpointPeers.keys.toList() }
        for (endpointId in endpoints) {
            api.disconnect(endpointId)
        }
    }

    /**
     * Collapse [peers] to `{ selfId }` and shut the roster mirror out of it, as one critical section.
     *
     * [Seam.peers] requires a `Torn` seam's roster to be exactly `{ selfId }` — **not** `emptySet()`
     * and not the pre-tear membership: a torn radio reaches nobody, but `peers` always carries this
     * peer's own id.
     *
     * Called from [latchTorn] and nowhere else, *after* its CAS — so it runs exactly once per seam.
     * A second (or concurrent) [close] loses that CAS and returns before reaching here.
     */
    private fun collapseRoster() = peersLock.withReentrantLock {
        collapsed = true
        _peers.value = setOf(selfId)
    }

    /**
     * Terminal teardown, latched exactly once via [closed]. Collapses the roster, publishes
     * [SeamState.Torn], completes [incoming] by closing the [spool], and cancels the whole [scope] —
     * which stops the receive/disconnect loops AND the background accept coroutine
     * [NearbyLoom.openSession] launches into the same scope, preventing coroutine leaks. Returns
     * `false` if teardown already ran.
     *
     * Called from both [close] (local) and [disconnectLoop] (last remote endpoint gone). The collapse
     * lives *here* rather than at each call site precisely because both paths publish the same
     * terminal `Torn` a consumer waits on, and [Seam.peers] makes the collapse ORDERED against it:
     * running it immediately before the `_state` write is what makes "already collapsed when `Torn`
     * becomes observable" true on either path, with no way to add a third that forgets.
     *
     * [collapseRoster] releases [peersLock] before returning, so *this* frame does not hold it across
     * the `_state` write — that write resumes `state` collectors, which can run consumer code inline
     * and re-enter this seam. Not an absolute invariant about the thread: an inline collector resumed
     * by the roster write could itself be inside [peersLock] further up the stack. That is safe rather
     * than accidental — the lock is reentrant, and the CAS above already makes a re-entrant tear a
     * no-op — but the guarantee is "no lock held by this frame", not "no lock held at all".
     */
    private fun latchTorn(reason: CloseReason): Boolean {
        if (!closed.compareAndSet(expect = false, update = true)) return false
        collapseRoster()
        _state.value = SeamState.Torn(reason)
        scope.coroutineContext[Job]?.cancel()
        spool.close()
        return true
    }

    private fun checkNotClosed() {
        check(!closed.value) { "NearbySeam for $selfId is closed" }
    }
}

/** Monotonically increasing message ID counter. Not thread-safe; callers must synchronise. */
internal class MsgIdCounter {
    private var value = 0
    fun next(): Int = ++value
}
