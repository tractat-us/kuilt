package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A frame arrives in pieces, and `NwSeam` reassembles it while the shared demux loop keeps running.**
 *
 * A real `nw_connection_receive` completion carries at most ~64 KiB, so on the wire almost every
 * non-trivial frame reaches the far end as several `bytesReceived` events. [FakeNwRadio] delivered
 * every payload **whole** until #2479, so nothing in this module had ever driven `NwSeam` across a
 * chunk boundary: [NwFramer]'s accumulator was exercised only by `NwFramingTest`, which drives the
 * framer in isolation and knows nothing of the connection it belongs to.
 *
 * What that left untested is the **integration**, and it is where #2134 lived:
 *
 *  1. the per-connection accumulator attributing a reassembled frame to the right sender;
 *  2. that accumulator surviving another connection's `HELLO` and DATA arriving *between two of its
 *     own chunks* on the single shared `bytesReceivedLoop`;
 *  3. a connId tombstoned **mid-frame** — the #1528 hazard with a real partial frame in the buffer,
 *     which whole-payload delivery could not reach at all;
 *  4. a split frame arriving under an armed #2425 ordering hold: held, and released **whole**.
 *
 * ## The knob is a prescription, and its vacuous setting is the easy one
 * [FakeNwRadio.maxChunkBytes] switches chunking OFF for any payload that fits inside it — one
 * emission, no boundary, and a test that goes green having proved nothing. So no test here infers
 * chunking from a successful reassembly. Each one **counts the emissions the receiving device
 * actually saw**, through a collector on that device's own [NwApi.bytesReceived] — an instrument
 * independent of the radio's own bookkeeping — and asserts that count is at least two and is what
 * the budget implies, *before* asserting anything about the frame that came out.
 *
 * [CHUNK] is 8 bytes for the same reason: it is small enough that a 4-byte length prefix and its
 * body land in different receives, which is the boundary that matters. Every larger value moves
 * that boundary later, and a value past a frame's own length removes it entirely.
 *
 * Determinism: injected [StandardTestDispatcher], seeded [Random] per seam, bounded pumping only —
 * never `advanceUntilIdle()`.
 */
class NwChunkedReceiveTest {

    // ── 1. a split frame reassembles, and is attributed to the connection it came in on ──

    /**
     * The base case: one frame, six receives, one [Swatch] — carrying the original bytes and the
     * sender of the link they arrived on.
     *
     * Two peers are attached rather than one, and that is not decoration: with a single peer,
     * "attributed to the right sender" cannot be false, because there is no other identity the seam
     * could have named. The silent spoke is what makes the attribution assertion falsifiable.
     */
    @Test
    fun aFrameSplitAcrossReceivesReassemblesAndIsAttributedToItsSender() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val hub = hub("attrib")
            val alice = hub.attach("dev-alice", "peer-alice")
            val bystander = hub.attach("dev-bystander", "peer-bystander")
            hub.hello(alice, fill = 1)
            hub.hello(bystander, fill = 2)

            val frame = encodeFrame(NwWire.encodeData(SPLIT_PAYLOAD))
            val mark = hub.mark()
            hub.radio.maxChunkBytes = CHUNK
            hub.data(alice, SPLIT_PAYLOAD)
            hub.pumpUntil { hub.received.isNotEmpty() }

            val chunks = hub.chunksOn(alice.hubEnd, since = mark)
            assertAll(
                // ── rig receipts: the split really happened, and happened where it was asked to ──
                {
                    assertEquals(
                        ceilDiv(frame.size, CHUNK),
                        chunks.size,
                        "the ${frame.size}-byte frame must arrive as ${ceilDiv(frame.size, CHUNK)} receives of " +
                            "at most $CHUNK bytes — a count of 1 means the knob did nothing and this test is " +
                            "about whole-payload delivery, which every other test in this module already covers",
                    )
                },
                { assertTrue(chunks.size >= 2, "…and at least two, or there is no boundary to reassemble across") },
                {
                    assertEquals(
                        List(chunks.size - 1) { CHUNK } + (frame.size - CHUNK * (chunks.size - 1)),
                        chunks.map { it.bytes.size },
                        "every receive but the last carries a full budget, and the last the remainder",
                    )
                },
                {
                    assertTrue(
                        chunks.none { it.bytes.size == frame.size },
                        "no single receive carried the whole frame: ${chunks.map { it.bytes.size }}",
                    )
                },
                {
                    // The length prefix is 4 bytes and the first chunk is 8, so the frame's own header is
                    // complete in chunk 0 while its body is not. That is the shape the accumulator exists
                    // for, and it is worth pinning rather than assuming from the budget.
                    assertTrue(
                        chunks[0].bytes.size < frame.size && chunks[0].bytes.size > Int.SIZE_BYTES,
                        "chunk 0 must carry a complete length prefix and an INCOMPLETE body",
                    )
                },

                // ── the outcome ──────────────────────────────────────────────────────────────
                {
                    assertEquals(
                        1,
                        hub.received.size,
                        "six receives are ONE frame — a consumer must never see the pieces: ${hub.saw()}",
                    )
                },
                {
                    // `firstOrNull`, not `single`: a broken accumulator delivers NOTHING, and a `single()`
                    // on an empty list raises a NoSuchElementException that `assertAll` promotes over every
                    // named failure beside it — so the diagnosis becomes "List is empty" instead of the
                    // assertion that says what was expected.
                    assertContentEquals(
                        SPLIT_PAYLOAD,
                        hub.received.firstOrNull()?.toByteArray(),
                        "…and it is byte-for-byte the payload that was written",
                    )
                },
                {
                    assertEquals(
                        alice.peerId,
                        hub.received.firstOrNull()?.sender,
                        "…attributed to the peer whose connection carried it, not to the other resolved peer",
                    )
                },
            )
        }

    // ── 2. the shared demux loop interleaves another connection's HELLO and DATA mid-frame ──

    /**
     * One `bytesReceivedLoop` serves every connection, so a second peer's traffic is demultiplexed
     * **between** the chunks of the first peer's frame — including the `HELLO` that gives that
     * second peer an identity at all.
     *
     * This is the interaction the accumulator has to be per-connection for. A single shared buffer,
     * or one reset on each receive, both survive test 1 above (one connection, no interruption) and
     * both die here.
     *
     * The second peer's link is opened during setup but left **anonymous**: its `HELLO` is injected
     * from inside the gap, so the seam resolves a brand-new identity while it is holding half of
     * somebody else's frame.
     */
    @Test
    fun aSplitFrameInterleavedWithAnotherConnectionsHelloAndDataReassemblesBothInOrder() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val hub = hub("interleave")
            val alice = hub.attach("dev-alice", "peer-alice")
            val bob = hub.attach("dev-bob", "peer-bob")
            hub.hello(alice, fill = 1) // bob stays anonymous until the gap

            val frame = encodeFrame(NwWire.encodeData(SPLIT_PAYLOAD))
            val bobFrames = mutableListOf<String>()
            val mark = hub.mark()
            hub.radio.maxChunkBytes = CHUNK
            hub.radio.betweenChunks = { _, index ->
                if (index == 0) {
                    hub.hello(bob, fill = 2, settle = false)
                } else {
                    val marker = "b-$index"
                    bobFrames += marker
                    hub.data(bob, marker.encodeToByteArray())
                }
            }
            hub.data(alice, SPLIT_PAYLOAD)
            hub.pumpUntil { hub.received.size == bobFrames.size + 1 }

            val tags = hub.chunks.drop(mark).map { if (it.connectionId == alice.hubEnd) "a" else "b" }
            assertAll(
                // ── rig receipts ─────────────────────────────────────────────────────────────
                {
                    assertEquals(
                        ceilDiv(frame.size, CHUNK),
                        tags.count { it == "a" },
                        "alice's frame must still be split into ${ceilDiv(frame.size, CHUNK)} receives",
                    )
                },
                { assertTrue(tags.count { it == "a" } >= 2, "…and split at all") },
                {
                    assertTrue(
                        tags.indexOfFirst { it == "b" } > 0 &&
                            tags.indexOfLast { it == "b" } < tags.indexOfLast { it == "a" },
                        "EVERY one of bob's receives must fall strictly INSIDE alice's frame — that is the " +
                            "interleaving this test is named for, and without it this is test 1 twice: $tags",
                    )
                },
                {
                    assertEquals(
                        ceilDiv(BOB_HELLO_BYTES, CHUNK) + bobFrames.size,
                        tags.count { it == "b" },
                        "…and bob's traffic is his ${ceilDiv(BOB_HELLO_BYTES, CHUNK)}-receive HELLO plus one " +
                            "receive per data frame: $tags",
                    )
                },

                // ── the outcome ──────────────────────────────────────────────────────────────
                {
                    assertEquals(
                        bobFrames + SPLIT_MARKER,
                        hub.saw(),
                        "both streams reassemble, in arrival order: bob's frames complete as they land, " +
                            "alice's on her last chunk — which is after all of them",
                    )
                },
                {
                    assertEquals(
                        List<PeerId?>(bobFrames.size) { bob.peerId } + alice.peerId,
                        hub.senders(),
                        "…each attributed to ITS OWN connection. A shared accumulator would splice the two " +
                            "streams together and attribute the result to whichever chunk closed it",
                    )
                },
                {
                    assertContentEquals(
                        SPLIT_PAYLOAD,
                        hub.received.lastOrNull()?.toByteArray(),
                        "…and alice's payload is intact, with none of bob's bytes spliced into it",
                    )
                },
                {
                    assertEquals(
                        setOf(hub.selfId, alice.peerId, bob.peerId),
                        hub.seam.peers.value,
                        "bob's identity resolved from a HELLO delivered mid-frame — the roster move that " +
                            "used to be impossible to interleave with a partial frame",
                    )
                },
            )
        }

    // ── 3. a connId tombstoned BETWEEN two chunks of one frame ──────────────────────────

    /**
     * **The #1528 hazard, now reachable mid-frame.**
     *
     * A connection is evicted and tombstoned while its accumulator holds half a frame; the tail then
     * arrives on a connId the seam has already buried. It must be dropped — not used to resurrect a
     * [NwSeam.ConnState], and not fed to a fresh [NwFramer] that would read it as a frame in its own
     * right.
     *
     * ## The tail is a well-formed HELLO, deliberately
     * "Do not misparse the tail" is only an assertion if some parse of the tail is *reachable*. So
     * the payload is built so that the bytes after the boundary are, verbatim, an encoded `HELLO`
     * frame for a peer named [PHANTOM] — bytes that are inert inside the DATA frame that contains
     * them, and a roster move the instant a fresh framer is handed them at offset zero. Resurrect
     * the connection and [PHANTOM] joins the seam; drop the tail and nothing happens at all.
     *
     * ## The rig proves the tombstone landed BETWEEN the chunks, not merely during the frame
     * A prelude frame is concatenated ahead of the crafted one and sized so that chunk 0 is exactly
     * `[whole prelude frame][the crafted frame's header and filler]`. The hook then waits until the
     * consumer has been handed the prelude — which is only possible if the demux loop consumed
     * chunk 0 — before tearing the link down. So at the moment of the tombstone the accumulator
     * provably holds a partial frame, rather than that being inferred from the emission order.
     */
    @Test
    fun aConnIdTombstonedBetweenTwoChunksNeitherResurrectsNorMisparsesTheTail() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val hub = hub("tombstone")
            val alice = hub.attach("dev-alice", "peer-alice")
            val control = hub.attach("dev-control", "peer-control")
            hub.hello(alice, fill = 1)
            hub.hello(control, fill = 2)

            val phantomHello = encodeFrame(NwWire.encodeHello(PeerId(PHANTOM), nonce(9)))
            val prelude = encodeFrame(NwWire.encodeData(PRELUDE.encodeToByteArray()))
            // Filler as long as the hello frame, so the boundary falls past it and the split is exactly two.
            val crafted = encodeFrame(NwWire.encodeData(ByteArray(phantomHello.size) { FILLER } + phantomHello))
            val boundary = prelude.size + crafted.size - phantomHello.size

            var sawWhenHookRan: List<String>? = null
            var peersWhenHookRan: Set<PeerId>? = null
            var hookFirings = 0
            val mark = hub.mark()
            hub.radio.maxChunkBytes = boundary
            hub.radio.betweenChunks = { _, _ ->
                hookFirings += 1
                // Chunk 0 has been emitted; wait until the seam has PROCESSED it, which the prelude
                // reaching the consumer is the proof of. Bounded yields, never `advanceUntilIdle`.
                yieldUntil { hub.saw().isNotEmpty() }
                sawWhenHookRan = hub.saw()
                // Evict and tombstone alice's connId with the crafted frame half-delivered.
                hub.radio.disconnect(alice.deviceId, alice.ownEnd)
                yieldUntil { alice.peerId !in hub.seam.peers.value }
                peersWhenHookRan = hub.seam.peers.value
            }
            hub.radio.send(alice.deviceId, alice.ownEnd, prelude + crafted)
            hub.pump()

            // The loop is still serving everybody else — the operational meaning of "dropped, not fatal".
            hub.radio.maxChunkBytes = null
            hub.radio.betweenChunks = null
            hub.data(control, CONTROL.encodeToByteArray())
            hub.pumpUntil { hub.saw().size == 2 }

            val chunks = hub.chunksOn(alice.hubEnd, since = mark)
            assertAll(
                // ── rig receipts ─────────────────────────────────────────────────────────────
                { assertEquals(2, chunks.size, "the crafted frame must arrive as exactly two receives") },
                { assertEquals(1, hookFirings, "…so the gap between them is entered exactly once") },
                {
                    assertContentEquals(
                        phantomHello,
                        chunks[1].bytes,
                        "the tail must be EXACTLY an encoded HELLO frame — otherwise 'does not misparse the " +
                            "tail as a Hello' is a claim about a parse that was never reachable",
                    )
                },
                {
                    assertEquals(
                        listOf(PRELUDE),
                        sawWhenHookRan,
                        "rig check: the seam had consumed chunk 0 (it delivered the prelude riding in it) and " +
                            "was therefore holding a PARTIAL crafted frame when the link was torn",
                    )
                },
                {
                    assertEquals(
                        setOf(hub.selfId, control.peerId),
                        peersWhenHookRan,
                        "rig check: alice really was evicted and tombstoned BETWEEN the two chunks",
                    )
                },

                // ── the outcome ──────────────────────────────────────────────────────────────
                {
                    assertFalse(
                        PeerId(PHANTOM) in hub.seam.peers.value,
                        "the tail must NOT be read as a fresh HELLO on a resurrected connection — that is a " +
                            "phantom peer conjured out of the middle of somebody else's payload",
                    )
                },
                {
                    assertTrue(
                        hub.seam.formationSnapshot().links.none { it.connId == alice.hubEnd.value },
                        "…and no ConnState is resurrected for the buried connId either. This is the STRICTER " +
                            "half: the classify-time tombstone check still drops the frame, so a bytes-loop " +
                            "that resurrects the connection leaks it silently and the roster looks fine: " +
                            "${hub.seam.formationSnapshot().links.map { it.render() }}",
                    )
                },
                {
                    assertEquals(
                        listOf(PRELUDE, CONTROL),
                        hub.saw(),
                        "the crafted payload never reaches the consumer, and the shared loop goes on serving " +
                            "the other connection",
                    )
                },
                { assertTrue(hub.seam.state.value !is SeamState.Torn, "one buried connection is not a torn seam") },
            )
        }

    // ── 4. a split frame under an armed #2425 ordering hold ─────────────────────────────

    /**
     * **A held frame is held as a frame, and released whole.**
     *
     * The #2425 ordering hold buffers frames for a peer whose displaced link is still draining. It
     * operates on decoded frames, so a frame that arrives in six pieces must be held **once**, after
     * the last piece completes it — never partially, never once per receive, and never released
     * before the drain ends.
     *
     * The rig is `NwPublishSwapWindowTest`'s: silence one link of a double dial so the joiner
     * publishes the host on the link the dedup then displaces, and withhold both goodbyes so the
     * drain — and therefore the hold — stays open while the split frame arrives on the winner.
     */
    @Test
    fun aSplitFrameArrivingUnderAnArmedOrderingHoldIsHeldAndReleasedWhole() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val radio = FakeNwRadio()
            val hostDevice = "hold-host"
            val joinerDevice = "hold-joiner"
            val hostApi = FakeNwApi(radio, deviceId = hostDevice, serviceName = "hold-svc-host")
            val joinerApi = FakeNwApi(radio, deviceId = joinerDevice, serviceName = "hold-svc-joiner")
            // The seed pair `NwPublishSwapWindowTest` names KEEPS_OUTBOUND: with the outbound link
            // silenced, both ends resolve on the inbound one and the dedup then displaces it.
            val host = NwSeam(PeerId("hold-peer-host"), hostApi, seamScope(), Random(0L))
            val joiner = NwSeam(PeerId("hold-peer-joiner"), joinerApi, seamScope(), Random(0L))
            val atJoiner = mutableListOf<Swatch>()
            val joinerChunks = mutableListOf<NwBytesReceived>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { host.incoming.collect { } }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { joiner.incoming.collect { atJoiner += it } }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                joinerApi.bytesReceived.collect { joinerChunks += it }
            }
            testScheduler.runCurrent()

            // Silence the outbound link in both directions, so BOTH ends resolve on the inbound one —
            // which `Random(0) to Random(0)` then makes the dedup's loser.
            val dial = radio.injectDoubleDial(hostDevice, joinerDevice)
            radio.holdSends(dial.outbound.dialerConnectionId)
            radio.holdSends(dial.outbound.accepterConnectionId)
            assertTrue(
                pumpUntil { host.peers.value.size == 2 && joiner.peers.value.size == 2 },
                "both seams must publish the peer on the link left speaking: " +
                    "host=${host.peers.value} joiner=${joiner.peers.value}",
            )
            // Withhold both goodbyes so the drain — and the ordering hold with it — stays open.
            radio.holdSends(dial.inbound.dialerConnectionId)
            radio.holdSends(dial.inbound.accepterConnectionId)
            radio.releaseSends(dial.outbound.dialerConnectionId)
            radio.releaseSends(dial.outbound.accepterConnectionId)
            pump()
            val liveDuringDrain = radio.liveLinkCount

            val winnerAtJoiner = checkNotNull(dial.outbound.endOn(joinerDevice))
            val frame = encodeFrame(NwWire.encodeData(SPLIT_PAYLOAD))
            // Mark BEFORE arming the knob: formation put a whole HELLO on this same link, and counting
            // from zero would fold it into the split count.
            val mark = joinerChunks.size
            radio.maxChunkBytes = CHUNK
            host.broadcast(SPLIT_PAYLOAD)
            pump()
            val sawWhileHeld = atJoiner.map { it.decodeToString() }
            val chunksWhileHeld = joinerChunks.drop(mark).count { it.connectionId == winnerAtJoiner }

            // The goodbyes finally cross: the drain ends and the hold flushes.
            radio.releaseSends(dial.inbound.dialerConnectionId)
            radio.releaseSends(dial.inbound.accepterConnectionId)
            pump()

            assertAll(
                // ── rig receipts ─────────────────────────────────────────────────────────────
                {
                    assertEquals(
                        2,
                        liveDuringDrain,
                        "rig check: the displaced link must still be draining, or no hold is armed and this " +
                            "test is about an ordinary delivery",
                    )
                },
                {
                    assertEquals(
                        ceilDiv(frame.size, CHUNK),
                        chunksWhileHeld,
                        "rig check: the held frame must have ARRIVED in ${ceilDiv(frame.size, CHUNK)} pieces " +
                            "on the winner link — a count of 1 makes this a whole-payload hold test",
                    )
                },
                { assertTrue(chunksWhileHeld >= 2, "…and in more than one") },

                // ── the outcome ──────────────────────────────────────────────────────────────
                {
                    assertEquals(
                        emptyList(),
                        sawWhileHeld,
                        "NOTHING is delivered while the hold is armed — not the frame, and above all not a " +
                            "prefix of it: a hold that acted per RECEIVE rather than per FRAME would hand the " +
                            "consumer pieces",
                    )
                },
                {
                    assertEquals(
                        1,
                        atJoiner.size,
                        "…and the release yields ONE frame, not one per chunk: " +
                            "${atJoiner.map { it.payloadSize }}",
                    )
                },
                {
                    assertContentEquals(
                        SPLIT_PAYLOAD,
                        atJoiner.firstOrNull()?.toByteArray(),
                        "…carrying the whole payload, reassembled across the boundary it was held over",
                    )
                },
                {
                    assertEquals(
                        host.selfId,
                        atJoiner.firstOrNull()?.sender,
                        "…attributed to the host, whose link it arrived on",
                    )
                },
                { assertEquals(1, radio.liveLinkCount, "the drain ended and disposed of the loser") },
                { assertEquals(SeamState.Woven, joiner.state.value) },
            )
        }

    // ---------------------------------------------------------------- harness

    /** One bare peer device dialled into the [Hub] — no seam of its own; the test writes its frames. */
    private class Spoke(
        val peerId: PeerId,
        val deviceId: String,
        /** This device's own end of the link — what a test [FakeNwRadio.send]s from. */
        val ownEnd: NwConnectionId,
        /** The hub's end — what the hub's `bytesReceived` events are keyed on. */
        val hubEnd: NwConnectionId,
    )

    /**
     * One real [NwSeam] with N bare devices dialled into it.
     *
     * Only the hub runs a seam: every spoke's frames are written by the test with the production
     * encoders, through the production radio. That is what makes a chunk boundary land exactly where
     * a test asks for it — a second seam would interpose its own sends and its dedup.
     *
     * [chunks] is the rig's instrument: a collector on the hub device's own [NwApi.bytesReceived],
     * independent of anything [FakeNwRadio] reports about itself.
     */
    private class Hub(
        val radio: FakeNwRadio,
        val seam: NwSeam,
        val selfId: PeerId,
        val deviceId: String,
        val received: MutableList<Swatch>,
        val chunks: MutableList<NwBytesReceived>,
        private val scope: TestScope,
    ) {
        /**
         * How many receives this device has seen so far — the mark a test takes before arming
         * [FakeNwRadio.maxChunkBytes], so its emission count is the count the knob caused and not the
         * formation traffic that preceded it. Counting from zero silently folds each spoke's whole
         * `HELLO` into the total and every count assertion is then off by the number of peers.
         */
        fun mark(): Int = chunks.size

        fun chunksOn(connId: NwConnectionId, since: Int = 0): List<NwBytesReceived> =
            chunks.drop(since).filter { it.connectionId == connId }

        fun saw(): List<String> = received.map { it.decodeToString() }

        /** Nullable because [Swatch.sender] is: an unattributed frame must show up as `null`, not as a guess. */
        fun senders(): List<PeerId?> = received.map { it.sender }

        fun pump(times: Int = 40) = repeat(times) { scope.testScheduler.runCurrent() }

        fun pumpUntil(cond: () -> Boolean): Boolean {
            repeat(MAX_PUMPS) {
                if (cond()) return true
                scope.testScheduler.runCurrent()
            }
            return cond()
        }

        /** Dial [deviceId] into this hub and let the connection open. No identity is sent. */
        suspend fun attach(deviceId: String, peerId: String): Spoke {
            // Constructing it registers it on the radio; nothing collects its flows — a spoke has no seam.
            FakeNwApi(radio, deviceId = deviceId, serviceName = deviceId)
            val before = radio.openedLinkCount
            radio.connect(deviceId, NwEndpoint(id = "ep-${this.deviceId}", serviceName = this.deviceId))
            val link = radio.openedLinks[before]
            pump()
            return Spoke(PeerId(peerId), deviceId, link.dialerConnectionId, link.accepterConnectionId)
        }

        /**
         * Write [spoke]'s identity preamble. [settle] pumps until the hub has the peer; it is `false`
         * when the hello is written from inside a [FakeNwRadio.betweenChunks] gap, where pumping the
         * scheduler re-entrantly is not available (and the caller asserts the roster afterwards).
         */
        suspend fun hello(spoke: Spoke, fill: Byte, settle: Boolean = true) {
            radio.send(spoke.deviceId, spoke.ownEnd, encodeFrame(NwWire.encodeHello(spoke.peerId, nonce(fill))))
            if (settle) check(pumpUntil { spoke.peerId in seam.peers.value }) { "${spoke.peerId} never wove" }
        }

        suspend fun data(spoke: Spoke, payload: ByteArray) =
            radio.send(spoke.deviceId, spoke.ownEnd, encodeFrame(NwWire.encodeData(payload)))
    }

    private fun TestScope.hub(tag: String): Hub {
        val radio = FakeNwRadio()
        val deviceId = "$tag-hub"
        val api = FakeNwApi(radio, deviceId = deviceId, serviceName = "$tag-svc")
        val selfId = PeerId("$tag-self")
        val seam = NwSeam(selfId, api, seamScope(), Random(0))
        val received = mutableListOf<Swatch>()
        val chunks = mutableListOf<NwBytesReceived>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { seam.incoming.collect { received += it } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { api.bytesReceived.collect { chunks += it } }
        testScheduler.runCurrent()
        return Hub(radio, seam, selfId, deviceId, received, chunks, this)
    }

    /** A child scope with its OWN Job, so one seam's teardown cannot cancel another's loops. */
    private fun TestScope.seamScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.pump(times: Int = 40) = repeat(times) { testScheduler.runCurrent() }

    private fun TestScope.pumpUntil(cond: () -> Boolean): Boolean {
        repeat(MAX_PUMPS) {
            if (cond()) return true
            testScheduler.runCurrent()
        }
        return cond()
    }

    private companion object {
        const val MAX_PUMPS = 500

        /**
         * The receive budget every chunking test uses, and what it switches OFF.
         *
         * Eight bytes puts the boundary INSIDE every frame here: a frame's 4-byte length prefix
         * completes in chunk 0 while its body does not, which is the state the accumulator exists for.
         * Raise it past a frame's own size and that frame stops being split at all — the vacuous
         * setting — so every test asserts the emission count it produced rather than trusting it.
         */
        const val CHUNK = 8

        /** 40 bytes, so a DATA frame of it is 45 and splits into six receives at [CHUNK]. */
        const val SPLIT_MARKER = "chunked-payload-crossing-many-receives--"
        val SPLIT_PAYLOAD = SPLIT_MARKER.encodeToByteArray()

        /** Bob's `HELLO` frame length: `[len_be32][type][version][idLen_be32][id][nonce]`. */
        const val BOB_HELLO_BYTES =
            Int.SIZE_BYTES + NwWire.TYPE_BYTES + VERSION_BYTES + Int.SIZE_BYTES + 8 /* "peer-bob" */ + NONCE_BYTES

        /** The identity the tombstone test's crafted tail would announce if the tail were ever parsed. */
        const val PHANTOM = "phantom-peer"

        /** Rides in chunk 0 of the tombstone test, so "the seam consumed chunk 0" is observable. */
        const val PRELUDE = "prelude-in-chunk-0"
        const val CONTROL = "control-link-still-works"
        const val FILLER: Byte = 0x7A

        fun nonce(fill: Byte) = ByteArray(NONCE_BYTES) { fill }

        fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

        /** Bounded pumping from inside a suspend hook, where `runCurrent()` would be re-entrant. */
        suspend fun yieldUntil(cond: () -> Boolean) {
            repeat(MAX_PUMPS) {
                if (cond()) return
                yield()
            }
            check(cond()) { "condition never held after $MAX_PUMPS yields" }
        }
    }
}
