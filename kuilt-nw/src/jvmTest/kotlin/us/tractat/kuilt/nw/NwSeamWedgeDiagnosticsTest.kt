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

    /**
     * The watchdog PARKS once every settled link has been reported — it must not re-arm forever, or
     * `runTest`'s terminal `advanceUntilIdle` spins on it and every test using this seam hangs instead of
     * failing (it did: three `:kuilt-nw` test tasks timed out). Parking costs a wake signal on each state
     * change that could make something reportable again, and this pins the one wake site nothing else
     * covers: an inbound frame ending a reported silence episode.
     *
     * Without that wake the watchdog stays parked and the SECOND episode is never reported — a silent
     * regression, since the first episode still fires and every other assertion here stays green.
     */
    @Test
    fun anArrivalReArmsAParkedWatchdogSoASecondSilenceEpisodeIsReportedToo() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val (a, b) = buildPair("rearm")

                // Episode 1: both links quiet, both reported, watchdog parks.
                oneProbe(count = 2)
                val firstEpisode = appender.lines(Level.WARN, "nw.seam.inbound-silent", "rearm-")

                // Nothing further happens: a PARKED watchdog must add nothing.
                oneProbe(count = 6)
                val whileParked = appender.lines(Level.WARN, "nw.seam.inbound-silent", "rearm-")

                // A frame arrives on B's link from A. That ends B's silence episode and is the only thing
                // that can un-park the watchdog for it.
                a.seam.broadcast("wake-up".encodeToByteArray())
                pumpUntil { false }

                // Episode 2: the link that received goes quiet again and must be reported a second time.
                oneProbe(count = 4)
                val secondEpisode = appender.lines(Level.WARN, "nw.seam.inbound-silent", "rearm-")
                val forB = secondEpisode.filter { it.contains("self=${b.peerId.value}") }
                val forA = secondEpisode.filter { it.contains("self=${a.peerId.value}") }

                assertAll(
                    { assertEquals(2, firstEpisode.size, "one line per link in episode 1: $firstEpisode") },
                    {
                        assertEquals(
                            2,
                            whileParked.size,
                            "a parked watchdog must emit nothing at all: $whileParked",
                        )
                    },
                    {
                        assertEquals(
                            2,
                            forB.size,
                            "B received a frame, so its silence re-arms and is reported a second time: " +
                                secondEpisode,
                        )
                    },
                    {
                        assertEquals(
                            1,
                            forA.size,
                            "A received nothing, so its episode never ended and must NOT be re-reported: " +
                                secondEpisode,
                        )
                    },
                    {
                        assertTrue(
                            forB.last().contains("frames-in=1"),
                            "the second report counts the frame that arrived in between: ${forB.last()}",
                        )
                    },
                )
            }
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
                        // `dialled=` is what makes the two devices' lines comparable — it says which side
                        // opened the link each end settled on, so a reader can check they kept the same one.
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

                // RIG the state: A's peer B is left bound to a connection A has dropped from `conns`.
                // No NwApi input can produce this — every production removal path either evicts the peer
                // or proves the connection was not the live one — so the ERROR arm is only ever observed
                // by rigging it. (This is NOT what happened in #2425: a two-sided byte ledger showed the
                // binding there was correct and carrying traffic. The check is a cheap backstop whose
                // ABSENCE from a future capture rules local bookkeeping out in one line.)
                val forgotten = a.seam.dropConnWithoutEvictingForAuditRig(b.peerId)
                assertNotNull(forgotten, "rig did not fire: B was not registered on A")

                // The close that REVEALS it — verbatim the field's `nw.seam.closed … : unknown-conn`,
                // an arm that removes nothing and deliberately evicts nobody. The audit must still run.
                //
                // Emitted directly rather than driven through `disconnect`, because `FakeNwRadio` delivers
                // the close EVENT only to the REMOTE end, whereas `RealNwApi` reports a locally-initiated
                // close locally too (`nw.api.close` → `nw.seam.closed … : unknown-conn`). This is that
                // event: the arm that removes nothing and evicts nobody, and must still reach the audit.
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

    /**
     * The publish-then-swap window (#2425). A peer is published on one link, the consumer writes to it,
     * and the seam then rebinds the peer to a second link and closes the first — so the write went to a
     * socket the far end may already have closed, and this fabric neither retries nor reports it.
     *
     * The two links are established SEQUENTIALLY rather than double-dialled in one pump, which is what
     * makes the window openable at all: the first link must settle (and the peer become visible) before
     * the second arrives to displace it. `nowMillis` is an injected counter the test steps by hand, so
     * `visible-for` is an exact assertion rather than a wall-clock reading that would differ per run.
     */
    @Test
    fun aPeerRepublishedOnAnotherLinkReportsTheWindowAndWhatWasWrittenIntoIt() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                // CONTROL: seeds whose canonical nonces make the FIRST link the survivor, so both ends take
                // the `dedup.keep` arm and no peer is ever moved. If this arm ever reds, the harness's nonce
                // ordering has changed — pick another keep-producing pair, do not delete the arm: without it
                // the subject below is satisfied by a WARN that fires on every dedup.
                val keptPeers = swapScenario(tag = "keepctl", seeds = 0 to 0)
                val keptLines = appender.lines(Level.WARN, "nw.seam.publish-swap", "keepctl-")

                // SUBJECT: seeds whose canonical nonces make the SECOND link the survivor, so both ends
                // rebind — and only device 0 wrote into the window.
                val swappedPeers = swapScenario(tag = "swapped", seeds = 0 to 3)
                val swapLines = appender.lines(Level.WARN, "nw.seam.publish-swap", "swapped-")
                val writerLine = swapLines.firstOrNull { it.contains("self=swapped-peer-0") } ?: ""
                val quietLine = swapLines.firstOrNull { it.contains("self=swapped-peer-1") } ?: ""

                assertAll(
                    { assertEquals(2, keptPeers, "control scenario must still converge") },
                    {
                        assertEquals(
                            0,
                            keptLines.size,
                            "no peer was moved, so no window was opened: $keptLines",
                        )
                    },
                    { assertEquals(2, swappedPeers, "subject scenario must still converge") },
                    {
                        assertEquals(
                            2,
                            swapLines.size,
                            "both ends rebind, so both report their own window: $swapLines",
                        )
                    },
                    {
                        assertTrue(
                            writerLine.contains("frames-written-to-published-link=1"),
                            "the frame written after publish and before the swap must be counted — this is " +
                                "the field's stranded write, as a number: $swapLines",
                        )
                    },
                    {
                        assertTrue(
                            quietLine.contains("frames-written-to-published-link=0"),
                            "the counter must be a measurement, not a constant — the peer that wrote " +
                                "nothing into its window reports zero: $swapLines",
                        )
                    },
                    {
                        // The window's width, exactly as stepped: 37 ms before the write, 5 ms after.
                        assertTrue(
                            writerLine.contains("visible-for=42ms"),
                            "the publish→swap window must be measured from the injected clock, not a wall " +
                                "clock: $swapLines",
                        )
                    },
                    {
                        assertTrue(
                            writerLine.contains("published-on=") && writerLine.contains("now-on=") &&
                                writerLine.contains("dialled="),
                            "both link identities, and the direction of each: $swapLines",
                        )
                    },
                )
            }
        }

    /**
     * Two peers, two links, established one at a time so the first can settle before the second displaces
     * it. [seeds] pick each device's nonce stream, which is what decides whether the second link wins (a
     * rebind) or loses (no rebind at all). Device 0 broadcasts once while the peer is published on the
     * FIRST link — the write that a swap strands. Returns the converged peer count.
     */
    private suspend fun TestScope.swapScenario(tag: String, seeds: Pair<Int, Int>): Int {
        var clockMillis = 0L
        val radio = FakeNwRadio()
        val devices = (0..1).map { i ->
            val api = FakeNwApi(radio, deviceId = "$tag-dev-$i", serviceName = "$tag-svc-$i")
            val id = PeerId("$tag-peer-$i")
            Device(
                id,
                api,
                NwSeam(
                    selfId = id,
                    api = api,
                    scope = seamScope(),
                    random = Random((if (i == 0) seeds.first else seeds.second).toLong()),
                    inboundSilenceProbe = PROBE,
                    nowMillis = { clockMillis },
                ),
            )
        }
        for (d in devices) {
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { d.seam.incoming.collect { } }
        }
        testScheduler.runCurrent()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            devices[0].api.connect(NwEndpoint(id = "ep-$tag-dev-1", serviceName = "$tag-svc-1"))
        }
        assertTrue(
            pumpUntil { devices.all { it.seam.peers.value.size == 2 } },
            "$tag: the first link must publish the peer before the second is dialled",
        )
        clockMillis += 37
        devices[0].seam.broadcast("in-the-window".encodeToByteArray())
        pumpUntil { false }
        clockMillis += 5
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            devices[1].api.connect(NwEndpoint(id = "ep-$tag-dev-0", serviceName = "$tag-svc-0"))
        }
        pumpUntil { false }
        return devices.minOf { it.seam.peers.value.size }
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
