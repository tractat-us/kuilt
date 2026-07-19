package us.tractat.kuilt.liveness

import kotlin.time.Instant

/**
 * What happened to a session that started out with nobody else in it.
 *
 * A [SoloDeadlineDetector] emits exactly one of these, ever: either somebody showed up in
 * time ([Paired]) or nobody did ([NeverPaired]).
 */
public sealed interface SoloDeadlineEvent {
    /** When the outcome was decided, read from the detector's injected clock. */
    public val at: Instant

    /**
     * Membership stayed below the minimum for the whole deadline — nobody ever joined.
     *
     * This is the "reap me" signal for a room, table or lobby that was created and then
     * abandoned. The detector only reports it; closing the room or the seam is the
     * consumer's policy call.
     *
     * @param observed the most recent membership size seen, or `0` if
     *   [SoloDeadlineDetector.observeMembership] was never called.
     * @param required the configured [SoloDeadlineDetector] minimum.
     */
    public data class NeverPaired(
        val observed: Int,
        val required: Int,
        override val at: Instant,
    ) : SoloDeadlineEvent

    /**
     * Membership reached the minimum before the deadline elapsed; the detector disarms
     * permanently and will never emit again.
     */
    public data class Paired(
        override val at: Instant,
    ) : SoloDeadlineEvent
}
