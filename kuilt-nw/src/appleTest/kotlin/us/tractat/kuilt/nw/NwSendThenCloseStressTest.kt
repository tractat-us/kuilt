package us.tractat.kuilt.nw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback measurement — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Measures the question #2670 turned on: does a frame handed to [NwSeam.sendTo] immediately before
 * [NwSeam.close] reach the far end over the **real** Network.framework TLS-PSK binding?
 *
 * `connection.h` promises nothing: *"There is no guarantee that any outstanding sends that have not yet
 * delivered send completion handlers will send data before the connection is closed."* So this is a
 * measurement of current behaviour, never a contract, and a loss-free row here does not lift that
 * disclaimer. Loopback is an oracle for the framework and the seam, **not** for the AWDL path a
 * two-device session takes.
 *
 * Opt-in (`-Pconcurrency.stress.tests=true`), since it forms a few hundred real TLS links. It prints one
 * row per wait and asserts only that it ran every trial: the number is the output, not a verdict.
 */
class NwSendThenCloseStressTest {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val ROOM_KEY = "loopback-secret"
        const val TRIALS_PER_ROW = 40
        val ARRIVAL_CEILING: Duration = 1.seconds
    }

    private data class Row(val wait: Duration, val trials: Int, val arrived: Int)

    @Test
    fun aFrameSentJustBeforeCloseOverTheRealRadio() = runBlocking {
        withContext(Dispatchers.Default) {
            val rows = listOf(0.milliseconds, 5.milliseconds, 25.milliseconds, 100.milliseconds)
                .map { wait -> measureRow(wait) }
            rows.forEach { println("nw.send-then-close wait=${it.wait} arrived=${it.arrived}/${it.trials}") }
            assertEquals(List(rows.size) { TRIALS_PER_ROW }, rows.map { it.trials }, "every trial ran")
        }
    }

    private suspend fun measureRow(wait: Duration): Row {
        var arrived = 0
        var trials = 0
        repeat(TRIALS_PER_ROW) { trial ->
            trials += 1
            if (oneTrial(wait, trial)) arrived += 1
        }
        return Row(wait, trials, arrived)
    }

    private suspend fun oneTrial(wait: Duration, trial: Int): Boolean {
        val psk = NwPsk.derive(ROOM_KEY, SERVICE_TYPE)
        val rendezvous = NwLoopbackRendezvous()
        val hostApi = RealNwApi(psk, NwLoopbackConfig(dial = false, rendezvous = rendezvous))
        val joinerApi = RealNwApi(psk, NwLoopbackConfig(dial = true, rendezvous = rendezvous))
        val hostLoom = realDispatch(NwLoom(hostApi, serviceType = SERVICE_TYPE, random = Random(trial)))
        val joinerLoom = realDispatch(NwLoom(joinerApi, serviceType = SERVICE_TYPE, random = Random(trial + 1)))
        val (host, joiner) = coroutineScope {
            val h = async { hostLoom.host(Pattern("host")) }
            val j = async { joinerLoom.join(InMemoryTag(sessionName = "host", peerKey = "nw-loopback-joiner")) }
            h.await() to j.await()
        }
        try {
            return coroutineScope {
                val got = CompletableDeferred<Boolean>()
                val collector = launch {
                    val frame = joiner.incoming.first()
                    got.complete(frame.payload.decodeToString() == "farewell")
                }
                delay(5.milliseconds) // the collector is live before the send, on a real dispatcher
                host.sendTo(joiner.selfId, "farewell".encodeToByteArray())
                if (wait > Duration.ZERO) delay(wait)
                host.close(CloseReason.Normal)
                val seen = withTimeoutOrNull(ARRIVAL_CEILING) { got.await() } ?: false
                collector.cancel()
                seen
            }
        } finally {
            host.close(CloseReason.Normal)
            joiner.close(CloseReason.Normal)
            listOf(hostApi, joinerApi).forEach { api ->
                api.stopListening()
                api.stopBrowsing()
                api.cancelPathMonitor()
            }
        }
    }

    private fun realDispatch(delegate: Loom): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            withContext(Dispatchers.Default) { delegate.weave(rendezvous) }

        override fun capability(): TransportCapability = delegate.capability()
    }
}
