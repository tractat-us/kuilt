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

/**
 * The [MeshHello] inside a frame a mesh sent, failing by name if that frame was not a hello (#2474).
 *
 * A hand-driven far end used to call `MeshHello.decode` on whatever arrived, which was correct only
 * while the wire was positional. Since the frame says what it is, the cast is the assertion: a rig
 * that has slipped a frame out of step fails here rather than misparsing a payload as an identity.
 */
internal fun meshHelloOf(frame: ByteArray): MeshHello =
    (MeshWire.decode(frame) as MeshWireFrame.Hello).hello

/**
 * The consumer payload inside a [MeshFrameType.Data] frame a mesh sent, failing by name if the frame
 * was a hello or a goodbye. The mirror of [MeshWire.encodeData] for a hand-driven far end.
 */
internal fun meshPayloadOf(frame: ByteArray): ByteArray {
    check(MeshWire.decode(frame) == MeshWireFrame.Data) { "expected a mesh DATA frame, got ${MeshWire.decode(frame)}" }
    return frame.copyOfRange(MeshWire.TYPE_BYTES, frame.size)
}
