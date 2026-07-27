package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private companion object {
        private const val GAME_ID_ATTRIBUTE = "game.id"
    }
}
