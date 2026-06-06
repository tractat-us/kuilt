package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

class DotSetTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyStoreIsBottom() {
        assertEquals(true, DotSet().isBottom)
        assertEquals(false, DotSet(setOf(Dot(a, 1L))).isBottom)
    }

    @Test
    fun joinKeepsDotsLiveInBoth() {
        val store = DotSet(setOf(Dot(a, 1L)))
        val ctx = DotContext.of(Dot(a, 1L))
        assertEquals(setOf(Dot(a, 1L)), store.join(store, ctx, ctx).dots)
    }

    @Test
    fun joinKeepsADotTheOtherSideHasNotSeen() {
        // mine has (B,1); other has neither it nor it in context -> keep
        val mine = DotSet(setOf(Dot(b, 1L)))
        val myCtx = DotContext.of(Dot(b, 1L))
        val other = DotSet(emptySet())
        val otherCtx = DotContext.EMPTY
        assertEquals(setOf(Dot(b, 1L)), mine.join(other, myCtx, otherCtx).dots)
    }

    @Test
    fun joinDropsADotTheOtherSideRemoved() {
        // other had (A,1) in context but not in store -> other removed it -> drop
        val mine = DotSet(setOf(Dot(a, 1L)))
        val myCtx = DotContext.of(Dot(a, 1L))
        val other = DotSet(emptySet())
        val otherCtx = DotContext.of(Dot(a, 1L))
        assertEquals(emptySet(), mine.join(other, myCtx, otherCtx).dots)
    }
}
