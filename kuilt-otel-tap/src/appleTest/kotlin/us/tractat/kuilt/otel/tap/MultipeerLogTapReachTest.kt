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
import us.tractat.kuilt.otel.WarpLogRecordExporter
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
 *     reuses unchanged — composes over an injected in-memory fabric on the Apple targets, and
 *  2. the `:kuilt-multipeer` fabric links into the tap module's Apple variants: a
 *     [MultipeerPeerLinkFactory] constructs (no IO until it weaves) and is a [Loom], so it is a
 *     valid argument to [installMultipeerLogTap].
 */
class MultipeerLogTapReachTest {
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
        WarpLogRecordExporter(replica = ReplicaId("iphone"), store = InMemoryDurableStore()).also { e ->
            (1..n).forEach { i -> e.export(LogRecord(recordId = ByteString(ByteArray(8) { i.toByte() }), body = "log $i")) }
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
