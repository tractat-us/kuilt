package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A snapshot holding values no engine could have written is [RestoreResult.Corrupt] at decode, and
 * one that decodes restores and runs (#3261). Every bound below is one the engine keeps after every
 * call: its checked arithmetic keeps each ledger total within `Long`, nothing is spent that was not
 * minted, a charge never takes an edge past what was issued across it, a spend never takes a group
 * below nothing, [LocalHeddle.apply] keeps each parent's weights in the budget, and a lease reserves
 * only what its path still holds. A bound decode adds beyond those would refuse a snapshot a real
 * engine wrote, so none is added: a lease's [Lease.reserved] above [LocalHeddle.MAX_RESERVATION] is
 * one an engine writes, after an extension, and a counter at `Long.MAX_VALUE` is one the lifetime
 * limit lets work already running reach.
 *
 * Each bound has an arm just past it, which is corrupt for that bound by name, and one exactly at
 * it, which restores and runs. Then [everyNumberInEveryGoldenAtEveryHostileValue] edits each golden
 * at every number it holds, and counts what each edit reaches.
 */
class LocalHeddleHostileSnapshotTest {

    private val policy = LocalPolicy(defaultReservation = 1L)

    private fun corruptFor(text: String, reason: String): () -> Unit = {
        val out = LocalHeddle.decode(text, Json)
        assertTrue(out is RestoreResult.Corrupt && reason in out.reason, "expected Corrupt naming \"$reason\", got $out")
    }

    private fun restoresAndRuns(text: String, name: String): () -> Unit = {
        val out = LocalHeddle.decode(text, Json)
        assertEquals(Outcome.SURVIVED, outcomeOf(name, out), "$name: decode answered $out")
    }

    /** The edit [from] → [to] of [golden], after checking [from] is there once. */
    private fun edited(golden: String, from: String, to: String): String {
        val at = golden.indexOf(from)
        check(at >= 0 && golden.indexOf(from, at + 1) < 0) { "the rig: \"$from\" is in the golden exactly once" }
        return golden.replaceFirst(from, to)
    }

    /**
     * The root's minted supply is a sum the engine keeps in `Long`. In the rich golden, taken at
     * epoch 1, a second mint from that epoch bringing the sum to `Long.MAX_VALUE` restores and
     * runs; one more unit is corrupt, as is a counter whose two writers' slots sum past `Long`,
     * which `decode` threw on as an `ArithmeticException` before (#3261).
     */
    @Test
    fun aLedgerTotalPastLongIsCorrupt() {
        val mint = "\"minted\":{\"local#0\":{\"root\":\"root\",\"holder\":\"local\",\"amount\":18}}"
        fun withSecondMint(amount: Long) =
            edited(GOLDEN_FORMAT_1_RICH, mint, mint.dropLast(1) + ",\"local#1\":{\"root\":\"root\",\"holder\":\"local\",\"amount\":$amount}}")
        val issuedB = "\"root/b@1:1#0\":{\"counts\":{\"local\":1}}"
        assertAll(
            restoresAndRuns(withSecondMint(Long.MAX_VALUE - 18), "two mints summing to Long.MAX_VALUE"),
            corruptFor(withSecondMint(Long.MAX_VALUE - 17), "leaves Long"),
            corruptFor(edited(GOLDEN_FORMAT_1, issuedB, "\"root/b@1:1#0\":{\"counts\":{\"local\":1,\"other\":${Long.MAX_VALUE}}}"), "leaves Long"),
        )
    }

    /**
     * An edge is never charged, returned and spent together, past what was issued across it. The
     * rich golden's `batch/x` edge is exactly at that bound, everything issued returned; one unit
     * spent on top is corrupt.
     */
    @Test
    fun anEdgeChargedPastItsIssuanceIsCorrupt() {
        val x = AttachmentId("root/acme/batch/x@1:1#0")
        val atBound = checkNotNull(LocalHeddle.decode(GOLDEN_FORMAT_1_RICH, Json).let { assertIs<RestoreResult.Restored>(it) }.snapshot.ledger.edge(x))
        val past = edited(GOLDEN_FORMAT_1_RICH, "\"leafSpent\":{}", "\"leafSpent\":{\"${x.value}\":{\"counts\":{\"local\":1}}}")
        assertAll(
            { assertEquals(EdgeSummary(x, issued = 4L, returned = 4L, spent = 0L), atBound, "the rig: x is at the bound, returned and spent equal to issued") },
            restoresAndRuns(GOLDEN_FORMAT_1_RICH, "the rich golden, x at the bound"),
            corruptFor(past, "past the 4 issued"),
        )
    }

    /**
     * Everything minted is still held somewhere or has been spent, so the two together never come to
     * more than was minted. The rich golden is exactly at that bound: 18 minted, 3 spent by its
     * forgotten `t2` and 15 held. A fold of 4 is corrupt. A fold of 18, which alone spends no more
     * than was minted, is corrupt too: checking the spend alone let it restore, and the engine's
     * first completion then took the spend past the mint.
     */
    @Test
    fun spendingAndHoldingMoreThanWasMintedIsCorrupt() {
        val golden = assertIs<RestoreResult.Restored>(LocalHeddle.decode(GOLDEN_FORMAT_1_RICH, Json)).snapshot.ledger
        val held = golden.allGroups().sumOf { golden.holdings(it, LOCAL) }
        val leafSpend = "\"leafSpend\":{\"local\":3}"
        fun spending(amount: Long) = edited(GOLDEN_FORMAT_1_RICH, leafSpend, "\"leafSpend\":{\"local\":$amount}")
        assertAll(
            { assertEquals(Triple(18L, 3L, 15L), Triple(golden.mintedTotal(), golden.leafSpentTotal(), held), "the rig: 18 minted, 3 spent, 15 held") },
            restoresAndRuns(GOLDEN_FORMAT_1_RICH, "the rich golden, at the bound"),
            corruptFor(spending(4L), "spent 4 and holds 15, more than the 18 ever minted"),
            corruptFor(spending(18L), "spent 18 and holds 15, more than the 18 ever minted"),
        )
    }

    /** An edge's effective spend is never below zero: a relocation out of `batch/x` with nothing spent there to move is corrupt. */
    @Test
    fun aNegativeEffectiveSpendIsCorrupt() {
        val x = "root/acme/batch/x@1:1#0"
        val past = edited(GOLDEN_FORMAT_1_RICH, "\"gauges\":{", "\"leafRelocOut\":{\"$x\":{\"counts\":{\"local\":1}}},\"gauges\":{")
        assertAll(
            { assertEquals(0L, LocalHeddle.decode(GOLDEN_FORMAT_1_RICH, Json).let { assertIs<RestoreResult.Restored>(it) }.snapshot.ledger.edge(AttachmentId(x))?.spent, "the rig: x is at the bound, nothing spent") },
            restoresAndRuns(GOLDEN_FORMAT_1_RICH, "the rich golden, x at the bound"),
            corruptFor(past, "less than nothing"),
        )
    }

    /**
     * A group never holds less than nothing. In the rich golden `tenants` holds exactly nothing:
     * what came in went on to its children, `t1` and the forgotten `t2`. One unit more in the fold of
     * its forgotten outflow is corrupt.
     */
    @Test
    fun aGroupHoldingLessThanNothingIsCorrupt() {
        val tenants = GroupId("root/tenants")
        val held = assertIs<RestoreResult.Restored>(LocalHeddle.decode(GOLDEN_FORMAT_1_RICH, Json)).snapshot.ledger.holdings(tenants, LOCAL)
        val outflow = "\"outflow\":{\"root/tenants\":{\"local\":3}}"
        assertAll(
            { assertEquals(0L, held, "the rig: tenants is at the bound, holding nothing") },
            restoresAndRuns(GOLDEN_FORMAT_1_RICH, "the rich golden, tenants at the bound"),
            corruptFor(edited(GOLDEN_FORMAT_1_RICH, outflow, "\"outflow\":{\"root/tenants\":{\"local\":4}}"), "root/tenants holds -1 for local, less than nothing"),
        )
    }

    /**
     * A parent's active children's weights fit the weight budget [LocalHeddle.apply] keeps. Beside
     * `b` at 1, `a` at the heaviest whole weight that fits restores and runs; one heavier is
     * corrupt. At `Long.MAX_VALUE - 1` a pick over it threw an `ArithmeticException` before (#3261).
     */
    @Test
    fun weightsPastTheBudgetAreCorrupt() {
        fun fits(numerator: Long) = weightsFitTheBudget(listOf(Weight.of(numerator), Weight.of(1L)))
        // The heaviest whole weight that fits beside a weight-1 sibling, by bisection on the budget itself.
        var low = 1L
        var high = Long.MAX_VALUE - 1L
        while (low < high) {
            val mid = low + (high - low + 1L) / 2L
            if (fits(mid)) low = mid else high = mid - 1L
        }
        val heaviest = low
        val weightA = "\"child\":\"root/a\",\"weight\":{\"numerator\":1,"
        fun weighing(numerator: Long) = edited(GOLDEN_FORMAT_1, weightA, "\"child\":\"root/a\",\"weight\":{\"numerator\":$numerator,")
        assertAll(
            { assertTrue(fits(heaviest) && !fits(heaviest + 1L) && heaviest > 1L, "the rig: $heaviest is the bound") },
            restoresAndRuns(weighing(heaviest), "a at the heaviest weight that fits, $heaviest"),
            corruptFor(weighing(heaviest + 1L), "do not fit the weight budget"),
            corruptFor(weighing(Long.MAX_VALUE - 1L), "do not fit the weight budget"),
        )
    }

    /**
     * The leases across an edge never reserve more than is outstanding on it. In the golden, `a`'s
     * edge has 1 outstanding and its lease reserves 1. With one more unit issued to `a` from the
     * root's spare supply, a lease reserving 2 is exactly at the bound and restores and runs; 3 is
     * corrupt. Not [LocalHeddle.MAX_RESERVATION]: [LocalHeddle.extend] adds to a lease's
     * reservation, so an engine writes a lease above it.
     */
    @Test
    fun leasesReservingMoreThanTheirEdgeHoldsAreCorrupt() {
        val a = AttachmentId("root/a@1:1#0")
        val golden = assertIs<RestoreResult.Restored>(LocalHeddle.decode(GOLDEN_FORMAT_1, Json)).snapshot
        val issuedA = "\"root/a@1:1#0\":{\"counts\":{\"local\":51}}"
        val leaseA = "\"seq\":50,\"reserved\":1,"
        fun reserving(amount: Long) =
            edited(edited(GOLDEN_FORMAT_1, issuedA, "\"root/a@1:1#0\":{\"counts\":{\"local\":52}}"), leaseA, "\"seq\":50,\"reserved\":$amount,")
        assertAll(
            { assertEquals(1L, golden.ledger.edge(a)?.outstanding, "the rig: a's edge has 1 outstanding") },
            { assertEquals(listOf(1L), golden.leases.filter { it.leaf == GroupId("root/a") }.map { it.reserved }, "the rig: a's one lease reserves it") },
            restoresAndRuns(GOLDEN_FORMAT_1, "the golden, a's lease at the bound"),
            restoresAndRuns(reserving(2L), "a lease reserving 2 of 2 outstanding"),
            corruptFor(reserving(3L), "reserve 3, more than the 2 outstanding"),
            corruptFor(edited(GOLDEN_FORMAT_1, leaseA, "\"seq\":50,\"reserved\":${Long.MAX_VALUE},"), "more than the 1 outstanding"),
        )
    }

    /**
     * Why decode caps no lease at [LocalHeddle.MAX_RESERVATION]: the cap bounds one reservation or
     * one extension, and [LocalHeddle.extend] adds to the lease. A real engine leases `a` at the cap,
     * extends it by the cap again, and persists, and what it wrote decodes, restores and runs. A
     * check refusing a lease past the cap would make this snapshot corrupt, so this is the arm that
     * reds when one is added (#3261).
     */
    @Test
    fun aLeaseAnEngineExtendedPastTheReservationCapRestores() {
        val topology = draft("root") { lane("a", Weight.ONE) }
        val a = topology.groupId("a")
        val engine = LocalHeddle.bootWhole(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        val opened = engine.admit(a, waiting = setOf(a), startedAt = 0L, reservation = LocalHeddle.MAX_RESERVATION)
        val lease = assertIs<AcquireOutcome.Acquired>(opened, "the rig: the admit leased").lease
        val extended = engine.extend(lease.id, LocalHeddle.MAX_RESERVATION, waiting = emptySet())
        val reserved = engine.snapshot().leases.single().reserved
        val text = Json.encodeToString(LocalHeddleSnapshot.serializer(), engine.persist())
        assertAll(
            { assertEquals(LeaseOutcome.Done(0L, 0L, 0L), extended, "the rig: the extension landed") },
            { assertEquals(2 * LocalHeddle.MAX_RESERVATION, reserved, "the rig: the lease reserves twice the cap") },
            { assertTrue(reserved > LocalHeddle.MAX_RESERVATION, "the rig: the lease reserves $reserved, past the cap") },
            restoresAndRuns(text, "a lease extended to $reserved, past the cap"),
        )
    }

    /**
     * [LocalHeddle.persist] runs decode's check on the engine's own books, so a bound broken there is
     * the engine's bug, and it throws [IllegalStateException] rather than the
     * [IllegalArgumentException] decode reports as corrupt bytes. The books of an engine with a lease
     * open on `a` are swapped for those of one that issued `a` nothing, so the lease reserves more
     * than its edge holds.
     */
    @Test
    fun anEngineWhoseOwnBooksBreakABoundThrowsIllegalStateFromPersist() {
        val topology = draft("root") { lane("a", Weight.ONE) }
        val a = topology.groupId("a")
        fun engine() = LocalHeddle.bootWhole(topology, policy, epoch = 1L).apply { reconcileCapacity(1L) }
        val leased = engine()
        assertIs<AcquireOutcome.Acquired>(leased.admit(a, waiting = setOf(a), startedAt = 0L, reservation = 5L), "the rig: the admit leased")
        val idle = engine().persist().ledger
        leased.reshapeForTest { idle }
        val thrown = assertFailsWith<IllegalStateException> { leased.persist() }
        assertAll(
            { assertIs<IllegalArgumentException>(thrown.cause, "the bound decode checks is what broke") },
            { assertTrue("reserve 5, more than" in thrown.message.orEmpty(), "it names the bound: ${thrown.message}") },
        )
    }

    /** What one hostile snapshot reached. */
    private enum class Outcome {
        /** Decoded as [RestoreResult.Corrupt]. */
        CORRUPT,

        /** Decoded as [RestoreResult.UnsupportedVersion]: the edit hit the version. */
        UNSUPPORTED,

        /** Decoded at epoch `Long.MAX_VALUE`, which no later epoch can restore. */
        NO_LATER_EPOCH,

        /** Decoded, but [policy] cannot size its capacity: [LocalHeddle.restore]'s documented refusal. */
        CAPACITY_REFUSED,

        /** Decoded, restored, and ran both arms of [survives] without a throw. */
        SURVIVED,
    }

    /**
     * What [decoded] reaches. A decoded snapshot that can be restored is restored twice, and each
     * engine runs one arm: an acquire then every lease completed at its reservation, or every lease
     * completed one past it, an overrun, then an acquire. Then it persists, which checks its books
     * again. Any throw fails the test, naming [name].
     */
    private fun outcomeOf(name: String, decoded: RestoreResult): Outcome = when (decoded) {
        is RestoreResult.Corrupt -> Outcome.CORRUPT
        is RestoreResult.UnsupportedVersion -> Outcome.UNSUPPORTED
        is RestoreResult.Restored -> {
            val snapshot = decoded.snapshot
            when {
                snapshot.epoch == Long.MAX_VALUE -> Outcome.NO_LATER_EPOCH
                !capacitySizes(snapshot.totalSlots) -> Outcome.CAPACITY_REFUSED
                else -> {
                    survives(name, snapshot, acquireFirst = true)
                    survives(name, snapshot, acquireFirst = false)
                    Outcome.SURVIVED
                }
            }
        }
    }

    private fun capacitySizes(totalSlots: Long): Boolean =
        try {
            Capacity.sized(totalSlots, policy)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun survives(name: String, snapshot: LocalHeddleSnapshot, acquireFirst: Boolean) {
        val arm = if (acquireFirst) "acquire, then complete at the reservation" else "complete past the reservation, then acquire"
        val groups = snapshot.ledger.allEdges().mapNotNull { snapshot.ledger.record(it)?.child }.toSet()
        try {
            val engine = LocalHeddle.restore(snapshot, policy, epoch = snapshot.epoch + 1)
            if (acquireFirst) engine.acquire(groups, groups, startedAt = 0L)
            for (lease in engine.snapshot().leases) {
                val cost = when {
                    acquireFirst -> lease.reserved
                    lease.reserved == Long.MAX_VALUE -> lease.reserved
                    else -> lease.reserved + 1L
                }
                engine.complete(lease.id, cost, waiting = groups)
            }
            if (!acquireFirst) engine.acquire(groups, groups, startedAt = 0L)
            // What it ran to is a snapshot too, so it persists: its books still balance.
            engine.persist()
        } catch (failure: Exception) {
            fail("$name: restored, then threw on \"$arm\": $failure", failure)
        }
    }

    /** Every number literal of [golden] in value position, as the index of its first character and its text. */
    private fun numbersIn(golden: String): List<Pair<Int, String>> =
        Regex("""[:\[,]-?[0-9]+(?=[,}\]])""").findAll(golden).map { it.range.first + 1 to it.value.drop(1) }.toList()

    /**
     * Every number each golden holds, replaced in turn by each of [HOSTILE]: `decode` never throws,
     * and every snapshot it restores runs. The count of each outcome is pinned per golden, so a run
     * whose edits stopped landing, or stopped reaching an outcome, reds.
     */
    @Test
    fun everyNumberInEveryGoldenAtEveryHostileValue() {
        val goldens = listOf("GOLDEN_FORMAT_1" to GOLDEN_FORMAT_1, "GOLDEN_FORMAT_1_RICH" to GOLDEN_FORMAT_1_RICH, "GOLDEN_FORMAT_1_ORIGIN" to GOLDEN_FORMAT_1_ORIGIN)
        val counts = goldens.associate { (label, golden) ->
            val reached = HashMap<Outcome, Int>()
            val numbers = numbersIn(golden)
            for ((at, literal) in numbers) for (value in HOSTILE) {
                if (value.toString() == literal) continue
                val text = golden.substring(0, at) + value + golden.substring(at + literal.length)
                val name = "$label at $at, $literal → $value"
                val decoded = try {
                    LocalHeddle.decode(text, Json)
                } catch (failure: Exception) {
                    fail("$name: decode threw $failure", failure)
                }
                val outcome = outcomeOf(name, decoded)
                reached[outcome] = (reached[outcome] ?: 0) + 1
            }
            label to (numbers.size to Outcome.entries.associateWith { reached[it] ?: 0 })
        }
        assertEquals(EXPECTED_COUNTS, counts)
    }

    private companion object {
        /** The values every number is replaced with: the ends of `Long`, the reservation cap either side, and the small edges. */
        val HOSTILE: List<Long> = listOf(
            Long.MAX_VALUE, Long.MAX_VALUE - 1L, Long.MIN_VALUE, -1L, 0L, 1L,
            LocalHeddle.MAX_RESERVATION, LocalHeddle.MAX_RESERVATION + 1L,
        )

        /** Per golden: how many numbers it holds, and how many edits reached each outcome. */
        val EXPECTED_COUNTS: Map<String, Pair<Int, Map<Outcome, Int>>> = mapOf(
            "GOLDEN_FORMAT_1" to (27 to counts(corrupt = 107, unsupported = 2, noLaterEpoch = 1, capacityRefused = 2, survived = 91)),
            "GOLDEN_FORMAT_1_RICH" to (94 to counts(corrupt = 386, unsupported = 2, noLaterEpoch = 1, capacityRefused = 2, survived = 331)),
            "GOLDEN_FORMAT_1_ORIGIN" to (35 to counts(corrupt = 141, unsupported = 2, noLaterEpoch = 1, capacityRefused = 2, survived = 121)),
        )

        fun counts(corrupt: Int, unsupported: Int, noLaterEpoch: Int, capacityRefused: Int, survived: Int): Map<Outcome, Int> = mapOf(
            Outcome.CORRUPT to corrupt,
            Outcome.UNSUPPORTED to unsupported,
            Outcome.NO_LATER_EPOCH to noLaterEpoch,
            Outcome.CAPACITY_REFUSED to capacityRefused,
            Outcome.SURVIVED to survived,
        )
    }
}
