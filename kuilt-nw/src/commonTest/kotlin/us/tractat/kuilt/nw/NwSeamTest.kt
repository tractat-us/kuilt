@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.advanceTimeBy for the #1478 grace-timer tests

package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.assertFalse
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
         * Bounded pump until [read] STOPS MOVING for [stableFor] consecutive pumps; returns the
         * settled value. Never hangs (capped at [maxPumps]).
         *
         * Deliberately quiesces rather than waiting for an expected value: a wait keyed to the
         * number the caller is about to assert makes its failing arm an uninformative timeout,
         * whereas this returns whatever the mesh actually settled on and lets `assertEquals` print
         * both sides. Advances no virtual time — `runCurrent` only drains the current instant — so
         * what it observes is the settled handshake state, not a later timer's doing.
         */
        fun TestScope.pumpUntilStable(stableFor: Int = 5, maxPumps: Int = 500, read: () -> Int): Int {
            var last = read()
            var stable = 0
            repeat(maxPumps) {
                testScheduler.runCurrent()
                val now = read()
                if (now == last) {
                    stable += 1
                    if (stable >= stableFor) return now
                } else {
                    stable = 0
                    last = now
                }
            }
            return read()
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

    /** All radios off (airplane mode) — the device path the #1618 Track A self-loss hook keys on. */
    private fun airplaneDown() = NwPathState(
        status = NwPathStatus.Unsatisfied,
        interfaces = emptySet(),
        isExpensive = false,
        isConstrained = false,
        unsatisfiedReason = NwUnsatisfiedReason.NotAvailable,
    )

    /** A satisfied peer-to-peer Wi-Fi path — the recovery the false-positive-safety test drives. */
    private fun wifiUp() = NwPathState(
        status = NwPathStatus.Satisfied,
        interfaces = setOf(NwInterfaceType.WifiDirect),
        isExpensive = false,
        isConstrained = false,
        unsatisfiedReason = null,
    )

    /**
     * Build an N-node full mesh over one radio and wait for every node's `peers` to converge to
     * all N ids. Each unordered pair is dialled from BOTH ends (a double-dial), so dedup runs.
     *
     * [radio] is supplied only by the tests that need to read the switchboard's link accounting
     * afterwards ([FakeNwRadio.liveLinkCount] / [FakeNwRadio.openedLinkCount]); every other caller
     * takes a fresh one. Exactly `n * (n - 1)` dials are issued here and the seam itself never
     * dials, so `openedLinkCount` is a closed-form receipt that the double-dial happened.
     */
    private fun TestScope.buildMesh(
        n: Int,
        policy: DeliveryPolicy = DeliveryPolicy.Reliable,
        radio: FakeNwRadio = FakeNwRadio(),
    ): List<Device> {
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
        //
        // #2390: until this test read [FakeNwRadio.liveLinkCount] it proved only the exactly-once
        // half of its own name. Delivery cannot see the collapse: broadcast/sendTo fan out over
        // NwSeam's `registry`, one connection per peer, so a duplicate link dedup FAILED to close
        // carries no frames — it is a wasted socket, not a duplicating one — and `peers` is a Set,
        // which a second link cannot change either. Deleting resolveIdentity's dedup outright left
        // all 186 tests in this module green.
        val radio = FakeNwRadio()
        val (a, b) = buildMesh(2, radio = radio)

        a.seam.broadcast("a-says".encodeToByteArray())
        b.seam.broadcast("b-says".encodeToByteArray())
        pumpUntil { a.received.isNotEmpty() && b.received.isNotEmpty() }
        val liveLinks = pumpUntilStable { radio.liveLinkCount }

        assertAll(
            // The rig fired: BOTH ends dialled, so the pair really did produce two links for dedup
            // to choose between. Without this the live-link assertion below would be satisfied by a
            // mesh that never double-dialled at all.
            { assertEquals(2, radio.openedLinkCount, "the one pair was dialled from both ends") },
            { assertEquals(1, liveLinks, "dedup disconnected the loser, leaving one live link") },
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
    fun everyMeshSizeSettlesAtExactlyOneLiveLinkPerPeerPair() = runTest(StandardTestDispatcher()) {
        // The #2390 property at the sizes that can distinguish it. A 2-node mesh alone cannot: it
        // settles at 1 link, and `n - 1`, `n / 2` and the constant 1 all yield 1 there too, so a
        // wrong formula reads as correct. Sweeping n = 2, 3, 4 pins the count to 1, 3, 6 — the
        // quadratic n*(n-1)/2 and nothing else of lower degree — and at n = 4 the deduped and
        // un-deduped counts are 6 against 12 rather than 1 against 2.
        //
        // Each size is measured on its OWN radio, so the counters describe that mesh only.
        val observed = (2..4).map { n ->
            val radio = FakeNwRadio()
            buildMesh(n, radio = radio)
            Triple(n, pumpUntilStable { radio.liveLinkCount }, radio.openedLinkCount)
        }

        assertAll(
            *observed.flatMap { (n, live, opened) ->
                val pairs = n * (n - 1) / 2
                listOf(
                    // Rig receipt per size: every unordered pair was dialled from both ends, so
                    // twice the settled count was opened. A mesh whose links all survived would
                    // report `live == opened`; one that never double-dialled, `opened == pairs`.
                    { assertEquals(2 * pairs, opened, "n=$n: every one of $pairs pairs double-dialled") },
                    { assertEquals(pairs, live, "n=$n: settles at one live link per pair") },
                )
            }.toTypedArray(),
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
    fun closeStopsAdvertisingAndBrowsing() = runTest(StandardTestDispatcher()) {
        // #1419 (I3): NwSeam owns the close lifecycle and holds the NwApi that NwLoom.weave started
        // advertising/browsing on — so close() MUST stop the advertiser and the browser. On a real device
        // an un-stopped NWListener/NWBrowser keeps the Bonjour advertiser + AWDL up and retains RealNwApi
        // via the ObjC block captures. Assert close() invokes stopListening/stopBrowsing exactly once.
        val (a, _, _) = buildMesh(3)
        a.seam.close()
        pumpUntil { a.seam.state.value is SeamState.Torn }

        assertAll(
            { assertEquals(1, a.api.stopListeningCalls, "close() stops advertising exactly once") },
            { assertEquals(1, a.api.stopBrowsingCalls, "close() stops browsing exactly once") },
        )
    }

    @Test
    fun closeConcurrentWithARemoteDepartureAlwaysEndsTornNeverReformsToWeaving() = runTest(StandardTestDispatcher()) {
        // #1513 review Fix 2: latchTorn writes _state=Torn UNDER the seam lock, so a concurrent locked
        // eviction (evictPeerLocked's Woven→Weaving reform) can never clobber terminal Torn back to Weaving.
        // A single-thread test dispatcher can't reproduce the true multi-threaded window, but this asserts
        // the observable invariant across the interleavings the scheduler produces: A's own close() issued
        // together with B's departure (which drives A's eviction/reform path) must ALWAYS end Torn — never
        // a re-formed Weaving with incoming already completed.
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

        // Issue A's close and B's departure "together": B's close delivers connectionClosed to A (the
        // eviction→reform path) while A tears itself. Whichever the scheduler runs first, A must end Torn —
        // close-first: the eviction sees closed=true and is a no-op; evict-first: latchTorn's under-lock
        // Torn write follows the Weaving write; a post-Torn writer reads Torn (not Woven) and makes no flip.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.close() }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.close() }
        assertTrue(pumpUntil { seamA.state.value is SeamState.Torn }, "A latched Torn")
        pumpUntil(maxPumps = 100) { false } // let any (wrong) reform-to-Weaving surface

        assertAll(
            { assertTrue(seamA.state.value is SeamState.Torn, "A stays Torn — a concurrent eviction never un-tears it to Weaving (was ${seamA.state.value})") },
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A}") },
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
    fun aMisresolvedDialNeverMarksTheDialledPeersEndpointAsSelf() = runTest(StandardTestDispatcher()) {
        // #2416, observed on hardware 2026-08-15 (two iPhone 17 Pro, kuilt 0.7.0-dev.1248). Under
        // Rendezvous.New identity comes from the TXT PeerId but the DIAL goes to a shared Bonjour name
        // that mDNS re-resolves at connect time — so a dial armed FOR a real peer can land on the local
        // device. The self-connection guard correctly drops that link (its `remoteId == selfId` operand
        // is the id the far end asserts in its own hello, so it cannot false-positive). What it must NOT
        // do is record the DIALLED endpoint id as a self endpoint: here that id is the REAL peer's.
        //
        // Why that is fatal rather than untidy: selfEndpointIds feeds settledEndpoints, and
        // NwLoom.redialLoop parks on a settled endpoint (`settledEndpoints.first { id !in it }`) until it
        // un-settles — but selfEndpointIds is cleared ONLY on full seam teardown, never per entry. So
        // poisoning the real peer's id blacklists that peer for the seam's whole lifetime, turning a
        // one-shot mDNS race into permanent formation failure. On hardware this read as "the two phones
        // never saw each other" with a perfectly healthy radio at -27 dBm.
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-self")
        val seam = NwSeam(self, api, seamScope(), Random(0))
        testScheduler.runCurrent()

        // The id the browse result carried — the REAL peer's TXT PeerId, not this device's endpoint.
        val realPeerEndpointId = "peer-remote"
        radio.injectDialLandingOnSelf("dev-0", realPeerEndpointId, identityResolved = true)
        repeat(50) { testScheduler.runCurrent() }

        assertAll(
            { assertEquals(setOf(self), seam.peers.value, "self must never join its own roster as a remote") },
            {
                assertFalse(
                    realPeerEndpointId in seam.settledEndpoints.value,
                    "the dialled REAL peer's endpoint was marked settled-as-self — NwLoom.redialLoop then " +
                        "parks on it forever (selfEndpointIds is never pruned), starving reconnection to " +
                        "that peer for the seam's lifetime",
                )
            },
        )
    }

    @Test
    fun aGenuineSelfDialStillSettlesSoTheLoomStopsRedialingIt() = runTest(StandardTestDispatcher()) {
        // POSITIVE CONTROL for the #2416 guard above. That fix narrows WHEN a self-resolved connection
        // settles its dialled endpoint, and before it no test pinned the settling at all — the only other
        // `settledEndpoints` assertions in this file concern a PEER's endpoint (#1513 departure). An
        // un-pinned branch is how the poisoning shipped, so both surviving settle-cases are pinned here.
        //
        // Settling must still happen when the dialled endpoint CAN be ours, in both provenances:
        //  - identityResolved=true  and id == selfId  → our own TXT-resolved advertisement.
        //  - identityResolved=false                   → the fallback id is the serviceName, which under
        //    Rendezvous.New is the shared name and may be ours (#1709). Safe to settle: the key is a name,
        //    not a peer identity, so a later resolved sighting arms a fresh redialer under a different key.
        // Without this, NwLoom.redialLoop would spin on its own advertisement forever (#1513).
        val self = PeerId("peer-self")

        val txtRadio = FakeNwRadio()
        val txtApi = FakeNwApi(txtRadio, deviceId = "dev-0", serviceName = "svc-0")
        val txtSeam = NwSeam(self, txtApi, seamScope(), Random(0))
        testScheduler.runCurrent()
        txtRadio.injectDialLandingOnSelf("dev-0", self.value, identityResolved = true)
        repeat(50) { testScheduler.runCurrent() }

        val fallbackRadio = FakeNwRadio()
        val fallbackApi = FakeNwApi(fallbackRadio, deviceId = "dev-0", serviceName = "svc-0")
        val fallbackSeam = NwSeam(self, fallbackApi, seamScope(), Random(0))
        testScheduler.runCurrent()
        fallbackRadio.injectDialLandingOnSelf("dev-0", "shared-session-name", identityResolved = false)
        repeat(50) { testScheduler.runCurrent() }

        assertAll(
            {
                assertTrue(
                    self.value in txtSeam.settledEndpoints.value,
                    "our OWN TXT-resolved endpoint must settle, or NwLoom redials its own advertisement forever",
                )
            },
            {
                assertTrue(
                    "shared-session-name" in fallbackSeam.settledEndpoints.value,
                    "an unresolved fallback id must still settle (#1709) — it is a name key, not a peer identity",
                )
            },
            { assertEquals(setOf(self), txtSeam.peers.value, "self never joins its own roster") },
            { assertEquals(setOf(self), fallbackSeam.peers.value, "self never joins its own roster") },
        )
    }

    @Test
    fun sendFailureOnLastPeerReformsToWeavingNotTorn() = runTest(StandardTestDispatcher()) {
        // #1513: a send failure that evicts the LAST remote re-forms the seam to Weaving (recoverable) —
        // NOT Torn — and keeps incoming OPEN, so NwLoom can redial. (Pre-#1513 this tore to
        // Torn(RemoteRequested) and completed incoming.)
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
        assertTrue(pumpUntil { seamA.peers.value == setOf(seamA.selfId) }, "A evicted B via send failure")
        pumpUntil(maxPumps = 50) { false } // give a wrong Torn/incoming-completion a chance to surface

        assertAll(
            { assertTrue(seamA.state.value is SeamState.Weaving, "A re-forms to Weaving, NOT Torn — was ${seamA.state.value}") },
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A}") },
            { assertTrue(!completed && !collectJob.isCompleted, "A.incoming stays OPEN after a send-failure eviction") },
        )
    }

    @Test
    fun remotePeerDepartureCollapsesPeersAndReformsToWeavingNotTorn() = runTest(StandardTestDispatcher()) {
        // #1513 policy: a peer loss is RECOVERABLE, not terminal. A 2-node mesh forms (A.peers == {A,B},
        // Woven). B departs — its close() disconnects B's connections, so the radio delivers
        // connectionClosed to A's surviving link. A must remove B (peers → {A}) and RE-FORM
        // Woven→Weaving — NOT latch Torn — keeping incoming OPEN so NwLoom can redial and rejoin.
        // (Pre-#1513 this latched Torn(RemoteRequested); reverting evictPeerLocked's reform makes this fail.)
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0))
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1))
        var aIncomingCompleted = false
        val aCollect = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            seamA.incoming.collect { }
            aIncomingCompleted = true
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        apiB.connect(NwEndpoint(id = "ep-dev-0", serviceName = "svc-0"))
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")

        // B departs: closing B disconnects B's links; the radio delivers connectionClosed to A.
        seamB.close()
        assertTrue(pumpUntil { seamA.peers.value == setOf(seamA.selfId) }, "A evicted B, peers collapse to {A}")
        // Give any (wrongly) latched Torn / incoming completion a chance to surface — must NOT happen.
        pumpUntil(maxPumps = 50) { false }

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A}") },
            { assertTrue(seamA.state.value is SeamState.Weaving, "A re-forms to Weaving (recoverable), NOT Torn — was ${seamA.state.value}") },
            { assertTrue(!aIncomingCompleted && !aCollect.isCompleted, "A.incoming stays OPEN — a reforming seam does not complete incoming") },
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
    fun pathLossThatExhaustsGraceReformsToWeavingNotTorn() = runTest(StandardTestDispatcher()) {
        // #1478 + #1513: a path loss that does NOT recover within the grace evicts the peer, but that is
        // now RECOVERABLE — the last-remote eviction re-forms Woven→Weaving (peers → {self}) and keeps
        // incoming OPEN so NwLoom redials. (Pre-#1513 this latched Torn(Unreachable) and completed incoming.)
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
        assertTrue(pumpUntil { seamA.peers.value == setOf(seamA.selfId) }, "grace exhausted → A evicted B")
        pumpUntil(maxPumps = 50) { false }

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A}") },
            { assertTrue(seamA.state.value is SeamState.Weaving, "A re-forms to Weaving, NOT Torn(Unreachable) — was ${seamA.state.value}") },
            { assertTrue(!completed && !collectJob.isCompleted, "A.incoming stays OPEN after the grace-expiry eviction") },
        )
    }

    @Test
    fun devicePathUnsatisfiedDrivesFastSelfLossAndReformsAfterGrace() = runTest(StandardTestDispatcher()) {
        // #1618 Track A: on a 2-phone airplane drop, the dropped phone's radios go off and its device-wide
        // NWPathMonitor reports `unsatisfied` — but its per-connection `ready→waiting` signal may not fire
        // promptly, so the phone used to learn of the loss only from the peer's 15s silence. This wires the
        // device-path-unsatisfied event to drive EVERY live connection to PathLost, reusing the SAME #1478
        // grace pipeline: the phone self-observes near the grace window instead of 15–75s later. Detection ≈
        // grace (false-positive-safe by construction — a recovering blip within grace is a no-op, see the
        // companion test below), NOT an immediate 1s tear.
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

        // A's radios drop: the device path goes unsatisfied. This must demote A's live connection(s) to
        // PathLost and arm the grace timer — WITHOUT the peer's own connectionClosed/waiting ever firing.
        apiA.emitPathState(airplaneDown())
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds + 1)
        assertTrue(pumpUntil { seamA.peers.value == setOf(seamA.selfId) }, "device-path grace exhausted → A evicted B")
        pumpUntil(maxPumps = 50) { false }

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A}") },
            { assertTrue(seamA.state.value is SeamState.Weaving, "A re-forms to Weaving (recoverable), NOT Torn — was ${seamA.state.value}") },
            { assertTrue(!completed && !collectJob.isCompleted, "A.incoming stays OPEN after the device-path self-loss eviction") },
        )
    }

    @Test
    fun devicePathUnsatisfiedThenSatisfiedWithinGraceDoesNotTear() = runTest(StandardTestDispatcher()) {
        // #1618 Track A false-positive safety: NWPathMonitor fires `unsatisfied` on every transient path
        // reshuffle (a brief total-loss blip, Wi-Fi↔cellular churn). An UNgraced tear would evict healthy
        // sessions. Reusing the #1478 grace makes a recovering blip a no-op: the device path going back to
        // satisfied within the grace restores viability and cancels the tear — the peer STAYS, no eviction.
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0), wovenPathGrace = grace)
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1), wovenPathGrace = grace)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")

        // Device path blips down, then recovers JUST before the grace expiry.
        apiA.emitPathState(airplaneDown())
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds - 1)
        testScheduler.runCurrent()
        apiA.emitPathState(wifiUp())
        testScheduler.runCurrent()
        // Advance well past the original expiry: proves the grace timer was cancelled, not merely deferred.
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds * 2)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(setOf(seamA.selfId, PeerId("peer-1")), seamA.peers.value, "B stays in A's peers") },
            { assertTrue(seamA.state.value is SeamState.Woven, "A stays Woven — the recovered device path did not tear") },
        )
    }

    @Test
    fun reconnectAfterReformFlipsWeavingBackToWoven() = runTest(StandardTestDispatcher()) {
        // #1513 test 2: after a peer loss re-forms the seam to Weaving, a fresh connection to the same
        // peer must flip it back Woven (the existing addPeerLocked path). A 2-seam pair forms over a
        // single dial (A's live link to B is deterministically conn-dev-0-0); a path-loss grace expiry
        // evicts B and disconnects the link (so B re-forms too), then A re-dials and both re-weave.
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0), wovenPathGrace = grace)
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1), wovenPathGrace = grace)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1")) // single dial → A's link is conn-dev-0-0
        assertTrue(pumpUntil { seamA.peers.value.size == 2 && seamB.peers.value.size == 2 }, "wove to 2 peers")

        // Drop A's live path; grace expiry evicts B AND api.disconnect tears the link → B re-forms too.
        apiA.emitConnectionViability(NwConnectionId("conn-dev-0-0"), viable = false)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds + 1)
        assertTrue(pumpUntil { seamA.state.value is SeamState.Weaving && seamB.state.value is SeamState.Weaving }, "both re-form to Weaving")

        // Reconnect: a fresh dial to the same peer must flip Weaving → Woven and regain the remote.
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A re-wove after reconnect")

        assertAll(
            { assertTrue(seamA.state.value is SeamState.Woven, "A flips Weaving → Woven on reconnect — was ${seamA.state.value}") },
            { assertEquals(setOf(seamA.selfId, PeerId("peer-1")), seamA.peers.value, "A regains B") },
        )
    }

    @Test
    fun viabilityLossObservedBeforeConnIsTrackedIsReconciledOnRegistrationNoZombie() = runTest(StandardTestDispatcher()) {
        // #1509 lost-wakeup race: viability is latest-value STATE that never re-emits an unchanged value.
        // If a `viable=false` for a connection is observed BEFORE the seam has registered that conn in
        // `conns` (the common startup window — a path drops right after `ready`, and the viability collector
        // runs before the connectionOpened loop), the arm is skipped; nothing re-runs reconciliation when
        // `conns` catches up, so the grace timer never arms and the dead peer lingers Woven forever (the
        // #1478 zombie — no `connectionClosed` fires for a path-lost `waiting` conn). The fix re-reconciles
        // the latest viability whenever a conn first enters `conns`.
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0), wovenPathGrace = grace)
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1), wovenPathGrace = grace)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()

        val live = NwConnectionId("conn-dev-0-0") // A's first-dial handle, allocated below
        // 1) Path loss lands BEFORE the connection is tracked: `conns` is still empty here, so the seam's
        //    viability collector arm-skips it (connId not in conns) — the lost-wakeup window.
        apiA.emitConnectionViability(live, viable = false)
        testScheduler.runCurrent()
        // 2) Now `conns` catches up: A dials B → connectionOpened(conn-dev-0-0) registers the conn and the
        //    NwHello exchange weaves A→B. On registration the seam must re-reconcile the LATEST viability
        //    (still {conn-dev-0-0=false}, unchanged so the StateFlow never re-emits it) and arm the timer.
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")
        // 3) Advance past the grace: with the pending loss reconciled, the timer fires and evicts the peer.
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds + 1)
        assertTrue(pumpUntil { seamA.peers.value == setOf(seamA.selfId) }, "grace armed on registration → A evicted B")

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A} — the pre-registration loss was not lost") },
            // #1513: the eviction re-forms to Weaving (recoverable), not Torn — the anti-zombie point holds
            // either way (no lingering Woven peer), the terminal state just became recoverable.
            { assertTrue(seamA.state.value is SeamState.Weaving, "A re-forms to Weaving — no zombie Woven peer, and recoverable") },
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

    @Test
    fun bufferedFrameOnATombstonedConnDoesNotResurrectItOrRegisterAPhantomPeer() = runTest(StandardTestDispatcher()) {
        // #1528 part A (tombstone). A connId that was removed from `conns` (here: a self-connection the
        // guard drops) must NOT be resurrected by a late/buffered frame that arrives on it afterwards.
        // Pre-fix, getOrCreateConn re-creates a fresh ConnState (resolvedPeerId=null), so the late DATA
        // frame is misparsed as an NwHello and a PHANTOM peer is registered. Post-fix the connId is
        // tombstoned and the frame is dropped. A genuinely-new conn is unaffected (#1509 non-regression),
        // proven by the still-live peer-1 link below.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-0")
        val seamA = NwSeam(self, apiA, seamScope(), Random(0))
        val received = mutableListOf<Swatch>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { received += it } }
        testScheduler.runCurrent()

        // A genuinely-new inbound conn resolves to a REAL remote peer-1 — the #1509 path that MUST keep working.
        val live = NwConnectionId("c-live")
        apiA.emitConnectionOpened(NwConnectionOpened(live, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(
            NwBytesReceived(live, encodeFrame(NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))),
        )
        assertTrue(pumpUntil { PeerId("peer-1") in seamA.peers.value }, "peer-1 resolved on the live conn")

        // A self-connection: its remote resolves to selfId, so the guard removes it from `conns` (and, once
        // fixed, tombstones it).
        val selfConn = NwConnectionId("c-self")
        apiA.emitConnectionOpened(NwConnectionOpened(selfConn, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(
            NwBytesReceived(selfConn, encodeFrame(NwHello.encode(self, ByteArray(NONCE_BYTES) { 2 }))),
        )
        testScheduler.runCurrent()
        assertEquals(setOf(self, PeerId("peer-1")), seamA.peers.value, "self never joins the roster (pre-condition)")

        // Late, buffered DATA frame arrives on the just-removed self-conn — pre-fix this resurrects it and is
        // misparsed as an NwHello for a phantom peer.
        apiA.emitBytesReceived(
            NwBytesReceived(selfConn, encodeFrame(NwHello.encode(PeerId("phantom-peer"), ByteArray(NONCE_BYTES) { 3 }))),
        )
        testScheduler.runCurrent()

        // The receive loop is still healthy: a later legit frame on the live conn is still delivered.
        apiA.emitBytesReceived(NwBytesReceived(live, encodeFrame("still-alive".encodeToByteArray())))
        pumpUntil { received.any { it.decodeToString() == "still-alive" } }

        assertAll(
            { assertEquals(setOf(self, PeerId("peer-1")), seamA.peers.value, "no phantom peer from the resurrected tombstoned conn") },
            { assertTrue(received.any { it.decodeToString() == "still-alive" }, "receive loop still delivers on the live conn") },
        )
    }

    @Test
    fun anUndecodableFirstFrameDoesNotKillTheReceiveLoop() = runTest(StandardTestDispatcher()) {
        // #1528 part B (backstop). Even if a tombstone is missed, a first frame on an unresolved conn that
        // fails NwHello.decode (garbage idLen, rejected by its own bounds check) must NOT propagate out of the collector and
        // kill bytesReceivedLoop (leaving the seam permanently DEAF yet non-Torn). Pre-fix the throw escapes
        // and kills the loop; post-fix the decode failure disconnects that conn and the loop keeps running —
        // proven by a subsequent legit frame delivered on a DIFFERENT live conn.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-0")
        val seamA = NwSeam(self, apiA, seamScope(), Random(0))
        val received = mutableListOf<Swatch>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { received += it } }
        testScheduler.runCurrent()

        // A live conn resolved to peer-1.
        val live = NwConnectionId("c-live")
        apiA.emitConnectionOpened(NwConnectionOpened(live, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(
            NwBytesReceived(live, encodeFrame(NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))),
        )
        assertTrue(pumpUntil { PeerId("peer-1") in seamA.peers.value }, "peer-1 resolved on the live conn")

        // A genuinely-new conn whose FIRST frame is undecodable as an NwHello (idLen = 0x7fffffff — a declared
        // length no buffer can satisfy, and one that overflows `4 + idLen`; see NwHello.decode).
        val bad = NwConnectionId("c-bad")
        apiA.emitConnectionOpened(NwConnectionOpened(bad, endpoint = null))
        testScheduler.runCurrent()
        val garbage = encodeFrame(byteArrayOf(0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        apiA.emitBytesReceived(NwBytesReceived(bad, garbage))
        testScheduler.runCurrent()

        // The loop survived: a later legit frame on the live conn is still delivered.
        apiA.emitBytesReceived(NwBytesReceived(live, encodeFrame("still-alive".encodeToByteArray())))
        pumpUntil { received.any { it.decodeToString() == "still-alive" } }

        assertAll(
            { assertTrue(received.any { it.decodeToString() == "still-alive" }, "receive loop survived the undecodable frame and still delivers") },
            { assertTrue(PeerId("peer-1") in seamA.peers.value, "peer-1 stays resolved") },
            { assertTrue(seamA.state.value !is SeamState.Torn, "seam is not torn by the decode failure") },
        )
    }

    @Test
    fun aFramerLevelBadLengthPrefixOnALiveConnDoesNotKillTheReceiveLoop() = runTest(StandardTestDispatcher()) {
        // #1528 finding 1 (framer-throw backstop). bytesReceivedLoop calls cs.framer.decode(bytes) UNGUARDED,
        // and NwFramer.decode throws FrameTooLargeException on a bad 4-byte length prefix (negative or
        // > maxFrameSize). A corrupt/hostile chunk on ANY live connection therefore escapes the collector and
        // kills the receive loop — the exact deaf-seam symptom #1528 targets. Post-fix the framer throw routes
        // through the same evict+tombstone+disconnect backstop as a hello-decode throw; the loop survives and
        // keeps delivering on OTHER live conns. Fed as RAW bytes (NOT via encodeFrame) so the length prefix is
        // the attacker-controlled garbage.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-0")
        val seamA = NwSeam(self, apiA, seamScope(), Random(0))
        val received = mutableListOf<Swatch>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { received += it } }
        testScheduler.runCurrent()

        // Two live resolved conns — the corrupt chunk lands on one; delivery must survive on the other.
        val c1 = NwConnectionId("c-1")
        apiA.emitConnectionOpened(NwConnectionOpened(c1, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(NwBytesReceived(c1, encodeFrame(NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))))
        val c2 = NwConnectionId("c-2")
        apiA.emitConnectionOpened(NwConnectionOpened(c2, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(NwBytesReceived(c2, encodeFrame(NwHello.encode(PeerId("peer-2"), ByteArray(NONCE_BYTES) { 2 }))))
        assertTrue(
            pumpUntil { setOf(PeerId("peer-1"), PeerId("peer-2")).all { it in seamA.peers.value } },
            "both peers resolved",
        )

        // Corrupt RAW chunk on the LIVE peer-1 conn: a 4-byte length prefix of 0x7fffffff → NwFramer throws.
        apiA.emitBytesReceived(NwBytesReceived(c1, byteArrayOf(0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        testScheduler.runCurrent()

        // The loop survived: a later legit frame on the OTHER live conn (peer-2) is still delivered.
        apiA.emitBytesReceived(NwBytesReceived(c2, encodeFrame("still-alive".encodeToByteArray())))
        pumpUntil { received.any { it.decodeToString() == "still-alive" } }

        assertAll(
            { assertTrue(received.any { it.decodeToString() == "still-alive" }, "receive loop survived the framer throw and still delivers on another conn") },
            { assertTrue(PeerId("peer-2") in seamA.peers.value, "peer-2 stays resolved") },
            { assertTrue(seamA.state.value !is SeamState.Torn, "seam is not torn by the framer throw") },
        )
    }

    @Test
    fun aHelloOnATombstonedButReTrackedConnIsNotResolvedIntoAZombiePeer() = runTest(StandardTestDispatcher()) {
        // #1528 finding 2 (stale-cs classify guard). getOrCreateConnForBytes and processFrame are TWO lock
        // acquisitions; under a MULTI-threaded dispatcher a removal path can run between them, tombstoning the
        // connId, after which processFrame would resolveIdentity on a dead conn and register
        // registry[peer] = Winner(deadConnId) — an UNEVICTABLE zombie (every eviction path bails "unknown-conn"
        // because conns[connId] is gone). That exact interleave is not deterministically reproducible under a
        // single-threaded StandardTestDispatcher (no suspension splits the two acquisitions), so this drives the
        // SAME guarded classify path deterministically: a connId is closed (removed + tombstoned), then re-tracked
        // by connectionOpenedLoop's tombstone-UNAWARE getOrCreateConn (so conns[connId] is live AND the connId is
        // still tombstoned), then a hello arrives. processFrame must DROP it — the tombstone means the conn is
        // dead — and register NO peer. Pre-fix (no guard) it resolves a phantom peer.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-0")
        val seamA = NwSeam(self, apiA, seamScope(), Random(0))
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        testScheduler.runCurrent()

        val conn = NwConnectionId("c-zombie")
        // 1) The conn opens then closes: connectionClosedLoop removes it from `conns` AND tombstones it.
        apiA.emitConnectionOpened(NwConnectionOpened(conn, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitConnectionClosed(NwConnectionClosed(conn, reason = null))
        testScheduler.runCurrent()
        // 2) The same connId is re-tracked by the opened loop (tombstone-unaware): `conns[conn]` is live again,
        //    yet the connId remains tombstoned — the deterministic stand-in for the stale-cs interleave window.
        apiA.emitConnectionOpened(NwConnectionOpened(conn, endpoint = null))
        testScheduler.runCurrent()
        // 3) A hello arrives on that tombstoned-but-tracked conn. The classify guard must DROP it (dead conn),
        //    resolving NO identity — pre-fix it registers a phantom "peer-1" zombie.
        apiA.emitBytesReceived(NwBytesReceived(conn, encodeFrame(NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))))
        testScheduler.runCurrent()
        pumpUntil(maxPumps = 50) { false } // let any (wrong) resolution surface

        assertAll(
            { assertEquals(setOf(self), seamA.peers.value, "no zombie peer resolved on the tombstoned conn") },
            { assertTrue(seamA.state.value is SeamState.Weaving, "seam stayed Weaving — the dead conn never wove a peer") },
        )
    }

    // ── #1522: closed connections as reconciled MONOTONE STATE ──────────────────────────────────

    @Test
    fun droppedCloseEventStillEvictsPeerViaClosedState() = runTest(StandardTestDispatcher()) {
        // #1522 HEADLINE. The connectionClosed EVENT is a lossy tryEmit; a dropped `failed`/`cancelled`
        // close used to strand a permanent zombie peer (its viability key already cleared, so no grace timer
        // ever arms — nothing evicts it). Model closure as drop-tolerant MONOTONE STATE (closedConnections):
        // even with A's close EVENT dropped, B's departure marks the STATE, and A's fifth collector reconciles
        // it — evicting B, re-forming to Weaving, and dropping B's endpoint from settledEndpoints.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0))
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1))
        var aIncomingCompleted = false
        val aCollect = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            seamA.incoming.collect { }
            aIncomingCompleted = true
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        // Double-dial so A learns B's endpoint (settledEndpoints must contain it pre-departure).
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        apiB.connect(NwEndpoint(id = "ep-dev-0", serviceName = "svc-0"))
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")
        assertTrue("ep-dev-1" in seamA.settledEndpoints.value, "A's settledEndpoints holds B's endpoint pre-departure")

        // DROP every close EVENT A would observe — the lossy-buffer scenario the fix must survive.
        apiA.dropCloseEvents = true
        // B departs: closing B disconnects B's link; the radio marks the close STATE on A (both sides) but the
        // close EVENT to A is swallowed. Pre-fix A never evicts B (permanent zombie); post-fix the STATE evicts.
        seamB.close()
        assertTrue(
            pumpUntil { seamA.peers.value == setOf(seamA.selfId) },
            "A evicted B via the connectionStates Closed STATE despite the dropped close EVENT (pre-fix: permanent zombie)",
        )
        pumpUntil(maxPumps = 50) { false } // let any wrong Torn / incoming completion surface

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "A's peers collapse to {A}") },
            { assertTrue(seamA.state.value is SeamState.Weaving, "A re-forms to Weaving (recoverable), NOT Torn — was ${seamA.state.value}") },
            { assertFalse("ep-dev-1" in seamA.settledEndpoints.value, "B's endpoint left settledEndpoints so NwLoom redials it") },
            { assertTrue(!aIncomingCompleted && !aCollect.isCompleted, "A.incoming stays OPEN — a reforming seam does not complete incoming") },
        )
    }

    @Test
    fun closedStateObservedBeforeConnTrackedIsReconciledOnRegistration() = runTest(StandardTestDispatcher()) {
        // #1522 lost-wakeup analog (mirrors the #1509 viability lost-wakeup). closedConnections is latest-value
        // STATE; if a close is marked BEFORE the seam tracks the conn, reconcileClosed's `it in conns` filter
        // no-ops it, and the StateFlow won't re-emit an unchanged value. The registration-time re-reconcile
        // (both the opened and bytes loops) catches it: the moment the conn enters `conns`, the pending closure
        // tears + tombstones it, so a late NwHello on it never resolves a phantom peer.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-0")
        val seamA = NwSeam(self, apiA, seamScope(), Random(0))
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        testScheduler.runCurrent()

        val conn = NwConnectionId("c-early-close")
        // 1) Close STATE lands before the conn is tracked: `conns` empty, reconcile is a filtered no-op.
        apiA.markConnectionClosed(conn, reason = "failed")
        testScheduler.runCurrent()
        // 2) Conn opens → the registration-time re-reconcile removes + tombstones it on the spot.
        apiA.emitConnectionOpened(NwConnectionOpened(conn, endpoint = null))
        testScheduler.runCurrent()
        // 3) A late NwHello on that conn must be DROPPED (tombstoned) — no phantom peer resolves.
        apiA.emitBytesReceived(NwBytesReceived(conn, encodeFrame(NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))))
        testScheduler.runCurrent()
        pumpUntil(maxPumps = 50) { false }

        assertAll(
            { assertEquals(setOf(self), seamA.peers.value, "the pre-registration closed conn never resolves a peer") },
            { assertTrue(seamA.state.value is SeamState.Weaving, "seam stays Weaving — no zombie from the early close") },
        )
    }

    @Test
    fun eventAndStateBothFiringEvictsExactlyOnce() = runTest(StandardTestDispatcher()) {
        // #1522 double-fire safety. In the normal case BOTH signals fire (close EVENT + close STATE). Whichever
        // runs first removes + tombstones the conn; the second sees `cs == null` → unknown-conn no-op. Assert a
        // single clean eviction (peers → {A}, one Woven→Weaving reform, incoming still open) — no double-tear,
        // no Weaving flap, no spurious re-eviction.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0))
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1))
        var aIncomingCompleted = false
        val aCollect = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            seamA.incoming.collect { }
            aIncomingCompleted = true
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1"))
        apiB.connect(NwEndpoint(id = "ep-dev-0", serviceName = "svc-0"))
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")

        // Normal departure: both the close EVENT (dropCloseEvents == false) and the close STATE fire on A.
        seamB.close()
        assertTrue(pumpUntil { seamA.peers.value == setOf(seamA.selfId) }, "A evicted B")
        pumpUntil(maxPumps = 50) { false } // let any double-tear / Weaving flap surface

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "B stays evicted exactly once — no re-eviction flap") },
            { assertTrue(seamA.state.value is SeamState.Weaving, "A re-formed to Weaving once, NOT Torn — was ${seamA.state.value}") },
            { assertTrue(!aIncomingCompleted && !aCollect.isCompleted, "A.incoming stays OPEN — no spurious tear from the second signal") },
        )
    }

    @Test
    fun closedStateForDedupLoserDoesNotEvictSurvivor() = runTest(StandardTestDispatcher()) {
        // #1522 identity guard (preserved through removeByConn). A stale/loser connection's closed-state must
        // NOT evict the peer whose LIVE connection is a different connId. Construct it deterministically:
        // peer-1 resolves on conn1; conn1 closes (peer evicted, conn1 tombstoned); peer-1 re-resolves on a
        // fresh conn2 (the live link). A late closed-state for the defunct conn1 must be a no-op — conn2's
        // peer-1 stays. (conn1 is not in `conns`, so reconcileClosed filters it; and even reaching removeByConn
        // its conn-identity guard would refuse to evict the survivor.)
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-0")
        val seamA = NwSeam(self, apiA, seamScope(), Random(0))
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        testScheduler.runCurrent()

        val conn1 = NwConnectionId("c-1")
        apiA.emitConnectionOpened(NwConnectionOpened(conn1, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(NwBytesReceived(conn1, encodeFrame(NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))))
        assertTrue(pumpUntil { PeerId("peer-1") in seamA.peers.value }, "peer-1 resolved on conn1")

        // conn1 closes (both signals) → peer-1 evicted, conn1 tombstoned.
        apiA.markConnectionClosed(conn1, reason = null)
        apiA.emitConnectionClosed(NwConnectionClosed(conn1, reason = null))
        assertTrue(pumpUntil { PeerId("peer-1") !in seamA.peers.value }, "peer-1 evicted on conn1 close")

        // peer-1 re-resolves on a fresh conn2 — the LIVE link now.
        val conn2 = NwConnectionId("c-2")
        apiA.emitConnectionOpened(NwConnectionOpened(conn2, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(NwBytesReceived(conn2, encodeFrame(NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 2 }))))
        assertTrue(pumpUntil { PeerId("peer-1") in seamA.peers.value }, "peer-1 re-resolved on conn2")

        // A STALE closed-state for the defunct conn1 must NOT evict peer-1 (whose live conn is conn2).
        apiA.markConnectionClosed(conn1, reason = "late")
        testScheduler.runCurrent()
        pumpUntil(maxPumps = 50) { false }

        assertAll(
            { assertTrue(PeerId("peer-1") in seamA.peers.value, "survivor peer-1 stays — the stale conn1 closed-state did not evict it") },
            { assertTrue(seamA.state.value is SeamState.Woven, "seam stays Woven on the live conn2") },
        )
    }

    @Test
    fun dialingConnAbsentFromBothMapsIsNeverEvicted() = runTest(StandardTestDispatcher()) {
        // #1522 no-absence-inference invariant. The seam acts ONLY on a POSITIVE closure marker (a key's
        // presence in closedConnections) — never on a key being ABSENT. A live conn absent from both
        // closedConnections and connectionViability must never be evicted, even as time passes.
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

        // Nobody closed; advance well past any conceivable grace window.
        testScheduler.advanceTimeBy(60.seconds.inWholeMilliseconds)
        testScheduler.runCurrent()
        pumpUntil(maxPumps = 50) { false }

        // B's live link carries NO positive closure marker (the map holds only the dedup-loser's defunct
        // connId, if any). B is retained purely because a conn's ABSENCE from closedConnections is never
        // inferred as closure — were absence read as closure, B would have been evicted here.
        assertAll(
            { assertEquals(setOf(seamA.selfId, PeerId("peer-1")), seamA.peers.value, "B stays — absence from connectionStates is never read as closure") },
            { assertTrue(seamA.state.value is SeamState.Woven, "A stays Woven — no eviction from an absent marker") },
        )
    }

    @Test
    fun closedStateCancelsArmedGraceTimerAndTearsImmediately() = runTest(StandardTestDispatcher()) {
        // #1522 × #1478. A path loss (viable=false) arms a grace timer. If a terminal closed-state then lands,
        // the seam must tear IMMEDIATELY (closure is terminal — no grace) and cancel the armed timer, so no
        // later spurious grace fire re-touches state.
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "svc-1")
        val seamA = NwSeam(PeerId("peer-0"), apiA, seamScope(), Random(0), wovenPathGrace = grace)
        val seamB = NwSeam(PeerId("peer-1"), apiB, seamScope(), Random(1), wovenPathGrace = grace)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamA.incoming.collect { } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seamB.incoming.collect { } }
        testScheduler.runCurrent()
        apiA.connect(NwEndpoint(id = "ep-dev-1", serviceName = "svc-1")) // A's live link = conn-dev-0-0
        assertTrue(pumpUntil { seamA.peers.value.size == 2 }, "A wove to 2 peers")

        val live = NwConnectionId("conn-dev-0-0")
        // Path lost → grace armed. Advance PARTWAY, not to expiry.
        apiA.emitConnectionViability(live, viable = false)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds / 2)
        testScheduler.runCurrent()
        assertEquals(setOf(seamA.selfId, PeerId("peer-1")), seamA.peers.value, "B still present mid-grace")

        // A terminal closed-state lands: tear IMMEDIATELY, cancelling the armed grace timer.
        apiA.markConnectionClosed(live, reason = "failed")
        assertTrue(pumpUntil { seamA.peers.value == setOf(seamA.selfId) }, "closed-state evicts B immediately, before grace would expire")
        // Advance past where the (now-cancelled) grace timer would have fired: no spurious re-fire.
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds)
        testScheduler.runCurrent()
        pumpUntil(maxPumps = 50) { false }

        assertAll(
            { assertEquals(setOf(seamA.selfId), seamA.peers.value, "B stays evicted; the cancelled grace timer never re-fires") },
            { assertTrue(seamA.state.value is SeamState.Weaving, "A re-formed to Weaving on the immediate tear") },
        )
    }

    @Test
    fun closedRetentionCapPrunesOldest() = runTest(StandardTestDispatcher()) {
        // #1522 FIFO retention bound (FakeNwApi level, mirroring RealNwApi/BridgeNwApi). The monotone map
        // retains only the newest CAP closures; the oldest is pruned so a long-lived churny fabric can't grow
        // it without bound. (An in-flight close reconciles within milliseconds of its mark, so a modest cap is
        // ample.)
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val cap = FakeNwApi.CLOSED_RETENTION_CAP
        val oldest = NwConnectionId("closed-0")
        api.markConnectionClosed(oldest, reason = null)
        for (i in 1..cap) api.markConnectionClosed(NwConnectionId("closed-$i"), reason = null)

        assertAll(
            { assertEquals(cap, api.connectionStates.value.size, "map retains exactly CAP entries") },
            { assertFalse(oldest in api.connectionStates.value, "the oldest entry was pruned past the cap") },
            { assertTrue(NwConnectionId("closed-$cap") in api.connectionStates.value, "the newest entry is retained") },
        )
    }

    // ── #1415: a slow local consumer must NOT wedge OTHER connections' handshakes ────────────────

    @Test
    fun aSlowConsumerDoesNotBlockAnotherConnectionsHandshake() = runTest(StandardTestDispatcher()) {
        // #1415 HEADLINE. The demux/receive loop is SINGLE and shared across every connection. Pre-fix it
        // called the (SUSPEND-under-Reliable) Spool.deliver INLINE, so the moment one peer's spool filled and
        // its consumer stalled, the loop wedged INSIDE deliver — starving reads for ALL connections, including
        // the identity handshake (NwHello) of a newly-arriving peer. Post-fix the loop hands data frames to a
        // bounded staging channel drained by a dedicated coroutine, so a slow consumer backs up its own
        // delivery WITHOUT blocking the loop — a later connection's handshake still resolves.
        //
        // Capacity-1 Reliable spool with NO incoming collector: after ONE delivered frame the spool is full and
        // deliver() suspends on the next. Pre-fix that suspends the demux loop; the late peer's NwHello (behind
        // the stuck data frame in the single loop) is never processed and peer-2 never resolves — pumpUntil
        // returns false and the assertion fails. Post-fix the staging channel absorbs the stuck frames and the
        // late handshake resolves.
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val self = PeerId("peer-0")
        // capacity=1 so the spool fills after a single undrained frame — makes the stall deterministic.
        val seam = NwSeam(self, apiA, seamScope(), Random(0), DeliveryPolicy(capacity = 1))
        // Deliberately DO NOT collect incoming: the spool never drains, so it wedges at capacity.
        testScheduler.runCurrent()

        // A slow source (peer-1) resolves on c-src; its data will fill and then over-fill the spool.
        val src = NwConnectionId("c-src")
        apiA.emitConnectionOpened(NwConnectionOpened(src, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(NwBytesReceived(src, encodeFrame(NwHello.encode(PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))))
        assertTrue(pumpUntil { PeerId("peer-1") in seam.peers.value }, "peer-1 (slow source) resolved")

        // Flood c-src with data frames. The first fills the 1-slot spool; the rest have nowhere to go — pre-fix
        // they suspend the demux loop inside deliver(). A handful (well under the staging depth) so post-fix the
        // loop keeps flowing.
        repeat(4) { i -> apiA.emitBytesReceived(NwBytesReceived(src, encodeFrame("data-$i".encodeToByteArray()))) }
        testScheduler.runCurrent()

        // A NEW peer (peer-2) arrives and sends its NwHello AFTER the slow source's flood. In the single demux
        // loop this handshake sits behind the stuck data frames — it can only resolve if delivery is decoupled
        // from the loop.
        val late = NwConnectionId("c-late")
        apiA.emitConnectionOpened(NwConnectionOpened(late, endpoint = null))
        testScheduler.runCurrent()
        apiA.emitBytesReceived(NwBytesReceived(late, encodeFrame(NwHello.encode(PeerId("peer-2"), ByteArray(NONCE_BYTES) { 2 }))))

        assertAll(
            {
                assertTrue(
                    pumpUntil { PeerId("peer-2") in seam.peers.value },
                    "peer-2's handshake resolved despite peer-1's full/stalled spool — the receive loop was not wedged",
                )
            },
            { assertTrue(PeerId("peer-1") in seam.peers.value, "peer-1 stays resolved") },
            { assertTrue(seam.state.value is SeamState.Woven, "seam is Woven with both remotes") },
        )
    }

    // ── #1566: reconcileStates reads the FRESHEST connectionStates under the lock, not a stale snapshot ──

    /**
     * #1566 anti-spurious-tear. The lost-wakeup catch-up guards (and the collector) used to reconcile from a
     * snapshot of `connectionStates` captured on the caller's thread; under a multi-threaded dispatcher that
     * snapshot could be STALE relative to the value the states collector had already processed, and because a
     * [StateFlow] never re-emits an unchanged value the stale outcome was FINAL. Here the freshest truth is
     * `{conn-Y: Viable}` (Y is healthy) while a STALE `{conn-Y: PathLost}` reaches the reconcile. Pre-fix the
     * reconcile trusts the stale `PathLost` and arms a grace timer that tears a HEALTHY Y after the grace;
     * post-fix it re-reads the fresh `Viable` under the lock and arms nothing, so Y is never torn.
     *
     * The staleness is forced deterministically (no real threads) by a [ControllableStates] whose collector
     * emission and `.value` are decoupled: the collector receives the stale map while `.value` returns the
     * fresh one — exactly the divergence the thread race produced. RED against the parameter-trusting version.
     */
    @Test
    fun staleCatchUpSnapshotDoesNotTearAHealthyPeer_reconcileReadsFreshValue() = runTest(StandardTestDispatcher()) {
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val fake = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val states = ControllableStates()
        val api = DivergentStatesApi(fake, states)
        val self = PeerId("peer-self")
        val seam = NwSeam(self, api, seamScope(), Random(0), wovenPathGrace = grace)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seam.incoming.collect { } }
        testScheduler.runCurrent()

        val (connY, peerY) = weaveRemote(seam, fake, "conn-Y", "peer-Y")

        // Freshest truth: Y's path is Viable (healthy). The reconcile the collector is about to run receives a
        // STALE {conn-Y: PathLost} while `.value` already holds {conn-Y: Viable}.
        states.diverge(
            fresh = mapOf(connY to NwConnState.Viable),
            staleEmission = mapOf(connY to NwConnState.PathLost),
        )
        testScheduler.runCurrent()
        // Advance well past the grace: a spuriously-armed timer would fire and tear Y here.
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds * 2)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(setOf(self, peerY), seam.peers.value, "healthy Y stays — fresh Viable won over the stale PathLost snapshot (#1566)") },
            { assertTrue(seam.state.value is SeamState.Woven, "seam stays Woven — no spurious tear from a stale catch-up snapshot") },
        )
    }

    /**
     * #1566 anti-zombie (the worse half). Y is legitimately [NwConnState.PathLost] with its grace timer armed;
     * a STALE `{conn-Y: Viable}` then reaches the reconcile while the freshest truth is still `PathLost`.
     * Pre-fix the reconcile trusts the stale `Viable` and CANCELS the armed timer — and since a path-lost
     * `waiting` connection fires no `connectionClosed`, and a [StateFlow] never re-emits an unchanged value, Y
     * is NEVER re-armed and NEVER torn: a permanent zombie peer (the #1478 mechanism exists to kill exactly
     * this). Post-fix the reconcile re-reads the fresh `PathLost` under the lock, leaves the timer armed, and Y
     * tears on expiry (last-remote loss → re-form to Weaving, #1513). RED against the parameter-trusting version.
     */
    @Test
    fun staleCatchUpSnapshotDoesNotCancelALegitimatelyArmedTimer_reconcileReadsFreshValue() = runTest(StandardTestDispatcher()) {
        val grace = 10.seconds
        val radio = FakeNwRadio()
        val fake = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val states = ControllableStates()
        val api = DivergentStatesApi(fake, states)
        val self = PeerId("peer-self")
        val seam = NwSeam(self, api, seamScope(), Random(0), wovenPathGrace = grace)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seam.incoming.collect { } }
        testScheduler.runCurrent()

        val (connY, _) = weaveRemote(seam, fake, "conn-Y", "peer-Y")

        // A real path loss on Y arms the grace timer (value and emission agree).
        states.emit(mapOf(connY to NwConnState.PathLost))
        testScheduler.runCurrent()
        // Freshest truth stays PathLost, but a STALE {conn-Y: Viable} reaches the reconcile.
        states.diverge(
            fresh = mapOf(connY to NwConnState.PathLost),
            staleEmission = mapOf(connY to NwConnState.Viable),
        )
        testScheduler.runCurrent()
        // Advance past the grace: the armed timer must still fire (fixed) — pre-fix it was cancelled (zombie).
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds + 1)
        val torn = pumpUntil { seam.peers.value == setOf(self) }

        assertAll(
            { assertTrue(torn, "the armed timer survived the stale Viable and tore Y — no zombie (#1566)") },
            { assertEquals(setOf(self), seam.peers.value, "Y evicted — the stale Viable did not cancel the legitimately-armed timer") },
            { assertTrue(seam.state.value is SeamState.Weaving, "last-remote loss re-forms to Weaving (#1513), not a zombie Woven peer") },
        )
    }

    /** Weave [remote] into [seam] over a bare [fake] (connectionOpened + an NwHello frame), returning its handles. */
    private suspend fun TestScope.weaveRemote(
        seam: NwSeam,
        fake: FakeNwApi,
        connIdValue: String,
        remoteValue: String,
    ): Pair<NwConnectionId, PeerId> {
        val connId = NwConnectionId(connIdValue)
        val remote = PeerId(remoteValue)
        fake.emitConnectionOpened(NwConnectionOpened(connId, endpoint = null))
        testScheduler.runCurrent()
        fake.emitBytesReceived(NwBytesReceived(connId, encodeFrame(NwHello.encode(remote, ByteArray(NONCE_BYTES) { 7 }))))
        assertTrue(pumpUntil { remote in seam.peers.value }, "$remote woven on $connId")
        return connId to remote
    }

    /**
     * [NwApi] that delegates everything to a real [FakeNwApi] EXCEPT [connectionStates], which it serves from a
     * [ControllableStates] whose collector emission and `.value` can be deliberately DIVERGED — the deterministic
     * (no-real-thread) stand-in for the #1566 thread race, where a stale caller-captured snapshot reached the
     * reconcile while `.value` had already advanced.
     */
    private class DivergentStatesApi(
        private val delegate: FakeNwApi,
        private val states: ControllableStates,
    ) : NwApi by delegate {
        override val connectionStates: StateFlow<Map<NwConnectionId, NwConnState>> get() = states
    }

    /**
     * A [StateFlow] whose collector emission and `.value` are decoupled so a test can hand the reconcile a STALE
     * snapshot while `.value` returns the FRESH one — reproducing the #1566 staleness deterministically under
     * virtual time. [emit] keeps them in agreement (a normal update); [diverge] sets `.value` to `fresh` while
     * the collector receives `staleEmission`.
     */
    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class) // hand-rolled StateFlow: the #1566 divergence vehicle
    private class ControllableStates : StateFlow<Map<NwConnectionId, NwConnState>> {
        private val current = MutableStateFlow<Map<NwConnectionId, NwConnState>>(emptyMap())
        private val feed = MutableSharedFlow<Map<NwConnectionId, NwConnState>>(replay = 1, extraBufferCapacity = 64)

        override val value: Map<NwConnectionId, NwConnState> get() = current.value
        override val replayCache: List<Map<NwConnectionId, NwConnState>> get() = listOf(current.value)

        override suspend fun collect(collector: FlowCollector<Map<NwConnectionId, NwConnState>>): Nothing =
            feed.collect(collector)

        /** Normal update: `.value` and the collector emission agree. */
        fun emit(map: Map<NwConnectionId, NwConnState>) {
            current.value = map
            feed.tryEmit(map)
        }

        /** #1566 exploit: the collector receives [staleEmission] while `.value` returns [fresh]. */
        fun diverge(fresh: Map<NwConnectionId, NwConnState>, staleEmission: Map<NwConnectionId, NwConnState>) {
            current.value = fresh
            feed.tryEmit(staleEmission)
        }
    }
}
