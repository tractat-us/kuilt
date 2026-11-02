@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.otel.tap.admit.SendCountingSeam
import us.tractat.kuilt.otel.tap.admit.TapAdmitMessage
import us.tractat.kuilt.otel.tap.admit.admissionTag
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The gated tap end-to-end over an in-memory [InMemoryLoom] pair: a puller presenting the
 * valid code pulls the device buffer; a wrong code is never admitted, so the pull never
 * converges; a peer that never proved the code is shut out of the puller too; and the
 * default `Open` admission reproduces the ungated behaviour.
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
        val c = LogTapClient(loom.join(InMemoryTag("puller")), backgroundScope, config, LogTapAdmission.Present(token.code, Random(11)))
            .also { client = it }

        val pulled = c.pull()

        assertEquals(listOf("log 1", "log 2", "log 3"), pulled.map { it.body })
    }

    /**
     * The harm in the terms a user sees: the laptop hosts, a stranger floods it with challenges and
     * reads nothing, and then the phone joins. The pull has to finish anyway.
     */
    @Test
    fun aStrangerWhoStopsReadingDoesNotStopThePull() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        val pullerSeam = SendCountingSeam(loom.host(config.pattern))
        val c = LogTapClient(pullerSeam, backgroundScope, config, LogTapAdmission.Present(token.code, Random(11)))
            .also { client = it }
        val stranger = loom.join(InMemoryTag("stranger"))

        var sent = 0
        while (pullerSeam.parkedTo(stranger.selfId) == 0) {
            check(sent < 10_000) { "rig: $sent challenges and the fabric still takes every answer" }
            val nonce = ByteString(Random(sent).nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES))
            stranger.sendTo(pullerSeam.selfId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
            sent++
            runCurrent()
        }

        host = installLogTapJoining(
            loom,
            exporterWith(3),
            backgroundScope,
            InMemoryTag("device"),
            config,
            LogTapAdmission.Verify(token, clock, Random(7)),
        )

        assertEquals(listOf("log 1", "log 2", "log 3"), c.pull().map { it.body })
    }

    @Test
    fun wrongCodeNeverConvergesThePull() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installLogTap(loom, exporterWith(3), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val c = LogTapClient(loom.join(InMemoryTag("attacker")), backgroundScope, config, LogTapAdmission.Present("WRONGGGG", Random(11)))
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
        val laptop = LogTapClient(loom.host(pattern), backgroundScope, config, LogTapAdmission.Present(token.code, Random(11)))
            .also { client = it }
        // Device joins and offers its buffer.
        host = installLogTapJoining(
            loom, exporterWith(4), backgroundScope, InMemoryTag("inverted"), config,
            LogTapAdmission.Verify(token, clock, Random(9)),
        )

        assertEquals(listOf("log 1", "log 2", "log 3", "log 4"), laptop.pull().map { it.body })
    }

    /**
     * Lock-out (#1865): a stranger reaches the hosting laptop first and challenges it. The laptop
     * used to answer only the first peer to challenge it, so the device behind the stranger was
     * never answered and the pull could never finish.
     */
    @Test
    fun aStrangerChallengingFirstDoesNotLockOutARoleInvertedPull() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        val laptopSeam = loom.host(us.tractat.kuilt.core.Pattern("inverted"))
        val laptop = LogTapClient(laptopSeam, backgroundScope, config, LogTapAdmission.Present(token.code, Random(11)))
            .also { client = it }
        val stranger = loom.join(InMemoryTag("stranger"))
        val answers = mutableListOf<TapAdmitMessage.Proof>()
        backgroundScope.launch {
            stranger.incoming.collect { swatch ->
                (TapAdmitMessage.decode(swatch.toByteArray()) as? TapAdmitMessage.Proof)?.let { answers += it }
            }
        }
        val nonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES))
        stranger.sendTo(laptopSeam.selfId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
        assertEquals(1, answers.size, "rig: the stranger's challenge must have reached the laptop first")

        host = installLogTapJoining(
            loom, exporterWith(4), backgroundScope, InMemoryTag("inverted"), config,
            LogTapAdmission.Verify(token, clock, Random(9)),
        )

        assertEquals(listOf("log 1", "log 2", "log 3", "log 4"), laptop.pull().map { it.body })
    }

    /** The plain topology's half: with a stranger on the mesh, the device still admits its puller. */
    @Test
    fun aStrangerOnTheMeshDoesNotStopAPlainPull() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installLogTap(loom, exporterWith(3), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val stranger = loom.join(InMemoryTag("stranger"))
        val pullerSeam = loom.join(InMemoryTag("puller"))
        val nonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES))
        stranger.sendTo(pullerSeam.selfId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
        val puller = LogTapClient(pullerSeam, backgroundScope, config, LogTapAdmission.Present(token.code, Random(11)))
            .also { client = it }

        assertEquals(listOf("log 1", "log 2", "log 3"), puller.pull().map { it.body })
    }

    /**
     * A stranger on the mesh after the pull. It can reach the puller, and the challenge it sends proves
     * its collector works, so the silence that follows means something: the puller replicates only to a
     * peer that has answered its nonce.
     */
    @Test
    fun aStrangerJoiningAfterThePullReadsNothingFromThePuller() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val loom = InMemoryLoom()
        host = installLogTap(loom, exporterWith(1), backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val pullerSeam = loom.join(InMemoryTag("puller"))
        val puller = LogTapClient(pullerSeam, backgroundScope, config, LogTapAdmission.Present(token.code, Random(11)))
            .also { client = it }
        assertEquals(listOf("log 1"), puller.pull().map { it.body }, "rig: the honest puller must have pulled")

        val stranger = loom.join(InMemoryTag("stranger"))
        val replicated = mutableListOf<String>()
        var answers = 0
        backgroundScope.launch {
            stranger.incoming.collect { swatch ->
                val frame = swatch.toByteArray()
                when {
                    swatch.sender != pullerSeam.selfId -> Unit
                    TapAdmitMessage.isAdmitFrame(frame) -> answers++
                    else -> replicated += frame.decodeToString()
                }
            }
        }
        val nonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES))
        stranger.sendTo(pullerSeam.selfId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
        delay(1.seconds) // suspends for real, so the collector above gets to run

        assertAll(
            { assertEquals(1, answers, "control: the stranger reaches the puller and hears what it is sent") },
            { assertEquals(emptyList(), replicated, "the puller must replicate nothing to a peer that never proved the code") },
        )
    }

    /**
     * The other half. An `Open` device has no code to prove, so to this puller it is any stranger with a
     * replicator: the frames arrive, and the pull does not take them. The control is
     * `roleInvertedDeviceJoinsAndLaptopPulls`, the same rig with a device that holds the code.
     */
    @Test
    fun aPullerHoldingACodeTakesNoLogsFromAPeerThatNeverProvedIt() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val pullerSeam = IncomingCountingSeam(loom.host(us.tractat.kuilt.core.Pattern("forged")))
        val puller = LogTapClient(pullerSeam, backgroundScope, config, LogTapAdmission.Present("CODE1234", Random(11)))
            .also { client = it }

        host = installLogTapJoining(loom, exporterWith(1), backgroundScope, InMemoryTag("forged"), config, LogTapAdmission.Open)

        assertFailsWith<TimeoutCancellationException> { puller.pull() }
        assertTrue(pullerSeam.received > 0, "rig: the stranger's frames must have reached the puller's gate")
    }

    /**
     * Every replication frame the replicator takes earns an ack in a job of its own, so a sender that
     * stops reading could park one job per frame on the puller. Dropping a stranger's frames at the gate
     * is what bounds that, since no ack is ever owed. The review of #3070 measured 873 parked at 1000
     * records with no gate here.
     */
    @Test
    fun aStrangerWhoStopsReadingParksNothingOnThePullerByLoggingAtIt() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val counted = IncomingCountingSeam(loom.host(us.tractat.kuilt.core.Pattern("deaf")))
        val pullerSeam = SendCountingSeam(counted)
        client = LogTapClient(pullerSeam, backgroundScope, config, LogTapAdmission.Present("CODE1234", Random(11)))
        val raw = loom.join(InMemoryTag("stranger"))
        val deaf = object : Seam by raw {
            override val incoming: Flow<Swatch> = flow { awaitCancellation() }
        }
        val exporter = WarpLogRecordExporter(replica = ReplicaId("stranger"), store = InMemoryDurableStore())
        host = LogTapHost(deaf, exporter, backgroundScope, config, LogTapAdmission.Open)
        runCurrent()

        logAndSampleParkedSends(exporter, pullerSeam, raw.selfId)

        assertAll(
            { assertTrue(counted.received >= PARKING_RECORDS, "rig: the frames must have reached the gate, got ${counted.received}") },
            { assertEquals(0, pullerSeam.parkedTo(raw.selfId), "nothing may park for a peer that never proved the code") },
            { assertEquals(0, pullerSeam.deliveredTo(raw.selfId), "and nothing may be sent to it either") },
        )
    }

    /**
     * A known limit. A peer that really proved the code and then stops reading still parks a send per
     * frame, because the replicator's acks have no bound of their own. On a fabric that carries a second
     * connection under an admitted id (#3079) a stranger gets this too. The cost measured here is memory;
     * no stall was measured.
     *
     * If this reds because acks are now bounded, delete it and fix "The honest limitations" in `module.md`.
     */
    @Test
    fun aPeerThatProvedTheCodeAndStopsReadingStillParksASendPerFrame() = runTest(UnconfinedTestDispatcher()) {
        val code = "CODE1234"
        val loom = InMemoryLoom()
        val pullerSeam = SendCountingSeam(loom.host(us.tractat.kuilt.core.Pattern("deaf")))
        val puller = LogTapClient(pullerSeam, backgroundScope, config, LogTapAdmission.Present(code, Random(11)))
            .also { client = it }
        val raw = loom.join(InMemoryTag("holder"))

        val nonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES))
        raw.sendTo(pullerSeam.selfId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
        val proof = TapAdmitMessage.decode(raw.incoming.first().toByteArray()) as TapAdmitMessage.Proof
        val answerNonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x44 })
        val tag = admissionTag(code, pullerSeam.selfId, raw.selfId, proof.proverNonce, answerNonce)
        raw.sendTo(pullerSeam.selfId, TapAdmitMessage.encode(TapAdmitMessage.Proof(answerNonce, ByteString(tag))))

        val deaf = object : Seam by raw {
            override val incoming: Flow<Swatch> = flow { awaitCancellation() }
        }
        val exporter = WarpLogRecordExporter(replica = ReplicaId("holder"), store = InMemoryDurableStore())
        host = LogTapHost(deaf, exporter, backgroundScope, config, LogTapAdmission.Open)
        runCurrent()

        val samples = logAndSampleParkedSends(exporter, pullerSeam, raw.selfId)
        val pulled = puller.pull()

        assertAll(
            { assertTrue(pulled.isNotEmpty(), "rig: the puller must have taken this peer's records") },
            { assertTrue(samples.last() > samples.first(), "parked sends to one peer keep growing: $samples") },
        )
    }

    /** Export [PARKING_RECORDS] records, one sync each, and return the parked sends to [peer] every 200. */
    private suspend fun kotlinx.coroutines.test.TestScope.logAndSampleParkedSends(
        exporter: WarpLogRecordExporter,
        pullerSeam: SendCountingSeam,
        peer: PeerId,
    ): List<Int> {
        val samples = mutableListOf<Int>()
        val sender = host ?: error("rig: the host must be installed first")
        repeat(PARKING_RECORDS) { i ->
            exporter.export(LogRecord(recordId = ByteString(ByteArray(8) { b -> ((i shr (b * 8)) and 0xFF).toByte() }), body = "x"))
            sender.sync()
            runCurrent()
            if ((i + 1) % 200 == 0) samples += pullerSeam.parkedTo(peer)
        }
        return samples
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

private const val PARKING_RECORDS = 1000

/** Counts the frames that reach whoever collects this seam, so a test can show a stranger's arrived. */
private class IncomingCountingSeam(private val delegate: Seam) : Seam by delegate {
    var received = 0
        private set

    override val incoming: Flow<Swatch> = delegate.incoming.onEach { received++ }
}
