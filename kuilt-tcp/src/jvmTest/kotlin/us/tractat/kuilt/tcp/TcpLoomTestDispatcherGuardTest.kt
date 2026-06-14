@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The real-IO [TcpLoom] must refuse to construct its seam under a virtual `TestDispatcher`:
 * its blocking socket reads never advance under virtual time, so a test would deadlock
 * silently. [TcpLoom.weave] calls `checkNotUnderTestDispatcher(strict = true)`, which throws
 * with a diagnostic naming the type and the in-memory substitute.
 *
 * The guard fires before any socket IO (it inspects the `seamDispatcher`), so no real network
 * is touched — passing a `StandardTestDispatcher` as the `seamDispatcher` is enough to trip it.
 */
class TcpLoomTestDispatcherGuardTest {

    @Suppress("ForbiddenMethodCall") // selector needs a real dispatcher; never used — the guard fires first
    private val selector = SelectorManager(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = selector.close()

    @Test
    fun weaveUnderTestDispatcherFailsLoudly() = runTest {
        val loom = TcpLoom.join(
            selfId = PeerId("joiner"),
            selector = selector,
            seamDispatcher = StandardTestDispatcher(testScheduler),
        )
        val ex = assertFailsWith<IllegalStateException> {
            loom.weave(us.tractat.kuilt.core.Rendezvous.Existing(TcpAddress("127.0.0.1", 1)))
        }
        assertTrue("TcpLoom" in ex.message!!, "Diagnostic must name the type: ${ex.message}")
        assertTrue(
            "TestDispatcher" in ex.message!! || "virtual time" in ex.message!!,
            "Diagnostic must mention TestDispatcher or virtual time: ${ex.message}",
        )
    }
}
