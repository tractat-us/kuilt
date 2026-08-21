@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.advanceTimeBy drives the dump schedule

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
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The #2420 formation-stuck dump, asserted on the **real log emission** — level, count and text —
 * because the line *is* the deliverable. A spy would prove the loom consulted an instrument; a captured
 * `ILoggingEvent` proves what a field capture would carry, at the level that decides whether a release
 * iPhone records it at all.
 *
 * Every arm here rigs the condition it claims to detect and **counts the firings**. That matters more
 * than usual: a diagnostic that never fires is green by absence, which is the exact failure #2420 exists
 * to remove — so there is a positive arm (it fires when stuck), a negative arm (it stays silent on an
 * idle device with nobody around), and a boundedness arm (the schedule is geometric, not a poll).
 *
 * Capture is a Logback [ListAppender] on the `us.tractat.kuilt.nw` logger, as in
 * `NwSeamWedgeDiagnosticsTest` — which makes this a `jvmTest`: Logback is a JVM backend and the subject
 * ([NwLoom]) is `commonMain`, so nothing platform-specific is lost. Peer ids are prefixed per test so no
 * line can be attributed to a sibling test sharing the JVM.
 */
class NwFormationStuckDumpTest {

    private companion object {
        const val TYPE = "_kuilt._tcp"

        /** The event this whole class is about. */
        const val STUCK = "nw.loom.formation-stuck"

        fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
            repeat(maxPumps) {
                if (cond()) return true
                testScheduler.runCurrent()
            }
            return cond()
        }

        /** One seam per child scope, so one seam's teardown cannot cancel another's loops. */
        fun TestScope.seamScope(): CoroutineScope =
            CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
    }

    /**
     * Advance exactly [d] of virtual time and run what is scheduled *on* the new instant.
     * [kotlinx.coroutines.test.TestCoroutineScheduler.advanceTimeBy] runs only what is scheduled
     * strictly before it, so the `runCurrent` is what executes a timer sitting exactly on the boundary.
     */
    private fun TestScope.advance(d: Duration) {
        testScheduler.advanceTimeBy(d)
        testScheduler.runCurrent()
    }

    /**
     * End a still-pending [NwLoom.weave] and let its cleanup run — **mandatory** in every arm here, and
     * not merely tidiness.
     *
     * `weave` builds the seam on a parentless `SupervisorJob` scope precisely so a consumer cancelling the
     * caller does not kill the session, so `runTest` cancelling `backgroundScope` does not reach it either.
     * Left running, the redial loop and the formation-stuck loop keep re-arming timers on the test
     * scheduler, which then never goes idle — under `runTest` that is a **hang, not a failure**. Cancelling
     * the weave takes the documented back-out path (`weave` closes the unreturned seam under
     * `NonCancellable`), which cancels the whole scope.
     */
    private fun TestScope.endWeave(weaveJob: Job) {
        weaveJob.cancel()
        pumpUntil { false }
    }

    // ── the trigger: fires when stuck, and only then ────────────────────────────

    /**
     * The positive arm. A loom that can SEE a peer and cannot form a session with it dumps its state at
     * WARN, halfway to the weave timeout — while the weave is still in progress, so the state that
     * explains the eventual failure is in the trail *before* the failure.
     *
     * The rig is the field's own shape reduced to one device: a bare advertiser that is discoverable and
     * dialable but never handshakes back, so the endpoint is discovered, a redialer is armed, and
     * `settledEndpoints` stays empty for as long as the seam lives. Nothing else in the loom emits a
     * single line about that state.
     *
     * Note what the bounded arm proves. On a single weave attempt at most ONE periodic dump can fire:
     * the next one would be due at `2.5 × weaveTimeout` (halfway, then ×4), which is past the timeout
     * that closes the seam. Repeated dumping is a property of a seam that WOVE and then re-formed — see
     * [theDumpStopsWhileWovenAndReArmsGeometricallyOnTheReForm].
     */
    @Test
    fun aLoomThatCanSeeAPeerAndHasNotConnectedDumpsItsStateAtWarn() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "stuck"
                val radio = FakeNwRadio()
                val loomApi = FakeNwApi(radio, deviceId = "$tag-dev-a", serviceName = "$tag-peer-a")
                // A bare advertiser: discoverable and dialable, but with no seam behind it, so it never
                // answers the NwHello and the dialled endpoint never settles.
                val peerApi = FakeNwApi(radio, deviceId = "$tag-dev-b", serviceName = "$tag-ep-b")
                peerApi.startListening("$tag-ep-b", TYPE)
                val loom = NwLoom(
                    loomApi,
                    serviceType = TYPE,
                    selfId = PeerId("$tag-peer-a"),
                    random = Random(0),
                    weaveTimeout = 100.seconds,
                )
                val weaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatchingCancellable { loom.join(InMemoryTag(sessionName = "lobby", peerKey = "$tag-peer-a")) }
                }
                assertTrue(pumpUntil { loomApi.connectCalls >= 1 }, "rig: the loom must discover and dial the advertiser")

                // BEFORE the first interval: nothing. The dump is scheduled, not unconditional.
                advance(49.seconds)
                val beforeFirst = appender.lines(Level.WARN, STUCK, tag)

                // ON the first interval (weaveTimeout / 2): exactly one.
                advance(1.seconds)
                val atFirst = appender.lines(Level.WARN, STUCK, tag)

                // BOUND: nothing further before the weave times out — the next periodic is due at ×4.
                advance(49.seconds)
                val beforeTimeout = appender.lines(Level.WARN, STUCK, tag)

                endWeave(weaveJob)

                assertAll(
                    { assertEquals(0, beforeFirst.size, "nothing may fire before weaveTimeout/2: $beforeFirst") },
                    { assertEquals(1, atFirst.size, "exactly one dump at weaveTimeout/2: $atFirst") },
                    {
                        assertEquals(
                            1,
                            beforeTimeout.size,
                            "the schedule is geometric, not a poll — no second dump inside one weave: $beforeTimeout",
                        )
                    },
                    {
                        assertTrue(
                            atFirst.single().contains("state=Weaving") && atFirst.single().contains("settled=[]"),
                            "the wedge itself: never woven, nothing settled: ${atFirst.single()}",
                        )
                    },
                    {
                        assertTrue(
                            atFirst.single().contains("$tag-ep-b(name=$tag-ep-b backoff="),
                            "the armed redialer, with the back-off it has reached: ${atFirst.single()}",
                        )
                    },
                    {
                        assertTrue(
                            atFirst.single().contains("parked=false"),
                            "…and whether the loop has stopped dialling, which is the silent state: ${atFirst.single()}",
                        )
                    },
                    {
                        assertTrue(
                            atFirst.single().contains("UNRESOLVED"),
                            "a link that opened and never handshook must be named as such, not omitted: " +
                                atFirst.single(),
                        )
                    },
                    {
                        assertTrue(
                            atFirst.single().contains("advertised=$tag-peer-a→$tag-peer-a renamed=false"),
                            "the uncontested name case — the control for the rename arm: ${atFirst.single()}",
                        )
                    },
                )
            }
        }

    /**
     * The negative arm, and the one that makes every count above mean something: a device sitting in an
     * empty lobby with nobody around must emit **nothing**, for as long as it waits.
     *
     * Without it "the dump fires" would be satisfied by a line that fires unconditionally — which would be
     * strictly worse than no dump at all, since it would flood a bounded on-device store with the state of
     * every app that ever opened a session and waited.
     *
     * The rig receipt is `nw.loom.self-skip`: the loom really did browse, really was delivered its own
     * advertisement (real mDNS does this, and so does [FakeNwRadio]), and dropped it. Without asserting
     * that, the zero would be green because discovery never ran at all.
     */
    @Test
    fun anIdleLoomWithNobodyAroundNeverDumps() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "idle"
                val radio = FakeNwRadio()
                val loomApi = FakeNwApi(radio, deviceId = "$tag-dev-a", serviceName = "$tag-peer-a")
                val loom = NwLoom(
                    loomApi,
                    serviceType = TYPE,
                    selfId = PeerId("$tag-peer-a"),
                    random = Random(0),
                    weaveTimeout = 100.seconds,
                )
                val weaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatchingCancellable { loom.join(InMemoryTag(sessionName = "lobby", peerKey = "$tag-peer-a")) }
                }
                pumpUntil { false }
                val selfSkips = appender.lines(Level.INFO, "nw.loom.self-skip", tag)

                advance(99.seconds)
                val dumps = appender.lines(Level.WARN, STUCK, tag)

                endWeave(weaveJob)

                assertAll(
                    {
                        assertTrue(
                            selfSkips.isNotEmpty(),
                            "rig receipt — discovery must have RUN and delivered this loom its own " +
                                "advertisement, or the zero below is green because nothing happened at all",
                        )
                    },
                    {
                        assertEquals(
                            emptyList(),
                            dumps,
                            "an idle device with nobody around must stay silent for the whole wait: $dumps",
                        )
                    },
                )
            }
        }

    // ── the weave-timeout path ──────────────────────────────────────────────────

    /**
     * A failed weave must leave behind the state that explains it, not a bare "timed out".
     *
     * Two things are pinned. The dump fires **unconditionally** on this path — the trigger's
     * "did we see anybody" guard exists to keep the *periodic* dump off idle devices, and here
     * "we saw nobody at all for the whole timeout" is itself the finding. And it fires **before** the
     * seam is discarded: `close` wipes `settledEndpoints` and cancels every redialer, so a dump taken
     * afterwards would report a blameless empty seam and quietly say nothing.
     */
    @Test
    fun theWeaveTimeoutDumpsTheStateThatExplainsItBeforeTheSeamIsDiscarded() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "timeout"
                val radio = FakeNwRadio()
                val loomApi = FakeNwApi(radio, deviceId = "$tag-dev-a", serviceName = "$tag-peer-a")
                val peerApi = FakeNwApi(radio, deviceId = "$tag-dev-b", serviceName = "$tag-ep-b")
                peerApi.startListening("$tag-ep-b", TYPE)
                val loom = NwLoom(
                    loomApi,
                    serviceType = TYPE,
                    selfId = PeerId("$tag-peer-a"),
                    random = Random(0),
                    weaveTimeout = 4.seconds,
                )
                var failure: Throwable? = null
                val weaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    failure = runCatchingCancellable {
                        loom.join(InMemoryTag(sessionName = "lobby", peerKey = "$tag-peer-a"))
                    }.exceptionOrNull()
                }
                assertTrue(pumpUntil { loomApi.connectCalls >= 1 }, "rig: the loom must discover and dial the advertiser")

                advance(5.seconds)
                pumpUntil { failure != null }
                val dumps = appender.lines(Level.WARN, STUCK, tag)
                val onTimeout = dumps.filter { it.contains("reason=weave-timeout") }

                endWeave(weaveJob)

                assertAll(
                    { assertTrue(failure is NwUnreachableException, "the weave must still fail as before: $failure") },
                    {
                        assertEquals(
                            2,
                            dumps.size,
                            "one periodic dump at weaveTimeout/2 and one on the timeout path: $dumps",
                        )
                    },
                    { assertEquals(1, onTimeout.size, "exactly one dump attributed to the timeout: $dumps") },
                    {
                        assertTrue(
                            onTimeout.single().contains("$tag-ep-b(name=$tag-ep-b backoff="),
                            "taken BEFORE the discard — a post-close dump would show no redialer at all: " +
                                onTimeout.single(),
                        )
                    },
                    {
                        assertTrue(
                            onTimeout.single().contains("state=Weaving") &&
                                onTimeout.single().contains("peers=[$tag-peer-a]"),
                            "…and the seam as it was when it gave up, not after it was torn: ${onTimeout.single()}",
                        )
                    },
                )
            }
        }

    /**
     * The state must reach the **exception message**, not only the WARN log (#2386).
     *
     * #2484 put [logFormationStuck] on the weave-timeout path, which is what a *field capture* reads. A
     * CI failure is read from somewhere else entirely: a Kotlin/Native test renders through the results
     * XML, and whether log capture reaches that XML is not guaranteed — on the two K/N lanes of
     * `apple-nightly` it does not. So the one artifact a CI reader is certain to have is the throwable,
     * and a bare `"no peer reached … within 30s"` names the deadline and nothing else. That is the whole
     * reason #2386 read as "a contended host is indistinguishable from a broken fabric".
     *
     * The last assertion is the load-bearing one, and it is not a duplicate of the others: the log line
     * is emitted **before** `discardUnreturnedSeam` and the exception is thrown **after** it, and `close`
     * wipes `settledEndpoints` and cancels every redialer. So two independent `render(formation)` calls
     * would disagree — the exception would carry a blameless empty seam while the log carried the real
     * one. Requiring the exception to contain the log's exact rendered tail pins that the render is
     * computed **once** and shared, which is the only shape in which the two cannot diverge.
     */
    @Test
    fun theWeaveTimeoutFailureCarriesTheFormationStateAndNotJustTheDeadline() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "xmsg"
                val radio = FakeNwRadio()
                val loomApi = FakeNwApi(radio, deviceId = "$tag-dev-a", serviceName = "$tag-peer-a")
                val peerApi = FakeNwApi(radio, deviceId = "$tag-dev-b", serviceName = "$tag-ep-b")
                peerApi.startListening("$tag-ep-b", TYPE)
                val loom = NwLoom(
                    loomApi,
                    serviceType = TYPE,
                    selfId = PeerId("$tag-peer-a"),
                    random = Random(0),
                    weaveTimeout = 4.seconds,
                )
                var failure: Throwable? = null
                val weaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    failure = runCatchingCancellable {
                        loom.join(InMemoryTag(sessionName = "lobby", peerKey = "$tag-peer-a"))
                    }.exceptionOrNull()
                }
                assertTrue(pumpUntil { loomApi.connectCalls >= 1 }, "rig: the loom must discover and dial the advertiser")

                advance(5.seconds)
                pumpUntil { failure != null }
                val onTimeout = appender.lines(Level.WARN, STUCK, tag).single { it.contains("reason=weave-timeout") }

                endWeave(weaveJob)

                val message = assertNotNull(
                    (failure as? NwUnreachableException)?.message,
                    "rig: the weave must have failed as NwUnreachableException, got: $failure",
                )
                // Everything the ONE render produced, with the log's own prefix stripped off.
                val renderedInLog = onTimeout.substringAfter("reason=weave-timeout after=4s ")

                assertAll(
                    {
                        assertTrue(
                            message.contains("within 4s") && message.contains("serviceType=$TYPE"),
                            "the deadline and the type must survive — this adds state, it does not replace " +
                                "what was there: $message",
                        )
                    },
                    {
                        assertTrue(
                            message.contains("state=Weaving") && message.contains("settled=[]"),
                            "the wedge itself must be readable from the throwable alone: $message",
                        )
                    },
                    {
                        assertTrue(
                            message.contains("$tag-ep-b(name=$tag-ep-b backoff="),
                            "…including WHICH endpoint was being dialled to no effect, which is what " +
                                "separates a busy box from a broken fabric: $message",
                        )
                    },
                    {
                        assertTrue(
                            message.contains(renderedInLog),
                            "the log line and the exception must carry the SAME render, or a reader " +
                                "correlating the two is reading a seam that was closed in between — " +
                                "log=<$renderedInLog> message=<$message>",
                        )
                    },
                )
            }
        }

    // ── the schedule: cancelled on Woven, re-armed geometrically on the re-form ──

    /**
     * The wedge shape a device actually sits in for an hour: a seam that WOVE, lost its peer, and can
     * never reach it again (#1513 re-forms `Woven → Weaving` rather than tearing, so the seam lives on
     * and the consumer sees a session that is "still connecting" forever).
     *
     * Three properties in one run, because they are the same mechanism seen from three sides:
     *  - **Silence while Woven.** Five minutes of a healthy session emits nothing. Without this arm every
     *    count below would be satisfied by a dump that fires regardless of state.
     *  - **Re-armed on the re-form**, at the FIRST interval — a wedge after a drop is as interesting as
     *    one at startup, and inheriting a five-minute silence would hide it.
     *  - **Geometric**, and capped: 10 s, then 40 s, then 160 s, then 300 s. Four lines across eight
     *    minutes of wedge, against the ~480 a per-second poll would write.
     *
     * The peer stops advertising before the link is cut, so the redial can never resolve it again — which
     * also makes the widened trigger load-bearing and asserted: `visible=[]` (the browse roster was pruned
     * on the removal) while the redialer is still hammering. Keying the trigger on the roster alone would
     * go silent in exactly this shape.
     */
    @Test
    fun theDumpStopsWhileWovenAndReArmsGeometricallyOnTheReForm() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "reform"
                val radio = FakeNwRadio()
                val loomApi = FakeNwApi(radio, deviceId = "$tag-dev-a", serviceName = "$tag-peer-a")
                val peerApi = FakeNwApi(radio, deviceId = "$tag-dev-b", serviceName = "$tag-ep-b")
                // A real seam behind the advertiser, so the pair genuinely weaves; no loom on that side, so
                // only the device under test redials.
                val peerSeam = NwSeam(
                    selfId = PeerId("$tag-peer-b"),
                    api = peerApi,
                    scope = seamScope(),
                    random = Random(7),
                )
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { peerSeam.incoming.collect { } }
                peerApi.startListening("$tag-ep-b", TYPE)
                val loom = NwLoom(
                    loomApi,
                    serviceType = TYPE,
                    selfId = PeerId("$tag-peer-a"),
                    random = Random(0),
                    weaveTimeout = 20.seconds,
                )
                var woven: Seam? = null
                val weaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    woven = loom.join(InMemoryTag(sessionName = "lobby", peerKey = "$tag-peer-a"))
                }
                assertTrue(pumpUntil { woven?.state?.value is SeamState.Woven }, "rig: the pair must weave first")
                val seam = assertNotNull(woven)

                // CONTROL: a healthy session, five minutes of it, emits nothing.
                advance(300.seconds)
                val whileWoven = appender.lines(Level.WARN, STUCK, tag)

                // Break it beyond repair: stop advertising FIRST (so a redial can never resolve the name
                // again), then cut the live link.
                peerApi.stopListening()
                pumpUntil { false }
                val link = assertNotNull(
                    radio.openedLinks.firstOrNull { it.endOn("$tag-dev-b") != null },
                    "rig: a link between the two devices must exist to cut",
                )
                radio.disconnect("$tag-dev-b", assertNotNull(link.endOn("$tag-dev-b")))
                assertTrue(
                    pumpUntil { seam.state.value is SeamState.Weaving },
                    "rig: the seam must re-form to Weaving rather than tear (#1513): ${seam.state.value}",
                )
                val atBreak = appender.lines(Level.WARN, STUCK, tag).size

                advance(9.seconds)
                val before1 = appender.lines(Level.WARN, STUCK, tag).size
                advance(1.seconds)
                val after1 = appender.lines(Level.WARN, STUCK, tag)
                advance(39.seconds)
                val before2 = appender.lines(Level.WARN, STUCK, tag).size
                advance(1.seconds)
                val after2 = appender.lines(Level.WARN, STUCK, tag).size
                advance(159.seconds)
                val before3 = appender.lines(Level.WARN, STUCK, tag).size
                advance(1.seconds)
                val after3 = appender.lines(Level.WARN, STUCK, tag).size
                advance(299.seconds)
                val before4 = appender.lines(Level.WARN, STUCK, tag).size
                advance(1.seconds)
                val after4 = appender.lines(Level.WARN, STUCK, tag).size

                // This weave RETURNED, so cancelling its job is a no-op — the seam is the consumer's now,
                // and closing it is the only thing that cancels the scope its loops (and their re-arming
                // timers) run on. See [endWeave] for why that is mandatory rather than tidy.
                assertTrue(weaveJob.isCompleted, "rig: the weave must have returned a seam, not still be running")
                seam.close(CloseReason.Normal)
                pumpUntil { false }

                assertAll(
                    {
                        assertEquals(
                            emptyList(),
                            whileWoven,
                            "a Woven seam must emit nothing however long it is held: $whileWoven",
                        )
                    },
                    { assertEquals(0, atBreak, "…including through the break itself: $atBreak") },
                    { assertEquals(0, before1, "re-armed at the FIRST interval (10s), not sooner: $before1") },
                    { assertEquals(1, after1.size, "…and it fires on it: $after1") },
                    { assertEquals(1, before2, "second interval is ×4 (40s), not another 10s: $before2") },
                    { assertEquals(2, after2, "…and fires on it") },
                    { assertEquals(2, before3, "third interval is ×4 again (160s)") },
                    { assertEquals(3, after3, "…and fires on it") },
                    { assertEquals(3, before4, "fourth interval is CAPPED at 300s, not 640s") },
                    { assertEquals(4, after4, "…and fires on it — four lines across eight minutes of wedge") },
                    {
                        assertTrue(
                            after1.single().contains("visible=[]"),
                            "the peer left the browse roster on its removal: ${after1.single()}",
                        )
                    },
                    {
                        assertTrue(
                            after1.single().contains("$tag-ep-b(name=$tag-ep-b backoff="),
                            "…while its redialer is still hammering, which is why the trigger cannot key on " +
                                "the roster alone: ${after1.single()}",
                        )
                    },
                    {
                        assertTrue(
                            after1.single().contains("state=Weaving") && after1.single().contains("settled=[]"),
                            "…and the endpoint has genuinely un-settled: ${after1.single()}",
                        )
                    },
                )
            }
        }

    // ── item 2: this device's OWN Bonjour rename ────────────────────────────────

    /**
     * The dump must say what this device is **actually** advertising, not what it asked for.
     *
     * mDNS resolves an instance-name collision by renaming the later advertiser (`alice` → `alice (2)`),
     * and reports it only to the renamed device — asynchronously, after the listener is up. In the
     * 2026-08-15 session that landed 6 s after the fatal dial and was recoverable only from the OTHER
     * phone's capture. `RealNwApi` now wires
     * `nw_listener_set_advertised_endpoint_changed_handler`; this pins the CONSEQUENCE — that the fact
     * reaches the one line a diagnostician reads — on the JVM, where [FakeNwApi.emitAdvertisedRename]
     * stands in for the native callback.
     *
     * Both arms in one run, so `renamed=` is a measurement rather than a constant: the first dump is taken
     * before the rename and must say `false`, the second after it and must say `true`.
     */
    @Test
    fun theDumpNamesAnMdnsRenameOfThisDevicesOwnAdvertisement() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "rename"
                val radio = FakeNwRadio()
                val loomApi = FakeNwApi(radio, deviceId = "$tag-dev-a", serviceName = "$tag-peer-a")
                val peerApi = FakeNwApi(radio, deviceId = "$tag-dev-b", serviceName = "$tag-ep-b")
                peerApi.startListening("$tag-ep-b", TYPE)
                val loom = NwLoom(
                    loomApi,
                    serviceType = TYPE,
                    selfId = PeerId("$tag-peer-a"),
                    random = Random(0),
                    weaveTimeout = 100.seconds,
                )
                val weaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatchingCancellable { loom.join(InMemoryTag(sessionName = "lobby", peerKey = "$tag-peer-a")) }
                }
                assertTrue(pumpUntil { loomApi.connectCalls >= 1 }, "rig: the loom must discover and dial")

                // Dump 1 — before any rename.
                advance(50.seconds)
                val beforeRename = appender.lines(Level.WARN, STUCK, tag)

                // mDNS renames US. Nothing else changes.
                loomApi.emitAdvertisedRename("$tag-peer-a (2)")
                pumpUntil { false }

                // Dump 2 — the weave-timeout one, after the rename.
                advance(50.seconds)
                pumpUntil { false }
                val all = appender.lines(Level.WARN, STUCK, tag)
                val afterRename = all.drop(beforeRename.size)

                endWeave(weaveJob)

                assertAll(
                    { assertEquals(1, beforeRename.size, "one dump before the rename: $beforeRename") },
                    {
                        assertTrue(
                            beforeRename.single().contains("advertised=$tag-peer-a→$tag-peer-a renamed=false"),
                            "the control — an uncontested name reports agreement: ${beforeRename.single()}",
                        )
                    },
                    { assertEquals(1, afterRename.size, "one further dump after it: $all") },
                    {
                        assertTrue(
                            afterRename.single().contains("advertised=$tag-peer-a→$tag-peer-a (2) renamed=true"),
                            "…and the renamed one reports BOTH names and the disagreement, so a reader can " +
                                "see that a dial armed for the requested name now reaches somebody else: " +
                                afterRename.single(),
                        )
                    },
                )
            }
        }

    // ── item 3: the redial campaign's health line ───────────────────────────────

    /**
     * A redialer that reaches the back-off ceiling without ever connecting says so, **once**.
     *
     * Before this, that state was reported only by `nw.loom.redial-failed` — at DEBUG, which a release
     * device's store does not retain at all (a capture taken during the #2425 wedge held 664 INFO / 7 WARN
     * / 1 ERROR and zero DEBUG records, in a store that had not wrapped), and with no attempt count. So a
     * capture could not distinguish "dialling hard and getting nowhere" from "not dialling".
     *
     * `attempts=5` is arithmetic, not a magic number: 250 ms doubling to a 5 s ceiling takes five dials.
     * Asserting the number rather than its presence is what makes it a measurement — a hardcoded constant
     * would satisfy "contains attempts=".
     */
    @Test
    fun aRedialerThatReachesTheBackOffCeilingReportsItOnceAtWarn() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withCapture { appender ->
                val tag = "ceiling"
                val radio = FakeNwRadio()
                val loomApi = FakeNwApi(radio, deviceId = "$tag-dev-a", serviceName = "$tag-peer-a")
                val peerApi = FakeNwApi(radio, deviceId = "$tag-dev-b", serviceName = "$tag-ep-b")
                peerApi.startListening("$tag-ep-b", TYPE)
                val loom = NwLoom(
                    loomApi,
                    serviceType = TYPE,
                    selfId = PeerId("$tag-peer-a"),
                    random = Random(0),
                    weaveTimeout = 1000.seconds,
                )
                val weaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatchingCancellable { loom.join(InMemoryTag(sessionName = "lobby", peerKey = "$tag-peer-a")) }
                }
                assertTrue(pumpUntil { loomApi.connectCalls >= 1 }, "rig: the loom must dial at least once")

                // CONTROL: one second in, the campaign is still ramping (250 ms → 500 ms → 1 s) and has not
                // reached the ceiling. Without this the count below would be satisfied by a line that fires
                // on the first dial.
                advance(1.seconds)
                val early = appender.lines(Level.WARN, "nw.loom.redial-ceiling", tag)
                val earlyDials = loomApi.connectCalls

                advance(20.seconds)
                val atCeiling = appender.lines(Level.WARN, "nw.loom.redial-ceiling", tag)

                // BOUND: a further minute at the ceiling is a dozen more dials and no more lines.
                advance(60.seconds)
                val later = appender.lines(Level.WARN, "nw.loom.redial-ceiling", tag)
                val laterDials = loomApi.connectCalls

                endWeave(weaveJob)

                assertAll(
                    { assertEquals(0, early.size, "the ceiling is not reached on the first dials: $early") },
                    { assertEquals(1, atCeiling.size, "reported exactly once when it is: $atCeiling") },
                    {
                        assertEquals(
                            1,
                            later.size,
                            "one line per CAMPAIGN, not per dial — a minute at the ceiling adds nothing: $later",
                        )
                    },
                    {
                        assertTrue(
                            laterDials > earlyDials + 5,
                            "rig receipt — the loop really did keep dialling through the quiet minute " +
                                "($earlyDials → $laterDials), so the single line above is boundedness and " +
                                "not a stopped campaign",
                        )
                    },
                    {
                        assertTrue(
                            atCeiling.single().contains("attempts=5"),
                            "250ms doubling to a 5s ceiling is five dials — a measurement, not a constant: " +
                                atCeiling.single(),
                        )
                    },
                    {
                        assertTrue(
                            atCeiling.single().contains("endpoint=$tag-ep-b") &&
                                atCeiling.single().contains("serviceName=$tag-ep-b"),
                            "…naming WHICH endpoint is being dialled to no effect: ${atCeiling.single()}",
                        )
                    },
                )
            }
        }

    // ── the public entry point ──────────────────────────────────────────────────

    /**
     * [NwLoom.dumpFormationState] is callable on demand — from a consumer's "still connecting…" timeout, a
     * crash reporter, or a harness — and answers honestly when there is nothing to describe.
     *
     * The `formations=none` arm matters because the alternative failure is silent: a loom whose seam has
     * been closed must not keep rendering the dead session's state as if it were live, and the only thing
     * that removes it is the seam scope's completion.
     */
    @Test
    fun theDumpIsCallableOnDemandAndReportsWhenThereIsNoLiveFormation() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val tag = "ondemand"
            val radio = FakeNwRadio()
            val loomApi = FakeNwApi(radio, deviceId = "$tag-dev-a", serviceName = "$tag-peer-a")
            val peerApi = FakeNwApi(radio, deviceId = "$tag-dev-b", serviceName = "$tag-ep-b")
            peerApi.startListening("$tag-ep-b", TYPE)
            val loom = NwLoom(
                loomApi,
                serviceType = TYPE,
                selfId = PeerId("$tag-peer-a"),
                random = Random(0),
                weaveTimeout = 4.seconds,
            )
            val beforeWeave = loom.dumpFormationState()

            var failure: Throwable? = null
            val weaveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatchingCancellable {
                    loom.join(InMemoryTag(sessionName = "lobby", peerKey = "$tag-peer-a"))
                }.exceptionOrNull()
            }
            assertTrue(pumpUntil { loomApi.connectCalls >= 1 }, "rig: the loom must discover and dial")
            val whileWeaving = loom.dumpFormationState()

            advance(5.seconds)
            pumpUntil { failure != null }
            // The scope's completion callback is what deregisters; give it a virtual instant to run.
            advance(1.milliseconds)
            val afterDiscard = loom.dumpFormationState()

            endWeave(weaveJob)

            assertAll(
                { assertTrue(beforeWeave.contains("formations=none"), "no weave yet: $beforeWeave") },
                {
                    assertTrue(
                        whileWeaving.contains("state=Weaving") && whileWeaving.contains("$tag-ep-b"),
                        "an in-flight formation renders its real state: $whileWeaving",
                    )
                },
                { assertTrue(failure is NwUnreachableException, "the weave must still fail as before: $failure") },
                {
                    assertTrue(
                        afterDiscard.contains("formations=none"),
                        "a discarded seam must not keep being described as live: $afterDiscard",
                    )
                },
            )
        }

    // ── capture plumbing (mirrors NwSeamWedgeDiagnosticsTest) ────────────────────

    /** Lines at [level] whose message contains [event] and this test's [scope] prefix. */
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
