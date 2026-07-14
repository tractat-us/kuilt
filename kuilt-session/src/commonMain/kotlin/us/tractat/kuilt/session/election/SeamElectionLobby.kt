package us.tractat.kuilt.session.election

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
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

    override suspend fun start(memberName: String?): Room = TODO("Task 5")

    override suspend fun awaitRoom(memberName: String?): Room = TODO("Task 5")

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
