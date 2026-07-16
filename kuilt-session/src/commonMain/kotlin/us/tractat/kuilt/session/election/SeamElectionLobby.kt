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

    override suspend fun start(memberName: String?): Room = failOnTear {
        startElection(memberName)
    }

    private suspend fun startElection(memberName: String?): Room {
        if (host.value != selfId) {
            throw NotElectedHostException("not the elected host: host=${host.value}, self=$selfId")
        }
        while (true) {
            // Re-check role each attempt: a lower-id peer may have appeared between retries.
            if (host.value != selfId) {
                throw NotElectedHostException("lost host election mid-start: host=${host.value}, self=$selfId")
            }
            val roster = peers.value
            val members = roster - selfId
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

    override suspend fun awaitRoom(memberName: String?): Room = failOnTear {
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
     * underlying [seam] latches [SeamState.Torn] — the co-elector(s) are permanently gone and no
     * Freeze / FreezeAck / Commit can ever complete. Without this, [awaitRoomElection]'s Freeze-wait
     * and [startElection]'s ack-wait suspend forever on a mid-2PC peer-set collapse (#1466): a 2-peer
     * mesh latches `Torn` when its last link drops, but [lobbyMessages] is a standalone flow that
     * never completes when [Seam.incoming] does, so nothing else wakes the waiter. This is the lobby's
     * analogue of `SeamRoom.runTornWatcher`, surfacing a terminal, retryable signal the caller can
     * act on (re-run `electLobby` to rejoin / re-elect) instead of a silent stall.
     *
     * The torn watcher and the body run as [CoroutineStart.UNDISPATCHED] siblings — each reaches its
     * first suspension point before control returns — so a tear already latched at entry (checked
     * eagerly first) or racing the body is never missed.
     */
    private suspend fun <T> failOnTear(body: suspend () -> T): T =
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
            val tearWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                val torn = seam.state.filterIsInstance<SeamState.Torn>().first()
                outcome.completeExceptionally(LobbyTornException(torn.reason))
            }
            try {
                outcome.await()
            } finally {
                work.cancel()
                tearWatcher.cancel()
            }
        }

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
