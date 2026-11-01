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
        const val TRIALS_PER_ROW = 20
        val FAREWELL = "farewell".encodeToByteArray()
        val ARRIVAL_CEILING: Duration = 1.seconds
        const val ROOM_TRIALS = 40

        /** Long past a healthy arrival, short of the 2-minute window: a miss reads as `no-terminal`. */
        val ROOM_CEILING: Duration = 5.seconds
    }

    /** One measured condition: [cargoBytes] handed to the seam ahead of the small frame, then [wait], then close. */
    private data class Row(val cargoBytes: Int, val wait: Duration, val trials: Int, val arrived: Int)

    @Test
    fun aFrameSentJustBeforeCloseOverTheRealRadio() = runBlocking {
        withContext(Dispatchers.Default) {
            val rows = listOf(
                0 to 0.milliseconds,
                0 to 5.milliseconds,
                0 to 25.milliseconds,
                0 to 100.milliseconds,
                64 * 1024 to 0.milliseconds,
                64 * 1024 to 100.milliseconds,
                1024 * 1024 to 0.milliseconds,
                1024 * 1024 to 100.milliseconds,
                8 * 1024 * 1024 to 0.milliseconds, // the control: a rig that cannot lose a frame reds nothing
                8 * 1024 * 1024 to 100.milliseconds,
            ).map { (cargo, wait) -> measureRow(cargo, wait) }
            rows.forEach {
                println("nw.send-then-close cargo=${it.cargoBytes} wait=${it.wait} arrived=${it.arrived}/${it.trials}")
            }
            assertEquals(List(rows.size) { TRIALS_PER_ROW }, rows.map { it.trials }, "every trial ran")
        }
    }

    /** The whole of it, room to room: how often a host's `leave` reaches the joiner as `HostLeft`. */
    @Test
    fun aHostsLeaveOverTheRealRadio() = runBlocking {
        withContext(Dispatchers.Default) {
            var ran = 0
            val reasons = List(ROOM_TRIALS) { trial ->
                ran += 1
                departOverTheRealRadio(seed = trial, ceiling = ROOM_CEILING).reason
            }
            println("nw.host-departure ${reasons.groupingBy { it?.toString() ?: "no-terminal" }.eachCount()} of $ran")
            assertEquals(ROOM_TRIALS, ran, "every trial ran")
        }
    }

    private suspend fun measureRow(cargoBytes: Int, wait: Duration): Row {
        var arrived = 0
        var trials = 0
        repeat(TRIALS_PER_ROW) { trial ->
            trials += 1
            if (oneTrial(cargoBytes, wait, trial)) arrived += 1
        }
        return Row(cargoBytes, wait, trials, arrived)
    }

    private suspend fun oneTrial(cargoBytes: Int, wait: Duration, trial: Int): Boolean {
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
                    joiner.incoming.first { it.toByteArray().size == FAREWELL.size }
                    got.complete(true)
                }
                delay(5.milliseconds) // the collector is live before the send, on a real dispatcher
                if (cargoBytes > 0) host.sendTo(joiner.selfId, ByteArray(cargoBytes))
                host.sendTo(joiner.selfId, FAREWELL)
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
