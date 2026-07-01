package us.tractat.kuilt.otel.tap.admit

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A short-lived join code that authorizes a debugger peer to pull the log tap.
 *
 * The device **shows** this code — printed once to the platform log (Xcode console /
 * logcat / stdout) or surfaced in an app debug UI — and the operator types it into the
 * puller. The code itself never crosses the wire: the puller proves knowledge of it by
 * returning `HMAC-SHA256(code, nonce)` to the device's challenge (see [TokenGatedSeam]).
 *
 * The token is valid only within [ttl] of [issuedAt], and is **reusable** for repeated
 * pulls or reconnects inside that window (so a `LogTapClient` that reconnects re-admits
 * seamlessly). Outside the window a proof is refused.
 *
 * [code] is secret material: [toString] redacts it, and it must never be logged except
 * at the single deliberate issuance print.
 */
public class LogTapJoinToken(
    public val code: String,
    public val issuedAt: Instant,
    public val ttl: Duration,
) {
    /** True while [now] falls within `[issuedAt] .. [issuedAt] + [ttl]` (inclusive). */
    public fun isValid(now: Instant): Boolean = now >= issuedAt && now <= issuedAt + ttl

    /** Redacted — never exposes [code]. */
    override fun toString(): String = "LogTapJoinToken(code=****, issuedAt=$issuedAt, ttl=$ttl)"

    public companion object {
        /** Default pairing window: long enough to read and type the code, short enough to bound guessing. */
        public val DEFAULT_TTL: Duration = 5.minutes

        // Crockford base32 — omits I/L/O/U so the code is unambiguous when read aloud or typed.
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CODE_LENGTH = 8

        /**
         * Mint a fresh token: a [CODE_LENGTH]-character code drawn from [random], stamped with
         * [clock]'s current instant and the given [ttl].
         *
         * [random] and [clock] are **required** — inject a seeded [Random] and a fixed [Clock]
         * in tests; never call an unseeded RNG or the wall clock directly.
         */
        public fun issue(random: Random, clock: Clock, ttl: Duration = DEFAULT_TTL): LogTapJoinToken {
            val code = buildString(CODE_LENGTH) {
                repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
            return LogTapJoinToken(code = code, issuedAt = clock.now(), ttl = ttl)
        }
    }
}
