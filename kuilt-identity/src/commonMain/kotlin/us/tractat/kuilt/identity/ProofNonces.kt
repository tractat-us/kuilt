package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The nonces a server hands out for [DeviceProof]s, and the record that each is spent once.
 *
 * A device about to dial fetches a nonce, signs a [DeviceProof] over it, and dials. The server
 * [check]s the nonce is one it issued and still fresh, checks the proof's signature, then [spend]s
 * the nonce, so the same proof presented again is [NonceSpend.Replayed].
 *
 * **Issuing keeps no table.** A nonce carries its own proof of origin, so fetching nonces costs a
 * flood nothing but bandwidth:
 *
 * ```
 * 0x01 ‖ issuedAt (u64 big-endian, epoch ms) ‖ 16 random bytes ‖ first 16 bytes of HMAC-SHA-256(secret, the 25 bytes before)
 * ```
 *
 * [NONCE_SIZE] bytes in all. The 32-byte secret is drawn from the platform's CSPRNG when the
 * instance is [create]d and is never stored or exported, so a nonce from another instance, or from
 * before a restart, is [NonceCheck.Unknown]: the device fetches another.
 *
 * **Spending keeps a capped set.** A nonce is good while the verifier's time is before
 * `issuedAt + ttl` and its random part is not yet spent. A spent random part is
 * kept until `issuedAt + ttl`, the instant the nonce stops being good, so nothing is forgotten while
 * it could still be accepted. At most [capacity] spent nonces are kept; when every one of them is
 * still live, [spend] answers [NonceSpend.Overloaded], failing closed, rather than forget a live one.
 * Expired entries are swept before a full set refuses, so they never cost a refusal.
 *
 * **The verifier's time never runs backwards.** Every call takes `now`, in epoch milliseconds, from
 * the caller's clock, and the instance answers as of the latest `now` it has seen. Without that, a
 * clock stepped forward would sweep a spent nonce and a clock stepped back again would accept it a
 * second time. A clock that jumps ahead leaves the instance ahead until the clock catches up: nonces
 * issued meanwhile carry the later time, so they still work for their whole [ttl].
 *
 * **One process.** The secret and the spent set belong to this instance. A deployment behind a load
 * balancer needs sticky routing between the nonce fetch and the dial.
 *
 * Safe under a multi-threaded dispatcher: one lock guards the spent set and the time, nothing
 * suspends while it is held, and two concurrent spends of one nonce meet at one insert, which one
 * of them wins.
 */
public class ProofNonces private constructor(
    private val key: HMAC.Key,
    private val random: Random,
    /** How long a nonce is good for after it is issued. */
    public val ttl: Duration,
    /** The most spent nonces kept at once. */
    public val capacity: Int,
) {
    private val ttlMillis = ttl.inWholeMilliseconds
    private val lock = reentrantLock()

    /** The latest `now` any call has passed: the instance's own time, which never moves back. */
    private var latest = Long.MIN_VALUE

    /** Each spent nonce's random part, to the instant it stops being good. */
    private val spent = HashMap<RandomPart, Long>()

    /** No entry of [spent] expires before this, so a full set whose entries are all live is not swept again until then. */
    private var earliestExpiry = Long.MAX_VALUE

    /**
     * A fresh nonce, issued at [now] — or at the latest time this instance has seen, if [now] is
     * earlier. Draws 16 bytes from the CSPRNG; keeps no record of the nonce.
     *
     * @throws IllegalArgumentException when [now] is outside `0..`[MAX_NOW].
     * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
     */
    public suspend fun issue(now: Long): ByteArray {
        val issuedAt = advanceTo(now)
        val body = byteArrayOf(FORMAT) + StatementCodec.time(issuedAt) + random.nextBytes(RANDOM_SIZE)
        return body + mac(body)
    }

    /**
     * Whether [nonce] is one this instance issued and is still good at [now]. Reads the spent set
     * not at all: a nonce that is [NonceCheck.Fresh] here may still be [NonceSpend.Replayed] when
     * it is spent. Never throws on bad input: anything this instance did not issue is
     * [NonceCheck.Unknown].
     *
     * @throws IllegalArgumentException when [now] is outside `0..`[MAX_NOW].
     * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
     */
    public suspend fun check(nonce: ByteArray, now: Long): NonceCheck {
        requireNow(now)
        if (nonce.size != NONCE_SIZE || nonce[0] != FORMAT) return NonceCheck.Unknown
        val body = nonce.copyOfRange(0, BODY_SIZE)
        if (!constantTimeEquals(mac(body), nonce.copyOfRange(BODY_SIZE, NONCE_SIZE))) return NonceCheck.Unknown
        val issuedAt = checkNotNull(StatementCodec.timeOrNull(body.copyOfRange(1, 1 + StatementCodec.TIME_SIZE)))
        if (!isLive(issuedAt, advanceTo(now))) return NonceCheck.Expired
        return NonceCheck.Fresh(this, issuedAt, RandomPart.of(body, 1 + StatementCodec.TIME_SIZE))
    }

    /**
     * Spends the nonce [fresh] names, at [now]: [NonceSpend.Spent] the first time, and
     * [NonceSpend.Replayed] for every spend after, from any thread. Spend only once the proof over
     * the nonce has been checked, so a garbage signature from someone who saw the nonce in flight
     * cannot burn it.
     *
     * [NonceSpend.Expired] when the nonce stopped being good since it was checked, and
     * [NonceSpend.Overloaded] when [capacity] live spent nonces are already kept. Never suspends.
     *
     * @throws IllegalArgumentException when [fresh] came from another instance, or [now] is outside
     *   `0..`[MAX_NOW].
     */
    public fun spend(fresh: NonceCheck.Fresh, now: Long): NonceSpend {
        require(fresh.owner === this) { "this nonce was checked by another ProofNonces instance" }
        requireNow(now)
        val expiry = fresh.issuedAt + ttlMillis
        return lock.withLock {
            val at = advanceLocked(now)
            when {
                !isLive(fresh.issuedAt, at) -> NonceSpend.Expired
                fresh.random in spent -> NonceSpend.Replayed
                spent.size >= capacity && !sweptRoom(at) -> NonceSpend.Overloaded
                else -> {
                    spent[fresh.random] = expiry
                    if (expiry < earliestExpiry) earliestExpiry = expiry
                    NonceSpend.Spent
                }
            }
        }
    }

    /** Sweeps every entry expired at [at], if any can be, and answers whether that made room. Under [lock]. */
    private fun sweptRoom(at: Long): Boolean {
        if (at < earliestExpiry) return false
        spent.entries.removeAll { it.value <= at }
        earliestExpiry = spent.values.minOrNull() ?: Long.MAX_VALUE
        return spent.size < capacity
    }

    /**
     * Whether a nonce issued at [issuedAt] is still good at the instance's time [at]. There is no
     * lower bound: [issue] stamps the instance's own time, which never moves back, so no nonce this
     * instance issued is from its future.
     */
    private fun isLive(issuedAt: Long, at: Long): Boolean = at < issuedAt + ttlMillis

    private fun advanceTo(now: Long): Long {
        requireNow(now)
        return lock.withLock { advanceLocked(now) }
    }

    private fun advanceLocked(now: Long): Long {
        if (now > latest) latest = now
        return latest
    }

    private suspend fun mac(body: ByteArray): ByteArray =
        providerCall(::macFailed) { key.signatureGenerator().generateSignature(body) }.copyOf(MAC_SIZE)

    override fun toString(): String = "ProofNonces(ttl=$ttl, capacity=$capacity)"

    /** A nonce's 16 random bytes, as two `Long`s so they hash and compare by value. */
    internal data class RandomPart(private val high: Long, private val low: Long) {
        companion object {
            fun of(bytes: ByteArray, at: Int): RandomPart = RandomPart(readLong(bytes, at), readLong(bytes, at + Long.SIZE_BYTES))

            private fun readLong(bytes: ByteArray, at: Int): Long =
                (0 until Long.SIZE_BYTES).fold(0L) { acc, i -> (acc shl Byte.SIZE_BITS) or (bytes[at + i].toLong() and 0xFF) }
        }
    }

    public companion object {
        /** The size of a nonce, in bytes. */
        public const val NONCE_SIZE: Int = 41

        /** A nonce is good for this long after it is issued, unless [create] is given another [ttl]. */
        public val DEFAULT_TTL: Duration = 60.seconds

        /** The most spent nonces kept at once, unless [create] is given another [capacity]. */
        public const val DEFAULT_CAPACITY: Int = 65_536

        /** The latest `now`, in epoch milliseconds, any call accepts: far past any real clock, and clear of overflow. */
        public const val MAX_NOW: Long = Long.MAX_VALUE / 2

        /** The longest [ttl] [create] accepts. A nonce is a minute's credential, not a day's. */
        public val MAX_TTL: Duration = 1.days

        /**
         * A new instance, with a fresh 32-byte secret from the platform's CSPRNG.
         *
         * @throws IllegalArgumentException when [ttl] is not between one millisecond and [MAX_TTL],
         *   or [capacity] is not positive.
         * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
         */
        public suspend fun create(ttl: Duration = DEFAULT_TTL, capacity: Int = DEFAULT_CAPACITY): ProofNonces =
            create(CryptographyRandom.Default, ttl, capacity)

        /** [create] drawing the secret and every nonce's random part from [random], so a test can pin them. */
        internal suspend fun create(random: Random, ttl: Duration, capacity: Int): ProofNonces {
            require(ttl >= 1.milliseconds && ttl <= MAX_TTL) { "ttl $ttl is not between 1ms and $MAX_TTL" }
            require(capacity > 0) { "capacity $capacity is not positive" }
            val key = providerCall(::macFailed) {
                platformProvider().get(HMAC).keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, random.nextBytes(SECRET_SIZE))
            }
            return ProofNonces(key, random, ttl, capacity)
        }

        private const val FORMAT: Byte = 0x01
        private const val RANDOM_SIZE = 16
        private const val MAC_SIZE = 16
        private const val SECRET_SIZE = 32
        private const val BODY_SIZE = 1 + StatementCodec.TIME_SIZE + RANDOM_SIZE

        private fun requireNow(now: Long) {
            require(now in 0..MAX_NOW) { "now ($now) is outside 0..$MAX_NOW epoch milliseconds" }
        }

        private fun macFailed(failureClass: String) =
            IllegalStateException("kuilt-identity: HMAC-SHA-256 failed in the platform cryptographic provider ($failureClass)")

        /** Whether [a] and [b] are equal, in a time that depends on their length and not on where they first differ. */
        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}

/** What [ProofNonces.check] found. */
public sealed interface NonceCheck {
    /**
     * Issued by this instance and still good: [ProofNonces.spend] takes it. Only [ProofNonces.check]
     * makes one, so a nonce cannot be spent without being checked first.
     */
    public class Fresh internal constructor(
        internal val owner: ProofNonces,
        internal val issuedAt: Long,
        internal val random: ProofNonces.RandomPart,
    ) : NonceCheck {
        override fun toString(): String = "Fresh(issuedAt=$issuedAt)"
    }

    /** Not a nonce this instance issued: another instance's, one from before a restart, or none at all. Fetch another. */
    public data object Unknown : NonceCheck

    /** Issued here, and no longer good. Fetch another. */
    public data object Expired : NonceCheck
}

/** What [ProofNonces.spend] did. */
public sealed interface NonceSpend {
    /** Spent now, for the first time: the proof over it is good once. */
    public data object Spent : NonceSpend

    /** Already spent. Whoever presents it second is refused. */
    public data object Replayed : NonceSpend

    /** It stopped being good between its check and its spend. Fetch another. */
    public data object Expired : NonceSpend

    /** Every kept spent nonce is still live, so this one cannot be recorded, and is refused rather than one forgotten. Back off. */
    public data object Overloaded : NonceSpend
}
