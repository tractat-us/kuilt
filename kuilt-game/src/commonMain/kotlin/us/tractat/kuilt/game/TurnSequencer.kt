@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.RaftNode

private val DEFAULT_FORMAT: BinaryFormat = Cbor

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
 * No Raft concepts (terms, log entries, snapshot machinery, raw byte commands)
 * are visible through this API.
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
 * // On any node — propose the local player's move (forwarded to the leader if needed):
 * val indexed = sequencer.propose(Move(player = 0, card = 3))
 * println("Move committed at index ${indexed.index}")
 * ```
 *
 * @param node The backing [RaftNode]. Lifetime is owned by the caller; this
 *   facade does not close [node] when done.
 * @param serializer The [KSerializer] used to encode actions to bytes for
 *   Raft replication and to decode committed bytes back to [A].
 * @param format The [BinaryFormat] used to encode and decode actions. This
 *   becomes the single source of truth for the wire encoding of every log
 *   entry, so any replay layer (e.g. snapshot / log scan) must use the same
 *   [format] instance to produce byte-identical payloads. Defaults to a
 *   shared CBOR instance.
 *
 * **Note:** [A] is invariant because it appears in both input position
 * (`propose(action: A)`) and output position ([committed]). Only [IndexedAction]
 * is covariant (`out A`) since it is a pure output carrier.
 */
public class TurnSequencer<A>(
    private val node: RaftNode,
    private val serializer: KSerializer<A>,
    private val format: BinaryFormat = DEFAULT_FORMAT,
) {
    /**
     * A hot [Flow] of committed [IndexedAction]s in index order, emitted on
     * every node in the cluster. Snapshot installs and internal Raft entries
     * are excluded — only application actions appear here.
     *
     * **Replay=0.** Late collectors miss entries emitted before they subscribed.
     *
     * **No-compaction assumption.** This flow currently drops [Committed.Install]
     * (snapshot installs) and therefore assumes log compaction is off (the default
     * for new clusters). Surfacing install events as a facade event is deferred.
     *
     * **Multicast semantics.** The backing [RaftNode.committed] on a real node is
     * a `MutableSharedFlow`, so multiple collectors share one upstream subscription.
     * Do NOT wrap this property in `shareIn` — doing so would add an unnecessary
     * extra layer of buffering and change cancellation behaviour. If [FakeRaftNode]
     * is used in tests, note that its `committed` is backed by a `Channel.receiveAsFlow()`
     * (single-collection), so two concurrent collectors of this flow against a fake
     * node will race for entries rather than both receiving every entry.
     */
    public val committed: Flow<IndexedAction<A>> = node.committed
        .filterIsInstance<Committed.Entry>()
        .map { (entry) -> IndexedAction(entry.index, decode(entry.command)) }

    /**
     * Proposes [action] for replication and suspends until a quorum commits it.
     *
     * Callable from any node: the leader appends directly; a follower or candidate
     * forwards the proposal to the current leader and awaits commit there. Suspends
     * cancellably if no leader is known yet.
     *
     * Returns the [IndexedAction] carrying [action] and its assigned log index.
     * The returned object re-wraps the caller's [action] directly rather than
     * decoding the committed bytes — this avoids a redundant serialization
     * round-trip since the action is already in hand.
     *
     * @throws [LeadershipLostException] if a forwarded proposal is rejected because
     *   the leader stepped down mid-flight. The caller may retry.
     */
    public suspend fun propose(action: A): IndexedAction<A> {
        val entry = node.propose(encode(action))
        // Re-wrap the caller's action object (avoids a redundant decode round-trip).
        return IndexedAction(entry.index, action)
    }

    private fun encode(action: A): ByteArray = format.encodeToByteArray(serializer, action)

    private fun decode(bytes: ByteArray): A = format.decodeFromByteArray(serializer, bytes)
}
