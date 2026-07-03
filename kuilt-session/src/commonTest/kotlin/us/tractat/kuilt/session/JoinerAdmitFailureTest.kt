package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * #1178: a joiner's admit handshake must fail **loudly**, never hang silently.
 *
 * Two terminal failure paths, both surfaced as [MembershipEvent.AdmissionFailed]:
 * - the host actively refuses (a [us.tractat.kuilt.session.admit.AdmitMessage.Reject],
 *   e.g. the #1172 room-mismatch gate) → [AdmissionFailure.Rejected];
 * - no [us.tractat.kuilt.session.admit.AdmitMessage.Welcome] arrives within the admit
 *   deadline (dropped Hello / absent host) → [AdmissionFailure.TimedOut].
 *
 * Before this, both cases left `join()`'s consumer waiting on [Room.roster] forever with
 * no signal — every admit-path regression presented as an opaque hang.
 */
class JoinerAdmitFailureTest {
    private fun factory(
        loom: InMemoryLoom,
        scope: CoroutineScope,
        admitTimeout: kotlin.time.Duration = 30.seconds,
    ) = SeamRoomFactory(
        loom,
        scope,
        clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) },
        admitTimeout = admitTimeout,
    )

    /**
     * Host actively rejects (room-mismatch): the joiner surfaces a terminal
     * [MembershipEvent.AdmissionFailed] carrying the host's reason, and never enters a roster.
     */
    @Test
    fun `a rejected joiner surfaces AdmissionFailed with a Rejected reason`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("HostA", roomKey = "room-A"))

            val joiner = factory(loom, backgroundScope).join(InMemoryTag("Bob", roomKey = "room-B"))

            val event = joiner.events.first { it is MembershipEvent.AdmissionFailed }
            val reason = (event as MembershipEvent.AdmissionFailed).reason

            assertAll(
                { assertTrue(reason is AdmissionFailure.Rejected, "expected Rejected, got $reason") },
                {
                    val message = (reason as AdmissionFailure.Rejected).message
                    assertTrue(
                        message.startsWith("room-mismatch"),
                        "expected a room-mismatch message, got: $message",
                    )
                },
                { assertEquals(emptySet(), joiner.roster.value, "a rejected joiner must not populate its roster") },
            )

            host.leave()
        }

    /**
     * No host ever Welcomes: the joiner's Hello reaches no one, and after the admit deadline
     * the joiner surfaces a terminal [MembershipEvent.AdmissionFailed] with [AdmissionFailure.TimedOut]
     * instead of hanging forever. Virtual time advances the deadline with no real wait.
     */
    @Test
    fun `a joiner that receives no Welcome times out with a TimedOut reason`() =
        runTest {
            val loom = InMemoryLoom()
            // No host wraps this mesh — the joiner's broadcast Hello is delivered to no one.
            val joiner = factory(loom, backgroundScope, admitTimeout = 5.seconds)
                .join(InMemoryTag("Bob"))

            val event = joiner.events.first { it is MembershipEvent.AdmissionFailed }

            assertEquals(
                AdmissionFailure.TimedOut,
                (event as MembershipEvent.AdmissionFailed).reason,
            )
        }

    /**
     * The happy path is untouched: a joiner admitted before the deadline never emits
     * [MembershipEvent.AdmissionFailed], even after the deadline would have elapsed.
     */
    @Test
    fun `an admitted joiner never surfaces AdmissionFailed`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope, admitTimeout = 1.seconds)
                .host(Pattern("HostA", roomKey = "room-A"))

            val joiner = factory(loom, backgroundScope, admitTimeout = 1.seconds)
                .join(InMemoryTag("Bob", roomKey = "room-A"))

            // Admitted normally.
            joiner.roster.first { it.isNotEmpty() }

            // Give virtual time room to pass the (short) admit deadline; no failure must fire.
            val failed = kotlinx.coroutines.withTimeoutOrNull(10.seconds) {
                joiner.events.first { it is MembershipEvent.AdmissionFailed }
            }
            assertEquals(null, failed, "an admitted joiner must not emit AdmissionFailed after the deadline")

            joiner.leave()
            host.leave()
        }
}
