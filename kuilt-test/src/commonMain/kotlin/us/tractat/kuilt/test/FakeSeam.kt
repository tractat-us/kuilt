package us.tractat.kuilt.test

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch

/**
 * A test double for [Seam] with test-driver helpers for state mutation, frame
 * delivery, and outgoing-frame inspection.
 *
 * Defaults make `FakeSeam()` a ready-to-use, single-peer, [SeamState.Woven] seam
 * in one line:
 *
 * ```kotlin
 * val seam = FakeSeam()
 * seam.deliver(PeerId("alice"), byteArrayOf(1, 2, 3))
 * val frame = seam.incoming.first()
 * ```
 *
 * For wired two-peer scenarios, prefer [fakeSeamPair] which cross-wires broadcast
 * delivery between two seams.
 *
 * **Send semantics** (matching the [Seam] contract):
 * - [broadcast] while [SeamState.Weaving] or [SeamState.Woven] with no other peers:
 *   no-op, but the payload is still recorded in [broadcasts].
 * - [sendTo] to a peer not in [peers]: throws [PeerNotConnected].
 * - Either send while [SeamState.Torn]: throws [IllegalStateException].
 *
 * The inbound buffer is bounded via [policy] (default [DeliveryPolicy.Reliable]).
 * Unbounded delivery is structurally unrepresentable — pass a custom policy to
 * change capacity or overflow behaviour.
 *
 * **`initialPeers` must contain [selfId]** — in every state, as [Seam.peers] requires from
 * construction onward. `peers.value.size > 1` is the contract's sentinel for "at least one remote is
 * connected", so a roster without [selfId] under-counts by one and a seam holding one remote reads as
 * alone. Passing one throws [IllegalArgumentException].
 *
 * **That obligation is an invariant, not a construction-time snapshot** (#2546). Two doors used to
 * reach the identical state the `require` refuses: [removePeer] applied to [selfId] now throws rather
 * than holing the roster, and `initialPeers` is **copied** rather than retained, so a caller holding a
 * `MutableSet` reference cannot edit the seam's roster out from under it after construction.
 *
 * **Constructing one already `Torn` is allowed, but only in the shape a real seam reaches**:
 * `initialPeers` must be `setOf(selfId)` (the default), because [Seam.peers] requires a torn seam's
 * roster to be exactly that. Anything else throws [IllegalArgumentException] — see the `init` block.
 * A seam constructed `Torn` also starts with [incoming] already completed, as [tear] leaves it.
 *
 * **`Torn` is terminal here too**: [weave] refuses once torn rather than silently un-tearing, so a
 * test cannot drive this fake through a transition no real seam performs. See [weave] for why that arm
 * throws while [tear] is a silent no-op.
 */
public class FakeSeam(
    override val selfId: PeerId = PeerId("self"),
    initialPeers: Set<PeerId> = setOf(selfId),
    initialState: SeamState = SeamState.Woven,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Seam {
    // Copied, not retained (#2546). `MutableStateFlow(initialPeers)` holds the caller's own instance,
    // so a caller passing a `MutableSet` could drop `selfId` from it one line after the `require`
    // below had passed — reaching the refused state with no FakeSeam method called at all. The copy is
    // also what the `init` guards read (`_peers.value`, not the parameter), so what is validated and
    // what is retained are the same object rather than two reads of a set the caller can still edit.
    private val _peers = MutableStateFlow(initialPeers.toSet())
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    private val _broadcasts = mutableListOf<ByteArray>()
    private val _directed = mutableListOf<Pair<PeerId, ByteArray>>()
    private var sequenceCounter = 0L

    init {
        // `Seam.peers` holds `selfId` from construction onward, in EVERY state — a strictly broader
        // obligation than the `Torn` one below, and independent of it (#2536). `initialPeers` is its own
        // parameter, so a caller could hand the fake a roster naming only a remote, or nobody at all, and
        // both read backwards in the direction consumer tests turn on: `peers.value.size > 1` is the
        // contract's sentinel for "a remote is connected", so a roster missing `selfId` under-counts by
        // one and a seam with one live remote reads as alone. The fake would also disagree with itself
        // about who `selfId` is — `sendTo` refuses a self-send before consulting the roster (#2428).
        require(selfId in _peers.value) {
            "A Seam's peers always contains selfId, from construction onward (Seam.peers, #2536) — " +
                "`peers.value.size > 1` is the contract's sentinel for \"at least one remote is " +
                "connected\", and a roster without selfId under-counts by one, so a seam holding one " +
                "remote reads as alone. Pass initialPeers containing selfId (setOf(selfId) is the " +
                "default). Got selfId=${selfId.value}, peers=${_peers.value.map { it.value }}"
        }
        // The constructor is the OTHER entry into `Torn`, and fixing `tear()` left it open (#2432).
        // `initialState` and `initialPeers` are independent parameters, so a caller could *start* in
        // the state the transition can no longer reach: `Torn` alongside a roster naming a remote.
        // A fake that can represent a state no conforming seam reaches is the permissive-fake shape
        // one level up — a consumer test written against it passes while describing something
        // production cannot produce, which makes its assertion unfalsifiable in the useful direction.
        //
        // Refused loudly rather than rewritten silently: deriving the roster from the state would
        // discard what the caller wrote and teach a reader the wrong model of the contract.
        //
        // This COMPOSES with the guard above rather than subsuming it or being subsumed by it: `Torn`
        // demands the roster be exactly `{ selfId }` and says nothing about any other state; `#2536`
        // demands `selfId ∈ peers` and says nothing about remotes. Folding either into the other loses
        // a shape — `FakeSeamRosterAlwaysHoldsSelfIdTest.tornAndLiveGuardsStayDistinct` is the check.
        val torn = initialState as? SeamState.Torn
        require(torn == null || _peers.value == setOf(selfId)) {
            "A Torn seam's peers is exactly { selfId } (Seam.peers, #1816) — a torn fabric can reach " +
                "nobody, and selfId is never absent. Construct with initialPeers = setOf(selfId) (the " +
                "default), or start Woven/Weaving and call tear(). Got state=$initialState, " +
                "peers=${_peers.value.map { it.value }}, selfId=${selfId.value}"
        }
        // The spool dimension of the same obligation: every real seam completes `incoming` on tear, and
        // `tear()` below closes the spool for exactly that reason. A seam that starts `Torn` with an
        // open spool would suspend a collector forever on a fabric that can never deliver — and since
        // `deliver` refuses once Torn, the spool is provably empty here.
        if (torn != null) spool.close()
    }

    /** All payloads passed to [broadcast], in call order. Each read returns a fresh defensive snapshot; two reads may observe different snapshots if a broadcast lands between them. */
    public val broadcasts: List<ByteArray> get() = _broadcasts.toList()

    /** All (peer, payload) pairs passed to [sendTo], in call order. Each read returns a fresh defensive snapshot; two reads may observe different snapshots if a [sendTo] lands between them. */
    public val directed: List<Pair<PeerId, ByteArray>> get() = _directed.toList()

    /**
     * Optional hook invoked after [broadcast] is recorded (and state/peers checks pass).
     * Used internally by [fakeSeamPair] to cross-wire delivery. Not part of the public API.
     */
    internal var onBroadcast: (suspend (ByteArray) -> Unit)? = null

    // ── Seam interface ────────────────────────────────────────────────────────

    override suspend fun broadcast(payload: ByteArray) {
        checkNotTorn()
        _broadcasts.add(payload)
        onBroadcast?.invoke(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        checkNotTorn()
        // A fake that accepts what production refuses makes every consumer test that exercises the
        // refusal vacuous — `_peers` includes selfId, so without this a self-send was recorded in
        // `directed` and the consumer's mistake read back as a delivered frame (#2428).
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        _directed.add(peer to payload)
    }

    override suspend fun close(reason: CloseReason) {
        tear(reason)
    }

    // ── Test-driver helpers ───────────────────────────────────────────────────

    /** Add [peer] to the live peers set. */
    public fun addPeer(peer: PeerId) {
        _peers.update { it + peer }
    }

    /**
     * Remove [peer] from the live peers set, leaving [state] untouched — the *membership drain* a real
     * peer-mesh performs when one link dies while others survive.
     *
     * **Refuses [selfId]**, throwing [IllegalArgumentException]. [Seam.peers] holds `selfId` in every
     * state, so `removePeer(selfId)` reaches the roster the constructor already refuses (#2536) — one
     * transition later, and from a consumer's test *body* rather than its fixture. The inversion that
     * guard exists to prevent fires exactly as it describes: a `Woven` seam with one live remote whose
     * `peers.value.size > 1` — the contract's sentinel for "at least one remote is connected" — reads
     * `false`, so the seam reads *alone*. It also brings this helper into line with [sendTo], which has
     * refused `selfId` since #2428; without it the fake disagreed with itself on the same axis in two
     * places, refusing to *address* self while accepting *forgetting* self (#2546).
     *
     * Use [tear] for the collapse to `{ selfId }`. Removing a peer the roster never held stays a silent
     * no-op — the guard is on `selfId`, not on membership.
     */
    public fun removePeer(peer: PeerId) {
        require(peer != selfId) {
            "A Seam's peers always contains selfId, in every state (Seam.peers, #2536/#2546) — " +
                "removing it holes the roster: `peers.value.size > 1` is the contract's sentinel for " +
                "\"at least one remote is connected\", so a seam still holding one live remote would " +
                "read as alone. To model a membership drain, remove the remote; to collapse the roster " +
                "to { selfId }, call tear(). Got selfId=${selfId.value}, peer=${peer.value}"
        }
        _peers.update { it - peer }
    }

    /**
     * Transition state from [SeamState.Weaving] to [SeamState.Woven]. Re-asserting [SeamState.Woven]
     * on an already-woven seam is a harmless no-op.
     *
     * **Refused once [SeamState.Torn]**, throwing [IllegalStateException]: `Torn` is terminal on every
     * real seam — the invariant `SeamStateGate` exists to make unrepresentable — so a fake that can be
     * un-torn lets a test construct an input no conforming seam can present. That is the same
     * permissive-fake shape as the constructor guards above (#2432/#2536), one arm over: they close
     * the *entry* into a state production cannot reach, this closes the *exit* from one it can.
     *
     * **Why this is loud where [tear] is a silent no-op.** The two arms differ deliberately, and the
     * asymmetry is the contract's, not an oversight. `tear()` after `tear()` asks for the state the
     * seam is already in, and `Seam.close` is idempotent on every real seam (`SeamStateGate.tear`
     * returns `false` rather than throwing) — so silence there *is* the contract. `weave()` after
     * `tear()` asks for a transition no seam performs, and has no idempotent reading: the caller
     * believes something false about the seam it holds. Silently ignoring it would leave that belief
     * in place and the assertions built on it green. [deliver] is the established precedent — also a
     * test-driver helper, also refusing loudly on a torn seam, for the same reason.
     *
     * Note this is reachable only from a test holding a concrete [FakeSeam]: `weave` is not on [Seam],
     * so a component under test can never drive it (#2622).
     */
    public fun weave() {
        check(_state.value !is SeamState.Torn) {
            "Torn is terminal (SeamState) — a seam never leaves it, so weave() cannot un-tear the fake " +
                "for $selfId. A test reaching here believes something about this seam that no " +
                "conforming seam can make true; build a fresh FakeSeam for the post-tear scenario, or " +
                "if you deliberately need a tier that violates terminality, declare that " +
                "non-conformance locally rather than borrowing it from the shared fake. " +
                "Got state=${_state.value}"
        }
        _state.value = SeamState.Woven
    }

    /**
     * Transition state to [SeamState.Torn] with [reason], collapsing [peers] to `{ selfId }` and
     * completing [incoming]. Idempotent.
     *
     * **The spool:** every real seam (`NwSeam.latchTorn`, `MeshSeam.tearDown`, `InMemorySeam.close`)
     * closes the spool on tear, so a consumer that relies on `incoming` completing (or wrongly
     * processes frames after Torn) can't pass against this fake but fail in production.
     *
     * **The roster:** [Seam.peers] requires a `Torn` seam's roster to be exactly `{ selfId }`,
     * published *before* the terminal latch — hence the `_peers` write ahead of `_state` below. Read
     * the seams named above as a model for the **spool dimension only**. On the roster one they now
     * all collapse to `{ selfId }` (#1849 fixed `InMemorySeam`, whose `peers` derives the collapse
     * from its close latch), but they do **not** all publish it *before* the latch: `NwSeam.close`
     * latches `Torn` and collapses afterwards, in a later lock acquisition (#2648). The obligation this
     * `tear` honours comes from [Seam.peers] and `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn`,
     * not from any one of them; it is pinned here by `TestFakePeersCollapseOnTearTest` (#1854).
     */
    public fun tear(reason: CloseReason = CloseReason.Normal) {
        if (_state.value is SeamState.Torn) return
        _peers.value = setOf(selfId)
        _state.value = SeamState.Torn(reason)
        spool.close()
    }

    /**
     * Push [swatch] directly into [incoming], applying the configured [DeliveryPolicy].
     *
     * Throws [IllegalStateException] once the seam is [SeamState.Torn] — a real seam completes
     * `incoming` on tear and never delivers again, so this fake rejects post-tear delivery rather
     * than silently masking a frame that could never arrive.
     */
    public suspend fun deliver(swatch: Swatch) {
        checkNotTorn()
        spool.deliver(swatch)
    }

    /**
     * Push a frame from [from] into [incoming], stamping [sender] and a
     * monotonically increasing [sequence] (receiver-local, starting at 1).
     */
    public suspend fun deliver(from: PeerId, payload: ByteArray) {
        deliver(Swatch(payload = payload, sender = from, sequence = ++sequenceCounter))
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    internal fun nextSequence(): Long = ++sequenceCounter

    private fun checkNotTorn() {
        check(_state.value !is SeamState.Torn) { "Seam for $selfId is torn" }
    }
}
