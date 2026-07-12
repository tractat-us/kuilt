@file:Suppress("ForbiddenImport", "ForbiddenMethodCall") // real-network loopback drain proof — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here

package us.tractat.kuilt.nw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Proves the load-bearing part of [RealNwApi] — the strong-ref connection registry — actually
 * **drains to empty** when the seams close, i.e. no `nw_connection_t` is leaked.
 *
 * Network.framework cancels any connection whose last strong reference drops, so [RealNwApi] holds
 * every live connection in its `connections` map; the SINGLE ref-drop site is `closeConnection`,
 * driven by a connection's `cancelled`/`failed` state. `NwSeam.close()` disconnects each connection
 * (cancel → `cancelled` state → drop). This test forms a real `127.0.0.1` TLS-PSK link over the
 * proven, flake-free loopback harness (reusing [NwLoopbackConformanceTest]'s rendezvous +
 * real-dispatcher weave), asserts the registry actually held the live connection, closes both seams,
 * and awaits the registry draining back to 0 with a **hard real-time ceiling** — a leak manifests as
 * a drain that never completes, and MUST fail fast, not hang.
 *
 * Deterministic and CI-safe. The opt-in, multi-threaded, high-VOLUME concurrent open/close stress
 * probe is a tracked follow-up, NOT this test.
 *
 * ## Real dispatcher (not virtual time)
 * [NwLoom.weave] captures its seam scope from `currentCoroutineContext()`, so under a `runTest`
 * virtual clock its `withTimeout` would fast-forward past the real GCD socket connect. [realDispatch]
 * wraps each loom so `weave` runs on a real [Dispatchers.Default]; the whole test runs under
 * [runBlocking] in real wall-clock time.
 */
class NwConnectionDrainTest {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val ROOM_KEY = "loopback-secret"

        /** Hard real-time ceiling on the drain: a leak = a drain that never completes ⇒ fail fast. */
        val DRAIN_CEILING = 10.seconds

        /** Poll cadence while awaiting the drain — bounded busy-wait, never a bare fixed sleep. */
        val POLL_INTERVAL = 20.milliseconds
    }

    /** The real APIs built for the test, torn down (listeners/browsers cancelled) in [tearDown]. */
    private val apis = mutableListOf<RealNwApi>()

    @AfterTest
    fun tearDown() = runBlocking {
        // Cancel the loopback listeners/browsers so no NW resources leak across the run; the seams
        // are closed by the test body itself.
        apis.forEach { api ->
            api.stopListening()
            api.stopBrowsing()
        }
        apis.clear()
    }

    @Test
    fun registryDrainsToEmptyOnSeamClose() = runBlocking {
        val psk = NwPsk.derive(ROOM_KEY, SERVICE_TYPE)
        // One shared rendezvous: the host publishes its real bound port, the joiner awaits it. Hold
        // direct references to the two RealNwApi instances so we can read liveConnectionCount().
        val rendezvous = NwLoopbackRendezvous()
        val hostApi = RealNwApi(psk, NwLoopbackConfig(dial = false, rendezvous = rendezvous))
        val joinerApi = RealNwApi(psk, NwLoopbackConfig(dial = true, rendezvous = rendezvous))
        apis += hostApi
        apis += joinerApi
        val hostLoom = realDispatch(NwLoom(hostApi, serviceType = SERVICE_TYPE, random = Random(0)))
        val joinerLoom = realDispatch(NwLoom(joinerApi, serviceType = SERVICE_TYPE, random = Random(1)))

        // Weave both concurrently — the host awaits the joiner's dial, the joiner awaits the host's
        // published port; each side's weave suspends until the link forms.
        val (hostSeam, joinerSeam) = coroutineScope {
            val host = async { hostLoom.host(Pattern("host")) }
            val joiner = async { joinerLoom.join(InMemoryTag(sessionName = "host", peerKey = "nw-loopback-joiner")) }
            host.await() to joiner.await()
        }

        // Guard: the registry actually held the live connection on BOTH ends — proof the test is
        // exercising a real link, not passing vacuously on an empty registry.
        assertAll(
            { assertTrue(hostApi.liveConnectionCount() >= 1, "host registry should hold the live connection") },
            { assertTrue(joinerApi.liveConnectionCount() >= 1, "joiner registry should hold the live connection") },
        )

        hostSeam.close(CloseReason.Normal)
        joinerSeam.close(CloseReason.Normal)

        // Await the drain with a HARD real-time ceiling. Poll both registries until each hits 0; if
        // the ceiling trips, fail LOUD with the last counts — a leak is a drain that never completes,
        // and must fail fast rather than spin. (Catch the withTimeout boundary's own
        // TimeoutCancellationException to convert it to a legible assertion — mirrors NwLoom.weave.)
        try {
            withTimeout(DRAIN_CEILING) {
                while (hostApi.liveConnectionCount() != 0 || joinerApi.liveConnectionCount() != 0) {
                    delay(POLL_INTERVAL)
                }
            }
        } catch (_: TimeoutCancellationException) {
            fail(
                "connection registry did not drain within $DRAIN_CEILING — leaked " +
                    "host=${hostApi.liveConnectionCount()} joiner=${joinerApi.liveConnectionCount()} " +
                    "(nw_connection_t leak: strong ref never dropped on seam close)",
            )
        }

        assertAll(
            { assertEquals(0, hostApi.liveConnectionCount(), "host registry drained to empty") },
            { assertEquals(0, joinerApi.liveConnectionCount(), "joiner registry drained to empty") },
        )
    }

    /**
     * Wrap [delegate] so `weave` runs on a real [Dispatchers.Default]. [NwLoom] captures its seam
     * scope from `currentCoroutineContext()`, so without this a virtual-time dispatcher would drive
     * the seam's `withTimeout`/timers and fast-forward past the real socket connect.
     */
    private fun realDispatch(delegate: Loom): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            withContext(Dispatchers.Default) { delegate.weave(rendezvous) }

        override fun availability(): FabricAvailability = delegate.availability()
    }
}
