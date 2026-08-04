@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nw

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The CI-visible reproducer window for the #1618 class of bug — **a peer that silently drops off a
 * `> 2`-peer mesh produces no membership event** — over the [NwLoom] fabric (Network.framework).
 *
 * ### Why this test exists
 *
 * Presence-on-drop was only ever exercised **2-peer over `InMemoryLoom`**
 * ([us.tractat.kuilt.conformance.RoomConformanceSuite]'s partition tests). Every real fabric —
 * `NwLoom` included — only subclasses the *Seam*-level
 * [us.tractat.kuilt.conformance.SeamConformanceSuite] / [us.tractat.kuilt.conformance.MeshConformanceSuite],
 * neither of which ever stands a [us.tractat.kuilt.session.Room] over the fabric and drops a member.
 * So the whole partition-detection wiring — a per-peer heartbeat detector reading the *NW seam's*
 * `incoming`/`peers`/`state`, at **mesh scale (≥3 peers)** — had **zero** automated coverage. #1618
 * was reproducible only on hardware. This test closes that gap for `NwLoom`.
 *
 * ### Virtual time is deliberate (and better than a real-time `withTimeout` here)
 *
 * The [FakeNwRadio] fake is purely event-driven — it consumes **no** wall-clock time and inherits the
 * caller's dispatcher (every `NwSeam` runs on `CoroutineScope(currentCoroutineContext() + SupervisorJob())`).
 * [NwMeshConformanceTest] already drives it under a plain `runTest`. Running this drop test under the
 * same virtual clock makes it **deterministic** — the heartbeat detector's timers advance in lock-step
 * with the injected [clock][SeamRoomFactory] — instead of a flaky real-time race that a contended CI box
 * would make brittle. What this proves is the **Room-over-NW-seam partition wiring at mesh scale**; a true
 * hardware-transport repro of #1618 (an AWDL frame the OS drops without a peer-set change) is a
 * device-only test, out of scope for a fake-radio unit suite.
 *
 * ### The drop model
 *
 * One non-host member's seam is wrapped in a [FaultySeam] and [partitioned][FaultySeam.partition]:
 * frames are dropped **both ways** while the underlying `peers`/`state` stay untouched — a silent
 * Wi-Fi loss, not a transport tear (the exact case the reconnect window exists for). Both the host's
 * and the survivor's heartbeat edge to the dropped peer go quiet; the host's detector must fire
 * [MembershipEvent.Partitioned]. The survivor's own edge to the host is never touched, so it must stay
 * [Liveness.Connected] in the host's roster.
 */
class NwMeshRoomPartitionTest {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
    }

    /** Fast host-side detection so the whole partition arc fits in a small virtual-time budget. */
    private val heartbeat = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 1.seconds,
    )

    @Test
    fun `host sees Partitioned when a non-host peer silently drops off a 3-peer NW mesh`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val radio = FakeNwRadio()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            fun nwLoom(i: Int) =
                NwLoom(
                    FakeNwApi(radio, deviceId = "dev-$i", serviceName = "dev-$i"),
                    serviceType = SERVICE_TYPE,
                    random = Random(i.toLong()),
                )

            val hostFactory = SeamRoomFactory(nwLoom(0), backgroundScope, clock, heartbeat)
            val droppedLoom = nwLoom(1)
            val droppedFactory = SeamRoomFactory(droppedLoom, backgroundScope, clock, heartbeat)
            val bystanderFactory = SeamRoomFactory(nwLoom(2), backgroundScope, clock, heartbeat)

            // Weave concurrently — host()/join() each block until their first peer connects, so a
            // sequential setup would deadlock (peer 0 has no one to connect to yet). Mirrors
            // NwMeshConformanceTest.newMeshOfSize. The dropped peer weaves its NwSeam, wraps it in a
            // FaultySeam, then adopts it (adopt takes an already-woven seam — no second weave).
            val hostD = async { hostFactory.host(Pattern("mesh")) }
            val droppedD = async {
                val seam = droppedLoom.join(InMemoryTag(sessionName = "mesh", peerKey = "dev-1"))
                val faulty = FaultySeam(seam, backgroundScope)
                faulty to droppedFactory.adopt(faulty, SessionRole.Joiner)
            }
            val bystanderD = async { bystanderFactory.join(InMemoryTag(sessionName = "mesh", peerKey = "dev-2")) }

            val hostRoom = hostD.await()
            val (droppedLink, droppedRoom) = droppedD.await()
            val bystanderRoom = bystanderD.await()

            // Admit-handshake convergence: the host admits both joiners; roster-sync Welcomes flood
            // the mesh so every room sees the other two members.
            hostRoom.roster.first { it.size == 2 }
            droppedRoom.roster.first { it.size == 2 }
            bystanderRoom.roster.first { it.size == 2 }

            val droppedId = droppedRoom.selfId
            val bystanderId = bystanderRoom.selfId

            val partitioned = mutableListOf<MembershipEvent.Partitioned>()
            backgroundScope.launch {
                hostRoom.events
                    .filterIsInstance<MembershipEvent.Partitioned>()
                    .collect { if (it.peerId == droppedId) partitioned += it }
            }
            testScheduler.runCurrent()

            // Silent drop: both directions dropped, peer set untouched.
            droppedLink.partition()
            // Past the host's detection timeout, short of its reconnect window — the seat is still held.
            testScheduler.advanceTimeBy(heartbeat.timeout + heartbeat.interval * 4)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        1,
                        partitioned.size,
                        "the host must fire Partitioned for a silently-dropped NW mesh peer " +
                            "(no event here is the #1618 reproduction) — observed $partitioned",
                    )
                },
                {
                    assertIs<Liveness.Partitioned>(
                        hostRoom.roster.value.first { it.id == droppedId }.liveness,
                        "the dropped peer's roster entry must read Partitioned",
                    )
                },
                {
                    assertEquals(
                        Liveness.Connected,
                        hostRoom.roster.value.first { it.id == bystanderId }.liveness,
                        "the survivor's host edge is untouched — it must stay Connected",
                    )
                },
            )

            hostRoom.leave()
            droppedRoom.leave()
            bystanderRoom.leave()
        }
}
