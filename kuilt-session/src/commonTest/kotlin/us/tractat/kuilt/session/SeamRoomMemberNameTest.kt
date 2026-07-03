package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A joiner's roster name comes from its own [SeamRoom] member name, NOT the discovered
 * session name — the #1177 fix.
 */
class SeamRoomMemberNameTest {

    @Test
    fun `joiner appears under its own memberName not the session name`() = runTest {
        val loom = InMemoryLoom()
        val clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) }
        val host = SeamRoomFactory(loom, backgroundScope, clock = clock)
            .host(Pattern(sessionName = "Alice's game"), memberName = "Alice")
        // Joiner discovers the session ("Alice's game") but names itself "Bob".
        val joiner = SeamRoomFactory(loom, backgroundScope, clock = clock)
            .join(InMemoryTag("Alice's game"), memberName = "Bob")

        val hostRoster = host.roster.first { it.size == 1 }      // the admitted joiner
        val joinerRoster = joiner.roster.first { it.isNotEmpty() } // the host
        assertAll(
            { assertEquals("Bob", hostRoster.single().identity.displayName) },
            { assertEquals("Alice", joinerRoster.single().identity.displayName) },
        )
    }
}
