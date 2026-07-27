package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Regression for #1630 — the sibling of #1034, for **attributes** rather than trace.
 *
 * [CaptureConfig.attributeMapper] must be applied at the **synchronous `log()`
 * edge** on the caller, not on the drain coroutine. A consumer that maps ambient
 * state (the id of the game/session currently in progress, a request id, a screen
 * name) into attributes otherwise gets that state as of *drain* time: any record
 * still queued when the ambient state changes is stamped with the **new** value,
 * silently mis-attributed with no consumer-side way to detect or repair it — the
 * emit-time value is gone by the time the mapper runs.
 *
 * The test proves resolution timing directly: a mapper reading a mutable ambient
 * holder is flipped from `A` to `B` **after** the synchronous log edge runs but
 * **before** the drain coroutine advances. The record must carry `A`.
 */
class AttributesResolveAtEdgeTest {
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000)
    }

    @Test
    fun attributesResolveAtLogEdgeNotAtDrain() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())

        // The ambient state a consumer folds into every record — here the game
        // currently in progress. It flips while records are still queued.
        var ambientGameId = "game-A"
        val config = CaptureConfig(
            attributeMapper = { event ->
                mapOf(LOGGER_NAME_ATTRIBUTE to event.loggerName, GAME_ID_ATTRIBUTE to ambientGameId)
            },
        )

        val installation = installLogCapture(exporter, config, fixedClock, Random(0), backgroundScope)
        try {
            // The appender's log() runs synchronously here, on this caller — the
            // mapper must see the ambient state NOW (ambientGameId == "game-A").
            KotlinLogging.logger("com.example.Edge").info { "last line of game A" }

            // Flip the ambient state AFTER the edge, BEFORE the drain. A mapper that
            // (buggily) runs on the drain would now stamp "game-B" onto a record that
            // was emitted during game A.
            ambientGameId = "game-B"

            testScheduler.runCurrent()

            val record = exporter.snapshot().toList().single()
            assertEquals(
                "game-A",
                record.attributes[GAME_ID_ATTRIBUTE],
                "attributes must be resolved at the log edge, not on the drain",
            )
        } finally {
            installation.close()
        }
    }

    /**
     * The mapper now runs on the application's logging thread, so it must not be
     * paid for a line that produces no record: an event below
     * [CaptureConfig.minLevel] is dropped before the mapper is applied.
     */
    @Test
    fun mapperIsNotAppliedToAnEventBelowMinLevel() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        var mapperCalls = 0
        val config = CaptureConfig(
            minLevel = LogLevel.INFO,
            attributeMapper = { event ->
                mapperCalls++
                defaultAttributeMapper(event)
            },
        )

        val installation = installLogCapture(exporter, config, fixedClock, Random(0), backgroundScope)
        try {
            val logger = KotlinLogging.logger("com.example.Edge")
            logger.debug { "below the floor" }
            testScheduler.runCurrent()
            assertEquals(0, mapperCalls, "a below-minLevel event must not pay the mapper at the edge")

            logger.info { "at the floor" }
            testScheduler.runCurrent()
            assertAll(
                { assertEquals(1, mapperCalls) },
                { assertEquals(1, exporter.snapshot().toList().size) },
            )
        } finally {
            installation.close()
        }
    }

    /**
     * Resolving on the caller puts consumer code on the application's logging path,
     * so a throwing mapper must not escape into the `log()` call. It drops that one
     * record — the same outcome as when the mapper still ran on the drain behind the
     * appender's best-effort guard.
     */
    @Test
    fun throwingMapperDropsTheRecordAndNeverEscapesIntoTheLogCall() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val config = CaptureConfig(attributeMapper = { error("mapper blew up") })

        val installation = installLogCapture(exporter, config, fixedClock, Random(0), backgroundScope)
        try {
            // Must not throw: the application's logging call is not a place a
            // capture-side failure may surface.
            KotlinLogging.logger("com.example.Edge").info { "mapper throws on this one" }
            testScheduler.runCurrent()
            assertTrue(exporter.snapshot().toList().isEmpty(), "a throwing mapper drops the record")
        } finally {
            installation.close()
        }
    }

    private companion object {
        private const val GAME_ID_ATTRIBUTE = "game.id"
    }
}
