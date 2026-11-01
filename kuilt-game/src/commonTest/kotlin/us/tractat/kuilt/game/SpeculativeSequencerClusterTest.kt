@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.raft.test.raftSimTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SpeculativeSequencer] on every node of a real cluster, so commits come from the engine's own
 * serials and forwarding rather than a script. The in-memory tests choose the interleaving; this one
 * checks that what they assume about a commit holds when an engine produced it.
 */
class SpeculativeSequencerClusterTest {

    /** 0 and 1 exclude each other: whichever commits second is refused on every node's fold. */
    private val game = object : SpeculativeGame<List<Int>, Int> {
        override fun apply(state: List<Int>, action: Int): List<Int> {
            check(!(action == 0 && 1 in state || action == 1 && 0 in state)) { "$action is excluded" }
            return state + action
        }

        override fun snapshot(state: List<Int>): List<Int> = state
        override fun restore(snapshot: List<Int>): List<Int> = snapshot
    }

    @Test
    fun everyNodeConvergesOnEqualValuesAndARejectedCommit() = raftSimTest(n = 3) { sim ->
        val sequencers = sim.nodeIds.map { id ->
            SpeculativeSequencer(
                sequencer = TurnSequencer(sim.nodes.getValue(id), Int.serializer()),
                game = game,
                initialState = emptyList(),
                scope = backgroundScope,
            )
        }
        sim.settle()
        sim.awaitLeader()

        // Every node proposes the same value twice, and its own index once: nodes 0 and 1 race the
        // two actions that exclude each other.
        val calls = sequencers.flatMapIndexed { node, sequencer ->
            listOf(7, 7, node).map { value -> launch { sequencer.propose(value) } }
        }

        sim.awaitTrue("every proposal returned") { calls.all { it.isCompleted } }
        sim.awaitTrue("every node folded all nine commits") {
            sequencers.all { it.confirmedCount.value == calls.size }
        }

        assertEquals(listOf(0, 0, 0), sequencers.map { it.pendingCount })
        val states = sequencers.map { it.speculativeState.value }
        assertEquals(1, states.distinct().size, "every node must agree: $states")
        val state = states.first()
        assertEquals(6, state.count { it == 7 }, "equal values from different nodes are each applied: $state")
        assertTrue(2 in state, "the third node's action landed: $state")
        assertEquals(1, state.count { it == 0 || it == 1 }, "rig: one of the excluded pair was refused: $state")
    }
}
