package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport

private val log = KotlinLogging.logger("us.tractat.kuilt.cluster.RoutedRaftTransport")

/**
 * A conservative allowance, in bytes, for the [RaftRelay] envelope that wraps a
 * relayed Raft payload. Subtracted from the inner transport's frame limit so a
 * ceiling-sized `InstallSnapshot` chunk plus its envelope never overflows a
 * framed fabric.
 *
 * Covers the CBOR structural overhead (map header + the field-name keys
 * `origin`/`dest`/`bytes` + length prefixes) plus two node-id strings of up to
 * roughly a hundred bytes each. Node ids are short, controlled identifiers by
 * convention (a host:port or a uuid — see [NodeId]); a pathologically long id
 * could still exceed this margin, which is why it is a tunable constructor
 * parameter rather than a hard constant.
 */
internal const val RELAY_HEADER_BUDGET: Int = 256

/**
 * Deliver Raft messages to nodes that sit behind *another* server, without ever
 * losing track of who really sent each one — the **routed Raft transport**.
 *
 * A federated game runs one Raft cluster whose members are spread across several
 * servers: the servers form a small fully-meshed core, and each player connects
 * to whichever server is nearest. An ordinary transport can only talk to nodes it
 * is directly wired to, so a leader on one server could never reach a player
 * behind a different one — that player would never receive the committed log.
 * This decorator fixes that by relaying: when a message is addressed to a node it
 * cannot reach directly, it wraps the message in a [RaftRelay] and hands it one
 * hop closer, along the bounded path `player → server → core → server → player`.
 * Every hop is a single-addressee send — the relay is never a broadcast, so a
 * message meant for one node never leaks to a second.
 *
 * ## It disappears when there is nothing to relay
 *
 * Wrapping [inner] is inert off a federation. When every addressee is a direct
 * peer of [inner] (a single server, a LAN, an in-memory test), [sendTo] delegates
 * straight through and **no relay frame is ever produced**. The decorator only
 * does work once a message is addressed to a node that is not directly reachable.
 *
 * ## Preserving the true origin (why this exists at all)
 *
 * On a direct link the fabric stamps the sender for you, and it can never be
 * forged. Relaying breaks that: the frame's fabric-sender becomes the relaying
 * server, not the node the Raft engine must credit. So the real sender rides
 * *inside* the [RaftRelay] as its `origin`, and this transport preserves it
 * verbatim — a relayed frame surfaces as `RaftEnvelope(from = origin)`, never the
 * relay's own sender. (The engine keys vote tallies, `matchIndex`, CheckQuorum,
 * ReadIndex acks and leadership-transfer auth on `from`; re-stamping would break
 * every one.)
 *
 * ## First-hop origin validation (commit-safety)
 *
 * Because `origin` now travels inside a forgeable frame, it is validated before a
 * relayed message is ever handed to the engine or forwarded on. The two roles
 * validate differently:
 *
 * **A server** applies the sender-based first-hop rule ([validFirstHop]):
 *
 * - A frame from **a spoke** (the fabric-sender is not a core member) is accepted
 *   only if its `origin` equals that sender — a player may speak only for itself,
 *   never forge a vote or a `matchIndex`-advancing response on another node's
 *   behalf.
 * - A frame from **the core** (the fabric-sender is a core member) is trusted to
 *   carry an already-validated `origin`; core servers preserve identity.
 * - A frame that arrived from the core and is **not** for a locally reachable
 *   node is dropped, never re-forwarded onto the core — the loop guard that keeps
 *   the hop bound at one core crossing.
 *
 * **A player** cannot trust the sender: its one peer (the relay server) need not
 * be a voter, and a co-player behind the same server could wrap a forged
 * `AppendEntries` as `RaftRelay(origin = self, dest = victim, …)` that the server
 * forwards down. So a player instead trusts a down-frame only when its **true
 * `origin` is a known voter** — read live via [voters] on every frame so a
 * committed membership growth is honoured. This is strictly tighter than the
 * sender rule and closes that log-corruption vector: the victim's engine does no
 * `from` validation and would otherwise truncate-and-append the forged log.
 *
 * ## The residual spoke→voter gap (a documented decision, not an oversight)
 *
 * This relay is deliberately *reachability-complete*: the bounded
 * `spoke → core → core → spoke` path lets **any admitted learner address every
 * cluster member** with honest-origin frames. That reach is the point — it is how
 * a far player's `AppendEntries` response reaches a leader on another server. But
 * reach cuts both ways, and one direction is only *partially* hardened here:
 *
 * - **Origin *spoofing* is blocked** in every direction. A spoke cannot claim to
 *   be another node: the first-hop rule ([validFirstHop]) rejects a spoke frame
 *   whose `origin` isn't the sender, and the player-side `origin ∈ voters()` gate
 *   rejects a fellow-spoke's forged down-frame. So no node is ever *impersonated*.
 * - **A voter accepting an *honest-origin* RPC it should never process is not.**
 *   A malicious-but-admitted learner can send a voter a `RaftRelay` carrying its
 *   own honest `origin` but an `AppendEntries` / `InstallSnapshot` body — RPC types
 *   only a *leader* should originate. The voter's `validFirstHop` passes (origin ==
 *   sender), the frame reaches the engine, and **the engine does no `from`
 *   validation on the RPC type**: an accepted `AppendEntries` from a non-leader
 *   truncates-and-appends the voter's log, and an `InstallSnapshot` overwrites its
 *   state — this is **log corruption**, not merely the term-inflation that a
 *   spoof-only view would suggest (votes and `matchIndex` are keyed on a validated
 *   `from`, but the *log itself* is not). The star topology this replaced happened
 *   to confine such a frame to one server; identity-preserving cluster-wide reach
 *   removes that accidental containment.
 *
 * Closing it needs a **voter-inbound RPC-type gate** (a voter accepts
 * `AppendEntries`/`InstallSnapshot` only from the node it currently believes is
 * leader), which lives at the `RaftRelayHub` fan-in, not in this send-side
 * decorator — tracked as follow-up **#1383**. It is called out here so the reach
 * this class grants is understood as a **deliberate, bounded decision** with a
 * known residual, not an accident.
 *
 * ## One class, two roles
 *
 * A [isServer] server may take one core hop when it holds a frame for a player
 * behind another server (its next hop comes from [attachment], the live "which
 * server is this player behind" lookup). A player always forwards to its single
 * server (the sole non-self peer of [relayChannel]) and never routes for anyone
 * else. Build a server via [serverRelayTransport] and a player via
 * [playerRelayTransport].
 *
 * ## Ownership & threading
 *
 * Takes **sole ownership** of [relayChannel]'s `incoming` stream (a single relay
 * coroutine, launched in [scope], pulls envelopes off it), per the
 * single-collection contract — do not run another collector over the same seam.
 * [incoming] merges [inner]'s own frames with the relayed frames destined for
 * this node. There is no shared mutable state: routing reads only the live
 * [inner]/[relayChannel] peer sets and the injected [attachment] function, so the
 * transport is correct under a multi-threaded dispatcher.
 *
 * @param inner the direct transport this wraps (typically a `SeamRaftTransport`).
 *   Sends to a direct peer, and this node's own directly-received frames, pass
 *   through it unchanged.
 * @param relayChannel the seam relay envelopes are sent and received on. For a
 *   server it reaches the local players and the other core servers; for a player
 *   it reaches its one server. The transport owns its `incoming`.
 * @param core the node ids of the fully-meshed server core — the sender-based
 *   trust boundary for a **server**'s first-hop validation. Unused by a player
 *   (which validates by origin against [voters]); pass the empty set.
 * @param isServer whether this endpoint is a core server (may take one core hop)
 *   or a player (always forwards to its one server).
 * @param scope the [CoroutineScope] whose [Job] parents the relay coroutine.
 *   **Required** — no real-dispatcher default; inject a test scope's
 *   `backgroundScope` under virtual time.
 * @param attachment for a server, the live `(player) -> the server it is behind`
 *   lookup used to pick the core hop for a remote player; ignored by a player
 *   (which always forwards to its one server). Pass
 *   [AttachmentDirectory.lookup]-style function on a server.
 * @param voters for a **player**, the live provider of the current voter node ids
 *   — a down-frame is accepted only when its `origin` is one of these, read
 *   **per frame** so a committed membership growth is picked up (never captured at
 *   construction). Unused by a server; pass `{ emptySet() }`.
 * @param headerBudget the [RaftRelay] envelope allowance subtracted from
 *   [inner]'s frame limit; defaults to [RELAY_HEADER_BUDGET].
 */
public class RoutedRaftTransport(
    private val inner: RaftTransport,
    private val relayChannel: Seam,
    private val core: Set<NodeId>,
    private val isServer: Boolean,
    scope: CoroutineScope,
    private val attachment: (NodeId) -> NodeId?,
    private val voters: () -> Set<NodeId>,
    private val headerBudget: Int = RELAY_HEADER_BUDGET,
) : RaftTransport {

    override val selfId: NodeId get() = inner.selfId

    /**
     * Reachability as [inner] sees it — the directly wired nodes. Nodes reachable
     * only across the core are absent here; the Raft engine still addresses them
     * (it sends to configured voters regardless of reachability) and [sendTo]
     * relays those.
     */
    override val peers: StateFlow<Set<NodeId>> get() = inner.peers

    /**
     * The largest payload a single [sendTo] may carry: [inner]'s limit less the
     * [headerBudget] the [RaftRelay] envelope consumes, so a ceiling-sized chunk
     * plus its envelope still fits a framed fabric. `null` (unbounded) when
     * [inner] is unbounded — an unbounded fabric never chunks, so the envelope's
     * few bytes are immaterial.
     */
    override val maxPayloadBytes: Int?
        get() = inner.maxPayloadBytes?.let { (it - headerBudget).coerceAtLeast(0) }

    /** Self-destined relayed frames, fed by [relayJob] and merged into [incoming]. */
    private val relayed: MutableSharedFlow<RaftEnvelope> =
        MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)

    /**
     * One-shot latch so a mis-wired multi-peer relay channel is warned at most once
     * (a per-frame warn would flood). Atomic so the warn fires exactly once even
     * under a multi-threaded dispatcher.
     */
    private val misWiredRelayWarned = atomic(false)

    /**
     * The sole collector of [relayChannel]. Validates each relay frame, hands
     * self-destined ones to the engine (via [relayed]) and forwards the rest one
     * hop onward. Launched in [scope]; cancelled by [close] or scope teardown.
     */
    private val relayJob: Job = scope.launch {
        runCatchingCancellable {
            relayChannel.incoming.collect { swatch -> handleRelayFrame(swatch) }
        }.onFailure { log.debug { "raft-relay: $selfId relay ended: ${it.message}" } }
    }

    override val incoming: Flow<RaftEnvelope> = merge(inner.incoming, relayed)

    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        if (peer in inner.peers.value) {
            // Direct peer: strict pass-through — no relay frame is produced.
            inner.sendTo(peer, message)
            return
        }
        val hop = nextHop(peer)
        if (hop == null) {
            log.debug { "raft-relay: $selfId has no next hop for remote $peer — dropping (Raft retries)" }
            return
        }
        val envelope = RaftRelay.encode(RaftRelay(origin = selfId, dest = peer, bytes = message))
        runCatchingCancellable { relayChannel.sendTo(PeerId(hop.value), envelope) }
            .onFailure { log.debug { "raft-relay: $selfId relay send to $hop for $peer failed" } }
    }

    /**
     * The one node to hand a message for a not-directly-reachable [peer] to:
     * a server sends a remote core member straight to it and a remote player to
     * the server it is behind ([attachment]); a player always sends to its single
     * server (the sole non-self peer of [relayChannel]).
     */
    private fun nextHop(peer: NodeId): NodeId? =
        if (isServer) {
            if (peer in core) peer else attachment(peer)
        } else {
            playerServerHop()
        }

    /**
     * A player's single upstream server: the sole non-self peer of [relayChannel].
     * Returns `null` (dropping the send) in two *distinct* situations:
     *
     * - **No server peer yet** — the relay channel is empty because the connection
     *   has not (re)established. A transient reconnect gap; Raft retries, so this is
     *   only a debug line.
     * - **More than one non-self peer** — a player's relay channel must be
     *   point-to-point, so multiple candidates mean the channel is **mis-wired** (a
     *   multi-peer relay handed to a player role). That is a configuration defect, not
     *   a transient gap, so it is **warned once** (naming the offending peers) rather
     *   than dropped in silence — every relayed send drops until it is fixed.
     */
    private fun playerServerHop(): NodeId? {
        val servers = relayChannel.peers.value.map { NodeId(it.value) }.filter { it != selfId }
        return when (servers.size) {
            1 -> servers.single()
            0 -> {
                log.debug { "raft-relay: $selfId player relay channel has no server peer yet (reconnect gap) — dropping" }
                null
            }
            else -> {
                if (misWiredRelayWarned.compareAndSet(expect = false, update = true)) {
                    log.warn {
                        "raft-relay: $selfId player relay channel is mis-wired — a player's relay channel must be " +
                            "point-to-point, but it has ${servers.size} server peers: $servers. Dropping every relayed " +
                            "send until it is fixed."
                    }
                }
                null
            }
        }
    }

    private suspend fun handleRelayFrame(swatch: Swatch) {
        val senderPeer = swatch.sender ?: return
        val relay = runCatchingCancellable { RaftRelay.decode(swatch.toByteArray()) }.getOrNull() ?: return
        val sender = NodeId(senderPeer.value)
        if (isServer) handleServerFrame(sender, relay) else handlePlayerFrame(relay)
    }

    /**
     * A **server**'s relay handling: sender-based first-hop validation, then
     * deliver-local / one-core-hop / loop-guard-drop by [RaftRelay.dest].
     */
    private suspend fun handleServerFrame(sender: NodeId, relay: RaftRelay) {
        // First-hop origin validation, BEFORE any emit or forward. A spoke may
        // speak only for itself; a core sender is trusted to carry a validated
        // origin. Shared with RaftRelayHub via [validFirstHop].
        if (!validFirstHop(sender = sender, origin = relay.origin, core = core)) {
            log.debug { "raft-relay: $selfId rejected spoofed frame (origin=${relay.origin}, sender=$sender)" }
            return
        }
        val fromCore = sender in core
        when {
            relay.dest == selfId ->
                // For us: hand to the engine with the true origin preserved.
                relayed.emit(RaftEnvelope(relay.origin, relay.bytes))

            relay.dest in inner.peers.value ->
                // Destination is directly reachable here: one hop down to it.
                forward(relay.dest, relay)

            !fromCore -> {
                // Remote destination, arrived from a spoke: one core hop onward.
                val hop = if (relay.dest in core) relay.dest else attachment(relay.dest)
                if (hop != null) {
                    forward(hop, relay)
                } else {
                    log.debug { "raft-relay: $selfId no core hop for remote dest ${relay.dest} — dropping" }
                }
            }

            else ->
                // Arrived from the core for a non-local destination: drop, never
                // re-forward onto the core (loop guard; Raft retries).
                log.debug { "raft-relay: $selfId dropping core frame for non-local dest ${relay.dest} (loop guard)" }
        }
    }

    /**
     * A **player**'s relay handling: trust a down-frame only when its true
     * [RaftRelay.origin] is a current voter (read live per frame via [voters]),
     * then hand it to the engine with the origin preserved. A player never routes
     * for anyone else, so any frame not addressed to itself is dropped.
     */
    private suspend fun handlePlayerFrame(relay: RaftRelay) {
        if (relay.origin !in voters()) {
            log.debug { "raft-relay: $selfId rejected down-frame from non-voter origin ${relay.origin}" }
            return
        }
        if (relay.dest == selfId) {
            relayed.emit(RaftEnvelope(relay.origin, relay.bytes))
        }
    }

    /** Hand [relay] one hop onward to [hop], preserving its origin. Best-effort. */
    private suspend fun forward(hop: NodeId, relay: RaftRelay) {
        runCatchingCancellable { relayChannel.sendTo(PeerId(hop.value), RaftRelay.encode(relay)) }
            .onFailure { log.debug { "raft-relay: $selfId forward to $hop failed" } }
    }

    /** Stop relaying and release the relay coroutine. Idempotent. */
    public fun close() {
        relayJob.cancel()
    }
}

/**
 * Build a [RoutedRaftTransport] for a **core server**: it may take one core hop
 * to reach a player behind another server, choosing that hop from [attachment].
 *
 * @param inner the server's direct transport (its peers are the local players and
 *   the other core servers it is wired to).
 * @param relayChannel the seam relay envelopes ride, reaching the local players
 *   and the core; the transport owns its `incoming`.
 * @param core the fully-meshed core's node ids — the first-hop trust boundary.
 * @param scope parents the relay coroutine. **Required.**
 * @param attachment the live `(player) -> the server it is behind` lookup.
 */
public fun serverRelayTransport(
    inner: RaftTransport,
    relayChannel: Seam,
    core: Set<NodeId>,
    scope: CoroutineScope,
    attachment: (NodeId) -> NodeId?,
): RoutedRaftTransport =
    RoutedRaftTransport(
        inner = inner,
        relayChannel = relayChannel,
        core = core,
        isServer = true,
        scope = scope,
        attachment = attachment,
        voters = { emptySet() },
    )

/**
 * Build a [RoutedRaftTransport] for a **player**: it always forwards to its single
 * server and never routes for anyone else. Required even single-server — without
 * it a learner's reply, handed raw to a follower server, is dropped or answered
 * `NotLeader`, wedging the commit path.
 *
 * A player trusts a down-frame only when its true `origin` is one of the current
 * [voters] — read live per frame, never captured — so the relay server need not be
 * a voter (closing the admission-hang where a non-voter relay id would be
 * rejected) and a co-player cannot forge an `AppendEntries` the server forwards
 * down (closing a log-corruption vector).
 *
 * ## One unsupported edge (unreachable today)
 *
 * A brand-new **voter** that wins an election *before* this client commits the
 * config entry adding it would have its frames rejected ([voters] would not yet
 * list it). This requires dynamic *voter* changes, which nothing does today —
 * `admitLearner` only ever grows *learners*, never voters. It is documented as
 * unsupported and deliberately not engineered around.
 *
 * @param inner the player's direct transport (its one peer is its server).
 * @param relayChannel the seam to its server; the transport owns its `incoming`.
 * @param voters the live provider of the current voter node ids — the origin trust
 *   set, read **per frame** so a committed membership growth is honoured.
 * @param scope parents the relay coroutine. **Required.**
 */
public fun playerRelayTransport(
    inner: RaftTransport,
    relayChannel: Seam,
    voters: () -> Set<NodeId>,
    scope: CoroutineScope,
): RoutedRaftTransport =
    RoutedRaftTransport(
        inner = inner,
        relayChannel = relayChannel,
        core = emptySet(),
        isServer = false,
        scope = scope,
        attachment = { null },
        voters = voters,
    )
