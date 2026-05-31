package us.tractat.kuilt.multipeer.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSInputStream
import platform.Foundation.NSProgress
import platform.Foundation.NSURL
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionDelegateProtocol
import platform.MultipeerConnectivity.MCSessionSendDataMode
import platform.MultipeerConnectivity.MCSessionState
import platform.darwin.NSObject
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

private val log = KotlinLogging.logger {}

/**
 * `Seam` backed by an `MCSession`.
 *
 * The class owns a private `MCSessionDelegate` that fans MC callbacks
 * (`peer didChangeState`, `didReceiveData`) onto:
 *  - [peers] — set of connected peer IDs (always includes [selfId]).
 *  - [incoming] — frames received from any other peer.
 *
 * MC delegate callbacks fire on the framework's private queue. Every state
 * mutation in this class therefore happens on a coroutine-friendly primitive
 * (`MutableStateFlow.update` / `MutableSharedFlow.tryEmit`) which is safe from
 * any thread.
 *
 * Two-way mapping from `MCPeerID` ↔ [PeerId]: we use the peer's
 * `displayName` as the wire identity. Apple does not expose a stable identity
 * across processes, so display name is the best handle we have. Two peers
 * with the same display name on the same network would collide; surfacing
 * that as a UX problem is left to the lobby.
 */
@OptIn(ExperimentalForeignApi::class)
internal class MCSessionLink(
    private val localPeerId: MCPeerID,
    internal val session: MCSession,
) : Seam {
    override val selfId: PeerId = PeerId(localPeerId.displayName)

    private val _peers: MutableStateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Starts Weaving; transitions to Woven on first MCSessionStateConnected callback.
    private val _state: MutableStateFlow<SeamState> = MutableStateFlow(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val incomingChannel: Channel<Swatch> = Channel(Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    val delegate: MCSessionDelegateProtocol = SessionDelegate()

    // Written by close() before disconnect(); read by the MC delegate callback.
    // No @Volatile here — K/N's memory model (since 1.7.20) makes plain var
    // writes visible across threads; @Volatile is JVM-only.
    private var closing: Boolean = false

    override suspend fun broadcast(payload: ByteArray) {
        val targets = session.connectedPeers
        if (targets.isEmpty()) {
            log.warn { "mc.session.send dropped — no connected peers localPeer=${selfId.value} bytes=${payload.size}" }
            return
        }
        log.debug { "mc.session.send localPeer=${selfId.value} targets=${targets.size} bytes=${payload.size}" }
        session.sendData(
            data = payload.toNSData(),
            toPeers = targets,
            withMode = MCSessionSendDataMode.MCSessionSendDataReliable,
            error = null,
        )
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        val target =
            session.connectedPeers
                .filterIsInstance<MCPeerID>()
                .firstOrNull { it.displayName == peer.value }
                ?: error("Peer ${peer.value} is not connected to MC session")
        log.debug { "mc.session.send localPeer=${selfId.value} toPeer=${peer.value} bytes=${payload.size}" }
        session.sendData(
            data = payload.toNSData(),
            toPeers = listOf(target),
            withMode = MCSessionSendDataMode.MCSessionSendDataReliable,
            error = null,
        )
    }

    override suspend fun close(reason: CloseReason) {
        // Set closing before disconnect() so the MC delegate sees it when
        // session:peer:didChangeState fires .notConnected for the clean
        // disconnect — suppressing the spurious mc.session.error warn.
        closing = true
        _state.value = SeamState.Torn(reason)
        incomingChannel.close()
        session.disconnect()
    }

    private inner class SessionDelegate :
        NSObject(),
        MCSessionDelegateProtocol {
        override fun session(
            session: MCSession,
            peer: MCPeerID,
            didChangeState: MCSessionState,
        ) {
            val peerId = PeerId(peer.displayName)
            val stateName =
                when (didChangeState) {
                    MCSessionState.MCSessionStateConnected -> "[Connected]"
                    MCSessionState.MCSessionStateConnecting -> "[Connecting]"
                    MCSessionState.MCSessionStateNotConnected -> "[Not Connected]"
                    else -> "[Unknown($didChangeState)]"
                }
            log.info { "mc.session.stateChange localPeer=${selfId.value} peer=${peer.displayName} to=$stateName" }
            when (didChangeState) {
                MCSessionState.MCSessionStateConnected -> {
                    _peers.update { it + peerId }
                    if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven
                }
                MCSessionState.MCSessionStateNotConnected -> {
                    // MCSession has no dedicated error callback; .notConnected is the
                    // closest session-level error surface (unexpected drops fire here).
                    // Suppress the warn when closing — that .notConnected is from our
                    // own session.disconnect(), not an unexpected drop.
                    if (!closing) {
                        log.warn { "mc.session.error localPeer=${selfId.value} peer=${peer.displayName}" }
                    }
                    _peers.update { it - peerId }
                }
                else -> Unit // Connecting — wait for terminal state
            }
        }

        override fun session(
            session: MCSession,
            didReceiveData: NSData,
            fromPeer: MCPeerID,
        ) {
            log.debug { "mc.session.receive localPeer=${selfId.value} fromPeer=${fromPeer.displayName} bytes=${didReceiveData.length}" }
            val frame =
                Swatch(
                    payload = didReceiveData.toByteArray(),
                    sender = PeerId(fromPeer.displayName),
                )
            incomingChannel.trySend(frame)
        }

        // MultipeerConnectivity defines five other delegate callbacks
        // (streams, resource transfers, certificate validation). This library
        // doesn't use any, but the protocol requires them. Auto-accept the
        // certificate; everything else is a no-op.
        override fun session(
            session: MCSession,
            didReceiveStream: NSInputStream,
            withName: String,
            fromPeer: MCPeerID,
        ) = Unit

        override fun session(
            session: MCSession,
            didStartReceivingResourceWithName: String,
            fromPeer: MCPeerID,
            withProgress: NSProgress,
        ) = Unit

        override fun session(
            session: MCSession,
            didFinishReceivingResourceWithName: String,
            fromPeer: MCPeerID,
            atURL: NSURL?,
            withError: NSError?,
        ) = Unit

        override fun session(
            session: MCSession,
            didReceiveCertificate: List<*>?,
            fromPeer: MCPeerID,
            certificateHandler: (Boolean) -> Unit,
        ) {
            certificateHandler(true)
        }
    }
}
