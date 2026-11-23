@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultySeam
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * The reference return rig (#3159, Task 1.7): a host and a joiner on their own devices over one
 * [InMemoryLoom], with every joiner seam wrapped in a [FaultySeam].
 *
 * [RoomConformanceSuite.ResumeHarness.releaseSeat] makes the host give the seat up for real: the
 * joiner's **outbound** traffic is dropped, so the host's detector hears nothing from it, partitions
 * the seat and lets its window expire, while the joiner keeps hearing the host and stays in session;
 * then the path heals. Nothing here touches the host's controller or roster directly, so the
 * properties' own check of the host roster is an independent observation.
 *
 * The counters read the joiner's sends on its seam, before any fault, so a send that fails still
 * counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun referenceResumeHarness(
    scope: TestScope,
    heartbeatConfig: HeartbeatConfig,
): RoomConformanceSuite.ResumeHarness {
    val inner = InMemoryLoom()
    var hellos = 0
    var resumes = 0
    val joinerSeams = mutableMapOf<PeerId, FaultySeam>()
    var hostRoom: Room? = null

    fun count(payload: ByteArray) {
        when (AdmitMessage.decode(payload)) {
            is AdmitMessage.Hello -> hellos++
            is AdmitMessage.Resume -> resumes++
            else -> Unit
        }
    }
    val loom = object : Loom {
        override fun capability() = inner.capability()

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = inner.weave(rendezvous)
            if (rendezvous is Rendezvous.New) return seam
            val counted = object : Seam by seam {
                override suspend fun broadcast(payload: ByteArray) {
                    count(payload)
                    seam.broadcast(payload)
                }

                override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                    count(payload)
                    seam.sendTo(peer, payload)
                }
            }
            return FaultySeam(counted, scope.backgroundScope).also { joinerSeams[it.selfId] = it }
        }
    }
    val clock = { Instant.fromEpochMilliseconds(scope.testScheduler.currentTime) }
    val hostDevice = SeamRoomFactory(loom = loom, scope = scope.backgroundScope, clock = clock, deviceKeyStore = fakeKeyStore(0), heartbeatConfig = heartbeatConfig)
    // The joiner is another device, with a key store of its own: one store shared with the host would
    // make the joiner the host's second tab, which the key-to-seat table gives a seat of its own.
    val joinerDevice = SeamRoomFactory(loom = loom, scope = scope.backgroundScope, clock = clock, deviceKeyStore = fakeKeyStore(1), heartbeatConfig = heartbeatConfig)
    val hostFactory = object : RoomFactory {
        override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room =
            hostDevice.host(pattern, memberName, roomId).also { hostRoom = it }

        override suspend fun join(tag: Tag, memberName: String?, deviceId: String?): Room =
            hostDevice.join(tag, memberName, deviceId)
    }
    return RoomConformanceSuite.ResumeHarness(
        hostFactory = hostFactory,
        joinerFactory = joinerDevice,
        releaseSeat = { joiner ->
            val host = assertNotNull(hostRoom, "the rig's host must be built before a seat is released")
            val seam = assertNotNull(joinerSeams[joiner], "the rig built no seam for ${joiner.value}")
            seam.setFaultProfile(FaultProfile.DropAll(Direction.Outbound))
            // Past the host's timeout and its whole window, in heartbeat steps, until the seat is gone.
            val budget = heartbeatConfig.timeout + heartbeatConfig.reconnectWindow + heartbeatConfig.interval * 4
            var waited = kotlin.time.Duration.ZERO
            while (waited < budget && host.roster.value.any { it.id == joiner }) {
                scope.testScheduler.advanceTimeBy(heartbeatConfig.interval)
                scope.runCurrent()
                waited += heartbeatConfig.interval
            }
            seam.heal()
            scope.runCurrent()
        },
        helloRequests = { hellos },
        resumeRequests = { resumes },
    )
}
