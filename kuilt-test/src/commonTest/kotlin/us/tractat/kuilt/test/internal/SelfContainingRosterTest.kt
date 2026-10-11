package us.tractat.kuilt.test.internal

import kotlinx.coroutines.flow.MutableStateFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SelfContainingRosterTest {
    private val self = PeerId("self")
    private val remote = PeerId("remote")
    private val other = PeerId("other")

    @Test
    fun constructionRejectsRostersMissingSelf() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { SelfContainingRoster(self, emptySet()) } },
            { assertFailsWith<IllegalArgumentException> { SelfContainingRoster(self, setOf(remote)) } },
        )
    }

    @Test
    fun constructorOwnsItsRoster() {
        val input = mutableSetOf(self, remote)
        val roster = SelfContainingRoster(self, input)
        input.remove(self)
        input.add(other)
        assertEquals(setOf(self, remote), roster.peers.value)
    }

    @Test
    fun removalRejectsSelfWithoutPublishing() {
        val roster = SelfContainingRoster(self, setOf(self, remote))
        assertAll(
            { assertFailsWith<IllegalArgumentException> { roster.remove(self) } },
            { assertEquals(setOf(self, remote), roster.peers.value) },
        )
    }

    @Test
    fun replacementRejectsMissingSelfWithoutPublishing() {
        val roster = SelfContainingRoster(self, setOf(self, remote))
        assertAll(
            { assertFailsWith<IllegalArgumentException> { roster.replace(emptySet()) } },
            { assertEquals(setOf(self, remote), roster.peers.value) },
            { assertFailsWith<IllegalArgumentException> { roster.replace(setOf(other)) } },
            { assertEquals(setOf(self, remote), roster.peers.value) },
        )
    }

    @Test
    fun replacementOwnsItsRosterAndLeavesEarlierSnapshotsAlone() {
        val roster = SelfContainingRoster(self, setOf(self, remote))
        val before = roster.peers.value
        val input = mutableSetOf(self, other)
        roster.replace(input)
        input.clear()
        assertAll(
            { assertEquals(setOf(self, other), roster.peers.value) },
            { assertEquals(setOf(self, remote), before) },
        )
    }

    @Test
    fun membershipChangesPreserveSnapshotsAndAllowNoOps() {
        val roster = SelfContainingRoster(self, setOf(self))
        roster.add(remote)
        val withRemote = roster.peers.value
        roster.add(self)
        roster.remove(other)
        assertEquals(setOf(self, remote), roster.peers.value)
        roster.remove(remote)
        assertAll(
            { assertEquals(setOf(self), roster.peers.value) },
            { assertEquals(setOf(self, remote), withRemote) },
        )
    }

    @Test
    fun collapsePublishesOnlySelfAndIsIdempotent() {
        val roster = SelfContainingRoster(self, setOf(self, remote, other))
        roster.collapseToSelf()
        assertEquals(setOf(self), roster.peers.value)
        roster.collapseToSelf()
        assertEquals(setOf(self), roster.peers.value)
    }

    @Test
    fun exposedFlowIsReadOnly() {
        val roster = SelfContainingRoster(self, setOf(self))
        assertFalse(roster.peers is MutableStateFlow<*>, "the owner must not receive the mutable flow")
    }
}
