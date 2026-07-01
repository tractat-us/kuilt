package us.tractat.kuilt.otel.tap

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorServerLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The token-gated tap over a **real loopback WebSocket** (a Ktor `testApplication` server —
 * the same shape a LAN puller uses), proving the admission handshake survives a real
 * asynchronous round-trip, not just the in-memory fabric:
 *
 * - a puller presenting the valid code admits and reconstructs the device's log sequence;
 * - a puller presenting a wrong code is never admitted, so its `pull()` times out.
 *
 * The token uses a fixed [Clock] and seeded [Random] so the run is deterministic even
 * though the transport itself runs under real time.
 */
class GatedLoopbackWebSocketTest {

    private val path = "/kuilt/log-tap"
    private val fixedClock = object : Clock { override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000) }

    private fun record(i: Int): LogRecord =
        LogRecord(recordId = ByteString(ByteArray(8) { i.toByte() }), severityText = "INFO", body = "ws log $i")

    @Test
    fun validCodeAdmitsAndReconstructsOverRealWebSocket() = testApplication {
        val exporter = WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore())
        val sent = (1..10).map { record(it) }
        sent.forEach { exporter.export(it) }

        val token = LogTapJoinToken.issue(Random(1), fixedClock, ttl = 5.minutes)
        val serverLoom = KtorServerLoom(application, path)
        val clientLoom = KtorClientLoom(createClient { install(WebSockets) })
        val advertisement = WebSocketAdvertisement(
            url = "ws://localhost$path",
            serverPeerId = serverLoom.selfPeerId,
            displayName = "puller",
        )
        val replicatorScope = CoroutineScope(coroutineContext + SupervisorJob())

        val extracted = withTimeout(15_000) {
            coroutineScope {
                val hostDeferred = async {
                    installLogTap(serverLoom, exporter, replicatorScope, admission = LogTapAdmission.Verify(token, fixedClock, Random(2)))
                }
                val clientSeam = clientLoom.join(advertisement)
                val host = hostDeferred.await()
                val client = LogTapClient(clientSeam, replicatorScope, admission = LogTapAdmission.Present(token.code))
                try {
                    client.pull()
                } finally {
                    client.close()
                    host.close()
                }
            }
        }
        replicatorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        assertEquals(sent.map { it.recordId }, extracted.map { it.recordId }, "order preserved")
        assertEquals(sent.size, extracted.size, "no duplicates")
        assertEquals(sent.map { it.body }, extracted.map { it.body })
    }

    @Test
    fun wrongCodeIsRefusedOverRealWebSocket() = testApplication {
        val exporter = WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore())
        (1..5).forEach { exporter.export(record(it)) }

        val token = LogTapJoinToken.issue(Random(1), fixedClock, ttl = 5.minutes)
        val serverLoom = KtorServerLoom(application, path)
        val clientLoom = KtorClientLoom(createClient { install(WebSockets) })
        val advertisement = WebSocketAdvertisement(
            url = "ws://localhost$path",
            serverPeerId = serverLoom.selfPeerId,
            displayName = "attacker",
        )
        val replicatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        // Short pull timeout so the refusal is observed quickly under real time.
        val config = LogTapConfig(pullTimeout = 3.seconds)

        withTimeout(15_000) {
            coroutineScope {
                val hostDeferred = async {
                    installLogTap(serverLoom, exporter, replicatorScope, config, LogTapAdmission.Verify(token, fixedClock, Random(2)))
                }
                val clientSeam = clientLoom.join(advertisement)
                val host = hostDeferred.await()
                val client = LogTapClient(clientSeam, replicatorScope, config, LogTapAdmission.Present("WRONGGGG"))
                try {
                    assertFailsWith<TimeoutCancellationException> { client.pull() }
                } finally {
                    client.close()
                    host.close()
                }
            }
        }
        replicatorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
