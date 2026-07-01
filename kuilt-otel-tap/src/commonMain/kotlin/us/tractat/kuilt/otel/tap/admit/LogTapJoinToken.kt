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
 *
 * ### Entropy ↔ TTL: why the window is short (and must stay short)
 *
 * The code carries about 2⁴⁰ of entropy (8 characters over a 32-symbol alphabet). On the
 * plaintext (`ws://`) wire that this tap targets, the code's secrecy is bounded **in time,
 * not just in size**. Two offline-cracking paths exist within the accepted honest-seam
 * threat model:
 * - a **passive eavesdropper** captures a `Challenge` nonce and the matching `Proof` tag
 *   and grinds candidate codes offline against `HMAC-SHA256(candidate, nonce)`;
 * - in the **role-inverted topology** (the prover hosts), an attacker sends the hosting
 *   prover a `Challenge` purely to harvest a `HMAC(code, nonce)` tag to grind.
 * Either way the attacker only wins if they crack the code **before the token expires** —
 * a recovered code is useless once [isValid] returns false. The short [ttl] is therefore a
 * load-bearing security control, not a UX knob: it is what keeps a ~2⁴⁰ offline search from
 * being worthwhile.
 *
 * **WARNING — do not raise [DEFAULT_TTL].** Widening the window trades directly against the
 * code's entropy: every extra minute is extra offline-cracking budget on the plaintext wire.
 * The 5-minute default is long enough to read and type the code and short enough to bound the
 * search. If a longer-lived tap is genuinely needed, add entropy (a longer code) or move to an
 * encrypted transport — never just lengthen the TTL.
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
        /**
         * Default pairing window: long enough to read and type the code, short enough to bound
         * an offline crack of the ~2⁴⁰ code on the plaintext wire.
         *
         * **Do not raise this.** The TTL bounds the attacker's offline-cracking budget; see the
         * "Entropy ↔ TTL" note on [LogTapJoinToken]. Add entropy or encrypt the transport instead.
         */
        public val DEFAULT_TTL: Duration = 5.minutes

        // Crockford base32 — omits I/L/O/U so the code is unambiguous when read aloud or typed.
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CODE_LENGTH = 8

        /**
         * Mint a fresh token: a [CODE_LENGTH]-character code drawn from [random], stamped with
         * [clock]'s current instant and the given [ttl].
         *
         * **[random] MUST be cryptographically secure in production** — pass
         * [cryptoRandom]. The code is the only secret in the admission scheme; a predictable
         * source (`Random.Default`, or a seeded `Random`) lets an attacker guess it. The
         * `Random` parameter is injectable **only so tests can supply a seeded, deterministic
         * source** — it is not an invitation to use a non-secure RNG in production.
         *
         * [random] and [clock] are **required** — never call an unseeded RNG or the wall clock
         * directly.
         */
        public fun issue(random: Random, clock: Clock, ttl: Duration = DEFAULT_TTL): LogTapJoinToken {
            val code = buildString(CODE_LENGTH) {
                repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
            return LogTapJoinToken(code = code, issuedAt = clock.now(), ttl = ttl)
        }
    }
}
