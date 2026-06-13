@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.RaftNode

/**
 * A game-developer-friendly facade over [RaftNode].
 *
 * Hides all Raft machinery — terms, log entries, snapshot installs, election
 * no-ops — and exposes only the concepts a turn-based game needs:
 *
 * - **[propose]** — submit a typed action; suspends until a quorum commits it
 *   and returns the [IndexedAction] with the assigned position in the log.
 * - **[committed]** — a [Flow] of every committed [IndexedAction] in order,
 *   on every node (leader and followers alike), decoded from Raft's opaque bytes.
 *
 * No Raft concepts (terms, [us.tractat.kuilt.raft.LogEntry], snapshot machinery,
 * raw byte commands) are visible through this API.
 *
 * ```kotlin
 * val sequencer = TurnSequencer(node, Move.serializer())
 *
 * // On every node — collect the committed turn stream:
 * scope.launch {
 *     sequencer.committed.collect { (index, move) ->
 *         applyMove(index, move)
 *     }
 * }
 *
 * // On the leader — propose the local player's move:
 * val indexed = sequencer.propose(Move(player = 0, card = 3))
 * println("Move committed at index ${indexed.index}")
 * ```
 *
 * @param node The backing [RaftNode]. Lifetime is owned by the caller; this
 *   facade does not close [node] when done.
 * @param serializer The [KSerializer] used to encode actions to bytes for
 *   Raft replication and to decode committed bytes back to [A].
 */
public class TurnSequencer<A>(
    private val node: RaftNode,
    private val serializer: KSerializer<A>,
) {
    /**
     * A hot [Flow] of committed [IndexedAction]s in index order, emitted on
     * every node in the cluster. Snapshot installs and internal Raft entries
     * are excluded — only application actions appear here.
     *
     * Replay=0; late collectors miss entries emitted before they subscribed.
     */
    public val committed: Flow<IndexedAction<A>> = node.committed
        .filterIsInstance<Committed.Entry>()
        .map { (entry) -> IndexedAction(entry.index, decode(entry.command)) }

    /**
     * Proposes [action] for replication and suspends until a quorum commits it.
     *
     * Returns the [IndexedAction] carrying [action] and its assigned log index.
     *
     * @throws us.tractat.kuilt.raft.NotLeaderException if this node is not the leader.
     * @throws us.tractat.kuilt.raft.LeadershipLostException if leadership is lost mid-flight.
     */
    public suspend fun propose(action: A): IndexedAction<A> {
        val entry = node.propose(encode(action))
        return IndexedAction(entry.index, action)
    }

    private fun encode(action: A): ByteArray = Cbor.encodeToByteArray(serializer, action)

    private fun decode(bytes: ByteArray): A = Cbor.decodeFromByteArray(serializer, bytes)
}
