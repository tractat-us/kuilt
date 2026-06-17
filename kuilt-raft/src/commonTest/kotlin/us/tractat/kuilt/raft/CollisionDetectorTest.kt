package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.CollisionDetector
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollisionDetectorTest {
    @Test
    fun ownIssuedSerialIsNotForeign() {
        val d = CollisionDetector(ClientId("a-1"))
        d.issued(1); d.issued(2)
        assertFalse(d.isForeign(DedupKey(ClientId("a-1"), 2))) // my own committed entry
    }

    @Test
    fun anotherClientIdIsNeverMyCollision() {
        val d = CollisionDetector(ClientId("a-1"))
        assertFalse(d.isForeign(DedupKey(ClientId("b-1"), 99))) // someone else's id — not my concern
    }

    @Test
    fun mySerialAboveMaxIssuedIsForeign() {
        val d = CollisionDetector(ClientId("a-1"))
        d.issued(3)
        assertTrue(d.isForeign(DedupKey(ClientId("a-1"), 4))) // I never issued 4 under my own id
    }

    @Test
    fun nullKeyIsNeverForeign() {
        assertFalse(CollisionDetector(ClientId("a-1")).isForeign(null))
    }
}
