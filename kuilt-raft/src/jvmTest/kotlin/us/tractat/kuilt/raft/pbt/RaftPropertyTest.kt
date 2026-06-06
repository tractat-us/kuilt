@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft.pbt

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.state.ActionChain
import net.jqwik.api.state.ActionChainArbitrary

/**
 * Property-based stateful tests for the Raft consensus implementation.
 *
 * Uses jqwik's [ActionChain] API to generate random sequences of cluster events
 * (leader elections, crashes, restarts, proposals, partitions) and verify that
 * Raft safety invariants hold after each action:
 *   - Election Safety: at most one leader per term.
 *   - State Machine Safety: no two nodes commit different commands at the same index.
 *
 * All timing is virtual — [RaftModel.scheduler] drives a [TestCoroutineScheduler]
 * so every delay is instant. 200 tries × 20 actions run in seconds, not minutes.
 */
internal class RaftPropertyTest {

    @Property(tries = 200)
    fun `3-node cluster invariants hold under random actions`(
        @ForAll("threeNodeChain") chain: ActionChain<RaftModel>,
    ) {
        val model = chain.run()
        model.cancel()
    }

    @Property(tries = 200)
    fun `5-node cluster invariants hold under random actions`(
        @ForAll("fiveNodeChain") chain: ActionChain<RaftModel>,
    ) {
        val model = chain.run()
        model.cancel()
    }

    @Provide("threeNodeChain")
    fun threeNodeChain(): ActionChainArbitrary<RaftModel> =
        ActionChain.startWith { RaftModel(3) }
            .withAction(3, ElectLeaderAction)
            .withAction(2, CrashLeaderAction)
            .withAction(2, RestartNodeAction(3))
            .withAction(2, ProposeAction(byteArrayOf(1, 2, 3)))
            .withAction(1, PartitionAction)
            .withAction(2, HealAction)
            .withMaxTransformations(20)

    @Provide("fiveNodeChain")
    fun fiveNodeChain(): ActionChainArbitrary<RaftModel> =
        ActionChain.startWith { RaftModel(5) }
            .withAction(3, ElectLeaderAction)
            .withAction(2, CrashLeaderAction)
            .withAction(2, RestartNodeAction(5))
            .withAction(2, ProposeAction(byteArrayOf(42)))
            .withAction(1, PartitionAction)
            .withAction(2, HealAction)
            .withMaxTransformations(20)
}
