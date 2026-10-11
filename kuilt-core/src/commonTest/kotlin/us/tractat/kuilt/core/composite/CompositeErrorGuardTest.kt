package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Errors leave the owning coroutine unchanged by reporting or cleanup guards. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeErrorGuardTest {
    @Test
    fun attachErrorFailsReconcileWithoutReporting() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = Rig(this)
        val error = GuardError()
        var attempts = 0
        try {
            rig.desired.value += PlyId("bad") to object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam {
                    attempts++
                    throw error
                }
            }
            runCurrent()
            assertAll(
                { assertEquals(1, attempts, "the failing weave ran") },
                { rig.assertEscaped(error) },
            )
        } finally {
            rig.composite.close()
        }
    }

    @Test
    fun detachErrorPassesBothCleanupAndReconcileGuards() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = Rig(this)
        val error = GuardError()
        var closes = 0
        val ply = object : Seam by FakeSeam(PeerId("bad")) {
            override suspend fun close(reason: CloseReason) {
                closes++
                throw error
            }
        }
        try {
            rig.desired.value += PlyId("bad") to OneSeamLoom(ply)
            runCurrent()
            rig.desired.value = listOf(rig.initial)
            runCurrent()
            assertAll(
                { assertEquals(1, closes, "the detached transport was closed") },
                { rig.assertEscaped(error) },
            )
        } finally {
            rig.composite.close()
        }
    }

    @Test
    fun purgeErrorEscapesAfterAnOrdinaryAttachFailure() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = Rig(this)
        val error = GuardError()
        val ordinary = IllegalArgumentException("pump accessor failed after registration")
        val fake = FakeSeam(PeerId("bad"))
        var reads = 0
        var closes = 0
        val ply = object : Seam by fake {
            override val state: StateFlow<SeamState>
                get() {
                    reads++
                    if (reads == 2) throw ordinary
                    return fake.state
                }
            override suspend fun close(reason: CloseReason) {
                closes++
                throw error
            }
        }
        try {
            rig.desired.value += PlyId("bad") to OneSeamLoom(ply)
            runCurrent()
            assertAll(
                { assertEquals(2, reads, "seed read succeeded, pump accessor failed") },
                { assertEquals(1, closes, "the half-attached ply was purged") },
                { rig.assertEscaped(error, listOf(ordinary)) },
            )
        } finally {
            rig.composite.close()
        }
    }

    @Test
    fun salvageErrorEscapesEvenWhenReconcileWasCancelled() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = Rig(this)
        val error = GuardError()
        var parked: Continuation<Unit>? = null
        var closes = 0
        val ply = object : Seam by FakeSeam(PeerId("orphan")) {
            override suspend fun close(reason: CloseReason) {
                closes++
                throw error
            }
        }
        try {
            rig.desired.value += PlyId("orphan") to object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam {
                    suspendCoroutine<Unit> { parked = it }
                    return ply
                }
            }
            runCurrent()
            val continuation = assertNotNull(parked, "weave is parked before returning the orphan")
            rig.composite.close()
            continuation.resume(Unit)
            runCurrent()
            assertAll(
                { assertEquals(1, closes, "the orphan salvage ran") },
                { rig.assertEscaped(error) },
            )
        } finally {
            rig.composite.close()
        }
    }

    @Test
    fun observerErrorFailsInboundPumpWithoutBeingReportedAgain() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val error = GuardError()
        val rig = Rig(this, observerError = error)
        val ordinary = IllegalArgumentException("upstream failed")
        var collections = 0
        val ply = object : Seam by FakeSeam(PeerId("bad")) {
            override val incoming: Flow<Swatch> = flow {
                collections++
                throw ordinary
            }
        }
        try {
            rig.desired.value += PlyId("bad") to OneSeamLoom(ply)
            runCurrent()
            assertAll(
                { assertEquals(1, collections, "inbound flow failed") },
                { rig.assertEscaped(error, listOf(ordinary)) },
            )
        } finally {
            rig.composite.close()
        }
    }

    @Test
    fun inboundErrorFailsPumpWithoutReporting() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = Rig(this)
        val error = GuardError()
        var collections = 0
        val ply = object : Seam by FakeSeam(PeerId("bad")) {
            override val incoming: Flow<Swatch> = flow {
                collections++
                throw error
            }
        }
        try {
            rig.desired.value += PlyId("bad") to OneSeamLoom(ply)
            runCurrent()
            assertAll(
                { assertEquals(1, collections, "inbound flow failed") },
                { rig.assertEscaped(error) },
            )
        } finally {
            rig.composite.close()
        }
    }

    @Test
    fun closeErrorEscapesToTheCallerWithoutReporting() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = Rig(this)
        val error = GuardError()
        var closes = 0
        val ply = object : Seam by FakeSeam(PeerId("bad")) {
            override suspend fun close(reason: CloseReason) {
                closes++
                throw error
            }
        }
        rig.desired.value += PlyId("bad") to OneSeamLoom(ply)
        runCurrent()
        val thrown = assertFailsWith<GuardError> { rig.composite.close() }
        assertAll(
            { assertSame(error, thrown) },
            { assertEquals(1, closes, "the close rig fired") },
            { assertTrue(rig.reported.isEmpty(), "Error must not reach onPlyFailure") },
        )
    }

    private class GuardError : Error("fatal consumer failure")

    private class OneSeamLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam
    }

    private class Rig(test: TestScope, observerError: Error? = null) {
        val reported = mutableListOf<Throwable>()
        private val escaped = mutableListOf<Pair<Job?, Throwable>>()
        private val initialSeam = FakeSeam(PeerId("initial"))
        val initial: Pair<PlyId, Loom> = PlyId("initial") to OneSeamLoom(initialSeam)
        val desired = MutableStateFlow(listOf(initial))
        val composite = CompositeSeam(
            initial = listOf(InitialPly(initial.first, initialSeam, emptySet())),
            rendezvous = Rendezvous.New(Pattern("host")),
            desired = desired,
            dispatcher = StandardTestDispatcher(test.testScheduler) +
                CoroutineExceptionHandler { context, failure -> escaped += context[Job] to failure },
            onPlyFailure = {
                reported += it.cause
                if (observerError != null) throw observerError
            },
        )

        fun assertEscaped(error: Error, expectedReports: List<Throwable> = emptyList()) {
            assertAll(
                { assertEquals(expectedReports, reported, "only ordinary failures reach the callback") },
                { assertSame(error, escaped.singleOrNull()?.second, "the Error itself escapes the owner") },
                { assertEquals(true, escaped.singleOrNull()?.first?.isCancelled, "the owning job failed") },
            )
        }
    }
}
