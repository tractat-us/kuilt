package us.tractat.kuilt.raft

/**
 * A state-machine snapshot. Opaque to kuilt-raft — it never deserializes [state]; only the consumer
 * can collapse many log entries into compact state. The same payload flows both directions: the
 * consumer publishes it via [RaftNode.snapshots] (outbound), and a lagging node receives it wrapped
 * in [Committed.Install] (inbound).
 *
 * @param throughIndex the highest committed log index this state reflects.
 * @param state opaque application bytes.
 */
public class Snapshot(public val throughIndex: Long, public val state: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Snapshot) return false
        return throughIndex == other.throughIndex && state.contentEquals(other.state)
    }
    override fun hashCode(): Int = 31 * throughIndex.hashCode() + state.contentHashCode()
    override fun toString(): String = "Snapshot(throughIndex=$throughIndex, state=${state.size}B)"
}
