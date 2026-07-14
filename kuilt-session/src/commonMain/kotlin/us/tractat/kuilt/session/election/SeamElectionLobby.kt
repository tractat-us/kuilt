package us.tractat.kuilt.session.election

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    override suspend fun start(memberName: String?): Room {
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
            runCatchingCancellable {
                seam.broadcast(
                    LobbyMessage.encode(
                        LobbyMessage.Freeze(selfId.value, roster.map { it.value }.toSet(), myEpoch),
                    ),
                )
            }

            val committed = withTimeoutOrNull(freezeTimeout) { awaitUnanimousAck(members, myEpoch) } ?: false
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
     * Await a [LobbyMessage.FreezeAck] from every peer in [members] for [ackEpoch]. Returns true on
     * unanimous ack; false (abort) if the peer set changes or this peer stops being the elected host.
     */
    private suspend fun awaitUnanimousAck(members: Set<PeerId>, ackEpoch: Long): Boolean {
        if (members.isEmpty()) return true
        val snapshot = peers.value
        return coroutineScope {
            val outcome = CompletableDeferred<Boolean>()
            val needed = members.toMutableSet()
            val ackJob = launch {
                lobbyMessages.collect { (sender, msg) ->
                    if (msg is LobbyMessage.FreezeAck && msg.hostId == selfId.value && msg.epoch == ackEpoch) {
                        needed.remove(sender)
                        if (needed.isEmpty()) outcome.complete(true)
                    }
                }
            }
            val membershipJob = launch {
                peers.collect { if (it != snapshot) outcome.complete(false) }
            }
            val hostJob = launch {
                host.collect { if (it != selfId) outcome.complete(false) }
            }
            val result = outcome.await()
            ackJob.cancel(); membershipJob.cancel(); hostJob.cancel()
            result
        }
    }

    override suspend fun awaitRoom(memberName: String?): Room {
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

            runCatchingCancellable {
                seam.broadcast(LobbyMessage.encode(LobbyMessage.FreezeAck(freeze.hostId, freeze.epoch)))
            }

            val resolution = withTimeoutOrNull(commitTimeout) {
                lobbyMessages.first { (_, msg) ->
                    (msg is LobbyMessage.Commit && msg.hostId == freeze.hostId && msg.epoch == freeze.epoch) ||
                        (msg is LobbyMessage.Reopen && msg.epoch == freeze.epoch)
                }.second
            }
            if (resolution is LobbyMessage.Commit) {
                return adoptRoom(SessionRole.Joiner, memberName)
            }
            // Reopen or timeout: discard and await a fresh Freeze.
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
