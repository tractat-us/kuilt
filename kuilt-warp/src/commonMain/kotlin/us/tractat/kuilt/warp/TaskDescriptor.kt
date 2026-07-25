package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable
import us.tractat.kuilt.core.PeerId

/**
 * The unit of work that travels across the fabric — the task-descriptor envelope.
 *
 * One envelope does three jobs:
 *
 * 1. **Routes the work.** The claiming peer looks [op] up in its local [OpRegistry] and
 *    runs its own registered copy with [args]. The code never crosses the wire; only the
 *    name does. By default the task is *ring-assigned* — `hash(taskId)` picks the owner,
 *    decoupling who-runs from whose-data (work-stealing). Set [pinnedOwner] to override
 *    this and route the work to exactly one named peer regardless of the hash ring.
 * 2. **Content-addresses the bobbin (future).** Once WASM bobbins ship (warp slices C4/C5),
 *    [op] will double as the content-hash of the bobbin — [OpId] value = hash of the kernel
 *    — so peers can verify what they fetched. For named-op dispatch (C1/C2) the [op] is a
 *    stable symbolic name registered at startup from the same compiled binary.
 * 3. **Carries the trace.** [traceparent] is a W3C Trace Context header value
 *    (`00-<traceId>-<spanId>-<flags>`). Null when no trace context is propagated — tracing
 *    is a tuning concern, not load-bearing for dispatch.
 *
 * **ByteArray equality.** Kotlin's default `==` on `ByteArray` is identity, not content.
 * This class overrides [equals] and [hashCode] to use [ByteArray.contentEquals] /
 * [ByteArray.contentHashCode] so two descriptors built from the same input compare equal.
 *
 * @see OpRegistry
 * @see OpId
 */
@Serializable
public class TaskDescriptor(
    /** The symbolic name of the operation to dispatch. */
    public val op: OpId,
    /** The serialised arguments passed to [Op.invoke] on the claiming peer. */
    public val args: ByteArray = ByteArray(0),
    /**
     * W3C Trace Context `traceparent` header value, or null when no trace context is
     * propagated. Not load-bearing for dispatch — a peer without a tracing back-end
     * behaves identically whether this field is null or present.
     */
    public val traceparent: String? = null,
    /**
     * When set, this task is owned by exactly this peer regardless of the hash ring;
     * absent (`null`) ⇒ ring-assigned.
     *
     * **Pinned execution** decouples *who-runs* from the consistent-hash ring: only the
     * named peer claims and runs the task, no other peer ever does, and if the pinned
     * owner is partitioned or absent the task stays pending (it does **not** re-home to a
     * survivor) until the owner returns. This makes inherently data-local workloads
     * expressible — e.g. federated learning, where only the data owner can run its own
     * training step on its private local data — and doubles as task affinity / sticky
     * placement. Use [WarpNode.enqueueLocal] for the common "pin to me" case.
     *
     * @sample us.tractat.kuilt.warp.samplePinnedExecution
     */
    public val pinnedOwner: PeerId? = null,
    /**
     * The opaque fair-share [Lane] this task rides. Defaults to [Lane.ROOT] — *no lane*,
     * the untagged path — so warp core assigns it no meaning and an untagged descriptor is
     * byte-for-byte unchanged on the wire (CBOR omits a field at its default). An enforcement
     * adapter (`:kuilt-warp-heddle`) binds the tag to a fair-share leaf and gates execution
     * on entitlement; warp core never interprets it.
     */
    public val lane: Lane = Lane.ROOT,
    /**
     * The opaque location-[Affinity] this task requires — the "can I execute *here*" predicate
     * (H8, design §14.6). Defaults to [Affinity.Anywhere] — *no requirement*, so placement is
     * over the whole roster and an unrestricted descriptor is byte-for-byte unchanged on the
     * wire (CBOR omits a field at its default). When set, warp core hashes the task over only
     * the **eligible subset** of the roster: the peers whose advertised [CapSet] satisfies this
     * predicate (see [WarpNode.advertiseCapabilities]). Eligibility is independent of the [lane]
     * — a task may carry both — and introduces no conserved quantity, so it never touches the
     * ledger's conservation.
     */
    public val affinity: Affinity = Affinity.Anywhere,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskDescriptor) return false
        return op == other.op &&
            args.contentEquals(other.args) &&
            traceparent == other.traceparent &&
            pinnedOwner == other.pinnedOwner &&
            lane == other.lane &&
            affinity == other.affinity
    }

    override fun hashCode(): Int {
        var hash = op.hashCode()
        hash = 31 * hash + args.contentHashCode()
        hash = 31 * hash + (traceparent?.hashCode() ?: 0)
        hash = 31 * hash + (pinnedOwner?.hashCode() ?: 0)
        hash = 31 * hash + lane.hashCode()
        hash = 31 * hash + affinity.hashCode()
        return hash
    }

    override fun toString(): String =
        "TaskDescriptor(op=${op.value}, args=[${args.size} bytes], " +
            "traceparent=$traceparent, pinnedOwner=${pinnedOwner?.value}, lane=${lane.tag}, affinity=$affinity)"
}
