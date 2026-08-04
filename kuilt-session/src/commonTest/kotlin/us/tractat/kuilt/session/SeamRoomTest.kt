package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Acceptance tests for [SeamRoom] + [SeamRoomFactory] against [InMemoryLoom].
 *
 * All tests use virtual time ([runTest]) and [backgroundScope] for admit loops.
 * No wall-clock. No randomness.
 */
class SeamRoomTest {
    private fun loom() = InMemoryLoom()
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    // ── Role ──────────────────────────────────────────────────────────────────

    @Test
    fun `host returns Room with role Host`() =
        runTest {
            val room = factory(loom(), backgroundScope).host(Pattern("Alice"))
            assertEquals(SessionRole.Host, room.role.value)
            room.leave()
        }

    @Test
    fun `join returns Room with role Joiner`() =
        runTest {
            val loom = loom()
            factory(loom, backgroundScope).host(Pattern("Alice"))
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"))
            assertEquals(SessionRole.Joiner, joinerRoom.role.value)
            joinerRoom.leave()
        }

    // ── Roster gate: roster ≠ Seam.peers ─────────────────────────────────────

    @Test
    fun `roster is empty when no peer has completed handshake`() =
        runTest {
            val room = factory(loom(), backgroundScope).host(Pattern("Alice"))
            assertEquals(emptySet(), room.roster.value)
            room.leave()
        }

    @Test
    fun `joiner appears in host roster only after completing handshake`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"), memberName = "Alice")
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"), memberName = "Bob")

            val hostRoster = hostRoom.roster.first { it.size == 1 }

            assertAll(
                { assertEquals(1, hostRoster.size) },
                { assertEquals("Bob", hostRoster.first().identity.displayName) },
                { assertEquals(Liveness.Connected, hostRoster.first().liveness) },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    @Test
    fun `host appears in joiner roster after handshake completes`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"), memberName = "Alice")
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"), memberName = "Bob")

            val joinerRoster = joinerRoom.roster.first { it.isNotEmpty() }

            assertAll(
                { assertEquals(1, joinerRoster.size) },
                { assertEquals("Alice", joinerRoster.first().identity.displayName) },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    @Test
    fun `peer connected to Seam but not admitted is NOT in roster`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))

            // Connect a raw Seam without doing the admit handshake
            val rawSeam = loom.join(InMemoryTag("RawPeer"))

            // Give the host's admit loop time to process (it won't add an unadmitted peer)
            delay(50)

            assertAll(
                { assertTrue(loom.peers.value.size >= 2) },
                { assertEquals(emptySet(), hostRoom.roster.value) },
            )

            rawSeam.close()
            hostRoom.leave()
        }

    // ── MembershipEvent: Joined / Left ────────────────────────────────────────

    @Test
    fun `Joined event fires when joiner completes handshake`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))

            // Subscribe before triggering join to avoid missing the event
            val joinedDeferred = CompletableDeferred<MembershipEvent.Joined>()
            val collectJob = backgroundScope.launch {
                hostRoom.events.collect { event ->
                    if (event is MembershipEvent.Joined) joinedDeferred.complete(event)
                }
            }
            yield()

            factory(loom, backgroundScope).join(InMemoryTag("Bob"), memberName = "Bob")

            val event = joinedDeferred.await()
            collectJob.cancel()
            assertEquals("Bob", event.member.identity.displayName)
        }

    @Test
    fun `Left event fires when admitted member leaves`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }

            // Subscribe before triggering leave
            val leftDeferred = CompletableDeferred<MembershipEvent.Left>()
            val collectJob = backgroundScope.launch {
                hostRoom.events.collect { event ->
                    if (event is MembershipEvent.Left) leftDeferred.complete(event)
                }
            }
            yield()

            joinerRoom.leave()

            val event = leftDeferred.await()
            collectJob.cancel()
            assertIs<MembershipEvent.Left>(event)
        }

    // ── broadcast / sendTo ────────────────────────────────────────────────────

    @Test
    fun `broadcast from host reaches admitted joiner via incoming`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val payload = "hello from host".encodeToByteArray()
            val frameJob = async { joinerRoom.incoming.first() }
            hostRoom.broadcast(payload)

            val frame = frameJob.await()
            assertAll(
                { assertEquals(hostRoom.selfId, frame.sender) },
                { assertTrue(payload.contentEquals(frame.payload)) },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    @Test
    fun `sendTo delivers frame only to target peer`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val payload = "direct message".encodeToByteArray()
            val frameJob = async { joinerRoom.incoming.first() }
            hostRoom.sendTo(joinerRoom.selfId, payload)

            val frame = frameJob.await()
            assertAll(
                { assertEquals(hostRoom.selfId, frame.sender) },
                { assertTrue(payload.contentEquals(frame.payload)) },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── Frame filtering: unadmitted peers ────────────────────────────────────

    @Test
    fun `frames from unadmitted peer are dropped from incoming`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))
            val rawSeam = loom.join(InMemoryTag("Intruder"))

            var appFrameReceived = false
            val job = launch {
                hostRoom.incoming.collect { appFrameReceived = true }
            }

            rawSeam.broadcast("should be dropped".encodeToByteArray())
            delay(100)
            job.cancel()

            assertFalse(appFrameReceived, "application frame from unadmitted peer must be dropped")

            rawSeam.close()
            hostRoom.leave()
        }

    @Test
    fun `application frames from admitted peer are NOT dropped`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            // Deliberately not "real message": `r` is 0x72, which [RoomFramePrefix.Relay] reserves
            // (#2007/#1994), so that payload is now classified as a relay frame and dropped as
            // malformed. That is the documented release note on the prefix, not a regression. Any
            // first byte outside the registry keeps this a test about admitted-peer routing.
            //
            // The await is BOUNDED, in virtual time, for the same reason: an unbounded `first()`
            // turns any future classification change that drops this payload into a 60-second hang
            // rather than a failure, and a hang is the one outcome that reports nothing about why.
            // The bound measures the test scheduler, not the host machine, so it is neither
            // load-sensitive nor a real-time ceiling (#1739/#1891).
            val appPayload = "message from an admitted peer".encodeToByteArray()
            val frameJob = async { withTimeoutOrNull(APP_FRAME_BUDGET) { hostRoom.incoming.first() } }
            joinerRoom.broadcast(appPayload)

            val frame = assertNotNull(
                frameJob.await(),
                "no application frame reached the host within $APP_FRAME_BUDGET of virtual time — " +
                    "the payload was classified as something other than application data",
            )
            assertTrue(appPayload.contentEquals(frame.payload))

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── selfId ────────────────────────────────────────────────────────────────

    @Test
    fun `selfId is stable and non-blank`() =
        runTest {
            val room = factory(loom(), backgroundScope).host(Pattern("Alice"))
            assertAll(
                { assertTrue(room.selfId.value.isNotBlank()) },
                { assertEquals(room.selfId, room.selfId) },
            )
            room.leave()
        }

    // ── leave idempotency ─────────────────────────────────────────────────────

    @Test
    fun `leave is idempotent`() =
        runTest {
            val room = factory(loom(), backgroundScope).host(Pattern("Alice"))
            room.leave()
            room.leave()
        }

    // ── multi-joiner roster ───────────────────────────────────────────────────

    @Test
    fun `two joiners both appear in host roster after handshake`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))
            factory(loom, backgroundScope).join(InMemoryTag("Bob"), memberName = "Bob")
            factory(loom, backgroundScope).join(InMemoryTag("Charlie"), memberName = "Charlie")

            val roster = hostRoom.roster.first { it.size == 2 }
            val names = roster.map { it.identity.displayName }.toSet()
            assertEquals(setOf("Bob", "Charlie"), names)

            hostRoom.leave()
        }

    // ── Joined event member liveness ─────────────────────────────────────────

    @Test
    fun `Joined event member has liveness Connected`() =
        runTest {
            val loom = loom()
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))

            val joinedDeferred = CompletableDeferred<MembershipEvent.Joined>()
            val collectJob = backgroundScope.launch {
                hostRoom.events.collect { event ->
                    if (event is MembershipEvent.Joined) joinedDeferred.complete(event)
                }
            }
            yield()

            factory(loom, backgroundScope).join(InMemoryTag("Bob"))

            val event = joinedDeferred.await()
            collectJob.cancel()
            assertEquals(Liveness.Connected, event.member.liveness)
        }

    // ── #449: SeamRoomFactory.host propagates loom failures ──────────────────

    /**
     * #449: when the underlying [Loom.host] throws a non-cancellation exception,
     * [SeamRoomFactory.host] must propagate it — callers that drive an accept loop
     * (e.g. [us.tractat.kuilt.websocket.KtorRoomHost]) can then surface the error.
     *
     * This test uses a [FailingLoom] stub that always throws [IllegalStateException]
     * from [Loom.weave]. [SeamRoomFactory.host] must NOT catch and swallow it.
     */
    @Test
    fun `SeamRoomFactory host propagates loom failure to caller`() =
        runTest {
            val clock: () -> kotlin.time.Instant = { kotlin.time.Instant.fromEpochMilliseconds(0L) }
            val failingLoom = FailingLoom(IllegalStateException("loom closed"))
            val factory = SeamRoomFactory(failingLoom, backgroundScope, clock)

            val result = runCatchingCancellable { factory.host(Pattern("Alice")) }

            assertIs<IllegalStateException>(
                result.exceptionOrNull(),
                "SeamRoomFactory.host must propagate non-cancellation Loom failures to its caller",
            )
        }

    /**
     * The #1172 root cause, at the Room layer: hosting a second room on ONE
     * [InMemoryLoom] would silently cross-admit both rooms' members over the
     * single flat mesh. The loom's concurrent-host guard makes the second
     * `host()` fail loudly instead — a factory (or two factories) over one loom
     * hosts exactly one live room.
     */
    @Test
    fun `two concurrent SeamRoomFactory hosts on one loom — the second is rejected`() =
        runTest {
            val loom = loom()
            val room = factory(loom, backgroundScope).host(Pattern("Alice"))

            val result = runCatchingCancellable { factory(loom, backgroundScope).host(Pattern("Bob")) }

            val failure = result.exceptionOrNull()
            assertIs<IllegalStateException>(
                failure,
                "a second concurrent host over one InMemoryLoom must be rejected, not silently cross-admitted",
            )
            assertTrue(
                failure.message?.contains("single flat mesh") == true,
                "the guard message must explain the single-mesh contract, got: ${failure.message}",
            )
            room.leave()
        }

    private companion object {
        /**
         * Virtual time allowed for a broadcast application frame to reach the host.
         *
         * Bounded in **virtual** time, so it measures the scheduler rather than the host machine
         * and is not the load-bearing real-time ceiling this repo bans (#1739/#1891). Generous
         * against what the delivery needs — which is zero virtual time — and its only job is to
         * turn a payload that stops being classified as application data into a *failure* with a
         * message instead of a wait for `runTest`'s own ceiling.
         */
        val APP_FRAME_BUDGET = 5.seconds
    }
}

/** Test stub: [Loom] that always throws the given [error] from [weave]. */
private class FailingLoom(private val error: Throwable) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = throw error
}

