package us.tractat.kuilt.session.admit

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/**
 * The key → seat table as a pure function: one test per row of the spec's table, the cases where two
 * rows meet (Decided 23, as Decided 30 amends it), and the whole truth table at once.
 *
 * Keys A and B are the two W3C CCG P-256 `did:key` vectors, parsed through the public entry point, so
 * each is a real key on every target rather than a string that merely looks like one. A bystander
 * holds a third, generated key C, so no fixture puts two seats under one key unless it means to.
 */
class SeatDecisionTest {

    private val p1 = PeerId("p1")
    private val p2 = PeerId("p2")
    private val p3 = PeerId("p3")

    private fun live(key: DidKey, heardAt: Long = 0) = Seat(key, SeatLiveness.Connected, Instant.fromEpochMilliseconds(heardAt))

    private fun away(key: DidKey, heardAt: Long = 0) = Seat(key, SeatLiveness.Partitioned, Instant.fromEpochMilliseconds(heardAt))

    private fun seatTest(body: (a: DidKey, b: DidKey, c: DidKey) -> Unit) = runTest {
        val a = DidKey.parse("did:key:zDnaerx9CtbPJ1q36T5Ln5wYt3MQYeGRG5ehnPAmxcf5mDZpv")
        val b = DidKey.parse("did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")
        val c = DeviceKey.generate().id
        // Every "different key" case below rests on this.
        assertAll(
            { assertNotEquals(a, b) },
            { assertNotEquals(a, c) },
            { assertNotEquals(b, c) },
        )
        body(a, b, c)
    }

    @Test
    fun sameIdSameKeyRestores() = seatTest { a, b, _ ->
        // Connected or partitioned, the same membership comes back.
        assertAll(
            { assertEquals(SeatDecision.Restore(p1), decideSeat(p1, a, held = mapOf(p1 to live(a), p2 to live(b)), holdsValidGrant = false)) },
            { assertEquals(SeatDecision.Restore(p1), decideSeat(p1, a, held = mapOf(p1 to away(a), p2 to live(b)), holdsValidGrant = false)) },
        )
    }

    @Test
    fun newIdSameKeyRestoresAPartitionedSeatUnderTheNewId() = seatTest { a, b, _ ->
        assertAll(
            {
                assertEquals(
                    SeatDecision.RestoreUnderNewId(previous = p1),
                    decideSeat(p3, a, held = mapOf(p1 to away(a), p2 to live(b)), holdsValidGrant = false),
                )
            },
            {
                assertEquals(
                    SeatDecision.RestoreUnderNewId(previous = p1),
                    decideSeat(p3, a, held = mapOf(p1 to away(a), p2 to live(b)), holdsValidGrant = true),
                )
            },
        )
    }

    @Test
    fun newIdSameKeyWithOnlyConnectedSeatsGetsAnAdditionalSeat() = seatTest { a, b, _ ->
        // Decided 30: live tabs of one browser coexist, so a connected seat is never superseded.
        assertAll(
            { assertEquals(SeatDecision.AdditionalSeat, decideSeat(p3, a, held = mapOf(p1 to live(a), p2 to live(b)), holdsValidGrant = false)) },
            { assertEquals(SeatDecision.AdditionalSeat, decideSeat(p3, a, held = mapOf(p1 to live(a), p2 to live(a)), holdsValidGrant = true)) },
        )
    }

    @Test
    fun noSeatValidGrantReturns() = seatTest { a, _, c ->
        // A seat held by someone else is in the table, so "no seat" is about this id and this key.
        assertEquals(
            SeatDecision.Returning,
            decideSeat(p1, a, held = mapOf(p2 to live(c)), holdsValidGrant = true),
        )
    }

    @Test
    fun sameIdDifferentKeyConflictsWhileTheSeatIsConnected() = seatTest { a, b, _ ->
        assertAll(
            { assertEquals(SeatDecision.KeyConflict, decideSeat(p1, a, held = mapOf(p1 to live(b)), holdsValidGrant = false)) },
            { assertEquals(SeatDecision.KeyConflict, decideSeat(p1, a, held = mapOf(p1 to live(b)), holdsValidGrant = true)) },
        )
    }

    @Test
    fun sameIdDifferentKeyReleasesAPartitionedSeat() = seatTest { a, b, _ ->
        // Decided 18: a device whose key store was cleared, and whose peer id is durable, gets back in
        // as a new member. Decided 23: with a valid grant that new member is returning.
        assertAll(
            {
                assertEquals(
                    listOf(SeatDecision.ReleaseAndAdmit(released = p1), SeatDecision.Ordinary),
                    settle(p1, a, held = mapOf(p1 to away(b)), holdsValidGrant = false),
                )
            },
            {
                assertEquals(
                    listOf(SeatDecision.ReleaseAndAdmit(released = p1), SeatDecision.Returning),
                    settle(p1, a, held = mapOf(p1 to away(b)), holdsValidGrant = true),
                )
            },
        )
    }

    @Test
    fun aReleasedSeatIsDecidedAgainWithoutIt() = seatTest { a, b, c ->
        // Decided 23's "decide again without the seat", stated as the caller would compute it.
        val helds = listOf(
            mapOf(p1 to away(b)),
            mapOf(p1 to away(b), p3 to live(c)),
            mapOf(p1 to away(b), p3 to away(c)),
            // The proven key holding a seat of its own is what Decided 30 changed: no conflict.
            mapOf(p1 to away(b), p2 to live(a)),
            mapOf(p1 to away(b), p2 to away(a)),
        )
        val checks = helds.flatMap { held ->
            listOf(false, true).map { grant ->
                {
                    val again = settle(p1, a, held, grant)
                    assertEquals(SeatDecision.ReleaseAndAdmit(released = p1), again.first(), "held=$held grant=$grant")
                    assertIs<SeatDecision.FreshId>(again.last(), "held=$held grant=$grant")
                    assertEquals(decideSeat(p1, a, held - p1, grant), again.last(), "held=$held grant=$grant")
                }
            }
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun noSeatNoGrantIsOrdinary() = seatTest { a, b, _ ->
        assertAll(
            { assertEquals(SeatDecision.Ordinary, decideSeat(p1, a, held = emptyMap(), holdsValidGrant = false)) },
            { assertEquals(SeatDecision.Ordinary, decideSeat(p1, a, held = mapOf(p2 to live(b)), holdsValidGrant = false)) },
        )
    }

    @Test
    fun aConnectedIdSeatUnderAnotherKeyConflictsWhateverTheKeyHolds() = seatTest { a, b, _ ->
        // p1 is held, connected, by key B; the joiner proved key A, which may hold p2 too. A different
        // device is claiming a present member's id (Decided 18), whatever else key A holds. Every
        // liveness of p2, the grant both ways, and both map orders.
        val seats = listOf(::live, ::away)
        val checks = seats.flatMap { keySeat ->
            val forward = mapOf(p1 to live(b), p2 to keySeat(a, 0))
            val reversed = mapOf(p2 to keySeat(a, 0), p1 to live(b))
            listOf(forward, reversed).flatMap { held ->
                listOf(false, true).map { grant ->
                    { assertEquals(SeatDecision.KeyConflict, decideSeat(p1, a, held, grant), "held=$held grant=$grant") }
                }
            }
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun aPartitionedIdSeatUnderAnotherKeyIsReleasedEvenWhenTheKeyHoldsASeat() = seatTest { a, b, _ ->
        // Decided 23's terminal "one key, two seats" conflict lost its premise to Decided 30: one key
        // may hold many seats. So the id's partitioned seat is released and key A decided again.
        assertAll(
            {
                assertEquals(
                    listOf(SeatDecision.ReleaseAndAdmit(released = p1), SeatDecision.AdditionalSeat),
                    settle(p1, a, held = mapOf(p1 to away(b), p2 to live(a)), holdsValidGrant = false),
                )
            },
            {
                assertEquals(
                    listOf(SeatDecision.ReleaseAndAdmit(released = p1), SeatDecision.RestoreUnderNewId(previous = p2)),
                    settle(p1, a, held = mapOf(p2 to away(a), p1 to away(b)), holdsValidGrant = true),
                )
            },
        )
    }

    @Test
    fun severalPartitionedSeatsRestoreTheOneHeardFromMostRecently() = seatTest { a, _, c ->
        // Decided 30: the most recently connected first. p2 was heard from later than p1, so a new id
        // restores p2 however the map is ordered, and a connected seat of the key never competes.
        val held = mapOf(p1 to away(a, heardAt = 100), p2 to away(a, heardAt = 200), PeerId("p4") to live(a, heardAt = 900))
        val reversed = held.entries.reversed().associate { it.key to it.value }
        val tied = mapOf(p2 to away(a, heardAt = 100), p1 to away(a, heardAt = 100))
        assertAll(
            { assertEquals(SeatDecision.RestoreUnderNewId(previous = p2), decideSeat(p3, a, held, holdsValidGrant = false)) },
            { assertEquals(SeatDecision.RestoreUnderNewId(previous = p2), decideSeat(p3, a, reversed, holdsValidGrant = false)) },
            // A tie goes to the lowest peer id, in either order, so the answer is still a function.
            { assertEquals(SeatDecision.RestoreUnderNewId(previous = p1), decideSeat(p3, a, tied, holdsValidGrant = false)) },
            { assertEquals(SeatDecision.Ordinary, decideSeat(p3, a, mapOf(p1 to away(c, heardAt = 999)), holdsValidGrant = false)) },
        )
    }

    @Test
    fun validGrantNeverOverridesAHeldSeat() = seatTest { a, b, _ ->
        assertAll(
            { assertEquals(SeatDecision.Restore(p1), decideSeat(p1, a, held = mapOf(p1 to live(a)), holdsValidGrant = true)) },
            { assertEquals(SeatDecision.AdditionalSeat, decideSeat(p2, a, held = mapOf(p1 to live(a)), holdsValidGrant = true)) },
            {
                assertEquals(
                    SeatDecision.RestoreUnderNewId(previous = p1),
                    decideSeat(p2, a, held = mapOf(p1 to away(a)), holdsValidGrant = true),
                )
            },
            { assertEquals(SeatDecision.KeyConflict, decideSeat(p1, a, held = mapOf(p1 to live(b)), holdsValidGrant = true)) },
        )
    }

    /**
     * Every combination of the inputs that matter — what the claimed id holds and how live it is,
     * whether the proven key holds another seat and how live that is, and the grant — against
     * expectations written out by hand. Two seats under one key are left to their own tests.
     *
     * [label]'s `when` has no `else`, so a new [SeatDecision] variant does not compile here until this
     * table says what it should be.
     */
    @Test
    fun everyCombinationOfTheInputsReachesItsRow() = seatTest { a, b, c ->
        // The claimed id is always p1 and the proven key always A. A bystander p3 holds key C in every
        // row, so "no seat" is about this id and this key, never an empty table.
        val rows = listOf(
            Triple(mapOf(p3 to live(c)), false, "ordinary"),
            Triple(mapOf(p3 to live(c)), true, "returning"),
            Triple(mapOf(p3 to live(c), p2 to live(a)), false, "additional seat"),
            Triple(mapOf(p3 to live(c), p2 to live(a)), true, "additional seat"),
            Triple(mapOf(p3 to live(c), p2 to away(a)), false, "restore under new id, previous p2"),
            Triple(mapOf(p3 to live(c), p2 to away(a)), true, "restore under new id, previous p2"),
            Triple(mapOf(p3 to live(c), p1 to live(a)), false, "restore p1"),
            Triple(mapOf(p3 to live(c), p1 to live(a)), true, "restore p1"),
            Triple(mapOf(p3 to live(c), p1 to away(a)), false, "restore p1"),
            Triple(mapOf(p3 to live(c), p1 to away(a)), true, "restore p1"),
            Triple(mapOf(p3 to live(c), p1 to live(b)), false, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to live(b)), true, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to live(b), p2 to live(a)), false, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to live(b), p2 to live(a)), true, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to live(b), p2 to away(a)), false, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to live(b), p2 to away(a)), true, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to away(b)), false, "release p1, then ordinary"),
            Triple(mapOf(p3 to live(c), p1 to away(b)), true, "release p1, then returning"),
            Triple(mapOf(p3 to live(c), p1 to away(b), p2 to live(a)), false, "release p1, then additional seat"),
            Triple(mapOf(p3 to live(c), p1 to away(b), p2 to live(a)), true, "release p1, then additional seat"),
            Triple(mapOf(p3 to live(c), p1 to away(b), p2 to away(a)), false, "release p1, then restore under new id, previous p2"),
            Triple(mapOf(p3 to live(c), p1 to away(b), p2 to away(a)), true, "release p1, then restore under new id, previous p2"),
        )
        val actual = rows.map { (held, grant, _) -> settle(p1, a, held, grant).joinToString(", then ") { label(it) } }
        assertEquals(rows.map { it.third }, actual)
    }

    /**
     * Two seats under one key is reachable — two tabs of one device both decided `Ordinary` before
     * either was added — and the Proof handler must not throw on it, since an escaping throw there
     * aborts a Kotlin/Native host. So the decision is still a decision, and the same one whichever
     * order the map hands the seats back in.
     */
    @Test
    fun twoSeatsUnderOneKeyStillDecide() = seatTest { a, b, c ->
        val both = mapOf(p1 to live(a), p2 to away(a))
        val bothReversed = mapOf(p2 to away(a), p1 to live(a))
        assertAll(
            // Assert the rig: both fixtures really do hold two seats under key A.
            { assertEquals(2, both.values.count { it.key == a }) },
            { assertEquals(2, bothReversed.values.count { it.key == a }) },
            { assertEquals(SeatDecision.Restore(p2), decideSeat(p2, a, both, holdsValidGrant = false)) },
            { assertEquals(SeatDecision.Restore(p2), decideSeat(p2, a, bothReversed, holdsValidGrant = false)) },
            // A new id names the key's partitioned seat, never its connected one, in either order.
            { assertEquals(SeatDecision.RestoreUnderNewId(previous = p2), decideSeat(p3, a, both, holdsValidGrant = false)) },
            { assertEquals(SeatDecision.RestoreUnderNewId(previous = p2), decideSeat(p3, a, bothReversed, holdsValidGrant = true)) },
            // The claimed id held, connected, under another key still conflicts.
            { assertEquals(SeatDecision.KeyConflict, decideSeat(p3, a, both + (p3 to live(b)), holdsValidGrant = false)) },
            // Two seats under someone else's key are no business of this decision.
            { assertEquals(SeatDecision.Ordinary, decideSeat(p3, a, mapOf(p1 to live(c), p2 to live(c)), holdsValidGrant = false)) },
        )
    }

    /**
     * What the host acts on, as `SeamRoom.settleSeat` reaches it: [decideSeat], and after a release
     * the decision again on the seats that remain.
     */
    private fun settle(claimedId: PeerId, key: DidKey, held: Map<PeerId, Seat>, holdsValidGrant: Boolean): List<SeatDecision> {
        val first = decideSeat(claimedId, key, held, holdsValidGrant)
        return if (first is SeatDecision.ReleaseAndAdmit) {
            listOf(first, decideSeat(claimedId, key, held - first.released, holdsValidGrant))
        } else {
            listOf(first)
        }
    }

    private fun label(decision: SeatDecision): String = when (decision) {
        is SeatDecision.Restore -> "restore ${decision.peerId.value}"
        is SeatDecision.RestoreUnderNewId -> "restore under new id, previous ${decision.previous.value}"
        is SeatDecision.ReleaseAndAdmit -> "release ${decision.released.value}"
        SeatDecision.AdditionalSeat -> "additional seat"
        SeatDecision.Returning -> "returning"
        SeatDecision.KeyConflict -> "key conflict"
        SeatDecision.Ordinary -> "ordinary"
    }
}
