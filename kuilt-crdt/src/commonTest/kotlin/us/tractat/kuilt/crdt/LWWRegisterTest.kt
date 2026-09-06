package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import us.tractat.kuilt.test.assertAll

class LWWRegisterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyHasNoValue() {
        assertNull(LWWRegister.empty<String>().value)
    }

    @Test
    fun setThenRead() {
        assertEquals("x", LWWRegister.empty<String>().set(a, 10L, "x").value)
    }

    @Test
    fun laterTimestampWins() {
        val r1 = LWWRegister.empty<String>().set(a, 10L, "x")
        val r2 = LWWRegister.empty<String>().set(b, 20L, "y")
        assertEquals("y", r1.piece(r2).value)
        assertEquals("y", r2.piece(r1).value) // commutative
    }

    @Test
    fun tieBreaksOnReplicaIdLexicographically() {
        // Same timestamp; B > A → "y" wins.
        val r1 = LWWRegister.empty<String>().set(a, 10L, "x")
        val r2 = LWWRegister.empty<String>().set(b, 10L, "y")
        assertEquals("y", r1.piece(r2).value)
        assertEquals("y", r2.piece(r1).value)
    }

    @Test
    fun mergeIsIdempotent() {
        val r = LWWRegister.empty<String>().set(a, 10L, "x")
        assertEquals(r, r.piece(r))
    }

    @Test
    fun unsetClearsTheValue() {
        assertNull(LWWRegister.empty<String>().set(a, 10L, "x").unset(a, 20L).value)
    }

    @Test
    fun unsetVsConcurrentSet_laterTagWins() {
        val base = LWWRegister.empty<String>().set(a, 10L, "x")
        val removed = base.unset(a, 20L)
        val rewritten = base.set(b, 30L, "y")
        assertAll(
            { assertEquals("y", removed.piece(rewritten).value) },
            { assertEquals("y", rewritten.piece(removed).value) },
            { assertNull(rewritten.unset(a, 40L).piece(removed).value) },
        )
    }

    @Test
    fun unsetVsSetSameTimestamp_tieBreaksOnReplicaId() {
        val base = LWWRegister.empty<String>().set(a, 10L, "x")
        val removedByB = base.unset(b, 20L) // B > A lexicographically
        val setByA = base.set(a, 20L, "y")
        assertAll(
            { assertNull(removedByB.piece(setByA).value) },
            { assertNull(setByA.piece(removedByB).value) },
        )
    }

    @Test
    fun roundTripsThroughJson() {
        val r = LWWRegister.empty<String>().set(a, 10L, "x")
        val ser = LWWRegister.serializer(String.serializer())
        assertEquals(r, Json.decodeFromString(ser, Json.encodeToString(ser, r)))
    }

    // ── #2087: a mutator may not move its own replica DOWN the lattice ────────────

    /**
     * **`X <= m(X)`, spelled `X.piece(m(X)) == m(X)`.** If a write is inflationary, joining the
     * writer's own starting point back into the result changes nothing.
     *
     * An *assigning* [LWWRegister.set] fails it: `converged.piece(lagging)` climbs back to
     * `converged`, which is strictly **above** `lagging`, so the write landed below where it
     * started — the #2087 defect. The third arm is the vacuity guard: without it this test would
     * pass on a `set` that simply refused every write, since a no-op is trivially inflationary.
     */
    @Test
    fun aSetWhoseTagLosesDoesNotMoveTheWriterDownTheLattice() {
        val converged = LWWRegister.empty<String>().set(b, 30L, "north")
        val lagging = converged.set(a, 5L, "south")

        assertAll(
            { assertEquals(lagging, converged.piece(lagging), "X <= m(X): no join may reach above m(X)") },
            { assertEquals("north", lagging.value, "the losing write is dropped, not applied") },
            { assertEquals("south", converged.set(a, 40L, "south").value, "vacuity guard: a winning write lands") },
        )
    }

    /**
     * The same law for [LWWRegister.unset]. A tombstone is a write like any other, so an unset at a
     * losing tag must be dropped rather than hiding a value that outranks it.
     */
    @Test
    fun anUnsetWhoseTagLosesDoesNotMoveTheWriterDownTheLattice() {
        val converged = LWWRegister.empty<String>().set(b, 30L, "north")
        val lagging = converged.unset(a, 5L)

        assertAll(
            { assertEquals(lagging, converged.piece(lagging), "X <= m(X): no join may reach above m(X)") },
            { assertEquals("north", lagging.value, "the losing unset is dropped, not applied") },
            { assertNull(converged.unset(a, 40L).value, "vacuity guard: a winning unset lands") },
        )
    }

    /**
     * **The #2145 trap, pinned.** `unset` is a real tombstone, not "clear the value": on an *empty*
     * register it must still write `(timestamp, replica, null)`, because that is what beats a
     * concurrent earlier [set] arriving later. An inflationary rewrite that turned an unset at the
     * bottom into a no-op would satisfy every law above and delete this silently.
     *
     * `LWWRegisterConvergenceTest`'s `unset-high` op is declared `RETIRE` and
     * `VacuityFloorSelfTest.aBottomStateRetireIsEffectiveOnTheBindingsThatWriteATombstone` asserts
     * it is effective at the bottom; this is the same claim, at the type.
     */
    @Test
    fun unsetOnAnEmptyRegisterStillWritesATombstone() {
        val tombstone = LWWRegister.empty<String>().unset(a, 10L)
        val earlierSetFromAPeer = LWWRegister.empty<String>().set(b, 5L, "x")

        assertAll(
            { assertNotEquals(LWWRegister.empty<String>(), tombstone, "the tombstone is a write, not a no-op") },
            { assertEquals(10L, tombstone.timestamp, "…carrying the tag it was given") },
            { assertNull(tombstone.piece(earlierSetFromAPeer).value, "…so an earlier-tagged set loses to it") },
            { assertNull(earlierSetFromAPeer.piece(tombstone).value, "…in either merge order") },
        )
    }

    /**
     * **What changes at an equal tag, stated rather than left to be discovered.**
     *
     * Writing through the join makes `set` and [LWWRegister.piece] agree *by construction*: a
     * locally-written cell and the identical cell arriving off the wire now resolve the same way.
     * `piece`'s equal-tag arm is `else -> this`, so the incumbent survives — and therefore so does
     * it here. Previously the local write won and the arriving frame lost, which is two answers to
     * one question.
     *
     * This is the tag-uniqueness precondition's domain, where the contract already promises nothing:
     * `(replica, timestamp)` MUST identify one write. What changes is that the anomaly is now
     * *order-independent* rather than depending on whether the second value was written or received.
     */
    @Test
    fun aWriteAtAnEqualTagKeepsTheIncumbentJustAsAnArrivingFrameWould() {
        val incumbent = LWWRegister.empty<String>().set(a, 10L, "x")
        val arriving = LWWRegister.empty<String>().set(a, 10L, "y")

        assertAll(
            { assertEquals("x", incumbent.set(a, 10L, "y").value, "a duplicate-tag write keeps the incumbent") },
            { assertEquals("x", incumbent.piece(arriving).value, "…exactly as that cell arriving off the wire does") },
        )
    }

    /**
     * The law over a swept tag space rather than one hand-picked pair — losing writes, winning
     * writes, duplicate tags and tombstones all in the same stream.
     *
     * **The reference is built by hand, not through [LWWRegister.piece].** Spelling the law as
     * `before.piece(after) == after` would be `x == x`: with `set` defined as `piece(cell)` that
     * reduces to `piece` being idempotent and associative, which a *wrong* `piece` satisfies too, so
     * the sweep would stay green under any tie-break the type happened to have. The expected winner
     * is therefore maxed here, over `(timestamp, replica)` directly. The shadow tag is
     * **non-decreasing by construction** — only ever replaced by one that strictly dominates it —
     * and every step asserts the register equals it, which is `X <= m(X)` stated without the
     * operation under test.
     *
     * The draw indexes a `List` with a seeded [Random] and never walks a hash-ordered collection, so
     * the trajectory is identical on every target. Chains restart every six steps; otherwise the
     * shadow tag saturates at the top of the band and nothing after it can land.
     *
     * All four rig counters are load-bearing: a zero in any of them means the stream never reached
     * that case, and the arm it was meant to pin asserted nothing.
     */
    @Test
    fun setAndUnsetAreInflationaryOverASweptTagSpace() {
        val random = Random(2087)
        val replicas = listOf("A", "B", "C")
        var dropped = 0
        var landed = 0
        var duplicateTags = 0
        var tombstonesLanded = 0

        repeat(500) {
            var state = LWWRegister.empty<String>()
            // The shadow model: the winning tag and value, maxed by hand.
            var winningTimestamp = Long.MIN_VALUE
            var winningReplica = ""
            var winningValue: String? = null

            repeat(6) {
                val replica = replicas[random.nextInt(replicas.size)]
                val timestamp = random.nextLong(0L, 8L)
                val written: String? = if (random.nextBoolean()) "v-$replica-$timestamp" else null

                if (timestamp == winningTimestamp && replica == winningReplica) duplicateTags++
                val wins = timestamp > winningTimestamp ||
                    (timestamp == winningTimestamp && replica > winningReplica)
                if (wins) {
                    winningTimestamp = timestamp
                    winningReplica = replica
                    winningValue = written
                    landed++
                    if (written == null) tombstonesLanded++
                } else {
                    dropped++
                }

                state = if (written == null) {
                    state.unset(ReplicaId(replica), timestamp)
                } else {
                    state.set(ReplicaId(replica), timestamp, written)
                }

                assertEquals(winningTimestamp, state.timestamp, "tag moved off the hand-maxed winner")
                assertEquals(winningValue, state.value, "value moved off the hand-maxed winner")
            }
        }

        assertAll(
            { assertTrue(dropped > 0, "rig: no write ever lost, so the law was never at risk") },
            { assertTrue(landed > 0, "rig: no write ever landed, so the sweep asserted over no-ops only") },
            { assertTrue(duplicateTags > 0, "rig: the equal-tag arm of `piece` was never reached") },
            { assertTrue(tombstonesLanded > 0, "rig: no unset ever won, so `unset` was never swept") },
        )
    }
}
