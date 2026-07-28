package us.tractat.kuilt.core.fabric

/**
 * Width of a mesh preamble nonce — mirrors `MeshSeam`'s file-private `NONCE_BYTES` (#1812).
 *
 * Kept local rather than widening the production constant's visibility: the wire width is what the
 * tests assert against, and a copy that disagrees fails loudly the moment either side moves.
 */
internal const val MESH_NONCE_BYTES: Int = 16

/**
 * A full-width mesh nonce whose first bytes are [leading] and whose remainder is zero.
 *
 * Several mesh tests hand-pick a short nonce to steer the canonical-nonce dedup tiebreak. Zero-
 * extension is order-preserving over the hex encoding `canonicalLinkNonce` compares (appending `"00"`
 * groups never changes which of two hex strings sorts first), so widening those nonces to the wire
 * width keeps each test's intended winner intact.
 */
internal fun meshNonce(vararg leading: Byte): ByteArray =
    ByteArray(MESH_NONCE_BYTES).also { leading.copyInto(it) }
