package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A symbolic, stable name for a registered operation in the warp op registry.
 *
 * Op IDs travel inside task descriptors (warp slice C2) so a receiving peer can look
 * the operation up in its own [OpRegistry] and run *its own copy* of the compiled
 * function. The ID is the only thing that crosses the fabric; the code never moves.
 *
 * Use a name that is stable across deployments — typically the fully-qualified name of
 * the function it identifies, or a short domain-specific constant agreed upon by all
 * peers.
 *
 * @see OpRegistry
 */
@Serializable
@JvmInline
public value class OpId(public val value: String)
