@file:OptIn(ExperimentalForeignApi::class)

package spike.suite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
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
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
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
        onLog("suite start role=$role")
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

        step { scenarioRawRoundTrip(role, onLog) }
        step { scenarioSeamWeave(role, onLog) }
        step { scenarioElection(role, onLog) }
        step { scenarioTeardownReconnect(role, onLog) }
        step { scenarioSoak(role, onLog) }

        val report = SuiteReport(role, startedAt, env, device, results.toList())
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
            val seam = loom.weave(Rendezvous.New(pattern()))
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

    private suspend fun scenarioTeardownReconnect(role: String, onLog: (String) -> Unit): ScenarioResult =
        scenario(4, "Teardown+reconnect", onLog) { hop ->
            hop("leg1 weave svc=$SVC4")
            val loomA = appleNwLoom(SVC4, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            val seamA = loomA.weave(Rendezvous.New(pattern()))
            withTimeoutOrNull(5.seconds) { seamA.state.first { it is SeamState.Woven } }
            hop("leg1 wove peers=${seamA.peers.value.size}")

            val tornObserved: Boolean
            if (role == "host") {
                delay(2.seconds) // let the joiner settle on the live link before the drop
                hop("dropping link (host close)")
                seamA.close(CloseReason.Normal)
                tornObserved = true // a local close is this side's own terminal signal
            } else {
                hop("awaiting terminal Torn after host drop")
                val torn = withTimeoutOrNull(TORN_TIMEOUT) { seamA.state.first { it is SeamState.Torn } }
                tornObserved = torn != null
                hop("torn=$tornObserved")
                seamA.close(CloseReason.Normal) // idempotent
            }

            hop("leg2 reweave svc=$SVC4B")
            val loomB = appleNwLoom(SVC4B, ROOM_KEY, weaveTimeout = WEAVE_TIMEOUT)
            val reMark = TimeSource.Monotonic.markNow()
            val seamB = loomB.weave(Rendezvous.New(pattern()))
            val reMs = reMark.elapsedNow().inWholeMilliseconds
            val peers = seamB.peers.value.size
            hop("leg2 wove peers=$peers in ${reMs}ms")
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
        const val ROOM_KEY = "kuilt-suite-psk"
        const val SESSION = "kuilt-suite"

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
