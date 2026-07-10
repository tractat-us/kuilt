package us.tractat.kuilt.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies #1330's per-dial hook for [WebSocketSignalingChannel]: [buildSignalingUrl] merges a
 * [us.tractat.kuilt.core.Weft]-supplied query-param map onto the signaling URL, percent-encoded.
 */
class WebSocketSignalingChannelUrlTest {
    @Test
    fun noQueryParamsLeavesTheUrlUnchanged() {
        assertEquals(
            "https://example.com/signaling/room-1",
            buildSignalingUrl("https://example.com", "room-1", emptyMap()),
        )
    }

    @Test
    fun queryParamsArePercentEncodedAndAppended() {
        assertEquals(
            "https://example.com/signaling/room-1?ticket=abc%20123%26x",
            buildSignalingUrl("https://example.com", "room-1", mapOf("ticket" to "abc 123&x")),
        )
    }
}
