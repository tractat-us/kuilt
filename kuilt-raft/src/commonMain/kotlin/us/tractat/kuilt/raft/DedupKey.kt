package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * Opaque, comparable client identity for Raft §8 client-serial dedup.
 *
 * The auto form ([auto]) is `"auto:$nodeId-$randomHex"`: a reserved `auto:` sentinel, the
 * cluster-unique [NodeId], and a per-incarnation random hex suffix. A caller that wants cross-crash
 * exactly-once passes any **stable** opaque string it persists itself (and **must not** use the
 * reserved `auto:` namespace). Raft-the-transport never parses the value; the one structural reader
 * is [autoFamily], localized here because this type owns the format.
 */
@Serializable
@JvmInline
public value class ClientId(public val value: String) {
    /**
     * The nodeId family of an auto-minted id, or `null` if this id is not auto-shaped.
     *
     * Two auto ids share a family iff they are incarnations of the same node — the dedup GC uses
     * this to evict a superseded incarnation (a new `auto:$nodeId-…` proves the prior sibling dead).
     * Durable/custom ids (no `auto:` sentinel, or a malformed tail) return `null` and are never
     * GC-eligible. The hex tail is fixed-length, so a `$nodeId` containing `-` is preserved verbatim.
     */
    internal fun autoFamily(): String? {
        if (!value.startsWith(AUTO_PREFIX)) return null
        val tailStart = value.length - SUFFIX_HEX_LEN - 1 // index of the '-' before the hex suffix
        if (tailStart <= AUTO_PREFIX.length) return null   // family must be non-empty
        if (value[tailStart] != '-') return null
        for (i in tailStart + 1 until value.length) {
            val c = value[i]
            if (c !in '0'..'9' && c !in 'a'..'f') return null
        }
        return value.substring(AUTO_PREFIX.length, tailStart)
    }

    public companion object {
        private const val AUTO_PREFIX = "auto:"
        private const val SUFFIX_HEX_LEN = 16

        /**
         * Auto identity: `"auto:$nodeId-$randomHex"` — the reserved `auto:` sentinel, [nodeId], and a
         * per-incarnation random hex suffix from [random] (inject the engine's seeded RNG; never the
         * global). The [nodeId] prefix keeps two nodes distinct even under the same seed and makes the
         * id readable in logs; the sentinel lets the dedup GC recognize the auto family unambiguously.
         */
        public fun auto(nodeId: NodeId, random: Random): ClientId {
            val suffix = ByteArray(8).also { random.nextBytes(it) }
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return ClientId("$AUTO_PREFIX${nodeId.value}-$suffix")
        }
    }
}

/** A proposal's end-to-end dedup identity: a [clientId] plus a per-client monotonic [requestId]. */
@Serializable
public data class DedupKey(val clientId: ClientId, val requestId: Long)
