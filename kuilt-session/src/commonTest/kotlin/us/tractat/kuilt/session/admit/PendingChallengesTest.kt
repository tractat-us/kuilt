package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.TEST_KEY_A
import us.tractat.kuilt.session.admit.AdmitMessage.Hello
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PendingChallengesTest {

    private var now = Instant.fromEpochMilliseconds(1_000_000)
    private val table = PendingChallenges(clock = { now })

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")

    private fun hello(name: String) = KeyedHello(
        Hello(displayName = name, sessionId = "session-$name"),
        TEST_KEY_A,
        ByteArray(Hello.JOINER_NONCE_SIZE),
    )

    @Test
    fun openThenTakeReturnsTheEntryAndItStaysCurrent() {
        val hello = hello("alice")
        val nonce = byteArrayOf(1, 2, 3)
        val deadline = now + 30.seconds
        table.open(alice, hello, nonce, deadline, principal = null)

        val pending = table.take(alice, answering = null)

        assertNotNull(pending)
        assertAll(
            { assertSame(hello, pending.keyed) },
            { assertContentEquals(nonce, pending.hostNonce) },
            { assertEquals(deadline, pending.deadline) },
            // A taken challenge is a decision in flight, not a finished one: it stays current until a
            // later open, a drop or its deadline supersedes it.
            { assertTrue(table.isCurrent(alice, pending.generation)) },
            // A challenge is answered once. A second Proof for it finds nothing to take.
            { assertNull(table.take(alice, answering = null)) },
        )
    }

    @Test
    fun takeAfterExpiryReturnsNull() {
        val deadline = now + 30.seconds
        table.open(alice, hello("alice"), byteArrayOf(7), deadline, principal = null)

        // Precondition: one millisecond before the deadline the entry exists, so the null below is
        // the deadline's doing and not an entry that was never there.
        now = deadline - 1.milliseconds
        assertEquals(1, table.retained)

        now = deadline
        assertAll(
            { assertNull(table.take(alice, answering = null)) },
            { assertEquals(0, table.retained, "the lazy check removes what it finds expired") },
        )
    }

    @Test
    fun expiredEntryIsNotCurrent() {
        val deadline = now + 30.seconds
        table.open(alice, hello("alice"), byteArrayOf(7), deadline, principal = null)
        val pending = assertNotNull(table.take(alice, answering = null))

        now = deadline - 1.milliseconds
        val beforeDeadline = table.isCurrent(alice, pending.generation)
        now = deadline
        val atDeadline = table.isCurrent(alice, pending.generation)

        assertAll(
            { assertTrue(beforeDeadline, "positive control: current until the deadline") },
            { assertFalse(atDeadline) },
        )
    }

    @Test
    fun reopenBySameSenderBumpsTheGenerationAndSupersedesTheOld() {
        table.open(alice, hello("first"), byteArrayOf(1), now + 30.seconds, principal = null)
        val first = assertNotNull(table.take(alice, answering = null))
        // Precondition: the first generation is current before the re-open.
        assertTrue(table.isCurrent(alice, first.generation))

        table.open(alice, hello("second"), byteArrayOf(2), now + 30.seconds, principal = null)
        val second = assertNotNull(table.take(alice, answering = null))

        assertAll(
            { assertNotEquals(first.generation, second.generation) },
            { assertEquals(hello("second").hello, second.keyed.hello) },
            { assertFalse(table.isCurrent(alice, first.generation), "the re-open superseded it") },
            { assertTrue(table.isCurrent(alice, second.generation)) },
            { assertEquals(1, table.retained, "one entry per sender") },
        )
    }

    @Test
    fun reopenBeforeTakeOverwritesTheUntakenEntry() {
        table.open(alice, hello("first"), byteArrayOf(1), now + 30.seconds, principal = null)
        table.open(alice, hello("second"), byteArrayOf(2), now + 30.seconds, principal = null)

        val pending = assertNotNull(table.take(alice, answering = null))

        assertAll(
            { assertEquals(hello("second").hello, pending.keyed.hello) },
            { assertContentEquals(byteArrayOf(2), pending.hostNonce) },
        )
    }

    @Test
    fun sweepOnOpenEvictsExpiredEntriesOfOtherSenders() {
        table.open(bob, hello("bob"), byteArrayOf(1), now + 10.seconds, principal = null)
        table.open(carol, hello("carol"), byteArrayOf(2), now + 60.seconds, principal = null)
        now += 20.seconds

        // Precondition: bob's entry has expired but is still held. Nothing has looked at it, and no
        // timer runs, so whatever removes it below is the open.
        assertEquals(2, table.retained)

        table.open(alice, hello("alice"), byteArrayOf(3), now + 30.seconds, principal = null)

        assertAll(
            { assertEquals(2, table.retained, "bob swept, carol and alice kept") },
            { assertNotNull(table.take(carol, answering = null), "an unexpired entry of another sender survives") },
            { assertNotNull(table.take(alice, answering = null)) },
            { assertNull(table.take(bob, answering = null)) },
        )
    }

    @Test
    fun takeOfUnknownSenderReturnsNull() {
        val onEmptyTable = table.take(alice, answering = null)
        table.open(bob, hello("bob"), byteArrayOf(1), now + 30.seconds, principal = null)

        assertAll(
            { assertNull(onEmptyTable) },
            { assertNull(table.take(alice, answering = null), "another sender's entry is not alice's") },
            { assertNotNull(table.take(bob, answering = null), "positive control: the entry that does exist is taken") },
        )
    }

    @Test
    fun dropRemovesTheEntryAndAReopenDoesNotRevalidateTheOldGeneration() {
        table.open(alice, hello("first"), byteArrayOf(1), now + 30.seconds, principal = null)
        table.open(bob, hello("bob"), byteArrayOf(9), now + 30.seconds, principal = null)
        val first = assertNotNull(table.take(alice, answering = null))
        assertTrue(table.isCurrent(alice, first.generation))

        table.drop(alice)
        val afterDrop = table.isCurrent(alice, first.generation)
        table.open(alice, hello("second"), byteArrayOf(2), now + 30.seconds, principal = null)
        val afterReopen = table.isCurrent(alice, first.generation)

        assertAll(
            { assertFalse(afterDrop) },
            // Generations are unique across the table, not counted per sender, so a sender that is
            // dropped and opens again never hands a stale decision the number it was waiting on.
            { assertFalse(afterReopen) },
            { assertNotNull(table.take(bob, answering = null), "dropping alice touched nobody else") },
        )
    }

    @Test
    fun retireOnTheCurrentGenerationRemovesTheEntry() {
        table.open(alice, hello("alice"), byteArrayOf(1), now + 30.seconds, principal = null)
        val pending = assertNotNull(table.take(alice, answering = null))
        // Precondition: the entry is held and current, so a `true` below is a removal, not a no-op.
        assertAll(
            { assertEquals(1, table.retained) },
            { assertTrue(table.isCurrent(alice, pending.generation)) },
        )

        val retired = table.retire(alice, pending.generation)

        assertAll(
            { assertTrue(retired) },
            { assertEquals(0, table.retained) },
            { assertFalse(table.isCurrent(alice, pending.generation)) },
        )
    }

    @Test
    fun retireOnASupersededGenerationLeavesTheNewerEntryTakeable() {
        // The race retire exists for: a decision on generation 1 finishes after a re-Hello opened
        // generation 2. Retiring generation 1 must not cost the joiner its second challenge.
        table.open(alice, hello("first"), byteArrayOf(1), now + 30.seconds, principal = null)
        val first = assertNotNull(table.take(alice, answering = null))
        table.open(alice, hello("second"), byteArrayOf(2), now + 30.seconds, principal = null)

        val retired = table.retire(alice, first.generation)
        val second = table.take(alice, answering = null)

        assertAll(
            { assertFalse(retired) },
            { assertNotNull(second, "generation 2 survives the stale retire") },
            { assertEquals(hello("second").hello, second?.keyed?.hello) },
        )
    }

    @Test
    fun retireOnAnExpiredGenerationReturnsFalse() {
        val deadline = now + 30.seconds
        table.open(alice, hello("alice"), byteArrayOf(1), deadline, principal = null)
        val pending = assertNotNull(table.take(alice, answering = null))
        now = deadline
        // Precondition: the expired entry is still held, so the false below is about expiry.
        assertEquals(1, table.retained)

        assertAll(
            // A decision that outlived its deadline is stale: the joiner has already timed out.
            { assertFalse(table.retire(alice, pending.generation)) },
            { assertEquals(0, table.retained, "the expired entry is removed all the same") },
        )
    }

    @Test
    fun openCopiesTheHostNonce() {
        val nonce = byteArrayOf(1, 2, 3)
        table.open(alice, hello("alice"), nonce, now + 30.seconds, principal = null)
        nonce[0] = 99

        val pending = assertNotNull(table.take(alice, answering = null))

        assertAll(
            { assertEquals(99, nonce[0], "precondition: the caller's array was mutated") },
            { assertContentEquals(byteArrayOf(1, 2, 3), pending.hostNonce) },
        )
    }

    @Test
    fun isCurrentOfUnknownSenderIsFalse() {
        table.open(bob, hello("bob"), byteArrayOf(1), now + 30.seconds, principal = null)
        val bobs = assertNotNull(table.take(bob, answering = null))

        assertAll(
            { assertTrue(table.isCurrent(bob, bobs.generation), "positive control") },
            { assertFalse(table.isCurrent(alice, bobs.generation), "a generation is bound to its sender") },
        )
    }

    /** #3610: a drop keyed to a mark removes what was open then and keeps what was opened after. */
    @Test
    fun dropOpenedByKeepsAnEntryOpenedAfterTheMark() {
        val before = table.mark()
        val older = table.open(alice, hello("alice"), byteArrayOf(1), now + 30.seconds, principal = null)
        val mark = table.mark()
        val newer = table.open(alice, hello("alice"), byteArrayOf(2), now + 30.seconds, principal = null)
        table.open(bob, hello("bob"), byteArrayOf(3), now + 30.seconds, principal = null)

        table.dropOpenedBy(alice, mark)
        val aliceKept = table.isCurrent(alice, newer)
        table.dropOpenedBy(bob, mark)
        val bobKept = table.retained == 2
        table.dropOpenedBy(alice, table.mark())

        assertAll(
            { assertEquals(0L, before, "no generation is minted before the first open") },
            { assertEquals(older, mark, "the mark is the newest generation minted") },
            { assertTrue(aliceKept, "alice's entry opened after the mark survives a drop at that mark") },
            { assertTrue(bobKept, "bob's entry, opened after the mark too, survives") },
            { assertFalse(table.isCurrent(alice, newer), "a drop at a later mark removes it") },
            { assertEquals(1, table.retained, "and only alice's") },
        )
    }

    /** The route watcher's order (#3610): an entry opened while the read runs is above the mark. */
    @Test
    fun markBeforeMarksAheadOfTheRead() {
        val before = table.open(alice, hello("alice"), byteArrayOf(1), now + 30.seconds, principal = null)
        val (mark, during) = table.markBefore { table.open(alice, hello("alice"), byteArrayOf(2), now + 30.seconds, principal = null) }

        table.dropOpenedBy(alice, mark)

        assertAll(
            { assertEquals(before, mark, "the mark was taken before the read opened anything") },
            { assertTrue(table.isCurrent(alice, during), "the entry opened during the read survives a drop at the mark") },
        )
    }

    @Test
    fun dropOpenedByRemovesAnEntryOpenedAtTheMark() {
        val generation = table.open(alice, hello("alice"), byteArrayOf(1), now + 30.seconds, principal = null)
        assertTrue(table.isCurrent(alice, generation), "precondition: the entry is open")

        table.dropOpenedBy(alice, table.mark())

        assertAll(
            { assertFalse(table.isCurrent(alice, generation)) },
            { assertEquals(0, table.retained) },
        )
    }
}
