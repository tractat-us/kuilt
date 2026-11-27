package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Nonces issued with no state and spent once. Every instance draws from a seeded [Random], so the
 * secret and each random part are pinned, and every call is given its `now`, so no clock is read.
 * Each refusal arm carries a control that is accepted, so it shows the check can fail rather than
 * only that it passed.
 */
class ProofNoncesTest {
    @Test
    fun aNonceIsTheDocumentedBytes() = runTest {
        val nonces = nonces(seed = 7)
        val nonce = nonces.issue(T)
        // The secret and the random part, drawn again from the same seed in the same order: an
        // independent construction of the bytes, through the provider's HMAC and not ProofNonces'.
        val replay = Random(7)
        val secret = replay.nextBytes(32)
        val randomPart = replay.nextBytes(16)
        val body = byteArrayOf(0x01) + "0000018bcfe56800".hexToByteArray() + randomPart
        val mac = platformProvider().get(HMAC).keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, secret)
            .signatureGenerator().generateSignature(body).copyOf(16)
        assertAll(
            { assertEquals(ProofNonces.NONCE_SIZE, nonce.size, "41 bytes") },
            { assertEquals(T, 0x18bcfe56800L, "precondition: T is the issuedAt written above") },
            { assertEquals((body + mac).toHexString(), nonce.toHexString(), "format ‖ issuedAt ‖ random ‖ truncated MAC") },
        )
    }

    @Test
    fun spentOnceThenReplayed() = runTest {
        val nonces = nonces()
        val nonce = nonces.issue(T)
        val other = nonces.issue(T)
        val first = nonces.spend(fresh(nonces, nonce, T), T)
        // A check reads no spent set, so the nonce is still Fresh there: the spend is where the replay shows.
        val again = nonces.check(nonce, T + 1)
        val second = nonces.spend(assertIs<NonceCheck.Fresh>(again), T + 1)
        val otherSpend = nonces.spend(fresh(nonces, other, T + 2), T + 2)
        assertAll(
            { assertEquals(NonceSpend.Spent, first, "the first spend") },
            { assertEquals(NonceSpend.Replayed, second, "the second spend of the same nonce") },
            { assertEquals(NonceSpend.Spent, otherSpend, "the control: another nonce from the same instance") },
        )
    }

    @Test
    fun expiredAtIssuedAtPlusTtlAndNotOneMillisecondBefore() = runTest {
        // Two instances, since an instance's time never moves back: one asked just before, one at.
        val before = nonces()
        val at = nonces()
        val beforeNonce = before.issue(T)
        val atNonce = at.issue(T)
        val lastMoment = T + TTL.inWholeMilliseconds - 1
        val checkedBefore = before.check(beforeNonce, lastMoment)
        val spentBefore = before.spend(assertIs<NonceCheck.Fresh>(checkedBefore), lastMoment)
        val checkedAt = at.check(atNonce, lastMoment + 1)
        assertAll(
            { assertEquals(NonceSpend.Spent, spentBefore, "one millisecond before issuedAt + ttl") },
            { assertEquals(NonceCheck.Expired, checkedAt, "at issuedAt + ttl") },
        )
    }

    @Test
    fun aNonceThatExpiresBetweenItsCheckAndItsSpendIsNotSpent() = runTest {
        val nonces = nonces()
        val nonce = nonces.issue(T)
        val lastMoment = T + TTL.inWholeMilliseconds - 1
        val checked = assertIs<NonceCheck.Fresh>(nonces.check(nonce, lastMoment), "the rig: fresh at its check")
        assertEquals(NonceSpend.Expired, nonces.spend(checked, lastMoment + 1), "spent at issuedAt + ttl")
    }

    @Test
    fun anotherInstancesNonceIsUnknown() = runTest {
        val mine = nonces(seed = 1)
        val theirs = nonces(seed = 2)
        val nonce = theirs.issue(T)
        val own = mine.issue(T)
        // Their instance accepts it, so the refusal below is about whose secret it carries.
        val theirsAccepts = theirs.check(nonce, T)
        val mineRefuses = mine.check(nonce, T)
        val mineAcceptsOwn = mine.check(own, T)
        assertAll(
            { assertIs<NonceCheck.Fresh>(theirsAccepts, "precondition: the issuing instance accepts it") },
            { assertEquals(NonceCheck.Unknown, mineRefuses, "another instance's nonce") },
            { assertIs<NonceCheck.Fresh>(mineAcceptsOwn, "the control: its own nonce") },
        )
    }

    @Test
    fun aDamagedNonceIsUnknownWithoutThrowing() = runTest {
        val nonces = nonces()
        val nonce = nonces.issue(T)
        val rows = listOf(
            "a flipped format byte" to nonce.withLowBitFlipped(0),
            "a flipped issuedAt bit, which would move its window" to nonce.withLowBitFlipped(8),
            "a flipped random bit" to nonce.withLowBitFlipped(20),
            "a flipped MAC bit" to nonce.withLowBitFlipped(40),
            "one byte short" to nonce.copyOf(40),
            "one byte long" to nonce.copyOf(42),
            "no bytes" to byteArrayOf(),
        ).map { (what, bytes) -> what to nonces.check(bytes, T) }
        val control = nonces.check(nonce, T)
        assertAll(
            *rows.map { (what, verdict) -> { assertEquals(NonceCheck.Unknown, verdict, what) } }.toTypedArray(),
            { assertIs<NonceCheck.Fresh>(control, "the control: the nonce as issued") },
        )
    }

    @Test
    fun aFullSetAnswersOverloadedThenFreesSpaceAsItsEntriesExpire() = runTest {
        val nonces = nonces(capacity = 3)
        val step = 400L
        // Three spent, issued at T, T + step and T + 2·step, so each expires at its own instant.
        val spent = (0..2).map { i -> nonces.issue(T + i * step) }
        val fills = spent.mapIndexed { i, n -> nonces.spend(fresh(nonces, n, T + i * step), T + i * step) }
        val now = T + 2 * step
        val overflow = nonces.issue(now)
        val overloaded = nonces.spend(fresh(nonces, overflow, now), now)
        // A replay is still a replay when the set is full: the spent record answers first.
        val replayWhileFull = nonces.spend(fresh(nonces, spent[0], now), now)
        val stillOverloaded = nonces.spend(fresh(nonces, overflow, now + 1), now + 1)
        // The first entry expires at T + ttl, and its slot is free from then on: one slot, not three.
        val firstGone = T + TTL.inWholeMilliseconds
        val afterOne = nonces.spend(fresh(nonces, overflow, firstGone), firstGone)
        val next = nonces.issue(firstGone)
        val fullAgain = nonces.spend(fresh(nonces, next, firstGone), firstGone)
        assertAll(
            { assertEquals(List(3) { NonceSpend.Spent }, fills, "precondition: the set filled") },
            { assertEquals(NonceSpend.Overloaded, overloaded, "a fourth live nonce") },
            { assertEquals(NonceSpend.Replayed, replayWhileFull, "a spent nonce, while full") },
            { assertEquals(NonceSpend.Overloaded, stillOverloaded, "an overloaded nonce was not spent: still refused, not replayed") },
            { assertEquals(NonceSpend.Spent, afterOne, "once the first entry expired, its slot took the refused nonce") },
            { assertEquals(NonceSpend.Overloaded, fullAgain, "the other two were still live, so the set is full again") },
        )
    }

    @Test
    fun aClockSetBackNeverReopensASpentNonce() = runTest {
        val nonces = nonces()
        val nonce = nonces.issue(T)
        val first = nonces.spend(fresh(nonces, nonce, T + 500), T + 500)
        // Back before the spend, and back before the issue: the instance still answers as of T + 500 ms.
        val backToIssue = nonces.check(nonce, T)
        val spendBack = nonces.spend(assertIs<NonceCheck.Fresh>(backToIssue), T)
        val longAgo = nonces.check(nonce, 0)
        val spendLongAgo = nonces.spend(assertIs<NonceCheck.Fresh>(longAgo), 0)
        assertAll(
            { assertEquals(NonceSpend.Spent, first, "precondition: spent once") },
            { assertEquals(NonceSpend.Replayed, spendBack, "the clock set back to the issue") },
            { assertEquals(NonceSpend.Replayed, spendLongAgo, "the clock set back to the epoch") },
        )
    }

    @Test
    fun aClockSetForwardThenBackNeverReopensASpentNonce() = runTest {
        // Capacity one, so the next spend must sweep the first nonce's entry to succeed: the rig
        // shows the record really is gone before the clock comes back.
        val nonces = nonces(capacity = 1)
        val nonce = nonces.issue(T)
        val first = nonces.spend(fresh(nonces, nonce, T), T)
        val far = T + 10 * TTL.inWholeMilliseconds
        val forward = nonces.check(nonce, far)
        val later = nonces.issue(far)
        val sweptToFit = nonces.spend(fresh(nonces, later, far), far)
        // Back inside the first nonce's window by the caller's clock. Without the instance's own
        // time, the check would answer Fresh and the spend Spent: the record was swept.
        val back = nonces.check(nonce, T + 1)
        assertAll(
            { assertEquals(NonceSpend.Spent, first, "precondition: spent once") },
            { assertEquals(NonceCheck.Expired, forward, "the clock set forward past its window") },
            { assertEquals(NonceSpend.Spent, sweptToFit, "the rig: the first entry was swept to make room") },
            { assertEquals(NonceCheck.Expired, back, "the clock set back inside its window again") },
        )
    }

    @Test
    fun aNonceIssuedWhileTheClockIsBehindCarriesTheInstancesTime() = runTest {
        val nonces = nonces()
        nonces.issue(T + 30_000)
        val behind = nonces.issue(T)
        val issuedAt = StatementCodec.timeOrNull(behind.copyOfRange(1, 9))
        // Good for its whole ttl from the instance's time, not from the clock that fell behind.
        val lastMoment = T + 30_000 + TTL.inWholeMilliseconds - 1
        val atLastMoment = nonces.check(behind, lastMoment)
        assertAll(
            { assertEquals(T + 30_000, issuedAt, "stamped with the latest time seen") },
            { assertIs<NonceCheck.Fresh>(atLastMoment, "good to the end of its window") },
        )
    }

    @Test
    fun aCheckFromAnotherInstanceCannotBeSpentHere() = runTest {
        val mine = nonces(seed = 1)
        val theirs = nonces(seed = 2)
        val checked = fresh(theirs, theirs.issue(T), T)
        assertFailsWith<IllegalArgumentException>("their check, spent here") { mine.spend(checked, T) }
        assertEquals(NonceSpend.Spent, theirs.spend(checked, T), "the control: spent where it was checked")
    }

    @Test
    fun aTimeOutsideTheRangeIsRefused() = runTest {
        val nonces = nonces()
        val nonce = nonces.issue(T)
        assertFailsWith<IllegalArgumentException>("a negative now at issue") { nonces.issue(-1) }
        assertFailsWith<IllegalArgumentException>("a now past MAX_NOW at check") { nonces.check(nonce, ProofNonces.MAX_NOW + 1) }
        assertFailsWith<IllegalArgumentException>("Long.MAX_VALUE at spend") { nonces.spend(fresh(nonces, nonce, T), Long.MAX_VALUE) }
        assertEquals(NonceSpend.Spent, nonces.spend(fresh(nonces, nonce, T), T), "the control: a time in range")
    }

    @Test
    fun createRefusesATtlOrCapacityOutOfRange() = runTest {
        assertFailsWith<IllegalArgumentException>("a zero ttl") { nonces(ttl = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException>("a ttl past MAX_TTL") { nonces(ttl = ProofNonces.MAX_TTL + 1.milliseconds) }
        assertFailsWith<IllegalArgumentException>("a zero capacity") { nonces(capacity = 0) }
        val smallest = nonces(ttl = 1.milliseconds, capacity = 1)
        val longest = ProofNonces.create(ttl = ProofNonces.MAX_TTL)
        assertAll(
            { assertEquals(1.milliseconds, smallest.ttl, "the control: the smallest ttl") },
            { assertEquals(ProofNonces.MAX_TTL, longest.ttl, "the control: the longest ttl, on the platform CSPRNG") },
            { assertEquals(ProofNonces.DEFAULT_CAPACITY, longest.capacity, "the default capacity") },
        )
    }

    private suspend fun nonces(seed: Int = 0, ttl: Duration = TTL, capacity: Int = ProofNonces.DEFAULT_CAPACITY): ProofNonces =
        ProofNonces.create(Random(seed), ttl, capacity)

    private suspend fun fresh(nonces: ProofNonces, nonce: ByteArray, now: Long): NonceCheck.Fresh =
        assertIs<NonceCheck.Fresh>(nonces.check(nonce, now), "the rig: the nonce checks fresh")

    private companion object {
        /** 2023-11-14T22:13:20Z: a real epoch-ms time, well inside the accepted range. */
        const val T = 1_700_000_000_000L
        val TTL = 1.seconds
    }
}
