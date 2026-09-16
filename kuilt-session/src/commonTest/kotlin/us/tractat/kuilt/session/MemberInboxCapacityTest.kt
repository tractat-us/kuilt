package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `SeamRoomFactory(memberInboxCapacity = …)` — the knob a consumer that never reads
 * [Room.incomingFrom] turns off (#2802), and the reason it is a knob at all: a room opens an inbox for
 * every member it admits, on the joiner side too, so a client reading only [Room.incoming] would
 * otherwise hold that many of the host's frames per member.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberInboxCapacityTest {
    private val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) }

    @Test
    fun `a room built with capacity 0 still delivers on incoming`() =
        runTest {
            val loom = InMemoryLoom()
            val host = SeamRoomFactory(loom, backgroundScope, clock, memberInboxCapacity = 0).host(Pattern("Alice"))
            val joiner = SeamRoomFactory(loom, backgroundScope, clock).join(InMemoryTag("Alice"))
            host.roster.first { it.size == 1 }

            val received = mutableListOf<String>()
            val reader = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                host.incoming.collect { received += it.payload.decodeToString() }
            }
            joiner.broadcast("f-0".encodeToByteArray())
            advanceTimeBy(100L)
            reader.cancel()

            assertEquals(listOf("f-0"), received, "`incoming` keeps its own buffer; only the per-member hold is off")
            joiner.leave()
            host.leave()
        }

    @Test
    fun `incomingFrom on a room built with capacity 0 fails loudly`() =
        runTest {
            val loom = InMemoryLoom()
            val host = SeamRoomFactory(loom, backgroundScope, clock, memberInboxCapacity = 0).host(Pattern("Alice"))
            val joiner = SeamRoomFactory(loom, backgroundScope, clock).join(InMemoryTag("Alice"))
            val joinerId = host.roster.first { it.size == 1 }.single().id

            val refusal = assertFailsWith<IllegalStateException> { host.incomingFrom(joinerId) }
            assertAll(
                { assertTrue(refusal.message.orEmpty().contains("memberInboxCapacity"), "the refusal names the knob that disabled it") },
                {
                    assertTrue(
                        host.roster.value.any { it.id == joinerId },
                        "and the member is admitted — this is a configuration refusal, not a missing admission",
                    )
                },
            )

            joiner.leave()
            host.leave()
        }

    @Test
    fun `a negative capacity is refused at construction`() =
        runTest {
            assertFailsWith<IllegalArgumentException>("-1 would otherwise behave as 0 under a refusal message that says 0") {
                SeamRoomFactory(InMemoryLoom(), backgroundScope, clock, memberInboxCapacity = -1)
            }
        }

    @Test
    fun `a room built with a smaller capacity holds exactly that many frames`() =
        runTest {
            val loom = InMemoryLoom()
            val host = SeamRoomFactory(loom, backgroundScope, clock, memberInboxCapacity = 2).host(Pattern("Alice"))
            val joiner = SeamRoomFactory(loom, backgroundScope, clock).join(InMemoryTag("Alice"))
            val joinerId = host.roster.first { it.size == 1 }.single().id

            repeat(2) { joiner.broadcast("f-$it".encodeToByteArray()) }
            advanceTimeBy(100L)
            val held = mutableListOf<String>()
            val frames = host.incomingFrom(joinerId)
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { frames.collect { held += it.payload.decodeToString() } }
            advanceTimeBy(100L)

            assertEquals(listOf("f-0", "f-1"), held, "the configured depth is what the room holds, not the default")
            joiner.leave()
            host.leave()
        }

    /**
     * The other half of the same knob, and the half that can fail: a room built with a smaller depth
     * **overflows at that depth**. Holding two frames proves nothing on its own — a room that ignored the
     * parameter and held the default 64 would hold those two just as well.
     */
    @Test
    fun `a room built with a smaller capacity overflows once it is exceeded`() =
        runTest {
            val loom = InMemoryLoom()
            val host = SeamRoomFactory(loom, backgroundScope, clock, memberInboxCapacity = 2).host(Pattern("Alice"))
            val joiner = SeamRoomFactory(loom, backgroundScope, clock).join(InMemoryTag("Alice"))
            val joinerId = host.roster.first { it.size == 1 }.single().id

            repeat(3) { joiner.broadcast("f-$it".encodeToByteArray()) }
            advanceTimeBy(100L)

            val lost = assertFailsWith<FramesLost> { host.incomingFrom(joinerId).first() }
            assertAll(
                { assertEquals(3L, lost.dropped, "the third frame overflowed a depth of two, releasing all three") },
                { assertEquals(false, lost.claimed, "nothing had claimed the inbox when it overflowed") },
            )

            joiner.leave()
            host.leave()
        }
}
