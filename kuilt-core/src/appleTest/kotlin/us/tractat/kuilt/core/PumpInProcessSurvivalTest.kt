@file:Suppress("ForbiddenImport") // deliberate real-threading harness: see the class KDoc — a test dispatcher cannot observe a process abort.

package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: deliberate real-threading harness: see the class KDoc — a test dispatcher cannot observe a process abort.
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A [pumpIn] pump whose **flow** fails must not terminate the process (#1803 / #1788 item 2).
 *
 * ### Why this test is native, bare, and in `appleTest` rather than `commonTest`
 * On Kotlin/Native an unhandled coroutine exception does not merely kill the coroutine — it aborts the
 * process: `handleUncaughtCoroutineException` → the runtime default → `Uncaught Kotlin exception` →
 * abort. A [SupervisorJob] is not protection, it is the mechanism, because suppressing *parent*
 * propagation is exactly what routes the throw to the global handler. kuilt installs no
 * `setUnhandledExceptionHook` anywhere in its non-test sources and the test runner installs none either,
 * so what a shipped iOS app gets is that bare default (measured on #1788).
 *
 * **`runTest` cannot pin this and never could.** kotlinx-coroutines-test collects an unhandled throw and
 * reports it as a test failure even from a detached, non-child scope — so a `runTest` regression test for
 * this passes whether the abort is fixed or not. Hence a **bare `@Test` with no `runTest`**, so a
 * regression reads as `Test running process exited unexpectedly` rather than a green build.
 *
 * ### What it adds over `CompositeMalformedFrameProcessSurvivalTest`
 * That one pins the *body* half at one call site — a peer's malformed frame, dropped by the guard inside
 * the collector. This pins the **upstream** half, at the helper every such pump now runs through: a throw
 * the flow itself raises never enters that body guard at all, and so travelled the abort route untouched
 * until `pumpIn` grew its `.catch`. One test here covers all six of `CompositeSeam`'s pumps and every
 * future caller, rather than one call site.
 *
 * `appleTest` compiles into `macosArm64Test`, `iosSimulatorArm64Test` and `iosArm64Test`, and both Apple
 * lanes already invoke `macosArm64Test iosSimulatorArm64Test`. Those run on a Mac only — `ci.yml`'s
 * `build-native` job is a Linux host where the Apple test tasks are disabled — so this runs locally or in
 * the `apple-nightly` workflow (#933). The deterministic half is wired into `ci-required` by `PumpInTest`
 * and `CompositePlyPumpUpstreamTest`, so removing the guard still turns a PR red; this is what makes the
 * *process-death* dimension legible when someone does run it.
 */
class PumpInProcessSurvivalTest {

    @Test
    fun aFailingUpstreamFlowDoesNotTerminateTheProcess() {
        // A pump on REAL threads under a SupervisorJob — the production shape, and the shape measured to
        // abort. Nothing here is a test dispatcher: under one, the throw is collected instead.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reported = CompletableDeferred<Pair<PumpFailure, Throwable>>()

        val failure = runBlocking {
            println("$MARKER-BEGIN launching a pump whose flow will fail")
            flow {
                emit(1)
                error(BOOM)
            }.pumpIn(
                scope,
                onFailure = { half, thrown -> reported.complete(half to thrown) },
                name = "process-survival-probe",
            ) { /* no-op */ }

            // Getting a value out of here at all IS the assertion. Without the upstream guard the process
            // is gone before this line: the runner prints "Test running process exited unexpectedly" with
            // an "Uncaught Kotlin exception: kotlin.IllegalStateException" and never reaches the marker
            // below. A timeout would be a *different* failure (the throw was swallowed unreported), so the
            // window is generous — a liveness bound, not a performance assertion.
            val seen = withTimeout(REPORT_TIMEOUT) { reported.await() }
            scope.cancel()
            seen
        }

        println("$MARKER-SURVIVED $failure")
        assertAll(
            { assertEquals(PumpFailure.UPSTREAM, failure.first, "the flow failed, not an item") },
            { assertIs<IllegalStateException>(failure.second) },
        )
    }

    private companion object {
        /** Printed either side of the pump, so a regression's abort is legible in the runner's output. */
        const val MARKER = "PUMP-IN-SURVIVAL"
        const val BOOM = "the flow itself gave up"
        val REPORT_TIMEOUT = 30.seconds
    }
}
