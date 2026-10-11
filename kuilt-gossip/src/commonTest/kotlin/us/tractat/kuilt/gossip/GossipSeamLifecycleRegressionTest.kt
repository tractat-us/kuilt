package us.tractat.kuilt.gossip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GossipSeamLifecycleRegressionTest {
    private class Boom : Error("Boom")

    private fun gossip(base: Seam): GossipSeam =
        GossipSeam(
            base = base,
            random = Random(0),
            clock = { Instant.fromEpochMilliseconds(0) },
            jitter = Duration.ZERO..Duration.ZERO,
        )

    private class FailingIncoming(val failure: Error) : Seam by FakeSeam() {
        var collections = 0
        override val incoming: Flow<Swatch> = flow {
            collections++
            throw failure
        }
    }

    @Test
    fun workerErrorIsReportedUnderSupervisorScope() = runTest {
        val boom = Boom()
        val base = FailingIncoming(boom)
        val seam = gossip(base)
        val reported = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> reported.add(failure) }
        val caller = backgroundScope.launch(handler) {
            supervisorScope { seam.start(this) }
        }
        runCurrent()

        assertAll(
            { assertEquals(1, base.collections, "the inbound failure must fire") },
            { assertTrue(caller.isCompleted, "failed seam workers must terminate") },
            { assertEquals(listOf<Throwable>(boom), reported, "supervisorScope must report the worker Error") },
        )
    }

    @Test
    fun workerErrorIsReportedUnderSupervisorJobWithCoroutineParent() = runTest {
        val boom = Boom()
        val base = FailingIncoming(boom)
        val seam = gossip(base)
        val reported = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> reported.add(failure) }
        val caller = backgroundScope.launch(handler) {
            val supervisor = SupervisorJob(coroutineContext[Job])
            try {
                seam.start(CoroutineScope(coroutineContext + supervisor))
                awaitCancellation()
            } finally {
                supervisor.cancel()
            }
        }
        runCurrent()

        assertAll(
            { assertEquals(1, base.collections, "the inbound failure must fire") },
            { assertTrue(caller.isActive, "the supervisor must preserve its caller") },
            { assertEquals(listOf<Throwable>(boom), reported, "parented SupervisorJob must report the worker Error") },
        )
    }

    @Test
    fun closePublishesTornBeforeIncomingCompletes() = runTest {
        val base = FakeSeam()
        val allowClose = CompletableDeferred<Unit>()
        var closeEntered = false
        val seam = gossip(object : Seam by base {
            override suspend fun close(reason: CloseReason) {
                closeEntered = true
                allowClose.await()
                base.close(reason)
            }
        })
        seam.start(backgroundScope)
        var stateAtCompletion: SeamState? = null
        backgroundScope.launch {
            seam.incoming.collect {}
            stateAtCompletion = seam.state.value
        }
        runCurrent()
        val closer = backgroundScope.launch { seam.close() }
        runCurrent()
        assertTrue(closeEntered, "the base close must be suspended before publishing Torn")
        val completionWhileBaseIsWoven = stateAtCompletion

        allowClose.complete(Unit)
        runCurrent()

        assertAll(
            { assertNull(completionWhileBaseIsWoven, "incoming completed before the base published Torn") },
            { assertEquals(SeamState.Torn(CloseReason.Normal), stateAtCompletion, "incoming completion must observe Torn") },
            { assertTrue(closer.isCompleted, "close must finish after releasing the base") },
        )
    }
}
