package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TCK for [NwSeam]: N `NwSeam`s over ONE [FakeNwRadio], forming a full mesh by direct
 * dialling (the seam does not auto-dial — that is `NwLoom`'s job in Task 2.7, so the
 * test dials every endpoint itself). Runs under `runTest`'s virtual clock: distinct
 * per-node [PeerId]s (lexicographically ordered so the dedup tiebreak is deterministic),
 * seam coroutines on [TestScope.backgroundScope], and BOUNDED pumping only — never
 * `advanceUntilIdle()`.
 */
class NwSeamTest {

    private class Device(val peerId: PeerId, val api: FakeNwApi, val seam: NwSeam) {
        val received = mutableListOf<Swatch>()
    }

    private companion object {
        const val TYPE = "_kuilt._tcp"
        /** Bounded pump: run current-virtual-time tasks until [cond] or the cap. Never hangs. */
        fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
            repeat(maxPumps) {
                if (cond()) return true
                testScheduler.runCurrent()
            }
            return cond()
        }

        /**
         * A dedicated child scope per seam: still a child of [TestScope.backgroundScope] (so it is
         * cancelled at test teardown) but with its OWN [Job], so one seam's teardown — which cancels
         * the scope it was given via `latchTorn` — does NOT cancel the other seams' loops or the
         * assertion collectors. Passing the shared `backgroundScope` masked teardown: the first
         * `close()` cancelled every seam and every `incoming` collector at once.
         */
        fun TestScope.seamScope(): CoroutineScope =
            CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
    }

    /** Endpoint the radio maps back to device `dev-<i>` (see FakeNwRadio's `ep-<deviceId>`). */
    private fun endpointFor(i: Int) = NwEndpoint(id = "ep-dev-$i", serviceName = "svc-$i")

    /**
     * Build an N-node full mesh over one radio and wait for every node's `peers` to converge to
     * all N ids. Each unordered pair is dialled from BOTH ends (a double-dial), so dedup runs.
     */
    private fun TestScope.buildMesh(n: Int, policy: DeliveryPolicy = DeliveryPolicy.Reliable): List<Device> {
        val radio = FakeNwRadio()
        val devices = (0 until n).map { i ->
            val api = FakeNwApi(radio, deviceId = "dev-$i", serviceName = "svc-$i")
            // Each seam gets its OWN scope so one seam's teardown doesn't cancel the others. A
            // per-node SEEDED Random gives distinct nonces so the canonical-nonce dedup is deterministic.
            Device(PeerId("peer-$i"), api, NwSeam(PeerId("peer-$i"), api, seamScope(), Random(i.toLong()), policy))
        }
        // Single-collection: collect each seam's incoming exactly once — into backgroundScope
        // (NOT the seam's own scope), so the collector can only terminate because spool.close()
        // completed incoming, never because the seam cancelled its scope.
        for (d in devices) {
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                d.seam.incoming.collect { d.received += it }
            }
        }
        testScheduler.runCurrent()
        // Full-mesh direct dial: every ordered (i, j) dials → both ends of each pair dial (double-dial).
        for (i in devices.indices) {
            for (j in devices.indices) {
                if (i != j) {
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        devices[i].api.connect(endpointFor(j))
                    }
                }
            }
        }
        assertTrue(
            pumpUntil { devices.all { it.seam.peers.value.size == n } },
            "mesh did not converge to $n peers: ${devices.map { it.peerId to it.seam.peers.value }}",
        )
        return devices
    }

    @Test
    fun broadcastReachesEveryPeerAttributedToTheSender() = runTest(StandardTestDispatcher()) {
        val (a, b, c) = buildMesh(3)
        a.seam.broadcast("hello".encodeToByteArray())
        pumpUntil { b.received.isNotEmpty() && c.received.isNotEmpty() }

        assertAll(
            { assertEquals(1, b.received.size, "B receives exactly one frame") },
            { assertEquals(1, c.received.size, "C receives exactly one frame") },
            { assertEquals("hello", b.received.single().decodeToString()) },
            { assertEquals("hello", c.received.single().decodeToString()) },
            // Direct-mesh delivery: sender is A itself, not a relay/hub id.
            { assertEquals(a.peerId, b.received.single().sender, "B sees A as sender") },
            { assertEquals(a.peerId, c.received.single().sender, "C sees A as sender") },
        )
    }

    @Test
    fun sendToDeliversToTheAddressedPeerOnly() = runTest(StandardTestDispatcher()) {
        val (a, b, c) = buildMesh(3)
        a.seam.sendTo(c.peerId, "direct".encodeToByteArray())
        pumpUntil { c.received.isNotEmpty() }

        assertAll(
            { assertEquals(1, c.received.size, "C receives the directed frame") },
            { assertEquals("direct", c.received.single().decodeToString()) },
            { assertEquals(a.peerId, c.received.single().sender, "C sees A as sender") },
            { assertEquals(0, b.received.size, "B does NOT receive a directed A→C frame") },
        )
    }

    @Test
    fun sendToAbsentPeerThrowsPeerNotConnected() = runTest(StandardTestDispatcher()) {
        val (a, _, _) = buildMesh(3)
        assertFailsWith<PeerNotConnected> {
            a.seam.sendTo(PeerId("peer-absent"), "nope".encodeToByteArray())
        }
    }

    @Test
    fun doubleDialCollapsesToOneConnectionWithExactlyOnceDelivery() = runTest(StandardTestDispatcher()) {
        // A 2-node mesh double-dials (A→B and B→A). Dedup must keep exactly ONE connection so
        // a broadcast is delivered exactly once — not duplicated over both underlying links.
        val (a, b) = buildMesh(2)

        a.seam.broadcast("a-says".encodeToByteArray())
        b.seam.broadcast("b-says".encodeToByteArray())
        pumpUntil { a.received.isNotEmpty() && b.received.isNotEmpty() }

        assertAll(
            { assertEquals(setOf(a.peerId, b.peerId), a.seam.peers.value) },
            { assertEquals(setOf(a.peerId, b.peerId), b.seam.peers.value) },
            // Exactly-once each way over the single surviving connection.
            { assertEquals(1, b.received.size, "B received A's broadcast exactly once") },
            { assertEquals("a-says", b.received.single().decodeToString()) },
            { assertEquals(a.peerId, b.received.single().sender) },
            { assertEquals(1, a.received.size, "A received B's broadcast exactly once") },
            { assertEquals("b-says", a.received.single().decodeToString()) },
            { assertEquals(b.peerId, a.received.single().sender) },
        )
    }

    @Test
    fun everyNodePeersConvergesToAllIds() = runTest(StandardTestDispatcher()) {
        val devices = buildMesh(4)
        val allIds = devices.map { it.peerId }.toSet()
        assertAll(
            *devices.map { d ->
                { assertEquals(allIds, d.seam.peers.value, "${d.peerId} converged to all ids") }
            }.toTypedArray(),
        )
    }

    @Test
    fun closeDrivesTornAndCollapsesPeers() = runTest(StandardTestDispatcher()) {
        val (a, _, _) = buildMesh(3)
        a.seam.close()
        pumpUntil { a.seam.state.value is SeamState.Torn }

        assertAll(
            { assertEquals(SeamState.Torn(CloseReason.Normal), a.seam.state.value, "A is Torn(Normal)") },
            { assertEquals(setOf(a.peerId), a.seam.peers.value, "A's peers collapse to {A}") },
        )
    }

    @Test
    fun incomingCompletesOnClose() = runTest(StandardTestDispatcher()) {
        // Per-seam scopes: closing seamA cancels only ITS scope, not the collector below. So the
        // collector can only terminate because spool.close() completed incoming — proving the
        // completion contract, not that a shared scope was cancelled out from under it.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0))
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1))
        var completed = false
        // Collector lives in backgroundScope (a scope seamA does NOT cancel); it sets `completed`
        // only AFTER collect returns NORMALLY, so the flag distinguishes completion from cancellation.
        val collectJob = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            seamA.incoming.collect { }
            completed = true
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        pumpUntil { seamA.peers.value.size == 2 }

        seamA.close()
        pumpUntil { completed }
        assertAll(
            { assertTrue(completed, "A.incoming completed (collect returned) when the seam tore") },
            { assertTrue(collectJob.isCompleted && !collectJob.isCancelled, "completed NORMALLY, not by cancellation") },
        )
    }

    @Test
    fun selfConnectionIsDroppedAndSelfNeverJoinsTheRoster() = runTest(StandardTestDispatcher()) {
        // #1466 root cause. Real Bonjour returns a device's OWN advertisement to its own browser — and
        // in the election mesh both peers advertise the SAME Rendezvous.New service name, so NwLoom
        // discovers self and dials it. (FakeNwRadio omits self from discovery, which is exactly why this
        // shipped uncaught — so we drive the self-dial explicitly here.) NwSeam MUST NOT register that
        // self-connection: registering self puts selfId in the roster/registry, and when the self-link
        // later fails, connectionClosedLoop evicts self — dropping this peer from its OWN roster with no
        // Torn (peers → [otherPeer], state stays Woven), silently wedging every consumer keying on
        // peers/host. The self-link must be dropped: self stays alone, no remote resolves.
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-self")
        val seam = NwSeam(self, api, seamScope(), Random(0))
        testScheduler.runCurrent()

        api.connect(NwEndpoint(id = "ep-dev-0", serviceName = "svc-0")) // dev-0 dials its OWN endpoint
        repeat(50) { testScheduler.runCurrent() } // let the self-hello exchange settle

        assertAll(
            { assertEquals(setOf(self), seam.peers.value, "self must never join its own roster as a remote") },
            {
                assertTrue(
                    seam.state.value is SeamState.Weaving,
                    "no real remote resolved → still Weaving (pre-fix wrongly resolved self and went Woven)",
                )
            },
        )
    }

    @Test
    fun sendFailureOnLastPeerTearsSeamAndCompletesIncoming() = runTest(StandardTestDispatcher()) {
        // Fix 2 coverage: a send failure that evicts the LAST remote must tear the seam to
        // Torn(RemoteRequested) and complete incoming — mirroring a clean connectionClosed — not
        // leave it stuck Woven with peers == {selfId}.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0))
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1))
        var completed = false
        val collectJob = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            seamA.incoming.collect { }
            completed = true
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        pumpUntil { seamA.peers.value.size == 2 }

        // Make A's next send fail → the single remote is evicted via the send-failure path.
        apiA.failSend = true
        seamA.broadcast("boom".encodeToByteArray())
        pumpUntil { seamA.state.value is SeamState.Torn }

        pumpUntil { completed }
        assertAll(
            { assertEquals(SeamState.Torn(CloseReason.RemoteRequested), seamA.state.value, "A tears to Torn(RemoteRequested)") },
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A}") },
            { assertTrue(completed, "A.incoming completes after send-failure teardown") },
            { assertTrue(collectJob.isCompleted && !collectJob.isCancelled, "completed NORMALLY, not by cancellation") },
        )
    }

    @Test
    fun tornStaysTornAndSendsThrowAfterClose() = runTest(StandardTestDispatcher()) {
        val (a, b, _) = buildMesh(3)
        a.seam.close()
        pumpUntil { a.seam.state.value is SeamState.Torn }

        // A late remote close (e.g. B tears its own side) must not un-tear A.
        b.seam.close()
        pumpUntil(maxPumps = 50) { false }

        assertAll(
            { assertTrue(a.seam.state.value is SeamState.Torn, "still Torn after churn") },
            { assertEquals(CloseReason.Normal, (a.seam.state.value as SeamState.Torn).reason) },
        )
        assertFailsWith<IllegalStateException> { a.seam.broadcast("x".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { a.seam.sendTo(b.peerId, "x".encodeToByteArray()) }
    }
}
