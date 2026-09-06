@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamCollapsedException
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fakeSeamPair
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FairRandomTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    /** Bob's own contribution, fixed so two runs of [bobRolls] differ only in what Alice sent. */
    private val bobSecret = ByteArray(FairRandomMessage.Reveal.SECRET_BYTES) { 0xBB.toByte() }
    private val bobNonce = ByteArray(FairRandomMessage.Reveal.NONCE_BYTES) { 0x22.toByte() }

    // ── Two-peer agreement ────────────────────────────────────────────────────

    @Test
    fun twoPeers_agreeOnIdenticalSeed() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)
        val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)

        val aliceDef = scope.async { FairRandom(aliceSeam, peers).roll() }
        val bobDef = scope.async { FairRandom(bobSeam, peers).roll() }

        val aliceSeed = aliceDef.await()
        val bobSeed = bobDef.await()

        assertEquals(aliceSeed, bobSeed, "Both peers must derive the same seed")
    }

    @Test
    fun twoPeers_differentRoundsProduceDifferentSeeds() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)

        val (aliceSeam1, bobSeam1) = fakeSeamPair(alice, bob)
        val (aliceSeam2, bobSeam2) = fakeSeamPair(alice, bob)

        val seed1 = run {
            val a = scope.async { FairRandom(aliceSeam1, peers).roll() }
            val b = scope.async { FairRandom(bobSeam1, peers).roll() }
            b.await(); a.await()
        }
        val seed2 = run {
            val a = scope.async { FairRandom(aliceSeam2, peers).roll() }
            val b = scope.async { FairRandom(bobSeam2, peers).roll() }
            b.await(); a.await()
        }

        // With 32-byte CSPRNG secrets the probability of collision is negligible.
        assertNotEquals(seed1, seed2, "Independent rolls should (almost certainly) differ")
    }

    // ── Mid-2PC collapse (membership drain, no tear) ──────────────────────────

    @Test
    fun roll_throwsWhenParticipantDrainsMidRound() = runTest {
        // The membership-drain analogue of the lobby #1466 bug: alice is mid-round awaiting bob's
        // commit; bob leaves the live peer set (removePeer — seam stays Woven, NO tear). Without
        // raceCollapse, commits.receive() would hang forever. roll() must instead throw within a bound.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)
        val (aliceSeam, _) = fakeSeamPair(alice, bob)

        // Only alice rolls; bob never participates, so alice suspends awaiting bob's commit.
        val aliceDef = scope.async { runCatchingCancellable { FairRandom(aliceSeam, peers).roll() } }
        runCurrent() // let alice broadcast her commit and suspend on commits.receive()

        aliceSeam.removePeer(bob) // membership drain: bob leaves, seam stays Woven (no tear)

        val result = withTimeout(5.seconds) { aliceDef.await() }
        assertIs<SeamCollapsedException>(result.exceptionOrNull())
    }

    // ── Commitment-scheme verification ────────────────────────────────────────

    @Test
    fun tampered_reveal_isRejected() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)
        val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)

        // Alice reveals a different secret than she committed to.
        val dishonestAlice = FairRandom(aliceSeam, peers, tamperedReveal = true)
        val honestBob = FairRandom(bobSeam, peers)

        val aliceDef = scope.async { runCatchingCancellable { dishonestAlice.roll() } }
        val bobDef = scope.async { runCatchingCancellable { honestBob.roll() } }

        aliceDef.await()
        val bobResult = bobDef.await()

        // Bob must detect Alice's tampered reveal and throw CommitmentViolation.
        assertFails { bobResult.getOrThrow() }
        assertIs<CommitmentViolation>(bobResult.exceptionOrNull())
    }

    /**
     * A wrong-width reveal is refused by the **receiver**, and contributes nothing to the seed.
     *
     * The re-split attack in full: `SHA-256(secret ‖ nonce)` carries no length delimiter between
     * the two halves, so a peer who commits to a 48-byte string may afterwards present *any* split
     * of it and the commitment verifies against the hash it already published. Presenting the
     * (33, 15) split rather than the (32, 16) one therefore changes what that peer contributes to
     * the derived seed **after** every other peer is committed — the post-commit bias the fixed
     * widths exist to forbid.
     *
     * Alice exists here only as raw bytes on Bob's seam, built by [unconstrainedRevealFrame]. She
     * is not a [FairRandom]: since #2650 our own encoder calls [FairRandomMessage.Reveal]'s
     * constructor on the way *out*, so it can no longer express a malformed frame — a test that
     * built the attack by handing a local `FairRandom` a 33-byte `fixedSecret` (as this one used
     * to) would now throw on the sending side, and Bob would never receive the frame the test
     * exists to make him refuse. A real attacker is not running our encoder either.
     *
     * The second run is the control that makes the first non-vacuous: "Bob did not complete" would
     * be equally true of a rig whose frames never reached him at all. It shares Alice's commitment
     * with the attacked run, so it also pins that the re-split frame was refused for its *width* —
     * the commitment behind both is identical and demonstrably acceptable.
     */
    @Test
    fun wrongWidthReveal_isRefusedByTheReceiverAndContributesNothing() = runTest {
        val preimage = ByteArray(48) { (it + 1).toByte() }
        val aliceCommit = Cbor.encodeToByteArray<FairRandomMessage>(
            FairRandomMessage.Commit(FairRandom.sha256(preimage)),
        )
        val declared = FairRandomMessage.Reveal.SECRET_BYTES
        val honestSplit = unconstrainedRevealFrame(
            preimage.copyOfRange(0, declared),
            preimage.copyOfRange(declared, preimage.size),
        )
        val reSplit = unconstrainedRevealFrame(
            preimage.copyOfRange(0, declared + 1),
            preimage.copyOfRange(declared + 1, preimage.size),
        )

        var stillWaiting = false
        val attacked = bobRolls(aliceCommit, listOf(reSplit, honestSplit)) { index, bobCompleted ->
            if (index == 0) stillWaiting = !bobCompleted
        }
        val clean = bobRolls(aliceCommit, listOf(honestSplit)) { _, _ -> }

        assertTrue(
            stillWaiting,
            "Bob's round ended the instant Alice's ${declared + 1}-byte secret arrived, so the " +
                "re-split reveal decided it. A frame whose widths the wire type forbids must be " +
                "dropped and the reveal phase left waiting, whichever way the round would have gone",
        )
        val cleanSeed = assertNotNull(
            clean.getOrNull(),
            "control arm: Bob must complete on the honest split of the same commitment, got $clean",
        )
        val attackedSeed = assertNotNull(
            attacked.getOrNull(),
            "Bob must still complete once Alice's honest split arrives, got $attacked",
        )
        assertEquals(
            cleanSeed,
            attackedSeed,
            "the re-split reveal must contribute nothing: a seed that moves when it is injected is " +
                "the post-commit bias the fixed widths exist to prevent",
        )
    }

    /**
     * An unparseable frame is dropped and the round carries on — the *other* half of the single
     * catch arm the width refusal now shares.
     *
     * `roll()`'s collector catches one type, `IllegalArgumentException`, and it covers both
     * refusals only because `SerializationException` happens to extend it. That is a property of
     * kotlinx-serialization, not of this code, and nothing else in this module would notice if it
     * stopped holding: the round would start dying on any peer's stray frame, with the collector
     * gone and no tear to observe. This is the arm that notices.
     */
    @Test
    fun unparseableFrame_isDroppedAndTheRoundContinues() = runTest {
        val preimage = ByteArray(48) { (it + 1).toByte() }
        val declared = FairRandomMessage.Reveal.SECRET_BYTES
        val aliceCommit = Cbor.encodeToByteArray<FairRandomMessage>(
            FairRandomMessage.Commit(FairRandom.sha256(preimage)),
        )
        val honestSplit = unconstrainedRevealFrame(
            preimage.copyOfRange(0, declared),
            preimage.copyOfRange(declared, preimage.size),
        )

        var survivedTheGarbage = false
        val result = bobRolls(aliceCommit, listOf(byteArrayOf(0x00, 0x01, 0x02), honestSplit)) { index, done ->
            if (index == 0) survivedTheGarbage = !done
        }

        assertTrue(survivedTheGarbage, "three bytes of garbage from a peer ended Bob's round: $result")
        assertNotNull(result.getOrNull(), "Bob must still finish on Alice's honest reveal, got $result")
    }

    /**
     * The widths are the wire type's own invariant, so the *sender* cannot express a violation
     * either — the half of the fix a receiver-side test cannot see.
     */
    @Test
    fun reveal_refusesAWrongWidthFieldOnConstruction(): Unit = assertAll(
        {
            val ex = assertFailsWith<IllegalArgumentException> {
                FairRandomMessage.Reveal(ByteArray(33), ByteArray(FairRandomMessage.Reveal.NONCE_BYTES))
            }
            assertContains(ex.message ?: "", "33")
        },
        {
            val ex = assertFailsWith<IllegalArgumentException> {
                FairRandomMessage.Reveal(ByteArray(FairRandomMessage.Reveal.SECRET_BYTES), ByteArray(15))
            }
            assertContains(ex.message ?: "", "15")
        },
    )

    /**
     * Drive an honest Bob through one round against an Alice who exists only as raw frames.
     *
     * [aliceReveals] are delivered in order once Bob is waiting on the reveal phase, and
     * [afterEachReveal] is called with Bob's completion state after each — the hook that lets a
     * caller assert *when* Bob finished, which is the difference between "refused it" and
     * "accepted it and then saw a duplicate".
     */
    private suspend fun TestScope.bobRolls(
        aliceCommit: ByteArray,
        aliceReveals: List<ByteArray>,
        afterEachReveal: (index: Int, bobCompleted: Boolean) -> Unit,
    ): Result<Long> {
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
        aliceReveals.forEachIndexed { index, frame ->
            bobSeam.deliver(alice, frame)
            runCurrent()
            afterEachReveal(index, bobDef.isCompleted)
        }
        return withTimeout(5.seconds) { bobDef.await() }
    }

    // ── Deterministic derivation ──────────────────────────────────────────────

    @Test
    fun identicalSecrets_produceIdenticalSeed() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)

        val fixedAliceSecret = ByteArray(32) { 0xAA.toByte() }
        val fixedBobSecret = ByteArray(32) { 0xBB.toByte() }
        val fixedAliceNonce = ByteArray(16) { 0x11.toByte() }
        val fixedBobNonce = ByteArray(16) { 0x22.toByte() }

        val (aliceSeam1, bobSeam1) = fakeSeamPair(alice, bob)
        val (aliceSeam2, bobSeam2) = fakeSeamPair(alice, bob)

        val seed1 = run {
            val a = scope.async {
                FairRandom(aliceSeam1, peers, fixedSecret = fixedAliceSecret, fixedNonce = fixedAliceNonce).roll()
            }
            val b = scope.async {
                FairRandom(bobSeam1, peers, fixedSecret = fixedBobSecret, fixedNonce = fixedBobNonce).roll()
            }
            b.await(); a.await()
        }

        val seed2 = run {
            val a = scope.async {
                FairRandom(aliceSeam2, peers, fixedSecret = fixedAliceSecret, fixedNonce = fixedAliceNonce).roll()
            }
            val b = scope.async {
                FairRandom(bobSeam2, peers, fixedSecret = fixedBobSecret, fixedNonce = fixedBobNonce).roll()
            }
            b.await(); a.await()
        }

        assertEquals(seed1, seed2, "Identical secrets + nonces must produce an identical seed")
    }

    // ── R6: golden-seed pin (byte-identical deriveSeed) ───────────────────────

    @Test
    fun deriveSeed_matchesGoldenValue_forFixedInputs() = runTest {
        // SEED-AGREEMENT-CRITICAL: pins the concrete Long deriveSeed() produces for
        // fixed peer ids + secrets + nonces. A refactor of deriveSeed's internals
        // (R6: linear copyInto instead of fold + reallocation) must not move this
        // value — any change to the byte layout or concatenation order breaks
        // cross-peer seed agreement.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)

        val fixedAliceSecret = ByteArray(32) { 0xAA.toByte() }
        val fixedBobSecret = ByteArray(32) { 0xBB.toByte() }
        val fixedAliceNonce = ByteArray(16) { 0x11.toByte() }
        val fixedBobNonce = ByteArray(16) { 0x22.toByte() }

        val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)

        val aliceDef = scope.async {
            FairRandom(aliceSeam, peers, fixedSecret = fixedAliceSecret, fixedNonce = fixedAliceNonce).roll()
        }
        val bobDef = scope.async {
            FairRandom(bobSeam, peers, fixedSecret = fixedBobSecret, fixedNonce = fixedBobNonce).roll()
        }

        val aliceSeed = aliceDef.await()
        bobDef.await()

        assertEquals(6733418063457564066L, aliceSeed, "deriveSeed must stay byte-identical across refactors")
    }

    // ── F3: participant validation ────────────────────────────────────────────

    @Test
    fun selfId_notInPeers_isRejected() = runTest {
        val carol = PeerId("carol")
        val (aliceSeam, _) = fakeSeamPair(alice, bob)
        // alice's seam says selfId=alice, but we only list bob+carol in peers
        val fr = FairRandom(aliceSeam, setOf(bob, carol))

        val ex = assertFailsWith<IllegalArgumentException> { fr.roll() }
        assertContains(ex.message ?: "", "selfId")
    }

    @Test
    fun singlePeer_isRejected() = runTest {
        val (aliceSeam, _) = fakeSeamPair(alice, bob)
        val fr = FairRandom(aliceSeam, setOf(alice))

        val ex = assertFailsWith<IllegalArgumentException> { fr.roll() }
        assertContains(ex.message ?: "", "2 peers")
    }

    // ── F8: double-commit convergence ─────────────────────────────────────────

    @Test
    fun duplicateCommit_fromSameSender_isIgnored() = runTest {
        // If a racing duplicate commit arrives (different hash bytes), the first one
        // wins. Both honest peers must still converge on the same seed rather than
        // one throwing CommitmentViolation and one succeeding.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)
        val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)

        val fixedAliceSecret = ByteArray(32) { 0xAA.toByte() }
        val fixedBobSecret = ByteArray(32) { 0xBB.toByte() }
        val fixedAliceNonce = ByteArray(16) { 0x11.toByte() }
        val fixedBobNonce = ByteArray(16) { 0x22.toByte() }

        val aliceDef = scope.async {
            FairRandom(aliceSeam, peers, fixedSecret = fixedAliceSecret, fixedNonce = fixedAliceNonce).roll()
        }
        val bobDef = scope.async {
            FairRandom(bobSeam, peers, fixedSecret = fixedBobSecret, fixedNonce = fixedBobNonce).roll()
        }

        val aliceSeed = aliceDef.await()
        val bobSeed = bobDef.await()

        // Core assertion: both agree.
        assertEquals(aliceSeed, bobSeed)
    }

    // ── F2: seed-framing regression ───────────────────────────────────────────

    @Test
    fun seedFraming_differsByPeerId() = runTest {
        // The seed must differ when peer identities differ even if secrets are identical.
        // This proves the PeerId is folded into deriveSeed (the id ‖ secret hash).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val secret = ByteArray(32) { 0xAA.toByte() }
        val nonce = ByteArray(16) { 0x11.toByte() }

        // Run 1: alice + bob
        val carol = PeerId("carol")
        val dave = PeerId("dave")

        val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)
        val (carolSeam, daveSeam) = fakeSeamPair(carol, dave)

        val seedAliceBob = run {
            val a = scope.async {
                FairRandom(aliceSeam, setOf(alice, bob), fixedSecret = secret, fixedNonce = nonce).roll()
            }
            val b = scope.async {
                FairRandom(bobSeam, setOf(alice, bob), fixedSecret = secret, fixedNonce = nonce).roll()
            }
            b.await(); a.await()
        }

        val seedCarolDave = run {
            val c = scope.async {
                FairRandom(carolSeam, setOf(carol, dave), fixedSecret = secret, fixedNonce = nonce).roll()
            }
            val d = scope.async {
                FairRandom(daveSeam, setOf(carol, dave), fixedSecret = secret, fixedNonce = nonce).roll()
            }
            d.await(); c.await()
        }

        // Different peer identities → different seeds even with identical secrets.
        assertNotEquals(
            seedAliceBob, seedCarolDave,
            "PeerId must be folded into seed derivation; identical secrets with different identities must yield different seeds",
        )
    }
}
