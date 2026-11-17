package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins [LWWMap.tagOf] and [LWWMap.without], the two entry points a writer that owns one key's cell
 * needs (#3307): reading where its own write stands, and joining a peer's state without letting the
 * peer's copy of that cell in.
 */
@OptIn(CrdtInternalApi::class)
class LWWMapOwnCellTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val serializer = LWWMap.serializer(String.serializer(), Long.serializer())

    private fun LWWMap<String, Long>.with(replica: ReplicaId, timestamp: Long, key: String, value: Long) =
        piece(set(replica, timestamp, key, value).delta)

    @Test
    fun tagOfReadsTheTimestampOfEachCellATombstoneIncluded() {
        val map = LWWMap.empty<String, Long>()
            .with(a, 7L, "a", 3L)
            .with(b, 9L, "b", 4L)
            .let { it.piece(it.remove(b, 12L, "gone").delta) }
        assertAll(
            { assertEquals(7L, map.tagOf("a")) },
            { assertEquals(9L, map.tagOf("b")) },
            { assertEquals(12L, map.tagOf("gone")) },
            { assertNull(map.tagOf("never")) },
        )
    }

    @Test
    fun withoutLeavesNoCellSoTheMapIsOneThatNeverHeldTheKey() {
        val held = LWWMap.empty<String, Long>().with(a, 5L, "a", 3L).with(b, 6L, "b", 4L)
        val never = LWWMap.empty<String, Long>().with(b, 6L, "b", 4L)
        val dropped = held.without("a")
        assertAll(
            // The rig: the key was there before.
            { assertEquals(5L, held.tagOf("a")) },
            { assertNull(dropped.tagOf("a")) },
            { assertEquals(never, dropped) },
            { assertEquals(Json.encodeToString(serializer, never), Json.encodeToString(serializer, dropped)) },
        )
    }

    @Test
    fun aJoinAfterWithoutTakesTheOtherSidesCellBackSoItIsNoTombstone() {
        val mine = LWWMap.empty<String, Long>().with(a, 5L, "a", 3L)
        val old = LWWMap.empty<String, Long>().with(a, 2L, "a", 10L)
        assertAll(
            { assertEquals(10L, mine.without("a").piece(old)["a"]) },
            { assertEquals(2L, mine.without("a").piece(old).tagOf("a")) },
        )
    }

    @Test
    fun withoutOfAnAbsentKeyIsTheSameMap() {
        val map = LWWMap.empty<String, Long>().with(a, 5L, "a", 3L)
        assertSame(map, map.without("never"))
    }
}
