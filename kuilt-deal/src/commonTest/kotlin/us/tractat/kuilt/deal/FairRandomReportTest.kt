@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamCollapsedException
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.fakeSeamPair
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FairRandomReportTest {
    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    @Test
    fun overlappingRollDoesNotBroadcastAgain() = runTest {
        val (_, seam) = fakeSeamPair(alice, bob)
        val round = FairRandom(seam, setOf(alice, bob))
        val first = backgroundScope.async { round.roll() }
        runCurrent()
        val second = backgroundScope.async { runCatchingCancellable { round.roll() } }
        runCurrent()
        assertEquals(1, seam.broadcasts.size, "a second roll must be refused before broadcasting")
        assertTrue(second.isCompleted)
        assertIs<IllegalStateException>(second.await().exceptionOrNull())
        assertNull(round.malformedFrameCounts)
        first.cancel()
        first.join()
        assertFailsWith<IllegalStateException> { round.rollWithReport() }
        assertEquals(emptyMap(), round.malformedFrameCounts)
    }

    @Test
    fun successRetainsParticipantCounts() = exerciseRound(End.SUCCESS)

    @Test
    fun commitmentFailureRetainsParticipantCounts() = exerciseRound(End.VIOLATION)

    @Test
    fun cancellationRetainsParticipantCounts() = exerciseRound(End.CANCEL)

    @Test
    fun collapseRetainsParticipantCounts() = exerciseRound(End.COLLAPSE)

    private enum class End { SUCCESS, VIOLATION, CANCEL, COLLAPSE }

    private fun exerciseRound(end: End) = runTest {
        val (_, seam) = fakeSeamPair(alice, bob)
        val participants = mutableSetOf(alice, bob)
        val round = FairRandom(seam, participants)
        val outsider = PeerId("outsider")
        // Caller-owned membership must not change either the protocol or the count bound.
        participants.add(outsider)
        assertNull(round.malformedFrameCounts)
        val result = backgroundScope.async { runCatchingCancellable { round.rollWithReport() } }
        runCurrent()
        val preimage = ByteArray(48) { it.toByte() }
        seam.deliver(alice, Cbor.encodeToByteArray<FairRandomMessage>(
            FairRandomMessage.Commit(FairRandom.sha256(preimage)),
        ))
        runCurrent()
        val malformed = listOf(byteArrayOf(0), unconstrainedRevealFrame(
            preimage.copyOfRange(0, 33), preimage.copyOfRange(33, 48),
        ), byteArrayOf(1))
        for (frame in malformed) {
            seam.deliver(alice, frame)
            runCurrent()
        }
        repeat(8) { index ->
            seam.deliver(if (index == 0) outsider else PeerId("outsider-$index"), byteArrayOf(0))
            runCurrent()
        }
        assertNull(round.malformedFrameCounts, "counts must remain unpublished while collecting")
        when (end) {
            End.SUCCESS, End.VIOLATION -> {
                val secret = preimage.copyOfRange(0, 32)
                if (end == End.VIOLATION) secret[0] = 99
                seam.deliver(alice, unconstrainedRevealFrame(secret, preimage.copyOfRange(32, 48)))
            }
            End.CANCEL -> result.cancel(CancellationException("caller cancelled"))
            End.COLLAPSE -> seam.removePeer(alice)
        }
        runCurrent()
        assertTrue(result.isCompleted, "round must finish without a timeout")
        val expected = mapOf(alice to malformed.size.toLong())
        when (end) {
            End.SUCCESS -> assertEquals(expected, result.await().getOrThrow().malformedFrameCounts)
            End.VIOLATION -> assertIs<CommitmentViolation>(result.await().exceptionOrNull())
            End.CANCEL -> assertFailsWith<CancellationException> { result.await() }
            End.COLLAPSE -> assertIs<SeamCollapsedException>(result.await().exceptionOrNull())
        }
        assertEquals(expected, round.malformedFrameCounts)
        assertFailsWith<IllegalStateException> { round.roll() }
        assertFailsWith<IllegalStateException> { round.rollWithReport() }
        assertEquals(expected, round.malformedFrameCounts, "repeat calls must not erase counts")
        seam.deliver(alice, byteArrayOf(0))
        runCurrent()
        assertEquals(expected, round.malformedFrameCounts, "snapshot must remain final")
    }

    @Test
    fun cancellationWaitsForCollectorBeforePublishingSnapshot() = runTest {
        val (_, delegate) = fakeSeamPair(alice, bob)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val seam = object : Seam by delegate {
            override val incoming = delegate.incoming.onCompletion {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }
        val round = FairRandom(seam, setOf(alice, bob))
        val result = backgroundScope.async { round.roll() }
        runCurrent()
        delegate.deliver(alice, byteArrayOf(0))
        runCurrent()
        result.cancel()
        runCurrent()
        try {
            assertAll(
                { assertTrue(cleanupEntered.isCompleted, "collector cleanup rig must fire") },
                { assertFalse(result.isCompleted, "round must wait for its collector") },
                { assertNull(round.malformedFrameCounts, "snapshot must wait for collector shutdown") },
            )
        } finally {
            releaseCleanup.complete(Unit)
        }
        runCurrent()
        assertTrue(result.isCompleted)
        assertFailsWith<CancellationException> { result.await() }
        assertEquals(mapOf(alice to 1L), round.malformedFrameCounts)
    }

    @Test
    fun reportCopiesItsInputAndReturnedMaps() {
        val input = mutableMapOf(alice to 1L, bob to 2L)
        val report = FairRandomReport(42L, input)
        input.clear()
        // On targets where the read-only Map is mutable at runtime, edits must stay local.
        (report.malformedFrameCounts as? MutableMap<PeerId, Long>)?.clear()
        assertEquals(mapOf(alice to 1L, bob to 2L), report.malformedFrameCounts)
    }

    @Test
    fun countersSaturate(): Unit = assertAll(
        { assertEquals(1L, incrementMalformedCount(0L)) },
        { assertEquals(Long.MAX_VALUE, incrementMalformedCount(Long.MAX_VALUE - 1L)) },
        { assertEquals(Long.MAX_VALUE, incrementMalformedCount(Long.MAX_VALUE)) },
    )

    @Test
    fun reportSampleAgreesWithLegacyRoll() = runTest {
        val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)
        val report = async { sampleFairRandomReport(aliceSeam, setOf(alice, bob)) }
        val legacySeed = async { FairRandom(bobSeam, setOf(alice, bob)).roll() }
        val completed = report.await()
        val seed = legacySeed.await()
        assertAll(
            { assertEquals(seed, completed.seed) },
            { assertEquals(emptyMap(), completed.malformedFrameCounts) },
        )
    }
}
