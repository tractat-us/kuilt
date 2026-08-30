package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Regression for #1659 — attributes must be bound to a **scope**, not to the process.
 *
 * [CaptureConfig.attributeMapper] is installed once on the whole process's capture
 * edge. A runtime holding **concurrent sessions** therefore has no way to stamp a
 * record with the session that emitted it: the mapper folds in whichever session is
 * currently armed, so a line emitted by the mesh session while a server game is
 * armed is recorded as belonging to the server game. That is a mis-attribution the
 * consumer cannot detect or repair downstream — a filter selecting on `session.id`
 * hands back another session's lines.
 *
 * [withLogContext] binds the attributes to the calling scope instead, and the
 * capture edge merges them per-emitter. The tests here are all **cross-session** on
 * purpose: a single-scope test ("do the attributes show up at all?") passes against
 * the process-global mapper too, i.e. against the bug.
 *
 * The strongest form of the property — two scopes **interleaved on one thread** —
 * lives in `jvmTest`'s `LogContextConcurrentScopesTest`, because it holds only where
 * a coroutine primitive can mirror the context across dispatches. See
 * [withLogContext]'s platform note.
 */
class LogContextPerScopeTest {
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000)
    }

    private fun exporter() = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())

    /**
     * Two sessions, one after the other, each stamping its own id.
     *
     * Discriminating against the bug: under a process-global mapper both records
     * carry whichever session was armed, so the two assertions cannot both hold.
     */
    @Test
    fun distinctScopesEachStampTheirOwnAttributes() = runTest {
        val exporter = exporter()
        val installation = installLogCapture(exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope)
        try {
            val log = KotlinLogging.logger("com.example.Session")
            withLogContext(mapOf(SESSION_ID to "server-game")) {
                log.info { "server line" }
            }
            withLogContext(mapOf(SESSION_ID to "mesh")) {
                log.info { "mesh line" }
            }
            testScheduler.runCurrent()

            val sessionOf = exporter.snapshot().toList().associate { it.body to it.attributes[SESSION_ID] }
            assertAll(
                { assertEquals("server-game", sessionOf["server line"]) },
                { assertEquals("mesh", sessionOf["mesh line"], "each scope stamps its own session, not the last-armed one") },
            )
        } finally {
            installation.close()
        }
    }

    /**
     * The scope's attributes beat the process-global mapper on a key collision —
     * "narrower scope wins", the same rule that makes an inner [withLogContext] beat
     * an outer one.
     *
     * This is the reported defect stated directly: the mapper is armed to the wrong
     * session, and entering the emitting session's scope must correct it rather than
     * be silently overridden by it.
     */
    @Test
    fun theScopeContextWinsOverTheProcessGlobalMapper() = runTest {
        val exporter = exporter()
        val config = CaptureConfig(
            attributeMapper = { event ->
                mapOf(LOGGER_NAME_ATTRIBUTE to event.loggerName, SESSION_ID to "globally-armed")
            },
        )
        val installation = installLogCapture(exporter, config, fixedClock, Random(0), backgroundScope)
        try {
            withLogContext(mapOf(SESSION_ID to "the-emitting-session")) {
                KotlinLogging.logger("com.example.Session").info { "corrected line" }
            }
            testScheduler.runCurrent()

            val record = exporter.snapshot().toList().single()
            assertAll(
                { assertEquals("the-emitting-session", record.attributes[SESSION_ID]) },
                { assertEquals("com.example.Session", record.attributes[LOGGER_NAME_ATTRIBUTE], "the mapper's other keys survive") },
            )
        } finally {
            installation.close()
        }
    }

    /**
     * Nesting merges, and the inner scope wins a key collision — while a key only
     * the outer scope set is still carried into the inner one. Leaving the inner
     * scope restores the outer binding rather than clearing it.
     */
    @Test
    fun nestedScopesMergeWithTheInnerWinningOnACollision() = runTest {
        val exporter = exporter()
        val installation = installLogCapture(exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope)
        try {
            val log = KotlinLogging.logger("com.example.Session")
            withLogContext(mapOf(SESSION_ID to "outer", DEVICE_ROLE to "host")) {
                withLogContext(mapOf(SESSION_ID to "inner")) {
                    log.info { "nested line" }
                }
                log.info { "outer line" }
            }
            testScheduler.runCurrent()

            val byBody = exporter.snapshot().toList().associateBy { it.body }
            val nested = byBody.getValue("nested line").attributes
            val outer = byBody.getValue("outer line").attributes
            assertAll(
                { assertEquals("inner", nested[SESSION_ID], "the inner scope wins the collision") },
                { assertEquals("host", nested[DEVICE_ROLE], "a key only the outer scope set is inherited") },
                { assertEquals("outer", outer[SESSION_ID], "leaving the inner scope restores the outer binding") },
                { assertEquals("host", outer[DEVICE_ROLE]) },
            )
        } finally {
            installation.close()
        }
    }

    /**
     * A line emitted outside any scope is captured exactly as before — the mapper's
     * attributes and nothing else. Keeps the merge from being satisfied by an
     * implementation that stamps something unconditionally, and pins that the
     * feature costs an un-scoped consumer nothing.
     */
    @Test
    fun aLineOutsideAnyScopeCarriesOnlyTheMapperAttributes() = runTest {
        val exporter = exporter()
        val installation = installLogCapture(exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope)
        try {
            withLogContext(mapOf(SESSION_ID to "a-session")) {
                KotlinLogging.logger("com.example.Session").info { "inside" }
            }
            KotlinLogging.logger("com.example.Session").info { "outside" }
            testScheduler.runCurrent()

            val byBody = exporter.snapshot().toList().associateBy { it.body }
            assertAll(
                { assertEquals("a-session", byBody.getValue("inside").attributes[SESSION_ID]) },
                { assertNull(byBody.getValue("outside").attributes[SESSION_ID], "the scope must not outlive its block") },
                {
                    assertEquals(
                        "com.example.Session",
                        byBody.getValue("outside").attributes[LOGGER_NAME_ATTRIBUTE],
                        "an un-scoped line is captured exactly as before",
                    )
                },
            )
        } finally {
            installation.close()
        }
    }

    private companion object {
        private const val SESSION_ID = "session.id"
        private const val DEVICE_ROLE = "device.role"
    }
}
