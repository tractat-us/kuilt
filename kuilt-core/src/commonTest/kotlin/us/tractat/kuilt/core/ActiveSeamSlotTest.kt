package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit coverage for [ActiveSeamSlot] — the Torn-aware single-active guard that
 * replaces the per-fabric `check(slot == null)` + `onTerminated` side-channel.
 */
class ActiveSeamSlotTest {
    @Test
    fun claimWhileLiveThrows() {
        val slot = ActiveSeamSlot("already has an active session")
        val live = FakeSeam()
        slot.occupy { live }

        // A second claim while the occupant is live (non-Torn) is rejected.
        assertFailsWith<IllegalStateException> { slot.occupy { FakeSeam() } }
    }

    @Test
    fun claimAfterOccupantTornSucceedsSelfHealing() {
        val slot = ActiveSeamSlot()
        val first = FakeSeam()
        slot.occupy { first }

        // No explicit release — the occupant simply latches Torn (self-driven death).
        first.tear()

        // The next claim consults the occupant's OWN terminal state and treats the
        // slot as free — the structural self-healing property.
        val second = FakeSeam()
        val installed = slot.occupy { second }
        assertSame(second, installed)
    }

    @Test
    fun identityGuardedReleaseIsNoOpForStaleSeam() {
        val slot = ActiveSeamSlot()
        val live = FakeSeam()
        slot.occupy { live }

        // A stale release from an already-replaced session must not evict the
        // current live occupant.
        slot.release(FakeSeam())

        assertFailsWith<IllegalStateException>("live occupant must remain — stale release is a no-op") {
            slot.occupy { FakeSeam() }
        }
    }

    @Test
    fun releaseFreesTheSlot() {
        val slot = ActiveSeamSlot()
        val first = FakeSeam()
        slot.occupy { first }

        slot.release(first)

        // Slot is now empty — a fresh claim succeeds.
        val second = FakeSeam()
        assertSame(second, slot.occupy { second })
    }

    @Test
    fun grabAndReleaseNullsTheSlotAndReturnsTheOccupant() {
        val slot = ActiveSeamSlot()
        val first = FakeSeam()
        slot.occupy { first }

        val grabbed = slot.grabAndRelease()
        val onceEmpty = slot.grabAndRelease()

        assertAll(
            { assertSame(first, grabbed, "grabAndRelease returns the current occupant") },
            { assertNull(onceEmpty, "the slot is empty after grabAndRelease") },
        )
    }

    @Test
    fun buildFailureLeavesTheSlotFree() {
        val slot = ActiveSeamSlot()

        assertFailsWith<IllegalStateException> {
            slot.occupy<FakeSeam> { error("session open failed") }
        }

        // A build that throws must not wedge the slot — the guard never installed anything.
        val recovered = FakeSeam()
        assertSame(recovered, slot.occupy { recovered })
    }

    @Test
    fun occupyMessageUsesTheConfiguredText() {
        val slot = ActiveSeamSlot("MyFactory already has an active session")
        slot.occupy { FakeSeam() }

        val failure = assertFailsWith<IllegalStateException> { slot.occupy { FakeSeam() } }
        assertEquals("MyFactory already has an active session", failure.message)
    }

    /** A minimal [Seam] whose [state] can be flipped to [SeamState.Torn] on demand. */
    private class FakeSeam : Seam {
        private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
        override val selfId: PeerId = PeerId("self")
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
        override val state: StateFlow<SeamState> = _state
        override val incoming: Flow<Swatch> = emptyFlow()

        fun tear() {
            _state.value = SeamState.Torn(CloseReason.RemoteRequested)
        }

        override suspend fun broadcast(payload: ByteArray) = Unit

        override suspend fun sendTo(
            peer: PeerId,
            payload: ByteArray,
        ) = Unit

        override suspend fun close(reason: CloseReason) {
            _state.value = SeamState.Torn(reason)
        }
    }
}
