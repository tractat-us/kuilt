package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId

/** A frame on the composite wire. Opaque-payload bytes from each ply's perspective. */
internal sealed interface PlyFrame {
    /** Control: the sender's composite id, used to reconcile per-ply transport ids. */
    data class Announce(val compositeId: PeerId) : PlyFrame

    /** Application: origin-stamped payload for dedup + per-origin ordering. */
    data class Data(val originId: PeerId, val originSeq: Long, val payload: ByteArray) : PlyFrame

    companion object {
        private const val TAG_ANNOUNCE: Byte = 1
        private const val TAG_DATA: Byte = 2

        /** Fixed prefix both frame kinds share: 1 tag byte + a 4-byte big-endian id length. */
        private const val HEADER_BYTES = 1 + Int.SIZE_BYTES

        fun encode(frame: PlyFrame): ByteArray =
            when (frame) {
                is Announce -> encodeAnnounce(frame)
                is Data -> encodeData(frame)
            }

        private fun encodeAnnounce(frame: Announce): ByteArray {
            val id = frame.compositeId.value.encodeToByteArray()
            val out = ByteArray(1 + 4 + id.size)
            out[0] = TAG_ANNOUNCE
            writeInt(out, 1, id.size)
            id.copyInto(out, 5)
            return out
        }

        private fun encodeData(frame: Data): ByteArray {
            val id = frame.originId.value.encodeToByteArray()
            val out = ByteArray(1 + 4 + id.size + 8 + frame.payload.size)
            out[0] = TAG_DATA
            writeInt(out, 1, id.size)
            id.copyInto(out, 5)
            writeLong(out, 5 + id.size, frame.originSeq)
            frame.payload.copyInto(out, 5 + id.size + 8)
            return out
        }

        /**
         * Decode [bytes] as a [PlyFrame], throwing [IllegalArgumentException] for anything malformed.
         *
         * ### Every check runs BEFORE the read it protects (#1788)
         * These bytes come off the wire from another peer, so a malformed frame is *reachable input*,
         * not a local programming error. It used to be worse than a rejected frame: `readInt(bytes, 1)`
         * ran **before** the `require` written to reject a short frame, so a **2-byte** frame threw
         * [IndexOutOfBoundsException] out of the composite's per-ply inbound pump — which on
         * Kotlin/Native is an unhandled coroutine exception on a `SupervisorJob` child, routed to the
         * global handler and, with no `setUnhandledExceptionHook` installed, **aborting the process**.
         * One short frame from any peer crashed a shipped app.
         *
         * Three malformed shapes a peer can put on the wire are rejected, not one:
         *  - a buffer too short to hold the [HEADER_BYTES] header at all — bounds-checked before [readInt]
         *    touches `bytes[1..4]`;
         *  - a **negative** declared length: the prefix is read as a signed [Int], so a peer can set its
         *    high bit and the old `bytes.size >= 5 + len` passed for a length no buffer can satisfy. (The
         *    read that followed then died in `decodeToString`'s own `startIndex > endIndex` check — a
         *    throw either way, just a different one, and nothing but that internal was closing the hole.)
         *  - a length that **overflows** when added to the header, wrapping `5 + len + 8` negative so that
         *    same comparison passed again. Hence [idLength] subtracts rather than adds.
         *
         * ### Why this throws where the exemplars return null
         * `NamedFrame.headerLength` and `GossipFrame.tryDecode` are bounds- and overflow-safe by the same
         * check-before-read discipline, but they are **discriminators**: a frame that does not decode there
         * is ordinary application traffic, passed through unwrapped, so `null` is a category and not a
         * fault. Every frame arriving on a composite ply is a `PlyFrame` by construction, so one that does
         * not decode is a fault with a diagnosis worth carrying — and the exception *is* the diagnosis
         * `CompositeSeam`'s inbound pump raises through `onPlyFailure`
         * ([PlyReconcileException.Phase.INBOUND]) after dropping the frame.
         */
        fun decode(bytes: ByteArray): PlyFrame {
            require(bytes.isNotEmpty()) { "empty ply frame" }
            return when (bytes[0]) {
                TAG_ANNOUNCE -> decodeAnnounce(bytes)
                TAG_DATA -> decodeData(bytes)
                else -> throw IllegalArgumentException("unknown ply frame tag: ${bytes[0]}")
            }
        }

        private fun decodeAnnounce(bytes: ByteArray): Announce {
            val len = idLength(bytes, trailing = 0, kind = "announce")
            return Announce(PeerId(bytes.decodeToString(HEADER_BYTES, HEADER_BYTES + len)))
        }

        private fun decodeData(bytes: ByteArray): Data {
            val len = idLength(bytes, trailing = Long.SIZE_BYTES, kind = "data")
            val id = bytes.decodeToString(HEADER_BYTES, HEADER_BYTES + len)
            val seq = readLong(bytes, HEADER_BYTES + len)
            val payload = bytes.copyOfRange(HEADER_BYTES + len + Long.SIZE_BYTES, bytes.size)
            return Data(PeerId(id), seq, payload)
        }

        /**
         * The **validated** declared id length of [bytes], for a frame carrying [trailing] fixed bytes
         * after the id (a [Data] frame's 8-byte sequence; nothing for an [Announce]).
         *
         * Rejects all three peer-supplied malformed shapes — see [decode]. Once this returns,
         * `HEADER_BYTES + len (+ trailing)` is guaranteed to be a valid index into [bytes], so every read
         * in the callers is in bounds by construction rather than by inspection.
         */
        private fun idLength(bytes: ByteArray, trailing: Int, kind: String): Int {
            // BEFORE readInt, which touches bytes[1..4]: a 2-byte frame index-faulted here (#1788).
            require(bytes.size >= HEADER_BYTES) {
                "truncated $kind frame: ${bytes.size} bytes cannot hold the $HEADER_BYTES-byte header"
            }
            val len = readInt(bytes, 1)
            // The length is read as a SIGNED Int, so a peer can simply set the high bit.
            require(len >= 0) { "malformed $kind frame: negative declared id length $len" }
            // Subtract, never add: `HEADER_BYTES + len + trailing` wraps NEGATIVE for a large declared
            // length and `bytes.size >= <negative>` then passes — the overflow hole. The left-hand side
            // cannot overflow (a non-negative size minus two small constants), and its going negative is
            // itself a buffer too short for the header plus `trailing`, which this same check rejects.
            require(bytes.size - HEADER_BYTES - trailing >= len) {
                "truncated $kind frame: declared id length $len exceeds the ${bytes.size}-byte buffer"
            }
            return len
        }

        private fun writeInt(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte()
            b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte()
            b[off + 3] = v.toByte()
        }

        private fun readInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

        private fun writeLong(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
        }

        private fun readLong(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
    }
}
