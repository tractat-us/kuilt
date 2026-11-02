package us.tractat.kuilt.heddle

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * What one committed log entry turned out to be, as far as heddle's control plane is concerned: an act
 * it can read, or an entry it has to stop at. There is no third answer. The log is dedicated to this
 * control plane, so bytes heddle cannot read are never set aside as somebody else's (#1738).
 */
internal sealed interface ControlEntry {

    /** A heddle act this build can read. */
    data class Act(val envelope: ControlEnvelope) : ControlEntry

    /**
     * An entry this build cannot read.
     *
     * @property version the format its header names: [ControlLogCodec.UNTAGGED_VERSION] when it has no
     *   header, or `null` when it was cut off before its version byte.
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
 * ## An entry with no header
 *
 * Governed nodes replay from index 1 on every boot, so an act from before the header existed stays
 * readable for as long as this build can decode it. Anything without the header gets that one chance:
 * it decodes as a [ControlEnvelope] and is an act, or it does not and is [ControlEntry.Unreadable].
 * Whether it was a heddle act from an older version or bytes heddle never wrote is deliberately not
 * asked, because both answers stop the peer.
 *
 * **A decoding default is not a repair.** It would turn an unreadable act into a readable, wrong one.
 * `ControlCommand.Mint.root` is the sharp case: a defaulted root could admit a second root.
 *
 * ## A log from v0.7.3 does not carry over
 *
 * Its `Mint` has no `root` and its `Prepare` carries a field since deleted, so a durable log holding
 * either stops every peer of this version at that entry. **The answer is to start a fresh log. There
 * is no migrator, and none is planned:** that is the accepted trade for a reader that never guesses.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ControlLogCodec {

    /** How an entry with no header reports its format. No version of heddle writes one any more. */
    const val UNTAGGED_VERSION: Int = 0

    /** The format this build writes, and the only tagged one it can read. */
    const val CURRENT_VERSION: Int = 1

    /**
     * **Change the version byte, never this.** A build that meets a magic it does not know reads the
     * entry as having no header, so it still stops, but it reports [UNTAGGED_VERSION] in place of the
     * version that would have told an operator what to upgrade to.
     */
    private val MAGIC = "KHCL".encodeToByteArray()

    fun encode(envelope: ControlEnvelope): ByteArray =
        MAGIC + CURRENT_VERSION.toByte() + Cbor.encodeToByteArray(ControlEnvelope.serializer(), envelope)

    fun decode(bytes: ByteArray): ControlEntry =
        if (bytes.opensWith(MAGIC)) decodeTagged(bytes) else decodeCbor(bytes, UNTAGGED_VERSION)

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
