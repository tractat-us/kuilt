package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identity of one **group** in the fairness tree — a tenant, a lane, a leaf where
 * work actually runs. Opaque, value-equal, and stably serialized: a `GroupId` is
 * just a stable string name, compared as a string so every peer orders a set of
 * groups identically.
 */
@Serializable
@JvmInline
public value class GroupId(public val value: String) : Comparable<GroupId> {
    override fun compareTo(other: GroupId): Int = value.compareTo(other.value)
}

/**
 * Identity of one **attachment generation** — the immutable parent→child edge that
 * carries fairness state (see [AttachmentRecord]). There is exactly **one**
 * `AttachmentId` per generation: changing anything fairness-significant (weight,
 * parent, a reset) mints a new id rather than mutating an existing one, so old
 * generations keep their history forever.
 */
@Serializable
@JvmInline
public value class AttachmentId(public val value: String) : Comparable<AttachmentId> {
    override fun compareTo(other: AttachmentId): Int = value.compareTo(other.value)
}

/**
 * Identity of one **mint** — a single act of introducing root supply into the
 * ledger. Mints are keyed by `MintId` rather than by the holder's replica id so
 * that two independently-recorded mints **union** instead of max-colliding: under
 * control-plane failover the same holder may be credited by more than one
 * committed mint, and each must survive the merge.
 */
@Serializable
@JvmInline
public value class MintId(public val value: String) : Comparable<MintId> {
    override fun compareTo(other: MintId): Int = value.compareTo(other.value)
}

/**
 * Names an **entitlement path** by its final edge. Each group has exactly one
 * active inbound edge, so the edge *names* the whole path from the root — no
 * `List<AttachmentId>` is ever needed as a key. The root path has no final edge
 * and is named by the [ROOT] sentinel.
 *
 * A `PathKey` is compared and serialized as its underlying string.
 */
@Serializable
@JvmInline
public value class PathKey private constructor(public val value: String) : Comparable<PathKey> {
    override fun compareTo(other: PathKey): Int = value.compareTo(other.value)

    public companion object {
        /** The root path — the path with no inbound edge. Sorts before every real edge. */
        public val ROOT: PathKey = PathKey("")

        /** The path whose final edge is [edge]. */
        public fun of(edge: AttachmentId): PathKey = PathKey(edge.value)
    }
}
