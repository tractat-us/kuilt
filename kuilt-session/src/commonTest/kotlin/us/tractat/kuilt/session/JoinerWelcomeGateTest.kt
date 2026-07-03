package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1180: joiner-side cross-admit hardening. Once a joiner has identified its host, a
 * [AdmitMessage.Welcome] from any *other* sender must be dropped — a foreign host on a flat
 * loom must not pollute the joiner's roster or hijack its host identity.
 *
 * Complements the #1172 Change A host-side gate (which stops foreign Welcomes at the source);
 * this is the cheap two-ended belt-and-suspenders on the receiving end.
 */
class JoinerWelcomeGateTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    /**
     * After the joiner is admitted by its real host, a foreign peer broadcasting a host-intro
     * [AdmitMessage.Welcome] is ignored: the joiner's roster stays exactly {host}.
     */
    @Test
    fun `a Welcome from a non-host sender is dropped once the host is established`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope)
                .host(Pattern("HostA", roomKey = "room-A"), memberName = "HostA")
            val joiner = factory(loom, backgroundScope)
                .join(InMemoryTag("Bob", roomKey = "room-A"), memberName = "Bob")

            // Admitted: the joiner's roster now holds exactly its host.
            val admittedRoster = joiner.roster.first { it.isNotEmpty() }
            assertEquals("HostA", admittedRoster.single().identity.displayName)

            // A foreign peer joins the flat mesh and impersonates a host self-intro.
            val foreign = loom.join(InMemoryTag("Foreign"))
            foreign.broadcast(
                AdmitMessage.encode(
                    AdmitMessage.Welcome(
                        assignedPeerId = foreign.selfId.value,
                        displayName = "Foreign",
                        sessionId = foreign.selfId.value,
                    ),
                ),
            )
            // Let the errant Welcome land before asserting it changed nothing.
            delay(100)

            assertAll(
                { assertEquals(1, joiner.roster.value.size, "roster must not grow from a foreign Welcome") },
                { assertEquals("HostA", joiner.roster.value.single().identity.displayName) },
                {
                    assertTrue(
                        joiner.roster.value.none { it.id == foreign.selfId },
                        "foreign peer must not appear in the joiner's roster",
                    )
                },
            )

            foreign.close()
            joiner.leave()
            host.leave()
        }
}
