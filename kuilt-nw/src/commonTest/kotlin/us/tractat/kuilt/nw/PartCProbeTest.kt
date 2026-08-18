@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.SeamState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MEASUREMENT PROBE for #2448 Part C — throwaway, not a landing candidate.
 *
 * After a send-failure eviction the seam re-forms to Weaving (#1513), so the Torn guard in
 * `sendTo` does not fire and the registry miss falls through to `PeerNotConnected` — blaming
 * the addressee for the link's own death.
 */
class PartCProbeTest {

    @Test
    fun sendToAfterLinkDeathBlamesTheAddressee() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, backgroundScope, Random(0))
        val seamB = NwSeam(PeerId("peer-1"), apiB, backgroundScope, Random(1))
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        repeat(200) { testScheduler.runCurrent() }
        assertEquals(2, seamA.peers.value.size, "precondition: A and B are woven")

        // Kill the LINK (not the peer): A's next send fails, evicting the only remote.
        apiA.failSend = true
        seamA.broadcast("boom".encodeToByteArray())
        repeat(200) { testScheduler.runCurrent() }

        assertTrue(
            seamA.state.value is SeamState.Weaving,
            "precondition (#1513): the seam RE-FORMS to Weaving, it does not tear — was ${seamA.state.value}",
        )

        val thrown = assertFailsWith<PeerNotConnected> {
            seamA.sendTo(seamB.selfId, byteArrayOf(1))
        }
        println("PARTC-PROBE state=${seamA.state.value} threw=${thrown::class.simpleName} : ${thrown.message}")
    }
}
