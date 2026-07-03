@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Apple-target proof for the Multipeer encrypted reach path (issue #1042).
 *
 * The real Mac↔iPhone transport can only be exercised on two physical Apple devices, so it is
 * out of scope here (the manual-validation note owns it). What this test *can* prove on the
 * Apple variants, without opening a real Multipeer session, is:
 *
 *  1. the fabric-agnostic token-admission decorator — the same gate the Multipeer entry point
 *     reuses unchanged — composes over an injected in-memory fabric on the Apple targets
 *     (a valid code pulls in order; a wrong code never converges),
 *  2. the gated pull re-merges without gaps or duplicates when a puller drops and a fresh one
 *     rejoins — the automatable half of the manual reconnect check,
 *  3. the metric reach path ([installMultipeerMetricTap]'s underlying [installMetricTap]) round-
 *     trips a converged metric buffer over the same injected fabric, gated exactly like the log
 *     path (a valid code pulls, a wrong code never converges), and
 *  4. the `:kuilt-multipeer` fabric links into the tap module's Apple variants: a
 *     [MultipeerPeerLinkFactory] constructs (no IO until it weaves) and is a [Loom], so it is a
 *     valid argument to [installMultipeerLogTap].
 *
 * What stays manual (owned by the validation note): real Multipeer discovery between two physical
 * Apple devices, the packet-capture proof that the wire carries only ciphertext, and the actual
 * transport-level link drop/re-establish — the in-memory fabric can model a puller rejoin but not
 * a real DTLS session tearing and healing.
 */
class MultipeerLogTapReachTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock { override fun now(): Instant = t0 }
    private val config = LogTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))
    private val metricConfig = MetricTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    private var host: LogTapHost? = null
    private var client: LogTapClient? = null
    private var metricHost: MetricTapHost? = null
    private var metricClient: MetricTapClient? = null

    @AfterTest
    fun tearDown() {
        client?.close()
        host?.close()
        metricClient?.close()
        metricHost?.close()
    }

    private fun logRecord(i: Int): LogRecord =
        LogRecord(recordId = ByteString(ByteArray(8) { i.toByte() }), body = "log $i")

    private suspend fun exporterWith(n: Int): WarpLogRecordExporter =
        WarpLogRecordExporter(replica = ReplicaId("iphone"), store = InMemoryDurableStore()).also { e ->
            (1..n).forEach { i -> e.export(logRecord(i)) }
        }

    @Test
    fun gatedTapComposesOverAnInjectedFabricOnApple() = runTest(UnconfinedTestDispatcher()) {
        // The iPhone hosts and verifies (holds the join code); the Mac joins, presents the code,
        // and pulls. Over Multipeer this is the natural topology — no role inversion.
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installLogTap(loom, exporterWith(3), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val mac = LogTapClient(loom.join(InMemoryTag("mac")), backgroundScope, config, LogTapAdmission.Present(token.code))
            .also { client = it }

        assertEquals(listOf("log 1", "log 2", "log 3"), mac.pull().map { it.body })
    }

    @Test
    fun wrongCodeNeverConvergesThePullOnApple() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installLogTap(loom, exporterWith(3), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val impostor = LogTapClient(loom.join(InMemoryTag("impostor")), backgroundScope, config, LogTapAdmission.Present("WRONGGGG"))
            .also { client = it }

        assertFailsWith<TimeoutCancellationException> { impostor.pull() }
    }

    @Test
    fun gatedReconnectReMergesWithoutGapsOrDuplicatesOnApple() = runTest(UnconfinedTestDispatcher()) {
        // Automatable half of the manual reconnect check. The real transport drop is a hardware
        // step (a DTLS session tearing and healing), but the re-merge invariant — a puller that
        // rejoins re-admits through the gate and reconstructs the same sequence with no gap and no
        // repeat — is a property of the idempotent, order-preserving CRDT merge, so it holds over
        // the injected fabric where a client close + fresh join models the rejoin.
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val exporter = exporterWith(3)
        val loom = InMemoryLoom()
        val h = installLogTap(loom, exporter, backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
            .also { host = it }

        val first = LogTapClient(loom.join(InMemoryTag("mac-1")), backgroundScope, config, LogTapAdmission.Present(token.code))
        assertEquals(listOf("log 1", "log 2", "log 3"), first.pull().map { it.body })
        first.close() // drop the link

        // More logs are captured while no puller is attached, then offered.
        (4..6).forEach { i -> exporter.export(logRecord(i)) }
        h.sync()

        // A fresh Mac client rejoins the same host and re-presents the code.
        val second = LogTapClient(loom.join(InMemoryTag("mac-2")), backgroundScope, config, LogTapAdmission.Present(token.code))
            .also { client = it }
        val secondPull = second.pull().map { it.body }

        assertAll(
            { assertEquals((1..6).map { "log $it" }, secondPull, "reconnect re-merges the full sequence in order, no gap") },
            { assertEquals(secondPull.toSet().size, secondPull.size, "no duplicate after reconnect") },
        )
    }

    @Test
    fun metricTapRoundTripsOverAnInjectedFabricOnApple() = runTest(UnconfinedTestDispatcher()) {
        // Proves the metric entry point's replication round-trips a converged buffer on the
        // Apple variants over an injected fabric with the default Open admission — the
        // automatable core of the manual metric check. Gated admission is proven separately
        // below: Multipeer's DTLS encryption governs what a snooper can read off the wire, not
        // who may connect, so admission is still this tap's own concern.
        val exporter = WarpMetricExporter(replica = ReplicaId("iphone"), store = InMemoryDurableStore())
        exporter.incrementSum(MetricKey("frames", MetricKind.SUM), by = 7L)
        exporter.setGauge(MetricKey("fps", MetricKind.GAUGE), 60.0, timestamp = 1L)

        val loom = InMemoryLoom()
        metricHost = installMetricTap(loom, exporter, backgroundScope, metricConfig)
        val mac = MetricTapClient(loom.join(InMemoryTag("mac")), backgroundScope, metricConfig)
            .also { metricClient = it }

        val snap = mac.pull()
        assertAll(
            { assertEquals(7L, snap.sums.getValue(MetricKey("frames", MetricKind.SUM))) },
            { assertEquals(60.0, snap.gauges.getValue(MetricKey("fps", MetricKind.GAUGE))) },
        )
    }

    @Test
    fun gatedMetricTapComposesOverAnInjectedFabricOnApple() = runTest(UnconfinedTestDispatcher()) {
        // Same topology as the log path's gatedTapComposesOverAnInjectedFabricOnApple, but for
        // the metric buffer: the iPhone hosts and verifies (holds the join code); the Mac joins,
        // presents the code, and pulls.
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val exporter = WarpMetricExporter(replica = ReplicaId("iphone"), store = InMemoryDurableStore())
        exporter.incrementSum(MetricKey("frames", MetricKind.SUM), by = 7L)

        val loom = InMemoryLoom()
        metricHost = installMetricTap(loom, exporter, backgroundScope, metricConfig, LogTapAdmission.Verify(token, clock, Random(7)))
        val mac = MetricTapClient(
            loom.join(InMemoryTag("mac")),
            backgroundScope,
            metricConfig,
            LogTapAdmission.Present(token.code),
        ).also { metricClient = it }

        assertEquals(7L, mac.pull().sums.getValue(MetricKey("frames", MetricKind.SUM)))
    }

    @Test
    fun wrongCodeNeverConvergesTheMetricPullOnApple() = runTest(UnconfinedTestDispatcher()) {
        // The metric-path twin of wrongCodeNeverConvergesThePullOnApple: any Mac on the LAN that
        // discovers the advertised service is auto-accepted by Multipeer, so without this gate
        // it could otherwise pull the entire metric buffer ungated.
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val exporter = WarpMetricExporter(replica = ReplicaId("iphone"), store = InMemoryDurableStore())
        exporter.incrementSum(MetricKey("frames", MetricKind.SUM), by = 7L)

        val loom = InMemoryLoom()
        metricHost = installMetricTap(loom, exporter, backgroundScope, metricConfig, LogTapAdmission.Verify(token, clock, Random(7)))
        val impostor = MetricTapClient(
            loom.join(InMemoryTag("impostor")),
            backgroundScope,
            metricConfig,
            LogTapAdmission.Present("WRONGGGG"),
        ).also { metricClient = it }

        assertFailsWith<TimeoutCancellationException> { impostor.pull() }
    }

    @Test
    fun multipeerFabricLinksIntoTheTapModuleOnApple() {
        // Construction alone starts no advertising/browsing (weave() does), so this is safe off
        // real devices. It proves the Apple variants compile and link against :kuilt-multipeer,
        // and that a factory is a valid Loom argument to the Multipeer tap entry points.
        val factory = MultipeerPeerLinkFactory(displayName = "iphone", serviceType = "kuilt-tap")
        // Compile-time proof the factory is a Loom the tap can host over (no always-true runtime check).
        val asLoom: Loom = factory
        assertAll(
            { assertTrue(asLoom === factory, "MultipeerPeerLinkFactory is a Loom the tap can host over") },
            { assertTrue(factory.visiblePeers.value.isEmpty(), "no peers before browsing starts") },
            // Force-link the Apple-only entry points against the factory's type.
            { assertTrue(::installMultipeerLogTap != ::installMultipeerMetricTap) },
        )
        factory.close()
    }
}
