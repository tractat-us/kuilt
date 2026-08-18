package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.random.Random
import kotlin.test.Test

class ZzProbeTest {

    private fun TestScope.seamScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
        repeat(maxPumps) {
            if (cond()) return true
            testScheduler.runCurrent()
        }
        return cond()
    }

    @Test
    fun probe2() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        for ((sa, sb) in listOf(0 to 0, 0 to 3)) {
            val tag = "q$sa$sb"
            val radio = FakeNwRadio()
            val apiH = FakeNwApi(radio, deviceId = "$tag-H", serviceName = "$tag-svc-H")
            val apiJ = FakeNwApi(radio, deviceId = "$tag-J", serviceName = "$tag-svc-J")
            val seamH = NwSeam(PeerId("$tag-peer-H"), apiH, seamScope(), Random(sa.toLong()))
            val seamJ = NwSeam(PeerId("$tag-peer-J"), apiJ, seamScope(), Random(sb.toLong()))
            val atJ = mutableListOf<Swatch>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamH.incoming.collect { } }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamJ.incoming.collect { atJ += it } }
            testScheduler.runCurrent()

            val dd = radio.injectDoubleDial("$tag-H", "$tag-J")
            radio.holdSends(dd.outbound.dialerConnectionId)
            radio.holdSends(dd.outbound.accepterConnectionId)
            val resolved = pumpUntil { seamH.peers.value.size == 2 && seamJ.peers.value.size == 2 }
            val heldHellos = radio.sentFrames.count { it.wasHeld }

            // Freeze H's writes so the window write is IN FLIGHT when the dedup decides.
            radio.holdSends(dd.inbound.accepterConnectionId)
            seamH.broadcast("WINDOWMARK".encodeToByteArray())
            pumpUntil(20) { false }
            val mark = radio.sentFrames.last { it.bytes.decodeToString().contains("WINDOWMARK") }
            val fateInWindow = mark.fate

            radio.releaseSends(dd.outbound.dialerConnectionId)
            radio.releaseSends(dd.outbound.accepterConnectionId)
            pumpUntil(50) { false }
            val fateAfterDedup = mark.fate

            radio.releaseSends(dd.inbound.accepterConnectionId)
            pumpUntil(50) { false }

            println(
                "SEEDS $sa,$sb resolvedBoth=$resolved heldHellos=$heldHellos writeOn=${mark.connectionId.value} " +
                    "inWindow=$fateInWindow afterDedup=$fateAfterDedup final=${mark.fate} " +
                    "outLive=${radio.isLive(dd.outbound.dialerConnectionId)} " +
                    "inLive=${radio.isLive(dd.inbound.dialerConnectionId)} " +
                    "peersH=${seamH.peers.value} stateH=${seamH.state.value} " +
                    "atJ=${atJ.map { it.decodeToString() }}",
            )
        }
    }
}
