package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.otel.tap.admit.GateRole
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.otel.tap.admit.tokenGated
import kotlin.random.Random
import kotlin.time.Clock

/**
 * How the tap admits a peer.
 *
 * The default [Open] reproduces the shipped behaviour — no gate — which is safe on a
 * loopback fabric where only local processes can reach the session. To expose the tap on
 * a real network, use a short-lived join code: the offering side holds [Verify] (it
 * challenges each joiner and checks their proof) and the pulling side holds [Present] (it
 * answers the challenge with the code the device showed).
 *
 * The live [Clock]/[Random] a [Verify] needs are carried here, deliberately **outside**
 * [LogTapConfig] — that is a value `data class`, and embedding live dependencies in it
 * would break its equality/hashing.
 */
public sealed interface LogTapAdmission {
    /** No admission gate. The default; intended for loopback. */
    public data object Open : LogTapAdmission

    /**
     * Offering side: challenge joiners and admit only those whose proof matches
     * `HMAC-SHA256(token.code, nonce)` while [token] is valid. Inject a seeded [random]
     * and a fixed [clock] in tests.
     */
    public class Verify(
        public val token: LogTapJoinToken,
        public val clock: Clock,
        public val random: Random,
    ) : LogTapAdmission

    /** Pulling side: answer the offering side's challenge with the shown [code]. */
    public class Present(public val code: String) : LogTapAdmission
}

/** The gate role a peer that **offers** its logs plays. `null` for [LogTapAdmission.Open]. */
internal fun LogTapAdmission.offeringRole(): GateRole? = when (this) {
    is LogTapAdmission.Open -> null
    is LogTapAdmission.Verify -> GateRole.Verifier(token, clock, random)
    is LogTapAdmission.Present ->
        error("LogTapAdmission.Present is for the pulling side; an offering tap needs Verify or Open")
}

/** The gate role a peer that **pulls** logs plays. `null` for [LogTapAdmission.Open]. */
internal fun LogTapAdmission.pullingRole(): GateRole? = when (this) {
    is LogTapAdmission.Open -> null
    is LogTapAdmission.Present -> GateRole.Prover(code)
    is LogTapAdmission.Verify ->
        error("LogTapAdmission.Verify is for the offering side; a pulling client needs Present or Open")
}

/** Wrap [this] with the token gate for [role], or return it unchanged when [role] is `null`. */
internal fun Seam.gatedIfNeeded(role: GateRole?, scope: CoroutineScope): Seam =
    if (role == null) this else tokenGated(role, scope)
