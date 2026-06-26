package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable

/**
 * The address of a compiled bobbin *variant*: which source kernel it was built from, for
 * which [Target], at which [OptLevel]. Recorded as the `variantOf` provenance on a
 * [BobbinMeta] so a peer can discover "the compiled-for-my-target version of source S".
 */
@Serializable
public data class VariantKey(
    val sourceHash: BobbinHash,
    val target: Target,
    val optLevel: OptLevel,
)

/**
 * A manifest entry: a content-addressed bobbin plus optional variant provenance.
 *
 * `variantOf == null` is a **raw/source** bobbin (the pre-variant meaning — every bobbin
 * the C5 path published was implicitly this). A non-null [variantOf] marks a compiled
 * variant. The [hash] is always `hash(bytes)`, so `Creel`'s content-addressing invariant is
 * untouched; provenance rides alongside, never replacing the key.
 *
 * **Determinism invariant:** a given [hash] always carries the same [variantOf] (the bytes
 * are either always raw or always the variant-of-X they hash to), so the grow-only manifest
 * `GSet` never holds two conflicting entries for one hash.
 */
@Serializable
public data class BobbinMeta(
    val hash: BobbinHash,
    val variantOf: VariantKey?,
)
