package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * Exercises the real controller and room mapping; only window setup and observation are injected.
 *
 * A host verdict comes from one of two places, and the rig observes both without asking the room
 * what it decided. The window controller renders the ones it is asked for, recorded at
 * [JoinerReconnectController.tryResume]. The room renders the refusals it can decide from its own
 * state before any controller is asked (#3165) — no secret, another room's id, a peer that is not a
 * member — and those the rig classifies itself from the `Resume` frame as it reaches the host,
 * against the host's own room id and roster.
 *
 * [ignoreSecret] is the suite's own positive control, never a fixture: every joiner room it hands
 * out replaces the secret of any token it is asked to present with its own real one — so a forgery
 * arrives at the host indistinguishable from the member, which is what a room that does not check
 * the secret (#3165) amounts to — and a property claiming to catch that room can be shown to red.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun referenceResumeHarness(
    scope: TestScope,
    heartbeatConfig: HeartbeatConfig,
    ignoreSecret: Boolean = false,
): RoomConformanceSuite.ResumeHarness {
    val inner = InMemoryLoom()
    val verdicts = mutableListOf<ResumeResult.HostVerdict>()
    var requests = 0
    var controller: DefaultJoinerReconnectController? = null
    var hostRoom: Room? = null

    /** The refusal the room decides for itself before the controller, or null when it asks it. */
    fun roomLevelVerdict(resume: AdmitMessage.Resume): ResumeResult.HostVerdict? {
        val room = hostRoom ?: return null
        return when {
            resume.resumeSecret == null -> ResumeResult.TokenInvalid("missing-secret")
            resume.tokenRoomId != room.roomId.value?.value -> ResumeResult.TokenInvalid("session-mismatch")
            room.roster.value.none { it.id.value == resume.tokenPeerId } -> ResumeResult.WindowClosed
            else -> null
        }
    }
    val loom = object : Loom {
        override fun capability() = inner.capability()

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = inner.weave(rendezvous)
            val isHost = rendezvous is Rendezvous.New
            return object : Seam by seam {
                override val incoming: Flow<Swatch> =
                    if (!isHost) {
                        seam.incoming
                    } else {
                        seam.incoming.onEach { swatch ->
                            val resume = AdmitMessage.decode(swatch.toByteArray()) as? AdmitMessage.Resume
                            resume?.let(::roomLevelVerdict)?.let { verdicts.add(it) }
                        }
                    }

                override suspend fun broadcast(payload: ByteArray) {
                    if (AdmitMessage.decode(payload) is AdmitMessage.Resume) requests++
                    seam.broadcast(payload)
                }

                override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                    if (AdmitMessage.decode(payload) is AdmitMessage.Resume) requests++
                    seam.sendTo(peer, payload)
                }
            }
        }
    }
    val factory = SeamRoomFactory(
        loom = loom,
        scope = scope.backgroundScope,
        clock = { Instant.fromEpochMilliseconds(scope.testScheduler.currentTime) },
        heartbeatConfig = heartbeatConfig,
        reconnectControllerFactory = { roomId, controllerScope, clock ->
            val real = DefaultJoinerReconnectController(
                roomId = roomId,
                reconnectWindowMs = heartbeatConfig.reconnectWindow.inWholeMilliseconds,
                clock = { clock().toEpochMilliseconds() },
                scope = controllerScope,
            )
            controller = real
            object : JoinerReconnectController by real {
                override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult.HostVerdict =
                    real.tryResume(token, at).also { verdicts.add(it) }
            }
        },
    )
    val hostFactory = object : RoomFactory {
        override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room =
            factory.host(pattern, memberName, roomId).also { hostRoom = it }

        override suspend fun join(tag: Tag, memberName: String?, deviceId: String?): Room =
            factory.join(tag, memberName, deviceId)
    }
    val joinerFactory = if (!ignoreSecret) factory else object : RoomFactory {
        override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room =
            factory.host(pattern, memberName, roomId)

        override suspend fun join(tag: Tag, memberName: String?, deviceId: String?): Room {
            val room = factory.join(tag, memberName, deviceId)
            return object : Room by room {
                override suspend fun resume(token: ResumeToken): ResumeResult.JoinerOutcome =
                    room.resume(room.resumeToken?.let { token.copy(secret = it.secret) } ?: token)
            }
        }
    }
    return RoomConformanceSuite.ResumeHarness(
        hostFactory = hostFactory,
        joinerFactory = joinerFactory,
        prepare = { token, verdict ->
            val real = assertNotNull(controller, "the host must install the observed controller")
            when (verdict) {
                ResumeResult.Success, ResumeResult.WindowClosed -> {
                    real.onPeerUnresponsive(token.peerId, scope.testScheduler.currentTime)
                    scope.runCurrent()
                    if (verdict == ResumeResult.WindowClosed) {
                        real.expire(token.peerId, scope.testScheduler.currentTime)
                        scope.runCurrent()
                    }
                    token
                }
                ResumeResult.WindowNotYetOpen -> token
                is ResumeResult.TokenInvalid -> token.copy(roomId = RoomId("${token.roomId.value}-foreign"))
            }
        },
        hostVerdicts = { verdicts.toList() },
        resumeRequests = { requests },
    )
}
