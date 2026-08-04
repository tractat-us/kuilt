@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nearby

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * Regression for #1432: [NearbyLoom] must mint a *globally* unique self-identity,
 * not a per-loom counter that restarts at the same value on every device.
 *
 * Two independent looms (each its own [FakeNearbyRadio]) model two devices. Both
 * host a session; with the old per-loom counter each minted `nearby-peer-1`, so the
 * identities collided the instant the devices met. A random UUID makes them distinct
 * without coordination.
 */
class NearbyIdentityTest {

    @Test
    fun independentLoomsMintDistinctSelfIds() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loomA = NearbyLoom(FakeNearbyApi(FakeNearbyRadio()))
        val loomB = NearbyLoom(FakeNearbyApi(FakeNearbyRadio()))

        // host() returns immediately without needing a joiner.
        val seamA = loomA.host(Pattern("device-a"))
        val seamB = loomB.host(Pattern("device-b"))

        assertNotEquals(
            seamA.selfId,
            seamB.selfId,
            "two devices' first-woven seams must not share a self-identity",
        )
    }
}
