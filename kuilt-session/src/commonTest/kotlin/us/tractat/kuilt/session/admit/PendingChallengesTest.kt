package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.AdmitMessage.Hello
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun hello(name: String) = Hello(displayName = name, sessionId = "session-$name")

    @Test
    fun openThenTakeReturnsTheEntryAndItStaysCurrent() {
        val hello = hello("alice")
        val nonce = byteArrayOf(1, 2, 3)
        val deadline = now + 30.seconds
        table.open(alice, hello, nonce, deadline)

        val pending = table.take(alice)

        assertNotNull(pending)
        assertAll(
            { assertEquals(hello, pending.hello) },
            { assertContentEquals(nonce, pending.hostNonce) },
            { assertEquals(deadline, pending.deadline) },
            // A taken challenge is a decision in flight, not a finished one: it stays current until a
            // later open, a drop or its deadline supersedes it.
            { assertTrue(table.isCurrent(alice, pending.generation)) },
            // A challenge is answered once. A second Proof for it finds nothing to take.
            { assertNull(table.take(alice)) },
        )
    }

    @Test
    fun takeAfterExpiryReturnsNull() {
        val deadline = now + 30.seconds
        table.open(alice, hello("alice"), byteArrayOf(7), deadline)

        // Precondition: one millisecond before the deadline the entry is live, so the null below is
        // the deadline's doing and not an entry that was never there.
        now = deadline - 1.milliseconds
        assertEquals(1, table.retained)

        now = deadline
        assertAll(
            { assertNull(table.take(alice)) },
            { assertEquals(0, table.retained, "the lazy check removes what it finds expired") },
        )
    }

    @Test
    fun expiredEntryIsNotCurrent() {
        val deadline = now + 30.seconds
        table.open(alice, hello("alice"), byteArrayOf(7), deadline)
        val pending = assertNotNull(table.take(alice))

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
        table.open(alice, hello("first"), byteArrayOf(1), now + 30.seconds)
        val first = assertNotNull(table.take(alice))
        // Precondition: the first generation is current before the re-open.
        assertTrue(table.isCurrent(alice, first.generation))

        table.open(alice, hello("second"), byteArrayOf(2), now + 30.seconds)
        val second = assertNotNull(table.take(alice))

        assertAll(
            { assertNotEquals(first.generation, second.generation) },
            { assertEquals(hello("second"), second.hello) },
            { assertFalse(table.isCurrent(alice, first.generation), "the re-open superseded it") },
            { assertTrue(table.isCurrent(alice, second.generation)) },
            { assertEquals(1, table.retained, "one entry per sender") },
        )
    }

    @Test
    fun reopenBeforeTakeOverwritesTheUntakenEntry() {
        table.open(alice, hello("first"), byteArrayOf(1), now + 30.seconds)
        table.open(alice, hello("second"), byteArrayOf(2), now + 30.seconds)

        val pending = assertNotNull(table.take(alice))

        assertAll(
            { assertEquals(hello("second"), pending.hello) },
            { assertContentEquals(byteArrayOf(2), pending.hostNonce) },
        )
    }

    @Test
    fun sweepOnOpenEvictsExpiredEntriesOfOtherSenders() {
        table.open(bob, hello("bob"), byteArrayOf(1), now + 10.seconds)
        table.open(carol, hello("carol"), byteArrayOf(2), now + 60.seconds)
        now += 20.seconds

        // Precondition: bob's entry has expired but is still held. Nothing has looked at it, and no
        // timer runs, so whatever removes it below is the open.
        assertEquals(2, table.retained)

        table.open(alice, hello("alice"), byteArrayOf(3), now + 30.seconds)

        assertAll(
            { assertEquals(2, table.retained, "bob swept, carol and alice kept") },
            { assertNotNull(table.take(carol), "an unexpired entry of another sender survives") },
            { assertNotNull(table.take(alice)) },
            { assertNull(table.take(bob)) },
        )
    }

    @Test
    fun takeOfUnknownSenderReturnsNull() {
        val onEmptyTable = table.take(alice)
        table.open(bob, hello("bob"), byteArrayOf(1), now + 30.seconds)

        assertAll(
            { assertNull(onEmptyTable) },
            { assertNull(table.take(alice), "another sender's entry is not alice's") },
            { assertNotNull(table.take(bob), "positive control: the entry that does exist is taken") },
        )
    }

    @Test
    fun dropRemovesTheEntryAndAReopenDoesNotRevalidateTheOldGeneration() {
        table.open(alice, hello("first"), byteArrayOf(1), now + 30.seconds)
        val first = assertNotNull(table.take(alice))
        assertTrue(table.isCurrent(alice, first.generation))

        table.drop(alice)
        val afterDrop = table.isCurrent(alice, first.generation)
        table.open(alice, hello("second"), byteArrayOf(2), now + 30.seconds)
        val afterReopen = table.isCurrent(alice, first.generation)

        assertAll(
            { assertFalse(afterDrop) },
            // Generations are unique across the table, not counted per sender, so a sender that is
            // dropped and opens again never hands a stale decision the number it was waiting on.
            { assertFalse(afterReopen) },
            { assertNull(table.take(bob), "dropping alice touched nobody else") },
        )
    }

    @Test
    fun isCurrentOfUnknownSenderIsFalse() {
        table.open(bob, hello("bob"), byteArrayOf(1), now + 30.seconds)
        val bobs = assertNotNull(table.take(bob))

        assertAll(
            { assertTrue(table.isCurrent(bob, bobs.generation), "positive control") },
            { assertFalse(table.isCurrent(alice, bobs.generation), "a generation is bound to its sender") },
        )
    }
}
