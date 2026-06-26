package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * The content address of a bobbin — a warp kernel stored in the local [Creel].
 *
 * The value is the lowercase hex representation of the FNV-1a-64 hash of the kernel
 * bytes. Content-addressing makes the key a deterministic function of the value:
 * `BobbinHash = hash(bytes)`. Two peers that hold the same [BobbinHash] are
 * guaranteed to hold byte-identical bytes — there is no merge conflict, only the
 * one-step value lattice **`Absent ⊏ Present`**.
 *
 * The string form is 16 lowercase hexadecimal characters (64 bits / 4 bits per char).
 * It is stable across all KMP targets and safe to embed in [us.tractat.kuilt.warp.OpId]
 * values once WASM bobbins ship (warp slice C5).
 *
 * @see Creel
 */
@Serializable
@JvmInline
public value class BobbinHash(public val value: String)
