@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Runs the cookbook's [returnIsAutomaticSample] for what it claims: an app killed and launched again
 * gets its seat back by doing exactly what it did the first time, over the same key store.
 *
 * Two processes of one device, each on a client loom of its own and each presenting its own peer id,
 * as an app relaunched on most fabrics does. The host holds the first process's seat when the second
 * arrives, so it hands that seat to the new id (`Superseded`, then `Joined`) rather than seating a
 * stranger. [whoCameBackSample] watches the host's events throughout.
 */
class ReturnIsAutomaticSampleTest {

    @Test
    fun anAppKilledAndLaunchedAgainGetsItsSeatBack() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = Rig(this)
        val host = SeamRoomFactory(
            loom = rig.server,
            scope = backgroundScope,
            clock = virtualClock(),
            deviceKeyStore = fakeKeyStore(HOST_DEVICE),
        ).host(Pattern(SESSION), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))
        val hostLog = EventLog(host, backgroundScope)
        backgroundScope.launch { whoCameBackSample(host) }
        val store = fakeKeyStore(1)

        val first = rig.process(PeerId("ada-1"))
        val firstRoom = returnIsAutomaticSample(first.loom, first.scope, virtualClock(), store, InMemoryTag(SESSION))
        runCurrent()
        val key = host.roster.value.single { it.id == firstRoom.selfId }.key

        first.kill()
        runCurrent()
        assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == firstRoom.selfId }.liveness, "rig: the host holds the killed process's seat")

        val mark = hostLog.mark()
        val second = rig.process(PeerId("ada-2"))
        val secondRoom = returnIsAutomaticSample(second.loom, second.scope, virtualClock(), store, InMemoryTag(SESSION))
        runCurrent()

        assertAll(
            { assertEquals(listOf(secondRoom.selfId), host.roster.value.map { it.id }, "one seat, under the new id") },
            { assertEquals(key, host.roster.value.single().key, "the same device's key") },
            {
                assertEquals(
                    listOf("Left(ada-1, ${LeaveReason.Superseded(secondRoom.selfId)})", "Joined(ada-2)"),
                    hostLog.edgesSince(mark).map { it.describe() },
                    "the old id is superseded by the new one, not let go and replaced by a stranger",
                )
            },
        )
        secondRoom.leave()
        host.leave()
    }

    /** A relay hub, and per process a client loom over a link of its own. */
    private class Rig(private val test: TestScope) {
        private val dispatcher = requireNotNull(test.coroutineContext[ContinuationInterceptor])
        private val source = InMemoryConnectionSource()
        val server = MuxServerLoom(source, test.backgroundScope, PeerId("host"), RoomAuthorizer.AllowAll, dispatcher, Random(13))
        private var seed = 1

        inner class Process(id: PeerId) {
            val scope = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
            private val base = object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam {
                    val (serverEnd, clientEnd) = connectionPair()
                    source.offer(serverEnd)
                    return hubMesh(id, listOf(clientEnd), dispatcher, Random(seed++))
                }
            }
            val loom = MuxClientLoom(base, Rendezvous.New(Pattern("base")), scope) { SESSION }

            /** The app dies: its rooms stop, and the system closes its socket. */
            suspend fun kill() {
                scope.cancel()
                loom.closeBase()
            }
        }

        fun process(id: PeerId) = Process(id)
    }

    private companion object {
        const val SESSION = "table"
        const val HOST_DEVICE = 0
    }
}
