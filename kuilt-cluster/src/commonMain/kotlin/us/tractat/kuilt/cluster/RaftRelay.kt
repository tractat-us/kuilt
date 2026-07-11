package us.tractat.kuilt.cluster

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.NodeId

/**
 * One Raft message travelling across the server core with its **true sender kept
 * intact from end to end** — the relay envelope.
 *
 * In a federation, the servers form a small fully-meshed core and each player
 * connects to whichever server is nearest. When the leader must deliver the
 * committed log to a player sitting behind a *different* server, the message has
 * to hop across the core: `player → server → core → server → player`. On an
 * ordinary point-to-point link the fabric stamps *who sent this frame* for you,
 * so a receiver can always trust it. A relayed frame is different: it is handed
 * on by an intermediate server, so the fabric's "sender" is that middle server,
 * not the node the Raft engine actually needs to credit. This envelope carries
 * the real [origin] inside the frame so the far end can recover it.
 *
 * ## Why the origin must survive the trip
 *
 * The Raft engine keys almost everything on *who a message came from*: vote
 * tallies, a leader's per-follower `matchIndex`/`nextIndex`, CheckQuorum contact
 * tracking, ReadIndex acknowledgement crediting, and leadership-transfer
 * authentication. If a relaying server re-stamped the frame with its own id, all
 * of those would credit the wrong node and the reply path would silently break.
 * So the relay preserves [origin] verbatim at every hop and never re-stamps it.
 *
 * [dest] is the ultimate recipient's [NodeId]; [bytes] is the opaque, already
 * serialised Raft RPC — this envelope never inspects it.
 *
 * CBOR-encoded (the module's established binary frame format). [equals]/[hashCode]
 * compare [bytes] by content (kuilt convention for byte-carrying value types).
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal class RaftRelay(
    val origin: NodeId,
    val dest: NodeId,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RaftRelay) return false
        return origin == other.origin && dest == other.dest && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = origin.hashCode()
        result = 31 * result + dest.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String = "RaftRelay(origin=$origin, dest=$dest, bytes=${bytes.size}B)"

    internal companion object {
        private val cbor = Cbor { ignoreUnknownKeys = true }

        fun encode(relay: RaftRelay): ByteArray = cbor.encodeToByteArray(relay)

        fun decode(bytes: ByteArray): RaftRelay = cbor.decodeFromByteArray(bytes)
    }
}
