@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.Flow
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
 * Hides most Raft machinery — terms, log entries, election no-ops — and exposes only the concepts a
 * turn-based game needs:
 *
 * - **[propose]** — submit a typed action; suspends until a quorum commits it
 *   and returns the [IndexedAction] with the assigned position in the log.
 * - **[events]** — a [Flow] of [TurnEvent]s in order, on every node (leader and followers alike):
 *   committed actions decoded from Raft's opaque bytes ([TurnEvent.Committed]) and, when log
 *   compaction is enabled, snapshot installs ([TurnEvent.Reset]).
 *
 * The one Raft concept that necessarily surfaces is the snapshot install, as [TurnEvent.Reset] —
 * a consumer that enables compaction must reset its state machine, so the event cannot be hidden.
 * Terms, log indices as raw entries, and the §5.4.2 no-op stay invisible.
 *
 * ```kotlin
 * val sequencer = TurnSequencer(node, Move.serializer())
 * val table = ClientSessionTable()
 *
 * // On every node — drive the state machine off the turn-event stream:
 * scope.launch {
 *     sequencer.events.collect { event ->
 *         when (event) {
 *             is TurnEvent.Committed -> {
 *                 val (index, move, key) = event.indexed
 *                 if (table.shouldApply(key)) applyMove(index, move) // exactly-once
 *             }
 *             is TurnEvent.Reset -> resetStateMachine(event.snapshot)
 *         }
 *     }
 * }
 *
 * // On any node — propose the local player's move (forwarded to the leader if needed):
 * val indexed = sequencer.propose(Move(player = 0, card = 3))
 * println("Move committed at index ${indexed.index}")
 *
 * // A durable client replays the same requestId after a crash to get cross-crash exactly-once:
 * sequencer.propose(Move(player = 0, card = 3), requestId = nextSerial)
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
 * (`propose(action: A)`) and output position ([events]). Only [IndexedAction] and [TurnEvent]
 * are covariant (`out A`) since they are pure output carriers.
 */
public class TurnSequencer<A>(
    private val node: RaftNode,
    private val serializer: KSerializer<A>,
    private val format: BinaryFormat = DEFAULT_FORMAT,
) {
    /**
     * A hot [Flow] of [TurnEvent]s in index order, emitted on every node in the cluster.
     *
     * Each [Committed.Entry] surfaces as [TurnEvent.Committed] carrying the decoded action, its log
     * index, and the proposer-stamped [IndexedAction.dedupKey] (always present — fold it through a
     * [us.tractat.kuilt.raft.ClientSessionTable] for exactly-once). Each [Committed.Install] surfaces
     * as [TurnEvent.Reset] carrying the raft [us.tractat.kuilt.raft.Snapshot] directly. The internal
     * §5.4.2 election no-op never appears. Both event kinds share this one stream so a reset always
     * arrives in order relative to the entries around it.
     *
     * **Replay=0.** Late collectors miss events emitted before they subscribed.
     *
     * **Multicast semantics.** The backing [RaftNode.committed] on a real node is a
     * `MutableSharedFlow`, so multiple collectors share one upstream subscription. Do NOT wrap this
     * property in `shareIn` — doing so would add an unnecessary extra layer of buffering and change
     * cancellation behaviour. If `FakeRaftNode` is used in tests, note that its `committed` is backed
     * by a `Channel.receiveAsFlow()` (single-collection), so two concurrent collectors of this flow
     * against a fake node will race for events rather than both receiving every event.
     */
    public val events: Flow<TurnEvent<A>> = node.committed.map { committed ->
        when (committed) {
            is Committed.Entry -> {
                val entry = committed.entry
                TurnEvent.Committed(
                    IndexedAction(
                        index = entry.index,
                        action = decode(entry.command),
                        dedupKey = checkNotNull(entry.dedupKey) {
                            "committed application entry at index ${entry.index} carries no dedupKey — " +
                                "every entry from a current engine is stamped; a null key is vestigial " +
                                "legacy data this facade does not support"
                        },
                    ),
                )
            }
            is Committed.Install -> TurnEvent.Reset(committed.snapshot)
        }
    }

    /**
     * Proposes [action] for replication and suspends until a quorum commits it, drawing the next
     * per-node monotonic serial as the dedup [requestId].
     *
     * Callable from any node: the leader appends directly; a follower or candidate
     * forwards the proposal to the current leader and awaits commit there. Suspends
     * cancellably if no leader is known yet.
     *
     * Returns the [IndexedAction] carrying [action], its assigned log index, and the stamped
     * [IndexedAction.dedupKey]. The returned object re-wraps the caller's [action] directly rather
     * than decoding the committed bytes — this avoids a redundant serialization round-trip since the
     * action is already in hand.
     *
     * Use the [propose] (action, requestId) overload instead when the caller is a **durable** client
     * that needs cross-crash exactly-once (replay the same `requestId` on a post-crash retry).
     *
     * @throws [LeadershipLostException] if a forwarded proposal is rejected because
     *   the leader stepped down mid-flight. The caller may retry.
     */
    public suspend fun propose(action: A): IndexedAction<A> {
        val entry = node.propose(encode(action))
        // Re-wrap the caller's action object (avoids a redundant decode round-trip).
        return IndexedAction(entry.index, action, checkNotNull(entry.dedupKey) { MISSING_KEY })
    }

    /**
     * Proposes [action] with a caller-pinned [requestId] (Raft §8 client serial) under the backing
     * [RaftNode]'s `clientId`, then suspends until a quorum commits it (same semantics as [propose]).
     *
     * This is the exactly-once propose path for a **durable** client: replay the *same* [requestId]
     * on a post-crash retry and the consumer's [us.tractat.kuilt.raft.ClientSessionTable] skips the
     * serial it has already applied. The client supplies its stable identity at bootstrap
     * (`gameNode`/`gameHost`/`gameJoin`/`gameSpectate`'s `identity` parameter); [requestId] must be a
     * per-client monotonic serial the caller owns — do not pass a log index or a random value.
     *
     * @throws [LeadershipLostException] if a forwarded proposal is rejected because the leader
     *   stepped down mid-flight. The caller may retry with the same [requestId].
     */
    public suspend fun propose(action: A, requestId: Long): IndexedAction<A> {
        val entry = node.propose(encode(action), requestId)
        return IndexedAction(entry.index, action, checkNotNull(entry.dedupKey) { MISSING_KEY })
    }

    private fun encode(action: A): ByteArray = format.encodeToByteArray(serializer, action)

    private fun decode(bytes: ByteArray): A = format.decodeFromByteArray(serializer, bytes)

    private companion object {
        private const val MISSING_KEY =
            "propose returned a committed entry with no dedupKey — a current engine always stamps one"
    }
}
