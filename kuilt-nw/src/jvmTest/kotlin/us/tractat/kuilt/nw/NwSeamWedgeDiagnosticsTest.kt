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

        /** The injected #2425 zombie-link backstop. Virtual, and driven by hand where it is reached. */
        val DRAIN_BOUND: Duration = 2.seconds

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
                // The control's own rig receipt: assert the dedup actually RAN and took the keep arm.
                // Without this the control is green by absence — it would read the same if the second
                // link never arrived at all. Readable only because `dedup.keep` was promoted to INFO.
                val keptDedups = appender.lines(Level.INFO, "nw.seam.dedup.keep", "keepctl-")

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
                            2,
                            keptDedups.size,
                            "control rig check — both ends must have run the dedup and KEPT their first " +
                                "link, or the zero below proves nothing: $keptDedups",
                        )
                    },
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

    /**
     * The post-swap wedge — the exact shape this whole class exists to instrument, and the one the
     * watchdog was blind to.
     *
     * A peer settles, goes quiet, is reported, and the watchdog PARKS. A second link then wins the dedup
     * and the peer is rebound onto it. That rebind goes through neither [addRemotePeer] (the peer is not
     * new) nor the data arm (the frame that drove the resolve carried identity, not data), so nothing woke
     * the parked loop — and the brand-new link, which is the one a swap-related wedge would sit on, was
     * never watched again however long it stayed dead.
     *
     * The assertion is deliberately about the SECOND episode naming the NEW connId: a test that only
     * counted lines would pass on the first episode alone.
     */
    @Test
    fun aPeerReboundOntoANewLinkIsWatchedAgainEvenAfterTheWatchdogParked() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                var clockMillis = 0L
                val tag = "postswap"
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
                            // Seeds chosen so the SECOND link wins the dedup on both ends — i.e. the
                            // rebind actually happens. The `dedup.replace` assertion below is what proves
                            // the rig fired rather than leaving the peer on its original link.
                            random = Random((if (i == 0) 0 else 3).toLong()),
                            inboundSilenceProbe = PROBE,
                            nowMillis = { clockMillis },
                        ),
                    )
                }
                for (d in devices) {
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { d.seam.incoming.collect { } }
                }
                testScheduler.runCurrent()

                // Link 1 alone, so the peer is published and can be reported before link 2 arrives.
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    devices[0].api.connect(NwEndpoint(id = "ep-$tag-dev-1", serviceName = "$tag-svc-1"))
                }
                assertTrue(
                    pumpUntil { devices.all { it.seam.peers.value.size == 2 } },
                    "the first link must publish the peer before the second is dialled",
                )

                // Episode 1 on link 1: reported, then the watchdog parks.
                oneProbe(count = 2)
                val episode1 = appender.lines(Level.WARN, "nw.seam.inbound-silent", "$tag-")
                val link1ConnIds = episode1.map { connIdOf(it) }

                // Confirm it really is parked: more probes with nothing pending must add nothing.
                oneProbe(count = 4)
                val whileParked = appender.lines(Level.WARN, "nw.seam.inbound-silent", "$tag-")

                // Link 2 arrives and wins the dedup: the peer is REBOUND onto a link nothing has watched.
                clockMillis += 11
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    devices[1].api.connect(NwEndpoint(id = "ep-$tag-dev-0", serviceName = "$tag-svc-0"))
                }
                pumpUntil { false }
                val swaps = appender.lines(Level.WARN, "nw.seam.publish-swap", "$tag-")

                // Episode 2 must happen on the NEW link.
                oneProbe(count = 3)
                val episode2 = appender.lines(Level.WARN, "nw.seam.inbound-silent", "$tag-")
                val fresh = episode2.drop(episode1.size)

                assertAll(
                    { assertEquals(2, episode1.size, "episode 1: one line per link: $episode1") },
                    {
                        assertEquals(
                            2,
                            whileParked.size,
                            "the watchdog must be genuinely parked before the rebind, or this test proves " +
                                "nothing about waking it: $whileParked",
                        )
                    },
                    {
                        assertEquals(
                            2,
                            swaps.size,
                            "rig check — both ends must actually REBIND, else there is no swap to be blind " +
                                "to. If this reds, the harness's nonce ordering changed; pick seeds that " +
                                "produce a replace rather than deleting the arm: $swaps",
                        )
                    },
                    {
                        assertEquals(
                            2,
                            fresh.size,
                            "the rebound peers must be watched again — this is the post-swap wedge, and a " +
                                "parked watchdog reports NOTHING here: $episode2",
                        )
                    },
                    {
                        // The decisive assertion: the new lines are about the NEW connections. Counting
                        // alone would be satisfied by a stale repeat of episode 1.
                        assertTrue(
                            fresh.none { connIdOf(it) in link1ConnIds },
                            "episode 2 must name the links the peers were REBOUND onto, not the displaced " +
                                "ones (episode 1 was $link1ConnIds): $fresh",
                        )
                    },
                )
            }
        }

    /**
     * Pins `site=classify` — the audit's position inside [NwSeam]'s frame-classify critical section, on the
     * identity arm. Nothing else exercised it, so the placement was free to be wrong (and it was: it used
     * to sit after the whole `when`, firing on every data frame while being structurally unable to detect
     * anything there).
     *
     * The rig leaves A holding a stale binding for B, then a THIRD peer resolves on A. That resolve is the
     * only thing that runs; virtual time never advances, so the watchdog cannot be what reports it.
     */
    @Test
    fun aStaleBindingIsReportedByTheIdentityPathWhenTheNextPeerResolves() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "classify"
                val radio = FakeNwRadio()
                val devices = (0..2).map { i ->
                    val api = FakeNwApi(radio, deviceId = "$tag-dev-$i", serviceName = "$tag-svc-$i")
                    val id = PeerId("$tag-peer-$i")
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
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { d.seam.incoming.collect { } }
                }
                testScheduler.runCurrent()

                // A and B form; C stays out for now.
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    devices[0].api.connect(NwEndpoint(id = "ep-$tag-dev-1", serviceName = "$tag-svc-1"))
                }
                assertTrue(
                    pumpUntil { devices[0].seam.peers.value.size == 2 },
                    "A and B must form before the rig",
                )

                val forgotten = devices[0].seam.dropConnWithoutEvictingForAuditRig(devices[1].peerId)
                assertNotNull(forgotten, "rig did not fire: B was not registered on A")
                val beforeResolve = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "$tag-")

                // C now resolves on A. NO virtual time passes, so the watchdog's `delay` cannot fire and
                // the identity path is the only thing that can report the stale binding.
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    devices[2].api.connect(NwEndpoint(id = "ep-$tag-dev-0", serviceName = "$tag-svc-0"))
                }
                assertTrue(
                    pumpUntil { devices[0].seam.peers.value.size == 3 },
                    "C must resolve on A, or the identity path never runs",
                )
                val afterResolve = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "$tag-")

                assertAll(
                    {
                        assertEquals(
                            0,
                            beforeResolve.size,
                            "the rig alone must report nothing — it goes through no audit site: $beforeResolve",
                        )
                    },
                    {
                        assertEquals(
                            1,
                            afterResolve.size,
                            "the next identity resolution must report the stale binding: $afterResolve",
                        )
                    },
                    {
                        assertTrue(
                            afterResolve.single().contains("site=classify"),
                            "and it must be the CLASSIFY site — no virtual time passed, so the watchdog " +
                                "cannot be what found it: ${afterResolve.single()}",
                        )
                    },
                    {
                        assertTrue(
                            afterResolve.single().contains("connId=${forgotten.value}"),
                            "naming the link the registry still points at: ${afterResolve.single()}",
                        )
                    },
                )
            }
        }

    /** The `connId=` field of a diagnostic line, so an assertion can compare LINKS rather than counts. */
    private fun connIdOf(line: String): String =
        line.substringAfter("connId=").substringBefore(' ')

    // ── the displacement drain's own trail (#2425) ──────────────────────────────

    /**
     * A double-dialled pair with the loser's GOODBYEs withheld on BOTH ends, so a drain in progress is
     * observable at all — with them flowing it begins and ends in the same pump. `dev-0` writes into its
     * publish-then-swap window, on a link whose sends are frozen, so the frame is genuinely in flight when
     * the dedup runs and its delivery is genuinely the drain's doing.
     *
     * Returns the radio plus the two devices; the caller decides which holds to release and when.
     */
    private class DrainRig(
        val radio: FakeNwRadio,
        val dial: DoubleDial,
        val writer: Device,
        val reader: Device,
        val writerLoserEnd: NwConnectionId,
        val readerLoserEnd: NwConnectionId,
    )

    private suspend fun TestScope.drainRig(tag: String, holdCapacity: Int): DrainRig {
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
                    // Seeds that make the SECOND-resolving link the survivor on both ends, so both
                    // rebind. The `dedup.replace` count below is what proves the rig fired.
                    random = Random(i.toLong()),
                    inboundSilenceProbe = Duration.ZERO, // no watchdog: this test is about the drain trail
                    drainBound = DRAIN_BOUND,
                    orderingHoldCapacity = holdCapacity,
                ),
            )
        }
        for (d in devices) {
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { d.seam.incoming.collect { } }
        }
        testScheduler.runCurrent()

        val dial = radio.injectDoubleDial("$tag-dev-0", "$tag-dev-1")
        // Silence the OUTBOUND link entirely, so both ends publish the peer on the INBOUND one and the
        // dedup then moves them off it — the field's shape.
        radio.holdSends(dial.outbound.dialerConnectionId)
        radio.holdSends(dial.outbound.accepterConnectionId)
        assertTrue(
            pumpUntil { devices.all { it.seam.peers.value.size == 2 } },
            "$tag: both ends must publish on the link left speaking: " +
                "${devices.map { it.peerId to it.seam.peers.value }}",
        )
        val writerLoserEnd = dial.inbound.endOn("$tag-dev-0")!!
        val readerLoserEnd = dial.inbound.endOn("$tag-dev-1")!!
        return DrainRig(radio, dial, devices[0], devices[1], writerLoserEnd, readerLoserEnd)
    }

    /**
     * The `nw.seam.publish-swap` WARN is emitted at DRAIN-END and carries the outcome, because a window
     * measured without knowing what the drain that followed it delivered is the half a field capture
     * already had (#2425).
     *
     * Both ends report the same frame from opposite sides — `frames-written-to-published-link=1` on the
     * end that wrote it, `drained=1` on the end that received it — which is what makes the pair readable
     * as one event across two devices' captures rather than two unrelated ones.
     *
     * The two `via=` values in one run are deliberate. `dev-1` disposes of its end on `dev-0`'s goodbye;
     * `dev-0`'s own drain then ends on the close that produces — so a single formation exercises the
     * in-band terminator and the terminal-error one, and neither is inferred from the other.
     */
    @Test
    fun theDrainReportsWhatItCarriedAndWhichTerminatorEndedIt() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "drainrep"
                val rig = drainRig(tag, holdCapacity = NwSeam.DEFAULT_ORDERING_HOLD_CAPACITY)
                // Freeze BOTH ends of the loser: dev-0's so its window write is in flight when the dedup
                // runs, dev-1's so its goodbye cannot reach dev-0 while dev-0 still has bytes queued.
                rig.radio.holdSends(rig.writerLoserEnd)
                rig.radio.holdSends(rig.readerLoserEnd)
                rig.writer.seam.broadcast("in-the-window".encodeToByteArray())
                pumpUntil { false }

                // Release the silenced link: both ends resolve it, both rebind, both drain.
                rig.radio.releaseSends(rig.dial.outbound.dialerConnectionId)
                rig.radio.releaseSends(rig.dial.outbound.accepterConnectionId)
                pumpUntil { false }
                // …then let the window write and the goodbye behind it out.
                rig.radio.releaseSends(rig.writerLoserEnd)
                pumpUntil { false }

                val swaps = appender.lines(Level.WARN, "nw.seam.publish-swap", "$tag-")
                val ends = appender.lines(Level.INFO, "nw.seam.drain-end", "$tag-")
                val writerLine = swaps.firstOrNull { it.contains("self=$tag-peer-0") } ?: ""
                val readerLine = swaps.firstOrNull { it.contains("self=$tag-peer-1") } ?: ""

                assertAll(
                    {
                        // Rig receipt: both ends must actually have taken the REPLACE arm, or "the window
                        // was drained" is a claim about a swap that never happened. If this reds, the
                        // harness's nonce ordering changed — pick seeds that produce a replace rather
                        // than deleting the arm.
                        assertEquals(
                            2,
                            appender.lines(Level.INFO, "nw.seam.dedup.replace", "$tag-").size,
                            "both ends must rebind: ${appender.lines(Level.INFO, "nw.seam.dedup", "$tag-")}",
                        )
                    },
                    { assertEquals(2, swaps.size, "one window report per end: $swaps") },
                    { assertEquals(2, ends.size, "one uniform drain receipt per end: $ends") },
                    {
                        assertTrue(
                            writerLine.contains("frames-written-to-published-link=1"),
                            "the writer counts what it put into the window: $writerLine",
                        )
                    },
                    {
                        assertTrue(
                            readerLine.contains("drained=1"),
                            "…and the reader counts the same frame arriving on the drained link — the " +
                                "other side of one event, which is what makes the two captures joinable: " +
                                "$readerLine",
                        )
                    },
                    {
                        assertTrue(
                            writerLine.contains("drained=0"),
                            "`drained=` must be a MEASUREMENT, not a constant: the end nobody wrote to " +
                                "reports zero: $writerLine",
                        )
                    },
                    {
                        assertTrue(
                            readerLine.contains("via=goodbye"),
                            "the reader's drain ends in-band, on the writer's GOODBYE: $readerLine",
                        )
                    },
                    {
                        assertTrue(
                            writerLine.contains("via=error"),
                            "…and the writer's ends on the close the reader's disposal produces, so one " +
                                "formation exercises both terminators: $writerLine",
                        )
                    },
                    {
                        assertEquals(
                            2,
                            ends.count { it.contains("arm=replace") },
                            "the uniform receipt names which dedup arm displaced the link: $ends",
                        )
                    },
                    {
                        assertEquals(
                            0,
                            appender.lines(Level.WARN, "nw.seam.drain.hold-overflow", "$tag-").size,
                            "a hold that fits its cap must not report an overflow — the arm below is what " +
                                "makes that WARN a measurement rather than an unconditional line",
                        )
                    },
                )
            }
        }

    /**
     * The two backstops, in one run: a drain whose GOODBYE never arrives is ended by [DRAIN_BOUND], and an
     * ordering hold that fills before that releases early and says so.
     *
     * Both are reported conditions rather than silent policy. `via=bound` in a field capture means the
     * in-band terminator did not work on that formation; `nw.seam.drain.hold-overflow` means
     * `Seam.incoming`'s send-order promise was traded for liveness on that peer. Neither is something a
     * reader should have to infer from an absence.
     */
    @Test
    fun aBackstoppedDrainAndAnOverflowingHoldBothReportThemselves() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "drainbound"
                val rig = drainRig(tag, holdCapacity = 2)
                rig.radio.holdSends(rig.writerLoserEnd)
                rig.radio.holdSends(rig.readerLoserEnd)

                rig.radio.releaseSends(rig.dial.outbound.dialerConnectionId)
                rig.radio.releaseSends(rig.dial.outbound.accepterConnectionId)
                pumpUntil { false }

                // Three frames onto the live link while dev-1's hold is armed: two fit, the third overflows.
                repeat(3) { rig.writer.seam.broadcast("live-$it".encodeToByteArray()) }
                pumpUntil { false }
                val overflows = appender.lines(Level.WARN, "nw.seam.drain.hold-overflow", "$tag-")

                // Neither goodbye can cross, so only the clock can end either drain.
                val boundedBefore = appender.lines(Level.INFO, "nw.seam.drain-end", "$tag-")
                testScheduler.advanceTimeBy(DRAIN_BOUND + 1.seconds)
                pumpUntil { false }
                val ends = appender.lines(Level.INFO, "nw.seam.drain-end", "$tag-")

                assertAll(
                    {
                        assertEquals(
                            1,
                            overflows.size,
                            "one WARN per overflow episode, on the end whose hold filled: $overflows",
                        )
                    },
                    {
                        assertTrue(
                            overflows.single().contains("buffered=2") && overflows.single().contains("capacity=2"),
                            "…naming the cap it hit and what it was holding when it did: ${overflows.single()}",
                        )
                    },
                    {
                        assertEquals(
                            emptyList(),
                            boundedBefore,
                            "no drain may end before the bound expires — with both goodbyes withheld the " +
                                "clock is the only thing that can end one: $boundedBefore",
                        )
                    },
                    { assertEquals(2, ends.size, "both ends' drains are backstopped: $ends") },
                    {
                        assertEquals(
                            2,
                            ends.count { it.contains("via=bound") },
                            "…and both say the BOUND ended them, not the goodbye: $ends",
                        )
                    },
                )
            }
        }

    /**
     * The audit's second arm (#2425): a [registry] entry naming a link this seam is DRAINING.
     *
     * `broadcast`/`sendTo` route through `registry`, so such an entry would write into a socket the seam
     * has already said goodbye on — a send that goes nowhere, silently, which is #2425's whole shape one
     * level up. `NwSeam` makes the state unreachable (the dedup writes the loser into `draining` in the
     * same critical section that points `registry` at the winner), which is exactly why the arm needs a
     * rig: a guard that never fires passes identically whether or not it works.
     */
    @Test
    fun aRegistryEntryNamingADrainingLinkIsReportedAsAContractViolation() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val (a, b) = buildPair("drainbind")

                // CONTROL: a healthy pair, swept, reports nothing. Without it the subject below would be
                // satisfied by a check that fires unconditionally.
                oneProbe(count = 3)
                val healthy = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "drainbind-")

                val poisoned = a.seam.markWinnerDrainingForAuditRig(b.peerId)
                assertNotNull(poisoned, "rig did not fire: B was not registered on A")
                oneProbe()
                val reported = appender.lines(Level.ERROR, "nw.seam.registry.orphan", "drainbind-")

                assertAll(
                    { assertEquals(0, healthy.size, "a healthy binding reports nothing: $healthy") },
                    { assertEquals(1, reported.size, "the poisoned binding is reported once: $reported") },
                    {
                        assertTrue(
                            reported.single().contains("binding=draining"),
                            "…and says WHICH way it is unusable — tracked but drained, not untracked, " +
                                "which are different bugs with different next steps: ${reported.single()}",
                        )
                    },
                    {
                        assertTrue(
                            reported.single().contains("connId=${poisoned.value}") &&
                                reported.single().contains("draining=["),
                            "the binding identity plus the draining key set: ${reported.single()}",
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
