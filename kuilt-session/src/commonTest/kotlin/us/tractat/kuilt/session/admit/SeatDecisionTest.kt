package us.tractat.kuilt.session.admit

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The key → seat table as a pure function: one test per row of the spec's table, the two precedence
 * cases the rows leave open, and the whole truth table at once.
 *
 * Keys are the two W3C CCG P-256 `did:key` vectors, parsed through the public entry point, so each is
 * a real key on every target rather than a string that merely looks like one.
 */
class SeatDecisionTest {

    private val p1 = PeerId("p1")
    private val p2 = PeerId("p2")
    private val p3 = PeerId("p3")

    private fun live(key: DidKey) = Seat(key, SeatLiveness.Connected)

    private fun seatTest(body: (a: DidKey, b: DidKey) -> Unit) = runTest {
        val a = DidKey.parse("did:key:zDnaerx9CtbPJ1q36T5Ln5wYt3MQYeGRG5ehnPAmxcf5mDZpv")
        val b = DidKey.parse("did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")
        // Every "different key" case below rests on this.
        assertNotEquals(a, b)
        body(a, b)
    }

    @Test
    fun sameIdSameKeyRestores() = seatTest { a, b ->
        assertEquals(
            SeatDecision.Restore(p1),
            decideSeat(p1, a, held = mapOf(p1 to live(a), p2 to live(b)), holdsValidGrant = false),
        )
    }

    @Test
    fun newIdSameKeyRestoresUnderNewId() = seatTest { a, b ->
        assertEquals(
            SeatDecision.RestoreUnderNewId(previous = p1),
            decideSeat(p3, a, held = mapOf(p1 to live(a), p2 to live(b)), holdsValidGrant = false),
        )
    }

    @Test
    fun noSeatValidGrantReturns() = seatTest { a, b ->
        // A seat held by someone else is in the table, so "no seat" is about this id and this key.
        assertEquals(
            SeatDecision.Returning,
            decideSeat(p1, a, held = mapOf(p2 to live(b)), holdsValidGrant = true),
        )
    }

    @Test
    fun sameIdDifferentKeyConflicts() = seatTest { a, b ->
        assertEquals(
            SeatDecision.KeyConflict,
            decideSeat(p1, a, held = mapOf(p1 to live(b)), holdsValidGrant = false),
        )
    }

    @Test
    fun noSeatNoGrantIsOrdinary() = seatTest { a, b ->
        assertAll(
            { assertEquals(SeatDecision.Ordinary, decideSeat(p1, a, held = emptyMap(), holdsValidGrant = false)) },
            { assertEquals(SeatDecision.Ordinary, decideSeat(p1, a, held = mapOf(p2 to live(b)), holdsValidGrant = false)) },
        )
    }

    @Test
    fun idConflictBeatsKeyMatchElsewhere() = seatTest { a, b ->
        // p1 is held by key B; the joiner proved key A, which holds p2. Restoring p2 would reward a
        // joiner that lied about which seat it is. Both orders, so the verdict is not a scan order.
        val forward = mapOf(p1 to live(b), p2 to live(a))
        assertAll(
            { assertEquals(SeatDecision.KeyConflict, decideSeat(p1, a, held = forward, holdsValidGrant = false)) },
            { assertEquals(SeatDecision.KeyConflict, decideSeat(p1, a, held = forward.entries.reversed().associate { it.toPair() }, holdsValidGrant = false)) },
            { assertEquals(SeatDecision.KeyConflict, decideSeat(p1, a, held = forward, holdsValidGrant = true)) },
        )
    }

    @Test
    fun validGrantNeverOverridesAHeldSeat() = seatTest { a, b ->
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
     * Every combination of the three inputs that matter — what the claimed id holds, whether the
     * proven key holds another seat, and the grant — against expectations written out by hand.
     *
     * [label]'s `when` has no `else`, so a new [SeatDecision] variant does not compile here until this
     * table says what it should be.
     */
    @Test
    fun everyCombinationOfTheInputsReachesItsRow() = seatTest { a, b ->
        // The claimed id is always p1. What p1 holds, and whether key A holds p2.
        val idHoldsSameKey = mapOf(p1 to live(a))
        val idHoldsOtherKey = mapOf(p1 to live(b))
        val idHoldsOtherKeyAndKeyHoldsP2 = mapOf(p1 to live(b), p2 to live(a))
        val keyHoldsP2 = mapOf(p2 to live(a))
        val bystanderOnly = mapOf(p3 to live(b))
        val nothing = emptyMap<PeerId, Seat>()

        val rows = listOf(
            Triple(idHoldsSameKey, false, "restore p1"),
            Triple(idHoldsSameKey, true, "restore p1"),
            Triple(idHoldsOtherKey, false, "key conflict"),
            Triple(idHoldsOtherKey, true, "key conflict"),
            Triple(idHoldsOtherKeyAndKeyHoldsP2, false, "key conflict"),
            Triple(idHoldsOtherKeyAndKeyHoldsP2, true, "key conflict"),
            Triple(keyHoldsP2, false, "restore under new id, previous p2"),
            Triple(keyHoldsP2, true, "restore under new id, previous p2"),
            Triple(bystanderOnly, false, "ordinary"),
            Triple(bystanderOnly, true, "returning"),
            Triple(nothing, false, "ordinary"),
            Triple(nothing, true, "returning"),
        )
        val actual = rows.map { (held, grant, _) -> label(decideSeat(p1, a, held, grant)) }
        assertEquals(rows.map { it.third }, actual)
    }

    @Test
    fun twoSeatsSharingAKeyAreRefused() = seatTest { a, _ ->
        assertFailsWith<IllegalArgumentException> {
            decideSeat(p3, a, held = mapOf(p1 to live(a), p2 to live(a)), holdsValidGrant = false)
        }
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
