package us.tractat.kuilt.nw

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CI-safe guards for the [nwHost]/[nwJoin] bootstrap factories.
 *
 * The require-roomKey check throws **synchronously**, before any `RealNwApi`/socket is built, so
 * these tests need no real Bonjour/AWDL network and cannot hang — the throw completes at virtual
 * `t=0`. The happy path (a real encrypted link) is proven separately by
 * [NwLoopbackConformanceTest]; the PSK derivation by `NwPskTest`.
 */
class NwFabricTest {

    /** A minimal [Tag] whose [roomKey] is `null` — [NwTag] cannot express this (it forces roomKey non-null). */
    private class NullRoomKeyTag(
        override val sessionName: String = "host",
        override val peerKey: String = "peer",
    ) : Tag

    @Test
    fun nwHostRejectsNullRoomKey() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            nwHost(Pattern(sessionName = "host", roomKey = null), serviceType = "_kuilt._tcp")
        }
        assertTrue(
            failure.message?.contains("roomKey") == true,
            "message should name roomKey (so the require-guard fired, not an unrelated error): ${failure.message}",
        )
    }

    @Test
    fun nwJoinRejectsNullRoomKey() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            nwJoin(NullRoomKeyTag(), serviceType = "_kuilt._tcp")
        }
        assertTrue(
            failure.message?.contains("roomKey") == true,
            "message should name roomKey (so the require-guard fired, not an unrelated error): ${failure.message}",
        )
    }
}
