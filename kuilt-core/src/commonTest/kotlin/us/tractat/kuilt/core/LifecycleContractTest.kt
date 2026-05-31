package us.tractat.kuilt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Consumer-contract tests for [FlakyLifecycleSeam].
 *
 * Verifies that across a blip, frames sent while [SeamState.Weaving] are dropped
 * and the surviving stream's order is preserved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleContractTest {

    @Test
    fun `order is preserved across a blip and weaving sends do not arrive`() = runTest {
        val mem = InMemoryLoom()
        val a = FlakyLifecycleSeam(mem.host(Pattern("a")), backgroundScope)
        val b = mem.join(InMemoryTag("b"))
        // Allow the background peers-watcher coroutine to update _peers with b's id.
        testScheduler.runCurrent()

        // Start collecting before any sends so no frames are missed.
        val received = async { b.incoming.take(2).toList() }

        a.broadcast(byteArrayOf(1))      // Woven → delivered
        a.enterWeaving()
        a.broadcast(byteArrayOf(99))     // Weaving → dropped (link down)
        a.recover()
        a.broadcast(byteArrayOf(2))      // Woven → delivered

        val frames = received.await()
        assertAll(
            { assertTrue(frames[0].payload.contentEquals(byteArrayOf(1)), "first Woven frame must arrive") },
            { assertTrue(frames[1].payload.contentEquals(byteArrayOf(2)), "99 (sent while Weaving) must not arrive; only 2 arrives") },
        )
    }

    @Test
    fun `broadcast after blip delivers normally`() = runTest {
        val mem = InMemoryLoom()
        val a = FlakyLifecycleSeam(mem.host(Pattern("a")), backgroundScope)
        val b = mem.join(InMemoryTag("b"))
        // Allow the background peers-watcher coroutine to update _peers with b's id.
        testScheduler.runCurrent()

        val received = async { b.incoming.take(1).toList() }

        a.blip(weavingFor = 50.milliseconds)
        a.broadcast(byteArrayOf(42))

        val frames = received.await()
        assertTrue(frames[0].payload.contentEquals(byteArrayOf(42)), "post-blip broadcast must be delivered")
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
