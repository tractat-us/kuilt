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
 * A2 (#1172): admission is bound to room identity. A host admits a joiner only when the
 * joiner's [AdmitMessage.Hello.targetRoom] agrees with the host's own
 * [Pattern.roomKey] — or names no room at all (permissive).
 *
 * These live in `kuilt-session` (not the TCK): they need **two hosts on one flat mesh**,
 * which the conformance-suite fabrics can't express.
 */
class RoomBoundAdmissionTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    /**
     * The #1172 regression, made local. Two independent `host()` rooms share ONE
     * [InMemoryLoom] (a flat mesh — every seam hears every Hello). A joiner that targets
     * room A must land only in room A; room B's roster must stay empty. Without the gate,
     * both hosts passively admit the same Hello → cross-room admission.
     */
    @Test
    fun `joiner targeting one room is not admitted by another room on the same loom`() =
        runTest {
            val loom = InMemoryLoom()
            val roomA = factory(loom, backgroundScope).host(Pattern("HostA", roomKey = "room-A"))
            val roomB = factory(loom, backgroundScope).host(Pattern("HostB", roomKey = "room-B"))

            val joiner = factory(loom, backgroundScope).join(InMemoryTag("Bob", roomKey = "room-A"))

            // Room A admits the joiner; the joiner sees only room-A members.
            val roomARoster = roomA.roster.first { it.size == 1 }
            val joinerRoster = joiner.roster.first { it.isNotEmpty() }

            // Let any errant admit from room B land before asserting it stayed empty.
            delay(100)

            assertAll(
                { assertEquals("Bob", roomARoster.single().identity.displayName) },
                { assertEquals(emptySet(), roomB.roster.value, "room B must not admit a room-A joiner") },
                { assertEquals(setOf("HostA"), joinerRoster.map { it.identity.displayName }.toSet()) },
            )

            joiner.leave()
            roomA.leave()
            roomB.leave()
        }

    /**
     * Permissive-null policy (pinned so it can't silently flip). A joiner that names no
     * target room (`InMemoryTag.roomKey == null`, the default for every existing fabric)
     * is admitted — the transport is trusted to have bound the room.
     */
    @Test
    fun `joiner with null target room is admitted`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("Alice"))

            val joiner = factory(loom, backgroundScope).join(InMemoryTag("Bob")) // roomKey defaults null

            val hostRoster = host.roster.first { it.size == 1 }
            val joinerRoster = joiner.roster.first { it.isNotEmpty() }

            assertAll(
                { assertEquals("Bob", hostRoster.single().identity.displayName) },
                { assertEquals("Alice", joinerRoster.single().identity.displayName) },
            )

            joiner.leave()
            host.leave()
        }

    /**
     * Mismatch → the host replies with a loud [AdmitMessage.Reject] (`"room-mismatch: …"`),
     * never a silent drop. Driven at the wire level: a raw seam sends a Hello naming the
     * wrong room and observes the Reject on its own incoming.
     */
    @Test
    fun `host rejects a Hello whose target room mismatches`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("Alice", roomKey = "room-A"))

            val rawSeam = loom.join(InMemoryTag("Mallory"))
            rawSeam.broadcast(
                AdmitMessage.encode(
                    AdmitMessage.Hello(
                        displayName = "Mallory",
                        sessionId = "session-mallory",
                        targetRoom = "room-B",
                    ),
                ),
            )

            val reply = rawSeam.incoming.first { AdmitMessage.decode(it.toByteArray()) is AdmitMessage.Reject }
            val reject = AdmitMessage.decode(reply.toByteArray()) as AdmitMessage.Reject

            assertTrue(
                reject.reason.startsWith("room-mismatch"),
                "expected a room-mismatch Reject, got: ${reject.reason}",
            )

            rawSeam.close()
            host.leave()
        }
}
