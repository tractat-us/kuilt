package us.tractat.kuilt.session.election

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamCollapsedException
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.raceCollapse
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.election.SeamElectionLobby")

/**
 * [Seam]-backed [ElectionLobby]. Owns the woven mesh [seam] until a [Room] adopts it.
 *
 * A single collector drains [Seam.incoming], decoding [LobbyMessage]s and republishing them on
 * [lobbyMessages] for [start]/[awaitRoom] to consume. At adopt time that collector is cancelled and
 * joined BEFORE [SeamRoomFactory.adopt] starts the room's own collector — preserving the
 * single-collection `incoming` contract (ADR-034).
 */
internal class SeamElectionLobby(
    private val seam: Seam,
    private val factory: SeamRoomFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val roomKey: String?,
    private val freezeTimeout: Duration = 10.seconds,
    private val commitTimeout: Duration = 10.seconds,
) : ElectionLobby {

    override val selfId: PeerId = seam.selfId

    // Seam.peers already includes selfId (documented invariant) — no ∪ {self} needed.
    override val peers: StateFlow<Set<PeerId>> = seam.peers

    override val host: StateFlow<PeerId> =
        seam.peers
            .map { electHost(it) }
            .stateIn(scope, SharingStarted.Eagerly, electHost(seam.peers.value))

    private val _lobbyMessages = MutableSharedFlow<Pair<PeerId, LobbyMessage>>(extraBufferCapacity = 64)
    private val lobbyMessages: SharedFlow<Pair<PeerId, LobbyMessage>> = _lobbyMessages.asSharedFlow()

    private val adoptMutex = Mutex()
    private var adopted = false
    private var epoch: Long = 0L

    private val collectorJob: Job = scope.launch {
        seam.incoming.collect { swatch ->
            val sender = swatch.sender ?: return@collect
            val msg = LobbyMessage.decode(swatch.toByteArray()) ?: return@collect
            logger.info { "lobby.rx self=${selfId.value} from=${sender.value} msg=$msg" }
            _lobbyMessages.emit(sender to msg)
        }
    }

    // Diagnostic (#1466): log the underlying transport's state, roster, and elected host together on
    // every change, from the lobby's own view. This is the co-timestamped line needed to tell a
    // transport tear (state→Torn) from a membership drain (peers shrinks, state stays Woven) from a
    // present-but-unreachable peer (peers unchanged, state Woven) on hardware — the three cases that
    // #1468 / #1471 / liveness handle respectively. Note [seam] here is exactly the seam the election
    // elects over, and [peers] is `seam.peers` verbatim (which always contains selfId by contract).
    private val diagnosticJob: Job = scope.launch {
        combine(seam.state, seam.peers, host) { s, p, h -> Triple(s, p, h) }
            .collect { (s, p, h) ->
                logger.info {
                    "lobby.diag self=${selfId.value} state=$s peers=${p.map { it.value }} host=${h.value}"
                }
            }
    }

    override suspend fun start(memberName: String?): Room {
        logger.info { "lobby.start.enter self=${selfId.value} host=${host.value} peers=${peers.value.map { it.value }} state=${seam.state.value}" }
        // Host collapse (the transport-tear watcher in guardElection handles a fabric that latches Torn;
        // startElection itself handles the membership-drain case where the seam stays Woven).
        return try {
            guardElection(collapseSignals = emptyList()) { startElection(memberName) }
                .also { logger.info { "lobby.start.adopted self=${selfId.value}" } }
        } catch (e: Throwable) {
            logger.info { "lobby.start.exit self=${selfId.value} threw=${e::class.simpleName}: ${e.message}" }
            throw e
        }
    }

    private suspend fun startElection(memberName: String?): Room {
        if (host.value != selfId) {
            throw NotElectedHostException("not the elected host: host=${host.value}, self=$selfId")
        }
        var everHadMembers = false
        while (true) {
            // Re-check role each attempt: a lower-id peer may have appeared between retries.
            if (host.value != selfId) {
                throw NotElectedHostException("lost host election mid-start: host=${host.value}, self=$selfId")
            }
            val roster = peers.value
            val members = roster - selfId
            // Membership-drain abort (#1466 host path): if we were electing with members and the roster
            // has since collapsed to just us, surface a retryable signal instead of silently committing a
            // solo room. A host that started alone (never had members) still commits immediately below.
            if (members.isEmpty() && everHadMembers) {
                throw LobbyTornException(collapseReason())
            }
            if (members.isNotEmpty()) everHadMembers = true
            val myEpoch = ++epoch

            // awaitUnanimousAck subscribes to lobbyMessages BEFORE it broadcasts the Freeze, so a
            // FreezeAck landing in the replay-0 gap between broadcast and subscribe is never lost.
            val committed = withTimeoutOrNull(freezeTimeout) { awaitUnanimousAck(roster, members, myEpoch) } ?: false
            logger.info { "lobby.freeze-round self=${selfId.value} epoch=$myEpoch members=${members.map { it.value }} committed=$committed" }
            if (committed) {
                logger.info { "lobby.tx.Commit self=${selfId.value} epoch=$myEpoch → adopting Host" }
                runCatchingCancellable {
                    seam.broadcast(LobbyMessage.encode(LobbyMessage.Commit(selfId.value, myEpoch)))
                }
                return adoptRoom(SessionRole.Host, memberName)
            }
            // Aborted (membership changed, lost host, or timed out): reopen and retry.
            logger.info { "lobby.tx.Reopen self=${selfId.value} epoch=$myEpoch (freeze aborted; retrying)" }
            runCatchingCancellable { seam.broadcast(LobbyMessage.encode(LobbyMessage.Reopen(myEpoch))) }
        }
    }

    /**
     * Broadcast the [LobbyMessage.Freeze] for [ackEpoch] and await a [LobbyMessage.FreezeAck] from
     * every peer in [members]. Returns true on unanimous ack; false (abort) if the peer set drifts
     * from [roster] (the set the Freeze advertised) or this peer stops being the elected host.
     *
     * **Subscribe-before-broadcast.** The ack/abort collectors are launched [CoroutineStart.UNDISPATCHED]
     * so each runs up to its `collect` subscription *before* control returns to broadcast the Freeze —
     * closing the window where an ack emitted on the replay-0 [lobbyMessages] would be dropped. The
     * membership baseline is [roster] (captured with [members] at Freeze time), not a later
     * `peers.value` re-read, so a peer that leaves between capture and here is detected rather than
     * silently awaited forever.
     */
    private suspend fun awaitUnanimousAck(roster: Set<PeerId>, members: Set<PeerId>, ackEpoch: Long): Boolean =
        coroutineScope {
            val outcome = CompletableDeferred<Boolean>()
            val needed = members.toMutableSet()
            val ackJob = launch(start = CoroutineStart.UNDISPATCHED) {
                lobbyMessages.collect { (sender, msg) ->
                    if (msg is LobbyMessage.FreezeAck && msg.hostId == selfId.value && msg.epoch == ackEpoch) {
                        needed.remove(sender)
                        if (needed.isEmpty()) outcome.complete(true)
                    }
                }
            }
            val membershipJob = launch(start = CoroutineStart.UNDISPATCHED) {
                peers.collect { if (it != roster) outcome.complete(false) }
            }
            val hostJob = launch(start = CoroutineStart.UNDISPATCHED) {
                host.collect { if (it != selfId) outcome.complete(false) }
            }
            // Subscriptions are now live (UNDISPATCHED ran each collector to its first suspend) — only
            // now broadcast the Freeze, so no FreezeAck can land in the replay-0 gap before we listen.
            logger.info { "lobby.tx.Freeze self=${selfId.value} epoch=$ackEpoch roster=${roster.map { it.value }} awaiting-acks-from=${needed.map { it.value }}" }
            runCatchingCancellable {
                seam.broadcast(
                    LobbyMessage.encode(
                        LobbyMessage.Freeze(selfId.value, roster.map { it.value }.toSet(), ackEpoch),
                    ),
                )
            }
            if (needed.isEmpty()) outcome.complete(true) // no members → immediate commit
            val result = outcome.await()
            ackJob.cancel(); membershipJob.cancel(); hostJob.cancel()
            result
        }

    override suspend fun awaitRoom(memberName: String?): Room {
        logger.info { "lobby.awaitRoom.enter self=${selfId.value} host=${host.value} peers=${peers.value.map { it.value }} state=${seam.state.value}" }
        return try {
            guardElection(
                // Membership-drain abort (#1466 member path, the hardware failure): the elected host left
                // the peer set, so [host] recomputed to self — this member can never receive a Freeze from a
                // host that is now itself. Mirror of startElection's role re-check, but as a racing watcher
                // because awaitRoomElection is suspended in `lobbyMessages.first { … }` and never re-evaluates
                // `host` without an emission. Fires even when the seam stays Woven (no Torn).
                collapseSignals = listOf {
                    host.first { it == selfId }
                    logger.info { "lobby.awaitRoom.collapse-signal self=${selfId.value} host→self (elected host left) → LobbyTornException" }
                    LobbyTornException(collapseReason())
                },
            ) {
                awaitRoomElection(memberName)
            }.also { logger.info { "lobby.awaitRoom.adopted self=${selfId.value}" } }
        } catch (e: Throwable) {
            logger.info { "lobby.awaitRoom.exit self=${selfId.value} threw=${e::class.simpleName}: ${e.message}" }
            throw e
        }
    }

    private suspend fun awaitRoomElection(memberName: String?): Room {
        while (true) {
            // Await a Freeze from THIS peer's currently-elected host that names us in the roster.
            // Ignore a Freeze whose host is ourselves (a member never joins itself) or a foreign host.
            val freeze = lobbyMessages
                .first { (sender, msg) ->
                    msg is LobbyMessage.Freeze &&
                        msg.hostId != selfId.value &&
                        sender.value == msg.hostId &&
                        PeerId(msg.hostId) == host.value &&
                        selfId.value in msg.roster
                }
                .second as LobbyMessage.Freeze
            logger.info { "lobby.freeze-matched self=${selfId.value} from-host=${freeze.hostId} epoch=${freeze.epoch} → ack + await Commit" }

            // Subscribe-before-broadcast: establish the Commit/Reopen subscription (UNDISPATCHED, so it
            // reaches `collect` before we return) BEFORE broadcasting the ack. Otherwise a Commit emitted
            // on the replay-0 lobbyMessages between the ack and the subscribe is lost — and since the host
            // adopts (and stops broadcasting) right after Commit, the member would wait out commitTimeout
            // and then loop for a fresh Freeze that never comes, wedging it out of the session forever.
            val resolution = coroutineScope {
                val resolved = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(commitTimeout) {
                        lobbyMessages.first { (_, msg) ->
                            (msg is LobbyMessage.Commit && msg.hostId == freeze.hostId && msg.epoch == freeze.epoch) ||
                                (msg is LobbyMessage.Reopen && msg.epoch == freeze.epoch)
                        }.second
                    }
                }
                logger.info { "lobby.tx.FreezeAck self=${selfId.value} to-host=${freeze.hostId} epoch=${freeze.epoch}" }
                runCatchingCancellable {
                    seam.broadcast(LobbyMessage.encode(LobbyMessage.FreezeAck(freeze.hostId, freeze.epoch)))
                }
                resolved.await()
            }
            logger.info { "lobby.resolution self=${selfId.value} epoch=${freeze.epoch} → ${resolution?.let { it::class.simpleName } ?: "TIMEOUT (retry for fresh Freeze)"}" }
            if (resolution is LobbyMessage.Commit) {
                return adoptRoom(SessionRole.Joiner, memberName)
            }
            // Reopen or timeout: discard and await a fresh Freeze.
        }
    }

    /**
     * Run [body] (an election handshake) but abort it with [LobbyTornException] the instant the
     * election becomes impossible to complete. Two mid-2PC peer-set collapses (#1466), surfacing
     * differently at the seam layer, must both fire:
     *
     * - **Transport tear** — the underlying [seam] latches [SeamState.Torn] (e.g. a peerMesh losing its
     *   last link). Owned by [Seam.raceCollapse]'s always-present torn watcher (+ eager torn check).
     * - **Membership drain** — the observed hardware failure: `Seam.peers` drops to `{self}` (and [host]
     *   recomputes to `self`) but `state` never becomes `Torn`. A torn watcher alone never fires; the
     *   role-specific [collapseSignals] key on [host] instead.
     *
     * The torn dimension is delegated to the core [Seam.raceCollapse] primitive; its [SeamCollapsedException]
     * is re-thrown as the lobby's [LobbyTornException]. The membership dimension is **not** a bare
     * peers-size predicate — a lone host legitimately runs solo (`start` from an empty roster commits
     * immediately) — so `abortWhen` is disabled and the lobby composes its own [collapseSignals] on top
     * (member: `host` became self; the host path handles drain inside [startElection]'s `everHadMembers`
     * re-check). Each signal and [body] run as [CoroutineStart.UNDISPATCHED] siblings so a collapse already
     * true at entry, or racing the body, is never missed.
     *
     * Without this, [awaitRoomElection]'s Freeze-wait and [startElection]'s ack-wait suspend forever:
     * [lobbyMessages] is a standalone flow that never completes when [Seam.incoming] does, so nothing
     * else wakes the waiter. The retryable throw lets the caller re-run `electLobby` to rejoin / re-elect.
     *
     * @param collapseSignals each suspends until its collapse condition holds, then returns the
     *   [LobbyTornException] to abort with. Role-specific (member: became-host; host: lost all members).
     */
    private suspend fun <T> guardElection(
        collapseSignals: List<suspend () -> LobbyTornException>,
        body: suspend () -> T,
    ): T =
        try {
            seam.raceCollapse(abortWhen = { false }) {
                if (collapseSignals.isEmpty()) body() else raceCollapseSignals(collapseSignals, body)
            }
        } catch (e: SeamCollapsedException) {
            throw LobbyTornException(e.reason)
        }

    /** Race [body] against the lobby's role-specific [collapseSignals]; the first to resolve wins. */
    private suspend fun <T> raceCollapseSignals(
        collapseSignals: List<suspend () -> LobbyTornException>,
        body: suspend () -> T,
    ): T =
        coroutineScope {
            val outcome = CompletableDeferred<T>()
            val work = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    outcome.complete(body())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    outcome.completeExceptionally(e)
                }
            }
            val signalJobs = collapseSignals.map { signal ->
                launch(start = CoroutineStart.UNDISPATCHED) { outcome.completeExceptionally(signal()) }
            }
            try {
                outcome.await()
            } finally {
                work.cancel()
                signalJobs.forEach { it.cancel() }
            }
        }

    /** [CloseReason] to report for a membership-drain collapse: the seam's own if torn, else [CloseReason.Unreachable]. */
    private fun collapseReason(): CloseReason = (seam.state.value as? SeamState.Torn)?.reason ?: CloseReason.Unreachable

    override suspend fun leave() {
        adoptMutex.withLock {
            if (adopted) return // seam ownership transferred to the Room; do not close it.
            collectorJob.cancel()
            diagnosticJob.cancel()
            seam.close(CloseReason.Normal)
        }
    }

    /**
     * Stop the lobby's `incoming` collector, then adopt the seam as [role]. Cancel-and-join guarantees
     * the lobby collector has fully stopped before the room starts its own collector (single-collection).
     * Callable at most once.
     */
    private suspend fun adoptRoom(role: SessionRole, memberName: String?): Room =
        adoptMutex.withLock {
            check(!adopted) { "lobby already adopted a room" }
            adopted = true
            collectorJob.cancelAndJoin()
            diagnosticJob.cancel()
            factory.adopt(seam, role, memberName = memberName, roomKey = roomKey)
        }
}
