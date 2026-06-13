@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fakeSeamPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertIs

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
}
