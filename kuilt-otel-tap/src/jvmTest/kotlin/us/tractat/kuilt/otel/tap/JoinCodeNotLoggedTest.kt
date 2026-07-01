@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.quilter.QuilterConfig
import org.slf4j.LoggerFactory
import org.slf4j.Logger.ROOT_LOGGER_NAME
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Secret hygiene: the join code is the only secret, and it must never be written to any
 * logger — not even at DEBUG on the device console.
 *
 * Rather than exercise only the host's `announce` breadcrumb, this drives a **full
 * admission handshake** end-to-end while a logback `ListAppender` captures everything at
 * DEBUG: a valid puller (challenge → proof → verify-**admit**) and a wrong-code puller
 * (challenge → proof → verify-**reject**). Every code-bearing path — `announceGatedTap`,
 * `maybeChallenge`, `respondToChallenge`, `verify`, and the reject branch — thus runs
 * under the appender, so a code accidentally logged anywhere along the way is caught.
 */
class JoinCodeNotLoggedTest {
    private val clock = object : Clock { override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000) }
    private val config = LogTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    private suspend fun exporterWith(n: Int): WarpLogRecordExporter =
        WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore()).also { e ->
            (1..n).forEach { i -> e.export(LogRecord(recordId = ByteString(ByteArray(8) { i.toByte() }), body = "log $i")) }
        }

    @Test
    fun theJoinCodeIsNeverEmittedToAnyLoggerAcrossAFullHandshake() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val slf4jRoot: org.slf4j.Logger = LoggerFactory.getLogger(ROOT_LOGGER_NAME)
        val root = slf4jRoot as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = root.level
        root.level = Level.DEBUG // capture everything, including any stray debug line
        root.addAppender(appender)

        try {
            // Admit path on its own session: full challenge → proof → verify(admit) → replicate.
            // (A separate session from the reject path so an admitted puller cannot relay the
            // buffer to the wrong-code puller — a multi-peer artifact unrelated to secret hygiene.)
            val admitLoom = InMemoryLoom()
            val admitHost = installLogTap(
                admitLoom, exporterWith(2), backgroundScope, config,
                admission = LogTapAdmission.Verify(token, clock, Random(2)),
            )
            val good = LogTapClient(
                admitLoom.join(InMemoryTag("good")), backgroundScope, config, LogTapAdmission.Present(token.code),
            )
            assertEquals(listOf("log 1", "log 2"), good.pull().map { it.body }, "valid code pulls the buffer")
            good.close()
            admitHost.close()

            // Reject path on its own session: full challenge → proof → verify(reject); never converges.
            val rejectLoom = InMemoryLoom()
            val rejectHost = installLogTap(
                rejectLoom, exporterWith(2), backgroundScope, config,
                admission = LogTapAdmission.Verify(token, clock, Random(3)),
            )
            val bad = LogTapClient(
                rejectLoom.join(InMemoryTag("bad")), backgroundScope, config, LogTapAdmission.Present("WRONGGGG"),
            )
            assertFailsWith<TimeoutCancellationException> { bad.pull() }
            bad.close()
            rejectHost.close()
        } finally {
            root.detachAppender(appender)
            root.level = previousLevel
        }

        val leaked = appender.list.filter { event ->
            event.formattedMessage.contains(token.code) || event.message.orEmpty().contains(token.code)
        }
        assertTrue(leaked.isEmpty(), "join code must never be logged; leaked in: ${leaked.map { it.formattedMessage }}")
    }
}
