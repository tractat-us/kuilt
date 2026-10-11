package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryRosterErrorGuardTest {
    @Test
    fun discoveryErrorFailsOwnerWithoutReporting() = checkError(departure = false, observer = false)

    @Test
    fun departureErrorFailsOwnerWithoutReporting() = checkError(departure = true, observer = false)

    @Test
    fun observerErrorFailsOwnerWithoutBeingReportedAgain() = checkError(departure = false, observer = true)

    // A consumer Error subtype also avoids JVM stack-trace recovery copying the standard Error.
    private class DiscoveryError : Error("fatal discovery failure")

    private fun checkError(departure: Boolean, observer: Boolean) = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val error = DiscoveryError()
        val ordinary = IllegalArgumentException("feed failed")
        val reported = mutableListOf<Throwable>()
        val escaped = mutableListOf<Pair<Job?, Throwable>>()
        var collections = 0
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { context, failure -> escaped += context[Job] to failure },
        )
        val failing: Flow<Nothing> = flow {
            collections++
            throw if (observer) ordinary else error
        }
        val source = object : PeerDiscoverySource {
            override val kind: DiscoveryKind = DiscoveryKind.Mdns
            override fun discoveries(): Flow<Tag> = if (departure) emptyFlow() else failing
            override fun departures(): Flow<String> = if (departure) failing else emptyFlow()
        }
        try {
            discoveryRoster(listOf(source), scope) { _, failure ->
                reported += failure
                if (observer) throw error
            }
            runCurrent()
            assertAll(
                { assertEquals(1, collections, "the failing feed was collected") },
                { assertEquals(if (observer) listOf(ordinary) else emptyList(), reported) },
                { assertSame(error, escaped.singleOrNull()?.second, "the Error itself escapes the fold") },
                { assertEquals(true, escaped.singleOrNull()?.first?.isCancelled, "the owning job failed") },
            )
        } finally {
            scope.cancel()
        }
    }
}
