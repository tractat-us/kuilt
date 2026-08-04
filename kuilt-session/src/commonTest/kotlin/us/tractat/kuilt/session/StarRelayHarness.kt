@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The shared star fixture for #1994's relay: one host plus N spokes over the real hub fabric
 * ([InMemoryRoomFabric]), with every member's wire tapped.
 *
 * Modelled on `LivenessRouteGateTest.star()`, but deliberately **not** on the real-time-ceiling-on-a-
 * virtual-time-test pattern that fixture used to carry — a bare `timeout = 5.seconds`, since retired
 * onto `TEST_WEDGE_BACKSTOP` (`:kuilt-test`) along with the rest of this module (#1739/#1891). Callers
 * use a generous named backstop instead.
 *
 * ## What the tap buys
 *
 * A relay is a routing decision, so several of the properties under test are about *what crossed
 * the wire*, not only about what was delivered: that a host with a partitioned member still sends
 * **directly** (I3), that a diverged joiner relays **both** call shapes (I2), and that a wedged
 * spoke really was targeted. [WireTapSeam] records every outbound frame and can also inject one
 * inbound — the flat-loom/compromised-peer case, which a star fabric cannot otherwise produce
 * because a spoke has no route to a co-spoke (that absence *is* #1994).
 *
 * ## Payload-byte trap
 *
 * A plain-text payload is relayable only because [RoomFramePrefix] does not *classify* it. The
 * lowercase letters `a`, `c`, `e`, `k`, `r` **are** the five reserved bytes `0x61`, `0x63`, `0x65`,
 * `0x6b`, `0x72`, and three of the five families classify on that byte alone — so a payload
 * starting with `a`, `e`, or `r` is **not** a plain application frame and will not be relayed.
 *
 * The other two are narrower than their byte, and the gap is deliberate rather than incidental:
 * `Channel` also requires a 3-byte header and `Heartbeat` requires the whole
 * `"kuilt.heartbeat.ping"`/`"…pong"` string, so `"keepalive"` and a 2-byte `0x63` payload are
 * ordinary application data on both the direct and the relayed plane. Getting *that* wrong is the
 * defect §T17/§T18 exist to pin, and those two tests choose their payloads on purpose.
 *
 * So: safe to add a payload starting with `c` or `k` (as long as it is not literally a heartbeat
 * frame); **never** add one starting with `a`, `e`, or `r` unless the test is about it being
 * refused. The plain strings in use are "hello", "legit", "plain", "nested", "for-b", "for-host",
 * "somewhere", "honest", "forged", "nowhere", "unadmitted", "to-host", "keepalive".
 */
internal fun appPayload(text: String): ByteArray = text.encodeToByteArray()

/**
 * Heartbeat timing for the relay star.
 *
 * Chosen so the two send budgets are far apart and the gap is *legible*: the relay writer's budget
 * is [HeartbeatConfig.interval] (200 ms) and the membership writer's is
 * `reconnectWindow + timeout` (10.6 s). That ~50× separation is what makes the head-of-line test
 * discriminate — a relay flood parked on the membership queue would cost 64 × 10.6 s before the
 * next announcement moved, while the dedicated queue costs one interval per wedged item and never
 * touches the membership writer at all.
 */
internal val relayHeartbeat = HeartbeatConfig(
    interval = 200.milliseconds,
    timeout = 600.milliseconds,
    reconnectWindow = 10.seconds,
)

/**
 * One member of the star: its [Room], its identity, its wire tap, and what it has received.
 */
internal class StarMember(
    val room: Room,
    val id: PeerId,
    internal val wire: WireTapSeam,
    private val received: List<RoomFrame>,
    private val partitionedPeers: Set<PeerId>,
) {
    /** Application payloads this member received **credited to [peer]**, decoded as text. */
    fun appFramesFrom(peer: PeerId): List<String> =
        received.filter { it.sender == peer }.map { it.payload.decodeToString() }

    /** Every application payload this member received, from any sender. */
    fun appFrames(): List<String> = received.map { it.payload.decodeToString() }

    /**
     * Application payloads received **credited to [peer]**, as raw bytes.
     *
     * For payloads that are not valid text — a 2-byte frame whose first byte is one of the
     * reserved prefixes, say — where [appFramesFrom]'s `decodeToString` would compare replacement
     * characters rather than the bytes under test.
     */
    fun rawAppFramesFrom(peer: PeerId): List<ByteArray> =
        received.filter { it.sender == peer }.map { it.payload }

    /** This member's identified host, or null before identification. */
    fun hostPeer(): PeerId? = (room as SeamRoom).hostPeer()

    /** Relay forwards this member's room discarded to queue overflow. See [SeamRoom.relayForwardsDropped]. */
    fun relayForwardsDropped(): Long = (room as SeamRoom).relayForwardsDropped

    /** Frames this member's room refused to put on the wire as oversize. See [SeamRoom.oversizeFramesDropped]. */
    fun oversizeFramesDropped(): Long = (room as SeamRoom).oversizeFramesDropped

    /** Whether this member observed [peer] going [MembershipEvent.Partitioned]. */
    fun sawPartitioned(peer: PeerId): Boolean = peer in partitionedPeers

    /**
     * Write a relay envelope straight onto this member's wire, addressed to the host.
     *
     * Bypasses [Room.broadcast] so a test can drive the host's relay arm with an envelope of its
     * own choosing — including ones [Room] would never mint.
     */
    suspend fun sendRelay(dest: RelayDest, payload: ByteArray) {
        sendRelayForgingOrigin(origin = id, dest = dest, payload = payload)
    }

    /** As [sendRelay], but claiming [origin] as the originator — the spoofing case. */
    suspend fun sendRelayForgingOrigin(origin: PeerId, dest: RelayDest, payload: ByteArray) {
        wire.broadcast(RelayEnvelope.encode(RelayEnvelope(origin, dest, payload)))
    }

    /**
     * Inject [envelope] directly into [target]'s inbound stream, stamped with **this** member's id.
     *
     * The compromised-co-joiner case: on a flat loom a peer can put a relay frame on another
     * peer's link, and the star fabric's inability to do so is exactly the topological protection
     * #1994 removes. Not reachable through the fabric, so it is injected.
     */
    fun injectRelayDirectlyTo(target: StarMember, envelope: RelayEnvelope) {
        target.wire.inject(Swatch(RelayEnvelope.encode(envelope), sender = id))
    }
}

/**
 * A host plus its spokes, with the collective operations a relay test needs.
 *
 * [joinerA]/[joinerB]/[joinerC] are the first three spokes in admission order; asking for one the
 * star does not have fails loudly rather than returning a surprise.
 */
internal class RelayStar(
    val host: StarMember,
    private val joiners: List<StarMember>,
    private val newUnadmittedSeam: suspend () -> Seam,
) {
    val hostId: PeerId get() = host.id

    val joinerA: StarMember get() = joiner(0)
    val joinerB: StarMember get() = joiner(1)
    val joinerC: StarMember get() = joiner(2)

    val joinerAId: PeerId get() = joinerA.id
    val joinerBId: PeerId get() = joinerB.id
    val joinerCId: PeerId get() = joinerC.id

    private fun joiner(index: Int): StarMember = requireNotNull(joiners.getOrNull(index)) {
        "this star has ${joiners.size} joiners; index $index was asked for"
    }

    /**
     * Partition [peer] **from the host's point of view**: gone from the host's `Seam.peers`, still
     * in its roster, and its inbound frames dropped.
     *
     * Both halves are load-bearing and they serve different tests. Leaving the roster untouched
     * while removing the peer from `Seam.peers` is precisely the state a member inside its
     * reconnect window occupies (#1557/#1614) — the state in which `rosterPeers ⊆ seam.peers` is
     * **false on a host**, which is why the host's direct-send rule keys on the role and not on
     * that subset (I3). Dropping the peer's inbound frames additionally lets the host's own
     * liveness detector mature the silence into an authoritative `Paused` fan-out.
     */
    fun partition(peer: PeerId) {
        host.wire.exclude(peer)
        host.wire.silence(peer)
    }

    /** Data-plane frames the **host** wrote that would reach [peer] (unicasts to it, plus broadcasts). */
    fun wireFramesTo(peer: PeerId): List<ByteArray> = host.wire.dataFramesSentTo(peer)

    /** Data-plane frames the member [peer] wrote to the wire. */
    fun wireFramesFrom(peer: PeerId): List<ByteArray> = memberOf(peer).wire.dataFramesSent()

    /** Inject [envelope] into [target]'s inbound stream stamped as coming from the host. */
    fun hostRelayDirectlyTo(target: StarMember, envelope: RelayEnvelope) {
        host.injectRelayDirectlyTo(target, envelope)
    }

    /**
     * Drive the host's relay arm from a peer that never completed the admit handshake.
     *
     * A real seam on the real fabric — the connection and the transport-level peer handshake both
     * complete, so the host stamps a genuine sender on the frame — but no `Hello` is ever sent, so
     * the host's `admittedById` does not contain it. That is exactly the state the relay arm's own
     * admission gate exists for, and it is reachable because the arm fires *before* the
     * `isAdmittedPeer` arm it precedes.
     */
    suspend fun sendRelayFromUnadmitted(dest: RelayDest, payload: ByteArray) {
        val seam = newUnadmittedSeam()
        seam.broadcast(RelayEnvelope.encode(RelayEnvelope(seam.selfId, dest, payload)))
    }

    private fun memberOf(peer: PeerId): StarMember =
        requireNotNull((joiners + host).firstOrNull { it.id == peer }) { "no member ${peer.value} in this star" }
}

/**
 * Build a star of one host and [coJoiners] spokes, admitted **sequentially**.
 *
 * Sequential admission is load-bearing: admitting concurrently lets the interleaving decide the
 * order of `admittedById`, and both the fan-out recipient order and this harness's `joinerA/B/C`
 * naming rest on it.
 *
 * [wedge] names spokes whose host-side `sendTo` must never return — the black-holed link of #1655
 * — by spoke name (`"joiner-b"`), applied before any test traffic.
 *
 * Every wire log is **cleared** before this returns, so a test observes only the frames its own
 * body caused rather than the admit burst's.
 */
internal suspend fun TestScope.relayStar(
    coJoiners: Int = 2,
    wedge: Set<String> = emptySet(),
): RelayStar {
    require(coJoiners >= 2) { "a relay needs at least two spokes to relay between" }
    val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
        "no dispatcher (ContinuationInterceptor) in coroutine context"
    }
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
    val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(0L))

    val hostWire = WireTapSeam(fabric.serverLoom.host(Pattern(ROOM)), backgroundScope)
    val hostRoom = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock, relayHeartbeat)
        .adopt(hostWire, SessionRole.Host)
    val host = observe(hostRoom, hostWire)

    val joiners = mutableListOf<StarMember>()
    repeat(coJoiners) { index ->
        val loom = fabric.clientLoom(PeerId(joinerName(index)), Random(index.toLong() + 1L))
        // `adopt` (rather than `join`) so the harness keeps the spoke's own seam handle and can tap
        // it — mirroring LivenessRouteGateTest.star().
        val wire = WireTapSeam(loom.join(InMemoryTag(ROOM)), backgroundScope)
        val room = SeamRoomFactory(loom, backgroundScope, clock, relayHeartbeat)
            .adopt(wire, SessionRole.Joiner)
        joiners += observe(room, wire)
        // Await THIS spoke's admission before starting the next, so admission order is loop order.
        hostRoom.roster.first { it.size == index + 1 }
    }
    // Every spoke must hold every co-spoke before a relay test means anything: the roster is what
    // diverges from `Seam.peers`, and that divergence is what routes a send through the host.
    joiners.forEach { joiner -> joiner.room.roster.first { it.size == coJoiners } }

    wedge.forEach { name ->
        // A wedge naming a spoke that does not exist would be a silent no-op, and a test whose
        // wedge never took effect can still look green for the wrong reason.
        require(joiners.any { it.id.value == name }) {
            "wedge names '$name', which is not a spoke of this star: ${joiners.map { it.id.value }}"
        }
        host.wire.wedge(PeerId(name))
    }
    (joiners + host).forEach { it.wire.clearSentLog() }

    var intruders = 0
    return RelayStar(
        host = host,
        joiners = joiners,
        newUnadmittedSeam = {
            val nth = intruders++
            val loom = fabric.clientLoom(PeerId("intruder-$nth"), Random(1_000L + nth))
            loom.join(InMemoryTag(ROOM)).also { seam ->
                // No Hello is ever sent, so the room never admits it — but the transport handshake
                // must complete or the host would have no stamped sender to reject.
                //
                // Waiting on `Woven` alone is NOT enough and the difference is the whole test:
                // `broadcast` is best-effort with no peers, so a frame written before this seam has
                // learned the host goes nowhere, and the refusal the test then "observes" is the
                // frame never arriving. `peers.size > 1` is the contract's own documented sentinel
                // for "at least one remote peer is connected"; a mutation that deletes the relay's
                // admission gate survives without it.
                seam.state.first { it is SeamState.Woven }
                seam.peers.first { it.size > 1 }
            }
        },
    )
}

private const val ROOM = "table"

/**
 * A host and two joiners on a **flat mesh**, where every member holds a direct edge to every other
 * — and therefore a liveness detector for every other.
 *
 * This exists for the one property a star cannot express. "Data is relayed; liveness is not" is
 * about a relayed payload refreshing the *origin's* detector, and on a pure star no spoke has a
 * detector for a co-spoke (that is the #1576 route gate), so the masking is unreachable there and
 * the property is vacuously true. The plan says as much: the hazard bites "on a partial-mesh,
 * composite or tiered topology where B does hold a direct edge to A".
 *
 * [subject]'s seam is tapped so a test can silence one peer's real traffic and inject relayed
 * frames in its place.
 */
internal class MeshTrio(
    val hostId: PeerId,
    val originId: PeerId,
    val subject: StarMember,
    private val subjectChannelPayloads: List<String>,
) {
    /** Whether [subject] runs a liveness detector for [peer] — the precondition for masking. */
    fun subjectHasDetectorFor(peer: PeerId): Boolean = (subject.room as SeamRoom).hasDetector(peer)

    /** Silence [peer]'s **real** traffic into [subject], so its detector for [peer] starves. */
    fun silenceIntoSubject(peer: PeerId) {
        subject.wire.silence(peer)
    }

    /** Payloads [subject]'s [MESH_CHANNEL] view has received, de-framed. */
    fun subjectChannelFrames(): List<String> = subjectChannelPayloads.toList()

    /**
     * Inject into [subject] a host-relayed **channel** frame carrying [payload] on behalf of
     * [originId] — the shape whose delivery surface is the one under test.
     *
     * A channel frame deliberately, not a plain application frame: only the channel branch of
     * `deliverRelayedPayload` emits onto a `Swatch` stream at all (a plain frame goes to
     * `Room.incoming`, which no detector reads), so a plain frame cannot exercise the split this
     * fixture exists to observe.
     */
    fun relayChannelFrameToSubject(payload: String) {
        val framed = RoomChannel.frame(RoomChannel.channelSubId(MESH_CHANNEL), appPayload(payload))
        val envelope = RelayEnvelope(originId, RelayDest.Everyone, framed)
        subject.wire.inject(Swatch(RelayEnvelope.encode(envelope), sender = hostId))
    }
}

/** The channel [meshTrio]'s subject subscribes to. */
internal const val MESH_CHANNEL = "mesh-data"

/**
 * Build a [MeshTrio] over a flat [InMemoryLoom].
 *
 * Only the subject's seam is tapped. Weaving a throwaway seam merely to give the other two a tap
 * would be actively wrong here: [InMemoryLoom] is a *single flat mesh* in which a joiner of one
 * room is silently admitted to every other room on the same loom, so an extra `host`/`join` would
 * add phantom members and corrupt the very rosters this fixture asserts on.
 */
internal suspend fun TestScope.meshTrio(): MeshTrio {
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
    val loom = InMemoryLoom()
    val factory = SeamRoomFactory(loom, backgroundScope, clock, relayHeartbeat)

    val hostRoom = factory.host(Pattern(ROOM))
    val originRoom = factory.join(InMemoryTag("origin"))
    hostRoom.roster.first { it.size == 1 }

    val subjectWire = WireTapSeam(loom.join(InMemoryTag("subject")), backgroundScope)
    val subjectRoom = factory.adopt(subjectWire, SessionRole.Joiner)
    val subject = observe(subjectRoom, subjectWire)

    hostRoom.roster.first { it.size == 2 }
    subjectRoom.roster.first { it.size == 2 }
    originRoom.roster.first { it.size == 2 }

    // Subscribed eagerly, for the same reason `observe` is: the channel view is `replay = 0`, so a
    // collector that has not yet subscribed misses the frame rather than receiving it late.
    val channelPayloads = mutableListOf<String>()
    val view = subjectRoom.channel(MESH_CHANNEL)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        view.incoming.collect { channelPayloads += it.toByteArray().decodeToString() }
    }

    return MeshTrio(
        hostId = hostRoom.selfId,
        originId = originRoom.selfId,
        subject = subject,
        subjectChannelPayloads = channelPayloads,
    )
}

/** Spoke names are stable and ordered, so `joiner-b` in a test brief names the second spoke. */
private fun joinerName(index: Int): String = "joiner-${'a' + index}"

/**
 * Start recording [room]'s application frames and partition events.
 *
 * Deliberately [UnconfinedTestDispatcher]: `Room.incoming` and `Room.events` are emitted with
 * `tryEmit` onto a `SharedFlow` whose replay does not cover application frames, so a collector
 * that has not yet *subscribed* misses the frame outright. An unconfined `launch` subscribes
 * synchronously before it returns; a `StandardTestDispatcher` collector is merely queued, and a
 * test built on one is green whether or not the frame was ever delivered. The collector bodies
 * only append to a collection, so eager inline resumption costs nothing else. (Same reasoning,
 * and the same carve-out, as `JoinWindowHostIdentityTest.sampleJoins`.)
 */
private fun TestScope.observe(room: Room, wire: WireTapSeam): StarMember {
    val received = mutableListOf<RoomFrame>()
    val partitioned = mutableSetOf<PeerId>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        room.incoming.collect { received += it }
    }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        room.events.filterIsInstance<MembershipEvent.Partitioned>().collect { partitioned += it.peerId }
    }
    return StarMember(
        room = room,
        id = room.selfId,
        wire = wire,
        received = received,
        partitionedPeers = partitioned,
    )
}

/**
 * The oversize error a framed fabric raises, as this module can observe it.
 *
 * Stands in for `:kuilt-stream`'s `FrameTooLargeException` — the real thing a length-prefixed
 * fabric throws once a frame exceeds its limit — which `:kuilt-session` does not depend on. The
 * **identity of the type** is what the payload-budget tests assert on: a fabric-level oversize
 * error escaping a room send is the #2047 symptom, so a test that merely counted failures could
 * not tell the bug from its fix.
 */
internal class FabricFrameTooLarge(size: Int, max: Int) :
    Exception("frame length $size exceeds fabric max $max")

/**
 * A [Seam] decorator that records what a member wrote, and can bend the link five ways.
 *
 * Every capability here exists for one property under test and none of them is a general-purpose
 * fault model — reach for `FaultySeam` for that. What this adds that `FaultySeam` cannot is
 * control over the *peer set*: [exclude] removes a peer from [peers] while leaving the room's
 * roster alone, which is the one state in which `rosterPeers ⊆ seam.peers` is false on a host.
 *
 * - [exclude] — drop a peer from [peers] (a member inside its reconnect window).
 * - [disconnect] — as [exclude], **and** make [sendTo] to that peer throw [PeerNotConnected].
 * - [silence] — drop that peer's inbound frames, so a liveness detector matures the silence.
 * - [wedge] — make [sendTo] to that peer never return, the black-holed link of #1655.
 * - [limitFrames] — bound the frame size this fabric accepts, as a length-prefixed one does.
 * - [inject] — put a frame on this member's inbound stream with an arbitrary stamped sender.
 *
 * Mutable state is held in [MutableStateFlow]s, and **every mutation goes through
 * [MutableStateFlow.update]** rather than `value = value + …`. The distinction is the whole claim:
 * `MutableStateFlow` makes each individual `value` read and each `value` write atomic, but a
 * read-modify-write built from the two is not, so concurrent `record` calls under a multi-threaded
 * dispatcher would lose frames. `update` performs the read-modify-write as a CAS loop, which is
 * what actually makes this decorator correct off the test dispatcher.
 */
internal class WireTapSeam(
    private val delegate: Seam,
    scope: CoroutineScope,
) : Seam {

    private companion object {
        /** See [injected]. Far above what any test needs, so hitting it means something is wrong. */
        const val INJECTION_CAPACITY = 64
    }

    /** One frame this member wrote. [to] is null for a broadcast. */
    private class SentFrame(val to: PeerId?, val bytes: ByteArray)

    private val sent = MutableStateFlow<List<SentFrame>>(emptyList())
    private val excluded = MutableStateFlow<Set<PeerId>>(emptySet())
    private val unreachable = MutableStateFlow<Set<PeerId>>(emptySet())
    private val silenced = MutableStateFlow<Set<PeerId>>(emptySet())
    private val wedged = MutableStateFlow<Set<PeerId>>(emptySet())
    private val frameLimit = MutableStateFlow<Int?>(null)
    /**
     * Injected inbound frames. **Bounded**, per the repo's `forbidUnboundedSwatchDelivery` guard —
     * and the bound is honest rather than evasive: a test injects a frame or two, so anything
     * approaching this capacity is a runaway, not a burst.
     */
    private val injected = Channel<Swatch>(capacity = INJECTION_CAPACITY)

    private val _peers = MutableStateFlow(delegate.peers.value)

    init {
        scope.launch { delegate.peers.collect { recomputePeers() } }
    }

    /**
     * Recompute [peers] from the delegate minus [excluded].
     *
     * Written through [MutableStateFlow.update] even though the lambda ignores its argument: the
     * CAS retry is what forces a concurrent [exclude] to be re-read rather than lost, since the
     * sources are read *inside* the block.
     */
    private fun recomputePeers() {
        _peers.update { delegate.peers.value - excluded.value }
    }

    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = _peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override val capability: StateFlow<TransportCapability> get() = delegate.capability

    /** [limitFrames]'s ceiling when one is set, else the delegate's own. */
    override val maxPayloadBytes: Int? get() = frameLimit.value ?: delegate.maxPayloadBytes

    /**
     * The delegate's inbound stream, minus [silence]d senders, merged with anything [inject]ed.
     *
     * [silence] deliberately applies to the **delegate only**. Silencing models a dead link; an
     * injection is a deliberate act of the test, and a test that silences a peer's real traffic in
     * order to watch a detector starve usually needs to keep injecting *through* that same peer —
     * which is exactly how the "data is relayed, liveness is not" property is observed.
     *
     * Single-collection like every [Seam.incoming] (ADR-034) — `SeamRoom` collects it exactly once.
     */
    override val incoming: Flow<Swatch> = merge(
        delegate.incoming.filter { swatch -> swatch.sender !in silenced.value },
        injected.receiveAsFlow(),
    )

    override suspend fun broadcast(payload: ByteArray) {
        record(to = null, bytes = payload)
        enforceFrameLimit(payload)
        delegate.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        // Recorded BEFORE the wedge, so a test can still prove the send was attempted — the whole
        // point of a black hole is that the caller cannot tell it from a very slow link.
        record(to = peer, bytes = payload)
        enforceFrameLimit(payload)
        if (peer in wedged.value) awaitCancellation()
        // Checked after the wedge and before the delegate, so a peer that is both wedged and
        // disconnected still models the black hole (the harsher of the two).
        if (peer in unreachable.value) throw PeerNotConnected(peer)
        delegate.sendTo(peer, payload)
    }

    override suspend fun close(reason: CloseReason) {
        injected.close()
        delegate.close(reason)
    }

    private fun record(to: PeerId?, bytes: ByteArray) {
        sent.update { it + SentFrame(to, bytes) }
    }

    /**
     * Put [swatch] on this member's inbound stream, sender and all.
     *
     * Fails loudly rather than dropping. Every caller injects a frame a test then expects to be
     * *refused*, so a silent drop would make that test pass for the wrong reason — the frame the
     * gate is supposed to reject would never have reached the gate at all.
     */
    fun inject(swatch: Swatch) {
        check(injected.trySend(swatch).isSuccess) {
            "injection channel full or closed — the frame never reached the member under test, " +
                "so any refusal this test observes is vacuous"
        }
    }

    fun exclude(peer: PeerId) {
        excluded.update { it + peer }
        // Recomputed synchronously so a `peers.value` read on the very next line already sees it,
        // rather than waiting for the collector above to be dispatched.
        recomputePeers()
    }

    /**
     * As [exclude], but [sendTo] to [peer] additionally throws [PeerNotConnected].
     *
     * This is the **contract-honest** shape of a dropped link: `Seam.sendTo` "when the addressed
     * peer is absent from `peers`: throws `PeerNotConnected`" (`Seam` KDoc). [exclude] deliberately
     * does *not* do this — it models only the room-level state in which a member inside its
     * reconnect window outlives the transport's peer set, and several tests need the host's
     * fan-out to that member to keep landing. Use this one when the link itself is the subject.
     */
    fun disconnect(peer: PeerId) {
        unreachable.update { it + peer }
        exclude(peer)
    }

    fun silence(peer: PeerId) {
        silenced.update { it + peer }
    }

    fun wedge(peer: PeerId) {
        wedged.update { it + peer }
    }

    /**
     * Bound the frames this fabric accepts to [max] bytes, as a length-prefixed transport does.
     *
     * A bigger frame raises [FabricFrameTooLarge] from `broadcast`/`sendTo` — the shape
     * `:kuilt-stream`'s `framed()` has, and the shape the star relay can walk a caller into: a
     * payload that fits a direct send no longer fits once the [RelayEnvelope] is wrapped around it
     * (#2047).
     */
    fun limitFrames(max: Int) {
        frameLimit.value = max
    }

    private fun enforceFrameLimit(payload: ByteArray) {
        val max = frameLimit.value ?: return
        if (payload.size > max) throw FabricFrameTooLarge(payload.size, max)
    }

    fun clearSentLog() {
        sent.value = emptyList()
    }

    /**
     * Frames this member wrote, excluding **heartbeat** frames.
     *
     * Heartbeats are time-driven background traffic on every live link, so an assertion of the
     * shape "every frame this member wrote was a relay frame" is about the *data plane* and would
     * otherwise be defeated by a ping that happened to be due. Nothing else is filtered: the log
     * is cleared once the star is built, so an admit or lobby frame appearing during a test body
     * is a real observation and stays visible.
     */
    fun dataFramesSent(): List<ByteArray> =
        sent.value.map { it.bytes }.filterNot { HeartbeatPartitionDetector.isHeartbeatFrame(it) }

    /** As [dataFramesSent], narrowed to frames that would reach [peer] — unicasts to it plus broadcasts. */
    fun dataFramesSentTo(peer: PeerId): List<ByteArray> =
        sent.value
            .filter { it.to == null || it.to == peer }
            .map { it.bytes }
            .filterNot { HeartbeatPartitionDetector.isHeartbeatFrame(it) }
}
