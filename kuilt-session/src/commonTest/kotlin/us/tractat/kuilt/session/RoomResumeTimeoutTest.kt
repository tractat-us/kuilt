@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Regression tests for #1587 — a direct [Room.resume] against a host that never replies must
 * not park the caller forever. It returns [ResumeResult.TimedOut] once
 * [HeartbeatConfig.resumeTimeout] virtual-elapses.
 *
 * Timing: `resumeTimeout` (200 ms) is deliberately far shorter than the heartbeat `timeout`
 * (30 s) so the host-liveness detector cannot drive the room terminal during the resume
 * window — the direct-resume deadline is the *only* thing releasing the caller, which is the
 * exact code path #1587 fixes. Pre-fix, [Room.resume] awaited an uncompleted deferred forever,
 * so these tests hang and hit the tight `runTest` wall-clock timeout / `UncompletedCoroutinesError`.
 */
class RoomResumeTimeoutTest {

    private val config = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 30.seconds,
        reconnectWindow = 60.seconds,
        resumeTimeout = 200.milliseconds,
    )

    /**
     * A single direct `resume(token)` against a black-holed link (host never sees the Resume,
     * so never replies) returns [ResumeResult.TimedOut] after `resumeTimeout`, rather than
     * hanging.
     */
    @Test
    fun `direct resume against a silent host times out instead of hanging`() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (joinerRoom, token, joinerSeam) = admittedJoiner()

        // Black-hole the joiner's link: the Resume broadcast is dropped (the host never sees it)
        // and no ResumeAck/Reject can arrive. The room stays Woven (not terminal).
        joinerSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        val direct = async { joinerRoom.resume(token) }
        advanceTimeBy(config.resumeTimeout + 100.milliseconds)
        runCurrent()

        assertTrue(direct.isCompleted, "resume() must complete on the resumeTimeout deadline, not hang")
        assertIs<ResumeResult.TimedOut>(direct.await(), "a silent host must resolve as TimedOut")
    }

    /**
     * A joined concurrent caller (coalesced onto the same in-flight attempt, #1280) must also be
     * released with [ResumeResult.TimedOut] — the timeout releases every caller of the flight,
     * not just the owner.
     */
    @Test
    fun `a joined concurrent resume also times out`() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (joinerRoom, token, joinerSeam) = admittedJoiner()
        joinerSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))

        val owner = async { joinerRoom.resume(token) }
        runCurrent() // owner installs the flight and broadcasts (dropped)
        assertTrue(joinerRoom.hasPendingResume(), "precondition: the owner's resume flight is in flight")

        val joined = async { joinerRoom.resume(token) }
        runCurrent() // joined coalesces onto the same flight instead of sending a second Resume

        advanceTimeBy(config.resumeTimeout + 100.milliseconds)
        runCurrent()

        assertTrue(owner.isCompleted, "owner resume() must complete on the deadline")
        assertTrue(joined.isCompleted, "joined resume() must complete on the deadline, not hang")
        val ownerResult = owner.await()
        val joinedResult = joined.await()
        assertAll(
            { assertIs<ResumeResult.TimedOut>(ownerResult) },
            { assertIs<ResumeResult.TimedOut>(joinedResult) },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Admitted joiner room + its resume token + its (fault-injectable) seam. */
    private suspend fun TestScope.admittedJoiner(): Triple<SeamRoom, us.tractat.kuilt.session.partition.ResumeToken, FaultySeam> {
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }
        val loom = InMemoryLoom()
        val hostSeam = FaultySeam(loom.host(Pattern("Alice")), backgroundScope, FaultProfile.Healthy)
        makeSeamRoom(hostSeam, SessionRole.Host, "Alice", clock, RoomId("room-1587"))
        val joinerSeam = FaultySeam(loom.join(InMemoryTag("Bob")), backgroundScope, FaultProfile.Healthy)
        val joinerRoom = makeSeamRoom(joinerSeam, SessionRole.Joiner, "Bob", clock)

        joinerRoom.roster.first { it.isNotEmpty() }
        val token = assertNotNull(joinerRoom.resumeToken, "joiner must hold a resume token after admit")
        return Triple(joinerRoom, token, joinerSeam)
    }

    private fun TestScope.makeSeamRoom(
        seam: Seam,
        role: SessionRole,
        displayName: String,
        clock: () -> Instant,
        roomId: RoomId? = null,
    ): SeamRoom =
        SeamRoom(
            seam = seam,
            role = role,
            memberName = displayName,
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = config,
            roomId = roomId,
        ).also { it.start() }
}
