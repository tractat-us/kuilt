package us.tractat.kuilt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NamedMuxSubscribeBeforeReturnTest {
    @Test
    fun replyToFirstSendSurvivesDeferredChannelSubscription() = runTest(StandardTestDispatcher()) {
        val host = PeerId("host")
        val buffered = FakeSeam(initialPeers = setOf(PeerId("self"), host))
        var drained = 0
        val base = object : Seam by buffered {
            override val incoming = buffered.incoming.onEach { drained++ }
            override suspend fun broadcast(payload: ByteArray) {
                // Echo the framed request immediately, without giving a dispatched subscriber a turn.
                buffered.deliver(host, payload)
            }
        }
        val channel = NamedMux(base, backgroundScope).channel("lobby")
        channel.broadcast("first".encodeToByteArray())
        runCurrent()

        // Collect late deliberately: the channel's reliable spool must retain the first reply.
        val received = mutableListOf<String>()
        backgroundScope.launch { channel.incoming.collect { received += it.decodeToString() } }
        runCurrent()
        val firstReply = received.toList()
        channel.broadcast("sentinel".encodeToByteArray())
        runCurrent()

        assertAll(
            { assertEquals(2, drained, "both replies reached the mux upstream") },
            { assertEquals(listOf("first"), firstReply, "first reply must survive channel construction") },
            { assertEquals("sentinel", received.lastOrNull(), "the pipe works after subscription") },
        )
    }
}
