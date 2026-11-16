package us.tractat.kuilt.otel.otlp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.toHexString
import org.slf4j.LoggerFactory
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The WARN ruling 1 promises when a record bigger than the cap is refused with a `413` and recorded
 * as sent, and its absence when a lone record inside the cap is refused and the send fails instead
 * (#3286). On the JVM because that is where a `kotlin-logging` backend can be tapped; the decision
 * to log is taken in common code.
 */
class OtlpHttpEdgeLone413WarnTest {

    private val cap = 2048
    private val id = ByteString(ByteArray(8) { 7 })

    @Test
    fun aLoneOversizedRecordRefusedWith413IsRecordedWithOneWarn() = runTest {
        val record = LogRecord(recordId = id, body = "大".repeat(cap))
        val (posts, outcome, warns) = sendRefused(record)
        val recorded = outcome.getOrNull()
        assertAll(
            { assertEquals(1, posts.size, "precondition: one POST, the record alone") },
            { assertTrue(posts.single() > cap, "precondition: the record is over the cap, ${posts.single()} bytes") },
            { assertEquals(setOf(id), recorded, "the record is recorded as sent: ${outcome.exceptionOrNull()}") },
            { assertEquals(1, warns.size, "one WARN: $warns") },
            { assertTrue(id.toHexString() in warns.single(), "the WARN names the record: ${warns.single()}") },
            { assertTrue("${posts.single()} bytes" in warns.single(), "and its size: ${warns.single()}") },
        )
    }

    @Test
    fun aLoneRecordInsideTheCapRefusedWith413FailsWithoutAWarn() = runTest {
        val record = LogRecord(recordId = id, body = "small")
        val (posts, outcome, warns) = sendRefused(record)
        assertAll(
            { assertEquals(1, posts.size, "precondition: one POST, the fast path") },
            { assertTrue(posts.single() <= cap, "precondition: the record is inside the cap") },
            { assertTrue(outcome.isFailure, "the send fails rather than recording the record") },
            { assertEquals(emptyList(), warns, "and logs no WARN") },
        )
    }

    private data class Refused(val postBytes: List<Int>, val recorded: Result<Set<ByteString>>, val warns: List<String>)

    /** Sends [record] to a collector that answers every POST `413`; returns what the edge then holds. */
    private suspend fun TestScope.sendRefused(record: LogRecord): Refused {
        val postBytes = mutableListOf<Int>()
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { req ->
                    postBytes += req.body.contentLength?.toInt() ?: -1
                    respond("{}", HttpStatusCode.PayloadTooLarge)
                }
            },
        )
        val edge = OtlpHttpEdge(HttpClient(engine), "https://c.example:4318", InMemoryDurableStore(), maxRequestBytes = cap)
        val (outcome, warns) = capturingWarnsOf("us.tractat.kuilt.otel.otlp.OtlpHttpEdge") {
            runCatchingCancellable { edge.sendLogs(setOf(record)) }.map { edge.logDigest().recordIds }
        }
        return Refused(postBytes.toList(), outcome, warns)
    }

    /** Runs [block], returning its result and the WARN messages [loggerName] emitted meanwhile. */
    // A local copy of :kuilt-otel's jvmTest `capturingLogsOf`, which another module's tests cannot reach.
    private suspend fun <T> capturingWarnsOf(loggerName: String, block: suspend () -> T): Pair<T, List<String>> {
        val slf4j: org.slf4j.Logger = LoggerFactory.getLogger(loggerName)
        val logger = slf4j as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.TRACE
        logger.addAppender(appender)
        try {
            val result = block()
            return result to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = previousLevel
        }
    }
}
