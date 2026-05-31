package us.tractat.kuilt.websocket

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
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
import io.ktor.websocket.CloseReason as KtorCloseReason
import io.ktor.websocket.Frame as KtorFrame

/**
 * A 2-peer [Seam] backed by a raw Ktor [DefaultWebSocketSession].
 *
 * Works for both the client path ([io.ktor.client.plugins.websocket.DefaultClientWebSocketSession])
 * and the server path ([io.ktor.server.websocket.DefaultWebSocketServerSession]) — both
 * extend [DefaultWebSocketSession].
 *
 * **Wire format:** byte-transparent. Each binary WebSocket frame's payload
 * is delivered verbatim as [Swatch.payload]; no framing prefix, no
 * in-band handshake. Non-binary frames are a protocol error and cause
 * immediate close.
 *
 * **PeerId discovery:** both [selfId] and [remoteId] are supplied at
 * construction time by the calling factory. Identity is exchanged out of
 * band — the client passes its [PeerId] in the URL query (`?peer=<id>`),
 * and the server's [PeerId] is part of the [WebSocketAdvertisement].
 *
 * **Sequence numbers:** receiver-local monotonic counter, incremented for
 * each [Swatch] delivered to [incoming].
 *
 * Internal type — callers receive [Seam].
 */
internal class WebSocketSeam(
    override val selfId: PeerId,
    private val remoteId: PeerId,
    private val session: DefaultWebSocketSession,
    private val scope: CoroutineScope,
) : Seam {
    private val _peers = MutableStateFlow<Set<PeerId>>(setOf(selfId, remoteId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // WebSocket session is open at construction — fabric is immediately live.
    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    private var closed = false
    private var sequenceCounter = 0L

    init {
        startReceiveLoop()
    }

    override suspend fun broadcast(payload: ByteArray) {
        checkNotClosed()
        session.send(KtorFrame.Binary(fin = true, data = payload))
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        checkNotClosed()
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        // In the 2-peer case, sendTo(remote, ...) is equivalent to broadcast.
        session.send(KtorFrame.Binary(fin = true, data = payload))
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        _peers.update { setOf(selfId) }
        _state.value = SeamState.Torn(reason)
        incomingChannel.close()
        session.close(reason.toKtorCloseReason())
    }

    private fun startReceiveLoop() {
        scope.launch {
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is KtorFrame.Binary -> deliver(frame.readBytes())
                        else -> closeOnUnexpectedFrame(frame)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Remote closed abruptly (EOFException, SocketException, etc.) —
                // treat as a disconnect; the finally block still calls onRemoteDisconnect().
            } finally {
                onRemoteDisconnect()
            }
        }
    }

    private suspend fun closeOnUnexpectedFrame(frame: KtorFrame) {
        close(
            CloseReason.Error(
                IllegalArgumentException("unexpected non-binary frame: ${frame::class.simpleName}"),
            ),
        )
    }

    private fun deliver(bytes: ByteArray) {
        if (closed) return
        val swatch =
            Swatch(
                payload = bytes,
                sender = remoteId,
                sequence = ++sequenceCounter,
            )
        incomingChannel.trySend(swatch)
    }

    private fun onRemoteDisconnect() {
        _peers.update { it - remoteId }
        _state.value = SeamState.Torn(CloseReason.RemoteRequested)
        incomingChannel.close()
    }

    private fun checkNotClosed() {
        check(!closed) { "Seam for $selfId is closed" }
    }
}

private fun CloseReason.toKtorCloseReason(): KtorCloseReason =
    when (this) {
        CloseReason.Normal -> KtorCloseReason(KtorCloseReason.Codes.NORMAL, "")
        CloseReason.RemoteRequested -> KtorCloseReason(KtorCloseReason.Codes.NORMAL, "remote requested")
        CloseReason.Unreachable -> KtorCloseReason(KtorCloseReason.Codes.CANNOT_ACCEPT, "unreachable")
        is CloseReason.Error -> KtorCloseReason(CLOSE_CODE_APPLICATION_ERROR, throwable.message ?: "error")
    }

private const val CLOSE_CODE_APPLICATION_ERROR: Short = 4000
