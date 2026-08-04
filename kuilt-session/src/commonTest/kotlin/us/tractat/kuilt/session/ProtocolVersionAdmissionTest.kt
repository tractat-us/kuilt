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
     * The #1994 inversion of the old permissive-null policy. A joiner whose `Hello` carries **no**
     * version predates #1569 and is therefore *definitionally* incapable of relaying between spokes
     * of a star fabric, so it is now **refused** with a terminal [RejectCode.ProtocolMismatch]
     * rather than admitted as legacy — leaving `null` permissive would re-admit exactly the
     * population the v2 bump exists to exclude.
     *
     * This test previously pinned the opposite ("a version-less hello must admit"). It is inverted
     * rather than deleted so the policy flip stays visible and cannot silently flip back.
     */
    @Test
    fun `version-less joiner is refused since protocol version 2`() =
        runTest {
            val loom = InMemoryLoom()
            val host = factory(loom, backgroundScope).host(Pattern("HostA"), memberName = "HostA")

            val legacySeam = loom.join(InMemoryTag("Bob"))
            legacySeam.broadcast(
                AdmitMessage.encode(
                    // protocolVersion defaults to null — the legacy, version-less form.
                    AdmitMessage.Hello(displayName = "Bob", sessionId = "session-bob"),
                ),
            )

            val reply = legacySeam.incoming.first { AdmitMessage.decode(it.toByteArray()) is AdmitMessage.Reject }
            val reject = AdmitMessage.decode(reply.toByteArray()) as AdmitMessage.Reject

            // Positive control: the same host admits a joiner that DOES declare the current version,
            // so the roster assertion below is attributable to the gate and not to an inert host.
            val currentSeam = loom.join(InMemoryTag("Carol"))
            currentSeam.broadcast(
                AdmitMessage.encode(
                    AdmitMessage.Hello(
                        displayName = "Carol",
                        sessionId = "session-carol",
                        protocolVersion = ProtocolVersion.CURRENT,
                    ),
                ),
            )
            val admitted = host.roster.first { it.size == 1 }

            assertAll(
                { assertEquals(RejectCode.ProtocolMismatch, reject.code, "a version-less hello must be refused") },
                { assertTrue(!reject.code.retryable, "a protocol mismatch is terminal, not retryable") },
                {
                    assertEquals(
                        "Carol",
                        admitted.single().identity.displayName,
                        "only the version-declaring joiner is admitted",
                    )
                },
            )

            legacySeam.close()
            currentSeam.close()
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
