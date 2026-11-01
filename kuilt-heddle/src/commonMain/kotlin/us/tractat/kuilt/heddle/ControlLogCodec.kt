package us.tractat.kuilt.heddle

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * What one committed log entry turned out to be, as far as heddle's control plane is concerned.
 *
 * [NotHeddle] and [Unreadable] are separate on purpose. A node that cannot read one of its own acts
 * cannot know the true control state, so it must stop; bytes heddle never wrote tell it nothing about
 * that state, so it can step over them (#1738).
 */
internal sealed interface ControlEntry {

    /** A heddle act this build can read. */
    data class Act(val envelope: ControlEnvelope) : ControlEntry

    /** Bytes no version of heddle wrote: neither the header nor the untagged shape every old act has. */
    data object NotHeddle : ControlEntry

    /**
     * A heddle act this build cannot read.
     *
     * @property version the format it was written in: [ControlLogCodec.UNTAGGED_VERSION] for an act from
     *   before the header existed, or `null` for one cut off before its version byte.
     * @property failure the decode failure's type and message; a version this build does not know is
     *   never decoded, so there it names the version instead.
     */
    data class Unreadable(val version: Int?, val failure: String) : ControlEntry
}

/**
 * The framing of a control act on the Raft log: a four-byte magic, one version byte, then the CBOR of
 * a [ControlEnvelope]. This is the positive determination #1738 asks for, chosen over a generic Raft
 * entry discriminator by ruling, because it stays inside this module and leaves other consumers'
 * logs alone.
 *
 * ## Reading a log written before the header
 *
 * Governed nodes replay from index 1 on every boot, so untagged acts stay readable for as long as this
 * build can decode them. Whether untagged bytes *are* an act is decided from a fact, not from whether
 * they happen to decode: every untagged act was written with the default [Cbor] as a [ControlEnvelope]
 * whose first property is `requestKey`, so it opens with an indefinite-length map (`0xBF`), a ten-byte
 * text key (`0x6A`) and the word `requestKey`. Nothing writes untagged acts any more, so that set of
 * writers is closed and [UNTAGGED_PREFIX] cannot drift, whatever later happens to [ControlEnvelope].
 *
 * So bytes with that opening which fail to decode are [ControlEntry.Unreadable], including a foreign
 * entry that happens to share the opening: the error is then a needless halt, never a skipped act.
 * Bytes with neither opening are [ControlEntry.NotHeddle]. The one thing that could turn an act into
 * those is corruption of its first bytes, which the crash-fault model the Raft log assumes rules out.
 *
 * **A decoding default is not a repair.** It would turn an unreadable act into a readable, wrong one.
 * `ControlCommand.Mint.root` is the sharp case: a defaulted root could admit a second root.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ControlLogCodec {

    /** The format of an act from before the header existed. Never written any more. */
    const val UNTAGGED_VERSION: Int = 0

    /** The format this build writes, and the only tagged one it can read. */
    const val CURRENT_VERSION: Int = 1

    private val MAGIC = "KHCL".encodeToByteArray()
    private val UNTAGGED_PREFIX = byteArrayOf(0xBF.toByte(), 0x6A) + "requestKey".encodeToByteArray()

    fun encode(envelope: ControlEnvelope): ByteArray =
        MAGIC + CURRENT_VERSION.toByte() + Cbor.encodeToByteArray(ControlEnvelope.serializer(), envelope)

    fun decode(bytes: ByteArray): ControlEntry = when {
        bytes.opensWith(MAGIC) -> decodeTagged(bytes)
        bytes.opensWith(UNTAGGED_PREFIX) -> decodeCbor(bytes, UNTAGGED_VERSION)
        else -> ControlEntry.NotHeddle
    }

    private fun decodeTagged(bytes: ByteArray): ControlEntry {
        if (bytes.size == MAGIC.size) return ControlEntry.Unreadable(null, "cut off before its version byte")
        return when (val version = bytes[MAGIC.size].toInt() and 0xFF) {
            CURRENT_VERSION -> decodeCbor(bytes.copyOfRange(MAGIC.size + 1, bytes.size), version)
            else -> ControlEntry.Unreadable(version, "this build reads only version $CURRENT_VERSION")
        }
    }

    private fun decodeCbor(bytes: ByteArray, version: Int): ControlEntry =
        runCatchingCancellable { Cbor.decodeFromByteArray(ControlEnvelope.serializer(), bytes) }
            .fold(
                onSuccess = { ControlEntry.Act(it) },
                onFailure = { ControlEntry.Unreadable(version, it.toString()) },
            )

    private fun ByteArray.opensWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
