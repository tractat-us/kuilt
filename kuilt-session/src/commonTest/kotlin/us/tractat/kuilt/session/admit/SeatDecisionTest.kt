package us.tractat.kuilt.session.admit

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The key → seat table as a pure function: one test per row of the spec's table, the cases where two
 * rows meet (Decided 23), and the whole truth table at once.
 *
 * Keys A and B are the two W3C CCG P-256 `did:key` vectors, parsed through the public entry point, so
 * each is a real key on every target rather than a string that merely looks like one. A bystander
 * holds a third, generated key C, so no fixture puts two seats under one key unless it means to.
 */
class SeatDecisionTest {

    private val p1 = PeerId("p1")
    private val p2 = PeerId("p2")
    private val p3 = PeerId("p3")

    private fun live(key: DidKey) = Seat(key, SeatLiveness.Connected)

    private fun away(key: DidKey) = Seat(key, SeatLiveness.Partitioned)

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
    fun newIdSameKeyRestoresUnderNewId() = seatTest { a, b, _ ->
        assertAll(
            {
                assertEquals(
                    SeatDecision.RestoreUnderNewId(previous = p1),
                    decideSeat(p3, a, held = mapOf(p1 to live(a), p2 to live(b)), holdsValidGrant = false),
                )
            },
            {
                assertEquals(
                    SeatDecision.RestoreUnderNewId(previous = p1),
                    decideSeat(p3, a, held = mapOf(p1 to away(a), p2 to live(b)), holdsValidGrant = false),
                )
            },
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
                    SeatDecision.ReleaseAndAdmit(released = p1, admission = SeatDecision.Ordinary),
                    decideSeat(p1, a, held = mapOf(p1 to away(b)), holdsValidGrant = false),
                )
            },
            {
                assertEquals(
                    SeatDecision.ReleaseAndAdmit(released = p1, admission = SeatDecision.Returning),
                    decideSeat(p1, a, held = mapOf(p1 to away(b)), holdsValidGrant = true),
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
        )
        val checks = helds.flatMap { held ->
            listOf(false, true).map { grant ->
                {
                    val decision = decideSeat(p1, a, held, grant)
                    assertEquals(
                        SeatDecision.ReleaseAndAdmit(released = p1, admission = decideSeat(p1, a, held - p1, grant) as SeatDecision.NewMember),
                        decision,
                        "held=$held grant=$grant",
                    )
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
    fun idConflictBeatsKeyMatchElsewhereWhateverTheLiveness() = seatTest { a, b, _ ->
        // p1 is held by key B; the joiner proved key A, which holds p2. Releasing p1 would leave key A
        // holding two seats, and restoring p2 would reward a joiner that lied about which seat it is
        // (Decided 23). Every liveness pair, the grant both ways, and both map orders.
        val seats = listOf(::live, ::away)
        val checks = seats.flatMap { idSeat ->
            seats.flatMap { keySeat ->
                val forward = mapOf(p1 to idSeat(b), p2 to keySeat(a))
                val reversed = mapOf(p2 to keySeat(a), p1 to idSeat(b))
                listOf(forward, reversed).flatMap { held ->
                    listOf(false, true).map { grant ->
                        { assertEquals(SeatDecision.KeyConflict, decideSeat(p1, a, held, grant), "held=$held grant=$grant") }
                    }
                }
            }
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun validGrantNeverOverridesAHeldSeat() = seatTest { a, b, _ ->
        assertAll(
            { assertEquals(SeatDecision.Restore(p1), decideSeat(p1, a, held = mapOf(p1 to live(a)), holdsValidGrant = true)) },
            {
                assertEquals(
                    SeatDecision.RestoreUnderNewId(previous = p1),
                    decideSeat(p2, a, held = mapOf(p1 to live(a)), holdsValidGrant = true),
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
            Triple(mapOf(p3 to live(c), p2 to live(a)), false, "restore under new id, previous p2"),
            Triple(mapOf(p3 to live(c), p2 to live(a)), true, "restore under new id, previous p2"),
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
            Triple(mapOf(p3 to live(c), p1 to away(b), p2 to live(a)), false, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to away(b), p2 to live(a)), true, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to away(b), p2 to away(a)), false, "key conflict"),
            Triple(mapOf(p3 to live(c), p1 to away(b), p2 to away(a)), true, "key conflict"),
        )
        val actual = rows.map { (held, grant, _) -> label(decideSeat(p1, a, held, grant)) }
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
            // A new id names the key's seat with the lowest id, in either order.
            { assertEquals(SeatDecision.RestoreUnderNewId(previous = p1), decideSeat(p3, a, both, holdsValidGrant = false)) },
            { assertEquals(SeatDecision.RestoreUnderNewId(previous = p1), decideSeat(p3, a, bothReversed, holdsValidGrant = true)) },
            // The claimed id held under another key still conflicts.
            { assertEquals(SeatDecision.KeyConflict, decideSeat(p3, a, both + (p3 to away(b)), holdsValidGrant = false)) },
            // Two seats under someone else's key are no business of this decision.
            { assertEquals(SeatDecision.Ordinary, decideSeat(p3, a, mapOf(p1 to live(c), p2 to live(c)), holdsValidGrant = false)) },
        )
    }

    private fun label(decision: SeatDecision): String = when (decision) {
        is SeatDecision.Restore -> "restore ${decision.peerId.value}"
        is SeatDecision.RestoreUnderNewId -> "restore under new id, previous ${decision.previous.value}"
        is SeatDecision.ReleaseAndAdmit -> "release ${decision.released.value}, then ${label(decision.admission)}"
        SeatDecision.Returning -> "returning"
        SeatDecision.KeyConflict -> "key conflict"
        SeatDecision.Ordinary -> "ordinary"
    }
}
