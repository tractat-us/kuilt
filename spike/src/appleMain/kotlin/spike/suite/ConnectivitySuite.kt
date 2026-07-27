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
import kotlinx.coroutines.delay
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
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
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
 * **Scenario 6 is separate and operator-driven** (#1712): it needs a human to toggle Airplane Mode,
 * which no API can do, so it never runs in the automatic battery. Two dedicated buttons run it alone —
 * and there the Host/Join button *does* pick the role, because the operator has to know which phone to
 * take offline. See [scenarioLocalFabric].
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
     * scenario 6 uses it to ask for an Airplane Mode toggle. It is deliberately a separate channel from
     * [onLog] so the UI can render it as a banner the operator cannot miss; an empty string clears it.
     * The automatic scenarios never call it.
     */
    public fun start(
        role: String,
        onLog: (String) -> Unit,
        onScenario: (ScenarioResult) -> Unit,
        onPrompt: (String) -> Unit,
        onComplete: (String) -> Unit,
    ) {
        scope.launch {
            try {
                runSuite(role, onLog, onScenario, onPrompt, onComplete)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onLog("suite crashed: ${e::class.simpleName}: ${e.message}")
                onPrompt("")
                onComplete(
                    SuiteReport(role, NSDate().timeIntervalSince1970, EnvSnapshot.UNKNOWN, deviceDescription(), results.toList()).text,
                )
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
    ) {
        results.clear()
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
        val baseRole = role.removeSuffix(S4_SUFFIX).removeSuffix(S6_SUFFIX)
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
        onLog("suite start role=$baseRole s4Only=$s4Only s6Only=$s6Only")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
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
                if (amHost) lobby.start(memberName = role) else lobby.awaitRoom(memberName = role)
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

            hop("role=$role svcLeg1=$SVC4 svcLeg2=$SVC4B weaveTimeout=$WEAVE_TIMEOUT tornTimeout=$TORN_TIMEOUT")
            hop("leg1 weave svc=$SVC4 t=${ms()}ms")
            val loomA = appleNwLoom(SVC4, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            hop("leg1 self=${loomA.selfId.value.take(8)}")
            val seamA = instrumentedWeave("leg1", loomA, hop)
                ?: return@scenario Verdict.FAIL to "leg1 never established on $SVC4 (teardown/reconnect NOT exercised)"
            withTimeoutOrNull(5.seconds) { seamA.state.first { it is SeamState.Woven } }
            hop(
                "leg1 wove state=${seamA.state.value.short()} " +
                    "peers=[${seamA.peers.value.joinToString(",") { it.value.take(8) }}] t=${ms()}ms",
            )

            val tornObserved: Boolean
            if (role == "host") {
                delay(2.seconds) // let the joiner settle on the live link before the drop
                hop("dropping link (host close) t=${ms()}ms")
                seamA.close(CloseReason.Normal)
                tornObserved = true // a local close is this side's own terminal signal
                hop("post-close stateA=${seamA.state.value.short()} t=${ms()}ms")
            } else {
                hop("awaiting terminal Torn after host drop t=${ms()}ms")
                val torn = withTimeoutOrNull(TORN_TIMEOUT) { seamA.state.first { it is SeamState.Torn } }
                tornObserved = torn != null
                hop("torn=$tornObserved stateA=${seamA.state.value.short()} t=${ms()}ms")
                seamA.close(CloseReason.Normal) // idempotent
            }

            hop("leg2 reweave svc=$SVC4B t=${ms()}ms")
            val loomB = appleNwLoom(SVC4B, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            hop("leg2 self=${loomB.selfId.value.take(8)}")
            val reMark = TimeSource.Monotonic.markNow()
            val seamB = instrumentedWeave("leg2", loomB, hop)
                ?: return@scenario Verdict.FAIL to "torn=$tornObserved but leg2 never established on $SVC4B"
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
            if (tornObserved && peers >= 2) Verdict.PASS to "Torn seen; re-wove in ${fmtMs(reMs)}"
            else Verdict.FAIL to "torn=$tornObserved reconnect peers=$peers"
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

        /** Role suffix selecting the scenario-4-in-isolation diagnostic run (#1467). */
        const val S4_SUFFIX = "-s4"

        /** Role suffix selecting the operator-driven scenario-6 local-fabric gate (#1712). */
        const val S6_SUFFIX = "-s6"

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

        val RAW_TIMEOUT: Duration = 45.seconds
        val WEAVE_TIMEOUT: Duration = 45.seconds
        val ELECTION_TIMEOUT: Duration = 30.seconds
        val TORN_TIMEOUT: Duration = 20.seconds
        val SOAK: Duration = 120.seconds
        val PING_INTERVAL: Duration = 250.milliseconds
        const val STALL_MS = 2_000L
        const val MAX_STALLS = 3

        // ── scenario 6 (#1712) ────────────────────────────────────────────────
        //
        // The two numbers that matter, and why they are not the defaults (5s/15s/60s):
        //
        //  * `timeout` 5s (not 15s) — and the binding reason is `NwSeam.DEFAULT_WOVEN_PATH_GRACE` (10s),
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
    }
}
