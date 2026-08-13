package us.tractat.kuilt.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The default values a [Loom] factory hands to the knobs that every fabric shares.
 *
 * A fabric factory's universal parameters — `selfId`, `policy`, `weaveTimeout` — should
 * default to the same thing whichever fabric you reach for, so that moving a consumer
 * from one transport to another changes the transport and nothing else. This object is
 * where those shared defaults live; `selfId`'s counterpart is [freshPeerId].
 *
 * Only knobs a fabric can genuinely *honour* belong on its factory. A fabric with no
 * weave-timeout wiring takes no `weaveTimeout` parameter rather than accepting one and
 * ignoring it — a silently-dropped argument is worse than an absent one.
 */
public object LoomDefaults {
    /**
     * How long a `weave` waits to reach its first peer before giving up.
     *
     * This bounds *rendezvous*, not delivery: the clock covers discovery, dialling and the
     * fabric's own handshake, and stops the moment the seam has a peer. It is deliberately
     * finite — a `weave` that waits forever gives a consumer no way to tell "still looking"
     * from "wedged" — and deliberately generous, because the slow paths it has to clear are
     * a cold mDNS cache, an AWDL link coming up, and a relay's TLS handshake, each of which
     * can take seconds on its own. A "wait for a friend to join" lobby wants a much larger
     * value and should pass one explicitly.
     *
     * The value is the one every fabric that bounds a weave already converged on
     * independently: `NwLoom.DEFAULT_WEAVE_TIMEOUT` and `:kuilt-webrtc`'s handshake runner
     * both chose it before this constant existed. It is a shared default, not a measured
     * optimum.
     *
     * A fabric carrying the same number is not automatically a client of this constant.
     * `:kuilt-nearby`'s connect state machine also uses 30 s, but it bounds one connection's
     * handshake with discovery *outside* the clock — `NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT`,
     * a different population that moves independently (#1430). Check what a candidate's clock
     * actually starts and stops on before folding it in here.
     */
    public val WEAVE_TIMEOUT: Duration = 30.seconds
}
