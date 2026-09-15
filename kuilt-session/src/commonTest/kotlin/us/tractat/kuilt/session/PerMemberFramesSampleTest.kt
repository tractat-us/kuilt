package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Runs [perMemberFramesSample], which `docs/agent-cookbook/session.md` quotes: a frame the member sent
 * before the sample started reading is delivered, and the sample reports a clean end when the member
 * leaves.
 */
class PerMemberFramesSampleTest {
    @Test
    fun `perMemberFramesSample delivers a frame sent before it started and ends cleanly when the member leaves`() =
        runTest {
            val loom = InMemoryLoom()
            val clock = { Instant.fromEpochMilliseconds(0L) }
            val host = SeamRoomFactory(loom, backgroundScope, clock = clock).host(Pattern("Alice"))
            val joiner = SeamRoomFactory(loom, backgroundScope, clock = clock).join(InMemoryTag("Bob"))
            val joinerId = host.roster.first { it.size == 1 }.single().id

            joiner.broadcast("hello".encodeToByteArray())
            advanceTimeBy(100L)
            val got = mutableListOf<String>()
            val ended = async { perMemberFramesSample(host, joinerId) { got += it.decodeToString() } }
            advanceTimeBy(100L)
            joiner.leave()

            val clean = withTimeout(5.seconds) { ended.await() }
            assertAll(
                { assertTrue(clean, "a member that left with every frame delivered is a clean end") },
                { assertEquals(listOf("hello"), got, "the frame sent before the sample started reading is delivered") },
            )
            host.leave()
        }
}
