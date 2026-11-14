package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Where a [Grant] is good: in one room, or in any room its issuer hosts.
 *
 * The scope says only *where*; whether the room checking it is the right one is the checker's
 * question, since only it knows its own room id and key.
 */
public sealed interface GrantScope {
    /**
     * Good in the room named [roomId] and no other. A host that restarts and wants this kind of grant
     * to keep working must host under the same, stable room id.
     */
    public data class Room(
        /** The room's id, as the host named it. */
        public val roomId: String,
    ) : GrantScope

    /** Good in any room hosted under the issuer's key, including rooms that do not exist yet. */
    public data object AnyRoomOfIssuer : GrantScope
}

/**
 * A host's signed pass that lets one device back in without being vetted again.
 *
 * When a host admits a device, it can hand the device a grant: "the device with key [subject] may
 * come back to [scope] until [notAfter]", signed with the host's own key, [issuer]. The device keeps
 * it, and presents it when it returns. A grant is not a secret: it names the device's key, so it is
 * useless to anyone who cannot sign with that key. Nor is it an invite: its subject is a key the host
 * has already seen, so only a device that was admitted once can hold one.
 *
 * This type is the statement and its signature, and nothing more. Deciding whether a grant is good
 * *here, now, for this device* — the right issuer, subject and room, not expired, not revoked — is
 * the checker's job, not this type's: [isSignedByIssuer] checks the signature alone.
 *
 * **The wire form is the grant.** [wire] is the signed layout followed by the 64-byte signature, and
 * it is what a device stores and sends. Two grants are equal exactly when their wire bytes are.
 *
 * **One set of terms can carry more than one valid signature.** ECDSA signatures are malleable: if
 * `(r, s)` verifies, so does `(r, n − s)`, and nothing here insists on one of the two. So two grants
 * with the same [id] and the same terms can both verify and still be unequal. To deduplicate grants,
 * key on [id] and [issuer], never on `==`.
 *
 * The layout, after the `kuilt/grant` tag and version `1`:
 *
 * | Field | Bytes |
 * |---|---|
 * | issuer | its 33-byte compressed point |
 * | subject | its 33-byte compressed point |
 * | scope | `0x01` then the room id's UTF-8 for [GrantScope.Room]; `0x02` alone for [GrantScope.AnyRoomOfIssuer] |
 * | [issuedAt] | 8 bytes, big-endian epoch milliseconds |
 * | [notAfter] | 8 bytes, big-endian epoch milliseconds; [NO_EXPIRY] for none |
 * | [id] | [ID_SIZE] random bytes |
 * | parent grant id | empty: reserved for a grant issued on the strength of another |
 *
 * Times are the issuer's to supply; nothing here reads a clock. Every grant satisfies
 * `notAfter > issuedAt`.
 *
 * @sample us.tractat.kuilt.identity.sampleIssueGrant
 */
public class Grant private constructor(
    /** The key that signed this grant: the host that issued it. */
    public val issuer: DidKey,
    /** The key of the device this grant lets back in. */
    public val subject: DidKey,
    /** Where it is good. */
    public val scope: GrantScope,
    /** When the issuer issued it, in epoch milliseconds. */
    public val issuedAt: Long,
    /** The first epoch millisecond it is no longer good, or [NO_EXPIRY]. Always after [issuedAt]. */
    public val notAfter: Long,
    private val idBytes: ByteArray,
    private val wireBytes: ByteArray,
) {
    init {
        require(idBytes.size == ID_SIZE) { "a grant id is $ID_SIZE bytes, not ${idBytes.size}" }
        require(notAfter > issuedAt) { "notAfter ($notAfter) must be after issuedAt ($issuedAt)" }
    }

    /** A copy of this grant's [ID_SIZE]-byte random id. */
    public val id: ByteArray get() = idBytes.copyOf()

    /** A copy of the wire form: the signed layout, then the 64-byte signature. [decode] reads it back. */
    public val wire: ByteArray get() = wireBytes.copyOf()

    /**
     * Whether the signature on this grant is [issuer]'s, over exactly these terms. A grant whose
     * terms — any byte of the signed layout: its subject, its scope, its expiry — changed after
     * signing answers `false`. The signature bytes themselves are another matter: see the note on
     * malleability above.
     *
     * Never throws on a bad grant. It throws [IllegalStateException] only when this platform's
     * cryptographic provider cannot run at all.
     */
    @OptIn(IdentityInternalApi::class)
    public suspend fun isSignedByIssuer(): Boolean = isSignedByIssuer(KeyScheme.P256)

    /**
     * [isSignedByIssuer], checked in [scheme] rather than always in [KeyScheme.P256]. A room's
     * [GrantTrust] passes the room's own [DeviceKeyStore.keyScheme].
     */
    @IdentityInternalApi
    public suspend fun isSignedByIssuer(scheme: KeyScheme): Boolean = StatementCodec.isSignedBy(wireBytes, issuer, scheme)

    override fun equals(other: Any?): Boolean = other is Grant && wireBytes.contentEquals(other.wireBytes)

    override fun hashCode(): Int = wireBytes.contentHashCode()

    override fun toString(): String =
        "Grant(issuer=${issuer.value}, subject=${subject.value}, scope=$scope, issuedAt=$issuedAt, " +
            "notAfter=$notAfter, id=${idBytes.toHexString()})"

    public companion object {
        /** A [notAfter] meaning the grant never expires. */
        public const val NO_EXPIRY: Long = Long.MAX_VALUE

        /** The size of a grant [id], in bytes. */
        public const val ID_SIZE: Int = 16

        /**
         * [issuer]'s grant letting [subject] back into [scope] from [issuedAt] until [notAfter], with
         * a fresh random [id] from the platform's cryptographically secure source.
         *
         * @throws IllegalArgumentException when [notAfter] is not after [issuedAt], or a
         *   [GrantScope.Room] id holds an unpaired surrogate or is longer than 65 534 UTF-8 bytes.
         * @throws IllegalStateException when [issuer] returns a signature that is not 64 bytes, or
         *   this platform's cryptographic provider cannot sign here.
         */
        public suspend fun issue(issuer: DeviceKey, subject: DidKey, scope: GrantScope, issuedAt: Long, notAfter: Long): Grant =
            issue(issuer, subject, scope, issuedAt, notAfter, CryptographyRandom.Default.nextBytes(ID_SIZE))

        /** [issue] with a fixed [id], so a test can pin the layout. */
        internal suspend fun issue(
            issuer: DeviceKey,
            subject: DidKey,
            scope: GrantScope,
            issuedAt: Long,
            notAfter: Long,
            id: ByteArray,
        ): Grant {
            require(id.size == ID_SIZE) { "a grant id is $ID_SIZE bytes, not ${id.size}" }
            require(notAfter > issuedAt) { "notAfter ($notAfter) must be after issuedAt ($issuedAt)" }
            val issuerId = issuer.id
            val layout = Layout.encode(
                TAG,
                VERSION,
                listOf(
                    StatementCodec.key(issuerId),
                    StatementCodec.key(subject),
                    scope.encode(),
                    StatementCodec.time(issuedAt),
                    StatementCodec.time(notAfter),
                    id,
                    NO_PARENT,
                ),
            )
            return Grant(issuerId, subject, scope, issuedAt, notAfter, id.copyOf(), StatementCodec.signed(issuer, layout))
        }

        /**
         * The grant [wire] holds, or `null` when it is not exactly a `kuilt/grant` v1 layout followed
         * by a 64-byte signature: another tag or version, a field of the wrong size, a point that is
         * not compressed, an unknown scope tag, a room id that is not UTF-8, a non-empty parent, or
         * `notAfter` not after `issuedAt`.
         *
         * Checks the layout only. Whether the signature is good is [isSignedByIssuer]'s question.
         */
        public fun decode(wire: ByteArray): Grant? {
            // One copy, taken first: what is parsed is what is kept, whatever the caller does to [wire] after.
            val bytes = wire.copyOf()
            val (layout, _) = Layout.split(bytes) ?: return null
            val fields = Layout.decode(layout, TAG, VERSION, FIELD_COUNT) ?: return null
            val issuer = StatementCodec.keyOrNull(fields[0]) ?: return null
            val subject = StatementCodec.keyOrNull(fields[1]) ?: return null
            val scope = scopeOf(fields[2]) ?: return null
            val issuedAt = StatementCodec.timeOrNull(fields[3]) ?: return null
            val notAfter = StatementCodec.timeOrNull(fields[4]) ?: return null
            val id = fields[5]
            if (id.size != ID_SIZE || fields[6].isNotEmpty() || notAfter <= issuedAt) return null
            return Grant(issuer, subject, scope, issuedAt, notAfter, id, bytes)
        }

        private const val TAG = "kuilt/grant"
        private const val VERSION = 1
        private const val FIELD_COUNT = 7
        private const val SCOPE_ROOM: Byte = 0x01
        private const val SCOPE_ANY_ROOM_OF_ISSUER: Byte = 0x02
        private val NO_PARENT = ByteArray(0)

        private fun GrantScope.encode(): ByteArray = when (this) {
            is GrantScope.Room -> byteArrayOf(SCOPE_ROOM) + StatementCodec.text(roomId)
            GrantScope.AnyRoomOfIssuer -> byteArrayOf(SCOPE_ANY_ROOM_OF_ISSUER)
        }

        private fun scopeOf(field: ByteArray): GrantScope? = when {
            field.isEmpty() -> null
            field[0] == SCOPE_ANY_ROOM_OF_ISSUER -> if (field.size == 1) GrantScope.AnyRoomOfIssuer else null
            field[0] == SCOPE_ROOM -> StatementCodec.textOrNull(field, startIndex = 1)?.let(GrantScope::Room)
            else -> null
        }
    }
}
