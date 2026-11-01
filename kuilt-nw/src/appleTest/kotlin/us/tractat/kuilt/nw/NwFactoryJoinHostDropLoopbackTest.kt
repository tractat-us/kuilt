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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A joiner built with `SeamRoomFactory.join` over the **real** Network.framework binding, whose host link
 * drops with no warning (#3031): a real `127.0.0.1` TLS-PSK link, real GCD sockets, real bytes.
 *
 * The drop is the host's binding closing the socket, which is what a lost `Farewell` or a real radio drop
 * looks like from the joiner's side: the room above the host's seam is told nothing, and no frame says
 * goodbye. The joiner then re-weaves through the factory's `reweave = { loom.join(tag) }`.
 *
 * **The failure this pins is a dead process, not a red assertion.** A second `NwSeam` over one `RealNwApi`
 * collects `bytesReceived` twice; the binding throws by design, the throw escapes into a scope with no
 * handler, and Kotlin/Native aborts. `FakeNwApi` now enforces the same rule, which is what
 * `NwRejoinTest` leans on, but only this test crosses the binding that produced the abort.
 *
 * Loopback is a sound oracle for the framework, the TLS layer and the seam, and it is **not** the AWDL path
 * two iPhones take. It shows the second weave no longer happens and that a real link heals in place; it says
 * nothing about how long AWDL takes to come back.
 */
class NwFactoryJoinHostDropLoopbackTest {

    @Test
    fun aFactoryBuiltJoinerHealsAfterItsHostLinkDropsOverTheRealRadio() = runBlocking {
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val scope = CoroutineScope(context + SupervisorJob(context[Job]))
            val serviceType = "_kuilt._tcp"
            val psk = NwPsk.derive("loopback-secret", serviceType)
            val rendezvous = NwLoopbackRendezvous()
            val hostApi = RealNwApi(psk, NwLoopbackConfig(dial = false, rendezvous = rendezvous))
            val joinerApi = RealNwApi(psk, NwLoopbackConfig(dial = true, rendezvous = rendezvous))
            val heartbeat = HeartbeatConfig(reconnectWindow = 2.minutes)
            val clock = { Clock.System.now() }
            val ceiling = 60.seconds
            try {
                val hostFactory = SeamRoomFactory(
                    NwLoom(hostApi, serviceType = serviceType, random = Random(0)), scope, clock, heartbeat,
                )
                val joinerFactory = SeamRoomFactory(
                    NwLoom(joinerApi, serviceType = serviceType, random = Random(1)), scope, clock, heartbeat,
                )
                val (host, joiner) = coroutineScope {
                    val h = async { hostFactory.host(Pattern("host")) }
                    val j = async { joinerFactory.join(InMemoryTag("host", peerKey = "nw-loopback-joiner")) }
                    h.await() to j.await()
                }
                withTimeout(ceiling) { host.roster.first { it.size == 1 } }
                withTimeout(ceiling) { joiner.roster.first { it.size == 1 } }

                val events = mutableListOf<MembershipEvent>()
                val resumed = CompletableDeferred<MembershipEvent.Resumed>()
                val hostLost = CompletableDeferred<MembershipEvent.HostLost>()
                val recorder = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    joiner.events.collect { event ->
                        events += event
                        if (event is MembershipEvent.Resumed) resumed.complete(event)
                        if (event is MembershipEvent.HostLost) hostLost.complete(event)
                    }
                }
                // The events flow does not replay: the recorder has to be subscribed before the drop lands.
                delay(50)

                val links = hostApi.connectionStates.value.filterValues { it !is NwConnState.Closed }.keys
                assertTrue(links.isNotEmpty(), "the rig must have a live link to drop")
                links.forEach { hostApi.disconnect(it) }

                withTimeout(ceiling) { resumed.await() }

                // A frame across the healed link: `Resumed` says the host answered, this says bytes move.
                val payload = byteArrayOf(1, 2, 3)
                val frame = async(start = CoroutineStart.UNDISPATCHED) { joiner.incoming.first() }
                delay(50)
                host.broadcast(payload)
                val received = withTimeout(ceiling) { frame.await() }

                recorder.cancel()
                assertAll(
                    { assertContentEquals(payload, received.bytes, "events: $events") },
                    { assertTrue(!hostLost.isCompleted, "the seat survived the drop: $events") },
                    { assertTrue(events.any { it is MembershipEvent.WindowOpened }, "the drop was seen: $events") },
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
