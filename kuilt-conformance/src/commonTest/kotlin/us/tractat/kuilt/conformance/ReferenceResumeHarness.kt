package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeSecret
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * Exercises the real controller and room mapping; only window setup and observation are injected.
 *
 * [ignoreSecret] is the suite's own positive control, never a fixture: it swaps every presented
 * secret for the enrolled one before the real controller sees it — the host that checks only the
 * public fields of a token (#3165) — so a property claiming to catch that host can be shown to red.
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
    val loom = object : Loom {
        override fun capability() = inner.capability()

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = inner.weave(rendezvous)
            return object : Seam by seam {
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
            val enrolled = mutableMapOf<PeerId, ResumeSecret>()
            object : JoinerReconnectController by real {
                override suspend fun enroll(peerId: PeerId): ResumeSecret =
                    real.enroll(peerId).also { enrolled[peerId] = it }

                override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult.HostVerdict {
                    val presented = enrolled[token.peerId]
                        ?.takeIf { ignoreSecret }
                        ?.let { token.copy(secret = it) }
                        ?: token
                    return real.tryResume(presented, at).also { verdicts.add(it) }
                }
            }
        },
    )
    return RoomConformanceSuite.ResumeHarness(
        hostFactory = factory,
        joinerFactory = factory,
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
