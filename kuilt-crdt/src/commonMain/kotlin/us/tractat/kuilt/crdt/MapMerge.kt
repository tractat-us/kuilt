package us.tractat.kuilt.crdt

/** Elementwise merge: for each key present in either map, combine the two values (or keep the lone one). */
internal inline fun <K, V> Map<K, V>.mergeValues(other: Map<K, V>, combine: (mine: V, theirs: V) -> V): Map<K, V> {
    val merged = HashMap<K, V>(this)
    for ((key, theirs) in other) {
        val mine = merged[key]
        merged[key] = if (mine == null) theirs else combine(mine, theirs)
    }
    return merged
}

/** Elementwise max-merge for comparable values. Short-circuits when either side is empty. */
internal fun <K, V : Comparable<V>> Map<K, V>.mergeMax(other: Map<K, V>): Map<K, V> {
    if (isEmpty()) return other
    if (other.isEmpty()) return this
    val merged = HashMap<K, V>(this)
    for ((key, theirs) in other) {
        val mine = merged[key]
        if (mine == null || theirs > mine) merged[key] = theirs
    }
    return merged
}
