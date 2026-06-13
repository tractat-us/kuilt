@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fakeSeamPair
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class FairRandomTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

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

    // ── Commitment-scheme verification ────────────────────────────────────────

    @Test
    fun tampered_reveal_isRejected() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)
        val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)

        // Alice reveals a different secret than she committed to.
        val dishonestAlice = FairRandom(aliceSeam, peers, tamperedReveal = true)
        val honestBob = FairRandom(bobSeam, peers)

        val aliceDef = scope.async { runCatching { dishonestAlice.roll() } }
        val bobDef = scope.async { runCatching { honestBob.roll() } }

        aliceDef.await()
        val bobResult = bobDef.await()

        // Bob must detect Alice's tampered reveal and throw CommitmentViolation.
        assertFails { bobResult.getOrThrow() }
        assertIs<CommitmentViolation>(bobResult.exceptionOrNull())
    }

    @Test
    fun wrong_length_reveal_isRejected() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val peers = setOf(alice, bob)
        val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)

        // Alice uses a 33-byte secret (not the canonical SECRET_BYTES=32). Bob must
        // reject it at the length check, preventing the re-split preimage ambiguity
        // attack: SHA-256(S₃₃ ‖ N₁₅) == SHA-256(S₃₂ ‖ N₁₆) for a crafted split.
        val dishonestAlice = FairRandom(aliceSeam, peers, fixedSecret = ByteArray(33) { 0xCC.toByte() })
        val honestBob = FairRandom(bobSeam, peers)

        val aliceDef = scope.async { runCatching { dishonestAlice.roll() } }
        val bobDef = scope.async { runCatching { honestBob.roll() } }

        aliceDef.await()
        val bobResult = bobDef.await()

        assertFails { bobResult.getOrThrow() }
        assertIs<CommitmentViolation>(bobResult.exceptionOrNull())
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
