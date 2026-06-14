package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.coroutines.CoroutineContext

/**
 * A byte-transparent 2-peer [Seam] over a [Conn] whose two identities are known.
 * `broadcast` == `sendTo(remoteId)`. Woven at construction; Torn on conn EOF/error
 * or [close]. Concurrent sends are serialized through an internal channel + single
 * writer so wire order matches call order.
 *
 * @param dispatcher Confines internal state. Production callers pass
 *   `Dispatchers.Default.limitedParallelism(1)`; test callers pass a
 *   `TestCoroutineDispatcher` derived from the test scheduler so that the seam's
 *   read/write loops share the same virtual clock as the test's `withTimeout`.
 */
public fun identified(
    conn: Conn,
    selfId: PeerId,
    remoteId: PeerId,
    dispatcher: CoroutineContext,
): Seam = LinkSeam(conn, selfId, remoteId, dispatcher)

internal class LinkSeam(
    private val conn: Conn,
    override val selfId: PeerId,
    private val remoteId: PeerId,
    dispatcher: CoroutineContext,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _peers = MutableStateFlow(setOf(selfId, remoteId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val inbox = Channel<Swatch>(Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = inbox.receiveAsFlow()

    // Single-writer outbound queue: concurrent broadcast/sendTo enqueue here;
    // one coroutine drains in FIFO order to conn.send.
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)

    private var closed = false
    private var seq = 0L

    init {
        scope.launch { writeLoop() }
        scope.launch { readLoop() }
    }

    override suspend fun broadcast(payload: ByteArray) {
        check(!closed) { "Seam for $selfId is closed" }
        outbox.send(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(!closed) { "Seam for $selfId is closed" }
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        outbox.send(payload)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        _peers.update { setOf(selfId) }
        _state.value = SeamState.Torn(reason)
        outbox.close()
        inbox.close()
        conn.close()
    }

    private suspend fun writeLoop() {
        for (frame in outbox) conn.send(frame)
    }

    private suspend fun readLoop() {
        try {
            conn.incoming.collect { bytes ->
                if (!closed) inbox.trySend(Swatch(payload = bytes, sender = remoteId, sequence = ++seq))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // remote dropped — fall through to teardown
        } finally {
            if (!closed) {
                closed = true
                _peers.update { setOf(selfId) }
                _state.value = SeamState.Torn(CloseReason.RemoteRequested)
                inbox.close()
                outbox.close()
            }
        }
    }
}
