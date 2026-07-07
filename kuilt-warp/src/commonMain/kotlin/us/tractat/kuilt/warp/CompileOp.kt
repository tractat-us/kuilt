@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.PeerId

/**
 * The arguments of a ring-dispatched **compile task**: which source kernel to compile, for
 * which [Target], at which [OptLevel] — `compile(sourceHash, target, optLevel) → variantHash`.
 *
 * Compilation is just another warp op. Instead of imperatively calling
 * [WarpNode.publishVariant] on a compiler node, any peer enqueues a [TaskDescriptor] built
 * by [CompileOp.descriptor]; the compile request then travels, is claimed, and is executed
 * through exactly the same ring-dispatch machinery as every other task. The winning compiler
 * node runs its registered compile op ([WarpNode.registerCompiler]), which publishes the
 * variant via [WarpNode.publishVariant] and records the variant's [BobbinHash] (as UTF-8
 * hex) on the results board.
 *
 * Encoded to/from the [TaskDescriptor.args] bytes with CBOR via [encode]/[decode].
 */
@Serializable
public data class CompileRequest(
    /** Content address of the **raw/source** bobbin to compile. */
    val sourceHash: BobbinHash,
    /** The platform the requested variant is built for. */
    val target: Target,
    /** The optimization level of the requested variant. */
    val optLevel: OptLevel,
) {
    /** Serialises this request to the [TaskDescriptor.args] wire form (CBOR). */
    public fun encode(): ByteArray = Cbor.encodeToByteArray(serializer<CompileRequest>(), this)

    public companion object {
        /**
         * Decodes a request previously produced by [encode].
         *
         * @throws kotlinx.serialization.SerializationException if [args] is not a valid
         *   CBOR-encoded [CompileRequest].
         */
        public fun decode(args: ByteArray): CompileRequest =
            Cbor.decodeFromByteArray(serializer<CompileRequest>(), args)
    }
}

/**
 * The well-known symbolic identity of the ring-dispatched compile op.
 *
 * A *compiler node* is simply a peer that registered this op ([WarpNode.registerCompiler]);
 * every other peer knows only the name. Enqueue a compile task with [descriptor] — the task
 * flows through the ordinary work queue, is claimed by the compiler node, and produces a
 * gossiped variant, rather than requiring a direct imperative [WarpNode.publishVariant] call.
 */
public object CompileOp {

    /** The [OpId] under which compiler nodes register the compile op. */
    public val ID: OpId = OpId("warp.compile")

    /**
     * Builds the [TaskDescriptor] that dispatches [request] to [compiler] over the ring.
     *
     * The descriptor is **pinned** to [compiler] ([TaskDescriptor.pinnedOwner]): only peers
     * that registered the compile op can run it, and pinning is how the ring routes a task
     * to a named capable peer today. If the compiler node is offline the task waits for it
     * (pinned tasks never re-home) — compilation is an optimization, so the mesh keeps
     * interpreting in the meantime.
     *
     * A homogeneous mesh where *every* peer is a compiler node may instead build a plain
     * `TaskDescriptor(CompileOp.ID, request.encode())` and let the hash ring pick the owner.
     *
     * @param request What to compile, for which target, at which opt level.
     * @param compiler The peer that registered the compile op via [WarpNode.registerCompiler].
     * @param traceparent Optional W3C Trace Context header value; see [TaskDescriptor.traceparent].
     */
    public fun descriptor(
        request: CompileRequest,
        compiler: PeerId,
        traceparent: String? = null,
    ): TaskDescriptor = TaskDescriptor(
        op = ID,
        args = request.encode(),
        traceparent = traceparent,
        pinnedOwner = compiler,
    )
}
