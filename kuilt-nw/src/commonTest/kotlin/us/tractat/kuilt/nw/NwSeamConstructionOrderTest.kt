@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher is the eager-dispatch rig

package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `NwSeam`'s constructor must not start a coroutine that reads a field the constructor has not yet
 * initialised (#2462).
 *
 * ## The defect this pins
 * Kotlin runs property initialisers in declaration order, so a `scope.launch { … }` written as a
 * property initialiser can only safely touch state declared **above** it. #2451 added the wedge
 * watchdog as `private val silenceJob = scope.launch { inboundSilenceLoop() }` at the top of the
 * class and its `watchdogWake` [kotlinx.coroutines.flow.MutableStateFlow] 840 lines below, so a
 * dispatcher that runs the body before the constructor finished dereferenced a `null` backing field:
 *
 * ```
 * java.lang.NullPointerException: Cannot invoke "kotlinx.coroutines.flow.Flow.collect(…)"
 *     at us.tractat.kuilt.nw.NwSeam.inboundSilenceLoop(NwSeam.kt:1290)
 * ```
 *
 * The throw lands inside a `launch` body, so it reaches the scope's `CoroutineExceptionHandler`
 * rather than the caller — the seam is constructed successfully with its watchdog already dead.
 *
 * ## Why an eager dispatcher, and why that is the whole rig
 * Every other `:kuilt-nw` test runs on a [kotlinx.coroutines.test.StandardTestDispatcher], which
 * **defers** a `CoroutineStart.DEFAULT` body until the scheduler is pumped — long after the
 * constructor returned. That is precisely why the entire suite stayed green while shipped code was
 * broken: under a standard test dispatcher the race cannot be lost. [UnconfinedTestDispatcher]
 * reports `isDispatchNeeded == false`, so a `launch` runs its body **inline, on the constructing
 * thread, at the launch site** — the same window a real dispatcher opens on another thread, but
 * deterministic. No production dispatcher and no real threads are involved.
 *
 * The first assertion is the **rig check**: it fails if the dispatcher ever stops being eager, so a
 * future edit that swaps it for a standard dispatcher reddens here instead of silently turning this
 * test vacuous.
 *
 * ## What it covers, and what it does not
 * It covers **every** coroutine `NwSeam`'s constructor starts, not just the watchdog: any of them
 * dereferencing a not-yet-initialised field surfaces as a throwable on the handler. It does not
 * cover a field read that happens after the first suspension point (by then the constructor has
 * returned on any dispatcher), and it cannot see an ordering hazard in a *different* class — eight
 * more production sites share the shape and are safe only positionally; #2465 proposes the build
 * guard that would cover them.
 */
class NwSeamConstructionOrderTest {

    @Test
    fun constructionStartsNoCoroutineThatReadsAnUninitialisedField() = runTest {
        val escaped = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> escaped += failure }
        val eagerScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob() + handler)

        // Rig check: this test is only meaningful while the scope runs a launched body INLINE.
        var ranInline = false
        eagerScope.launch { ranInline = true }
        assertTrue(
            ranInline,
            "rig broken: the scope must run a launched body inline at the launch site, or nothing " +
                "in this test can observe a constructor/coroutine race",
        )

        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        // A positive probe keeps inboundSilenceLoop past its `Duration.ZERO` early return, so the loop
        // really reaches the watchdogWake read that #2462 was.
        NwSeam(
            selfId = PeerId("peer-0"),
            api = api,
            scope = eagerScope,
            random = Random(0),
            inboundSilenceProbe = 50.milliseconds,
        )

        eagerScope.cancel()
        assertEquals(
            emptyList(),
            escaped.map { "${it::class.simpleName}: ${it.message}" },
            "a coroutine started by NwSeam's constructor failed during construction — a field it " +
                "reads is declared after the launch that starts it (#2462)",
        )
    }
}
