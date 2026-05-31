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

        fun decode(bytes: ByteArray): PlyFrame {
            require(bytes.isNotEmpty()) { "empty ply frame" }
            return when (bytes[0]) {
                TAG_ANNOUNCE -> decodeAnnounce(bytes)
                TAG_DATA -> decodeData(bytes)
                else -> throw IllegalArgumentException("unknown ply frame tag: ${bytes[0]}")
            }
        }

        private fun decodeAnnounce(bytes: ByteArray): Announce {
            val len = readInt(bytes, 1)
            require(bytes.size >= 5 + len) { "truncated announce frame: declared id length $len exceeds buffer" }
            val id = bytes.decodeToString(5, 5 + len)
            return Announce(PeerId(id))
        }

        private fun decodeData(bytes: ByteArray): Data {
            val len = readInt(bytes, 1)
            require(bytes.size >= 5 + len + 8) { "truncated data frame: declared id length $len exceeds buffer" }
            val id = bytes.decodeToString(5, 5 + len)
            val seq = readLong(bytes, 5 + len)
            val payload = bytes.copyOfRange(5 + len + 8, bytes.size)
            return Data(PeerId(id), seq, payload)
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
