package us.tractat.kuilt.session

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Exercises the real Hello/Goodbye handlers on two threads, without production hooks or sleeps.
 * Each round awaits both handlers before comparing levels; a disagreement is persistent, not a
 * read across an unfinished transition. This JVM stress probe samples interleavings rather than
 * forcing one, and does not establish a failure rate on Native or Wasm.
 */
class AdmissionRosterConcurrencyTest {
    @Test
    fun readmissionRacingGoodbyeKeepsAdmissionAndRosterTogether() = runBlocking {
        // Wall-clock wedge backstop, not the assertion that detects roster divergence.
        withTimeout(2.minutes) {
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val host = PeerId("host")
                val peer = PeerId("peer")
                // At most one Hello/Goodbye pair is queued before awaiting both receipts.
                val inbound = Channel<Swatch>(capacity = 2)
                val admissions = Channel<Unit>(capacity = 1)
                val departures = Channel<Unit>(capacity = 1)
                val seam = object : Seam {
                    override val selfId = host
                    // No heartbeat edge: the test drives admission, not transport liveness.
                    override val peers = MutableStateFlow(setOf(host))
                    override val state = MutableStateFlow<SeamState>(SeamState.Woven)
                    override val incoming = inbound.receiveAsFlow()
                    override suspend fun broadcast(payload: ByteArray) = Unit
                    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                        // The final host introduction occurs once per Hello. A re-admission also
                        // bootstraps the old peer entry, so its own Welcome is not a unique receipt.
                        val message = AdmitMessage.decode(payload)
                        if (message is AdmitMessage.Welcome && message.assignedPeerId == host.value) {
                            admissions.send(Unit)
                        }
                    }
                    override suspend fun close(reason: CloseReason) = Unit
                }
                val room = SeamRoom(
                    seam = seam,
                    role = SessionRole.Host,
                    memberName = "host",
                    scope = scope,
                    clock = { Instant.fromEpochMilliseconds(0) },
                    heartbeatConfig = HeartbeatConfig(),
                )
                val events = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    room.events.collect { if (it is MembershipEvent.Left) departures.send(Unit) }
                }
                val hello = Swatch(
                    AdmitMessage.encode(AdmitMessage.Hello(
                        displayName = "peer",
                        sessionId = "peer",
                        protocolVersion = ProtocolVersion.CURRENT,
                    )), sender = peer,
                )
                val goodbye = Swatch(AdmitMessage.encode(AdmitMessage.Goodbye), sender = peer)
                var rosterDisagreements = 0
                var channelDisagreements = 0
                var completed = 0
                try {
                    room.start()
                    repeat(20_000) {
                        // Establish the departure's precondition, even when the preceding round removed it.
                        inbound.send(hello)
                        admissions.receive()
                        assertTrue(room.isAdmitted(peer), "precondition: Goodbye has an admitted peer")
                        // Hello launches an admission child; Goodbye runs on the inbound collector.
                        // Both receipts precede inspection, so reads cannot straddle an unfinished mutation.
                        inbound.send(hello)
                        inbound.send(goodbye)
                        admissions.receive()
                        departures.receive()
                        val admitted = room.isAdmitted(peer)
                        if (admitted != room.roster.value.any { it.id == peer }) rosterDisagreements++
                        if (admitted != (peer in room.rosterPeers.value)) channelDisagreements++
                        completed++
                    }
                    println("admission-roster: completed=$completed roster=$rosterDisagreements channels=$channelDisagreements")
                    assertAll(
                        { assertEquals(20_000, completed, "every race round ran") },
                        { assertEquals(0, rosterDisagreements, "admission and roster disagree after both handlers completed") },
                        { assertEquals(0, channelDisagreements, "admission and channel peers disagree after both handlers completed") },
                    )
                } finally {
                    events.cancel()
                    room.leave()
                    scope.cancel()
                }
            }
        }
    }
}
