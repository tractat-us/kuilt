package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.test.FakeSeam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RaceCollapseTest {
    private val self = PeerId("self")
    private val other = PeerId("other")

    @Test
    fun `body completes normally when the seam stays healthy`() =
        runTest {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, other))
            val result = seam.raceCollapse { 42 }
            assertEquals(42, result)
        }

    @Test
    fun `body exception propagates unchanged`() =
        runTest {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, other))
            assertFailsWith<IllegalStateException> {
                seam.raceCollapse { error("boom") }
            }
        }

    @Test
    fun `a transport tear mid-body aborts with SeamCollapsedException`() =
        runTest {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, other))
            val gate = Channel<Unit>() // never sent to — body suspends forever without the tear
            val outcome = CompletableDeferred<Int>()
            val driver = launch {
                try {
                    outcome.complete(seam.raceCollapse { gate.receive(); 1 })
                } catch (e: Throwable) {
                    outcome.completeExceptionally(e)
                }
            }
            runCurrent()

            seam.tear(CloseReason.RemoteRequested)

            val ex = assertFailsWith<SeamCollapsedException> { withTimeout(5.seconds) { outcome.await() } }
            assertEquals(CloseReason.RemoteRequested, ex.reason)
            driver.cancel()
        }

    @Test
    fun `a membership drain with no tear aborts with SeamCollapsedException`() =
        runTest {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, other))
            val gate = Channel<Unit>()
            val outcome = CompletableDeferred<Int>()
            val driver = launch {
                try {
                    outcome.complete(seam.raceCollapse { gate.receive(); 1 })
                } catch (e: Throwable) {
                    outcome.completeExceptionally(e)
                }
            }
            runCurrent()

            // Peer leaves; seam stays Woven (no tear). Default abortWhen fires once we are alone.
            seam.removePeer(other)
            assertTrue(seam.state.value is SeamState.Woven, "drain must not tear the seam")

            val ex = assertFailsWith<SeamCollapsedException> { withTimeout(5.seconds) { outcome.await() } }
            assertEquals(CloseReason.Unreachable, ex.reason)
            driver.cancel()
        }

    @Test
    fun `already-torn at entry throws eagerly without starting body`() =
        runTest {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, other), initialState = SeamState.Torn(CloseReason.Normal))
            var bodyStarted = false
            val ex = assertFailsWith<SeamCollapsedException> {
                seam.raceCollapse { bodyStarted = true; 1 }
            }
            assertEquals(CloseReason.Normal, ex.reason)
            assertFalse(bodyStarted)
        }

    @Test
    fun `already-drained at entry throws eagerly without starting body`() =
        runTest {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self)) // alone from the start
            var bodyStarted = false
            val ex = assertFailsWith<SeamCollapsedException> {
                seam.raceCollapse { bodyStarted = true; 1 }
            }
            assertEquals(CloseReason.Unreachable, ex.reason)
            assertFalse(bodyStarted)
        }

    @Test
    fun `a custom abortWhen predicate keys on a specific required peer`() =
        runTest {
            val third = PeerId("third")
            val required = setOf(self, other, third)
            val seam = FakeSeam(selfId = self, initialPeers = required)
            val gate = Channel<Unit>()
            val outcome = CompletableDeferred<Int>()
            val driver = launch {
                try {
                    outcome.complete(
                        seam.raceCollapse(abortWhen = { live -> required.any { it !in live } }) { gate.receive(); 1 },
                    )
                } catch (e: Throwable) {
                    outcome.completeExceptionally(e)
                }
            }
            runCurrent()

            // Still two peers left (size >= 2 — the default would NOT fire), but a required participant left.
            seam.removePeer(third)

            assertFailsWith<SeamCollapsedException> { withTimeout(5.seconds) { outcome.await() } }
            driver.cancel()
        }

    @Test
    fun `body wins the race even when the seam later drains`() =
        runTest {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, other))
            val gate = Channel<Int>(capacity = 1)
            val result = async { seam.raceCollapse { gate.receive() } }
            runCurrent()
            gate.send(7) // body resolves before any collapse
            assertEquals(7, withTimeout(5.seconds) { result.await() })
            assertTrue(seam.state.value is SeamState.Woven)
        }

    private fun assertFalse(condition: Boolean) = assertTrue(!condition)
}
