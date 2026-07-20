package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protocol-version negotiation in the admit handshake (#1569). A host compares a joiner's
 * declared [AdmitMessage.Hello.protocolVersion] against its supported range ([ProtocolVersion])
 * and, on a mismatch, rejects at admit time with [RejectCode.ProtocolMismatch] — terminal, so the
 * joiner surfaces it through [AdmissionFailure.Rejected] and stops rather than retrying a version
 * it can never speak.
 *
 * The gate is exercised with a **single host** per [InMemoryLoom], exactly as
 * [RoomBoundAdmissionTest] does for the room-key gate: the joiner broadcasts its `Hello` on the
 * flat mesh, the host receives it, and the gate accepts or rejects on the declared version.
 */
class ProtocolVersionAdmissionTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    /**
     * The #1569 property: a joiner declaring a protocol version outside the host's supported range
     * is **not** admitted — the host roster stays empty and the joiner observes a loud
     * [AdmitMessage.Reject] carrying [RejectCode.ProtocolMismatch], never a silent drop.
     */
    @Test
    fun `joiner on an unsupported protocol version is rejected with ProtocolMismatch`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("HostA"))

            // Drive the joiner at the wire level so the Reject is directly observable, announcing a
            // version far beyond anything this build supports.
            val joinerSeam = loom.join(InMemoryTag("Bob"))
            joinerSeam.broadcast(
                AdmitMessage.encode(
                    AdmitMessage.Hello(
                        displayName = "Bob",
                        sessionId = "session-bob",
                        protocolVersion = ProtocolVersion.MAX_SUPPORTED + 999,
                    ),
                ),
            )

            val reply = joinerSeam.incoming.first { AdmitMessage.decode(it.toByteArray()) is AdmitMessage.Reject }
            val reject = AdmitMessage.decode(reply.toByteArray()) as AdmitMessage.Reject

            // Let any errant admit land before asserting the host roster stayed empty.
            delay(100)

            assertAll(
                { assertEquals(RejectCode.ProtocolMismatch, reject.code, "expected a protocol-mismatch code") },
                { assertTrue(!reject.code.retryable, "a protocol mismatch is terminal, not retryable") },
                { assertEquals(emptySet(), host.roster.value, "host must not admit an unsupported-version joiner") },
            )

            joinerSeam.close()
            host.leave()
        }

    /**
     * Wire additivity at the room level: a joiner whose `Hello` carries **no** version (a peer that
     * predates #1569 — `protocolVersion == null`) is still admitted, decoding as legacy exactly as a
     * pre-change build would send. Version negotiation must not lock out older peers.
     */
    @Test
    fun `legacy joiner with no protocol version still admits`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("HostA"), memberName = "HostA")

            val joinerSeam = loom.join(InMemoryTag("Bob"))
            joinerSeam.broadcast(
                AdmitMessage.encode(
                    // protocolVersion defaults to null — the legacy, version-less form.
                    AdmitMessage.Hello(displayName = "Bob", sessionId = "session-bob"),
                ),
            )

            val welcomed = host.roster.first { it.size == 1 }
            assertEquals("Bob", welcomed.single().identity.displayName, "a version-less hello must admit")

            joinerSeam.close()
            host.leave()
        }

    /**
     * The happy path: a joiner on the current protocol version — the normal `join()` path, which now
     * stamps [ProtocolVersion.CURRENT] into its `Hello` — completes the handshake normally.
     */
    @Test
    fun `joiner on the current protocol version is admitted`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("HostA"), memberName = "HostA")
            val joiner = factory(loom, backgroundScope).join(InMemoryTag("Bob"), memberName = "Bob")

            val hostRoster = host.roster.first { it.size == 1 }
            val joinerRoster = joiner.roster.first { it.isNotEmpty() }

            assertAll(
                { assertEquals("Bob", hostRoster.single().identity.displayName) },
                { assertEquals("HostA", joinerRoster.single().identity.displayName) },
            )

            joiner.leave()
            host.leave()
        }
}
