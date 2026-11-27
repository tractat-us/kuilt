package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

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
 * 0x01 ‖ issuedAt (u64 big-endian) ‖ 16 random bytes ‖ first 16 bytes of HMAC-SHA-256(secret, the 25 bytes before)
 * ```
 *
 * [NONCE_SIZE] bytes in all. `issuedAt` is not a date. It counts the milliseconds this instance's
 * time source had run since the instance was made, and only this instance reads it. The 32-byte
 * secret is drawn from the platform's CSPRNG and is never stored or exported, so a nonce from
 * another instance, or from before a restart, is [NonceCheck.Unknown]: the device fetches another.
 *
 * **Time runs one way, so no clock step can reopen a spent nonce.** A nonce is good until [ttl] has
 * passed on the instance's monotonic [TimeSource] since it was issued, and its spent random part is
 * kept exactly that long, on the same source. That source never moves back, so acceptance and
 * retention end at one instant however the wall clock is set, and nothing is forgotten while it
 * could still be accepted. At most [capacity] spent nonces are kept; when every one of them is
 * still live, [spend] answers [NonceSpend.Overloaded], failing closed, rather than forget a live
 * one. Expired entries are swept before a full set refuses, so they never cost a refusal.
 *
 * **When the two clocks disagree, a new secret is drawn.** The instance also reads the wall
 * [Clock], and compares how much time each clock says has passed since the secret was drawn. When
 * they differ by more than [ttl], it draws a new secret and starts an empty spent set. That covers
 * a wall clock stepped back or forward, and a machine that slept while its time source, as some
 * platforms' do, stopped counting. Every outstanding nonce is then [NonceCheck.Unknown], which the
 * device recovers from by fetching another, and a [NonceCheck.Fresh] checked before the change is
 * [NonceSpend.Expired] after it. So a nonce is good for [ttl] on the time source and for less than
 * three [ttl]s of wall-clock time, however long the machine sleeps. The new secret is drawn on the
 * next [issue] or [check], since [spend] never suspends; until then [spend] refuses.
 *
 * **One process.** The secret, the spent set and the time `issuedAt` counts on all belong to this
 * instance. That is why a nonce needs no lower bound on its time: no nonce this instance issued is
 * from its future. A deployment behind a load balancer needs sticky routing between the nonce fetch
 * and the dial.
 *
 * Safe under a multi-threaded dispatcher: one lock guards the current secret and its spent set,
 * nothing suspends while it is held, and two concurrent spends of one nonce meet at one insert,
 * which one of them wins.
 *
 * @sample us.tractat.kuilt.identity.sampleProofNonces
 */
public class ProofNonces private constructor(
    private val random: Random,
    timeSource: TimeSource,
    private val clock: Clock,
    firstKey: HMAC.Key,
    /** How long a nonce is good for after it is issued, on the time source. */
    public val ttl: Duration,
    /** The most spent nonces kept at once. */
    public val capacity: Int,
) {
    private val ttlMillis = ttl.inWholeMilliseconds
    private val lock = reentrantLock()

    /** The instance's own time: what `issuedAt` counts from. */
    private val origin = timeSource.markNow()

    /** The current secret and the nonces spent under it. Replaced whole when the clocks disagree. */
    private var epoch = Epoch(firstKey, clock.now(), Duration.ZERO)

    /**
     * A fresh nonce, issued now. Draws 16 bytes from the CSPRNG; keeps no record of the nonce.
     *
     * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
     */
    public suspend fun issue(): ByteArray {
        val epoch = currentEpoch()
        val issuedAt = origin.elapsedNow().inWholeMilliseconds
        val body = byteArrayOf(FORMAT) + StatementCodec.time(issuedAt) + random.nextBytes(RANDOM_SIZE)
        return body + epoch.mac(body)
    }

    /**
     * Whether [nonce] is one this instance issued under its current secret and is still good now.
     * Reads the spent set not at all: a nonce that is [NonceCheck.Fresh] here may still be
     * [NonceSpend.Replayed] when it is spent. Never throws on bad input: anything this instance did
     * not issue, or issued under a secret it has since replaced, is [NonceCheck.Unknown].
     *
     * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
     */
    public suspend fun check(nonce: ByteArray): NonceCheck {
        if (nonce.size != NONCE_SIZE || nonce[0] != FORMAT) return NonceCheck.Unknown
        val epoch = currentEpoch()
        val body = nonce.copyOfRange(0, BODY_SIZE)
        if (!constantTimeEquals(epoch.mac(body), nonce.copyOfRange(BODY_SIZE, NONCE_SIZE))) return NonceCheck.Unknown
        val issuedAt = checkNotNull(StatementCodec.timeOrNull(body.copyOfRange(1, 1 + StatementCodec.TIME_SIZE)))
        if (!isLive(issuedAt, origin.elapsedNow().inWholeMilliseconds)) return NonceCheck.Expired
        return NonceCheck.Fresh(this, epoch, issuedAt, RandomPart.of(body, 1 + StatementCodec.TIME_SIZE))
    }

    /**
     * Spends the nonce [fresh] names: [NonceSpend.Spent] the first time, and [NonceSpend.Replayed]
     * for every spend after, from any thread. Spend only once the proof over the nonce has been
     * checked, so a garbage signature from someone who saw the nonce in flight cannot burn it.
     *
     * [NonceSpend.Expired] when the nonce stopped being good since it was checked, because its [ttl]
     * ran out or the clocks came to disagree, and [NonceSpend.Overloaded] when [capacity] live spent
     * nonces are already kept. Never suspends.
     *
     * @throws IllegalArgumentException when [fresh] came from another instance.
     */
    public fun spend(fresh: NonceCheck.Fresh): NonceSpend {
        require(fresh.owner === this) { "this nonce was checked by another ProofNonces instance" }
        val expiry = fresh.issuedAt + ttlMillis
        return lock.withLock {
            val elapsed = origin.elapsedNow()
            val at = elapsed.inWholeMilliseconds
            val current = epoch
            when {
                fresh.epoch !== current || !current.clocksAgree(elapsed) || !isLive(fresh.issuedAt, at) -> NonceSpend.Expired
                fresh.random in current.spent -> NonceSpend.Replayed
                current.spent.size >= capacity && !current.sweptRoom(at) -> NonceSpend.Overloaded
                else -> {
                    current.spent[fresh.random] = expiry
                    if (expiry < current.earliestExpiry) current.earliestExpiry = expiry
                    NonceSpend.Spent
                }
            }
        }
    }

    /**
     * The epoch to issue and check under: the current one while the clocks agree, else a new one
     * under a fresh secret. When two callers both find the clocks disagreeing, both draw a secret
     * and the first to take the lock installs its epoch. The second then finds the clocks agreeing
     * with that epoch and uses it.
     */
    private suspend fun currentEpoch(): Epoch {
        lock.withLock { epoch.takeIf { it.clocksAgree(origin.elapsedNow()) } }?.let { return it }
        val key = drawKey(random)
        return lock.withLock {
            val elapsed = origin.elapsedNow()
            if (!epoch.clocksAgree(elapsed)) epoch = Epoch(key, clock.now(), elapsed)
            epoch
        }
    }

    /** Whether the wall clock and the time source agree, to within [ttl], on the time since this epoch began. Under [lock]. */
    private fun Epoch.clocksAgree(elapsed: Duration): Boolean =
        ((clock.now() - wallStart) - (elapsed - sourceStart)).absoluteValue <= ttl

    /** Sweeps every entry expired at [at], if any can be, and answers whether that made room. Under [lock]. */
    private fun Epoch.sweptRoom(at: Long): Boolean {
        if (at < earliestExpiry) return false
        spent.entries.removeAll { it.value <= at }
        earliestExpiry = spent.values.minOrNull() ?: Long.MAX_VALUE
        return spent.size < capacity
    }

    /** Whether a nonce issued at [issuedAt] is still good at [at], both in milliseconds on the time source. */
    private fun isLive(issuedAt: Long, at: Long): Boolean = at < issuedAt + ttlMillis

    override fun toString(): String = "ProofNonces(ttl=$ttl, capacity=$capacity)"

    /**
     * One secret, the nonces spent under it, and what each clock read when it was drawn: the wall
     * clock at [wallStart], and the time source at [sourceStart] past the instance's origin. Its
     * mutable state is guarded by the owning instance's lock.
     */
    internal class Epoch(private val key: HMAC.Key, val wallStart: Instant, val sourceStart: Duration) {
        /** Each spent nonce's random part, to the millisecond on the time source it stops being good. */
        val spent = HashMap<RandomPart, Long>()

        /** No entry of [spent] expires before this, so a full set whose entries are all live is not swept again until then. */
        var earliestExpiry = Long.MAX_VALUE

        suspend fun mac(body: ByteArray): ByteArray =
            providerCall(::macFailed) { key.signatureGenerator().generateSignature(body) }.copyOf(MAC_SIZE)
    }

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

        /** The longest [ttl] [create] accepts. A nonce is a minute's credential, not a day's. */
        public val MAX_TTL: Duration = 1.days

        /**
         * A new instance, with a fresh 32-byte secret from the platform's CSPRNG. A nonce's life is
         * timed on [timeSource], which must be monotonic; [clock] is read only to notice the two
         * disagreeing. A test drives both, and moves the wall clock with the time source unless the
         * step is what it is testing.
         *
         * @throws IllegalArgumentException when [ttl] is not between one millisecond and [MAX_TTL],
         *   or [capacity] is not positive.
         * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
         */
        public suspend fun create(
            ttl: Duration = DEFAULT_TTL,
            capacity: Int = DEFAULT_CAPACITY,
            timeSource: TimeSource = TimeSource.Monotonic,
            clock: Clock = Clock.System,
        ): ProofNonces = create(CryptographyRandom.Default, ttl, capacity, timeSource, clock)

        /** [create] drawing every secret and every nonce's random part from [random], so a test can pin them. */
        internal suspend fun create(random: Random, ttl: Duration, capacity: Int, timeSource: TimeSource, clock: Clock): ProofNonces {
            require(ttl >= 1.milliseconds && ttl <= MAX_TTL) { "ttl $ttl is not between 1ms and $MAX_TTL" }
            require(capacity > 0) { "capacity $capacity is not positive" }
            return ProofNonces(random, timeSource, clock, drawKey(random), ttl, capacity)
        }

        private const val FORMAT: Byte = 0x01
        private const val RANDOM_SIZE = 16
        private const val MAC_SIZE = 16
        private const val SECRET_SIZE = 32
        private const val BODY_SIZE = 1 + StatementCodec.TIME_SIZE + RANDOM_SIZE

        /** A new 32-byte secret, drawn from [random]. */
        private suspend fun drawKey(random: Random): HMAC.Key = providerCall(::macFailed) {
            platformProvider().get(HMAC).keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, random.nextBytes(SECRET_SIZE))
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
     * makes one, so a nonce cannot be spent without being checked first. It names the secret it was
     * checked under, so it cannot be spent once that secret is replaced.
     */
    public class Fresh internal constructor(
        internal val owner: ProofNonces,
        internal val epoch: ProofNonces.Epoch,
        internal val issuedAt: Long,
        internal val random: ProofNonces.RandomPart,
    ) : NonceCheck {
        override fun toString(): String = "Fresh(issuedAt=$issuedAt)"
    }

    /**
     * Not a nonce this instance issued under its current secret: another instance's, one from
     * before a restart or from before the clocks disagreed, or none at all. Fetch another.
     */
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

    /** It stopped being good between its check and its spend: its ttl ran out, or the clocks came to disagree. Fetch another. */
    public data object Expired : NonceSpend

    /** Every kept spent nonce is still live, so this one cannot be recorded, and is refused rather than one forgotten. Back off. */
    public data object Overloaded : NonceSpend
}
