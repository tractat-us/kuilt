@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertTrue
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The gated metric tap end-to-end over an in-memory [InMemoryLoom] pair — the metric-path
 * twin of [GatedTapEndToEndTest]: a puller presenting the valid code pulls the device's
 * converged metric buffer; a wrong code is never admitted, so the pull never converges; and
 * the default `Open` admission reproduces the ungated behaviour.
 */
class GatedMetricTapEndToEndTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock { override fun now(): Instant = t0 }
    private val config = MetricTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    private var host: MetricTapHost? = null
    private var client: MetricTapClient? = null

    @AfterTest
    fun tearDown() {
        client?.close()
        host?.close()
    }

    private suspend fun exporterWith(frames: Long): WarpMetricExporter =
        WarpMetricExporter(replica = ReplicaId("device"), store = InMemoryDurableStore()).also { e ->
            e.incrementSum(MetricKey("frames", MetricKind.SUM), by = frames)
        }

    @Test
    fun validCodePullsTheBuffer() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installMetricTap(loom, exporterWith(7), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val c = MetricTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config, LogTapAdmission.Present(token.code, Random(11)))
            .also { client = it }

        val snap = c.pull()

        assertEquals(7L, snap.sums.getValue(MetricKey("frames", MetricKind.SUM)))
    }

    @Test
    fun wrongCodeNeverConvergesThePull() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installMetricTap(loom, exporterWith(7), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val c = MetricTapClient(loom.join(InMemoryTag("attacker")), backgroundScope, config, LogTapAdmission.Present("WRONGGGG", Random(11)))
            .also { client = it }

        // The host never admits the attacker, so it never sends its buffer; pull() times out.
        assertFailsWith<TimeoutCancellationException> { c.pull() }
    }

    /** The metric client shares the log client's gate. The control is [validCodePullsTheBuffer]. */
    @Test
    fun aPullerHoldingACodeTakesNoMetricsFromAPeerThatNeverProvedIt() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        var received = 0
        val raw = loom.host(us.tractat.kuilt.core.Pattern("forged"))
        val counted = object : Seam by raw {
            override val incoming: Flow<Swatch> = raw.incoming.onEach { received++ }
        }
        val c = MetricTapClient(counted, backgroundScope, config, LogTapAdmission.Present("CODE1234", Random(11)))
            .also { client = it }
        // `pull()` parks until an admitted peer exists, so it times out whether or not forged metrics
        // arrived. What the replicator holds is what `tail()` shows.
        val seen = mutableListOf<MetricSnapshot>()
        backgroundScope.launch { c.tail().collect { seen += it } }
        // The device joins the puller's session after the puller is listening, so its first push is not
        // lost to the gate's construction window.
        host = installMetricTapJoining(loom, exporterWith(7), backgroundScope, InMemoryTag("forged"), config, LogTapAdmission.Open)
        delay(config.syncInterval * 3) // virtual time: several host syncs

        assertAll(
            { assertTrue(received > 0, "rig: the stranger's frames must have reached the puller's gate") },
            { assertEquals(emptyList(), seen.filter { it.sums.isNotEmpty() }, "the puller must hold none of the stranger's metrics") },
        )
    }

    @Test
    fun openAdmissionMatchesUngatedBehaviour() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        // Explicit Open on both ends == the shipped default path.
        host = installMetricTap(loom, exporterWith(3), backgroundScope, config, LogTapAdmission.Open)
        val c = MetricTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config, LogTapAdmission.Open)
            .also { client = it }

        assertEquals(3L, c.pull().sums.getValue(MetricKey("frames", MetricKind.SUM)))
    }
}
