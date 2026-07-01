@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

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
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The gated tap end-to-end over an in-memory [InMemoryLoom] pair: a puller presenting the
 * valid code pulls the device buffer; a wrong code is never admitted, so the pull never
 * converges; and the default `Open` admission reproduces the ungated behaviour.
 */
class GatedTapEndToEndTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock { override fun now(): Instant = t0 }
    private val config = LogTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    private var host: LogTapHost? = null
    private var client: LogTapClient? = null

    @AfterTest
    fun tearDown() {
        client?.close()
        host?.close()
    }

    private suspend fun exporterWith(n: Int): WarpLogRecordExporter =
        WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore()).also { e ->
            (1..n).forEach { i -> e.export(LogRecord(recordId = ByteString(ByteArray(8) { i.toByte() }), body = "log $i")) }
        }

    @Test
    fun validCodePullsTheBuffer() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installLogTap(loom, exporterWith(3), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val c = LogTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config, LogTapAdmission.Present(token.code))
            .also { client = it }

        val pulled = c.pull()

        assertEquals(listOf("log 1", "log 2", "log 3"), pulled.map { it.body })
    }

    @Test
    fun wrongCodeNeverConvergesThePull() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installLogTap(loom, exporterWith(3), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val c = LogTapClient(loom.join(InMemoryTag("attacker")), backgroundScope, config, LogTapAdmission.Present("WRONGGGG"))
            .also { client = it }

        // The host never admits the attacker, so it never sends its buffer; pull() times out.
        assertFailsWith<TimeoutCancellationException> { c.pull() }
    }

    @Test
    fun roleInvertedDeviceJoinsAndLaptopPulls() = runTest(UnconfinedTestDispatcher()) {
        // The iOS topology: the device (offering side) JOINS a rendezvous the laptop hosts,
        // because it cannot host itself. Logs still flow device -> laptop via the symmetric
        // replicator. The device is the verifier (holds the token); the laptop is the prover.
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        val pattern = us.tractat.kuilt.core.Pattern("inverted")
        // Laptop hosts the rendezvous AND is the puller.
        val laptop = LogTapClient(loom.host(pattern), backgroundScope, config, LogTapAdmission.Present(token.code))
            .also { client = it }
        // Device joins and offers its buffer.
        host = installLogTapJoining(
            loom, exporterWith(4), backgroundScope, InMemoryTag("inverted"), config,
            LogTapAdmission.Verify(token, clock, Random(9)),
        )

        assertEquals(listOf("log 1", "log 2", "log 3", "log 4"), laptop.pull().map { it.body })
    }

    @Test
    fun openAdmissionMatchesUngatedBehaviour() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        // Explicit Open on both ends == the shipped default path.
        host = installLogTap(loom, exporterWith(2), backgroundScope, config, LogTapAdmission.Open)
        val c = LogTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config, LogTapAdmission.Open)
            .also { client = it }

        assertEquals(listOf("log 1", "log 2"), c.pull().map { it.body })
    }
}
