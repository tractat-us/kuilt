package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Outbound delivery handle for one registered connection in a room — the server's way to send
 * a frame to exactly one admitted spoke. Identity-comparable so a reconnect's deregister can
 * tell a stale connection's handle apart from a fresh one for the same [PeerId].
 */
internal fun interface OutboundSender {
    /** Send [payload] to this connection on the room's channel. Best-effort. */
    suspend fun send(payload: ByteArray)
}

/**
 * Server-side hub [Seam] for one named room — the structural per-room isolation primitive.
 *
 * A [RoomHubSeam] is the server's view of a single room. A frame broadcast here reaches
 * **only** the connections that have been admitted to this room; a non-member is never in
 * the fanout list, so a cross-room leak is structurally unrepresentable — isolation by
 * construction, not by guard.
 *
 * ## Deterministic delivery
 *
 * Frames are **pushed** into the room by [MuxServerLoom], which performs a *single* collection
 * of each connection's underlying seam and demultiplexes by channel name inline. A room never
 * collects a per-channel flow itself — so registration and forwarding do not depend on the
 * replay-0 subscription timing of a [NamedMux] channel view. Inbound frames land in a bounded
 * [Spool] (a buffered channel), so a frame delivered before the room's consumer subscribes is
 * retained rather than dropped. This is what makes the path deterministic under virtual time.
 *
 * ## Membership / registration
 *
 * A connection joins a room via two gates, both applied by [deliver] on the first frame:
 * 1. **Authorization** — [authorizer] is invoked with the peer's id and this room's
 *    [channelName]. A `false` return structurally excludes the connection: it is never added
 *    to [peers], the fanout, or the inbound stream.
 * 2. **First-frame admission** — only if the authorizer returns `true` is the connection
 *    registered. All subsequent frames from that connection on this channel are then
 *    forwarded to [incoming] and the connection appears in [peers].
 *
 * A connection is deregistered via [deregister] when its underlying link tears.
 *
 * ## Reconnect / resume
 *
 * Registration is keyed by [PeerId]. A returning peer (same id, fresh connection) replaces the
 * stale entry; the stale connection's later [deregister] is a no-op because its [OutboundSender]
 * is no longer the registered one — the resumed membership survives the old connection's teardown.
 *
 * The one replacement that is **refused** is an unattested link claiming an id whose live link the
 * host verified (#2357) — a peer id is self-asserted and public, so without that guard anyone could
 * take both the roster entry and the unicast route of a verified peer, presenting no credential.
 * See [principals]. Attested → attested replacement is unaffected, and so is a deployment that
 * attests nothing at all.
 *
 * ## Thread safety
 *
 * All mutable state ([registered] and the [attestedPrincipals] roster) is guarded by a reentrant
 * lock. Suspend calls (authorizer, sends, spool delivery) are always performed **outside** the lock.
 * The terminal lifecycle runs through a [SeamStateGate]: [close] latches `Torn` single-shot (no more
 * non-CAS `if (_state is Torn) return`), and the roster-resurrection hazard — an in-flight [deliver]
 * re-registering a peer after `close()` collapsed the roster — is closed by folding the [closed] check
 * into the **same** critical section that mutates [registered]/[_peers]/[principals], so a post-close
 * [deliver] can never republish membership. The check is that marker and not a read of `state`
 * because [Seam.peers] requires the roster collapse to be published **before** the `Torn` latch, so
 * mid-close there is an instant at which the roster is collapsed and `state` is not yet terminal.
 *
 * @param channelName the room name, matching the [NamedMux] channel tag clients use.
 * @param selfId this server peer's own [PeerId].
 * @param authorizer required authorization policy — invoked on first frame from each
 *   connection. Use [RoomAuthorizer.AllowAll] for open-access rooms and in tests.
 */
public class RoomHubSeam(
    internal val channelName: String,
    override val selfId: PeerId,
    private val authorizer: RoomAuthorizer,
) : Seam, PrincipalRoster {

    private val lock = reentrantLock()

    /** Registered (authorized) connections: peerId → outbound sender for that connection. */
    private val registered = mutableMapOf<PeerId, OutboundSender>()

    /**
     * Host-verified principals of currently-registered peers ([PrincipalRoster]). Maintained under
     * [lock] in the SAME critical sections that mutate [registered]/[_peers], so its key set can
     * never desync from the live membership: an entry is added only alongside a registration and
     * removed only alongside a deregistration or [close].
     *
     * A peer that has **never** been admitted with an attestation is absent from the map — never a
     * `null`-valued entry.
     *
     * **Presence here is what makes an id defended (#2357).** An entry means the host verified this
     * peer on a link that is still live, and [deliver] will refuse to let a link it verified as
     * nothing take that id over — so this map and [registered] cannot come to describe different
     * connections for one peer. It follows that the two agree on more than their key sets: whatever
     * [attestedPrincipals] reports for a peer is a verification of the connection [sendTo] reaches.
     *
     * Its keys are therefore always a **subset** of [registered]'s, maintained under [lock] in the
     * same critical sections, and an entry outlives only the connection it describes.
     */
    private val principals = mutableMapOf<PeerId, Principal>()

    private val _attestedPrincipals = MutableStateFlow<Map<PeerId, Principal>>(emptyMap())
    override val attestedPrincipals: StateFlow<Map<PeerId, Principal>> = _attestedPrincipals.asStateFlow()

    // The hub is a peer in its own room roster: [Seam.peers] must include [selfId] (contract in
    // Seam.kt). Initial value is `{ selfId }`; admission adds spokes, deregister removes only the
    // departing spoke, so selfId stays present on any live (non-Torn) roster. #1506.
    private val _peers = MutableStateFlow<Set<PeerId>>(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Set by [close], read by [deliver]'s registration block — both under [lock], never apart. NOT a
    // second lifecycle latch ([stateGate] is still the single-shot close gate): it exists because the
    // roster collapse must be published BEFORE `Torn` becomes observable ([Seam.peers]), which leaves
    // a window in which the hub is collapsed but not yet Torn. A registration landing there would
    // resurrect membership on a closed hub, so the guard is this marker rather than a read of
    // `state`. Check and write share one critical section — not the check-then-set [SeamStateGate]
    // bans, whose whole problem is that the two steps are apart.
    private var closed = false

    private val stateGate = SeamStateGate(SeamState.Woven)
    override val state: StateFlow<SeamState> = stateGate.state

    /**
     * Merged inbound stream: frames from all registered connections, pushed here by the loom.
     * A bounded buffered [Spool] — a frame pushed before the room's single consumer subscribes
     * is buffered, not dropped, which is what makes registration+delivery deterministic.
     */
    private val inboundSpool = Spool<Swatch>(DeliveryPolicy.Reliable)
    override val incoming: Flow<Swatch> = inboundSpool.incoming

    /**
     * Deliver one inbound [frame] from connection [connPeerId] into this room.
     *
     * On the first frame from a not-yet-registered connection, [authorizer] is consulted; a
     * rejection structurally excludes the connection (it is never registered, never appears in
     * [peers], and its frame is dropped). On admission (or for an already-registered connection)
     * the frame is forwarded to [incoming]. [sender] is the outbound handle stored for this
     * connection so the room can fan broadcasts back to it.
     *
     * [principal] is the host-verified identity for this connection (the mesh admitted the link
     * before the room ever sees a frame). It is recorded in [attestedPrincipals] at registration; a
     * `null` principal leaves a first-time peer unattested (absent from the roster).
     *
     * A reconnect (same [connPeerId], fresh [sender]) re-registers and an attested one refreshes the
     * entry — **unless** the id is already attested and this link is not, which is refused outright
     * and structurally, exactly as an authorizer rejection is (#2357; see [principals] and the guard
     * in the registration block). A deployment that attests nothing never meets that guard.
     *
     * Suspends only outside the lock (authorizer + spool delivery). Thread-safe.
     */
    internal suspend fun deliver(
        connPeerId: PeerId,
        frame: Swatch,
        sender: OutboundSender,
        principal: Principal?,
    ) {
        if (state.value is SeamState.Torn) return
        val alreadyRegistered = lock.withLock { registered[connPeerId] === sender }
        if (!alreadyRegistered) {
            if (!authorizer.authorize(connPeerId, channelName)) return
            val admitted = lock.withLock {
                // Re-check INSIDE the registration critical section: a close() may have collapsed the
                // roster while we were suspended in the authorizer above. Registering here would
                // resurrect membership after the collapse (#1364). Folding the check into the same
                // lock that clears the roster makes it unrepresentable.
                //
                // The check is [closed], not `state`: the collapse is published BEFORE the `Torn`
                // latch ([Seam.peers]'s ordering obligation), so between the two this hub is closed
                // while `state` still reads non-terminal. Reading `state` here would admit exactly
                // the registration this guard exists to reject.
                if (closed) return@withLock false
                // THE DISPOSSESSION GUARD (#2357). A link the host verified as NOTHING may not take
                // over a peer id the host has already verified on a link that is still live.
                //
                // Everything an attacker needs is the peer id, and that is not a secret — it is the
                // self-asserted preamble field every peer broadcasts. Dial a second connection
                // alongside the victim's (hers is never torn), announce her id, send one frame, and
                // before this guard you took both halves of her identity: `principals` dropped her
                // entry and `registered` became your sender, so `sendTo(her, …)` delivered to you.
                // `authorizer` cannot stop it — [RoomAuthorizer.authorize] never receives a
                // [Principal], so the policy is not merely unimplemented but unexpressible.
                //
                // It has to be BOTH halves. Keeping the roster entry while still handing over
                // `registered` is worse than either: the room would report `alice → verified-alice`
                // while her bytes went elsewhere, so a consumer that gates a send on the roster —
                // the security-conscious pattern — flips from accidentally fail-CLOSED to fail-OPEN.
                //
                // Keyed on [principals] alone: `principals`' keys are a subset of `registered`'s by
                // construction (both are mutated only here, in [deregister], and in [close], and an
                // entry is only ever added here alongside a registration), so an entry here IS a live
                // attested link. A peer whose link dropped is out of both maps and rejoins freely,
                // attested or not.
                //
                // Refusal is structural and silent — not registered, not in [_peers], frame dropped —
                // byte-identical in shape to an authorizer denial. Silent because kuilt-core is
                // logger-free by contract, and because the only caller of [deliver] is a
                // per-connection read loop that a throw would tear down. The cost is that a
                // deployment cannot see it is under attack; the nearest signal is that its
                // [RoomAuthorizer] was invoked for a frame that never registered.
                if (principal == null && connPeerId in principals) return@withLock false
                registered[connPeerId] = sender
                _peers.update { it + connPeerId }
                // With the guard above, a `null` principal here means this id has no attestation to
                // erase, so the old `principals.remove(connPeerId)` could only ever be a no-op —
                // writing it would suggest a forgetting that can no longer happen. Attested →
                // attested supersession is deliberately untouched (and still mandated by
                // `PrincipalAttestationConformanceSuite.rosterUpdatesPrincipalOnReconnect`).
                //
                // The republication stays UNCONDITIONAL: `deliver`'s contract is that the roster has
                // decided about this peer by the time it returns, and a harness may be awaiting the
                // emission itself rather than a change in the value.
                if (principal != null) principals[connPeerId] = principal
                _attestedPrincipals.value = principals.toMap()
                true
            }
            if (!admitted) return
        }
        inboundSpool.deliver(frame)
    }

    /**
     * Deregister [connPeerId] when its underlying link tears — but only if [sender] is still the
     * registered handle. A reconnect (same id, fresh connection) may have already replaced the
     * entry; the stale connection's teardown must not evict the live re-registered membership.
     *
     * Thread-safe.
     */
    internal fun deregister(connPeerId: PeerId, sender: OutboundSender) {
        lock.withLock {
            if (registered[connPeerId] === sender) {
                registered.remove(connPeerId)
                _peers.update { it - connPeerId }
                if (principals.remove(connPeerId) != null) _attestedPrincipals.value = principals.toMap()
            }
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        checkOpen()
        val targets = lock.withLock { registered.values.toList() }
        targets.forEach { sender ->
            runCatchingCancellable { sender.send(payload) }
                .onFailure { /* best-effort: torn spoke — ignore */ }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkOpen()
        val target = lock.withLock { registered[peer] }
            ?: throw PeerNotConnected(peer)
        runCatchingCancellable { target.send(payload) }
            .onFailure { /* best-effort: torn spoke */ }
    }

    override suspend fun close(reason: CloseReason) {
        // Collapse the roster to { selfId } BEFORE latching Torn. [Seam.peers] makes that an ORDERED
        // obligation — a consumer woken by the terminal state must already observe the collapse — and
        // it is `{ selfId }`, not `emptySet()`: the hub is a peer in its own room roster, so an empty
        // set overshoots the contract in the other direction (see the [_peers] note above).
        //
        // The [closed] marker set in the SAME critical section is what replaces the old latch-first
        // order. That order existed so a [deliver] acquiring the lock after the clear would read
        // `Torn` and decline; with the collapse now ahead of the latch there is a window in which the
        // hub is collapsed but not yet Torn, so the guard can no longer be a read of `state`. Marking
        // and clearing atomically closes that window instead of narrowing it — and it is strictly
        // stronger than the state read, since it cannot be observed half-applied.
        //
        // [lock] is deliberately NOT held across `tear`: that write resumes `state` collectors, which
        // can run consumer code inline and re-enter this hub, inverting the lock order against
        // [deliver]. Clearing before the (still single-shot) latch is safe for a losing caller too —
        // everything in the block is idempotent.
        lock.withLock {
            closed = true
            registered.clear()
            principals.clear()
            _peers.value = setOf(selfId)
            _attestedPrincipals.value = emptyMap()
        }
        if (!stateGate.tear(reason)) return
        inboundSpool.close()
    }

    private fun checkOpen() {
        check(state.value !is SeamState.Torn) { "RoomHubSeam for '$channelName' is closed" }
    }
}
