package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * Opaque, comparable client identity for Raft §8 client-serial dedup.
 *
 * The auto form ([auto]) is `"$nodeId-$randomHex"`: the cluster-unique [NodeId] prefixed to a
 * per-incarnation random suffix. A caller that wants cross-crash exactly-once passes any **stable**
 * opaque string it persists itself. Raft never parses the value.
 */
@Serializable
@JvmInline
public value class ClientId(public val value: String) {
    public companion object {
        /**
         * Auto identity: [nodeId] prefixed to a per-incarnation random hex suffix from [random]
         * (inject the engine's seeded RNG; never the global). The [nodeId] prefix keeps two nodes
         * distinct even under the same seed, and makes the id readable in logs.
         */
        public fun auto(nodeId: NodeId, random: Random): ClientId {
            val suffix = ByteArray(8).also { random.nextBytes(it) }
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return ClientId("${nodeId.value}-$suffix")
        }
    }
}

/** A proposal's end-to-end dedup identity: a [clientId] plus a per-client monotonic [requestId]. */
@Serializable
public data class DedupKey(val clientId: ClientId, val requestId: Long)
