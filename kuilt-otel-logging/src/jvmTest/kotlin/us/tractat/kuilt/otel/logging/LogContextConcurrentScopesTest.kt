package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The strongest form of #1659's property: two sessions **interleaved on one thread**
 * never contaminate each other.
 *
 * This is the shape the reported defect actually takes in the consumer's runtime —
 * concurrent sessions whose coroutines suspend and resume in and out of each other,
 * so that "the currently-armed session" is a different answer at every resumption.
 * A process-global `CaptureConfig.attributeMapper` gets it wrong for every line that
 * is not the armed session's; a scope-bound context must get it right for all of
 * them.
 *
 * **JVM/Android only, deliberately.** The guarantee rests on
 * `kotlinx.coroutines.ThreadContextElement`, which re-establishes the context on
 * every dispatch and so survives interleaving. That primitive does not exist on
 * Kotlin/Native or wasmJs (checked against coroutines 1.11.0), where
 * [withLogContext] sets the execution-local slot imperatively and therefore reaches
 * only a line logged synchronously within the block — exactly the reach
 * [withActiveTrace] documents for the same reason. `commonTest`'s
 * `LogContextPerScopeTest` holds the cross-session property that *is* universal.
 */
class LogContextConcurrentScopesTest {
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000)
    }

    /**
     * Two scopes, each logging repeatedly and yielding between lines so the test
     * dispatcher interleaves them, on one thread, under one process-global mapper.
     *
     * The assertion is per-record and total: every one of the six records must carry
     * the session of the scope that emitted it. Under the bug the two scopes'
     * records are indistinguishable.
     */
    @Test
    fun concurrentlyInterleavedScopesNeverContaminateEachOther() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope)
        try {
            val log = KotlinLogging.logger("com.example.Session")

            // Both launches share this test's StandardTestDispatcher, so `yield()`
            // hands control to the other scope: the two blocks are genuinely
            // interleaved rather than run one after the other.
            val serverGame = launch {
                withLogContext(mapOf(SESSION_ID to "server-game")) {
                    repeat(LINES_PER_SESSION) { i ->
                        log.info { "server-game line $i" }
                        yield()
                    }
                }
            }
            val mesh = launch {
                withLogContext(mapOf(SESSION_ID to "mesh")) {
                    repeat(LINES_PER_SESSION) { i ->
                        log.info { "mesh line $i" }
                        yield()
                    }
                }
            }
            serverGame.join()
            mesh.join()
            testScheduler.runCurrent()

            val records = exporter.snapshot().toList()
            // Each record names its own session in its body, so the expected stamp is
            // derivable from the record itself — no reliance on emission order.
            val misattributed = records.filter { record ->
                val emittedBy = record.body.orEmpty().substringBefore(" line")
                record.attributes[SESSION_ID] != emittedBy
            }
            assertAll(
                { assertEquals(2 * LINES_PER_SESSION, records.size, "every line was captured") },
                {
                    assertEquals(
                        emptyList(),
                        misattributed.map { "${it.body} -> ${it.attributes[SESSION_ID]}" },
                        "each interleaved scope's lines carry that scope's session",
                    )
                },
            )
        } finally {
            installation.close()
        }
    }

    private companion object {
        private const val SESSION_ID = "session.id"
        private const val LINES_PER_SESSION = 3
    }
}
