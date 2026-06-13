package us.tractat.kuilt.game

/**
 * An application action that has been committed to the replicated log at [index].
 *
 * @param index 1-based monotonically increasing position in the committed log.
 *   Two nodes in the same cluster assign the same [index] to the same [action].
 * @param action The deserialized application action.
 */
public data class IndexedAction<out A>(val index: Long, val action: A)
