@file:OptIn(ExperimentalForeignApi::class)

package spike.suite

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import spike.nw.SpikeNw
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.nw.NwLoom
import us.tractat.kuilt.nw.appleNwLoom
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.election.ElectionOutcome
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The on-device connectivity suite (#1467).
 *
 * Two phones run the same app: one taps **Host**, one taps **Join**. Each runs the same five-scenario
 * battery against the *real* `kuilt-nw` fabric (plus the raw Network.framework control), captures its
 * network environment, and emits a shareable pass/fail report the field user texts back — no Mac.
 *
 * Scenarios 2–5 are **symmetric**: both peers `weave(Rendezvous.New)` the same session, so they meet
 * regardless of who tapped which button. The Host/Join button only shapes the raw baseline (scenario 1,
 * where the host echoes and the join pings) and the teardown side (scenario 4, where the host drops the
 * link). Host election (scenario 3) self-elects `min(peerId)` — the button is irrelevant there.
 *
 * **Scenarios 6 and 7 are separate and operator-driven**: both need a human to toggle Airplane Mode,
 * which no API can do, so neither runs in the automatic battery. Each gets its own pair of buttons —
 * and there the Host/Join button *does* pick the role, because the operator has to know which phone to
 * take offline. Scenario 6 ([scenarioLocalFabric], #1712) asks *"whose network died?"*; scenario 7
 * ([scenarioSubTimeoutBlip], #1637) asks *"does a blip too short for the host to notice still
 * recover?"* — and is **expected to FAIL until #1637 is fixed**.
 *
 * Entry from Swift is non-suspending: [start] launches the battery on a background scope and reports
 * progress through callbacks (the SwiftUI layer hops them to the main thread).
 */
public class ConnectivitySuite {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val results = mutableListOf<ScenarioResult>()

    /**
     * Run the full battery for [role] (`"host"` or `"join"`). [onLog] streams every hop live; [onScenario]
     * fires once per finished scenario (drives the live matrix); [onComplete] delivers the final shareable
     * report text.
     *
     * [onPrompt] carries an **instruction for the human holding the phone** — the operator-driven
     * scenarios 6 and 7 use it to ask for an Airplane Mode toggle. It is deliberately a separate channel
     * from [onLog] so the UI can render it as a banner the operator cannot miss; an empty string clears
     * it. The automatic scenarios never call it.
     */
    public fun start(
        role: String,
        onLog: (String) -> Unit,
        onScenario: (ScenarioResult) -> Unit,
        onPrompt: (String) -> Unit,
        onComplete: (String) -> Unit,
    ) {
        // #1837 step 1 — the ONE call site that makes a run durable. Every line below is written to a
        // per-run file in the app container *before* it reaches the UI, so the trace survives an app
        // restart, a `--terminate-existing` relaunch, and the Airplane Mode window — the one window a
        // cabled `devicectl --console` cannot see, and the window scenario 6 exists to test. Pull it
        // afterwards with `spike/collect-logs.sh`. Capture failure is never a scenario failure: `open`
        // always returns a sink, and a sink that could not open its file says so once and discards.
        val capture = SuiteLogCapture.open(role)
        val uninstallTee = capture.installFabricTee()
        val log: (String) -> Unit = { line -> capture.line(line); onLog(line) }
        val complete: (String) -> Unit = { text -> capture.block("=== report ===", text); onComplete(text) }
        scope.launch {
            try {
                log(capture.warning ?: "trace → ${capture.path}")
                runSuite(role, log, onScenario, onPrompt, complete, capture)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("suite crashed: ${e::class.simpleName}: ${e.message}")
                onPrompt("")
                complete(
                    SuiteReport(role, NSDate().timeIntervalSince1970, EnvSnapshot.UNKNOWN, deviceDescription(), results.toList()).text,
                )
            } finally {
                uninstallTee()
                capture.close()
            }
        }
    }

    /** Abort a running battery (e.g. the user backgrounds/leaves). Idempotent. */
    public fun cancel() {
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun runSuite(
        role: String,
        onLog: (String) -> Unit,
        onScenario: (ScenarioResult) -> Unit,
        onPrompt: (String) -> Unit,
        onComplete: (String) -> Unit,
        // Scenario 7 alone needs it: its verdict is decided by watching the resume machine's own log
        // stream, because no in-process surface distinguishes the two ways a room can recover. See
        // [ResumeLaneProbe] for the search that established that.
        capture: SuiteLogCapture,
    ) {
        results.clear()
        // #2488: the one role that is not a scenario. See [runRenameProbe].
        if (role == RENAME_PROBE_ROLE) {
            runRenameProbe(onLog)
            onComplete("rename-probe done")
            return
        }
        // A "-s4" role suffix runs ONLY scenario 4 — the #1467 controlled experiment. Scenario 4's leg1
        // is structurally identical to scenario 2 (same weave call, different service type), yet it timed
        // out at 45s in the field while 2/3/5 wove in under a second. Running it alone, in a process where
        // no earlier scenario has left a listener/browser alive, isolates "accumulated state" from
        // "intrinsic to this service type" — one variable.
        val s4Only = role.endsWith(S4_SUFFIX)
        // A "-s6" role suffix runs ONLY scenario 6 — the #1712 local-fabric gate. It runs alone because
        // it is the one scenario a HUMAN drives (iOS exposes no way to toggle Airplane Mode from an app),
        // so folding it into the automatic battery would block every run on an operator; and because the
        // room it stands up has to survive two outages, which no earlier scenario's leftover listener
        // should be able to perturb.
        val s6Only = role.endsWith(S6_SUFFIX)
        // A "-s7" role suffix runs ONLY scenario 7 — the #1637 sub-timeout-blip repro. Alone for the
        // same two reasons as scenario 6 (a human drives the radio; a leftover listener must not
        // perturb the one room under test), plus a third of its own: it is the ONLY scenario whose
        // heartbeat `timeout` is longer than the seam's path grace, and that config is what creates
        // the repro interval. Folding it into a battery would mean two different detect budgets in
        // one process.
        val s7Only = role.endsWith(S7_SUFFIX)
        val baseRole = role.removeSuffix(S4_SUFFIX).removeSuffix(S6_SUFFIX).removeSuffix(S7_SUFFIX)
        if (s4Only) {
            // Surface the fabric's OWN dial/connection logging (nw.loom.weave, nw.dial,
            // nw.api.state/close, the #1560 nw_error capture) — the boundary AFTER discovery, which
            // the field run proved is where scenario 4 actually fails.
            // The Darwin default factory writes to os_log (the unified log), which `devicectl
            // --console` does NOT capture — hence zero nw.* lines in the first diagnostic run.
            // DirectLoggerFactory prints to stdout, which --console does capture.
            KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
            KotlinLoggingConfiguration.direct.logLevel = Level.DEBUG
            onLog("fabric logging → DirectLoggerFactory@DEBUG (diagnostic run)")
        }
        onLog("suite start role=$baseRole s4Only=$s4Only s6Only=$s6Only s7Only=$s7Only")
        val env = captureEnv()
        onLog(env.line)
        val startedAt = NSDate().timeIntervalSince1970
        val device = deviceDescription()

        suspend fun step(r: suspend () -> ScenarioResult) {
            val result = r()
            results.add(result)
            onScenario(result)
            settle()
        }

        if (s4Only) {
            // Scenario 4 ALONE: no earlier loom exists, so no other PSK (a different serviceType ⇒
            // different derived key) can be in play. If -9864 errSSLUnknownPSKIdentity STILL appears
            // here, two devices with identical roomKey+serviceType are failing PSK identity — a real
            // fabric bug, not cross-talk from a leaked scenario-2 listener.
            step { scenarioTeardownReconnect(baseRole, onLog) }
        } else if (s6Only) {
            // Scenario 6 ALONE — the #1712 gate. Additive: it does not displace or renumber any of the
            // five, and the automatic battery below is byte-for-byte the run that has been passing 5/5.
            step { scenarioLocalFabric(baseRole, onLog, onPrompt) }
            onPrompt("")
        } else if (s7Only) {
            // Scenario 7 ALONE — the #1637 repro. Additive in exactly the same way scenario 6 was: it
            // renumbers nothing and the automatic battery below is untouched.
            step { scenarioSubTimeoutBlip(baseRole, onLog, onPrompt, capture) }
            onPrompt("")
        } else {
            step { scenarioRawRoundTrip(baseRole, onLog) }
            step { scenarioSeamWeave(baseRole, onLog) }
            step { scenarioElection(baseRole, onLog) }
            step { scenarioTeardownReconnect(baseRole, onLog) }
            step { scenarioSoak(baseRole, onLog) }
        }

        val report = SuiteReport(
            when {
                s4Only -> "$baseRole S4-ONLY"
                s6Only -> "$baseRole S6-ONLY (airplane-mode gate)"
                s7Only -> "$baseRole S7-ONLY (sub-timeout blip #1637)"
                else -> baseRole
            },
            startedAt,
            env,
            device,
            results.toList(),
        )
        onLog("suite done: ${report.passed}/${report.results.size} passed")
        onComplete(report.text)
    }

    // ── scenario framework ───────────────────────────────────────────────────

    private suspend fun scenario(
        id: Int,
        name: String,
        onLog: (String) -> Unit,
        block: suspend (hop: (String) -> Unit) -> Pair<Verdict, String>,
    ): ScenarioResult {
        val hops = mutableListOf<String>()
        val hop: (String) -> Unit = { s -> hops.add(s); onLog("[$id] $s") }
        val mark = TimeSource.Monotonic.markNow()
        return try {
            val (verdict, detail) = block(hop)
            ScenarioResult(id, name, verdict, mark.elapsedNow().inWholeMilliseconds, detail, hops.toList())
        } catch (e: Throwable) {
            // Whose cancellation is this? A scenario body may bound itself with `withTimeout`
            // (scenario 3 bounds both the lobby wait and the election), and that mints a
            // `TimeoutCancellationException` — a `CancellationException` — without cancelling us.
            // A `catch (CancellationException) { throw e }` here matched it first and made the FAIL
            // result below dead for a timing-out scenario: the throwable escaped `runSuite` as a
            // cancellation, so the whole suite stopped with no report and no crash log, which on
            // device is indistinguishable from the app being suspended (#2292).
            currentCoroutineContext().ensureActive()
            hop("EX ${e::class.simpleName}: ${e.message}")
            ScenarioResult(id, name, Verdict.FAIL, mark.elapsedNow().inWholeMilliseconds, e.message ?: "exception", hops.toList())
        }
    }

    // ── 1. raw Network.framework round-trip (transport control) ───────────────

    private suspend fun scenarioRawRoundTrip(role: String, onLog: (String) -> Unit): ScenarioResult =
        scenario(1, "Raw NW round-trip", onLog) { hop ->
            val nw = SpikeNw()
            nw.setOnLog { onLog("  raw: $it") }
            val rt = kotlinx.coroutines.CompletableDeferred<Int>()
            nw.setOnFirstRoundTrip { rt.complete(it) }
            if (role == "host") {
                hop("advertising raw NW (echo)")
                nw.startHost()
            } else {
                hop("browsing raw NW (ping)")
                nw.startJoin()
            }
            val rtt = withTimeoutOrNull(RAW_TIMEOUT) { rt.await() }
            nw.stop()
            when {
                rtt == null -> Verdict.FAIL to "no round-trip in $RAW_TIMEOUT"
                role == "host" -> { hop("echoed inbound frame"); Verdict.PASS to "echoed inbound frame" }
                else -> { hop("RTT=${rtt}ms"); Verdict.PASS to "RTT=${rtt}ms" }
            }
        }

    // ── 2. real kuilt-nw Seam weave ───────────────────────────────────────────

    /**
     * Force an mDNS instance-name collision so `nw.api.advertised-name renamed=true` can be observed on
     * real hardware (#2488, #2420's handler).
     *
     * **Why this needs a pinned name.** Since ADR-005 (#2416) the advertised Bonjour instance name IS
     * [NwLoom.selfId], which defaults to a fresh random UUID per peer — so two kuilt peers can never
     * collide, and the `… (2)` rename path is unreachable between them **by construction**. The renamed
     * advertiser is whichever registers *second*, so making the phone the one renamed requires some other
     * advertiser to already hold the name — which requires the phone's name to be predictable.
     *
     * Deliberately reuses [SVC2]: every service type must be declared in `app/project.yml`'s
     * `NSBonjourServices` or iOS blocks discovery silently, and SVC2 is already declared.
     *
     * Operator step, on a Mac, BEFORE launching this role:
     * ```
     * dns-sd -R kuiltrenameprobe _ksuite2._tcp local 9999
     * ```
     * Then `grep nw.api.advertised-name` — `renamed=true` with `advertised=kuiltrenameprobe (2)` is the
     * result being sought; `renamed=false` means Network.framework did not report the rename here.
     */
    private suspend fun runRenameProbe(onLog: (String) -> Unit) {
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
        KotlinLoggingConfiguration.direct.logLevel = Level.DEBUG
        onLog("rename-probe: pinned name=$RENAME_PROBE_NAME svc=$SVC2 window=$RENAME_PROBE_WINDOW")
        onLog("rename-probe: expecting mDNS to rename US if another advertiser already holds that name")
        val loom = appleNwLoom(
            SVC2,
            ROOM_KEY,
            selfId = PeerId(RENAME_PROBE_NAME),
            weaveTimeout = RENAME_PROBE_WINDOW,
        )
        // The weave never completes (no peer is expected); it is only how the listener gets started and
        // kept advertising for the window. Cancelled below rather than awaited.
        val weaving = scope.launch {
            try {
                loom.weave(Rendezvous.New(pattern()))
            } catch (failure: Throwable) {
                onLog("rename-probe: weave ended: $failure")
            }
        }
        delay(RENAME_PROBE_WINDOW)
        weaving.cancel()
        onLog("rename-probe: window elapsed — grep nw.api.advertised-name for renamed=")
    }

    private suspend fun scenarioSeamWeave(role: String, onLog: (String) -> Unit): ScenarioResult =
        scenario(2, "Fabric Seam weave", onLog) { hop ->
            hop("appleNwLoom.weave New svc=$SVC2")
            val loom = appleNwLoom(SVC2, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            hop("self=${loom.selfId.value.take(8)}")
            // Same instrumentation as scenario 4 — this is the WORKING control for the #1467 A/B.
            val seam = instrumentedWeave("s2", loom, hop)
                ?: return@scenario Verdict.FAIL to "weave never established on $SVC2"
            val woven = withTimeoutOrNull(5.seconds) { seam.state.first { it is SeamState.Woven } } != null
            val peers = seam.peers.value.size
            hop("wove peers=$peers state=${seam.state.value.short()}")
            seam.close(CloseReason.Normal)
            if (peers >= 2 && woven) Verdict.PASS to "peers=$peers Woven"
            else Verdict.FAIL to "peers=$peers woven=$woven"
        }

    // ── 3. host-election lobby establishment (the #1466 path) ─────────────────

    private suspend fun scenarioElection(role: String, onLog: (String) -> Unit): ScenarioResult =
        scenario(3, "Election establish", onLog) { hop ->
            hop("electLobby svc=$SVC3")
            val loom = appleNwLoom(SVC3, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            val factory = SeamRoomFactory.systemClock(loom, scope)
            val lobby = factory.electLobby(pattern())
            // weave already awaited the first peer; confirm the 2-peer lobby is live.
            withTimeout(WEAVE_TIMEOUT) { lobby.peers.first { it.size >= 2 } }
            val amHost = lobby.host.value == lobby.selfId
            hop("peers=2 host=${lobby.host.value.value.take(8)} self=${lobby.selfId.value.take(8)} amHost=$amHost")
            val room = withTimeout(ELECTION_TIMEOUT) {
                if (amHost) {
                    lobby.start(memberName = role)
                } else {
                    when (val outcome = lobby.awaitRoom(memberName = role)) {
                        is ElectionOutcome.Adopted -> outcome.room
                        // The elected host walked out mid-election: start() on this SAME lobby (#1483).
                        ElectionOutcome.BecameHost -> lobby.start(memberName = role)
                        is ElectionOutcome.Torn -> error("lobby collapsed mid-election: ${outcome.reason}")
                    }
                }
            }
            hop("adopted Room as ${if (amHost) "Host" else "Joiner"} roster=${room.roster.value.size}")
            room.leave()
            Verdict.PASS to "adopted as ${if (amHost) "host" else "member"}"
        }

    // ── 4. mid-session teardown + reconnect (ties to #1450) ───────────────────

    /**
     * `weave()` with the DISCOVERY boundary instrumented (#1467 field diagnosis).
     *
     * `weave()` blocks until woven or [WEAVE_TIMEOUT] and then *throws* — destroying every clue about
     * where it stalled (the first field run reported only "no peer reached", with no way to tell a
     * browse/advertise failure from a connection-establishment failure). This watches
     * [NwLoom.visiblePeers] — the browser's roster, carrying endpoint IDENTITIES — for the whole call
     * and drains that timeline into the report on BOTH the success and the throw path.
     *
     * Reading the result:
     *  - `events=0 finalSeen=0` ⇒ the two sides never saw each other: browse/advertise never met
     *    (Bonjour/AWDL/lingering-listener territory).
     *  - endpoints seen but the weave still timed out ⇒ discovery is FINE; connection establishment
     *    (TLS-PSK handshake / transport) is the failing hop — a different subsystem entirely.
     *
     * Returns the woven [Seam], or `null` if the weave threw (already logged).
     */
    private suspend fun instrumentedWeave(leg: String, loom: NwLoom, hop: (String) -> Unit): Seam? {
        val t = TimeSource.Monotonic.markNow()
        fun ms() = t.elapsedNow().inWholeMilliseconds
        // The watcher runs on another thread; a Channel keeps hop()'s list single-writer (drained below).
        val disco = Channel<String>(Channel.UNLIMITED)
        var seam: Seam? = null
        var failure: Throwable? = null
        coroutineScope {
            val watcher = launch {
                loom.visiblePeers.collect { eps ->
                    disco.trySend("t=${ms()}ms n=${eps.size} ids=[${eps.joinToString(",") { it.id }}]")
                }
            }
            try {
                seam = loom.weave(Rendezvous.New(pattern()))
            } catch (e: CancellationException) {
                watcher.cancel()
                throw e
            } catch (e: Throwable) {
                failure = e
            }
            watcher.cancel()
        }
        disco.close()
        var events = 0
        while (true) {
            val r = disco.tryReceive()
            if (!r.isSuccess) break
            hop("  $leg disco ${r.getOrNull()}")
            events++
        }
        val seen = loom.visiblePeers.value
        hop("$leg discovery: events=$events finalSeen=${seen.size} ids=[${seen.joinToString(",") { it.id }}] elapsed=${ms()}ms")
        val f = failure
        if (f != null) {
            hop("$leg weave THREW after ${ms()}ms: ${f::class.simpleName}: ${f.message}")
            return null
        }
        return seam
    }

    private suspend fun scenarioTeardownReconnect(role: String, onLog: (String) -> Unit): ScenarioResult =
        scenario(4, "Teardown+reconnect", onLog) { hop ->
            val t0 = TimeSource.Monotonic.markNow()
            fun ms() = t0.elapsedNow().inWholeMilliseconds

            hop("role=$role svcLeg1=$SVC4 svcLeg2=$SVC4B weaveTimeout=$WEAVE_TIMEOUT peerLossTimeout=$PEER_LOSS_TIMEOUT")
            hop("leg1 weave svc=$SVC4 t=${ms()}ms")
            val loomA = appleNwLoom(SVC4, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            hop("leg1 self=${loomA.selfId.value.take(8)}")
            val seamA = instrumentedWeave("leg1", loomA, hop)
                ?: return@scenario Verdict.FAIL to "leg1 never established on $SVC4 (teardown/reconnect NOT exercised)"
            // Load-bearing, not evidence: BOTH roles' drop assertions below are only discriminating
            // GIVEN a confirmed prior Woven (see the joiner comment). Captured like leg 2's `wovenB`.
            val wovenA = withTimeoutOrNull(5.seconds) { seamA.state.first { it is SeamState.Woven } } != null
            hop(
                "leg1 wove state=${seamA.state.value.short()} woven5s=$wovenA " +
                    "peers=[${seamA.peers.value.joinToString(",") { it.value.take(8) }}] t=${ms()}ms",
            )

            // The two sides of ONE drop are contractually OPPOSITE (#1513), so they assert opposite things.
            // `dropObserved` is deliberately role-neutral: each branch sets it from the signal ITS side is
            // promised, and the verdict below names which.
            val dropObserved: Boolean
            if (role == "host") {
                delay(2.seconds) // let the joiner settle on the live link before the drop
                hop("dropping link (host close) t=${ms()}ms")
                seamA.close(CloseReason.Normal)
                // Read, don't assume: `close` calls `latchTorn` first and `latchTorn` writes
                // `state = Torn` synchronously under the lock before returning, so this is
                // deterministic the instant `close` returns. Asserting it is what earns the verdict
                // word below — set unconditionally, the host would still print "close latched Torn"
                // if the latch silently failed.
                dropObserved = wovenA && seamA.state.value is SeamState.Torn
                hop("post-close stateA=${seamA.state.value.short()} torn=$dropObserved t=${ms()}ms")
            } else {
                // NOT Torn — that assertion (removed in #1836) could only ever time out. Per NwSeam's
                // "peer loss is recoverable — re-form, don't tear" (#1513), a joiner losing its last remote
                // goes Woven → Weaving, resets `peers` to `{selfId}`, keeps `incoming` open and waits for
                // NwLoom to redial. `Torn` latches on ONLY an explicit consumer `close()` or the initial
                // weave timeout; it "is never a consequence of peer loss".
                //
                // BOTH halves are required, and the pair is discriminating only GIVEN the confirmed
                // prior Woven (`wovenA`) — on its own it is ALSO the seam's pre-weave state, since
                // `_state` initialises to Weaving and `peers` to `{selfId}`. Do not drop `wovenA` from
                // the conjunction below: without it a non-blocking leg-1 weave would make this
                // scenario a silent guaranteed PASS, which is worse than the guaranteed FAIL it
                // replaces. Individually: `Weaving` alone does not exclude a seam that never wove, nor
                // the brief `addRemotePeer` transient where `peers` has grown but `_state` has not yet
                // flipped; `peers == {selfId}` alone is that same pre-weave starting value. (Leg 1's
                // hop above prints the two peer identities, so the pre-drop roster is legible in the
                // shared report.)
                //
                // Matched on the COMBINED pair rather than awaited-then-sampled: `evictPeerLocked` writes
                // `peers` and then `state` under one lock, so a `peers.value` read taken after the state
                // wait could in principle observe a roster a redial had already re-grown.
                hop("awaiting recoverable re-form (Weaving + peers={self}) after host drop t=${ms()}ms")
                val alone = setOf(seamA.selfId)
                val reformed = withTimeoutOrNull(PEER_LOSS_TIMEOUT) {
                    combine(seamA.state, seamA.peers) { s, p -> s to p }
                        .first { (s, p) -> s is SeamState.Weaving && p == alone }
                }
                dropObserved = wovenA && reformed != null
                hop(
                    "reformed=$dropObserved stateA=${seamA.state.value.short()} " +
                        "peersA=[${seamA.peers.value.joinToString(",") { it.value.take(8) }}] t=${ms()}ms",
                )
                seamA.close(CloseReason.Normal) // idempotent
            }

            hop("leg2 reweave svc=$SVC4B t=${ms()}ms")
            val loomB = appleNwLoom(SVC4B, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            hop("leg2 self=${loomB.selfId.value.take(8)}")
            val reMark = TimeSource.Monotonic.markNow()
            val seamB = instrumentedWeave("leg2", loomB, hop)
                ?: return@scenario Verdict.FAIL to "dropObserved=$dropObserved but leg2 never established on $SVC4B"
            val reMs = reMark.elapsedNow().inWholeMilliseconds
            // Verdict still keys off the IMMEDIATE peer count (unchanged semantics); the post-wait
            // sample is pure evidence — it tells us whether a convergence wait would have mattered.
            val peers = seamB.peers.value.size
            val wovenB = withTimeoutOrNull(5.seconds) { seamB.state.first { it is SeamState.Woven } } != null
            hop(
                "leg2 wove state=${seamB.state.value.short()} peersImmediate=$peers " +
                    "peersAfter5s=${seamB.peers.value.size} woven5s=$wovenB in ${reMs}ms",
            )
            seamB.close(CloseReason.Normal)
            if (dropObserved && peers >= 2) {
                // The two roles PASS for contractually DIFFERENT reasons, so the verdict says which — two
                // reports reading identically would hide the asymmetry the scenario exists to demonstrate.
                val saw = if (role == "host") "close latched Torn" else "peer-loss re-form seen"
                Verdict.PASS to "$saw; re-wove in ${fmtMs(reMs)}"
            } else {
                Verdict.FAIL to "dropObserved=$dropObserved reconnect peers=$peers"
            }
        }

    // ── 5. soak (~2 min continuous round-trip) ────────────────────────────────

    private suspend fun scenarioSoak(role: String, onLog: (String) -> Unit): ScenarioResult =
        scenario(5, "Soak ${fmtMs(SOAK.inWholeMilliseconds)}", onLog) { hop ->
            hop("weave svc=$SVC5; soaking ${fmtMs(SOAK.inWholeMilliseconds)}")
            val loom = appleNwLoom(SVC5, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            val seam = loom.weave(Rendezvous.New(pattern()))
            withTimeoutOrNull(5.seconds) { seam.state.first { it is SeamState.Woven } }
            hop("wove peers=${seam.peers.value.size}")

            val self = seam.selfId.value
            val base = TimeSource.Monotonic.markNow()
            fun nowMs() = base.elapsedNow().inWholeMilliseconds
            val rtts = mutableListOf<Long>()
            val sent = mutableMapOf<Int, Long>()
            var lastEchoMs = nowMs()
            var stalls = 0

            coroutineScope {
                val collector = launch {
                    seam.incoming.collect { swatch ->
                        val parts = swatch.toByteArray().decodeToString().split('|')
                        if (parts.size < 3) return@collect
                        val kind = parts[0]; val origin = parts[1]; val seq = parts[2].toIntOrNull() ?: return@collect
                        when (kind) {
                            "P" -> runCatchingCancellable { seam.broadcast("E|$origin|$seq".encodeToByteArray()) }
                            "E" -> if (origin == self) {
                                val startMs = sent.remove(seq)
                                if (startMs != null) {
                                    rtts.add(nowMs() - startMs)
                                    val gap = nowMs() - lastEchoMs
                                    if (gap > STALL_MS) stalls++
                                    lastEchoMs = nowMs()
                                }
                            }
                        }
                    }
                }
                val pinger = launch {
                    var seq = 0
                    while (base.elapsedNow() < SOAK) {
                        val s = seq++
                        sent[s] = nowMs()
                        runCatchingCancellable { seam.broadcast("P|$self|$s".encodeToByteArray()) }
                        delay(PING_INTERVAL)
                    }
                }
                pinger.join()
                delay(1.seconds) // let the final echoes land
                collector.cancel()
            }
            seam.close(CloseReason.Normal)

            val n = rtts.size
            val expected = (SOAK.inWholeMilliseconds / PING_INTERVAL.inWholeMilliseconds).toInt()
            if (n == 0) return@scenario Verdict.FAIL to "no echoes in ${fmtMs(SOAK.inWholeMilliseconds)} (data-path stall)"
            val sorted = rtts.sorted()
            val p50 = sorted[n / 2]
            val p95 = sorted[minOf(n - 1, (n * 95) / 100)]
            hop("n=$n/$expected rtt min=${sorted.first()} p50=$p50 p95=$p95 max=${sorted.last()} stalls=$stalls")
            val ok = n >= expected / 2 && stalls <= MAX_STALLS
            (if (ok) Verdict.PASS else Verdict.FAIL) to "n=$n p50=${p50}ms p95=${p95}ms stalls=$stalls"
        }

    // ── 6. local-fabric outage — whose network died? (the #1712 gate) ─────────

    /**
     * Everything one role needs to walk the two outages: the [room] under test, the FIFO [queue] of its
     * [MembershipEvent]s, the two report sinks, and the verdict accumulators.
     *
     * [failures] and [skip] are written **only from the scenario's own coroutine** (the phase bodies
     * below), never from the collectors that feed [queue] — single-writer by construction, so they need
     * no lock. Everything genuinely crossing threads goes through a [Channel] or a `StateFlow`.
     */
    private class OutageCtx(
        val room: Room,
        val queue: ReceiveChannel<MembershipEvent>,
        /** Append a line to this scenario's hop trace. */
        val hop: (String) -> Unit,
        /** Put an instruction in front of the human holding this phone (also lands in [hop]). */
        val say: (String) -> Unit,
        /** Milliseconds since the scenario started — the report's only time axis. */
        val ms: () -> Long,
        val failures: MutableList<String>,
    ) {
        /** Non-null ⇒ nothing was observed because the operator never toggled; the run is a SKIP, not a FAIL. */
        var skip: String? = null

        /**
         * What this side actually measured on a PASS, so the one-line verdict quotes evidence rather
         * than a canned sentence. Scenario 6's two PASS strings are fixed (it asserts the same two
         * things every run); scenario 7's are not — its PASS has to name the measured outage, which is
         * different every run and is the number an adjudicator needs.
         */
        var passDetail: String? = null
    }

    /**
     * **Operator-driven.** Prove that one outage reads correctly — and *oppositely* — on both phones
     * (#1712).
     *
     * A peer used to be able to learn that *some other peer* went quiet, but never that **it** did, so a
     * device losing its own network blamed its peers. `Room.localFabric` plus the
     * [MembershipEvent.LocalFabricLost] / [MembershipEvent.LocalFabricRestored] edges and the
     * `localFabric` tag on [MembershipEvent.Partitioned] / [MembershipEvent.HostLost] fix that. `nw` is
     * the one fabric where that value is real (every other fabric reports
     * [FabricAvailability.Unknown] — "cannot tell"), which is why the check has to happen on hardware.
     *
     * The whole feature is one asymmetry, so the scenario is built to show it: **the same outage, read
     * two correct-but-opposite ways.**
     *
     * - The phone that goes offline says *"MY network died"* — `localFabric` → [FabricAvailability.Unavailable],
     *   a `LocalFabricLost`, and any `Partitioned`/`HostLost` it emits tagged `Unavailable`.
     * - The phone that stays up says *"THEY went away"* — its own `localFabric` stays
     *   [FabricAvailability.Available], and the `Partitioned` it emits for the vanished peer carries that
     *   `Available` tag.
     *
     * Two outages, because the interesting behaviour differs either side of the reconnect window:
     * a **short** one (~[SHORT_DROP]) must recover with the seat never lost, and a **long** one must
     * outlast the window so the seat expires — with the dropped phone *still* blaming itself.
     *
     * ## Why a human has to drive it
     *
     * iOS exposes no API to toggle Airplane Mode, and `localFabric` moves for exactly one reason: the
     * fabric's `nw_path_monitor` says the path went away. So the radio is the only input that can drive
     * this, and only a person can touch it. The scenario therefore **prompts and then detects** — it
     * never assumes the operator's timing was right:
     *
     * - every wait is bounded, generous, and names *what was expected and what was observed* on timeout;
     * - the outage is measured from `localFabric`'s own edges, so a "~8 s" drop held for 25 s still
     *   passes and the report says 25 s;
     * - the **long** outage is not stopwatched by the operator at all — the phone waits for its own
     *   `HostLost` and only then says "turn it back on", so the one timing that must exceed the window
     *   cannot be got wrong;
     * - an operator who never toggles produces [Verdict.SKIP] ("nothing was observed"), never a FAIL.
     */
    private suspend fun scenarioLocalFabric(
        role: String,
        onLog: (String) -> Unit,
        onPrompt: (String) -> Unit,
    ): ScenarioResult = scenario(6, "Local-fabric outage", onLog) { hop ->
        val amHost = role == "host"
        val t0 = TimeSource.Monotonic.markNow()
        fun ms() = t0.elapsedNow().inWholeMilliseconds
        fun say(text: String) {
            onPrompt(text)
            hop("SAY t=${ms()}ms | $text")
        }

        hop(
            "role=$role svc=$SVC6 side=${if (amHost) "SURVIVING" else "DROPPED"} " +
                "detect=${S6_HEARTBEAT.timeout} window=${S6_HEARTBEAT.reconnectWindow}",
        )
        say(
            if (amHost) {
                "This is the SURVIVING phone. Do NOT touch its network. Wait."
            } else {
                "This is the DROPPED phone. You will toggle Airplane Mode here, twice, when asked."
            },
        )
        val loom = appleNwLoom(SVC6, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
        hop("self=${loom.selfId.value.take(8)}")
        val seam = instrumentedWeave("s6", loom, hop)
            ?: return@scenario Verdict.FAIL to "weave never established on $SVC6 (outage NOT exercised)"

        // The role comes from the BUTTON, not an election: the operator must know which phone to take
        // offline, and "the one that tapped Join" is the only instruction that survives a coffee shop.
        // `adopt` on a symmetrically-woven seam is exactly what the election lobby does once it has
        // elected — minus the election. `reweave = { seam }` on the joiner is what lets a path that
        // returns inside the window resume THIS room instead of collapsing it (the recovery half of the
        // same work); the host needs none, it never resumes against itself.
        val factory = SeamRoomFactory.systemClock(loom, scope, heartbeatConfig = S6_HEARTBEAT)
        val heal: (suspend () -> Seam)? = if (amHost) null else ({ seam })
        val room = factory.adopt(
            seam = seam,
            role = if (amHost) SessionRole.Host else SessionRole.Joiner,
            memberName = role,
            roomKey = ROOM_KEY,
            reweave = heal,
        )

        // Two UNLIMITED channels, written from the collectors and read from the scenario body — the only
        // state crossing coroutines here, and both thread-safe by construction. `journal` is the report's
        // record of EVERYTHING (drained into hops at the end, like instrumentedWeave's discovery trace);
        // `queue` is the FIFO the phase waits consume. A phase wait must never match an event from an
        // earlier phase, which is why it drains a queue rather than re-subscribing to the room's
        // replay-cached `events`.
        val journal = Channel<String>(Channel.UNLIMITED)
        val queue = Channel<MembershipEvent>(Channel.UNLIMITED)
        val ctx = OutageCtx(room, queue, hop, ::say, ::ms, mutableListOf())

        coroutineScope {
            val eventJob = launch {
                room.events.collect { e ->
                    journal.trySend("t=${ms()}ms ev  ${e.short()}")
                    queue.trySend(e)
                }
            }
            val fabricJob = launch {
                room.localFabric.collect { a -> journal.trySend("t=${ms()}ms mine=${a.short()}") }
            }
            val rosterJob = launch {
                room.roster.collect { r -> journal.trySend("t=${ms()}ms roster=${r.render()}") }
            }
            // The peer id is *returned*, not stashed on `ctx`: a nullable field would need a null check in
            // each phase, and a phase that bailed on it would fall through to a PASS having asserted
            // nothing. Threading it as a non-null parameter deletes that failure mode outright.
            establish(ctx, amHost)?.let { peer ->
                if (amHost) survivingSide(ctx, peer) else droppedSide(ctx, peer)
            }
            eventJob.cancel()
            fabricJob.cancel()
            rosterJob.cancel()
        }
        journal.close()
        queue.close()
        while (true) {
            val r = journal.tryReceive()
            if (!r.isSuccess) break
            hop("  ${r.getOrNull()}")
        }
        hop("final mine=${room.localFabric.value.short()} roster=${room.roster.value.render()} t=${ms()}ms")
        room.leave()
        onPrompt("")

        val skip = ctx.skip
        when {
            // FAIL outranks SKIP deliberately: a wrong reading observed in the first outage must not be
            // buried because the operator then wandered off before the second one.
            ctx.failures.isNotEmpty() -> Verdict.FAIL to (ctx.failures + listOfNotNull(skip)).joinToString("; ")
            skip != null -> Verdict.SKIP to skip
            amHost -> Verdict.PASS to "THEIR outage both times: Partitioned tagged Available, mine stayed Available"
            else -> Verdict.PASS to "MY outage both times: Lost + Unavailable tag, short recovered, long self-attributed"
        }
    }

    /**
     * Get both phones into one live room and confirm this side's own fabric is reporting **Available**
     * before either outage — otherwise the asymmetry has no baseline to move away from. Returns the
     * admitted remote peer, or null (with [OutageCtx.skip] or [OutageCtx.failures] set) when the run
     * cannot proceed.
     */
    private suspend fun establish(ctx: OutageCtx, amHost: Boolean): PeerId? {
        val room = ctx.room
        // Admission is its own round-trip proof: the joiner's Hello reached the host and the host's
        // Welcome came back. No extra handshake frame needed, and none that could be lost to `incoming`'s
        // replay-0 shared flow.
        val roster = awaitRoster(room, ADMIT_WAIT) { r -> r.any { it.liveness is Liveness.Connected } }
        if (roster == null) {
            ctx.failures.add(
                "admission: no connected member in $ADMIT_WAIT — self=${room.selfId.value.take(8)} " +
                    "role=${room.role.value} roster=${room.roster.value.render()}. The seam wove, so both " +
                    "phones found each other; an empty roster means the admit handshake never completed. " +
                    "Did BOTH phones tap the same button? Exactly one must be Host and one Join",
            )
            return null
        }
        val peer = roster.first { it.liveness is Liveness.Connected }.id
        ctx.hop("admitted peer=${peer.value.take(8)} role=${room.role.value} t=${ctx.ms()}ms")

        // `Unknown` here is not a bug in the room — it is what every fabric without a live path observer
        // reports, and nw is supposed to be the exception. If it never resolves to Available, the
        // asymmetry is unobservable on this device and the honest verdict is SKIP, not FAIL.
        val mine = awaitFabric(room, BASELINE_WAIT) { it is FabricAvailability.Available }
        if (mine == null) {
            ctx.skip = "baseline: my localFabric never reported Available in $BASELINE_WAIT " +
                "(observed ${room.localFabric.value.short()}) — nw's path monitor gave no usable path, " +
                "so neither side of the asymmetry can be read here"
            return null
        }
        ctx.hop("baseline mine=${mine.short()} side=${if (amHost) "SURVIVING" else "DROPPED"} t=${ctx.ms()}ms")
        return peer
    }

    /**
     * Reset [OutageCtx.queue] to **now**, immediately after the operator has been asked to cause an
     * outage and before any wait that must be satisfied by *that* outage. Returns false (with
     * [OutageCtx.skip] set) when the outage cannot be measured from here.
     *
     * Without this the phase waits are satisfiable by a **pre-outage backlog**, and the failure mode is a
     * false PASS — the worst defect this scenario can have. Nothing drains [OutageCtx.queue] between the
     * collector subscribing and the first prompt, and that window is long: `ADMIT_WAIT` (60s) plus
     * `BASELINE_WAIT` (20s) on top of a 45s weave, all of it waited out on `StateFlow`s that never touch
     * the channel. `Room.events` also replays its most recent 64 events to a late subscriber, so the
     * backlog reaches back past `adopt` itself. One `Available → Unavailable → Available` path flap in
     * that window — Wi-Fi association churn, an SSID handoff, walking out of range, i.e. precisely the
     * adverse network the operator is sent to find — leaves a stale `LocalFabricLost` **and** a stale
     * `LocalFabricRestored` queued. Both of this phase's edge waits would then match instantly, the drain
     * point would still be parked *before* the real outage, and the negative tag checks
     * ([blameMyself], [theirOutage]) would inspect an empty list and pass by vacuity. The verdict: PASS,
     * having asserted only that a level moved.
     *
     * Nothing is lost from the report — the `journal` collector records every event with its `t=…ms` and
     * is drained into the hop trace independently — and the discarded events are named here rather than
     * counted, so a backlog is legible in the shared report instead of silently swallowed.
     *
     * The discard opens one narrow hazard of its own: an operator who kills the radio *before* being
     * asked would have the real edges thrown away. That is what the level check closes.
     * [Room.localFabric] is a zero-lag projection of `Seam.capability` (it stores no copy, so no collector
     * lag can leave it stale), so if the radio is already off it reads `Unavailable` *here*, and the run
     * SKIPs with that value quoted instead of measuring the wrong interval.
     *
     * Both roles arm: the surviving side's stale-backlog exposure is the same shape (a queued `Partitioned`
     * for the peer from a pre-baseline flap satisfies its wait and its roster check passes trivially), and
     * the level check reads correctly there too — that side asserts its *own* fabric stayed `Available`, so
     * a phase starting from anything else is unmeasurable rather than a library defect.
     *
     * **The exact strength of the invariant**, so a later reader does not over-trust it: the pair narrows
     * the stale window from ~125s of operator-driven wall time (weave + admit + baseline) to the gap
     * between the collector's `trySend` and this drain's `tryReceive`. It does not close it mathematically.
     * An event whose outage is *unresolved* cannot slip through — that is what the level check rules out —
     * but a flap that **completed** in that sub-dispatch sliver would leave `Available` reading true while
     * a stale `Lost`/`Restored` pair was still undelivered. Two things make even that legible rather than
     * silent: `at` is rendered on all four outage-bearing events (an edge stamped before the prompt is
     * visibly stale in the shared report), and this hop names whatever it did discard. Closing it outright
     * would mean filtering each selector on `at >= ` the prompt instant — every one of those events carries
     * one — which needs a clock threaded through the suite; worth doing if this ever proves reachable.
     */
    private fun OutageCtx.armOutage(phase: String): Boolean {
        val discarded = mutableListOf<MembershipEvent>()
        while (true) {
            val r = queue.tryReceive()
            if (!r.isSuccess) break
            r.getOrNull()?.let { discarded.add(it) }
        }
        val mine = room.localFabric.value
        if (mine !is FabricAvailability.Available) {
            skip = "$phase: my localFabric read ${mine.short()} at the start of this phase, not Available " +
                "— this side cannot measure an outage it did not start from a live path. Was the radio " +
                "already off before the prompt? (discarded ${discarded.render()})"
            return false
        }
        hop("$phase: armed mine=${mine.short()} discarded=${discarded.render()} t=${ms()}ms")
        return true
    }

    /**
     * The phone that **loses its radio**. It must read its own outage: `localFabric` →
     * [FabricAvailability.Unavailable], a [MembershipEvent.LocalFabricLost], and every
     * [MembershipEvent.Partitioned] / [MembershipEvent.HostLost] it emits tagged `Unavailable` — i.e.
     * *"my network died"*, never *"the host went away"*.
     */
    private suspend fun droppedSide(ctx: OutageCtx, host: PeerId) {
        val room = ctx.room

        // ── outage A: short — inside the reconnect window, the seat must survive ──
        ctx.say("AIRPLANE MODE **ON** now, on THIS phone. Hold it ~${SHORT_DROP.inWholeSeconds}s; I'll tell you when to turn it off.")
        if (!ctx.armOutage("short")) return
        val downA = awaitFabric(room, TOGGLE_WAIT) { it is FabricAvailability.Unavailable }
        if (downA == null) {
            ctx.skip = "short: my localFabric never left ${room.localFabric.value.short()} in $TOGGLE_WAIT " +
                "— Airplane Mode was never actually turned on, so nothing was tested"
            return
        }
        val downAtA = ctx.ms()
        ctx.hop("short: mine→${downA.short()} t=${downAtA}ms")
        val seenA = mutableListOf<MembershipEvent>()
        // The level can legitimately be AHEAD of the edge (events are buffered), so await the level first
        // and then look for the matching notification — never the other way round.
        if (awaitEvent(ctx.queue, seenA, EDGE_WAIT) { it as? MembershipEvent.LocalFabricLost } == null) {
            ctx.failures.add(
                "short: mine went ${downA.short()} but NO LocalFabricLost in $EDGE_WAIT " +
                    "(saw ${seenA.render()}) — the level moved and the edge did not",
            )
        }

        // Hold the radio down long enough for the surviving phone's detector to fire, then ask for it back.
        // Slop is absorbed by the reconnect window (a whole minute), and the outage that actually happened
        // is MEASURED off the level's own edges below — never assumed to be SHORT_DROP.
        delay(SHORT_DROP)
        ctx.say("AIRPLANE MODE **OFF** now. (Held ${fmtMs(ctx.ms() - downAtA)}.)")
        val upA = awaitFabric(room, RECOVER_WAIT) { it is FabricAvailability.Available }
        if (upA == null) {
            ctx.failures.add(
                "short: mine never returned to Available in $RECOVER_WAIT after the OFF prompt " +
                    "(observed ${room.localFabric.value.short()}) — was Airplane Mode turned back off?",
            )
            return
        }
        val outageA = ctx.ms() - downAtA
        ctx.hop("short: mine→Available after ${fmtMs(outageA)} (window ${S6_HEARTBEAT.reconnectWindow})")
        if (awaitEvent(ctx.queue, seenA, EDGE_WAIT) { it as? MembershipEvent.LocalFabricRestored } == null) {
            ctx.failures.add("short: mine returned to Available but NO LocalFabricRestored in $EDGE_WAIT (saw ${seenA.render()})")
        }
        // Whether the seat was ENTITLED to survive this outage — gate for both seat assertions below.
        //
        // The reconnect window opens at DETECTION, not at outage start, so the seat is held until
        // ≈`timeout + reconnectWindow` (5s + 60s = 65s) after the radio died. An operator who held the
        // radio down for 60s therefore SHOULD lose the seat; asserting otherwise would print a confident
        // accusation ("inside a 1m window") against a library behaving exactly as specified — and a
        // plausible-sounding wrong FAIL on the release gate is nearly as damaging as a wrong PASS. Require
        // at least `2 × timeout` (≈10s) of the budget left for path-return → redial → TLS-PSK handshake →
        // first heartbeat; past that, assert nothing about the seat and say so in the trace.
        val seatShouldSurvive = outageA.milliseconds + S6_HEARTBEAT.timeout < S6_HEARTBEAT.reconnectWindow
        if (seatShouldSurvive) {
            // Roster level, not Recovered-vs-Resumed: the two differ by recovery path and role, and the
            // level clears on either — that is the documented way to answer "is the seat back?".
            if (awaitRoster(room, RECOVER_WAIT) { r -> r.any { it.id == host && it.liveness is Liveness.Connected } } == null) {
                ctx.failures.add(
                    "short: host ${host.value.take(8)} never returned to Connected in $RECOVER_WAIT after a " +
                        "${fmtMs(outageA)} outage with ${S6_HEARTBEAT.timeout + S6_HEARTBEAT.reconnectWindow} " +
                        "(detect + window) to recover (roster=${room.roster.value.render()})",
                )
            }
            // The whole point of the short outage: the seat is held, not lost.
            //
            // Unlike the surviving side's deleted `Left` check, this one is NOT vacuous — but only just,
            // and for a reason worth writing down before someone "tidies" the two into consistency: a
            // HostLost this phase must catch fires while the radio is still down, so it lands inside the
            // drain window of the `LocalFabricRestored` wait above and reaches `seenA`. One arriving
            // *after* Restored is out of this phase's reach, and correctly so — by then the outage is over.
            seenA.filterIsInstance<MembershipEvent.HostLost>().forEach {
                ctx.failures.add(
                    "short: HostLost fired on a ${fmtMs(outageA)} outage that had " +
                        "${S6_HEARTBEAT.timeout + S6_HEARTBEAT.reconnectWindow} (detect + window) to recover " +
                        "(${it.short()})",
                )
            }
        } else {
            ctx.hop(
                "short: held ${fmtMs(outageA)} — too close to detect+window " +
                    "(${S6_HEARTBEAT.timeout} + ${S6_HEARTBEAT.reconnectWindow}) to require the seat " +
                    "survived, so losing it is CORRECT here; seat NOT asserted (the tag checks below still are)",
            )
        }
        ctx.blameMyself("short", seenA)
        ctx.hop("short: DONE outage=${fmtMs(outageA)} events=${seenA.render()} t=${ctx.ms()}ms")

        // ── outage B: long — past the window, the seat expires and I still blame myself ──
        ctx.say("AIRPLANE MODE **ON** again — and LEAVE IT ON until this phone tells you otherwise (about a minute).")
        // Redundant here — phase A's waits already drained past their own events — and deliberately kept:
        // it makes "this phase's waits can only be satisfied by this phase's outage" a LOCAL property of
        // every phase rather than something the reader has to re-derive from the phase above.
        if (!ctx.armOutage("long")) return
        val downB = awaitFabric(room, TOGGLE_WAIT) { it is FabricAvailability.Unavailable }
        if (downB == null) {
            ctx.skip = "long: my localFabric never left ${room.localFabric.value.short()} in $TOGGLE_WAIT " +
                "— Airplane Mode was never turned on the second time (the short outage DID pass)"
            return
        }
        val downAtB = ctx.ms()
        ctx.hop("long: mine→${downB.short()} t=${downAtB}ms")
        val seenB = mutableListOf<MembershipEvent>()
        if (awaitEvent(ctx.queue, seenB, EDGE_WAIT) { it as? MembershipEvent.LocalFabricLost } == null) {
            ctx.failures.add("long: mine went ${downB.short()} but NO LocalFabricLost in $EDGE_WAIT (saw ${seenB.render()})")
        }
        // The operator does NOT time this one — the phone waits for its own window to expire, so the
        // outage cannot be cut short by a fast thumb. This is the deliberate difference from outage A.
        val lost = awaitEvent(ctx.queue, seenB, EXPIRY_WAIT) { it as? MembershipEvent.HostLost }
        ctx.say("AIRPLANE MODE **OFF** now — that's the last toggle. (Held ${fmtMs(ctx.ms() - downAtB)}.)")
        if (lost == null) {
            ctx.failures.add(
                "long: no HostLost in $EXPIRY_WAIT after a ${fmtMs(ctx.ms() - downAtB)} outage past a " +
                    "${S6_HEARTBEAT.reconnectWindow} window (mine=${room.localFabric.value.short()}, " +
                    "roster=${room.roster.value.render()}, saw ${seenB.render()}) — the seat never expired",
            )
        } else if (lost.localFabric !is FabricAvailability.Unavailable) {
            // THE defect this scenario exists to catch: my own radio died and I blamed the host.
            ctx.failures.add(
                "long: HostLost tagged localFabric=${lost.localFabric.short()} — expected Unavailable. " +
                    "MY radio was off (${fmtMs(ctx.ms() - downAtB)} outage), so this phone is blaming the " +
                    "host for its own outage",
            )
        }
        ctx.blameMyself("long", seenB)
        ctx.hop("long: DONE outage≈${fmtMs(ctx.ms() - downAtB)} events=${seenB.render()} t=${ctx.ms()}ms")
    }

    /**
     * The phone that **keeps its radio**. Its own `localFabric` must stay [FabricAvailability.Available]
     * and the [MembershipEvent.Partitioned] it emits for the vanished peer must carry that `Available`
     * tag — i.e. *"they went away"*, which is the opposite reading of the same outage.
     */
    private suspend fun survivingSide(ctx: OutageCtx, peer: PeerId) {
        val room = ctx.room

        // ── outage A: short ──
        ctx.say("Hold still. The other phone is going offline for a few seconds. Do NOT touch this one.")
        if (!ctx.armOutage("short")) return
        val seenA = mutableListOf<MembershipEvent>()
        val partA = awaitEvent(ctx.queue, seenA, TOGGLE_WAIT + RECOVER_WAIT) { e ->
            (e as? MembershipEvent.Partitioned)?.takeIf { it.peerId == peer }
        }
        if (partA == null) {
            // SKIP, not FAIL — and deliberately so: this phone has NO information about the other one, so
            // it genuinely cannot tell "they never went offline" from "I failed to notice". CROSS-CHECK
            // against the DROPPED phone's report: if that one shows an outage and this says SKIP, the
            // surviving side failed to notice and THAT is a real failure.
            ctx.skip = "short: no Partitioned for ${peer.value.take(8)} in ${TOGGLE_WAIT + RECOVER_WAIT} " +
                "(mine=${room.localFabric.value.short()}, roster=${room.roster.value.render()}) — either the " +
                "other phone never went offline, or it came back inside the ${S6_HEARTBEAT.timeout} detect " +
                "window. Cross-check the DROPPED phone's report for a measured outage"
            return
        }
        ctx.theirOutage("short", partA)
        // This side cannot measure how long the other phone's radio was off — it only knows when it noticed
        // — so unlike the dropped side's seat check this one cannot self-gate on the outage length. The
        // message is therefore worded to be ADJUDICATED against the paired report rather than to accuse the
        // library outright: an overheld radio makes seat expiry correct, and only the other phone's measured
        // outage can tell the two apart. (The field guide's "up to about thirty seconds" is what keeps the
        // operator inside the band where this really is a failure.)
        if (awaitRoster(room, RECOVER_WAIT) { r -> r.any { it.id == peer && it.liveness is Liveness.Connected } } == null) {
            ctx.failures.add(
                "short: ${peer.value.take(8)} never returned to Connected in $RECOVER_WAIT " +
                    "(roster=${room.roster.value.render()}) — the seat was held to " +
                    "${S6_HEARTBEAT.timeout + S6_HEARTBEAT.reconnectWindow} (detect + window) from the drop. " +
                    "Cross-check the DROPPED phone's measured outage: under that, this is a real failure to " +
                    "hold the seat; over it, the radio was overheld and expiry was correct",
            )
        }
        // No `Left`-in-seenA check here, deliberately: `seenA` is filled only by the wait above, which stops
        // draining the instant it matches the peer's `Partitioned` — and a `Left` for that peer NECESSARILY
        // post-dates it (it comes from the roster removal on window expiry). Such a check reads as coverage
        // while inspecting an always-empty list, so the seat-survived guarantee rests on the roster wait
        // above, which is authoritative and actually observes the recovery. The dropped side's HostLost
        // check is the same shape but is genuinely reachable — see the note there before making them match.
        ctx.hop("short: DONE events=${seenA.render()} t=${ctx.ms()}ms")

        // ── outage B: long — the seat must expire, and my own fabric must stay Available throughout ──
        ctx.say("Hold still again. The other phone is going offline for good this time — about a minute. Do NOT touch this one.")
        if (!ctx.armOutage("long")) return
        val seenB = mutableListOf<MembershipEvent>()
        val partB = awaitEvent(ctx.queue, seenB, TOGGLE_WAIT + EXPIRY_WAIT) { e ->
            (e as? MembershipEvent.Partitioned)?.takeIf { it.peerId == peer }
        }
        if (partB == null) {
            ctx.skip = "long: no second Partitioned for ${peer.value.take(8)} in ${TOGGLE_WAIT + EXPIRY_WAIT} " +
                "(mine=${room.localFabric.value.short()}, roster=${room.roster.value.render()}) — the other " +
                "phone never went offline again (the short outage DID pass)"
            return
        }
        ctx.theirOutage("long", partB)
        val left = awaitEvent(ctx.queue, seenB, EXPIRY_WAIT) { e ->
            (e as? MembershipEvent.Left)?.takeIf { it.peerId == peer }
        }
        if (left == null) {
            ctx.failures.add(
                "long: ${peer.value.take(8)}'s seat never expired — no Left in $EXPIRY_WAIT past a " +
                    "${S6_HEARTBEAT.reconnectWindow} window (roster=${room.roster.value.render()}, saw ${seenB.render()})",
            )
        }
        val mine = room.localFabric.value
        if (mine !is FabricAvailability.Available) {
            ctx.failures.add("long: MY localFabric ended ${mine.short()} — this phone's radio was never touched, so it must still read Available")
        }
        ctx.hop("long: DONE mine=${mine.short()} events=${seenB.render()} t=${ctx.ms()}ms")
    }

    /**
     * Assert the surviving side's reading of one outage: its own fabric [FabricAvailability.Available]
     * and the peer's [MembershipEvent.Partitioned] carrying that same `Available` tag — *"they went
     * away"*.
     */
    private fun OutageCtx.theirOutage(phase: String, partitioned: MembershipEvent.Partitioned) {
        val tag = partitioned.localFabric
        val mine = room.localFabric.value
        hop("$phase: THEIR outage ${partitioned.short()} mine=${mine.short()} t=${ms()}ms")
        if (tag !is FabricAvailability.Available) {
            // The mirror-image defect: my radio is fine, so a non-Available tag would tell a consumer to
            // render "you are offline" on the phone that is emphatically online.
            failures.add(
                "$phase: Partitioned tagged localFabric=${tag.short()} — expected Available. " +
                    "MY radio was never touched, so this event must read as 'they went away'",
            )
        }
        if (mine !is FabricAvailability.Available) {
            failures.add("$phase: MY localFabric read ${mine.short()} while the OTHER phone was the one offline")
        }
    }

    /**
     * Assert the dropped side blamed **itself** for everything it emitted during an outage: every
     * [MembershipEvent.Partitioned] / [MembershipEvent.HostLost] tagged [FabricAvailability.Unavailable].
     * A `Partitioned` tagged `Available` here is precisely the pre-#1712 bug.
     */
    private fun OutageCtx.blameMyself(phase: String, seen: List<MembershipEvent>) {
        seen.filterIsInstance<MembershipEvent.Partitioned>().forEach { e ->
            if (e.localFabric !is FabricAvailability.Unavailable) {
                failures.add(
                    "$phase: Partitioned tagged localFabric=${e.localFabric.short()} — expected Unavailable. " +
                        "MY radio was off, so this event must read as 'my network died', not 'they went away'",
                )
            }
        }
        seen.filterIsInstance<MembershipEvent.HostLost>().forEach { e ->
            if (e.localFabric !is FabricAvailability.Unavailable) {
                failures.add("$phase: HostLost tagged localFabric=${e.localFabric.short()} — expected Unavailable (my radio was off)")
            }
        }
    }

    // ── 7. sub-timeout blip — the #1637 repro ─────────────────────────────────

    /**
     * **Operator-driven, and EXPECTED TO FAIL on a build without the #1637 fix.** That failure is the
     * whole point of the scenario: it is the RED capture, on hardware, of a bug no local suite has ever
     * reproduced.
     *
     * ## The bug, in one paragraph
     *
     * A joiner whose radio blips for **longer than the seam's path grace but shorter than the host's
     * liveness timeout** never resumes — it burns its entire reconnect budget and dies. The joiner's
     * `NwSeam` gives a path-lost connection [S7_WOVEN_PATH_GRACE] to come back, then evicts the host and
     * re-forms; its detector reports `TransportClosed` and the resume machine starts. But **the host's
     * link never closed** — only the joiner's side tore — so the host never noticed anything. Every
     * `Resume` the joiner sends is answered `WindowNotYetOpen` (retryable), and *the Resume frame
     * itself refreshes the host detector's `lastSeen`*, so the host's silence never reaches its
     * timeout and no window will ever open. The joiner retries every `interval` until the
     * [HeartbeatConfig.reconnectWindow] elapses → `FailureReason.Refused` → `HostLost`. A live room
     * dies of a blip both phones could have shrugged off.
     *
     * ## Why scenario 6 structurally cannot show this
     *
     * [scenarioLocalFabric] runs `timeout = 5s`, *below* the shipping 10 s grace — deliberately, so its
     * own short outage is detectable at all. The host therefore always opens its window **before** the
     * joiner's grace expires, every resume finds an open window and succeeds, and the interval
     * `grace < outage < timeout` is **empty**. Verified on hardware on 2026-07-27: a 23.7 s real outage
     * gave `Partitioned(LinkTimeout)` + `WindowOpened` at 9.3 s and a clean `Recovered` at 30.4 s — the
     * resume machine's failing lane was never entered.
     *
     * Scenario 7 exists solely to make that interval non-empty. [S7_HEARTBEAT] keeps `timeout` at
     * production's 15 s (the ceiling — see below), and the scenario lowers its *own* floor by passing
     * `appleNwLoom(wovenPathGrace = `[S7_WOVEN_PATH_GRACE]`)`, so the repro band is
     * `3s < outage < 15s`.
     *
     * **That 3 s floor is a deliberate test override; production ships 10 s**
     * ([PRODUCTION_WOVEN_PATH_GRACE]). Only this scenario overrides it, and only to make a
     * hand-operated Airplane Mode toggle able to land inside the band at all — at the shipping value
     * the band is 5 s wide and has to be aimed through ~9 s of radio lag. The override moves *when*
     * the joiner's resume episode starts, not the mechanism it exercises: the eviction still happens
     * while the radio is off, the host never sees the grace, and the fix under test dwells on
     * `HeartbeatConfig.timeout`.
     *
     * **The band's upper edge, by contrast, is not a setting.** The first hardware run (2026-07-27)
     * configured `timeout = 30s` and the host noticed anyway at t≈18.6 s, because its *TCP* connection
     * to the vanished peer died of ETIMEDOUT (`posix 60`) and fired `TransportClosed` — a
     * transport-level death the heartbeat detect has no say over. The upper half of that configured
     * band was physically unreachable. See [OBSERVED_HOST_TRANSPORT_DEATH]; the band is set below it.
     *
     * ## What each phone does
     *
     * The role comes from the BUTTON, exactly as in scenario 6, and the wiring is deliberately
     * identical: the **DROPPED phone is the Joiner** (`reweave = { seam }`, the adopt path #1637 is
     * about) and the surviving phone is the Host (`reweave = null`). One outage, not two.
     *
     * - **Dropped (Join)** — the phone under test. It measures its own outage off `localFabric`'s
     *   edges and refuses to judge a run that landed outside the band ([Verdict.SKIP], never a FAIL:
     *   an operator who held 8 s or 35 s must be told to retry, not shown a misleading verdict).
     *   PASS = the resume lane **observably resolved on the #1637 dwell** ([ResumeLaneProbe]) with no
     *   `HostLost` and the host back `Connected`. FAIL = `HostLost` **carrying
     *   `RejectCode.ResumeWindowNotYetOpen`** — that code is the whole signature; any other `HostLost`
     *   is a different failure and reports as its own SKIP. A room that merely *survives* is not a
     *   PASS: see [blippedSide] for why "no `HostLost` + host `Connected`" was a false green.
     * - **Stay-up (Host)** — a witness, *and the peer the other phone has to come back to*. By
     *   construction it should observe *nothing at all*, and its PASS says exactly that: it never
     *   partitioned the joiner, so no window ever opened. It reads the same on a fixed and an unfixed
     *   build; **the verdict that matters is on the other phone.** Critically, it must **outlive the
     *   joiner's whole episode** — see [stayUpSide], where the first hardware run invalidated itself by
     *   exiting 4 s before the joiner's radio came back.
     */
    private suspend fun scenarioSubTimeoutBlip(
        role: String,
        onLog: (String) -> Unit,
        onPrompt: (String) -> Unit,
        capture: SuiteLogCapture,
    ): ScenarioResult = scenario(7, "Sub-timeout blip", onLog) { hop ->
        val amHost = role == "host"
        val t0 = TimeSource.Monotonic.markNow()
        fun ms() = t0.elapsedNow().inWholeMilliseconds
        fun say(text: String) {
            onPrompt(text)
            hop("SAY t=${ms()}ms | $text")
        }

        hop(
            "role=$role svc=$SVC7 side=${if (amHost) "SURVIVING" else "DROPPED"} " +
                "detect=${S7_HEARTBEAT.timeout} window=${S7_HEARTBEAT.reconnectWindow} " +
                "grace=$S7_WOVEN_PATH_GRACE (TEST OVERRIDE; production ships $PRODUCTION_WOVEN_PATH_GRACE) " +
                "repro=($S7_WOVEN_PATH_GRACE,${S7_HEARTBEAT.timeout}) exclusive",
        )
        say(
            if (amHost) {
                "This is the STAY-UP phone. Do NOT touch its network — it finishes on its own."
            } else {
                "This is the DROPPED phone. You will toggle Airplane Mode here ONCE, when asked."
            },
        )
        // The ONE place in this suite that overrides the fabric's path grace, and the reason the repro
        // band is hand-hittable at all. Deliberate, scenario-local, and NOT production behaviour —
        // every other scenario (and every shipping consumer) gets PRODUCTION_WOVEN_PATH_GRACE.
        val loom = appleNwLoom(
            SVC7,
            ROOM_KEY,
            weaveTimeout = WEAVE_TIMEOUT,
            wovenPathGrace = S7_WOVEN_PATH_GRACE,
        )
        hop("self=${loom.selfId.value.take(8)}")
        val seam = instrumentedWeave("s7", loom, hop)
            ?: return@scenario Verdict.FAIL to "weave never established on $SVC7 (blip NOT exercised)"

        // The verdict's only witness to the mechanism under test. Installed before the room exists so
        // it cannot miss the machine's first line, and torn down after `leave()` below.
        val lane = ResumeLaneProbe()
        val unwatch = capture.observe(lane::record)
        // The resume machine's evidence lines are INFO. The tee deliberately never widens the level
        // (a soak's RTT distribution must keep measuring the same thing), so scenario 7 — which runs
        // alone, and whose verdict is unreadable without them — widens it for itself and puts it back.
        // Narrowing is never done: a diagnostic run already at DEBUG keeps its DEBUG.
        val previousLevel = KotlinLoggingConfiguration.direct.logLevel
        if (previousLevel > Level.INFO) KotlinLoggingConfiguration.direct.logLevel = Level.INFO
        hop("resume-lane probe armed (log level ${KotlinLoggingConfiguration.direct.logLevel.name}, was ${previousLevel.name})")

        // Identical wiring to scenario 6, and deliberately so: `reweave = { seam }` on the joiner is
        // what puts the adopt-path resume machine in the loop at all, and #1637 is a defect of that
        // machine. Change this and the scenario stops reproducing the bug.
        val factory = SeamRoomFactory.systemClock(loom, scope, heartbeatConfig = S7_HEARTBEAT)
        val heal: (suspend () -> Seam)? = if (amHost) null else ({ seam })
        val room = factory.adopt(
            seam = seam,
            role = if (amHost) SessionRole.Host else SessionRole.Joiner,
            memberName = role,
            roomKey = ROOM_KEY,
            reweave = heal,
        )

        val journal = Channel<String>(Channel.UNLIMITED)
        val queue = Channel<MembershipEvent>(Channel.UNLIMITED)
        val ctx = OutageCtx(room, queue, hop, ::say, ::ms, mutableListOf())

        coroutineScope {
            val eventJob = launch {
                room.events.collect { e ->
                    journal.trySend("t=${ms()}ms ev  ${e.short()}")
                    queue.trySend(e)
                }
            }
            val fabricJob = launch {
                room.localFabric.collect { a -> journal.trySend("t=${ms()}ms mine=${a.short()}") }
            }
            val rosterJob = launch {
                room.roster.collect { r -> journal.trySend("t=${ms()}ms roster=${r.render()}") }
            }
            establish(ctx, amHost)?.let { peer ->
                if (amHost) stayUpSide(ctx, peer) else blippedSide(ctx, peer, seam, lane)
            }
            eventJob.cancel()
            fabricJob.cancel()
            rosterJob.cancel()
        }
        journal.close()
        queue.close()
        while (true) {
            val r = journal.tryReceive()
            if (!r.isSuccess) break
            hop("  ${r.getOrNull()}")
        }
        hop("final mine=${room.localFabric.value.short()} roster=${room.roster.value.render()} t=${ms()}ms")
        hop("final resume-lane ${lane.snapshot().render()}")
        room.leave()
        // Ordered after leave() so a line the teardown itself emits still lands in the trace. The
        // scenario() wrapper's catch-all cannot reach here on a throw, but start()'s `finally`
        // uninstalls the whole tee, so a leaked observer cannot outlive the run either way.
        unwatch()
        KotlinLoggingConfiguration.direct.logLevel = previousLevel
        onPrompt("")

        val skip = ctx.skip
        when {
            // Same precedence as scenario 6: a wrong reading outranks an unmeasurable one.
            ctx.failures.isNotEmpty() -> Verdict.FAIL to (ctx.failures + listOfNotNull(skip)).joinToString("; ")
            skip != null -> Verdict.SKIP to skip
            // Never a canned string: every PASS here quotes the outage it was earned on.
            else -> Verdict.PASS to (ctx.passDetail ?: "nothing asserted — this is a bug in the scenario")
        }
    }

    /**
     * The phone whose radio blips. It measures its own outage, refuses to judge one that fell outside
     * the repro band, and then watches the resume lane for the **whole** budget the library committed
     * to before saying anything.
     *
     * ## Two defects this used to have, both of which produced a green PASS on 2026-07-28
     *
     * That run reported `PASS 14.8s … Recovered(441485b2) 8.5s after the radio died, no HostLost, host
     * Connected` against a build containing the #1637 fix — and the fix had not run at all.
     *
     * **1. It stopped watching at the first encouraging event.** #1637 kills the room when the
     * reconnect window expires, ≈ [S7_WOVEN_PATH_GRACE] + `reconnectWindow` ≈ 63 s after the radio
     * dies. Returning on the first `Recovered` meant a verdict at ~15 s, with 48 s of the window the
     * bug lives in never observed. The wait is now a **deadline** ([OBSERVE_WINDOW] from the measured
     * radio death), not a first-match: a late `HostLost` cannot be missed, and only `HostLost` — which
     * is terminal, nothing can follow it — ends it early.
     *
     * **2. It inferred health instead of observing the mechanism.** "No `HostLost` + host `Connected`"
     * is satisfied by a room that simply healed on its own, which is exactly what happened: the link
     * came back before the resume machine's dwell could fire, and the machine was then cancelled by
     * `leave()` having concluded nothing. Two membership surfaces look identical across the two cases
     * and neither can discriminate:
     *
     *  - **`Recovered(hostId)` is emitted by BOTH** the #1637 no-op path (`onNoOpResume` →
     *    `markRecovered`) and an ordinary detector-observed recovery (`PeerRecovered` → the same
     *    `markRecovered`). It is *not* evidence of the fix and is no longer read as any.
     *  - **`Partitioned`/`WindowOpened` do not prove the resume machine ran either** — the joiner emits
     *    both from `markPartitioned` on a plain heartbeat `Timeout`, machine untouched. They are kept
     *    only as a "did the blip reach the room at all" gate.
     *
     * So the discriminator is [ResumeLaneProbe], watching the machine's own `resume.*` evidence lines.
     * It answers **two** questions the old code conflated into one: was the lane *entered*
     * (`resume.refused`, #1969 — or `membership.unresponsive … branch=resume` as the weaker stand-in
     * on a build without it), and did it *resolve* (`resume.no-op` / `resume.ok`). "Entered and never
     * resolved" is a real, distinct, actionable answer, and it is not a PASS.
     *
     * ## The verdict, which never collapses the ways a room can survive
     *
     *  - **`HostLost(Refused(code=resume-window-not-yet-open))`** → FAIL. That reject code, and nothing
     *    else, is #1637's signature: a resume the host kept *answering* — retryably, forever — until the
     *    window ran out.
     *  - **any other `HostLost`** → SKIP, with prose that names what it actually was. A
     *    `HostLost(WindowExpired)` is the *opposite* shape: no resume was ever answered, most likely
     *    because none was ever sent. The first hardware run produced exactly that and the scenario
     *    reported it as "#1637 confirmed", asserting three mechanisms it had not observed. A verdict
     *    must never claim a mechanism the run did not see.
     *  - **`resume.no-op`** → PASS, and the only PASS. The machine dwelled on a persistent
     *    `WindowNotYetOpen`, concluded no window was ever coming, and completed the episode locally.
     *    That is the #1637 fix, observed.
     *  - **`resume.ok`** (or a `Resumed` event, its membership-level twin) → SKIP. A real `ResumeAck`
     *    proves the host had a window **open** — it did notice, the outage overran its link, and this
     *    was the ordinary resume lane. The room recovered; the sub-timeout lane was not tested.
     *  - **neither, and no `HostLost`** → SKIP, **NOT EXERCISED**. The room survived and the resume
     *    lane never resolved. This is the 2026-07-28 run, and reporting it as a PASS is the defect
     *    being fixed here. The prose splits it further, because the two shapes want opposite actions:
     *    *entered but unresolved* is a bug to file, *never entered* is a blip to re-aim.
     *
     * [seam] is threaded in purely as evidence for the non-#1637 branch: a seam still `Weaving` when the
     * episode closed proves this phone never re-wove, so it never dialled and never sent a Resume —
     * which points at the *other* phone having stopped advertising, not at the resume machine.
     */
    private suspend fun blippedSide(ctx: OutageCtx, host: PeerId, seam: Seam, probe: ResumeLaneProbe) {
        val room = ctx.room
        val lo = S7_WOVEN_PATH_GRACE
        val hi = S7_HEARTBEAT.timeout

        // The instruction is DERIVED from the band, and it names the lag, because the lag is most of the
        // outage: on the 2026-07-27 run a 15.0s hold measured 23.7s. Asking for "about fifteen seconds"
        // when the target is a (10s, 15s) band would guarantee an out-of-band run every time.
        ctx.say(
            "AIRPLANE MODE **ON** now, on THIS phone — then turn it straight back OFF. Keep your thumb " +
                "on the toggle: I'll say OFF after only ~${fmtMs(BLIP_HOLD.inWholeMilliseconds)}, and " +
                "you must turn it off the instant I do. The radio then takes ~" +
                "${fmtMs(RESTORE_LAG.inWholeMilliseconds)} to come back on its own, so that flick " +
                "measures ~${fmtMs((BLIP_HOLD + RESTORE_LAG).inWholeMilliseconds)} of outage — inside " +
                "the ($lo, $hi) target, with ~${fmtMs((BLIP_HOLD + RESTORE_LAG - lo).inWholeMilliseconds)} " +
                "of room below it and ~${fmtMs((hi - BLIP_HOLD - RESTORE_LAG).inWholeMilliseconds)} above.",
        )
        if (!ctx.armOutage("blip")) return
        val down = awaitFabric(room, TOGGLE_WAIT) { it is FabricAvailability.Unavailable }
        if (down == null) {
            ctx.skip = "blip: my localFabric never left ${room.localFabric.value.short()} in $TOGGLE_WAIT " +
                "— Airplane Mode was never actually turned on, so nothing was tested"
            return
        }
        val downAt = ctx.ms()
        ctx.hop("blip: mine→${down.short()} t=${downAt}ms")

        // The hold is timed from the OBSERVED path loss, not from the prompt, so an operator who took
        // twenty seconds to find Control Centre still gets a ~BLIP_HOLD outage rather than a 40 s one.
        // The queue is deliberately NOT drained here: LocalFabricLost, Partitioned and WindowOpened all
        // land during the hold, and the verdict wait below wants every one of them in `seen`.
        delay(BLIP_HOLD)
        ctx.say("AIRPLANE MODE **OFF** — NOW, immediately. That's the only toggle. (Held ${fmtMs(ctx.ms() - downAt)}.)")
        if (awaitFabric(room, RECOVER_WAIT) { it is FabricAvailability.Available } == null) {
            ctx.skip = "blip: my localFabric never returned to Available in $RECOVER_WAIT after the OFF " +
                "prompt (observed ${room.localFabric.value.short()}) — was Airplane Mode turned back " +
                "off? Nothing can be concluded about the resume"
            return
        }
        val outage = (ctx.ms() - downAt).milliseconds

        // ── the gate. Outside the band this run says NOTHING about #1637 ──
        //
        // Below `lo` the seam never evicts the host, so the resume machine is never entered; above `hi`
        // the host DOES notice, opens a window, and the resume succeeds the ordinary way. Both are
        // correct library behaviour, so both are SKIP — and the message names which side it fell on,
        // because that is the only thing that tells the operator which way to adjust.
        if (outage <= lo || outage >= hi) {
            val which = if (outage <= lo) {
                "SHORT of the $lo path grace (this scenario's deliberate override; production ships " +
                    "$PRODUCTION_WOVEN_PATH_GRACE) — the seam never evicted the host, so the resume " +
                    "machine was never entered. Hold it LONGER"
            } else {
                "PAST the $hi top of the band — an outage that long IS noticed by the host, a window " +
                    "opens, and the resume succeeds the ordinary way. Turn it back off FASTER. (The top " +
                    "of the band is NOT a knob: the host's own TCP connection to a vanished peer dies of " +
                    "ETIMEDOUT at ~${fmtMs(OBSERVED_HOST_TRANSPORT_DEATH.inWholeMilliseconds)} — one " +
                    "observation, 2026-07-27 — and fires TransportClosed regardless of how detect is " +
                    "configured, so raising detect cannot buy more room)"
            }
            ctx.skip = "blip: measured outage ${fmtMs(outage.inWholeMilliseconds)} fell $which. The repro " +
                "interval is ($lo, $hi) exclusive; the outage runs ~" +
                "${fmtMs(RESTORE_LAG.inWholeMilliseconds)} longer than you hold it, so aim for a " +
                "~${fmtMs(BLIP_HOLD.inWholeMilliseconds)} hold with your thumb already on the toggle, " +
                "then re-run"
            return
        }
        if (outage - lo < BLIP_MARGIN || hi - outage < BLIP_MARGIN) {
            // Not a verdict change — the band is exactly as stated. But a run that landed 1 s inside an
            // edge is one radio-reassociation hiccup away from the other side of it, and an adjudicator
            // reading the report later deserves to know that before trusting either outcome.
            ctx.hop(
                "blip: MARGINAL — ${fmtMs(outage.inWholeMilliseconds)} is within $BLIP_MARGIN of a " +
                    "($lo, $hi) edge; the verdict stands but a re-run nearer the middle is worth having",
            )
        }
        ctx.hop("blip: mine→Available after ${fmtMs(outage.inWholeMilliseconds)} — INSIDE ($lo, $hi) t=${ctx.ms()}ms")

        // ── observe the WHOLE window, not up to the first encouraging event ──
        //
        // #1637 kills the room when the reconnect window expires, ≈ grace + window ≈ 63s after the
        // radio died. A first-match wait rendered its verdict at ~15s and never saw the other 48s, so
        // a room that looked healthy early passed no matter what the resume lane did afterwards. The
        // wait is therefore a deadline measured from the observed radio death, and the loop keeps
        // draining past a `Recovered`/`Resumed` — neither is terminal, and a later `HostLost` outranks
        // both. `HostLost` IS terminal (the room is dead; nothing follows), so it alone breaks early.
        val seen = mutableListOf<MembershipEvent>()
        val observeUntilMs = downAt + OBSERVE_WINDOW.inWholeMilliseconds
        // The banner, not just the trace: the phone is about to sit apparently idle for over a minute
        // with the room already looking healthy, and an operator who reads that as a hang will kill the
        // app mid-observation — which is precisely the evidence the verdict is waiting for.
        ctx.say(
            "Done — hands off BOTH phones now. This one keeps watching for the full $OBSERVE_WINDOW " +
                "after the radio died (about " +
                "${fmtMs((observeUntilMs - ctx.ms()).coerceAtLeast(0))} left) before it will judge " +
                "anything. It looks idle on purpose: the connection can come back and STILL die a " +
                "minute later, and that is the whole bug. Do not close the app.",
        )
        ctx.hop(
            "blip: watching the whole window — verdict at t=${fmtMs(observeUntilMs)} " +
                "($OBSERVE_WINDOW after the radio died; a #1637 HostLost lands ≈" +
                "${S7_WOVEN_PATH_GRACE + S7_HEARTBEAT.reconnectWindow} after it)",
        )
        var outcome: MembershipEvent? = null
        while (true) {
            val remaining = (observeUntilMs - ctx.ms()).milliseconds
            if (remaining <= Duration.ZERO) break
            val e = awaitEvent(ctx.queue, seen, remaining) { ev ->
                when (ev) {
                    is MembershipEvent.HostLost -> ev
                    is MembershipEvent.Recovered -> ev.takeIf { it.peerId == host }
                    is MembershipEvent.Resumed -> ev
                    else -> null
                }
            } ?: break
            ctx.hop("blip: saw ${e.short()} t=${ctx.ms()}ms (still watching to t=${fmtMs(observeUntilMs)})")
            if (e is MembershipEvent.HostLost) {
                outcome = e
                break
            }
            if (outcome == null) outcome = e
        }
        // Bound to a val so the verdict below smart-casts, and so the loop's mutation is over.
        val closing: MembershipEvent? = outcome
        val lane = probe.snapshot()
        ctx.hop("blip: resume-lane ${lane.render()}")
        // Did the blip reach the room's partition machinery at all? Kept as a gate, NOT as evidence
        // that the resume machine ran: the joiner emits both of these from `markPartitioned` on a
        // plain heartbeat Timeout, with the machine never entered. `lane.entered` is what says the
        // room actually handed off to the resume lane.
        val episodeOpened = seen.any {
            (it is MembershipEvent.Partitioned && it.peerId == host) ||
                (it is MembershipEvent.WindowOpened && it.peerId == host)
        }
        val hostConnected = room.roster.value.any { it.id == host && it.liveness is Liveness.Connected }
        val sinceDrop = ctx.ms() - downAt
        when {
            closing is MembershipEvent.HostLost -> {
                // #1637 has ONE signature and this is it: Refused, carrying ResumeWindowNotYetOpen. A
                // verdict must never assert a mechanism the run did not observe — the first hardware run
                // printed "the host never partitioned me … every Resume was answered WindowNotYetOpen"
                // over a `HostLost(WindowExpired)` in which the joiner had sent zero Resumes and the host
                // HAD partitioned it. Every clause of that sentence was false.
                val reason = closing.reason
                val isNotYetOpen = reason is FailureReason.Refused &&
                    reason.code == RejectCode.ResumeWindowNotYetOpen
                if (isNotYetOpen) {
                    ctx.failures.add(
                        "blip: HostLost ${fmtMs(sinceDrop)} after the radio died, on a " +
                            "${fmtMs(outage.inWholeMilliseconds)} outage that sat INSIDE the ($lo, $hi) repro " +
                            "interval — reason=${closing.reason.describe()}, mine=${closing.localFabric.short()}. " +
                            "This IS #1637, discriminated on the reject code: the host never partitioned me " +
                            "(its link never closed), so every Resume was answered WindowNotYetOpen and " +
                            "refreshed its lastSeen, and the whole ${S7_HEARTBEAT.reconnectWindow} budget " +
                            "burned down to a terminal room. EXPECTED on a build without the #1637 fix — " +
                            "this FAIL is the capture. resume-lane=${lane.render()} (saw ${seen.render()})",
                    )
                } else {
                    // A DIFFERENT failure, reported as itself. `WindowExpired` in particular means no
                    // Resume ever got an answer at all — the opposite of #1637's "answered, over and
                    // over". The seam's own state is the discriminator worth quoting: still Weaving ⇒
                    // this phone never re-wove, so it never dialled and never sent one.
                    val neverRewove = seam.state.value !is SeamState.Woven
                    ctx.skip = "blip: HostLost ${fmtMs(sinceDrop)} after the radio died with " +
                        "reason=${closing.reason.describe()} (mine=${closing.localFabric.short()}), on a " +
                        "${fmtMs(outage.inWholeMilliseconds)} outage inside the ($lo, $hi) band. That is " +
                        "**not** the #1637 signature — #1637 is HostLost(Refused(code=" +
                        "${RejectCode.ResumeWindowNotYetOpen.id})), a resume that kept being answered. " +
                        "This run's resume was never answered at all. seam=${seam.state.value.short()} " +
                        "peers=[${seam.peers.value.joinToString(",") { it.value.take(8) }}]" +
                        (if (neverRewove) " — the seam never got back to Woven, so no Resume was ever sent" else "") +
                        ". Likely causes, in order: this phone never re-wove (the STAY-UP phone stopped " +
                        "advertising or was closed, or browse never re-found it); the dial was refused; " +
                        "or the host process was gone. CHECK THE STAY-UP PHONE'S REPORT for how long it " +
                        "stayed up, then re-run. Inconclusive about #1637 either way. " +
                        "resume-lane=${lane.render()} (saw ${seen.render()}, " +
                        "roster=${room.roster.value.render()})"
                }
            }

            // ── the ONLY PASS: the fix under test observably ran ──
            //
            // `resume.no-op` is emitted at exactly one place — the retry loop concluding, after
            // dwelling one HeartbeatConfig.timeout on a persistent ResumeWindowNotYetOpen, that no
            // window was ever coming. Nothing else in the library writes it, and it is unreachable
            // except THROUGH the refusals, so it proves both halves at once: the lane was entered and
            // it resolved the #1637 way. The refusal trail (#1969) is quoted when present because it
            // shows the retries the dwell sat on; its absence on an older build weakens the report,
            // never the conclusion. The roster check is not ceremony: the dwell must leave a LIVE room
            // behind, and `onNoOpResume` → markRecovered is what puts the host back to Connected.
            lane.noOp != null && hostConnected -> ctx.passDetail =
                "#1637 FIX OBSERVED — the resume lane was entered AND resolved on the dwell after a " +
                    "${fmtMs(outage.inWholeMilliseconds)} blip inside ($lo, $hi): '${lane.noOp}' " +
                    "${fmtMs(sinceDrop)} after the radio died, no HostLost through the whole " +
                    "$OBSERVE_WINDOW observation, host back Connected. " +
                    (
                        lane.refused?.let { "Refusal trail: '$it'. " }
                            ?: "(No resume.refused trail — this build predates #1969; resume.no-op is " +
                            "still conclusive, since the dwell is only reachable through a persistent " +
                            "ResumeWindowNotYetOpen.) "
                        ) +
                    "This is the mechanism, not an inference from a healthy-looking room: " +
                    "Recovered(host) alone would prove nothing, since the detector emits the identical " +
                    "event. (saw ${seen.render()})"

            // Same dwell, but the room it left behind is wrong. Not a PASS (the fix did not finish its
            // job) and not the #1637 FAIL (no HostLost, no reject code) — its own anomaly.
            lane.noOp != null -> ctx.skip =
                "blip: the resume lane DID resolve on the #1637 dwell ('${lane.noOp}') after a " +
                    "${fmtMs(outage.inWholeMilliseconds)} outage, but ${host.value.take(8)} is NOT " +
                    "Connected in the roster afterwards (roster=${room.roster.value.render()}, " +
                    "seam=${seam.state.value.short()}). The dwell is supposed to close the " +
                    "Partitioned/WindowOpened arc via markRecovered and leave a live room. It did not, " +
                    "so this is neither the fix working nor #1637 — file it as its own bug. " +
                    "(saw ${seen.render()})"

            // Recovery, the ordinary way. `resume.ok` and the `Resumed` event are the two faces of one
            // thing (a real ResumeAck), and either proves the host HAD a window open — i.e. it noticed
            // the outage, so the run sat above the band and the sub-timeout lane was never entered.
            // Reading this as a PASS is the false green this scenario could most plausibly produce.
            lane.ok != null || closing is MembershipEvent.Resumed -> ctx.skip =
                "blip: the host ACKed a REAL resume (${lane.ok ?: closing?.short()} after " +
                    "${fmtMs(sinceDrop)}), which means it HAD a window open — so it noticed the outage " +
                    "and this was the ordinary resume lane, not the sub-timeout one. Measured outage " +
                    "${fmtMs(outage.inWholeMilliseconds)} landed inside ($lo, $hi) and the host STILL " +
                    "noticed — most likely its transport died rather than its detect elapsing (~" +
                    "${fmtMs(OBSERVED_HOST_TRANSPORT_DEATH.inWholeMilliseconds)} on the one run measured). " +
                    "Check the STAY-UP phone's Partitioned reason, and re-run with a shorter hold. " +
                    "resume-lane=${lane.render()} (saw ${seen.render()})"

            // The machine ran and gave up before it could ever retry — no reweave, no resume token, or
            // no known host. None of those is #1637; all of them mean the wiring, not the fix, is what
            // this run measured.
            lane.terminal != null -> ctx.skip =
                "blip: the resume machine went terminal before retrying ('${lane.terminal}') after a " +
                    "${fmtMs(outage.inWholeMilliseconds)} outage. That names a missing precondition " +
                    "(reweave / resume token / known host), not #1637 — the dwell under test is in the " +
                    "RETRY loop and was never reached. (saw ${seen.render()}, " +
                    "roster=${room.roster.value.render()})"

            // Only when the lane was not entered either — a lane that DID run while the membership
            // edges stayed quiet is a contradiction worth reporting as such, and the branch below
            // quotes both halves.
            !episodeOpened && !lane.entered -> ctx.skip =
                "blip: no Partitioned/WindowOpened for ${host.value.take(8)} in ${fmtMs(sinceDrop)} after " +
                    "a ${fmtMs(outage.inWholeMilliseconds)} outage — the blip never reached the room's " +
                    "partition machinery at all, so nothing was tested. Did the path really drop for the " +
                    "whole hold? (saw ${seen.render()}, roster=${room.roster.value.render()})"

            // ── NOT EXERCISED: the case that used to be a silent PASS ──
            //
            // The room survived the blip and the resume lane never resolved. On 2026-07-28 this
            // reported `PASS 14.8s … Recovered(441485b2) 8.5s after the radio died, no HostLost, host
            // Connected` on a build carrying the #1637 fix, while JoinerResumeMachine had logged
            // nothing whatsoever. SKIP, not FAIL, for the same reason every other non-#1637 outcome is:
            // a FAIL here is read as "#1637 reproduced" the moment the report is pasted into the issue.
            else -> ctx.skip = buildString {
                append("NOT EXERCISED — the room survived a ${fmtMs(outage.inWholeMilliseconds)} blip ")
                append("inside ($lo, $hi) but the resume lane never resolved in $OBSERVE_WINDOW: no ")
                append("resume.no-op, no resume.ok, no HostLost. This run says NOTHING about #1637. ")
                append(
                    when {
                        // The probe's own wiring failed, so its silence is not a finding about the
                        // library at all. Say so first, and loudly: a reader who takes this for
                        // "the machine never ran" goes hunting the wrong bug entirely.
                        lane.blind ->
                            "WARNING: the resume-lane probe saw NO log events whatsoever, so it was " +
                                "BLIND and its silence says nothing about the machine. That is a defect " +
                                "in this scenario's wiring (the log tee or the level), not in the " +
                                "library — fix it before reading anything into this run. "

                        // ENTERED but not RESOLVED. Distinct from "never entered", and the distinction
                        // is actionable: the lane really did run, so this is either a genuinely stalled
                        // episode or an observation window that is STILL too short — both worth a bug,
                        // neither a reason to re-run and hope.
                        lane.entered ->
                            "The resume lane WAS entered ('${lane.entryEvidence}') and simply never " +
                                "concluded inside the full budget. That is not a mis-aimed blip — it is " +
                                "either a stalled episode or an observation window that is still too " +
                                "short, and it is worth filing as its own bug rather than re-running. "

                        else ->
                            "The resume lane was NEVER entered — no resume.refused (#1969) and no " +
                                "'membership.unresponsive … branch=resume' — so the seam never evicted " +
                                "the host: the blip stayed inside the fabric's path grace, or the link " +
                                "healed before the seam tore. Hold LONGER and re-run. "
                    },
                )
                append(
                    "Any Recovered(${host.value.take(8)}) here is NOT evidence of the fix — the " +
                        "detector emits the identical event on an ordinary recovery. ",
                )
                append("host ${if (hostConnected) "IS" else "is NOT"} Connected. ")
                append("resume-lane=${lane.render()} (saw ${seen.render()}, ")
                append("roster=${room.roster.value.render()}, seam=${seam.state.value.short()})")
            }
        }
        ctx.hop(
            "blip: DONE outage=${fmtMs(outage.inWholeMilliseconds)} events=${seen.render()} " +
                "resume-lane=${lane.render()} t=${ctx.ms()}ms",
        )
    }

    /**
     * The phone that keeps its radio — a **witness**, and, just as load-bearing, the **peer the other
     * one has to come back to**. #1637 is entirely joiner-side, so this side's correct *observation* is
     * nothing at all: its link to the joiner never closed, so it never partitions it and never opens a
     * window. That is precisely the precondition the bug needs, which makes "I saw nothing" the useful
     * thing to record.
     *
     * It therefore reads the same on a fixed and an unfixed build — **it is not the verdict that
     * matters**. What it *can* do is falsify the run: a `Partitioned` for the joiner proves the outage
     * overran this side's link, which puts the whole run outside the repro band regardless of what the
     * other phone concluded.
     *
     * ## Why it dwells instead of returning on the first thing it sees
     *
     * The first hardware run (2026-07-27) invalidated itself here. This side observed its `Partitioned`
     * at t=25.4s, decided its verdict, and **finished** — which stopped its advertisement. The joiner's
     * radio came back 4.1s later, its browse found only itself (`nw.loom.self-skip`), its dial was
     * refused (`posix code=61`), and it sent **zero** Resumes: the resume machine under test was never
     * entered, so the run said nothing about #1637 while looking like a clean pair of reports.
     *
     * So observing a `Partitioned` must not end this scenario. This side stays alive — advertising,
     * admitting, answering resumes — for the joiner's whole episode, and the report states how long it
     * stayed up so a later reader can see the joiner *had* a live peer to return to. The dwell ends on
     * whichever comes first:
     *
     *  - the joiner **leaving** (`Left`) — its episode is over by construction, there is nothing further
     *    to witness and nothing further to be present for;
     *  - the [HOST_OBSERVE] ceiling, extended to at least [HOST_HOLD_AFTER_PARTITION] past any
     *    `Partitioned` this side did emit.
     *
     * **An empty final roster here is normal, not a finding** — the other phone finishes first and calls
     * `leave()`. It says nothing about the outage; the claim this side makes is only ever *"I never
     * partitioned them, and I was still here"*.
     */
    private suspend fun stayUpSide(ctx: OutageCtx, peer: PeerId) {
        val room = ctx.room
        ctx.say(
            "Hold still. The other phone goes offline for a few seconds. Do NOT touch this one, and do " +
                "NOT close the app — it has to stay reachable until the other phone finishes its whole " +
                "reconnect. That phone now watches for the FULL $OBSERVE_WINDOW after its radio dies " +
                "before it will say anything, so expect about two minutes; at most " +
                "${HOST_OBSERVE.inWholeMinutes} minutes.",
        )
        if (!ctx.armOutage("blip")) return
        val seen = mutableListOf<MembershipEvent>()
        val dwell = TimeSource.Monotonic.markNow()
        // A deadline that can be pushed out, not a fixed wait: the ONE thing this side must not do is
        // stop being reachable while the other phone is still trying to reach it.
        var deadline = HOST_OBSERVE
        var partitioned: MembershipEvent.Partitioned? = null
        var partitionedAt = 0L
        var leftAt: Duration? = null
        while (dwell.elapsedNow() < deadline) {
            val e = awaitEvent(ctx.queue, seen, deadline - dwell.elapsedNow()) { it } ?: break
            if (e is MembershipEvent.Partitioned && e.peerId == peer && partitioned == null) {
                partitioned = e
                partitionedAt = dwell.elapsedNow().inWholeMilliseconds
                val extended = dwell.elapsedNow() + HOST_HOLD_AFTER_PARTITION
                if (extended > deadline) deadline = extended
                ctx.hop(
                    "blip: I partitioned ${peer.value.take(8)} at dwell=${fmtMs(partitionedAt)} " +
                        "(${e.short()}) — the run is out of band, but staying up to " +
                        "${fmtMs(deadline.inWholeMilliseconds)} anyway so the joiner still has a live peer " +
                        "to come back to",
                )
            }
            if (e is MembershipEvent.Left && e.peerId == peer) {
                leftAt = dwell.elapsedNow()
                ctx.hop(
                    "blip: ${peer.value.take(8)} LEFT at dwell=${fmtMs(dwell.elapsedNow().inWholeMilliseconds)} " +
                        "(${e.short()}) — its episode is over, so there is nothing left to be present for",
                )
                break
            }
        }
        val stayedUp = dwell.elapsedNow().inWholeMilliseconds
        val endedOn = if (leftAt != null) "the other phone leaving" else "the ${fmtMs(deadline.inWholeMilliseconds)} ceiling"
        val mine = room.localFabric.value
        if (mine !is FabricAvailability.Available) {
            ctx.failures.add(
                "blip: MY localFabric read ${mine.short()} — this phone's radio was never touched, so it " +
                    "must still read Available; nothing observed here can be trusted otherwise",
            )
        }
        val p = partitioned
        if (p != null) {
            // Named off the event's OWN reason, not off the configured detect. On the 2026-07-27 run this
            // read `TransportClosed` at t≈18.6s under a `detect=30s` config — the heartbeat detect was
            // never reached and never could have been. Asserting "my 30s detect elapsed" there was a
            // claim about a mechanism the run did not observe.
            ctx.skip = "blip: I DID notice ${peer.value.take(8)} go quiet (${p.short()}) at " +
                "dwell=${fmtMs(partitionedAt)} — reason=${p.reason::class.simpleName}. " +
                "TransportClosed means my TCP connection to the vanished peer died of its own ETIMEDOUT " +
                "(~${fmtMs(OBSERVED_HOST_TRANSPORT_DEATH.inWholeMilliseconds)} after the radio on the one " +
                "run we have measured — one observation, not a constant), which fires no matter how " +
                "${S7_HEARTBEAT.timeout} detect is configured; LinkTimeout means the heartbeat detect " +
                "really did elapse. Either way a window opened on THIS side, so the joiner's resume takes " +
                "the ordinary lane and this run says nothing about #1637. Cross-check the DROPPED phone's " +
                "measured outage and re-run with a shorter hold. I stayed up ${fmtMs(stayedUp)} " +
                "(ended on $endedOn), so the joiner did have a live peer to come back to"
        } else {
            ctx.passDetail = "never partitioned ${peer.value.take(8)} while staying up ${fmtMs(stayedUp)} " +
                "(ended on $endedOn) — my link to it never closed, so no window ever opened. That is the " +
                "#1637 precondition holding, AND the joiner had a live peer to return to for the whole " +
                "episode. The verdict that matters is on the DROPPED phone. roster=${room.roster.value.render()}"
        }
        ctx.hop(
            "blip: DONE stayedUp=${fmtMs(stayedUp)} endedOn=$endedOn saw=${seen.render()} " +
                "mine=${mine.short()} roster=${room.roster.value.render()} t=${ctx.ms()}ms",
        )
    }

    /**
     * Drain [queue] until [select] returns non-null or [timeout] elapses; everything consumed lands in
     * [seen] so a failing phase can report what it *did* see instead of only what it missed.
     *
     * A queue, not a re-subscription to [Room.events] — the room's replay cache would let a later phase
     * match an earlier phase's event, and the second outage's assertions must not be satisfied by the
     * first one's evidence. A *selector* rather than a predicate so each call site gets the concrete
     * event type back and can read its `localFabric` tag without a cast.
     */
    private suspend fun <T : Any> awaitEvent(
        queue: ReceiveChannel<MembershipEvent>,
        seen: MutableList<MembershipEvent>,
        timeout: Duration,
        select: (MembershipEvent) -> T?,
    ): T? = withTimeoutOrNull(timeout) {
        var found: T? = null
        while (found == null) {
            val e = queue.receive()
            seen.add(e)
            found = select(e)
        }
        found
    }

    /** Bounded wait on the authoritative *level* — a `StateFlow`, so it reads current, never stale. */
    private suspend fun awaitFabric(
        room: Room,
        timeout: Duration,
        match: (FabricAvailability) -> Boolean,
    ): FabricAvailability? = withTimeoutOrNull(timeout) { room.localFabric.first(match) }

    /** Bounded wait on the authoritative roster — the documented way to ask "is the seat back?". */
    private suspend fun awaitRoster(
        room: Room,
        timeout: Duration,
        match: (Set<Member>) -> Boolean,
    ): Set<Member>? = withTimeoutOrNull(timeout) { room.roster.first(match) }

    // ── report rendering: identities and values, never counts ──────────────────

    private fun FabricAvailability.short(): String = when (this) {
        FabricAvailability.Available -> "Available"
        is FabricAvailability.Unavailable -> "Unavailable($reason)"
        is FabricAvailability.Unknown -> "Unknown($reason)"
    }

    private fun Liveness.short(): String = when (this) {
        Liveness.Connected -> "Connected"
        is Liveness.Partitioned -> "Partitioned(since=$since window→$windowExpiresAt)"
    }

    private fun Set<Member>.render(): String =
        "[${joinToString(",") { "${it.id.value.take(8)}:${it.liveness.short()}" }}]"

    private fun List<MembershipEvent>.render(): String = "[${joinToString(",") { it.short() }}]"

    private fun MembershipEvent.short(): String = when (this) {
        is MembershipEvent.Joined -> "Joined(${member.id.value.take(8)},${member.liveness.short()})"
        is MembershipEvent.Left -> "Left(${peerId.value.take(8)},${reason::class.simpleName})"
        // `at` on the four outage-bearing events, not just their tag: it is the only thing in the shared
        // report that distinguishes an event from THIS outage from a stale one carried over from an earlier
        // path flap. Free to print, and the operator's report is the only artifact a debugger gets.
        is MembershipEvent.Partitioned ->
            "Partitioned(${peerId.value.take(8)},${reason::class.simpleName},mine=${localFabric.short()},at=$at)"
        is MembershipEvent.Recovered -> "Recovered(${peerId.value.take(8)})"
        is MembershipEvent.WindowOpened -> "WindowOpened(${peerId.value.take(8)},expires=$expiresAt)"
        is MembershipEvent.Resumed -> "Resumed(${peerId.value.take(8)})"
        is MembershipEvent.HostLost -> "HostLost(${reason::class.simpleName},mine=${localFabric.short()},at=$at)"
        is MembershipEvent.LocalFabricLost -> "LocalFabricLost($reason,at=$at)"
        is MembershipEvent.LocalFabricRestored -> "LocalFabricRestored(at=$at)"
        is MembershipEvent.AdmissionFailed -> "AdmissionFailed(${reason::class.simpleName})"
    }

    /**
     * Why a joiner's session died, spelled out for a report someone pastes into an issue. `Refused`
     * gets its `RejectCode` *and* that code's `retryable` flag, because `Refused(…, retryable=true)`
     * repeated to exhaustion is the #1637 signature and "Refused" alone does not say that.
     */
    private fun FailureReason.describe(): String = when (this) {
        FailureReason.WindowExpired -> "WindowExpired"
        FailureReason.Unrecoverable -> "Unrecoverable"
        is FailureReason.Refused ->
            "Refused(code=${code.id.ifEmpty { "<none>" }},retryable=${code.retryable},msg=$message)"
    }

    private fun SeamState.short(): String = when (this) {
        SeamState.Weaving -> "Weaving"
        SeamState.Woven -> "Woven"
        is SeamState.Torn -> "Torn"
    }

    private fun pattern() = Pattern(sessionName = SESSION, roomKey = ROOM_KEY)

    private fun deviceDescription(): String = NSProcessInfo.processInfo.operatingSystemVersionString

    private suspend fun settle() = delay(1.seconds)

    private companion object {
        // The out-of-band shared secret → TLS-PSK. Both phones run the same binary, so the constant IS
        // the out-of-band channel for this diagnostic. NOT a production pattern.
        // The ORIGINAL key — under the pre-#1577 derivation this derived a PSK identity with a 0x00 at
        // byte 8 for _ksuite4a._tcp, and scenario 4 failed 100% on two iPhones. Restored deliberately:
        // it is the reproducer, so the fix must be validated against THIS key, not a lucky one.
        const val ROOM_KEY = "kuilt-suite-psk"
        const val SESSION = "kuilt-suite"

        /** Role selecting the #2488 mDNS-rename probe — not a scenario. See `runRenameProbe`. */
        const val RENAME_PROBE_ROLE = "rename-probe"

        /** The pinned Bonjour instance name the probe advertises, and that the operator pre-registers. */
        const val RENAME_PROBE_NAME = "kuiltrenameprobe"

        /** How long the probe keeps advertising. Long enough to read the log, short enough to babysit. */
        val RENAME_PROBE_WINDOW: Duration = 60.seconds

        /** Role suffix selecting the scenario-4-in-isolation diagnostic run (#1467). */
        const val S4_SUFFIX = "-s4"

        /** Role suffix selecting the operator-driven scenario-6 local-fabric gate (#1712). */
        const val S6_SUFFIX = "-s6"

        /** Role suffix selecting the operator-driven scenario-7 sub-timeout-blip repro (#1637). */
        const val S7_SUFFIX = "-s7"

        // Per-scenario Bonjour service types keep each scenario's advertise/browse isolated so a
        // lingering listener from an earlier scenario can't cross-talk. ≤15-char service labels.
        // EVERY one of these must also be declared in app/project.yml's NSBonjourServices or iOS
        // silently blocks discovery — no error, the weave just times out.
        const val SVC2 = "_ksuite2._tcp"
        const val SVC3 = "_ksuite3._tcp"
        const val SVC4 = "_ksuite4a._tcp"
        const val SVC4B = "_ksuite4b._tcp"
        const val SVC5 = "_ksuite5._tcp"
        const val SVC6 = "_ksuite6._tcp"
        const val SVC7 = "_ksuite7._tcp"

        val RAW_TIMEOUT: Duration = 45.seconds
        val WEAVE_TIMEOUT: Duration = 45.seconds
        val ELECTION_TIMEOUT: Duration = 30.seconds

        /**
         * How long the **joiner** gets to observe the recoverable `Woven → Weaving` re-form after the host
         * drops the link. Emphatically NOT a "torn" timeout: peer loss never latches `SeamState.Torn`
         * (#1513), so a wait for `Torn` here could only ever burn this whole budget and report a false FAIL
         * — which is what it did until #1836. The host waits nothing; its own `close()` is synchronous.
         */
        val PEER_LOSS_TIMEOUT: Duration = 20.seconds
        val SOAK: Duration = 120.seconds
        val PING_INTERVAL: Duration = 250.milliseconds
        const val STALL_MS = 2_000L
        const val MAX_STALLS = 3

        // ── scenario 6 (#1712) ────────────────────────────────────────────────
        //
        // The two numbers that matter, and why they are not the defaults (5s/15s/60s):
        //
        //  * `timeout` 5s (not 15s) — and the binding reason is `NwLoom.DEFAULT_WOVEN_PATH_GRACE` (10s),
        //    not operator perception. A path-lost connection gets that 10s grace before the seam tears it,
        //    so an ~8s outage NEVER evicts the peer from `seam.peers` and the detector's eviction branch
        //    cannot fire — only its timeout branch can. At the stock 15s that leaves `15 > 10 > 8`: the
        //    timeout branch never fires either and NEITHER side detects anything at all, so both halves of
        //    the asymmetry go unexercised and the scenario would SKIP forever. 5s puts detection safely
        //    inside both bounds while staying well clear of a healthy AWDL link (the scenario-5 soak
        //    measures p95 ≈ 30ms with zero stalls), and `interval` 1.5s means three missed pings, not one.
        //  * `reconnectWindow` 60s (the default, deliberately kept) — it is what makes ~8s "short" and
        //    the second outage "long". It also sets the long outage's LENGTH: the dropped phone waits
        //    for its own window to expire (~65s: detect + window) before telling the operator to turn
        //    the radio back on, so the operator never has to time the one interval that must overrun.
        val S6_HEARTBEAT: HeartbeatConfig = HeartbeatConfig(
            interval = 1_500.milliseconds,
            timeout = 5.seconds,
            reconnectWindow = 60.seconds,
        )

        /** Both phones woven → one admitted, connected member. Generous: two people, two buttons. */
        val ADMIT_WAIT: Duration = 60.seconds

        /** How long `nw`'s path monitor gets to report a usable path before the run is called unobservable. */
        val BASELINE_WAIT: Duration = 20.seconds

        /**
         * How long the operator gets to find the Airplane Mode toggle after being asked. Two minutes
         * because they are holding two phones and reading a prompt — a scenario that FAILs on a fumble
         * is worse than no scenario.
         */
        val TOGGLE_WAIT: Duration = 120.seconds

        /**
         * Level → matching edge event. `Room.localFabric` is allowed to run *ahead* of the
         * `LocalFabricLost`/`Restored` notification (events are buffered), so this bounds the lag,
         * not the outage.
         */
        val EDGE_WAIT: Duration = 20.seconds

        /** The short outage the operator is asked to hold — comfortably past detect, far inside the window. */
        val SHORT_DROP: Duration = 8.seconds

        /** Radio back → fabric, edge and seat all recovered. Covers a whole reconnect window plus slack. */
        val RECOVER_WAIT: Duration = 90.seconds

        /** Radio down → seat expired (detect + window ≈ 65s), plus generous slack for a real radio. */
        val EXPIRY_WAIT: Duration = 150.seconds

        // ── scenario 7 (#1637) ────────────────────────────────────────────────
        //
        // Scenario 7 is defined by ONE inequality that scenario 6 deliberately inverts:
        //
        //     wovenPathGrace  <  outage  <  the host's link to a vanished peer dying
        //
        // Below the grace the joiner's seam never evicts the host, so the resume machine is never
        // entered. Above the point where the HOST's link dies the host DOES notice, opens a window, and
        // the resume succeeds normally. #1637 lives strictly between the two — and with scenario 6's
        // `timeout` of 5s that interval is EMPTY, which is why a separate scenario exists rather than
        // another phase bolted onto scenario 6.
        //
        // **The upper bound is NOT `HeartbeatConfig.timeout`, and raising `timeout` cannot widen it.**
        // The first hardware run (2026-07-27) was configured `detect=30s` and the host still noticed at
        // t≈18.6s: its TCP connection to the vanished peer died of its own ETIMEDOUT (`nw_error
        // domain=posix code=60`) and fired `TransportClosed` — a transport-level death the heartbeat
        // detect has no say over. So the *reachable* band was (10s, ~18.6s) and the whole upper half of
        // the configured (10s, 30s) was physically empty. `timeout` is therefore set to production's
        // 15s: it is below the observed transport death, so the band it names is one the run can
        // actually land in, and it matches #1637's original "<15s blip" framing.
        //
        // **The FLOOR, on the other hand, IS a knob — and scenario 7 turns it.** With production's 10s
        // grace the band was (10s, 15s): five seconds wide, aimed through ~9s of uncontrolled radio
        // lag, i.e. barely hittable by hand. `appleNwLoom` now exposes `wovenPathGrace`, so scenario 7
        // — and ONLY scenario 7 — lowers its own floor to [S7_WOVEN_PATH_GRACE], widening the band to
        // (3s, 15s). Lowering the floor changes WHEN the joiner starts its resume episode, never the
        // mechanism it then exercises: the eviction still happens while the radio is off, the joiner
        // still cannot dial until the path returns, the host is untouched, and the fix under test keys
        // its dwell on `HeartbeatConfig.timeout` rather than on the grace.

        /**
         * The fabric's **shipping** grace: how long a path-lost connection is given to recover before
         * the woven seam tears it (#1478). Imported from `:kuilt-nw`, not mirrored — the old hand-copied
         * literal could silently drift from the real default. Referenced only in prose about what
         * production does; scenario 7 itself runs on [S7_WOVEN_PATH_GRACE].
         */
        val PRODUCTION_WOVEN_PATH_GRACE: Duration = NwLoom.DEFAULT_WOVEN_PATH_GRACE

        /**
         * **A deliberate test-only override, not production behaviour.** Scenario 7 passes this to
         * `appleNwLoom(wovenPathGrace = …)` so its own repro band starts at 3s instead of the shipping
         * [PRODUCTION_WOVEN_PATH_GRACE] (10s). Nothing else in this suite — and nothing in any shipping
         * consumer — overrides the grace.
         *
         * **Why lower it.** The band's ceiling is the host's transport dying and cannot be raised (see
         * the block comment above), so the floor is the only movable edge. At 10s the band was 5s wide
         * and had to be aimed through ~[RESTORE_LAG] of uncontrolled radio lag — a target so tight that
         * most runs SKIP. At 3s it is 12s wide.
         *
         * **Why it is still the same bug.** #1637 is "the joiner evicted the host, the host never
         * noticed, so every Resume is answered `WindowNotYetOpen` forever". The grace decides only
         * *when* the eviction happens; every clause after it is unchanged. The joiner's radio is still
         * off at eviction time, so it still cannot dial until the path returns; the host's side never
         * sees the grace at all; and the fix under test dwells on `HeartbeatConfig.timeout`, not on
         * the grace, so the value here cannot flatter it.
         */
        val S7_WOVEN_PATH_GRACE: Duration = 3.seconds

        /**
         * The numbers that make the repro band exist, and why they are what they are:
         *
         *  * `timeout` **15s** — production's default, and deliberately *not* a raised one. It reads as
         *    the band's upper edge, but the real ceiling is the host's transport dying (see the block
         *    comment above): the 2026-07-27 run configured 30s and the host still noticed at ~18.6s via
         *    `TransportClosed`. Configuring anything above the transport death buys nothing and lies to
         *    the operator about how much room they have, so the number is pulled back under it. 15s also
         *    still buys the redial: after the path returns the joiner needs a second or so to re-dial and
         *    land its first `Resume`.
         *  * `reconnectWindow` **60s** (the default, kept) — the budget #1637 burns. It sets how long a
         *    FAIL takes to arrive (≈ grace + window ≈ 70s from the radio dying) and, post-fix, leaves
         *    ample room for the dwell: the fix concludes after `timeout` (15s) of `WindowNotYetOpen`,
         *    so a recovery lands at ≈ outage + 17s, comfortably inside 70s for any outage the gate
         *    admits.
         *
         * `interval` 1.5s: three-second-scale retries keep the trace dense and, in the failing lane,
         * refresh the host's `lastSeen` often enough that the bug is reproduced firmly rather than
         * marginally. `resumeTimeout` is left at its default (= `timeout`) — a resume is only ever
         * issued once the seam is `Woven` again, and the host answers `WindowNotYetOpen` in
         * milliseconds, so the per-RPC deadline never binds here.
         */
        val S7_HEARTBEAT: HeartbeatConfig = HeartbeatConfig(
            interval = 1_500.milliseconds,
            timeout = 15.seconds,
            reconnectWindow = 60.seconds,
        )

        /**
         * When the HOST's own link to a peer whose radio vanished died, on the first hardware run
         * (2026-07-27, iPhone 17 Pro ↔ iPhone XS over AWDL): `nw-2 FAILED nw_error domain=posix code=60`
         * — ETIMEDOUT — 18.6s after the radio went off, followed immediately by a `TransportClosed`
         * partition. That, not [HeartbeatConfig.timeout], is what actually caps the repro band.
         *
         * **One observation, not a constant.** It is a TCP/Network.framework retransmission timeout on
         * one pair of phones on one network; it will move with RTT, radio, and OS version. It is used
         * only to explain a SKIP to the operator, never to decide a verdict — the verdict's band is
         * [S7_WOVEN_PATH_GRACE]..[S7_HEARTBEAT]`.timeout`, which is deliberately set *below* this.
         */
        val OBSERVED_HOST_TRANSPORT_DEATH: Duration = 18_600.milliseconds

        /**
         * How much longer the outage runs than the operator's thumb, measured on the first hardware run:
         * a 15.0s hold produced a **23.7s** measured outage — 8.7s of lag. Two things make it up, and
         * neither is under the suite's control: the operator's reaction to the OFF prompt, and Wi-Fi
         * re-association (`localFabric` only returns to `Available` once `nw_path_monitor` has a usable
         * path again, which is well after the toggle moves).
         *
         * **One observation, and the least stable number here** — it is the operator's thumb plus a
         * radio. It is used only to *aim* [BLIP_HOLD]; the verdict is always measured from
         * `localFabric`'s own edges, never from this.
         */
        val RESTORE_LAG: Duration = 9.seconds

        /**
         * How long the operator is asked to hold the radio down — **derived**, not chosen, and the
         * derivation is unchanged from when the band was (10s, 15s): aim the middle of the band and
         * subtract the lag the radio adds on the way back ([RESTORE_LAG]).
         *
         * What changed is the inputs. Lowering the floor to [S7_WOVEN_PATH_GRACE] makes the band
         * (3s, 15s), whose midpoint is 9s — almost exactly [RESTORE_LAG]. The subtraction therefore
         * bottoms out on the 1s floor, and that floor is now the *instruction*: **flip Airplane Mode on
         * and straight back off.** The radio supplies essentially the whole outage, landing ≈ 1s + 9s
         * ≈ 10s — 7s clear of the bottom edge and 5s clear of the top, versus the ±2.5s of headroom the
         * old 5s band allowed.
         *
         * The 1s coercion is load-bearing, not defensive: without it the derivation asks for a
         * zero-length hold, which is not a thing a thumb can do.
         */
        val BLIP_HOLD: Duration =
            ((S7_WOVEN_PATH_GRACE + S7_HEARTBEAT.timeout) / 2 - RESTORE_LAG).coerceAtLeast(1.seconds)

        /**
         * Within this of a band edge, a run is flagged MARGINAL in the trace (the verdict is unchanged).
         *
         * 2s now that the band is 12s wide — it was shaved to 1s only because a 5s band flagged every
         * run at 2s and the flag stopped meaning anything. The non-marginal core is 8s across, wider
         * than the entire old band, so the flag is back to marking a genuinely close call.
         */
        val BLIP_MARGIN: Duration = 2.seconds

        /**
         * Headroom on top of the joiner's whole reconnect budget before a verdict is rendered.
         *
         * The budget itself is exact — [S7_WOVEN_PATH_GRACE] before the seam evicts the host, then
         * `reconnectWindow` before the episode dies — but the instants are not: the seam's eviction
         * runs off its own path-monitor callback, a real radio does not come back on a schedule, and
         * `HostLost` is delivered through a buffered event flow. 25 s covers all three without
         * stretching a normal run, because a normal run does not wait it out (a `HostLost` ends the
         * observation the moment it lands).
         */
        val OBSERVE_SLACK: Duration = 25.seconds

        /**
         * **How long the dropped phone watches after the radio dies before it is allowed to say
         * anything.** A deadline, not a first-match wait — that distinction is the whole of this
         * scenario's first defect.
         *
         * #1637 does not kill the room promptly; it kills it when the reconnect window expires,
         * ≈ [S7_WOVEN_PATH_GRACE] + [S7_HEARTBEAT]`.reconnectWindow` ≈ 63 s after the radio died. The
         * previous code returned on the first `Recovered`/`Resumed`/`HostLost` it saw, so a room that
         * looked healthy early was declared PASS at ~15 s with 48 s of the very window the bug lives in
         * unobserved. The 2026-07-28 hardware run did exactly that, on a build carrying the fix, and
         * the fix had not run.
         *
         * So: watch the whole budget plus [OBSERVE_SLACK] and only then decide. `HostLost` still ends
         * it early — the room is terminal and nothing can follow — so a genuine #1637 FAIL still
         * arrives as fast as it ever did; it is the *green* answers that now have to survive the full
         * window.
         */
        val OBSERVE_WINDOW: Duration =
            S7_WOVEN_PATH_GRACE + S7_HEARTBEAT.reconnectWindow + OBSERVE_SLACK

        /**
         * The **ceiling** on how long the stay-up phone keeps its room alive — advertising, admitting,
         * and answering resumes — after arming. Not a "watch for an event" window: this phone's job is
         * to still be *there* when the other one comes back, and the first hardware run failed on
         * exactly that (it decided its verdict on the first `Partitioned` and exited at t=25.4s, four
         * seconds before the joiner's radio returned, so the joiner's browse found nothing to dial and
         * sent zero Resumes — every such run is invalidated, whatever the joiner reports).
         *
         * Sized for the worst case the joiner's own gates admit, and re-derived when [OBSERVE_WINDOW]
         * replaced the old first-match verdict: [TOGGLE_WAIT] (120s) to find the toggle + [BLIP_HOLD]
         * + [RECOVER_WAIT] (90s) for the radio + [OBSERVE_WINDOW] (88s) ≈ 299s. At the old 240s this
         * side could have stopped advertising while the joiner was still inside its observation — the
         * exact failure the first hardware run had, reintroduced by a longer joiner. It is a ceiling,
         * not a duration: a normal run ends much sooner (≈2 minutes), when the joiner leaves.
         */
        val HOST_OBSERVE: Duration = 300.seconds

        /**
         * If the stay-up phone *does* partition the joiner, it stays up at least this much longer past
         * that instant. The joiner's own window expires ≈ [S7_WOVEN_PATH_GRACE] + `reconnectWindow` after
         * its radio died, and this side learns of the outage *later* than that radio died — so this
         * only ever needs to cover the remainder. Generous on purpose: the run is already out of band
         * by then and the only remaining job is to not be the reason the joiner's episode failed.
         */
        val HOST_HOLD_AFTER_PARTITION: Duration = 120.seconds
    }
}
