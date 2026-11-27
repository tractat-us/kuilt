@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.ProvenResume
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.FakeSeam
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The shared rig of the key → seat tests (#3159, Tasks 1.5 and 1.6): a host whose joiners are driven
 * frame by frame ([RawHost]), and a switchboard of real rooms whose peer ids the test chooses
 * ([Switchboard]), so that "the same device, rebuilt, with the same fabric id" is expressible.
 *
 * Every timing is virtual. A seat is **connected** while its peer sent anything in the last
 * [KEY_SEAT_HEARTBEAT]`.timeout`, and **partitioned** once the host's detector has seen that silence:
 * the detector judges silence on its own ticks, so a Hello and Proof delivered with no time passing
 * are decided while the seat is still in whatever state the last tick left it. That is what lets a
 * test reach the partitioned-seat `tryResume` arm deterministically.
 */
internal val KEY_SEAT_HEARTBEAT = HeartbeatConfig(
    interval = 100.milliseconds,
    timeout = 200.milliseconds,
    // Long against the ~400 ms a partition takes to detect, so a held seat is still held when a test
    // looks, and two seats partitioned one after the other are both still held.
    reconnectWindow = 2.seconds,
)

internal val KEY_SEAT_ADMIT_TIMEOUT: Duration = 1.seconds

internal val KEY_SEAT_ROOM = RoomId("room-1")

internal val KEY_SEAT_HOST = PeerId("host")

/** The host's device number; every other device in these tests is numbered from 1. */
internal const val HOST_DEVICE = 0

/** Long enough past a silence for the host's detector to have called the seat partitioned. */
internal val PARTITION_DETECTED: Duration = KEY_SEAT_HEARTBEAT.timeout + KEY_SEAT_HEARTBEAT.interval * 2

internal fun TestScope.virtualClock(): () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

/** A scope that dies alone: cancelling it is a process crash for the rooms built on it. */
internal fun TestScope.processScope(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

/**
 * The host's reconnect-window controller, observed: every [ProvenResume] the room presents and every
 * verdict it gets back. [verdict], when set, answers in the real controller's place — a hold policy
 * that refuses — while the real controller's window, and its timer, carry on underneath.
 */
internal class ObservedHold(
    private val verdict: ((ProvenResume) -> ResumeResult.HostVerdict)? = null,
    /** When set, every `tryResume` suspends here before it answers: the room is mid-ask until it completes. */
    private val gate: CompletableDeferred<Unit>? = null,
) {
    var real: DefaultJoinerReconnectController? = null
    val calls = mutableListOf<ProvenResume>()
    val verdicts = mutableListOf<ResumeResult.HostVerdict>()

    /** When set, every `tryResume` runs this after [gate] and before the window is consulted. */
    var beforeAnswer: (suspend (ProvenResume) -> Unit)? = null

    /**
     * When set, every `tryResume` suspends here once its verdict is decided and recorded, before
     * returning it: the window is already spent on a `Success`, and the room has not acted on it.
     */
    var holdAnswer: CompletableDeferred<Unit>? = null

    /** As [holdAnswer], for the `tryResume` call with this index (from 0) alone. */
    val holdCall = mutableMapOf<Int, CompletableDeferred<Unit>>()

    /**
     * When set, a partition the room reports reaches the real controller only once this completes: a
     * controller that lags in opening the window. The default one lags too — it opens it on a launched
     * coroutine — but only for an instant, which a test cannot hold open.
     */
    var lagOpening: CompletableDeferred<Unit>? = null

    /**
     * As [lagOpening], for a recovery the room reports: it reaches the real controller only once this
     * completes. The room calls the controller outside its lock (#1614), so a thread can stall there.
     */
    var lagRecovering: CompletableDeferred<Unit>? = null

    /** The detection instant of every partition the real controller has been told of, in order. */
    val opened = mutableListOf<Long>()

    /** The instant of every recovery the real controller has been told of, in order. */
    val recovered = mutableListOf<Long>()

    val factory: JoinerReconnectControllerFactory = { roomId, scope, clock ->
        val controller = DefaultJoinerReconnectController(
            roomId = roomId,
            reconnectWindowMs = KEY_SEAT_HEARTBEAT.reconnectWindow.inWholeMilliseconds,
            clock = { clock().toEpochMilliseconds() },
            scope = scope,
        )
        real = controller
        object : JoinerReconnectController by controller {
            override fun onPeerUnresponsive(peerId: PeerId, at: Long) {
                val lag = lagOpening
                if (lag == null) {
                    opened += at
                    controller.onPeerUnresponsive(peerId, at)
                } else {
                    scope.launch {
                        lag.await()
                        opened += at
                        controller.onPeerUnresponsive(peerId, at)
                    }
                }
            }

            override fun onPeerRecovered(peerId: PeerId, at: Long) {
                val lag = lagRecovering
                if (lag == null) {
                    recovered += at
                    controller.onPeerRecovered(peerId, at)
                } else {
                    scope.launch {
                        lag.await()
                        recovered += at
                        controller.onPeerRecovered(peerId, at)
                    }
                }
            }

            override suspend fun tryResume(resume: ProvenResume, at: Long): ResumeResult.HostVerdict {
                val index = calls.size
                calls += resume
                gate?.await()
                beforeAnswer?.invoke(resume)
                val answer = (verdict?.invoke(resume) ?: controller.tryResume(resume, at)).also { verdicts += it }
                holdAnswer?.await()
                holdCall[index]?.await()
                return answer
            }
        }
    }
}

/** Every [MembershipEvent] a room emits, from its construction on (the room's events replay). */
internal class EventLog(room: Room, scope: CoroutineScope) {
    private val events = mutableListOf<MembershipEvent>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { room.events.collect { events += it } }
    }

    /** A position to read [since] from. */
    fun mark(): Int = events.size

    fun since(mark: Int): List<MembershipEvent> = events.drop(mark)

    /** The membership edges since [mark] — `Joined`, `Left`, `Resumed` — the ones a restore must not fake. */
    fun edgesSince(mark: Int): List<MembershipEvent> = since(mark).filter {
        it is MembershipEvent.Joined || it is MembershipEvent.Left || it is MembershipEvent.Resumed
    }
}

internal fun MembershipEvent.describe(): String = when (this) {
    is MembershipEvent.Joined -> "Joined(${member.id.value})"
    is MembershipEvent.Left -> "Left(${peerId.value}, $reason)"
    is MembershipEvent.Resumed -> "Resumed(${peerId.value})"
    is MembershipEvent.Readmitted -> "Readmitted(${peerId.value})"
    else -> toString()
}

internal fun List<AdmitMessage>.welcomesFor(peer: PeerId): List<AdmitMessage.Welcome> =
    filterIsInstance<AdmitMessage.Welcome>().filter { it.assignedPeerId == peer.value }

internal fun List<AdmitMessage>.farewellsFor(peer: PeerId): List<AdmitMessage.Farewell> =
    filterIsInstance<AdmitMessage.Farewell>().filter { it.peerId == peer.value }

/** Whether this frame tells its recipient anything about [peer]'s membership. */
internal fun AdmitMessage.names(peer: PeerId): Boolean = when (this) {
    is AdmitMessage.Welcome -> assignedPeerId == peer.value
    is AdmitMessage.Farewell -> peerId == peer.value
    is AdmitMessage.Paused -> peerId == peer.value
    is AdmitMessage.Unpaused -> peerId == peer.value
    else -> false
}

// ── A host whose joiners are frames ──────────────────────────────────────────

/**
 * A host room over a [FakeSeam] that already sees [joiners], driven one admit frame at a time — the
 * `KeyAdmissionTest` shape. A joiner here is a [DeviceKey] and a fabric sender; nothing answers the
 * host's pings, so a joiner the test does not [ping] goes silent and is partitioned after
 * [PARTITION_DETECTED].
 */
internal class RawHost(
    private val test: TestScope,
    val seam: FakeSeam,
    val room: Room,
    val hold: ObservedHold,
    val events: EventLog,
    val hostKey: DidKey,
) {
    suspend fun say(from: PeerId, message: AdmitMessage) {
        seam.deliver(from, AdmitMessage.encode(message))
        test.testScheduler.runCurrent()
    }

    /** Every admit frame the room sent to [peer], decoded, in order. */
    fun sentTo(peer: PeerId): List<AdmitMessage> =
        seam.directed.filter { it.first == peer }.mapNotNull { AdmitMessage.decode(it.second) }

    fun challengesTo(peer: PeerId): List<Challenge> =
        sentTo(peer).filterIsInstance<AdmitMessage.Challenge>().map { assertNotNull(Challenge.decode(it.statement)) }

    fun rejectsTo(peer: PeerId): List<AdmitMessage.Reject> = sentTo(peer).filterIsInstance<AdmitMessage.Reject>()

    /**
     * The roster a joiner at [peer] would hold from what the host sent it: every `Welcome` adds the
     * member it names, every `Farewell` removes one. A star bystander has no other source, so a
     * member still here after its seat is gone is a ghost on that bystander (#1557).
     */
    fun rosterHeardBy(peer: PeerId): Set<PeerId> = sentTo(peer).fold(emptySet()) { roster, message ->
        when (message) {
            is AdmitMessage.Welcome -> roster + PeerId(message.assignedPeerId)
            is AdmitMessage.Farewell -> roster - PeerId(message.peerId)
            else -> roster
        }
    }

    /** Every admit frame the room sent, to anyone, decoded. */
    fun sentToAnyone(): List<AdmitMessage> = seam.directed.mapNotNull { AdmitMessage.decode(it.second) }

    fun member(peer: PeerId): Member? = room.roster.value.firstOrNull { it.id == peer }

    fun hello(name: String, key: DidKey, nonce: Byte, deviceId: String? = null): AdmitMessage.Hello = AdmitMessage.Hello(
        displayName = name,
        sessionId = "session-$name-$nonce",
        deviceId = deviceId,
        protocolVersion = ProtocolVersion.CURRENT,
        key = key,
        joinerNonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { nonce },
    )

    /** The proof [device] signs, as fabric sender [from], to answer [challenge], presenting [grants]. */
    suspend fun proof(challenge: Challenge, device: DeviceKey, from: PeerId, grants: List<Grant> = emptyList()): AdmitMessage.Proof = AdmitMessage.Proof(
        AdmissionProof.sign(
            joiner = device,
            roomId = challenge.roomId,
            hostKey = challenge.hostKey,
            hostPeerId = KEY_SEAT_HOST.value,
            joinerPeerId = from.value,
            deviceId = null,
            hostNonce = challenge.hostNonce,
        ).wire,
        grants.map { it.wire },
    )

    /**
     * One whole exchange from fabric sender [from] proving [device]: Hello, then the proof answering
     * the challenge that Hello drew, presenting [grants]. Asserts the challenge was sent — the rig
     * fired — and returns it.
     */
    suspend fun admit(from: PeerId, device: DeviceKey, nonce: Byte, name: String = from.value, grants: List<Grant> = emptyList()): Challenge {
        val before = challengesTo(from).size
        say(from, hello(name, device.id, nonce))
        val challenges = challengesTo(from)
        assertTrue(challenges.size == before + 1, "rig: the Hello from ${from.value} drew one challenge")
        val challenge = challenges.last()
        say(from, proof(challenge, device, from, grants))
        return challenge
    }

    /** Every pass the host sent [peer], on its direct Welcomes and renewals, decoded, in order. */
    fun passesTo(peer: PeerId): List<Grant> = sentTo(peer).mapNotNull { message ->
        when (message) {
            is AdmitMessage.Welcome -> message.grant?.takeIf { message.assignedPeerId == peer.value }
            is AdmitMessage.GrantRenewal -> message.grant
            else -> null
        }
    }.map { assertNotNull(Grant.decode(it), "every pass the host sends decodes") }

    /** A frame from [from] the host's detector counts as contact. */
    suspend fun ping(from: PeerId) {
        seam.deliver(from, HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray())
    }

    /** Advance [duration] of virtual time, one heartbeat interval at a time, with [alive] pinging each step. */
    suspend fun advanceKeeping(alive: Set<PeerId>, duration: Duration) {
        var left = duration
        while (left > Duration.ZERO) {
            alive.forEach { ping(it) }
            val step = minOf(left, KEY_SEAT_HEARTBEAT.interval)
            test.testScheduler.advanceTimeBy(step)
            test.testScheduler.runCurrent()
            left -= step
        }
    }

    /**
     * Let [peer] go silent until the host holds its seat as partitioned, keeping [alive] connected.
     * Asserts all three witnesses — the roster level, the `Partitioned` event and the window — so a
     * test built on it cannot pass with the seat still connected.
     */
    suspend fun partition(peer: PeerId, alive: Set<PeerId>) {
        val mark = events.mark()
        advanceKeeping(alive, PARTITION_DETECTED)
        val seen = events.since(mark)
        assertIs<Liveness.Partitioned>(member(peer)?.liveness, "rig: the host holds ${peer.value}'s seat as partitioned")
        assertTrue(seen.any { it is MembershipEvent.Partitioned && it.peerId == peer }, "rig: the host saw ${peer.value} partition")
        assertTrue(seen.any { it is MembershipEvent.WindowOpened && it.peerId == peer }, "rig: ${peer.value}'s window opened")
        alive.forEach { assertTrue(member(it)?.liveness == Liveness.Connected, "rig: ${it.value} stayed connected") }
    }
}

/**
 * A [RawHost] for [joiners], on device [HOST_DEVICE], with an observed controller, deciding newcomers
 * with [admission]. The room adopts [adopted] of the raw seam, which the test still drives directly:
 * a decorator that adds what a fabric reports beside the frames, such as a `PrincipalRoster`.
 */
internal suspend fun TestScope.rawHost(
    joiners: Set<PeerId>,
    hold: ObservedHold = ObservedHold(),
    store: DeviceKeyStore = fakeKeyStore(HOST_DEVICE),
    clock: () -> Instant = virtualClock(),
    admission: HostAdmission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
    roomId: RoomId? = KEY_SEAT_ROOM,
    adopted: (FakeSeam) -> Seam = { it },
): RawHost {
    val seam = FakeSeam(selfId = KEY_SEAT_HOST, initialPeers = setOf(KEY_SEAT_HOST) + joiners)
    val room = SeamRoomFactory(
        loom = InMemoryLoom(),
        scope = backgroundScope,
        clock = clock,
        deviceKeyStore = store,
        heartbeatConfig = KEY_SEAT_HEARTBEAT,
        admitTimeout = KEY_SEAT_ADMIT_TIMEOUT,
        reconnectControllerFactory = hold.factory,
    ).adopt(adopted(seam), AdoptRole.Host(admission, roomId = roomId), memberName = "Host")
    val events = EventLog(room, backgroundScope)
    testScheduler.runCurrent()
    return RawHost(this, seam, room, hold, events, store.deviceKey().id)
}

// ── Real rooms with peer ids the test chooses ────────────────────────────────

/**
 * A fabric of [FakeSeam]s, one per plugged peer, whose sends are delivered into each other: real rooms
 * over it keep the peer ids the test gives them, which is what "the same device rebuilt with the same
 * fabric id" needs and `InMemoryLoom` cannot give (it numbers its peers).
 *
 * Every peer of [cast] is listed in every seam's peers from the start, plugged or not, so a peer that
 * is [unplug]ged or [mute]d is a **black-holed link**, not a closed one: the host's detector sees only
 * silence and partitions it by timeout, holding its seat. Its frames are dropped, not queued.
 *
 * [sent] records what each end *tried* to send, before any muting — so a frame sent to a crashed
 * process is still visible. [delivered] records only admit frames that arrived.
 */
internal class Switchboard(private val cast: Set<PeerId>) {
    class Frame(val from: PeerId, val to: PeerId, val message: AdmitMessage)

    private val ends = mutableMapOf<PeerId, FakeSeam>()
    private val muted = mutableSetOf<Pair<PeerId, PeerId>>()
    private val stalls = mutableListOf<Stall>()
    private val sentLog = mutableListOf<Frame>()
    private val deliveredLog = mutableListOf<Frame>()

    private class Stall(val from: PeerId, val to: PeerId, val duration: Duration, val matches: (AdmitMessage) -> Boolean) {
        var fired = 0
    }

    /** A fresh end for [id], replacing any end it had: a rebuilt process on the same fabric id. */
    fun plug(id: PeerId): Seam {
        require(id in cast) { "${id.value} is not in the cast" }
        val fake = FakeSeam(selfId = id, initialPeers = cast)
        ends[id] = fake
        return Wire(fake)
    }

    /** [id]'s end is gone: every frame to it is dropped from now on. */
    fun unplug(id: PeerId) {
        ends.remove(id)
    }

    /** Frames from [from] to [to] are dropped until [unmute]. One direction only. */
    fun mute(from: PeerId, to: PeerId) {
        muted += from to to
    }

    fun unmute(from: PeerId, to: PeerId) {
        muted -= from to to
    }

    /**
     * The first frame from [from] to [to] matching [matches] waits [duration] of virtual time inside
     * the sender's `sendTo` — the `AdmitFanOutOrderingTest` stall, on one recipient's lane.
     * Returns a counter of how many times it fired.
     */
    fun stallFirst(from: PeerId, to: PeerId, duration: Duration, matches: (AdmitMessage) -> Boolean): () -> Int {
        val stall = Stall(from, to, duration, matches)
        stalls += stall
        return { stall.fired }
    }

    fun sent(from: PeerId? = null, to: PeerId? = null): List<AdmitMessage> =
        sentLog.filter { (from == null || it.from == from) && (to == null || it.to == to) }.map { it.message }

    fun delivered(from: PeerId? = null, to: PeerId? = null): List<AdmitMessage> =
        deliveredLog.filter { (from == null || it.from == from) && (to == null || it.to == to) }.map { it.message }

    /** Hand [message] to [to]'s end as if [from] had sent it, bypassing [from]'s room entirely. */
    suspend fun inject(from: PeerId, to: PeerId, message: AdmitMessage) {
        carry(from, to, AdmitMessage.encode(message))
    }

    private suspend fun carry(from: PeerId, to: PeerId, payload: ByteArray) {
        val decoded = AdmitMessage.decode(payload)
        if (decoded != null) sentLog += Frame(from, to, decoded)
        if (decoded != null) {
            stalls.firstOrNull { it.fired == 0 && it.from == from && it.to == to && it.matches(decoded) }?.let {
                it.fired++
                delay(it.duration)
            }
        }
        if ((from to to) in muted) return
        val end = ends[to] ?: return
        end.deliver(from, payload)
        if (decoded != null) deliveredLog += Frame(from, to, decoded)
    }

    private inner class Wire(private val fake: FakeSeam) : Seam by fake {
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            fake.sendTo(peer, payload)
            carry(fake.selfId, peer, payload)
        }

        override suspend fun broadcast(payload: ByteArray) {
            fake.broadcast(payload)
            for (peer in cast - fake.selfId) carry(fake.selfId, peer, payload)
        }

        override suspend fun close(reason: CloseReason) = fake.close(reason)
    }
}

/** A real room on [board] under [id], on [store], with every key-seat timing. */
internal suspend fun TestScope.boardRoom(
    board: Switchboard,
    id: PeerId,
    store: DeviceKeyStore,
    role: SessionRole,
    scope: CoroutineScope = backgroundScope,
    hold: ObservedHold? = null,
    seam: Seam = board.plug(id),
    reweave: (suspend () -> Seam)? = null,
    name: String = id.value,
    admission: HostAdmission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
): Room = SeamRoomFactory(
    loom = InMemoryLoom(),
    scope = scope,
    clock = virtualClock(),
    deviceKeyStore = store,
    heartbeatConfig = KEY_SEAT_HEARTBEAT,
    admitTimeout = KEY_SEAT_ADMIT_TIMEOUT,
    reconnectControllerFactory = hold?.factory,
).adopt(
    seam,
    if (role == SessionRole.Host) AdoptRole.Host(admission, roomId = KEY_SEAT_ROOM) else AdoptRole.Joiner,
    memberName = name,
    reweave = reweave,
)
