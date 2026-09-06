package us.tractat.kuilt.webrtc

import us.tractat.kuilt.webrtc.internal.BrowserRtcFacadeFactory
import us.tractat.kuilt.webrtc.internal.DEFAULT_HANDSHAKE_TIMEOUT_MS
import us.tractat.kuilt.webrtc.internal.HandshakeRunner
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacade
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacadeFactory
import us.tractat.kuilt.webrtc.internal.WebRTCPeerLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.TransportCapability
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
 * Wasm-only at launch.
 *
 * ## Where this stands against the universal factory convention (#1430)
 *
 * This is a [Loom] with a **public** constructor, so the convention's shared knobs do apply to it.
 * The epic's survey table lists this fabric as having no identity, policy, timeout *or* dispatcher
 * wiring, and its option (a) states outright that "WebRTC has no timeout wiring"; three of those
 * four entries are wrong, so what is actually here is recorded below.
 *
 * - **`selfId` — genuinely exempt.** Identity is minted per weave inside `connect`, from the
 *   rendezvous's own `sessionName` (`"<displayName>-<8 random letters>"`), and that prefix is what
 *   labels this peer at the far end once the id frame crosses the data channel. A loom-level
 *   `selfId` would either be overwritten per weave — the silently-ignored argument the convention
 *   exists to prevent — or would displace the display-name labelling this fabric's [Loom.join]
 *   contract promises. Same conclusion as `InMemoryLoom` and `NearbyLoom`, for the same reason.
 *   Test determinism already has its seam here: the `random` parameter.
 * - **`policy` — real wiring, unreachable.** `buildLink` takes a [DeliveryPolicy] and feeds it to
 *   the user-payload [Spool], but its single call site passes nothing, so every seam this factory
 *   weaves is pinned to [DeliveryPolicy.Reliable] whatever the consumer asked for — the same
 *   defect `:kuilt-nearby` found and fixed. The `handshaking` barrier that blocked the knob on
 *   `:kuilt-tcp` is not in the way here: this fabric builds its seam directly.
 * - **`weaveTimeout` — real wiring, unreachable, and the gap is user-visible.** Apply the
 *   discriminator the `:kuilt-nearby` slice established — *what does the clock start and stop
 *   on?* — and this fabric reaches the **opposite** verdict from Nearby's. `HandshakeRunner` wraps
 *   the data-channel wait in a `withTimeout`, and the wait for the far peer to appear is *inside*
 *   it: a weave nobody ever joins fails at 30 s rather than hanging. That is exactly the bound
 *   [us.tractat.kuilt.core.LoomDefaults.WEAVE_TIMEOUT] describes, and the value already matches;
 *   only the relay dial (`signaling.open(room)`) sits outside the clock. But [weave] calls
 *   `connect` without a timeout, so it always takes the default, and the one public surface that
 *   can widen it is [openWithServerRoleResult] — which additionally requires a
 *   [WebSocketSignalingChannel] and server-assigned roles. So a consumer arriving through the
 *   plain [Loom] contract cannot change it, even though `DEFAULT_HANDSHAKE_TIMEOUT_MS`'s own
 *   documentation says two WASM tabs may stagger by 30–60 s during bundle load and that such
 *   callers *should* pass a larger value.
 * - **`dispatcher` — omitted, following the convention as amended.** The 2026-07-13 second review
 *   dropped `dispatcher` from the universal set: new fabrics inherit caller context at weave time
 *   and dispatcher params stay fabric-specific, which is why `:kuilt-nearby` omits it too.
 *   [WebRTCPeerLink] does take one for its internal scope and this factory constructs it without
 *   one, pinning it to [kotlinx.coroutines.Dispatchers.Default] — worth knowing, but not a knob
 *   this convention asks for.
 *
 * Closing the two reachability gaps means adding parameters to a public constructor, so it is left
 * as a follow-up rather than folded in here.
 */
public class WebRTCPeerLinkFactory
    internal constructor(
        private val signaling: SignalingChannel,
        private val room: String,
        private val iceConfig: IceConfig = IceConfig.DefaultStun,
        private val facadeFactory: RtcPeerConnectionFacadeFactory,
        private val random: Random = Random.Default,
    ) : Loom {
        /**
         * The fabric's **static** self-report: what a WebRTC data channel can do on this runtime.
         * Deliberately not the live signal — that is a woven seam's
         * [Seam.capability], driven from the peer connection's ICE state (#1544).
         */
        override fun capability(): TransportCapability =
            TransportCapability(roles = WEBRTC_ROLES, availability = FabricAvailability.Available)

        public constructor(
            signaling: SignalingChannel,
            room: String,
            iceConfig: IceConfig = IceConfig.DefaultStun,
            random: Random = Random.Default,
        ) : this(signaling, room, iceConfig, BrowserRtcFacadeFactory(), random)

        override suspend fun weave(rendezvous: Rendezvous): Seam =
            when (rendezvous) {
                is Rendezvous.New ->
                    connect(isHost = true, displayName = rendezvous.pattern.sessionName, session = signaling.open(room))
                is Rendezvous.Existing ->
                    connect(isHost = false, displayName = rendezvous.tag.sessionName, session = signaling.open(room))
            }

        /**
         * Mint a [PeerId], create the facade for this [isHost] role, run the WebRTC
         * offer/answer/ICE handshake over the already-opened [session], then build the
         * peer link. The session source differs by caller — [weave] opens a fresh
         * `signaling.open(room)` per branch, while [openWithServerRoleResult] reuses the
         * single role-assigning session — so it is acquired by the caller, not here.
         */
        private suspend fun connect(
            isHost: Boolean,
            displayName: String,
            session: SignalingSession,
            handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
        ): Seam {
            val selfId = PeerId(randomToken(displayName.ifBlank { if (isHost) "host" else "peer" }))
            val facade = facadeFactory.create(iceConfig, hostInitiated = isHost)
            if (isHost) {
                HandshakeRunner.runHost(facade, session, handshakeTimeoutMs)
            } else {
                HandshakeRunner.runJoiner(facade, session, handshakeTimeoutMs)
            }
            return buildLink(selfId, facade)
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
            policy: DeliveryPolicy = DeliveryPolicy.Reliable,
        ): WebRTCPeerLink {
            val guessedRemoteId = PeerId(randomToken("peer"))
            facade.sendBytes(selfId.value.encodeToByteArray())

            val senderIdDeferred = CompletableDeferred<PeerId>()
            val userSpool = Spool<ByteArray>(policy)
            // Construct the link first so its scope exists, then launch the demux on
            // that scope — no orphan CoroutineScope; the demux dies when the link closes.
            val link = WebRTCPeerLink(selfId, guessedRemoteId, facade, userSpool.incoming, senderIdDeferred)
            link.scope.launch {
                var idReceived = false
                facade.incomingBytes.collect { bytes ->
                    if (!idReceived) {
                        idReceived = true
                        senderIdDeferred.complete(PeerId(bytes.decodeToString()))
                    } else {
                        userSpool.deliver(bytes)
                    }
                }
                userSpool.close()
            }
            // Return immediately — senderIdDeferred resolves in the background when the
            // remote's ID frame arrives. Seam.incoming awaits it lazily per frame.
            return link
        }

        /**
         * Open a peer link using server-assigned role assignment.
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
         * Use this instead of [open]/[join] for symmetric peers (e.g. a peer-to-peer
         * session) where neither tab knows in advance which role it should take.
         *
         * Prefer [openWithServerRoleResult] when the caller needs both the assigned
         * role and the link — it avoids a second signaling session.
         */
        public suspend fun openWithServerRole(config: Pattern): Seam = openWithServerRoleResult(config).second

        /**
         * Like [openWithServerRole] but surfaces the relay-assigned role alongside
         * the established [Seam], using a **single** signaling session.
         *
         * The signaling relay caps each room at 2 peers. A caller that opens one
         * session to detect the role and a second for the handshake would fill the
         * room before the partner connects. This method avoids that by reading the
         * role frame and completing the WebRTC handshake on the same session.
         *
         * Returns `Pair<isHost, Seam>` where `isHost == true` means this peer took
         * the host role and `false` means it took the joiner role. The caller is
         * responsible for branching behaviour accordingly.
         *
         * Requires [signaling] to be a [WebSocketSignalingChannel].
         *
         * @param handshakeTimeoutMs overall deadline for the WebRTC offer/answer/ICE
         *   exchange. Defaults to [DEFAULT_HANDSHAKE_TIMEOUT_MS] (30 s). Increase for
         *   environments where the peer tab may take longer to boot before joining the
         *   room (e.g. 90 s on a slow connection). Passing a short value in tests
         *   enables fast timeout-failure coverage.
         */
        public suspend fun openWithServerRoleResult(
            config: Pattern,
            handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
        ): Pair<Boolean, Seam> {
            val wsChannel =
                signaling as? WebSocketSignalingChannel
                    ?: error("openWithServerRoleResult requires a WebSocketSignalingChannel; got $signaling")
            val (isHost, session) = wsChannel.openWithRole(room)
            val link = connect(isHost, config.sessionName, session, handshakeTimeoutMs)
            return isHost to link
        }

        private fun randomToken(prefix: String): String =
            buildString {
                append(prefix)
                append('-')
                repeat(8) { append(('a' + random.nextInt(26))) }
            }
    }
