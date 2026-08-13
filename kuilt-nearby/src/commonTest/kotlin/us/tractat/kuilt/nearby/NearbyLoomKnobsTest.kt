@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nearby

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What [NearbyLoom]'s construction knobs actually reach — the behavioural half of the uniform
 * `Loom`-construction convention (#1430).
 *
 * The convention's rule is that a factory carries a knob **only** where the fabric honours it, so
 * each test here drives the knob to a non-default value and asserts a difference the default could
 * not produce. Two of the universal knobs are absent from this fabric, and an absence is a claim
 * too, so it is pinned as well:
 *
 * - **`selfId`** — [twoSeamsFromOneLoomMustNotShareAnIdentity] pins the property a loom-level
 *   identity would break, which is why the knob is omitted rather than defaulted.
 * - **`weaveTimeout`** — [theHandshakeCeilingDoesNotBoundDiscovery] shows a `weave` running well
 *   past the only timeout this fabric has, which is why [NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT] is
 *   not `LoomDefaults.WEAVE_TIMEOUT` under another name.
 *
 * The Android entry point `nearbyLoom(context, …)` forwards these same knobs but cannot be tested
 * here: it builds a `GmsNearbyApi`, which resolves a real `ConnectionsClient` from a `Context` at
 * construction. This repo has no Robolectric and no instrumented-test source set, so the factory's
 * one untested line is `GmsNearbyApi(context)` — everything downstream of it is what these tests
 * drive directly.
 */
class NearbyLoomKnobsTest {

    /**
     * A [NearbyApi] that stalls, with discovery as the switch.
     *
     * With [announceEndpoint] true, [startDiscovery] reports one endpoint and then the radio goes
     * silent — so [NearbyLoom.joinSession] clears discovery and sits inside [ConnectStateMachine]
     * with nothing that can resolve it. With it false, nothing is ever found, so the join never
     * reaches the state machine at all. That pair isolates exactly which span the handshake
     * ceiling covers.
     */
    private class StallingNearbyApi(private val announceEndpoint: Boolean) : NearbyApi {
        private val found = MutableSharedFlow<EndpointFound>(extraBufferCapacity = 1)

        override val endpointFound: Flow<EndpointFound> = found.asSharedFlow()
        override val connectionInitiated: Flow<ConnectionInitiated> = emptyFlow()
        override val connectionResult: Flow<ConnectionResult> = emptyFlow()
        override val payloadReceived: Flow<PayloadReceived> = emptyFlow()
        override val endpointDisconnected: Flow<EndpointDisconnected> = emptyFlow()

        override fun availability(): FabricAvailability = FabricAvailability.Available
        override suspend fun startAdvertising(displayName: String, serviceId: String) {}
        override suspend fun stopAdvertising() {}
        override suspend fun startDiscovery(serviceId: String) {
            if (announceEndpoint) found.emit(EndpointFound(HOST_ENDPOINT, "host"))
        }
        override suspend fun stopDiscovery() {}
        override suspend fun requestConnection(displayName: String, endpointId: String) {}
        override suspend fun acceptConnection(endpointId: String) {}
        override suspend fun disconnect(endpointId: String) {}
        override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) {}
    }

    // ── policy ────────────────────────────────────────────────────────────────

    @Test
    fun deliveryPolicyReachesTheWovenSeamsInboundBuffer() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // capacity 1 + DROP_OLDEST: an undrained receiver keeps only the NEWEST frame. Under the
            // default (Reliable — capacity 256, SUSPEND) all three would queue and `first()` would
            // return frame 1, so this reds the moment the loom stops forwarding `policy`.
            val loom = NearbyLoom(
                api = FakeNearbyApi(FakeNearbyRadio()),
                policy = DeliveryPolicy(capacity = 1, overflow = Overflow.DROP_OLDEST),
            )
            val host = loom.host(Pattern("device"))
            val joiner = loom.join(NearbyTag("device", HOST_ENDPOINT))

            joiner.broadcast(byteArrayOf(1))
            joiner.broadcast(byteArrayOf(2))
            joiner.broadcast(byteArrayOf(3))
            runCurrent()

            assertEquals(
                listOf<Byte>(3),
                host.incoming.first().toByteArray().toList(),
                "a lossy capacity-1 policy must reach the seam's Spool — the two older frames are " +
                    "dropped, so the first frame collected is the newest one sent",
            )
        }

    // ── handshakeTimeout ──────────────────────────────────────────────────────

    @Test
    fun theSuppliedHandshakeCeilingIsTheOneEnforced() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = NearbyLoom(
                api = StallingNearbyApi(announceEndpoint = true),
                handshakeTimeout = 3.seconds,
            )

            val startedAt = currentTime
            // SupervisorJob'd backgroundScope: the stalled join fails, and that must not fail the test.
            val join = backgroundScope.async { loom.join(NearbyTag("device", HOST_ENDPOINT)) }
            assertFailsWith<TimeoutCancellationException>("a handshake nothing answers must not hang") {
                join.await()
            }

            assertEquals(
                3_000L,
                currentTime - startedAt,
                "the ceiling enforced must be the supplied 3s, not DEFAULT_HANDSHAKE_TIMEOUT",
            )
        }

    /**
     * The evidence that this knob is a `handshakeTimeout` and not a `weaveTimeout` (#1430).
     *
     * `LoomDefaults.WEAVE_TIMEOUT` promises a clock that "covers discovery, dialling and the
     * fabric's own handshake". Here discovery is outside it: a `weave` that never finds an endpoint
     * is still waiting ten ceilings later. Renaming the knob would publish a bound on `weave` that
     * no path delivers.
     */
    @Test
    fun theHandshakeCeilingDoesNotBoundDiscovery() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = NearbyLoom(
                api = StallingNearbyApi(announceEndpoint = false),
                handshakeTimeout = 1.seconds,
            )

            val join = backgroundScope.async { loom.join(NearbyTag("device", HOST_ENDPOINT)) }
            advanceTimeBy(10.seconds)
            runCurrent()

            assertTrue(
                join.isActive,
                "10x past the handshake ceiling, a weave still hunting for an endpoint has not been " +
                    "bounded by it — so the knob does not bound a rendezvous and is not a weaveTimeout",
            )
            join.cancel()
        }

    // ── the absent selfId ─────────────────────────────────────────────────────

    /**
     * Why the convention's `selfId` knob is omitted here rather than defaulted: one [NearbyLoom]
     * weaves both ends of a session, so a loom-level identity would hand both seams the same
     * [us.tractat.kuilt.core.PeerId]. This test reds if a future change routes both weave paths
     * through one shared identity.
     */
    @Test
    fun twoSeamsFromOneLoomMustNotShareAnIdentity() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = NearbyLoom(api = FakeNearbyApi(FakeNearbyRadio()))

            val host = loom.host(Pattern("device"))
            val joiner = loom.join(NearbyTag("device", HOST_ENDPOINT))

            assertNotEquals(
                host.selfId,
                joiner.selfId,
                "the two ends of one session must be distinguishable: a shared identity would " +
                    "collapse the roster to one entry and fail sendTo's own peer != selfId check",
            )
            assertTrue(
                joiner.peers.value.containsAll(setOf(host.selfId, joiner.selfId)),
                "…and both identities are in the session roster",
            )
        }

    private companion object {
        /** Endpoint id the stalling/fake radio hands the discoverer for the advertiser. */
        const val HOST_ENDPOINT = "ep-advertiser"
    }
}
