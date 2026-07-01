@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import org.slf4j.LoggerFactory
import org.slf4j.Logger.ROOT_LOGGER_NAME
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Secret hygiene: the join code is the only secret, and it must never be written to any
 * logger — not even at INFO on the device console, and (separately) it must not reach the
 * durable capture buffer. This attaches a logback `ListAppender` to the root logger, installs
 * a gated tap, and asserts the code appears in no captured log line.
 */
class JoinCodeNotLoggedTest {
    private val clock = object : Clock { override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000) }

    @Test
    fun theJoinCodeIsNeverEmittedToAnyLogger() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val slf4jRoot: org.slf4j.Logger = LoggerFactory.getLogger(ROOT_LOGGER_NAME)
        val root = slf4jRoot as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = root.level
        root.level = Level.DEBUG // capture everything, including any stray debug line
        root.addAppender(appender)

        val host = try {
            val exporter = WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore())
            installLogTap(
                InMemoryLoom(), exporter, backgroundScope,
                admission = LogTapAdmission.Verify(token, clock, Random(2)),
            )
        } finally {
            root.detachAppender(appender)
            root.level = previousLevel
        }
        host.close()

        val leaked = appender.list.filter { event ->
            event.formattedMessage.contains(token.code) || event.message.orEmpty().contains(token.code)
        }
        assertTrue(leaked.isEmpty(), "join code must never be logged; leaked in: ${leaked.map { it.formattedMessage }}")
    }
}
