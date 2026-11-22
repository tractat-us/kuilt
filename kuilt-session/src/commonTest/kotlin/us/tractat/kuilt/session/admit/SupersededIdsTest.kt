@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The host's bounded memory of superseded peer ids (#3159, Task 1.7, inherited obligation 3). */
class SupersededIdsTest {
    private val key = fakeDidKey(1)

    @Test
    fun aSupersededIdIsAnsweredWithItsSuccessorForItsOwnKeyOnly() {
        val ids = SupersededIds(capacity = 4)
        ids.record(PeerId("old"), key, successor = PeerId("new"))
        assertAll(
            { assertEquals(PeerId("new"), ids.successorFor(PeerId("old"), key)) },
            { assertNull(ids.successorFor(PeerId("old"), fakeDidKey(2)), "another key under that id is another device") },
            { assertNull(ids.successorFor(PeerId("new"), key), "the successor itself was never superseded") },
        )
    }

    @Test
    fun theRecordIsBoundedAndForgetsTheOldestFirst() {
        val ids = SupersededIds(capacity = 3)
        (1..5).forEach { ids.record(PeerId("old-$it"), key, successor = PeerId("new-$it")) }
        assertAll(
            { assertEquals(3, ids.size, "never more than its capacity") },
            { assertNull(ids.successorFor(PeerId("old-1"), key)) },
            { assertNull(ids.successorFor(PeerId("old-2"), key)) },
            { assertEquals(PeerId("new-3"), ids.successorFor(PeerId("old-3"), key)) },
            { assertEquals(PeerId("new-5"), ids.successorFor(PeerId("old-5"), key)) },
        )
    }

    @Test
    fun anIdSeatedAgainIsForgotten() {
        val ids = SupersededIds(capacity = 3)
        ids.record(PeerId("old"), key, successor = PeerId("new"))
        ids.forget(PeerId("old"))
        assertAll(
            { assertNull(ids.successorFor(PeerId("old"), key)) },
            { assertEquals(0, ids.size) },
        )
    }

    @Test
    fun aSuccessorSupersededInTurnPointsItsPredecessorsOnward() {
        // old → mid, then mid → new: a Hello from old is told the membership's current id.
        val ids = SupersededIds(capacity = 3)
        ids.record(PeerId("old"), key, successor = PeerId("mid"))
        ids.record(PeerId("mid"), key, successor = PeerId("new"))
        assertEquals(PeerId("new"), ids.successorFor(PeerId("old"), key))
    }
}
