@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nearby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies [NearbySeam] honours the [us.tractat.kuilt.core.Seam] contract that `incoming`
 * completes once the seam reaches [SeamState.Torn] — including a *self-driven* teardown when
 * the last connected endpoint disconnects on its own (not via a local `close()`).
 *
 * ## The [us.tractat.kuilt.core.Seam.peers] collapse obligation (#1816 / #1850)
 * A `Torn` seam's roster must be exactly `{ selfId }`, published **before, or atomically with**,
 * the terminal `Torn` latch. `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn` (bound here by
 * [NearbyConformanceTest]) asserts the terminal *value*; the two things it structurally cannot see
 * get their own tests below:
 *
 *  - **Ordering.** `peers` is a conflating `StateFlow`, so a collector resumed after `close()`
 *    returns always reads the settled value and would pass against any implementation. The probe in
 *    [theCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable] collects on an
 *    [UnconfinedTestDispatcher] so it resumes **inline** inside the `_state` write, reading `peers`
 *    at exactly the instant `Torn` became observable.
 *  - **Blast radius.** A seam that "collapses" by writing the roster flow it was handed edits every
 *    other reader of that flow. The TCK drives one loom and can only look at the seam it closed;
 *    [closingOneSeamDoesNotEvictItFromAnotherSeamsRoster] looks at the other one.
 *
 *    Since #1878 [NearbyLoom] mints a roster **per weave**, so it no longer hands one flow to two
 *    seams and the production topology that made this reachable is gone. The test is kept, and
 *    still hands one flow to two seams by hand, because the property it pins is [NearbySeam]'s
 *    own — `close()` collapses [NearbySeam.peers] and writes the roster flow *not at all* — and
 *    that obligation is what a future change would break first. Read it as defence in depth on a
 *    seam-level contract, not as a model of what the loom now builds.
 */
class NearbySeamTearDownTest {

    private val self = PeerId("self")

    /**
     * Minimal [NearbyApi] whose only live surface is [endpointDisconnected], which the test
     * drives directly. Everything else is a no-op — the seam is constructed already-connected.
     */
    private class ControllableNearbyApi : NearbyApi {
        val disconnects = MutableSharedFlow<EndpointDisconnected>(extraBufferCapacity = 4)

        override fun availability(): FabricAvailability = FabricAvailability.Available
        override suspend fun startAdvertising(displayName: String, serviceId: String) {}
        override suspend fun stopAdvertising() {}
        override suspend fun startDiscovery(serviceId: String) {}
        override suspend fun stopDiscovery() {}
        override suspend fun requestConnection(displayName: String, endpointId: String) {}
        override suspend fun acceptConnection(endpointId: String) {}
        override suspend fun disconnect(endpointId: String) {}
        override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) {}

        override val endpointFound: Flow<EndpointFound> = emptyFlow()
        override val connectionInitiated: Flow<ConnectionInitiated> = emptyFlow()
        override val connectionResult: Flow<ConnectionResult> = emptyFlow()
        override val payloadReceived: Flow<PayloadReceived> = emptyFlow()
        override val endpointDisconnected: Flow<EndpointDisconnected> = disconnects.asSharedFlow()
    }

    /**
     * Build a seam that is already [SeamState.Woven]: [endpoints] are pre-populated and
     * [weavePeers] already carries a remote peer, so [NearbySeam]'s roster watcher latches
     * Woven synchronously at construction.
     *
     * [weavePeers] is a parameter so a test can hand the *same* flow to two seams — a topology
     * [NearbyLoom] no longer builds (#1878 made the roster per-weave), kept reachable here because
     * the seam-level obligation it probes still stands. See the class KDoc.
     */
    private fun wovenSeam(
        api: NearbyApi,
        scope: CoroutineScope,
        endpoints: Map<String, PeerId>,
        selfId: PeerId = self,
        weavePeers: MutableStateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId) + endpoints.values),
    ): NearbySeam =
        NearbySeam(
            selfId = selfId,
            endpointPeers = endpoints.toMutableMap(),
            endpointPeersMutex = Mutex(),
            // Derived from the SAME map as endpointPeers — see registryOver.
            registry = registryOver(selfId, endpoints),
            api = api,
            weavePeers = weavePeers,
            scope = scope,
            msgIdCounter = MsgIdCounter(),
        )

    @Test
    fun lastEndpointDisconnectLatchesTornAndCompletesIncoming() = runTest {
        val api = ControllableNearbyApi()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val remote = PeerId("remote")
        val seam = wovenSeam(api, scope, mapOf("ep-remote" to remote))
        assertIs<SeamState.Woven>(seam.state.value)

        api.disconnects.emit(EndpointDisconnected("ep-remote"))

        // incoming completes (the spool closed) → toList returns immediately.
        val drained = seam.incoming.toList()
        assertTrue(drained.isEmpty(), "no frames were delivered")
        assertIs<SeamState.Torn>(seam.state.value)
    }

    @Test
    fun partialDisconnectDoesNotLatchTorn() = runTest {
        val api = ControllableNearbyApi()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val a = PeerId("a")
        val b = PeerId("b")
        val seam = wovenSeam(api, scope, mapOf("ep-a" to a, "ep-b" to b))
        assertIs<SeamState.Woven>(seam.state.value)

        api.disconnects.emit(EndpointDisconnected("ep-a"))

        // One of two peers dropped: the session is still live.
        assertIs<SeamState.Woven>(seam.state.value)
        assertContains(seam.peers.value, b)
        // Still open: a send does not throw the closed-seam error.
        seam.broadcast(byteArrayOf(1))

        scope.coroutineContext[Job]?.cancel()
    }

    // ── the peers collapse obligation (#1816 / #1850) ─────────────────────────

    @Test
    fun aTornSeamAdvertisesExactlyItsOwnId() = runTest {
        val api = ControllableNearbyApi()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val remote = PeerId("remote")
        val seam = wovenSeam(api, scope, mapOf("ep-remote" to remote))
        assertContains(seam.peers.value, remote, "precondition: a roster worth collapsing")

        seam.close()

        val peers = seam.peers.value
        assertAll(
            {
                assertEquals(
                    emptySet(),
                    peers - self,
                    "a Torn seam must advertise NO reachable remote peer (Seam.peers): a torn radio " +
                        "reaches nobody, and a decorator folding this seam reads what is left here as " +
                        "still reachable until the member is detached",
                )
            },
            {
                assertTrue(
                    self in peers,
                    "a Torn seam's collapsed roster is { selfId }, not empty — close() dropping selfId " +
                        "collapses too far (got ${peers.map { it.value }})",
                )
            },
        )
    }

    @Test
    fun theCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable() = runTest {
        val api = ControllableNearbyApi()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val seam = wovenSeam(api, scope, mapOf("ep-remote" to PeerId("remote")))

        // Unconfined so the probe resumes INLINE inside the `_state` write — what it reads from
        // `peers` is the value at exactly the instant Torn became observable, not the settled one.
        val peersWhenTornBecameVisible = CompletableDeferred<Set<PeerId>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            seam.state.first { it is SeamState.Torn }
            peersWhenTornBecameVisible.complete(seam.peers.value)
        }
        runCurrent()

        seam.close()

        // Sequential, NOT assertAll — and still so after #2283, for a different reason. The masking
        // it used to avoid is gone (assertAll now carries the sibling diagnoses along on the throw),
        // but the first assertion GUARDS the second: `getCompleted()` throws when the probe never
        // fired, and ordering them makes the named "did not latch it" failure the one you read.
        assertTrue(
            peersWhenTornBecameVisible.isCompleted,
            "the probe must have observed the terminal Torn — close() did not latch it",
        )
        assertEquals(
            setOf(self),
            peersWhenTornBecameVisible.getCompleted(),
            "peers must ALREADY be collapsed at the instant Torn becomes observable (Seam.peers): a " +
                "consumer woken by the terminal state must not read the pre-close roster",
        )
    }

    @Test
    fun closingOneSeamDoesNotEvictItFromAnotherSeamsRoster() = runTest {
        val api = ControllableNearbyApi()
        val closingScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val otherScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val remote = PeerId("remote")
        // ONE roster flow handed to both seams. Since #1878 the loom does not build this — it mints
        // a flow per weave — so this is constructed by hand to keep the seam-level obligation
        // reachable: `close()` must collapse its own view and write the flow not at all.
        val sessionRoster = MutableStateFlow(setOf(self, remote))
        val closing = wovenSeam(api, closingScope, mapOf("ep-remote" to remote), weavePeers = sessionRoster)
        val other = wovenSeam(api, otherScope, mapOf("ep-self" to self), selfId = remote, weavePeers = sessionRoster)

        // ControllableNearbyApi.disconnect is a no-op, so `other` learns nothing over the wire:
        // anything that changes its roster here got there by a direct write to the shared flow.
        closing.close()

        assertAll(
            {
                assertContains(
                    other.peers.value,
                    self,
                    "closing one seam must not evict its own id from ANOTHER seam's roster — the peer " +
                        "departure the counterparty is entitled to learn from its own transport",
                )
            },
            {
                assertContains(other.peers.value, remote, "…nor disturb the other seam's own id")
            },
            {
                assertEquals(
                    setOf(self, remote),
                    sessionRoster.value,
                    "close() must collapse this seam's own peers view, not write the loom-wide roster",
                )
            },
        )

        otherScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Guard, not a reproducer: the remote-driven tear already collapsed correctly before #1850
     * (the disconnect path drops the departing peer *before* latching). It is pinned because the
     * fix reroutes `peers` off the shared flow onto a per-seam view, and this path latches `Torn`
     * from inside a lock with the roster edit one line above — the shape most likely to end up
     * publishing a stale mirror.
     */
    @Test
    fun remoteDrivenTearAlsoCollapsesTheRosterToSelfId() = runTest {
        val api = ControllableNearbyApi()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val seam = wovenSeam(api, scope, mapOf("ep-remote" to PeerId("remote")))

        api.disconnects.emit(EndpointDisconnected("ep-remote"))

        assertAll(
            { assertIs<SeamState.Torn>(seam.state.value, "precondition: the last endpoint leaving tears the seam") },
            {
                assertEquals(
                    setOf(self),
                    seam.peers.value,
                    "a self-driven tear collapses the roster to { selfId } exactly as close() does",
                )
            },
        )
    }
}
