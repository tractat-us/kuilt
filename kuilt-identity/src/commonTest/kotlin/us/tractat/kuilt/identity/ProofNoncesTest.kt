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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

/**
 * Nonces issued with no state and spent once. Every instance draws from a seeded [Random], so each
 * secret and each random part is pinned, and runs on a [TestTimeSource] and a wall clock the test
 * moves, so no real clock is read. [Rig.advance] moves the two together; [Rig.stepWall] and
 * [Rig.sleep] move one alone, which is what makes them disagree. Each refusal arm carries a control
 * that is accepted, so it shows the check can fail rather than only that it passed.
 */
class ProofNoncesTest {
    @Test
    fun aNonceIsTheDocumentedBytes() = runTest {
        val rig = rig(seed = 7)
        rig.advance(0x1234.milliseconds)
        val nonce = rig.nonces.issue()
        // The secret and the random part, drawn again from the same seed in the same order: an
        // independent construction of the bytes, through the provider's HMAC and not ProofNonces'.
        val replay = Random(7)
        val secret = replay.nextBytes(32)
        val randomPart = replay.nextBytes(16)
        val body = byteArrayOf(0x01) + "0000000000001234".hexToByteArray() + randomPart
        val mac = platformProvider().get(HMAC).keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, secret)
            .signatureGenerator().generateSignature(body).copyOf(16)
        assertAll(
            { assertEquals(ProofNonces.NONCE_SIZE, nonce.size, "41 bytes") },
            { assertEquals((body + mac).toHexString(), nonce.toHexString(), "format ‖ ms on the time source ‖ random ‖ truncated MAC") },
        )
    }

    @Test
    fun spentOnceThenReplayed() = runTest {
        val rig = rig()
        val nonce = rig.nonces.issue()
        val other = rig.nonces.issue()
        val first = rig.nonces.spend(rig.fresh(nonce))
        rig.advance(1.milliseconds)
        // A check reads no spent set, so the nonce is still Fresh there: the spend is where the replay shows.
        val second = rig.nonces.spend(rig.fresh(nonce))
        val otherSpend = rig.nonces.spend(rig.fresh(other))
        assertAll(
            { assertEquals(NonceSpend.Spent, first, "the first spend") },
            { assertEquals(NonceSpend.Replayed, second, "the second spend of the same nonce") },
            { assertEquals(NonceSpend.Spent, otherSpend, "the control: another nonce from the same instance") },
        )
    }

    @Test
    fun expiryFallsAtExactlyTheTtlOnTheTimeSource() = runTest {
        // The wall clock never moves, so the time source alone decides. At the end the two
        // disagree by exactly the ttl, which is still agreement: had a new secret been drawn, the
        // second nonce would be Unknown rather than Expired.
        val rig = rig()
        val spentJustBefore = rig.nonces.issue()
        val checkedAt = rig.nonces.issue()
        rig.time += TTL - 1.milliseconds
        val lastMoment = rig.nonces.spend(rig.fresh(spentJustBefore))
        rig.time += 1.milliseconds
        val atTtl = rig.nonces.check(checkedAt)
        assertAll(
            { assertEquals(NonceSpend.Spent, lastMoment, "one millisecond before the ttl") },
            { assertEquals(NonceCheck.Expired, atTtl, "at the ttl") },
        )
    }

    @Test
    fun aNonceThatExpiresBetweenItsCheckAndItsSpendIsNotSpent() = runTest {
        val rig = rig()
        val nonce = rig.nonces.issue()
        rig.advance(TTL - 1.milliseconds)
        val checked = rig.fresh(nonce)
        rig.advance(1.milliseconds)
        assertEquals(NonceSpend.Expired, rig.nonces.spend(checked), "spent at the ttl")
    }

    @Test
    fun anotherInstancesNonceIsUnknown() = runTest {
        val mine = rig(seed = 1)
        val theirs = rig(seed = 2)
        val nonce = theirs.nonces.issue()
        val own = mine.nonces.issue()
        // Their instance accepts it, so the refusal below is about whose secret it carries.
        val theirsAccepts = theirs.nonces.check(nonce)
        val mineRefuses = mine.nonces.check(nonce)
        val mineAcceptsOwn = mine.nonces.check(own)
        assertAll(
            { assertIs<NonceCheck.Fresh>(theirsAccepts, "precondition: the issuing instance accepts it") },
            { assertEquals(NonceCheck.Unknown, mineRefuses, "another instance's nonce") },
            { assertIs<NonceCheck.Fresh>(mineAcceptsOwn, "the control: its own nonce") },
        )
    }

    @Test
    fun aDamagedNonceIsUnknownWithoutThrowing() = runTest {
        val rig = rig()
        val nonce = rig.nonces.issue()
        val rows = listOf(
            "a flipped format byte" to nonce.withLowBitFlipped(0),
            "a flipped issuedAt bit, which would move its window" to nonce.withLowBitFlipped(8),
            "a flipped random bit" to nonce.withLowBitFlipped(20),
            "a flipped MAC bit" to nonce.withLowBitFlipped(40),
            "one byte short" to nonce.copyOf(40),
            "one byte long" to nonce.copyOf(42),
            "no bytes" to byteArrayOf(),
        ).map { (what, bytes) -> what to rig.nonces.check(bytes) }
        val control = rig.nonces.check(nonce)
        assertAll(
            *rows.map { (what, verdict) -> { assertEquals(NonceCheck.Unknown, verdict, what) } }.toTypedArray(),
            { assertIs<NonceCheck.Fresh>(control, "the control: the nonce as issued") },
        )
    }

    @Test
    fun aFullSetAnswersOverloadedThenFreesSpaceAsItsEntriesExpire() = runTest {
        val rig = rig(capacity = 3)
        val step = 400.milliseconds
        // Three spent, issued step apart, so each expires at its own instant.
        val spent = (0..2).map { i ->
            if (i > 0) rig.advance(step)
            rig.nonces.issue()
        }
        val fills = spent.map { rig.nonces.spend(rig.fresh(it)) }
        val overflow = rig.nonces.issue()
        val overloaded = rig.nonces.spend(rig.fresh(overflow))
        // A replay is still a replay when the set is full: the spent record answers first.
        val replayWhileFull = rig.nonces.spend(rig.fresh(spent[0]))
        rig.advance(1.milliseconds)
        val stillOverloaded = rig.nonces.spend(rig.fresh(overflow))
        // The first entry expires at the ttl, and its slot is free from then on: one slot, not three.
        rig.advance(TTL - step * 2 - 1.milliseconds)
        val afterOne = rig.nonces.spend(rig.fresh(overflow))
        val fullAgain = rig.nonces.spend(rig.fresh(rig.nonces.issue()))
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
    fun aWallStepBackLongerThanTheTtlDrawsANewSecretAndAShorterOneDoesNot() = runTest {
        // Two instances alike in every byte, a set of one so a full set shows. One sees the wall
        // clock set back by a millisecond more than the ttl, the other by exactly the ttl.
        val longer = rig(capacity = 1)
        val shorter = rig(capacity = 1)
        val (longNonce, shortNonce) = longer.nonces.issue() to shorter.nonces.issue()
        val filled = listOf(longer.nonces.spend(longer.fresh(longNonce)), shorter.nonces.spend(shorter.fresh(shortNonce)))
        longer.stepWall(-(TTL + 1.milliseconds))
        shorter.stepWall(-TTL)
        // The time source has not moved, so the old nonce is still inside its ttl. Unknown, not
        // Fresh or Expired, is the outcome only a new secret gives.
        val longOld = longer.nonces.check(longNonce)
        val longNext = longer.nonces.spend(longer.fresh(longer.nonces.issue()))
        val shortOld = shorter.nonces.check(shortNonce)
        val shortReplay = shorter.nonces.spend(assertIs<NonceCheck.Fresh>(shortOld, "the control: the old nonce is still good"))
        val shortNext = shorter.nonces.spend(shorter.fresh(shorter.nonces.issue()))
        assertAll(
            { assertEquals(List(2) { NonceSpend.Spent }, filled, "precondition: each set is full of a live nonce") },
            { assertEquals(NonceCheck.Unknown, longOld, "longer than the ttl: the old nonce is unknown") },
            { assertEquals(NonceSpend.Spent, longNext, "and the spent set started empty") },
            { assertEquals(NonceSpend.Replayed, shortReplay, "exactly the ttl: the old secret and its spent set remain") },
            { assertEquals(NonceSpend.Overloaded, shortNext, "so the set is still full") },
        )
    }

    @Test
    fun aSleepTheTimeSourceDidNotCountDrawsANewSecret() = runTest {
        // Some platforms' monotonic time stops while the machine sleeps. Without the wall clock a
        // nonce issued before an hour's sleep would still be Fresh on waking.
        val rig = rig()
        val beforeSleep = rig.nonces.issue()
        rig.sleep(1.hours)
        val onWaking = rig.nonces.check(beforeSleep)
        val issuedAwake = rig.nonces.check(rig.nonces.issue())
        assertAll(
            { assertEquals(NonceCheck.Unknown, onWaking, "a nonce from before the sleep") },
            { assertIs<NonceCheck.Fresh>(issuedAwake, "the control: one issued after it") },
        )
    }

    @Test
    fun aWallClockSetForwardThenBackNeverReopensASpentNonce() = runTest {
        val rig = rig()
        val nonce = rig.nonces.issue()
        val first = rig.nonces.spend(rig.fresh(nonce))
        rig.stepWall(TTL * 10)
        val forward = rig.nonces.check(nonce)
        rig.stepWall(-TTL * 10)
        val back = rig.nonces.check(nonce)
        assertAll(
            { assertEquals(NonceSpend.Spent, first, "precondition: spent once") },
            { assertEquals(NonceCheck.Unknown, forward, "the wall clock set forward past its ttl") },
            { assertEquals(NonceCheck.Unknown, back, "and back to where it was: still not good again") },
        )
    }

    @Test
    fun aCheckThatStraddlesANewSecretCannotBeSpent() = runTest {
        // Spend sees the clocks disagree itself, before anything has drawn the new secret.
        val unseen = rig()
        val unseenNonce = unseen.nonces.issue()
        val unseenChecked = unseen.fresh(unseenNonce)
        unseen.stepWall(-TTL * 2)
        val unseenSpend = unseen.nonces.spend(unseenChecked)
        val unseenAfter = unseen.nonces.check(unseenNonce)
        // The new secret is already drawn, so the clocks agree again by the time of the spend:
        // only the secret the check was made under can refuse it.
        val drawn = rig()
        val drawnNonce = drawn.nonces.issue()
        val drawnChecked = drawn.fresh(drawnNonce)
        drawn.stepWall(-TTL * 2)
        val issuedUnderNew = drawn.nonces.issue()
        val drawnSpend = drawn.nonces.spend(drawnChecked)
        val newSpend = drawn.nonces.spend(drawn.fresh(issuedUnderNew))
        assertAll(
            { assertEquals(NonceSpend.Expired, unseenSpend, "checked before the step, spent after it") },
            { assertEquals(NonceCheck.Unknown, unseenAfter, "the rig: the next check drew a new secret") },
            { assertEquals(NonceSpend.Expired, drawnSpend, "checked under the old secret, spent under the new one") },
            { assertEquals(NonceSpend.Spent, newSpend, "the control: a nonce checked under the new secret") },
        )
    }

    @Test
    fun aSpentNonceIsNeverReopenedHoweverEitherClockMoves() = runTest {
        // A seeded walk: the clocks move together, the time source alone (a sleep it did count
        // while the wall clock did not), or the wall clock alone, by steps either side of the ttl.
        // Checks are spent at once or held for a later step, so some straddle a new secret.
        val tally = mutableMapOf<String, Int>()
        val violations = mutableListOf<String>()
        for (seed in 0 until SEEDS) {
            val rig = rig(seed = seed, capacity = 2)
            val walk = Random(1_000 + seed)
            val issued = mutableListOf<ByteArray>()
            val held = mutableListOf<Pair<ByteArray, NonceCheck.Fresh>>()
            val spentOnce = mutableSetOf<String>()
            fun spend(nonce: ByteArray, fresh: NonceCheck.Fresh, step: Int) {
                val verdict = rig.nonces.spend(fresh)
                tally[verdict.toString()] = (tally[verdict.toString()] ?: 0) + 1
                if (verdict == NonceSpend.Spent && !spentOnce.add(nonce.toHexString())) violations += "seed $seed step $step"
            }
            repeat(STEPS) { step ->
                when (walk.nextInt(16)) {
                    0, 1, 2 -> rig.advance(walk.nextLong(0, 300).milliseconds)
                    3 -> rig.time += walk.nextLong(0, 200).milliseconds
                    4 -> rig.stepWall(walk.nextLong(-2_500, 2_500).milliseconds)
                    5, 6, 7 -> issued += rig.nonces.issue()
                    8, 9, 10, 11, 12 -> if (issued.isNotEmpty()) {
                        // One of the last few issued, so most are still inside their ttl.
                        val nonce = issued[issued.size - 1 - walk.nextInt(minOf(issued.size, RECENT))]
                        when (val checked = rig.nonces.check(nonce)) {
                            is NonceCheck.Fresh -> if (walk.nextBoolean()) spend(nonce, checked, step) else held += nonce to checked
                            else -> tally["check $checked"] = (tally["check $checked"] ?: 0) + 1
                        }
                    }
                    else -> if (held.isNotEmpty()) held.removeAt(walk.nextInt(held.size)).let { (nonce, fresh) -> spend(nonce, fresh, step) }
                }
            }
        }
        val reached = listOf("Spent", "Replayed", "Expired", "Overloaded", "check Unknown", "check Expired")
        assertAll(
            { assertEquals(emptyList(), violations, "a nonce spent a second time") },
            *reached.map { arm -> { assertTrue((tally[arm] ?: 0) > 0, "the walk reached $arm: $tally") } }.toTypedArray(),
        )
    }

    @Test
    fun aCheckFromAnotherInstanceCannotBeSpentHere() = runTest {
        val mine = rig(seed = 1)
        val theirs = rig(seed = 2)
        val checked = theirs.fresh(theirs.nonces.issue())
        assertFailsWith<IllegalArgumentException>("their check, spent here") { mine.nonces.spend(checked) }
        assertEquals(NonceSpend.Spent, theirs.nonces.spend(checked), "the control: spent where it was checked")
    }

    @Test
    fun createRefusesATtlOrCapacityOutOfRange() = runTest {
        assertFailsWith<IllegalArgumentException>("a zero ttl") { rig(ttl = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException>("a ttl past MAX_TTL") { rig(ttl = ProofNonces.MAX_TTL + 1.milliseconds) }
        assertFailsWith<IllegalArgumentException>("a zero capacity") { rig(capacity = 0) }
        val smallest = rig(ttl = 1.milliseconds, capacity = 1).nonces
        val longest = ProofNonces.create(ttl = ProofNonces.MAX_TTL)
        assertAll(
            { assertEquals(1.milliseconds, smallest.ttl, "the control: the smallest ttl") },
            { assertEquals(ProofNonces.MAX_TTL, longest.ttl, "the control: the longest ttl, on the platform CSPRNG and clocks") },
            { assertEquals(ProofNonces.DEFAULT_CAPACITY, longest.capacity, "the default capacity") },
        )
    }

    /** An instance on a test time source and a wall clock this test moves. */
    private class Rig(val time: TestTimeSource, private val wall: WallClock, val nonces: ProofNonces) {
        /** Both clocks forward by [by]: time passing. */
        fun advance(by: Duration) {
            time += by
            wall.at += by
        }

        /** The wall clock alone, by [by], either way: a clock set by hand or by NTP. */
        fun stepWall(by: Duration) {
            wall.at += by
        }

        /** The wall clock alone, forward by [by]: a sleep the time source did not count. */
        fun sleep(by: Duration) = stepWall(by)

        suspend fun fresh(nonce: ByteArray): NonceCheck.Fresh =
            assertIs<NonceCheck.Fresh>(nonces.check(nonce), "the rig: the nonce checks fresh")
    }

    private class WallClock(var at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private suspend fun rig(seed: Int = 0, ttl: Duration = TTL, capacity: Int = ProofNonces.DEFAULT_CAPACITY): Rig {
        val time = TestTimeSource()
        val wall = WallClock(Instant.fromEpochMilliseconds(T))
        return Rig(time, wall, ProofNonces.create(Random(seed), ttl, capacity, time, wall))
    }

    private companion object {
        /** 2023-11-14T22:13:20Z: where every wall clock starts. */
        const val T = 1_700_000_000_000L
        val TTL = 1.seconds
        const val SEEDS = 8
        const val STEPS = 400
        const val RECENT = 4
    }
}
