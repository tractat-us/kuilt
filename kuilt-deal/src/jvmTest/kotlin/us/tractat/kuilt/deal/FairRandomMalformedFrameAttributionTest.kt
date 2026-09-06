@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import org.slf4j.LoggerFactory
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fakeSeamPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A dropped frame must still name the peer that sent it (#2666).
 *
 * Since the reveal widths moved onto the wire type (#2650), a wrong-width reveal is refused by
 * `FairRandomMessage.Reveal`'s constructor and `roll()`'s collector drops the frame. That is the
 * right disposition — but the sender is bound one line *above* the `try`, so dropping it silently
 * threw away an identity that was already in hand: a peer probing the protocol became
 * indistinguishable from one with a flaky link, and from one that simply withheld.
 *
 * These assert the **real log emission** — level, count and text — through a Logback
 * [ListAppender], the same vehicle `:kuilt-nw`'s `NwSeamWedgeDiagnosticsTest` uses. A spy on an
 * injected sink would prove the collector consulted an instrument; a captured [ILoggingEvent]
 * proves the line an operator would actually read, at the level that decides whether it is recorded
 * at all. That makes this a `jvmTest`: Logback is a JVM backend, while the subject ([FairRandom])
 * is `commonMain`, so nothing platform-specific is lost.
 *
 * Each test rigs the drop it claims to detect and **counts the firings**: a diagnostic that never
 * triggers passes green by absence, and the count rather than "at least one" is what pins the
 * boundedness the escalation promises — a `warn` that fires once must not become one per frame.
 * Peer ids are prefixed per test so a sibling test sharing the JVM cannot contribute to a count.
 */
class FairRandomMalformedFrameAttributionTest {

    /** Bob's own contribution, fixed so the round is deterministic. */
    private val bobSecret = ByteArray(FairRandomMessage.Reveal.SECRET_BYTES) { 0xBB.toByte() }
    private val bobNonce = ByteArray(FairRandomMessage.Reveal.NONCE_BYTES) { 0x22.toByte() }

    /**
     * The whole point of #2666: the identity is in scope at the drop site, so it must reach the log.
     *
     * The frame is the re-split attack of #2650 — a 48-byte preimage presented as (33, 15) rather
     * than the declared (32, 16). Its commitment verifies; only the widths are wrong, which is
     * exactly the case where the *sender* is the entire diagnosis, because nothing downstream will
     * ever see this peer again this round.
     */
    @Test
    fun aMalformedRevealNamesItsSenderAtWarn() = runTest {
        val alice = PeerId("attribution-alice")
        val preimage = ByteArray(48) { (it + 1).toByte() }
        val declared = FairRandomMessage.Reveal.SECRET_BYTES

        withCapture { appender ->
            val result = bobRolls(
                alice,
                commitFor(preimage),
                listOf(reSplitReveal(preimage), honestReveal(preimage)),
            )
            assertNotNull(
                result.getOrNull(),
                "control: Bob must still complete on Alice's honest split, got $result — a round " +
                    "that never reached the reveal phase would make every assertion below vacuous",
            )

            val warns = appender.namingIn(Level.WARN, alice)
            assertAll(
                {
                    assertEquals(
                        1,
                        warns.size,
                        "exactly one WARN must name '${alice.value}' after its ${declared + 1}-byte " +
                            "secret was dropped; got ${warns.size}: ${appender.dump()}",
                    )
                },
                {
                    // firstOrNull, not single(): on the empty list single() throws a bare
                    // NoSuchElementException, which replaces this assertion's own message with a
                    // red that says nothing about what was expected.
                    val line = warns.firstOrNull().orEmpty()
                    assertTrue(
                        line.contains("${declared + 1} bytes"),
                        "the line must carry the refusal's own message so the reader learns WHY the " +
                            "frame was dropped, not merely that one was; got: '$line'",
                    )
                },
            )
        }
    }

    /**
     * The escalation is the reason `warn` is affordable here at all, so it is asserted, not assumed.
     *
     * `roll()`'s collector sees every frame on the seam, from any peer, with no consensus or rate
     * limit in front of it. An unconditional `warn` would hand a hostile peer a log amplifier for
     * as long as the round lasts. One `warn` per sender, then `debug`, keeps the accountability the
     * issue asks for while capping what a flood can cost — and the `debug` lines mean the detail is
     * still recoverable by turning the level up, rather than discarded.
     */
    @Test
    fun aFloodFromOneSenderCostsOneWarnAndTheRestAtDebug() = runTest {
        val alice = PeerId("flood-alice")
        val preimage = ByteArray(48) { (it + 2).toByte() }
        val malformed = List(4) { reSplitReveal(preimage) }

        withCapture { appender ->
            val result = bobRolls(alice, commitFor(preimage), malformed + honestReveal(preimage))
            assertNotNull(
                result.getOrNull(),
                "control: Bob must still complete on Alice's honest split, got $result",
            )

            assertAll(
                {
                    assertEquals(
                        1,
                        appender.namingIn(Level.WARN, alice).size,
                        "a peer that floods ${malformed.size} malformed frames must cost exactly one " +
                            "WARN, not one per frame: ${appender.dump()}",
                    )
                },
                {
                    assertEquals(
                        malformed.size - 1,
                        appender.namingIn(Level.DEBUG, alice).size,
                        "every subsequent drop must still be recorded, demoted to DEBUG rather than " +
                            "discarded: ${appender.dump()}",
                    )
                },
            )
        }
    }

    // ── rig ─────────────────────────────────────────────────────────────────────

    private fun commitFor(preimage: ByteArray): ByteArray =
        Cbor.encodeToByteArray<FairRandomMessage>(FairRandomMessage.Commit(FairRandom.sha256(preimage)))

    /** The declared (32, 16) split — well-formed, and what lets the round finish. */
    private fun honestReveal(preimage: ByteArray): ByteArray {
        val declared = FairRandomMessage.Reveal.SECRET_BYTES
        return unconstrainedRevealFrame(
            preimage.copyOfRange(0, declared),
            preimage.copyOfRange(declared, preimage.size),
        )
    }

    /**
     * The (33, 15) split of the same preimage — refused for its widths alone.
     *
     * Built through `unconstrainedRevealFrame` because our own encoder now calls `Reveal`'s
     * constructor on the way *out*, so it can no longer express this frame. A real attacker is not
     * running our encoder either.
     */
    private fun reSplitReveal(preimage: ByteArray): ByteArray {
        val wrong = FairRandomMessage.Reveal.SECRET_BYTES + 1
        return unconstrainedRevealFrame(
            preimage.copyOfRange(0, wrong),
            preimage.copyOfRange(wrong, preimage.size),
        )
    }

    /** Drive an honest Bob through one round against an Alice who exists only as raw frames. */
    private suspend fun TestScope.bobRolls(
        alice: PeerId,
        aliceCommit: ByteArray,
        aliceFrames: List<ByteArray>,
    ): Result<Long> {
        // Deliberately NOT derived from Alice's id: the line also names the LOCAL peer, so an id
        // of which Alice's is a substring would let "the sender is named" pass on a line that in
        // fact named only Bob.
        val bob = PeerId(alice.value.replace("alice", "bob"))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val (_, bobSeam) = fakeSeamPair(alice, bob)
        val bobDef = scope.async {
            runCatchingCancellable {
                FairRandom(bobSeam, setOf(alice, bob), fixedSecret = bobSecret, fixedNonce = bobNonce).roll()
            }
        }
        runCurrent()
        bobSeam.deliver(alice, aliceCommit)
        runCurrent()
        aliceFrames.forEach { frame ->
            bobSeam.deliver(alice, frame)
            runCurrent()
        }
        return withTimeout(5.seconds) { bobDef.await() }
    }

    // ── capture plumbing ────────────────────────────────────────────────────────

    /** Lines at [level] that name [peer] — the per-test id prefix keeps a sibling test out of the count. */
    private fun ListAppender<ILoggingEvent>.namingIn(level: Level, peer: PeerId): List<String> =
        list.filter { it.level == level }.map { it.formattedMessage }.filter { it.contains(peer.value) }

    private fun ListAppender<ILoggingEvent>.dump(): String =
        list.joinToString("\n") { "${it.level} ${it.formattedMessage}" }.ifEmpty { "<nothing was logged>" }

    private inline fun withCapture(block: (ListAppender<ILoggingEvent>) -> Unit) {
        @Suppress("CastNullableToNonNullableType") // SLF4J returns non-null; Logback is the bound implementation
        val logger = LoggerFactory.getLogger("us.tractat.kuilt.deal") as Logger
        val previousLevel = logger.level
        logger.level = Level.DEBUG // so a DEMOTED line is still captured — and then fails the level count
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = previousLevel
        }
    }
}
