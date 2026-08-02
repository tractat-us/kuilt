package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Canonical-encoding cover for [MovableTree.compactedDots] (#1957).
 *
 * The `:kuilt-conformance` convergence suite cannot reach this field: its operation generators
 * never call [MovableTree.compact], so `compactedDots` is always empty and an empty set encodes
 * canonically on every target. A *compacted* tree is the state that diverges — `piece` merges the
 * two sides with `Set.plus`, whose `LinkedHashSet` result is in merge order.
 *
 * Mutation-checked: dropping the `@Serializable(with = CanonicalSetSerializer::class)` annotation
 * on `compactedDots` makes [sameCompactedStateEncodesIdenticallyUnderEitherMergeOrder] fail with
 * `compactedDots` reversed and every other field byte-identical.
 */
internal class MovableTreeCompactedDotsCanonicalTest {

    private val serializer = MovableTree.serializer(String.serializer())

    /**
     * A tree owned by [replica] with a compaction applied, so `compactedDots` holds exactly the
     * dot of the superseded `ts=3` move.
     */
    private fun compactedTreeFor(replica: ReplicaId, tag: String): MovableTree<String> {
        val (afterFirst, first) =
            MovableTree.empty<String>().addNode(replica, ts = 1L, parent = MovableTree.ROOT_ID, value = "${tag}1")
        val (afterSecond, second) =
            afterFirst.addNode(replica, ts = 2L, parent = MovableTree.ROOT_ID, value = "${tag}2")
        val (afterMove, _) = afterSecond.move(replica, ts = 3L, node = first, newParent = second)
        val (afterSupersede, _) = afterMove.move(replica, ts = 4L, node = first, newParent = MovableTree.ROOT_ID)

        val cut = VersionVector.of(mapOf(replica to 4L))
        val (compacted, _) = afterSupersede.compact(stableCut = cut, frontierMax = cut, delivered = cut)
            ?: error("compact() must succeed for $replica — the ts=3 move is stable and superseded")
        return compacted
    }

    @Test
    fun sameCompactedStateEncodesIdenticallyUnderEitherMergeOrder() {
        val alice = compactedTreeFor(ReplicaId("alice"), "a")
        val bob = compactedTreeFor(ReplicaId("bob"), "b")

        val aliceFirst = Json.encodeToString(serializer, alice.piece(bob))
        val bobFirst = Json.encodeToString(serializer, bob.piece(alice))

        assertTrue(
            aliceFirst.contains("""{"replica":"alice","seq":3}"""),
            "the probe is vacuous unless compactedDots is actually populated: $aliceFirst",
        )
        assertEquals(aliceFirst, bobFirst, "merge order must not change a compacted tree's encoding")
    }
}
