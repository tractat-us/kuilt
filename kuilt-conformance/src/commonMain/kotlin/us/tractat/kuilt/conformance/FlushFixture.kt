package us.tractat.kuilt.conformance

import us.tractat.kuilt.test.fabric.SendHold

/**
 * What a conformance harness says about `Seam.flush`, and what it can do to test it.
 *
 * The hard part of `flush` is a frame that has been accepted and has not left yet. The reference
 * cannot show one: `InMemoryLoom` delivers before `sendTo` returns, so a suite running against it
 * passes whether or not `flush` waits for anything. So the properties that matter need a harness that
 * can stop a send partway, and this says which harnesses can.
 *
 * No arm is taken on trust. [SeamConformanceSuite.flushDeclarationIsHonest] asks the seam, and each
 * arm has to agree with the answer.
 */
public sealed interface FlushFixture {

    /**
     * This fabric answers `FlushOutcome.Unsupported`: it cannot confirm a drain yet, and says so.
     *
     * The default of [SeamConformanceSuite.flushFixture], so a harness that says nothing is tracked
     * rather than silently green, and it cannot go stale in the flattering direction. The day the
     * fabric starts answering `Drained`, the honesty property reds until the harness says more.
     *
     * @property trackingUrl where the gap is tracked.
     */
    public data class Unsupported(val trackingUrl: String) : FlushFixture

    /**
     * This fabric confirms a drain, and the harness cannot stop a send partway.
     *
     * Two honest reasons: the reference has no queue to hold, and a real socket has no test-side
     * hook between the seam and the kernel.
     *
     * **What this arm cannot detect.** A `flush` that waits for nothing still passes everywhere the
     * queue happens to clear in time. The only property left is that a burst survives flush-then-close,
     * and it sees the queue only as often as that race goes wrong. Pair a real-socket arm with a
     * [Holdable] harness over the same seam, as `TcpConformanceTest` and `IdentifiedConformanceTest`
     * do for `LinkSeam`.
     *
     * @property reason why no hold is possible here, for the next reader.
     */
    public data class CannotHold(val reason: String) : FlushFixture

    /**
     * This fabric confirms a drain, and the harness can stop a send where the transport would.
     *
     * The only arm that can prove `flush` waits. It needs virtual time: the properties let the
     * scheduler run, then read [SendHold.parkedSends], so a real-IO harness would red on the rig
     * check rather than pass on a hold that never fired.
     *
     * @property host the hold on the host seam's sends, for the current pair.
     * @property joiner the hold on the joiner seam's sends, for the current pair.
     */
    public class Holdable(public val host: SendHold, public val joiner: SendHold) : FlushFixture
}
