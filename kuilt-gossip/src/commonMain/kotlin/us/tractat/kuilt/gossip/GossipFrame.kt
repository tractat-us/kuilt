package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch

/**
 * A disseminated broadcast frame as it travels the partial-mesh overlay
 * (`docs/gossip-mesh-design.md` Phase 3 — eager-flood-to-neighbours with dedup).
 *
 * A node wraps each [us.tractat.kuilt.core.Seam.broadcast] payload in a
 * [GossipFrame], stamping it with its own id as [origin], the next [seq], and an
 * initial [ttl]; it then floods the encoded frame to its active neighbours. A
 * receiver decodes the frame, delivers [payload] to the application once (keyed
 * by `(origin, seq)`), and — if [ttl] permits — decrements the hop budget and re-floods to
 * *its own* active neighbours minus the peer it arrived from. Anti-entropy
 * (Phase 1) backstops anything a flood drops, so the [ttl] is only a hard cap
 * against pathological loops; dedup is what actually terminates the flood.
 *
 * ## Wire format
 *
 * ```
 * [MAGIC: 4][VERSION: 1][ttl: 1][originLen: 2 BE][origin UTF-8][seq: 8 BE][payload…]
 * ```
 *
 * The 4-byte [MAGIC] plus the structural checks in [tryDecode] let a receiver
 * tell a relayed broadcast apart from a raw point-to-point [sendTo] frame (left
 * unwrapped) or a heartbeat ping/pong (a distinct text prefix consumed by the
 * liveness detectors) — relay handling applies only to frames that decode here.
 */
internal class GossipFrame private constructor(
    val origin: PeerId,
    val seq: Long,
    val ttl: Int,
    /** The application payload, with the gossip header stripped. */
    val payload: ByteArray,
) {
    /** A copy with the hop budget decremented by one, for re-flooding. */
    fun decremented(): GossipFrame = GossipFrame(origin, seq, ttl - 1, payload)

    /** Encodes this frame to its wire bytes (header + payload). */
    fun encode(): ByteArray {
        val originBytes = origin.value.encodeToByteArray()
        val out = ByteArray(headerSize(originBytes.size) + payload.size)
        var i = 0
        MAGIC.copyInto(out, i); i += MAGIC.size
        out[i++] = VERSION
        out[i++] = ttl.toByte()
        out[i++] = (originBytes.size ushr 8).toByte()
        out[i++] = originBytes.size.toByte()
        originBytes.copyInto(out, i); i += originBytes.size
        for (shift in 56 downTo 0 step 8) out[i++] = (seq ushr shift).toByte()
        payload.copyInto(out, i)
        return out
    }

    companion object {
        /** Frame discriminator: `gsp1`. Deliberately not the heartbeat text prefix. */
        val MAGIC: ByteArray = byteArrayOf(0x67, 0x73, 0x70, 0x31)
        const val VERSION: Byte = 1

        private const val TTL_OFFSET = 5
        private const val ORIGIN_LEN_OFFSET = 6
        private const val ORIGIN_OFFSET = 8

        /** Fixed header bytes preceding an [originLen]-byte origin id and the payload. */
        private fun headerSize(originLen: Int): Int = ORIGIN_OFFSET + originLen + Long.SIZE_BYTES

        /** A fresh origin-stamped frame for a locally-initiated broadcast. */
        fun origin(origin: PeerId, seq: Long, ttl: Int, payload: ByteArray): GossipFrame =
            GossipFrame(origin, seq, ttl, payload)

        /**
         * Decodes [swatch] as a [GossipFrame], or returns `null` when it is not a
         * gossip relay frame (wrong magic/version, or too short) — those frames are
         * passed through to the application unchanged.
         */
        fun tryDecode(swatch: Swatch): GossipFrame? {
            val size = swatch.payloadSize
            if (size < headerSize(0)) return null
            for (i in MAGIC.indices) if (swatch.byteAt(i) != MAGIC[i]) return null
            if (swatch.byteAt(MAGIC.size) != VERSION) return null

            val bytes = swatch.toByteArray()
            val ttl = bytes[TTL_OFFSET].toInt() and 0xFF
            val originLen = ((bytes[ORIGIN_LEN_OFFSET].toInt() and 0xFF) shl 8) or
                (bytes[ORIGIN_LEN_OFFSET + 1].toInt() and 0xFF)
            if (size < headerSize(originLen)) return null

            val origin = PeerId(bytes.decodeToString(ORIGIN_OFFSET, ORIGIN_OFFSET + originLen))
            var seqOffset = ORIGIN_OFFSET + originLen
            var seq = 0L
            repeat(Long.SIZE_BYTES) { seq = (seq shl 8) or (bytes[seqOffset++].toLong() and 0xFF) }
            val payload = bytes.copyOfRange(seqOffset, bytes.size)
            return GossipFrame(origin, seq, ttl, payload)
        }
    }
}
