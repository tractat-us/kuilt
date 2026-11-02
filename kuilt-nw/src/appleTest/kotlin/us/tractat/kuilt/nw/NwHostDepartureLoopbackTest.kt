package us.tractat.kuilt.nw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback proof — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A host's `leave` over the **real** Network.framework binding (#2670): a real `127.0.0.1` TLS-PSK link,
 * real GCD sockets, real bytes. `NwSendThenCloseStressTest` measures it repeatedly; this is one run, in
 * the normal build.
 *
 * Loopback is a sound oracle for the framework, the TLS layer and the seam, and it is **not** the AWDL
 * path two iPhones take, which is why #2670 stays open until a device run says otherwise. The window
 * here outlives the ceiling on purpose: an arrival inside it is unambiguous only if the fallback could
 * not have produced it.
 */
class NwHostDepartureLoopbackTest {

    @Test
    fun aJoinerLearnsOfTheHostsLeaveOverTheRealRadio() = runBlocking {
        withContext(Dispatchers.Default) {
            val departure = departOverTheRealRadio(seed = 0, ceiling = 30.seconds)
            assertAll(
                { assertEquals<FailureReason?>(FailureReason.HostLeft, departure.reason, "events: ${departure.events}") },
                { assertTrue(departure.events.none { it is MembershipEvent.WindowOpened }, "no window: ${departure.events}") },
            )
        }
    }
}

/** What a joiner saw of one host `leave`: the terminal reason, or null if none came inside the ceiling. */
internal class Departure(val reason: FailureReason?, val events: List<MembershipEvent>)

/** Form a real two-peer room over loopback, have the host leave, and report what the joiner saw. */
internal suspend fun departOverTheRealRadio(seed: Int, ceiling: Duration): Departure {
    val context = currentCoroutineContext()
    val scope = CoroutineScope(context + SupervisorJob(context[Job]))
    val serviceType = "_kuilt._tcp"
    val psk = NwPsk.derive("loopback-secret", serviceType)
    val rendezvous = NwLoopbackRendezvous()
    val hostApi = RealNwApi(psk, NwLoopbackConfig(dial = false, rendezvous = rendezvous))
    val joinerApi = RealNwApi(psk, NwLoopbackConfig(dial = true, rendezvous = rendezvous))
    val heartbeat = HeartbeatConfig(reconnectWindow = 2.minutes)
    val clock = { Clock.System.now() }
    fun loom(api: RealNwApi, offset: Int) = NwLoom(api, serviceType = serviceType, random = Random(seed + offset))
    val hostLoom = loom(hostApi, 0)
    val joinerLoom = loom(joinerApi, 1)
    try {
        val (hostSeam, joinerSeam) = withTimeout(ceiling) {
            coroutineScope {
                val h = async { hostLoom.host(Pattern("host")) }
                val j = async { joinerLoom.join(InMemoryTag("host", peerKey = "nw-loopback-joiner")) }
                h.await() to j.await()
            }
        }
        // Adopted with `reweave = { seam }`, the shape a room out of an election lobby has: NwSeam heals
        // in place, and a second weave on one RealNwApi would throw off `bytesReceived`'s single-collection
        // guard the moment a lost frame sent this joiner into its reconnect loop.
        val host = SeamRoomFactory(hostLoom, scope, clock, heartbeat).adopt(hostSeam, SessionRole.Host)
        val joiner = SeamRoomFactory(joinerLoom, scope, clock, heartbeat)
            .adopt(joinerSeam, SessionRole.Joiner, reweave = { joinerSeam })
        withTimeout(ceiling) { host.roster.first { it.size == 1 } }
        withTimeout(ceiling) { joiner.roster.first { it.size == 1 } }
        val events = mutableListOf<MembershipEvent>()
        val terminal = CompletableDeferred<MembershipEvent.HostLost>()
        val recorder = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            joiner.events.collect { event ->
                events += event
                if (event is MembershipEvent.HostLost) terminal.complete(event)
            }
        }

        host.leave()
        val lost = withTimeoutOrNull(ceiling) { terminal.await() }
        recorder.cancel()
        return Departure(lost?.reason, events.toList())
    } finally {
        listOf(hostApi, joinerApi).forEach { api ->
            api.stopListening()
            api.stopBrowsing()
            api.cancelPathMonitor()
        }
        scope.cancel()
    }
}
