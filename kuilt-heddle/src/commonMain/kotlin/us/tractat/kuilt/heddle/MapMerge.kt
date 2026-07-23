package us.tractat.kuilt.heddle

/**
 * Elementwise merge: for each key present in either map, combine the two values
 * (or keep the lone one).
 *
 * A verbatim local copy of `:kuilt-crdt`'s identically-named helper, which is
 * `internal` to that module and so not visible here. Promoting it to a shared
 * public utility is a separate concern; the ~8 lines are duplicated deliberately.
 */
internal inline fun <K, V> Map<K, V>.mergeValues(other: Map<K, V>, combine: (mine: V, theirs: V) -> V): Map<K, V> {
    val merged = HashMap<K, V>(this)
    for ((key, theirs) in other) {
        val mine = merged[key]
        merged[key] = if (mine == null) theirs else combine(mine, theirs)
    }
    return merged
}
