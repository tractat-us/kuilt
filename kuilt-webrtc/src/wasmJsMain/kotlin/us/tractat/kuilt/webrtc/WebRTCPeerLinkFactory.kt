package us.tractat.kuilt.webrtc

import us.tractat.kuilt.webrtc.internal.BrowserRtcFacadeFactory
import us.tractat.kuilt.webrtc.internal.DEFAULT_HANDSHAKE_TIMEOUT_MS
import us.tractat.kuilt.webrtc.internal.HandshakeRunner
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacade
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacadeFactory
import us.tractat.kuilt.webrtc.internal.WebRTCPeerLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.random.Random

/**
 * Establishes a [Seam] over a WebRTC `RTCDataChannel`. Signaling
 * (SDP / ICE exchange) is delegated to [signaling]; first available
 * impl is [WebSocketSignalingChannel].
 *
 * Room-scoped: one factory instance targets one signaling [room]. To
 * host or join multiple rooms, construct one factory per room. The
 * [Tag] passed to [join] is read for its `displayName`
 * (used to label the remote peer); its `peerKey` is informational —
 * the room is already pinned by this factory.
 *
 * Wasm-only at launch — see ADR-019 §7.
 */
public class WebRTCPeerLinkFactory
    internal constructor(
        private val signaling: SignalingChannel,
        private val room: String,
        private val iceConfig: IceConfig = IceConfig.DefaultStun,
        private val facadeFactory: RtcPeerConnectionFacadeFactory,
    ) : Loom {
        public constructor(
            signaling: SignalingChannel,
            room: String,
            iceConfig: IceConfig = IceConfig.DefaultStun,
        ) : this(signaling, room, iceConfig, BrowserRtcFacadeFactory())

        override suspend fun weave(rendezvous: Rendezvous): Seam =
            when (rendezvous) {
                is Rendezvous.New -> {
                    val config = rendezvous.pattern
                    val selfId = PeerId(randomToken(config.displayName.ifBlank { "host" }))
                    val facade = facadeFactory.create(iceConfig, hostInitiated = true)
                    val session = signaling.open(room)
                    HandshakeRunner.runHost(facade, session)
                    buildLink(selfId, facade)
                }
                is Rendezvous.Existing -> {
                    val advertisement = rendezvous.tag
                    val selfId = PeerId(randomToken(advertisement.displayName.ifBlank { "peer" }))
                    val facade = facadeFactory.create(iceConfig, hostInitiated = false)
                    val session = signaling.open(room)
                    HandshakeRunner.runJoiner(facade, session)
                    buildLink(selfId, facade)
                }
            }

        /**
         * Exchange [selfId] with the remote peer over the data channel so both sides
         * know each other's stable [PeerId]. The first frame sent and received on the
         * data channel is the peer-id frame; all subsequent frames are user payload.
         *
         * The exchange is non-blocking: a background demux coroutine reads the first
         * incoming frame as the remote's selfId and routes subsequent frames to the
         * user-payload channel. [WebRTCPeerLink] receives the [senderIdDeferred] and
         * awaits it lazily when incoming frames are collected — so host-only tests
         * that never collect [Seam.incoming] do not hang waiting for the remote's ID.
         *
         * Both sides call this symmetrically — order of the selfId frames doesn't
         * matter because the data channel is buffered; the frames cross in flight.
         */
        private suspend fun buildLink(
            selfId: PeerId,
            facade: RtcPeerConnectionFacade,
        ): WebRTCPeerLink {
            val guessedRemoteId = PeerId(randomToken("peer"))
            facade.sendBytes(selfId.value.encodeToByteArray())

            val senderIdDeferred = CompletableDeferred<PeerId>()
            val userChannel = Channel<ByteArray>(Channel.UNLIMITED)
            // Construct the link first so its scope exists, then launch the demux on
            // that scope — no orphan CoroutineScope; the demux dies when the link closes.
            val link = WebRTCPeerLink(selfId, guessedRemoteId, facade, userChannel.receiveAsFlow(), senderIdDeferred)
            link.scope.launch {
                var idReceived = false
                facade.incomingBytes.collect { bytes ->
                    if (!idReceived) {
                        idReceived = true
                        senderIdDeferred.complete(PeerId(bytes.decodeToString()))
                    } else {
                        userChannel.send(bytes)
                    }
                }
                userChannel.close()
            }
            // Return immediately — senderIdDeferred resolves in the background when the
            // remote's ID frame arrives. Seam.incoming awaits it lazily per frame.
            return link
        }

        /**
         * Open a peer link using server-assigned role assignment (#1300 B0).
         *
         * Connects to the signaling relay, reads the leading [SignalingMessage.Role]
         * frame sent by the server (which observes attach order and assigns host vs.
         * joiner), then dispatches to [runHost] or [runJoiner] accordingly.
         *
         * Requires [signaling] to be a [WebSocketSignalingChannel] — the only
         * implementation that supports server-side role assignment. The role frame is
         * consumed via [Channel.receive] directly so the rest of [incoming] remains
         * collectible by [HandshakeRunner].
         *
         * Use this instead of [open]/[join] for symmetric peers (e.g. Quick Play)
         * where neither tab knows in advance which role it should take.
         *
         * Prefer [openWithServerRoleResult] when the caller needs both the assigned
         * role and the link — it avoids a second signaling session.
         */
        public suspend fun openWithServerRole(config: Pattern = Pattern("quickplay")): Seam = openWithServerRoleResult(config).second

        /**
         * Like [openWithServerRole] but surfaces the relay-assigned role alongside
         * the established [Seam], using a **single** signaling session.
         *
         * The signaling relay caps each room at 2 peers. A caller that opens one
         * session to detect the role and a second for the handshake would fill the
         * room before the partner connects. This method avoids that by reading the
         * role frame and completing the WebRTC handshake on the same session.
         *
         * Returns `Pair<isHost, Seam>` where `isHost == true` means this peer
         * should run `LiveLeader` (the
         * authoritative host), and `false` means it should run
         * `LiveSessionRuntime` as a
         * client joiner. Used by `LiveDemoModule.wasmJs` to branch the host-leader
         * vs client-joiner paths (ADR-027 §3).
         *
         * Requires [signaling] to be a [WebSocketSignalingChannel].
         *
         * @param handshakeTimeoutMs overall deadline for the WebRTC offer/answer/ICE
         *   exchange. Defaults to [DEFAULT_HANDSHAKE_TIMEOUT_MS] (30 s). On WASM, the
         *   peer tab may take 30–60 s to boot before connecting, so the WASM Quick Play
         *   path passes a larger value (90 s) to avoid timing out before the partner
         *   has had a chance to join the room. Passing a short value in tests enables
         *   fast timeout-failure coverage.
         */
        public suspend fun openWithServerRoleResult(
            config: Pattern = Pattern("quickplay"),
            handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
        ): Pair<Boolean, Seam> {
            val wsChannel =
                signaling as? WebSocketSignalingChannel
                    ?: error("openWithServerRoleResult requires a WebSocketSignalingChannel; got $signaling")
            val (isHost, session) = wsChannel.openWithRole(room)
            val link =
                if (isHost) {
                    val selfId = PeerId(randomToken(config.displayName.ifBlank { "host" }))
                    val facade = facadeFactory.create(iceConfig, hostInitiated = true)
                    HandshakeRunner.runHost(facade, session, handshakeTimeoutMs)
                    buildLink(selfId, facade)
                } else {
                    val selfId = PeerId(randomToken(config.displayName.ifBlank { "peer" }))
                    val facade = facadeFactory.create(iceConfig, hostInitiated = false)
                    HandshakeRunner.runJoiner(facade, session, handshakeTimeoutMs)
                    buildLink(selfId, facade)
                }
            return isHost to link
        }

        private fun randomToken(prefix: String): String =
            buildString {
                append(prefix)
                append('-')
                repeat(8) { append(('a' + Random.nextInt(26))) }
            }
    }
