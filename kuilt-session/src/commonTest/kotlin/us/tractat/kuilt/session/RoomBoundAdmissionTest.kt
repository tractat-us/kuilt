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
 * The gate is fully exercised with a **single host** per [InMemoryLoom]: the joiner
 * broadcasts its Hello on the flat mesh, the host receives it, and the gate accepts or
 * rejects based on the target. (Raw multi-room on one `InMemoryLoom` is forbidden by the
 * #1184 second-`host()` guard; multi-room *isolation* on a shared substrate is proven
 * separately by `MultiRoomIsolationTest` over `InMemoryRoomFabric` — not duplicated here.)
 */
class RoomBoundAdmissionTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    /**
     * Match → admitted. A joiner whose target room equals the host's [Pattern.roomKey]
     * completes the handshake normally.
     */
    @Test
    fun `joiner whose target room matches the host is admitted`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("HostA", roomKey = "room-A"))

            val joiner = factory(loom, backgroundScope).join(InMemoryTag("Bob", roomKey = "room-A"))

            val hostRoster = host.roster.first { it.size == 1 }
            val joinerRoster = joiner.roster.first { it.isNotEmpty() }

            assertAll(
                { assertEquals("Bob", hostRoster.single().identity.displayName) },
                { assertEquals("HostA", joinerRoster.single().identity.displayName) },
            )

            joiner.leave()
            host.leave()
        }

    /**
     * The #1172 property, proven with one host: a joiner whose target room differs from the
     * host's own key is **not** admitted — the host roster stays empty and the joiner observes
     * a loud [AdmitMessage.Reject] (`"room-mismatch: …"`), never a silent drop (a silent drop
     * reproduces the #1172-adjacent hang). One host suffices: the mismatched Hello reaches the
     * host on the flat mesh and the gate rejects it.
     */
    @Test
    fun `joiner whose target room mismatches the host is rejected`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("HostA", roomKey = "room-A"))

            // Drive the joiner at the wire level so the Reject is directly observable.
            val joinerSeam = loom.join(InMemoryTag("Bob", roomKey = "room-B"))
            joinerSeam.broadcast(
                AdmitMessage.encode(
                    AdmitMessage.Hello(
                        displayName = "Bob",
                        sessionId = "session-bob",
                        targetRoom = "room-B",
                    ),
                ),
            )

            val reply = joinerSeam.incoming.first { AdmitMessage.decode(it.toByteArray()) is AdmitMessage.Reject }
            val reject = AdmitMessage.decode(reply.toByteArray()) as AdmitMessage.Reject

            // Let any errant admit land before asserting the host roster stayed empty.
            delay(100)

            assertAll(
                {
                    assertTrue(
                        reject.reason.startsWith("room-mismatch"),
                        "expected a room-mismatch Reject, got: ${reject.reason}",
                    )
                },
                { assertEquals(emptySet(), host.roster.value, "host must not admit a mismatched-room joiner") },
            )

            joinerSeam.close()
            host.leave()
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
            val host = factory(loom, backgroundScope).host(Pattern("Alice", roomKey = "room-A"))

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
}
