package us.tractat.kuilt.session.election

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
import kotlinx.coroutines.flow.filterIsInstance
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
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
            _lobbyMessages.emit(sender to msg)
        }
    }

    override suspend fun start(memberName: String?): Room =
        // Host collapse (the transport-tear watcher in guardElection handles a fabric that latches Torn;
        // startElection itself handles the membership-drain case where the seam stays Woven).
        guardElection(collapseSignals = emptyList()) {
            startElection(memberName)
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
            if (committed) {
                runCatchingCancellable {
                    seam.broadcast(LobbyMessage.encode(LobbyMessage.Commit(selfId.value, myEpoch)))
                }
                return adoptRoom(SessionRole.Host, memberName)
            }
            // Aborted (membership changed, lost host, or timed out): reopen and retry.
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

    override suspend fun awaitRoom(memberName: String?): Room =
        guardElection(
            // Membership-drain abort (#1466 member path, the hardware failure): the elected host left
            // the peer set, so [host] recomputed to self — this member can never receive a Freeze from a
            // host that is now itself. Mirror of startElection's role re-check, but as a racing watcher
            // because awaitRoomElection is suspended in `lobbyMessages.first { … }` and never re-evaluates
            // `host` without an emission. Fires even when the seam stays Woven (no Torn).
            collapseSignals = listOf {
                host.first { it == selfId }
                LobbyTornException(collapseReason())
            },
        ) {
            awaitRoomElection(memberName)
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
                runCatchingCancellable {
                    seam.broadcast(LobbyMessage.encode(LobbyMessage.FreezeAck(freeze.hostId, freeze.epoch)))
                }
                resolved.await()
            }
            if (resolution is LobbyMessage.Commit) {
                return adoptRoom(SessionRole.Joiner, memberName)
            }
            // Reopen or timeout: discard and await a fresh Freeze.
        }
    }

    /**
     * Run [body] (an election handshake) but abort it with [LobbyTornException] the instant the
     * election becomes impossible to complete — either the underlying [seam] latches [SeamState.Torn]
     * (transport gone) OR one of the [collapseSignals] fires (the co-elector(s) this peer is electing
     * with left the peer set while the seam stayed `Woven`). Both are mid-2PC peer-set collapses
     * (#1466); they surface differently at the seam layer:
     *
     * - **Transport tear** — a fabric that latches `Torn` on drain (e.g. a peerMesh losing its last
     *   link). Caught by the always-present torn watcher.
     * - **Membership drain** — the observed hardware failure: `Seam.peers` drops to `{self}` (and
     *   [host] recomputes to `self`) but `state` never becomes `Torn`. A torn watcher alone never
     *   fires; the caller-supplied [collapseSignals] key on [peers]/[host] instead.
     *
     * Without this, [awaitRoomElection]'s Freeze-wait and [startElection]'s ack-wait suspend forever:
     * [lobbyMessages] is a standalone flow that never completes when [Seam.incoming] does, so nothing
     * else wakes the waiter. The retryable throw lets the caller re-run `electLobby` to rejoin / re-elect
     * — the lobby's analogue of `SeamRoom.runTornWatcher`, generalised past transport tear.
     *
     * The watchers and the body run as [CoroutineStart.UNDISPATCHED] siblings — each reaches its first
     * suspension point before control returns — so a collapse already true at entry (the eager torn
     * check, plus each signal's own entry check) or racing the body is never missed.
     *
     * @param collapseSignals each suspends until its collapse condition holds, then returns the
     *   [LobbyTornException] to abort with. Role-specific (member: became-host; host: lost all members).
     */
    private suspend fun <T> guardElection(
        collapseSignals: List<suspend () -> LobbyTornException>,
        body: suspend () -> T,
    ): T =
        coroutineScope {
            (seam.state.value as? SeamState.Torn)?.let { throw LobbyTornException(it.reason) }
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
            val watchers = buildList {
                add(
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        val torn = seam.state.filterIsInstance<SeamState.Torn>().first()
                        outcome.completeExceptionally(LobbyTornException(torn.reason))
                    },
                )
                for (signal in collapseSignals) {
                    add(launch(start = CoroutineStart.UNDISPATCHED) { outcome.completeExceptionally(signal()) })
                }
            }
            try {
                outcome.await()
            } finally {
                work.cancel()
                watchers.forEach { it.cancel() }
            }
        }

    /** [CloseReason] to report for a membership-drain collapse: the seam's own if torn, else [CloseReason.Unreachable]. */
    private fun collapseReason(): CloseReason = (seam.state.value as? SeamState.Torn)?.reason ?: CloseReason.Unreachable

    override suspend fun leave() {
        adoptMutex.withLock {
            if (adopted) return // seam ownership transferred to the Room; do not close it.
            collectorJob.cancel()
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
            factory.adopt(seam, role, memberName = memberName, roomKey = roomKey)
        }
}
