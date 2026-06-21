package us.tractat.kuilt.game

import us.tractat.kuilt.raft.DedupKey

/**
 * An application action that has been committed to the replicated log at [index].
 *
 * @param index 1-based monotonically increasing position in the committed log.
 *   Two nodes in the same cluster assign the same [index] to the same [action].
 * @param action The deserialized application action.
 * @param dedupKey The Raft §8 client-serial dedup identity stamped onto this entry by its
 *   proposer. **Always present** — every committed application entry is stamped, including the
 *   auto-serial [TurnSequencer.propose] path. Fold it through a
 *   [us.tractat.kuilt.raft.ClientSessionTable] in the apply loop to preserve exactly-once across
 *   forwarding and reconnect: skip the entry when
 *   [us.tractat.kuilt.raft.ClientSessionTable.shouldApply] returns `false`.
 */
public data class IndexedAction<out A>(val index: Long, val action: A, val dedupKey: DedupKey)
