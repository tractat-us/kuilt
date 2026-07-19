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
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.nw.NwLoom
import us.tractat.kuilt.nw.appleNwLoom
import us.tractat.kuilt.session.SeamRoomFactory
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
     */
    public fun start(
        role: String,
        onLog: (String) -> Unit,
        onScenario: (ScenarioResult) -> Unit,
        onComplete: (String) -> Unit,
    ) {
        scope.launch {
            try {
                runSuite(role, onLog, onScenario, onComplete)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onLog("suite crashed: ${e::class.simpleName}: ${e.message}")
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
        onComplete: (String) -> Unit,
    ) {
        results.clear()
        // A "-s4" role suffix runs ONLY scenario 4 — the #1467 controlled experiment. Scenario 4's leg1
        // is structurally identical to scenario 2 (same weave call, different service type), yet it timed
        // out at 45s in the field while 2/3/5 wove in under a second. Running it alone, in a process where
        // no earlier scenario has left a listener/browser alive, isolates "accumulated state" from
        // "intrinsic to this service type" — one variable.
        val s4Only = role.endsWith(S4_SUFFIX)
        val baseRole = role.removeSuffix(S4_SUFFIX)
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
        onLog("suite start role=$baseRole s4Only=$s4Only")
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
        } else {
            step { scenarioRawRoundTrip(baseRole, onLog) }
            step { scenarioSeamWeave(baseRole, onLog) }
            step { scenarioElection(baseRole, onLog) }
            step { scenarioTeardownReconnect(baseRole, onLog) }
            step { scenarioSoak(baseRole, onLog) }
        }

        val report = SuiteReport(
            if (s4Only) "$baseRole S4-ONLY" else baseRole,
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

        // Per-scenario Bonjour service types keep each scenario's advertise/browse isolated so a
        // lingering listener from an earlier scenario can't cross-talk. ≤15-char service labels.
        const val SVC2 = "_ksuite2._tcp"
        const val SVC3 = "_ksuite3._tcp"
        const val SVC4 = "_ksuite4a._tcp"
        const val SVC4B = "_ksuite4b._tcp"
        const val SVC5 = "_ksuite5._tcp"

        val RAW_TIMEOUT: Duration = 45.seconds
        val WEAVE_TIMEOUT: Duration = 45.seconds
        val ELECTION_TIMEOUT: Duration = 30.seconds
        val TORN_TIMEOUT: Duration = 20.seconds
        val SOAK: Duration = 120.seconds
        val PING_INTERVAL: Duration = 250.milliseconds
        const val STALL_MS = 2_000L
        const val MAX_STALLS = 3
    }
}
