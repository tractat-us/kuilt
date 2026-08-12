@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress(
    "ForbiddenImport",
) // opt-in real-network multi-threaded stress probe — hundreds of real Network.framework loopback links need a real IO dispatcher; there is no virtual-time option here

package us.tractat.kuilt.nw

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: opt-in real-network multi-threaded stress probe — hundreds of real Network.framework loopback links need a real IO dispatcher; there is no virtual-time option here
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.getenv
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The heavy, **opt-in** sibling of [NwConnectionDrainTest]: open and close **hundreds** of real
 * `127.0.0.1` TLS-PSK links concurrently on a genuinely multi-threaded dispatcher
 * ([Dispatchers.Default]) and prove [RealNwApi]'s strong-ref connection registry drains back to
 * **empty** on every one of them — the reference-management stress check where a `nw_connection_t`
 * cinterop leak or a cancel/drop race under contention would surface as a registry that never
 * drains.
 *
 * ## Why this is opt-in (never in `ci-required`)
 * Unlike the deterministic, single-link [NwConnectionDrainTest] (which runs on every macOS build),
 * this probe stands up many concurrent real GCD sockets under real thread contention — expensive
 * and machine-sensitive. It runs ONLY when `-Pconcurrency.stress.tests=true` is passed, which the
 * module's `build.gradle.kts` forwards to the Kotlin/Native macOS host test binary as the
 * `CONCURRENCY_STRESS_TESTS` environment variable ([GATE_ENV_VAR]); absent the flag [gated] is
 * false and the test self-skips (mirrors `:kuilt-mdns`'s `MDNS_MULTICAST_TESTS` gating and
 * `:kuilt-core`'s `-Pconcurrency.stress.tests` JVM probes).
 *
 * ## Bounded fan-out (fd-safe, still high-volume + concurrent)
 * [TOTAL_LINKS] open/close cycles run through a [Semaphore] capped at [MAX_CONCURRENT] in-flight at
 * once. Each cycle stands up a fresh host/joiner [RealNwApi] pair, so an unbounded fan-out would
 * exhaust the process file-descriptor limit; the semaphore keeps dozens of real links live
 * simultaneously (genuine contention — verified via [peakConcurrent]) while the total volume stays
 * in the hundreds. Each cycle tears its own listeners/browsers down in a `finally`, freeing fds for
 * the next wave.
 *
 * ## Real dispatcher (not virtual time)
 * [NwLoom.weave] captures its seam scope from `currentCoroutineContext()`; [realDispatch] wraps each
 * loom so `weave` runs on a real [Dispatchers.Default], and the whole test runs under [runBlocking]
 * in real wall-clock time. A leak is a per-cycle drain that never completes and MUST fail fast
 * against [DRAIN_CEILING], not hang.
 */
class NwConnectionDrainStressTest {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val ROOM_KEY = "loopback-stress-secret"

        /** Env var forwarded from `-Pconcurrency.stress.tests=true` by this module's build.gradle.kts. */
        const val GATE_ENV_VAR = "CONCURRENCY_STRESS_TESTS"

        /** Total open/close cycles — "hundreds", each a fresh real loopback link. */
        const val TOTAL_LINKS = 240

        /** Cap on simultaneously-live links, keeping the open-fd count well under the process limit. */
        const val MAX_CONCURRENT = 24

        /** Hard real-time ceiling on each cycle's drain: a leak = a drain that never completes ⇒ fail fast. */
        val DRAIN_CEILING = 30.seconds

        /** Poll cadence while awaiting a drain — bounded busy-wait, never a bare fixed sleep. */
        val POLL_INTERVAL = 20.milliseconds
    }

    /** Peak simultaneously-live link count — asserted > 1 to prove the fan-out was genuinely concurrent. */
    private val peakConcurrent = atomic(0)

    /** Links live at this instant; feeds [peakConcurrent]. */
    private val inFlight = atomic(0)

    /** Cycles that failed to drain, captured for a legible aggregate failure. Guarded by [failuresLock]. */
    private val failures = mutableListOf<String>()
    private val failuresLock = Semaphore(1)

    @Test
    fun registryDrainsToEmptyUnderConcurrentOpenClose() = runBlocking {
        if (!gated()) return@runBlocking

        val gate = Semaphore(MAX_CONCURRENT)

        coroutineScope {
            repeat(TOTAL_LINKS) { i ->
                launch(Dispatchers.Default) {
                    gate.withPermit {
                        bumpPeak(inFlight.incrementAndGet())
                        try {
                            openCloseAndDrain(i)
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    }
                }
            }
        }

        assertAll(
            {
                assertTrue(
                    peakConcurrent.value > 1,
                    "fan-out was not genuinely concurrent (peak=${peakConcurrent.value}); the drain proof would be vacuous",
                )
            },
            {
                assertTrue(
                    failures.isEmpty(),
                    "registry failed to drain on ${failures.size}/$TOTAL_LINKS concurrent links:\n" +
                        failures.joinToString("\n"),
                )
            },
        )
    }

    /**
     * One open/close cycle: form a real loopback link over its own host/joiner [RealNwApi] pair,
     * assert both registries actually held the live connection, close both seams, and await both
     * registries draining to 0 within [DRAIN_CEILING]. Records a leak into [failures] rather than
     * throwing, so one straggler is reported alongside all the others (assertions collate in the
     * test body). Always cancels its own listeners/browsers so fds free for the next wave.
     */
    private suspend fun openCloseAndDrain(index: Int) {
        val psk = NwPsk.derive(ROOM_KEY, SERVICE_TYPE)
        val rendezvous = NwLoopbackRendezvous()
        val hostApi = RealNwApi(psk, NwLoopbackConfig(dial = false, rendezvous = rendezvous))
        val joinerApi = RealNwApi(psk, NwLoopbackConfig(dial = true, rendezvous = rendezvous))
        try {
            val hostLoom = realDispatch(NwLoom(hostApi, serviceType = SERVICE_TYPE, random = Random(index * 2)))
            val joinerLoom =
                realDispatch(NwLoom(joinerApi, serviceType = SERVICE_TYPE, random = Random(index * 2 + 1)))

            val (hostSeam, joinerSeam) = coroutineScope {
                val host = async { hostLoom.host(Pattern("host-$index")) }
                val joiner =
                    async { joinerLoom.join(InMemoryTag(sessionName = "host-$index", peerKey = "nw-loopback-joiner-$index")) }
                host.await() to joiner.await()
            }

            if (hostApi.liveConnectionCount() < 1 || joinerApi.liveConnectionCount() < 1) {
                recordLeak(
                    "[$index] link formed without registering a connection " +
                        "(host=${hostApi.liveConnectionCount()} joiner=${joinerApi.liveConnectionCount()})",
                )
            }

            hostSeam.close(CloseReason.Normal)
            joinerSeam.close(CloseReason.Normal)

            try {
                withTimeout(DRAIN_CEILING) {
                    while (hostApi.liveConnectionCount() != 0 || joinerApi.liveConnectionCount() != 0) {
                        delay(POLL_INTERVAL)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                recordLeak(
                    "[$index] registry did not drain within $DRAIN_CEILING — leaked " +
                        "host=${hostApi.liveConnectionCount()} joiner=${joinerApi.liveConnectionCount()} " +
                        "(nw_connection_t leak: strong ref never dropped on seam close under contention)",
                )
            }
        } finally {
            hostApi.stopListening()
            hostApi.stopBrowsing()
            joinerApi.stopListening()
            joinerApi.stopBrowsing()
        }
    }

    private suspend fun recordLeak(message: String) {
        failuresLock.withPermit { failures += message }
    }

    /** Raise [peakConcurrent] to [candidate] if it is a new high — a lock-free CAS retry loop. */
    private fun bumpPeak(candidate: Int) {
        while (true) {
            val current = peakConcurrent.value
            if (candidate <= current || peakConcurrent.compareAndSet(current, candidate)) return
        }
    }

    /** True when [GATE_ENV_VAR] is `"true"` — the `-Pconcurrency.stress.tests=true` opt-in. */
    private fun gated(): Boolean = getenv(GATE_ENV_VAR)?.toKString() == "true"

    /**
     * Wrap [delegate] so `weave` runs on a real [Dispatchers.Default]. [NwLoom] captures its seam
     * scope from `currentCoroutineContext()`, so without this a virtual-time dispatcher would drive
     * the seam's `withTimeout`/timers and fast-forward past the real socket connect.
     */
    private fun realDispatch(delegate: Loom): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            withContext(Dispatchers.Default) { delegate.weave(rendezvous) }

        override fun capability(): TransportCapability = delegate.capability()
    }
}
