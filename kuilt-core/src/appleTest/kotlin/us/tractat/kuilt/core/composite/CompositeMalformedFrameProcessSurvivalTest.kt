@file:Suppress("ForbiddenImport") // deliberate real-threading harness: see the class KDoc — a real dispatcher is the whole point, because a test dispatcher cannot observe a process abort.

package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: deliberate real-threading harness: see the class KDoc — a real dispatcher is the whole point, because a test dispatcher cannot observe a process abort.
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A malformed frame from a peer must not **terminate the process** (#1788).
 *
 * ### Why this test is native, bare, and in `appleTest` rather than in `commonTest`
 * On Kotlin/Native an unhandled coroutine exception does not merely kill the coroutine — it aborts the
 * process. A throw escaping a collector launched in a scope whose job is a
 * [kotlinx.coroutines.SupervisorJob] reaches
 * `handleUncaughtCoroutineException` → the runtime default → `Uncaught Kotlin exception` → abort.
 * `SupervisorJob` is not protection here, it is the mechanism: suppressing *parent* propagation is exactly
 * what routes the throw to the global handler. kuilt installs no `setUnhandledExceptionHook` anywhere in
 * its non-test sources, and the test runner installs none either, so what a shipped iOS app gets is that
 * bare default. `CompositeSeam.attachPly`'s inbound pump has precisely that shape, and its input is bytes
 * a peer chose — so before the fix **one 2-byte frame from any peer in the session crashed the app**.
 *
 * **`runTest` cannot pin this and never could.** kotlinx-coroutines-test collects an unhandled throw and
 * reports it as a test failure even from a detached, non-child scope — so a `runTest` regression test for
 * this passes whether the crash is fixed or not, which is exactly why the class kept surfacing as
 * thousands of uncaught exceptions in `system-err` on *passing* runs (#1784) rather than as crashes. Hence
 * a **bare `@Test` with no `runTest`**, so a regression reads as `Test running process exited
 * unexpectedly` instead of a green build.
 *
 * ### It lives in `appleTest`, not `macosArm64Test`, because the bug is an **iOS** crash
 * The shipped consumer is an iPhone app, so the abort has to be pinned on the target that models it.
 * `appleTest` is compiled into `macosArm64Test`, `iosSimulatorArm64Test` and `iosArm64Test`, and both
 * Apple lanes already invoke `macosArm64Test iosSimulatorArm64Test` — so the simulator runs this for free,
 * where a `macosArm64Test`-only file would have left the iOS lane with nothing to run. (The
 * "first `appleMain` source breaks the Dokka module-doc check" trap is a *main* source-set problem; a test
 * source set is not in Dokka's graph.)
 *
 * The complementary half — that the frame is *dropped and reported* and the pump keeps delivering — is
 * pinned deterministically on every target by `CompositeInboundPumpTest`, and **that** is the half wired
 * into `ci-required`: `ci.yml`'s `build-native` job runs on a Linux host, where the Apple test tasks are
 * disabled, so this test runs on a Mac only — locally, or out of band in the `apple-nightly` workflow
 * (`gh workflow run apple-nightly.yml`). See #933 for why the split is deliberate. Removing the guard
 * therefore still turns a PR red, via the sibling test; this one is what makes the *process-death*
 * dimension legible when someone does run it.
 */
class CompositeMalformedFrameProcessSurvivalTest {

    @Test
    fun aMalformedFrameFromAPeerDoesNotTerminateTheProcess() {
        // A real ply seam whose frames we push by hand, and a composite whose pumps run on REAL threads —
        // the production shape. Nothing here is a test dispatcher: under one, the throw is collected.
        val ply = FakeSeam(selfId = PeerId("ply-$PLY_NAME"))
        val raised = CompletableDeferred<PlyReconcileException>()

        val failure = runBlocking {
            val composite = CompositeLoom(
                plies = listOf(PLY to OneSeamLoom(ply) as Loom),
                dispatcher = Dispatchers.Default,
                onPlyFailure = { raised.complete(it) },
            ).host(Pattern("survival"))

            // THE crash frame: a valid Data tag plus one byte. `readInt(bytes, 1)` reaches for bytes[1..4],
            // three of which do not exist — and it used to run BEFORE the `require` written to reject this.
            val malformed = PlyFrame.encode(PlyFrame.Data(REMOTE_COMPOSITE, 0L, byteArrayOf())).copyOf(2)
            println("$MARKER-BEGIN delivering a ${malformed.size}-byte frame")
            ply.deliver(REMOTE_TRANSPORT, malformed)

            // Getting a value out of here at all IS the assertion. Pre-fix the process is gone before this
            // line: the runner prints "Test running process exited unexpectedly" with an
            // "Uncaught Kotlin exception: kotlin.IndexOutOfBoundsException" and never reaches the marker
            // below. A timeout would be a *different* failure (the frame was swallowed unreported), so the
            // window is generous — it is a liveness bound, not a performance assertion.
            val reported = withTimeout(REPORT_TIMEOUT) { raised.await() }
            composite.close(CloseReason.Normal)
            reported
        }

        println("$MARKER-SURVIVED $failure")
        assertAll(
            { assertEquals(PLY, failure.plyId, "the report must name the ply the frame arrived on") },
            { assertEquals(PlyReconcileException.Phase.INBOUND, failure.phase) },
            { assertIs<IllegalArgumentException>(failure.cause, "the cause must be the decoder's rejection") },
        )
    }

    /** A [Loom] weaving one given ply seam. */
    private class OneSeamLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    private companion object {
        const val PLY_NAME = "only"

        /** Printed either side of the delivery, so a regression's abort is legible in the runner's output. */
        const val MARKER = "PLY-FRAME-SURVIVAL"
        val PLY = PlyId(PLY_NAME)
        val REMOTE_TRANSPORT = PeerId("remote-transport")
        val REMOTE_COMPOSITE = PeerId("remote-composite")
        val REPORT_TIMEOUT = 30.seconds
    }
}
