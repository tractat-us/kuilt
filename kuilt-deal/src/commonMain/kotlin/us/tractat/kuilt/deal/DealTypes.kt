package us.tractat.kuilt.deal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.core.PeerId

public typealias PlayerId = PeerId

@Serializable
public class SchemeKey(public val raw: ByteArray) {
    override fun equals(other: Any?): Boolean = other is SchemeKey && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
    override fun toString(): String = "SchemeKey(${raw.size} bytes)"
}

public data class SchemeKeyPair(
    public val encryptKey: SchemeKey,
    public val stripKey: SchemeKey,
)

@Serializable
public class EncryptProof(public val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is EncryptProof && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "EncryptProof(${bytes.size} bytes)"
}

@Serializable
public class StripProof(public val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is StripProof && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "StripProof(${bytes.size} bytes)"
}

@Serializable
public class EncryptedKey(public val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is EncryptedKey && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "EncryptedKey(${bytes.size} bytes)"
}

public interface KeyEscrow {
    public suspend fun deposit(player: PlayerId, key: EncryptedKey)
}
