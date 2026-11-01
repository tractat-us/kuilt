package us.tractat.kuilt.nw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback proof — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A host's `leave` over the **real** Network.framework binding (#2670): a real `127.0.0.1` TLS-PSK link,
 * real GCD sockets, real bytes. `NwSendThenCloseStressTest` measures the frame; this measures the room.
 *
 * Loopback is a sound oracle for the framework, the TLS layer and the seam, and it is **not** the AWDL
 * path two iPhones take, which is why #2670 stays open until a device run says otherwise. The window
 * here outlives [DEADLINE] on purpose: an arrival inside it is unambiguous only if the fallback could
 * not have produced it.
 */
class NwHostDepartureLoopbackTest {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val ROOM_KEY = "loopback-secret"
        val DEADLINE = 30.seconds
    }

    @Test
    fun aJoinerLearnsOfTheHostsLeaveOverTheRealRadio() = runBlocking {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val psk = NwPsk.derive(ROOM_KEY, SERVICE_TYPE)
            val rendezvous = NwLoopbackRendezvous()
            val hostApi = RealNwApi(psk, NwLoopbackConfig(dial = false, rendezvous = rendezvous))
            val joinerApi = RealNwApi(psk, NwLoopbackConfig(dial = true, rendezvous = rendezvous))
            val heartbeat = HeartbeatConfig(reconnectWindow = 2.minutes)
            val clock = { Clock.System.now() }
            fun factory(api: RealNwApi, seed: Int) =
                SeamRoomFactory(NwLoom(api, serviceType = SERVICE_TYPE, random = Random(seed)), scope, clock, heartbeat)
            try {
                val (host, joiner) = coroutineScope {
                    val h = async { factory(hostApi, 0).host(Pattern("host")) }
                    val j = async { factory(joinerApi, 1).join(InMemoryTag("host", peerKey = "nw-loopback-joiner")) }
                    h.await() to j.await()
                }
                withTimeout(DEADLINE) { host.roster.first { it.size == 1 } }
                withTimeout(DEADLINE) { joiner.roster.first { it.size == 1 } }
                val events = mutableListOf<MembershipEvent>()
                val terminal = CompletableDeferred<MembershipEvent.HostLost>()
                val recorder = scope.launch {
                    joiner.events.collect { event ->
                        events += event
                        if (event is MembershipEvent.HostLost) terminal.complete(event)
                    }
                }

                host.leave()
                val lost = withTimeout(DEADLINE) { terminal.await() }
                recorder.cancel()

                assertAll(
                    { assertEquals<FailureReason>(FailureReason.HostLeft, lost.reason, "events: $events") },
                    { assertTrue(events.none { it is MembershipEvent.WindowOpened }, "no window: $events") },
                )
            } finally {
                listOf(hostApi, joinerApi).forEach { api ->
                    api.stopListening()
                    api.stopBrowsing()
                    api.cancelPathMonitor()
                }
                scope.cancel()
            }
        }
    }
}
