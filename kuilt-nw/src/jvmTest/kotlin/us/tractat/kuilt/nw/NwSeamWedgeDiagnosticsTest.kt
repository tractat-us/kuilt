@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.advanceTimeBy drives the watchdog

package us.tractat.kuilt.nw

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The #2420 wedge discriminators, asserted on the **real log emission** — level and text — rather
 * than on an injected spy, because the log line IS the deliverable here. A recorder would prove the
 * seam consulted an instrument; a captured `ILoggingEvent` proves the line a field capture would
 * actually carry, at the level that decides whether a release iPhone records it at all.
 *
 * Capture is a Logback [ListAppender] on the `us.tractat.kuilt.nw` logger — the same vehicle
 * `:kuilt-multipeer`'s `BridgePeerLinkClosingFlagTest` uses. That makes this a `jvmTest`: Logback is
 * a JVM backend, and the subject ([NwSeam]) is `commonMain`, so nothing platform-specific is lost.
 *
 * Every test here rigs the failure it claims to detect and **counts the firings**. A diagnostic that
 * never triggers passes green by absence, and the count (rather than "at least one") is also what
 * pins the boundedness these checks promise — an ERROR that fired once must not become one per sweep.
 * Peer ids are prefixed per test so a line can never be attributed to a sibling test sharing the JVM.
 */
class NwSeamWedgeDiagnosticsTest {

    private class Device(val peerId: PeerId, val api: FakeNwApi, val seam: NwSeam)

    private companion object {
        /** The watchdog cadence under test. Small, and virtual — nothing here waits on a wall clock. */
        val PROBE: Duration = 1.seconds

        fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
            repeat(maxPumps) {
                if (cond()) return true
                testScheduler.runCurrent()
            }
            return cond()
        }

        /** One seam per child scope, so one seam's teardown cannot cancel the other's loops. */
        fun TestScope.seamScope(): CoroutineScope =
            CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
    }

    /**
     * Advance exactly one watchdog interval and let the sweep scheduled at that instant run.
     * [kotlinx.coroutines.test.TestCoroutineScheduler.advanceTimeBy] runs what is scheduled *before*
     * the new instant, so the `runCurrent` is what executes the sweep sitting exactly on it.
     */
    private fun TestScope.oneProbe(count: Int = 1) {
        repeat(count) {
            testScheduler.advanceTimeBy(PROBE)
            testScheduler.runCurrent()
        }
    }

    /** A 2-node mesh over one radio, double-dialled so dedup runs, converged before returning. */
    private fun TestScope.buildPair(prefix: String): Pair<Device, Device> {
        val radio = FakeNwRadio()
        val devices = (0..1).map { i ->
            val api = FakeNwApi(radio, deviceId = "$prefix-dev-$i", serviceName = "$prefix-svc-$i")
            val id = PeerId("$prefix-peer-$i")
            Device(
                id,
                api,
                NwSeam(
                    selfId = id,
                    api = api,
                    scope = seamScope(),
                    random = Random(i.toLong()),
                    inboundSilenceProbe = PROBE,
                ),
            )
        }
        for (d in devices) {
            // Single-collection, off the seam's own scope so the collector outlives a seam teardown.
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { d.seam.incoming.collect { } }
        }
        testScheduler.runCurrent()
        for (i in devices.indices) {
            for (j in devices.indices) {
                if (i != j) {
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        devices[i].api.connect(NwEndpoint(id = "ep-$prefix-dev-$j", serviceName = "$prefix-svc-$j"))
                    }
                }
            }
        }
        assertTrue(
            pumpUntil { devices.all { it.seam.peers.value.size == 2 } },
            "mesh did not converge: ${devices.map { it.peerId to it.seam.peers.value }}",
        )
        return devices[0] to devices[1]
    }

    @Test
    fun aSettledLinkThatCarriesNoInboundFrameIsReportedOnceAtWarn() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val (a, b) = buildPair("silence")
                // Both peers are resolved and reported live, and neither will ever send: this is the
                // #2425 signature — `nw.seam.resolved.first` and then nothing.

                // CONTROL: one sweep is not a verdict. The link settled part-way through it, so the
                // window it covers began before the link existed and reporting on it would overstate
                // the silence. Nothing may fire yet.
                oneProbe()
                val afterOneSweep = appender.lines(Level.WARN, "nw.seam.inbound-silent", "silence-")

                // SUBJECT: a second consecutive silent sweep vouches for a full interval of silence.
                oneProbe()
                val afterTwoSweeps = appender.lines(Level.WARN, "nw.seam.inbound-silent", "silence-")

                // BOUND: edge-triggered, so a link that stays quiet is not re-reported every sweep.
                oneProbe(count = 6)
                val afterManySweeps = appender.lines(Level.WARN, "nw.seam.inbound-silent", "silence-")

                assertAll(
                    { assertEquals(0, afterOneSweep.size, "one sweep must not report: $afterOneSweep") },
                    {
                        assertEquals(
                            2,
                            afterTwoSweeps.size,
                            "exactly one line per silent link (A→B and B→A): $afterTwoSweeps",
                        )
                    },
                    {
                        assertEquals(
                            2,
                            afterManySweeps.size,
                            "six further silent sweeps must add nothing — one WARN per silence episode, " +
                                "not per sweep: $afterManySweeps",
                        )
                    },
                    {
                        assertTrue(
                            afterTwoSweeps.any {
                                it.contains("self=${a.peerId.value}") && it.contains("peer=${b.peerId.value}")
                            },
                            "A must name B as the silent peer: $afterTwoSweeps",
                        )
                    },
                    {
                        assertTrue(
                            afterTwoSweeps.any {
                                it.contains("self=${b.peerId.value}") && it.contains("peer=${a.peerId.value}")
                            },
                            "B must name A as the silent peer: $afterTwoSweeps",
                        )
                    },
                    {
                        assertTrue(
                            afterTwoSweeps.all { it.contains("frames-in=0") },
                            "the count of frames that DID arrive is the evidence: $afterTwoSweeps",
                        )
                    },
                    {
                        // `dialled=` is what makes the two devices' lines comparable — it says which
                        // side opened the surviving link, so two `<inbound>`s would mean opposite links.
                        assertTrue(
                            afterTwoSweeps.all { it.contains("dialled=") && it.contains("connId=") },
                            "the line must carry link identity, not just a verdict: $afterTwoSweeps",
                        )
                    },
                )
            }
        }

    @Test
    fun aRegistryBindingLeftOnADeadConnectionIsReportedAtErrorWhenACloseRevealsIt() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val (a, b) = buildPair("closed")

                // CONTROL: a healthy mesh, swept, reports nothing. Without this arm the subject arm
                // below would be satisfied by a check that fires unconditionally.
                oneProbe(count = 3)
                val healthy = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "closed-")

                // RIG the field shape (#2425, 18:26:36.9): A's peer B is left bound to a connection A
                // has dropped from `conns`. No NwApi input can produce this — every production removal
                // path either evicts the peer or proves the connection was not the live one — so the
                // ERROR arm is only ever observed by rigging it.
                val forgotten = a.seam.dropConnWithoutEvictingForAuditRig(b.peerId)
                assertNotNull(forgotten, "rig did not fire: B was not registered on A")

                // The close that REVEALS it — verbatim the field's `nw.seam.closed … : unknown-conn`,
                // an arm that removes nothing and deliberately evicts nobody. The audit must still run.
                //
                // Emitted directly rather than driven through `disconnect`, because `FakeNwRadio` delivers
                // the close EVENT only to the REMOTE end, while the field host observed the close of a
                // connection IT had closed (`nw.api.close id=nw-2 closing=true` → `nw.seam.closed … :
                // unknown-conn` 0 ms later). `RealNwApi` reports a local close locally; this is that event.
                a.api.emitConnectionClosed(NwConnectionClosed(forgotten, reason = null))
                pumpUntil { appender.lines(Level.ERROR, "nw.seam.registry.orphan", "closed-").isNotEmpty() }
                val revealed = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "closed-")

                // BOUND: one ERROR per episode, not one per sweep for the rest of the seam's life.
                oneProbe(count = 6)
                val later = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "closed-")

                assertAll(
                    { assertEquals(0, healthy.size, "a healthy mesh must report no orphan: $healthy") },
                    { assertEquals(1, revealed.size, "the stale binding must be reported once: $revealed") },
                    {
                        assertEquals(
                            1,
                            later.size,
                            "six further sweeps must add nothing — bounded per episode: $later",
                        )
                    },
                    {
                        assertTrue(
                            revealed.single().contains("site=closed"),
                            "the site says a LATER close revealed an already-stale binding, which is " +
                                "the position an insert-time-only check would miss: ${revealed.single()}",
                        )
                    },
                    {
                        assertTrue(
                            revealed.single().contains("peer=${b.peerId.value}") &&
                                revealed.single().contains("connId=${forgotten.value}"),
                            "the binding identity — peer and the connId the registry NAMES: ${revealed.single()}",
                        )
                    },
                    {
                        // The whole binding, rendered — `peer→conn` plus the live conns key set — so the
                        // next occurrence names the surviving link directly instead of implying a count.
                        assertTrue(
                            revealed.single().contains("${b.peerId.value}→${forgotten.value}") &&
                                revealed.single().contains("conns=["),
                            "the report must render the binding and the live conns key set: ${revealed.single()}",
                        )
                    },
                    {
                        assertTrue(
                            a.seam.peers.value.contains(b.peerId),
                            "precondition of the report: `peers` still calls B connected — that gap " +
                                "between a healthy-looking roster and a dead link is the whole defect",
                        )
                    },
                )
            }
        }

    @Test
    fun aStaleRegistryBindingIsReportedByTheWatchdogWhenNothingFurtherEverHappens() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val (a, b) = buildPair("watchdog")

                // Same rig, but NO subsequent event of any kind. This is the case #2420 exists for: the
                // fabric's failure mode is silence, so an event-driven audit alone would never run again
                // and the wedge would emit nothing for the seam's whole lifetime.
                val forgotten = a.seam.dropConnWithoutEvictingForAuditRig(b.peerId)
                assertNotNull(forgotten, "rig did not fire: B was not registered on A")

                val beforeAnySweep = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "watchdog-")
                oneProbe()
                val afterOneSweep = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "watchdog-")

                assertAll(
                    {
                        assertEquals(
                            0,
                            beforeAnySweep.size,
                            "nothing may be reported before a sweep has run: $beforeAnySweep",
                        )
                    },
                    {
                        assertEquals(
                            1,
                            afterOneSweep.size,
                            "the watchdog must find it with no further event at all: $afterOneSweep",
                        )
                    },
                    {
                        assertTrue(
                            afterOneSweep.single().contains("site=watchdog") &&
                                afterOneSweep.single().contains("connId=${forgotten.value}"),
                            "site and link identity: ${afterOneSweep.single()}",
                        )
                    },
                    {
                        // The orphaned peer must NOT also be reported as an inbound-silent link: it has
                        // no ConnState to be silent on, and saying "quiet" about a connection the seam
                        // cannot read would be a second, misleading story about one fact.
                        assertTrue(
                            appender.lines(Level.WARN, "nw.seam.inbound-silent", "watchdog-")
                                .none { it.contains("peer=${b.peerId.value}") && it.contains("self=${a.peerId.value}") },
                            "an orphaned binding is reported as an orphan, never as a silent link",
                        )
                    },
                )
            }
        }

    // ── capture plumbing ────────────────────────────────────────────────────────

    /**
     * Lines at [level] whose message contains [event] and [scope] — the per-test peer-id prefix, so a
     * sibling test sharing the JVM and the logger can never contribute to a count.
     */
    private fun ListAppender<ILoggingEvent>.lines(level: Level, event: String, scope: String): List<String> =
        list.filter { it.level == level }.map { it.formattedMessage }
            .filter { it.contains(event) && it.contains(scope) }

    private inline fun withCapture(block: (ListAppender<ILoggingEvent>) -> Unit) {
        @Suppress("CastNullableToNonNullableType") // SLF4J returns non-null; Logback is the bound implementation
        val logger = LoggerFactory.getLogger("us.tractat.kuilt.nw") as Logger
        val previousLevel = logger.level
        logger.level = Level.DEBUG // so a DEMOTED line would still be captured — and then fail the level assertion
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = previousLevel
        }
    }
}
