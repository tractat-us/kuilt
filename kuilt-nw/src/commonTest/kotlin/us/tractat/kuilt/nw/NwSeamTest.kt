@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.advanceTimeBy for the #1478 grace-timer tests

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
import kotlin.time.Duration.Companion.seconds

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
    fun remotePeerDepartureCollapsesPeersAndLatchesTorn() = runTest(StandardTestDispatcher()) {
        // #1466 investigation (#1472): the exact "peers 2→1" collapse path. A 2-node mesh forms
        // (A.peers == {A,B}, Woven). B departs — its close() disconnects B's connections, so the
        // radio delivers connectionClosed to A's surviving link. A must remove B (peers → {A}) AND,
        // because that was the last remote after having woven, LATCH Torn in the SAME step. Proves
        // NwSeam cannot leave peers == {self} while Woven-and-alive: a genuine 2→1 always tears.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0))
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1))
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        apiB.connect(NwEndpoint(id = "ep-dev-0", serviceName = "svc-0"))
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")

        // B departs: closing B disconnects B's links; the radio delivers connectionClosed to A.
        seamB.close()
        assertTrue(pumpUntil { seamA.state.value is SeamState.Torn }, "A tore on the remote departure")

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A} — never stuck at {A}+alive") },
            { assertEquals(SeamState.Torn(CloseReason.RemoteRequested), seamA.state.value, "A latches Torn(RemoteRequested)") },
        )
    }

    @Test
    fun pathLossThatRecoversWithinGraceKeepsThePeerAndDoesNotTear() = runTest(StandardTestDispatcher()) {
        // #1478: a Network.framework connection that loses its path goes ready→waiting (NOT failed),
        // firing NO close. NwSeam arms a grace timer on viable=false; if the path recovers (viable=true)
        // before the grace expires, the timer is cancelled and the peer STAYS — no tear.
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0), wovenPathGrace = grace)
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1), wovenPathGrace = grace)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1")) // A dials → A's handle is conn-dev-0-0
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")

        // Path lost on A's live link to B. Advance to JUST before the grace expiry, then recover.
        apiA.emitConnectionViability(NwConnectionId("conn-dev-0-0"), viable = false)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds - 1)
        testScheduler.runCurrent()
        apiA.emitConnectionViability(NwConnectionId("conn-dev-0-0"), viable = true)
        testScheduler.runCurrent()
        // Advance well PAST the original expiry: proves the timer was cancelled, not merely deferred.
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds * 2)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(setOf(seamA.selfId, PeerId("peer-1")), seamA.peers.value, "B stays in A's peers") },
            { assertTrue(seamA.state.value is SeamState.Woven, "A stays Woven — the recovered path did not tear") },
        )
    }

    @Test
    fun pathLossThatExhaustsGraceTearsToUnreachableAndCompletesIncoming() = runTest(StandardTestDispatcher()) {
        // #1478 core: a path loss that does NOT recover within the grace tears the seam. Because this
        // was the last remote after having woven, peers collapse to {self}, state latches
        // Torn(Unreachable) (distinct from a clean RemoteRequested leave), and incoming completes.
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0), wovenPathGrace = grace)
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1), wovenPathGrace = grace)
        var completed = false
        val collectJob = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            seamA.incoming.collect { }
            completed = true
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")

        // Path lost and never recovers: advance just past the grace so the timer fires.
        apiA.emitConnectionViability(NwConnectionId("conn-dev-0-0"), viable = false)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds + 1)
        assertTrue(pumpUntil { seamA.state.value is SeamState.Torn }, "grace exhausted → A tore")
        pumpUntil { completed }

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A}") },
            { assertEquals(SeamState.Torn(CloseReason.Unreachable), seamA.state.value, "A latches Torn(Unreachable)") },
            { assertTrue(completed, "A.incoming completes on the grace-expiry teardown") },
            { assertTrue(collectJob.isCompleted && !collectJob.isCancelled, "completed NORMALLY, not by cancellation") },
        )
    }

    @Test
    fun latestViabilityRecoveryIsNeverLostUnderBufferPressureNoSpuriousTear() = runTest(StandardTestDispatcher()) {
        // #1509: viability is per-connection STATE, not a fire-and-forget event. Under buffer pressure the
        // OLD tryEmit event stream could DROP a `viable=true` recovery, stranding NwSeam's armed grace timer
        // → a spurious tear of a healthy, recovered peer ~grace later. Represented as drop-tolerant
        // latest-value state, the LATEST value (true) is always observable, so the seam reconciles the
        // recovery and never tears.
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0), wovenPathGrace = grace)
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1), wovenPathGrace = grace)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1")) // A dials → A's handle is conn-dev-0-0
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")

        val live = NwConnectionId("conn-dev-0-0")
        // 1) Path lost on A's live link to B → the seam arms its grace timer.
        apiA.emitConnectionViability(live, viable = false)
        testScheduler.runCurrent()
        // 2) Recovery under buffer pressure: a filler signal (a DIFFERENT connection) fills the 1-slot
        //    buffer, then the recovery `viable=true` for the live link is emitted while the buffer is full.
        //    Under the OLD event stream that recovery is DROPPED (tryEmit returns false) and the grace timer
        //    strands; as drop-tolerant latest-value state the recovery is retained and reconciled.
        apiA.emitConnectionViability(NwConnectionId("conn-filler"), viable = false) // fills the 1-slot buffer
        apiA.emitConnectionViability(live, viable = true) // recovery — dropped by the old event design
        testScheduler.runCurrent()
        // 3) Advance well PAST the grace expiry: a stranded timer would fire and spuriously tear here.
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds * 2)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(setOf(seamA.selfId, PeerId("peer-1")), seamA.peers.value, "B stays in A's peers — recovery reconciled") },
            { assertTrue(seamA.state.value is SeamState.Woven, "A stays Woven — no spurious tear from a stranded grace timer") },
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
