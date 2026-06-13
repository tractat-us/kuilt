package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Acceptance tests for [SeamRoom] + [SeamRoomFactory] against [InMemoryLoom].
 *
 * All tests use virtual time ([runTest]) and [backgroundScope] for admit loops.
 * No wall-clock. No randomness.
 */
class SeamRoomTest {
    private fun loom() = InMemoryLoom()
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) = SeamRoomFactory(loom, scope)

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
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"))

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
            val hostRoom = factory(loom, backgroundScope).host(Pattern("Alice"))
            val joinerRoom = factory(loom, backgroundScope).join(InMemoryTag("Bob"))

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

            factory(loom, backgroundScope).join(InMemoryTag("Bob"))

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

            val appPayload = "real message".encodeToByteArray()
            val frameJob = async { hostRoom.incoming.first() }
            joinerRoom.broadcast(appPayload)

            val frame = frameJob.await()
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
            factory(loom, backgroundScope).join(InMemoryTag("Bob"))
            factory(loom, backgroundScope).join(InMemoryTag("Charlie"))

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
}

