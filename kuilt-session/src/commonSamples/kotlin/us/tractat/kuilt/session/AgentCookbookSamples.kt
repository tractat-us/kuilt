package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.util.ExponentialBackoff
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.session.admit.AdmissionDecision
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.GrantReach
import us.tractat.kuilt.session.admit.GrantTerms
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.election.ElectionLobby
import us.tractat.kuilt.session.election.ElectionOutcome
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.RoomId
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Minimal, compile-checked snippets quoted verbatim by docs/agent-cookbook/session.md (most) and
// docs/agent-cookbook/fabrics.md (chunkToTheRoomsBudgetSample).
// Keep each function tiny and self-contained; the cookbook copies the body.

/**
 * Read one member's frames without losing the ones it sent before you started reading.
 *
 * Returns `true` when the member's admission ended with every frame delivered, and `false` when frames
 * were lost — the caller then resyncs that member from its own state.
 */
public suspend fun perMemberFramesSample(room: Room, member: PeerId, onFrame: suspend (ByteArray) -> Unit): Boolean =
    try {
        // Held from admission: frames the member sent before this line are still delivered, in order.
        // Not `room.incoming.filter { it.sender == member }`, which loses whatever was routed first.
        room.incomingFrom(member).collect { onFrame(it.payload) }
        true // the admission ended; a member admitted again gets a fresh inbox, so call this again
    } catch (_: FramesLost) {
        false // never silent, and terminal for this admission: treat the member as lost
    }

/**
 * Chunk arbitrary bytes over a room channel, using the channel's published budget.
 *
 * The channel budget reserves space for both the relay envelope and the channel header. Sizing
 * to the fabric's own limit instead is the #2047 trap: it fits until the roster diverges and the
 * frame is relayed. Relay routes currently over-report this budget; see #2958.
 */
public suspend fun chunkToTheRoomsBudgetSample(room: Room, peer: PeerId, blob: ByteArray) {
    val ch = room.channel("blob")
    var start = 0
    while (start < blob.size) {
        // Re-read per chunk, not once for the loop: the budget is a reading, not a lease. On a mesh
        // it is the minimum across live links, so a peer attaching over a tighter transport lowers
        // it under you mid-blob. null means "this fabric names no ceiling" — unknown, not
        // unbounded; floored at 1 because the budget is legitimately 0 on a fabric whose ceiling is
        // under the relay and channel reservations.
        val budget = (ch.maxPayloadBytes ?: DEFAULT_CHUNK_BYTES).coerceAtLeast(1)
        val end = minOf(start + budget, blob.size)
        // Index arithmetic, not `asSequence().chunked()` — the latter boxes every byte and builds
        // an ArrayList<Byte> per chunk. On a blob big enough to need chunking that is the point.
        // A frame that will not fit the wire makes sendTo report PayloadTooLarge (addressed sends
        // do) while broadcast drops it with a log (lossy by contract) — neither surfaces the
        // fabric's own oversize error. A chunk at the budget fits on every route.
        ch.sendTo(peer, blob.copyOfRange(start, end))
        start = end
    }
}

/** A chunk size for a fabric that publishes no ceiling of its own. Small enough for any transport. */
private const val DEFAULT_CHUNK_BYTES: Int = 16 * 1024

/**
 * Read the per-session id both peers already agreed on, instead of minting one and replicating it.
 *
 * [Room.roomId] is settled in the admit handshake at zero extra traffic. Don't agree your own over
 * a side channel — and don't reach for the host's peer id either, which repeats across every room
 * that host runs.
 *
 * It names the **session**, so it scopes a log or names a table. It is not half of a
 * `(session, device) → seat` key: a device across launches is named by [Member.key], since a
 * process's [Room.selfId] can change on every relaunch, and the seat itself is the host's to keep,
 * keyed by that key.
 */
public suspend fun perSessionIdSample(room: Room) {
    // Null means "this joiner is not admitted yet", not "this room has no id" — so wait for the
    // value rather than sampling it. A host is non-null immediately and this returns at once.
    val id: RoomId = room.roomId.filterNotNull().first()
    // Both peers read the same value, and a fresh room means a fresh id, so it scopes this game's
    // log or names its table. Pair it with no peer id: selfId names this process, not the device.
    println("log scope: ${id.value}")
}

/**
 * Host under an id **decided outside kuilt** — a lobby code, an invite link, a matchmaker-assigned
 * game id — instead of letting the factory mint one, so every member reads back the value your
 * other systems already key on.
 *
 * Supplying an id does **not** make a host restart resumable: a restarted host has no roster and no
 * reconnect-window registry, so a device that comes back to it is admitted as a new member.
 *
 * Every host entry point takes the [HostAdmission] that decides who gets in;
 * `HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())` lets everyone in.
 */
public suspend fun callerSuppliedRoomIdSample(
    factory: RoomFactory,
    pattern: Pattern,
    lobbyCode: String,
    admission: HostAdmission,
): RoomId {
    val room = factory.host(pattern, roomId = RoomId(lobbyCode), admission = admission)
    // Read back off the room, not off the variable you passed in: the room is the thing joiners
    // agree with, and on a joiner this same property is how you learn the value at all.
    return room.roomId.value ?: error("a host room knows its id at construction")
}

/**
 * Watch a joiner come back after a dropped link. There is nothing to call: a joiner built by
 * [SeamRoomFactory] says Hello again on its own, within the reconnect window, and the device key its
 * Hello proves restores the seat the host held. Its loom must heal the same seam with the same
 * selfId (e.g. [us.tractat.kuilt.core.MuxClientLoom]). These events tell the app how it went.
 */
public suspend fun watchTheReturnSample(room: Room) {
    room.events.collect { event ->
        when (event) {
            // The link to the host dropped: the room is saying Hello again until the window closes.
            is MembershipEvent.Partitioned -> Unit
            // This room's own seat came back; state resync follows.
            is MembershipEvent.Resumed -> Unit
            // The seat was gone, so the host admitted this room afresh: still in session, but a new
            // membership. Drop what was tied to the old seat. The roster rebuilds as Joined/Left.
            is MembershipEvent.Readmitted -> Unit
            // The host went quiet and came back with no tear: no Hello was needed, the seat is as it was.
            is MembershipEvent.Recovered -> Unit
            // The window closed, or the host refused the Hello. WHY is in the reason's code.
            // See classifyRejectCodeSample.
            is MembershipEvent.HostLost -> Unit
            else -> Unit
        }
    }
}

/**
 * Decide whether a host's refusal is worth retrying. Branch on [RejectCode], not on the free-text
 * reason — and treat anything you don't recognise as retryable, because a peer that predates typed
 * codes (or a newer one that added its own) surfaces [RejectCode.Unknown].
 */
public fun classifyRejectCodeSample(reason: FailureReason.Refused): Boolean =
    when (reason.code) {
        // Terminal: the wrong room, a protocol version the host can't speak, a key proof that
        // failed, or another device holding this peer id. The same Hello fails the same way.
        RejectCode.RoomMismatch, RejectCode.ProtocolMismatch,
        RejectCode.BadProof, RejectCode.KeyConflict,
        -> false
        // Transient: the host could not do its own half of the exchange this time.
        RejectCode.HostFailed -> true
        // Anything else, including a code this build has never heard of.
        else -> reason.code.retryable
    }

/**
 * Hold a dropped peer's seat open for a grace window instead of evicting it immediately —
 * the server/host side of the rejoin flow. [JoinerReconnectController] already owns the timer,
 * the single-use token check, and the eviction; don't keep your own `pendingSeats` map.
 */
public suspend fun holdTheSeatOpenSample(
    controller: JoinerReconnectController,
    dropped: PeerId,
    nowEpochMs: Long,
) {
    // The peer's link dropped: open (or refresh) its reconnect window rather than evicting.
    controller.onPeerUnresponsive(dropped, at = nowEpochMs)
    controller.events.collect { event ->
        when (event) {
            // The seat is reserved until event.expiresAt.
            is JoinerReconnectEvent.WindowOpened -> Unit
            // It came back in time — push an application-state snapshot to event.peerId.
            is JoinerReconnectEvent.Resumed -> Unit
            // Window elapsed; the seat is released and MembershipEvent.Left(PartitionExpired) follows.
            is JoinerReconnectEvent.WindowExpired -> Unit
        }
    }
}

/**
 * Show a peer as *paused* (its seat held) rather than gone, on every member — not just the host.
 * Don't track your own `lastSeen` map: [Room.events] and [Member.liveness] already say it.
 */
public suspend fun observePausedPeersSample(room: Room) {
    // room.roster.value.filter { it.liveness is Liveness.Partitioned } is the same fact, pull-style —
    // and each Partitioned carries windowExpiresAt, so the countdown needs no event replay.
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.Partitioned -> Unit // grey the seat out — this peer's link dropped
            // Held until event.expiresAt — but a later WindowOpened for the same peer supersedes
            // this one (a more authoritative deadline arrived), so keep the latest, not the first.
            is MembershipEvent.WindowOpened -> Unit
            is MembershipEvent.Recovered -> Unit // it returned inside the window — un-grey it
            // Gone for good: Normal (clean), PartitionExpired, or Unobserved (it left while this room's
            // link was down). Superseded(by) is the same device back under a new peer id: move
            // whatever you keep by peer id over to `by`, not delete it.
            is MembershipEvent.Left -> Unit
            else -> Unit
        }
    }
}

/**
 * Retry a failed dial with full-jitter exponential back-off. [random] is injected so tests can
 * seed it; production passes [Random.Default]. Don't hand-roll a fixed delay table.
 */
public suspend fun retryWithBackoffSample(random: Random, dial: suspend () -> Boolean) {
    val backoff = ExponentialBackoff(base = 1.seconds, cap = 30.seconds, random = random)
    var attempt = 0
    while (!dial()) {
        delay(backoff.delay(attempt++)) // full-jitter; decorrelates simultaneous retriers
    }
}

/**
 * Drive a reconnect banner / terminal-error decision from the reason kuilt already classifies,
 * instead of re-deriving your own transient/unrecoverable buckets. [ReconnectReason] says why the
 * link is down while a window is open; [FailureReason] says why the session ended for good.
 *
 * Check the `localFabric` tag **first**. Both events carry this peer's own
 * [Room.localFabric] as it stood when they were emitted, and when that is
 * [FabricAvailability.Unavailable] the event is not evidence about the peer it names — our own end
 * of the fabric was down, so their silence says nothing about them. Without that branch a joiner
 * whose own radio died renders "lost the host" (#1712).
 */
public suspend fun reconnectBannerSample(room: Room) {
    room.events.collect { event ->
        // The tag inverts attribution only for silence *we* observed — our own detector, or our own
        // link tearing. `HostLost` is always that. `Partitioned` is not: on a joiner in a 3+-peer
        // room the host relays "peer C paused", and that report is host-authoritative — it arrived
        // over a link working well enough to deliver it, so an `Unavailable` tag there says our end
        // was down when we *processed* the report, not that the report is unfounded. Suppressing it
        // would leave C shown as present while the host holds its seat open.
        //
        // The event carries no provenance field, and `Room` exposes no host id, so a consumer cannot
        // tell the two apart from the event alone. In a two-peer session it does not matter (the only
        // peer you watch *is* the host). In a larger room, scope this to peers you observe yourself.
        val ourOwnEndWasDown = when (event) {
            is MembershipEvent.HostLost -> event.localFabric is FabricAvailability.Unavailable
            else -> false
        }
        when {
            // First branch, deliberately: never say "lost the host" when *we* are the ones offline.
            ourOwnEndWasDown -> Unit // "You're offline — check your connection"
            event is MembershipEvent.Partitioned -> when (event.reason) {
                ReconnectReason.LinkTimeout, ReconnectReason.TransportClosed -> Unit // "Reconnecting…"
                ReconnectReason.Backpressure -> Unit // "Connection congested…"
            }
            event is MembershipEvent.HostLost -> when (val reason = event.reason) {
                FailureReason.WindowExpired -> Unit // "Lost the host — rejoin"
                FailureReason.Unrecoverable -> Unit // "Can't reconnect — return to lobby"
                is FailureReason.Refused -> Unit // show reason.message (auth-expired / version, …)
            }
            else -> Unit
        }
    }
}

/**
 * Tell "**you** are offline" apart from "**they** are offline". Every other member of the presence
 * vocabulary names somebody else, so a peer that loses its own network blames its peers.
 * [Room.localFabric] is the level — a `StateFlow`, so a late reader cannot miss a drop — and
 * [MembershipEvent.LocalFabricLost] / [MembershipEvent.LocalFabricRestored] are the notifications
 * that it moved. Don't reach past [Room] into a transport-specific path monitor.
 */
public suspend fun localFabricBannerSample(room: Room) {
    // Bind the banner to the LEVEL, not to the edges. A StateFlow replays its current value to a late
    // collector, so this cannot miss a drop that happened before you subscribed, and it keeps the UI
    // reading the authoritative surface rather than a notification that may already be superseded.
    room.localFabric.collect { availability ->
        when (availability) {
            FabricAvailability.Available -> Unit // no banner
            is FabricAvailability.Unavailable -> Unit // "You're offline" — this room's fabric, not the device
            is FabricAvailability.Unknown -> Unit // kuilt cannot tell on this fabric — say nothing
        }
    }
    // The edges are for things a level cannot express — logging the transport's own words, or firing a
    // one-shot. Only transitions into Unavailable and into Available emit; a move into Unknown emits
    // nothing, because "we stopped being able to tell" is not a loss. Re-read the level when handling
    // one: under a rapid flap the level may already be ahead of the edge in your hand.
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.LocalFabricLost ->
                Unit // event.reason is the transport's own words; room.localFabric.value is the truth now
            is MembershipEvent.LocalFabricRestored -> Unit // may arrive with no preceding Lost
            else -> Unit
        }
    }
}

/**
 * Handle all three ways a member's wait for the session can end (#1483).
 *
 * [ElectionLobby.awaitRoom] does not only return a [Room]. The peer that was hosting can leave the
 * roster, at which point this peer becomes the elected host — nothing has collapsed, and the
 * recovery is [ElectionLobby.start] on the **same** lobby, not a re-election. [admission] is what the
 * room decides newcomers with if this peer ends up hosting.
 */
public suspend fun handleEveryElectionOutcomeSample(lobby: ElectionLobby, admission: HostAdmission): Room? =
    when (val outcome = lobby.awaitRoom(memberName = "Player 2")) {
        is ElectionOutcome.Adopted -> outcome.room
        // The hosting peer left and this peer is now the elected host, with the co-members still
        // parked in their own awaitRoom on the SAME seam — so they ack this freeze round at once.
        // Re-running electLobby(...) would start a SECOND lobby beside this one, which strands them on
        // most looms; calling leave() first would close the shared seam and collapse them.
        ElectionOutcome.BecameHost -> lobby.start(memberName = "Player 2", admission = admission)
        // A genuine mid-2PC collapse: the co-electors are gone. Retryable — leave this lobby, then
        // re-run electLobby(...). A lobby left open stays on the mesh and can hold a host's start().
        is ElectionOutcome.Torn -> {
            lobby.leave()
            null
        }
    }

/** Send arbitrary bytes, including reserved Room prefixes, inside a named channel. */
public suspend fun arbitraryRoomPayloadSample(room: Room, peer: PeerId, payload: ByteArray) {
    room.channel("data").sendTo(peer, payload)
}

/**
 * Send a last frame and leave, using a `delay` only where the fabric cannot do better.
 *
 * Returns `true` when the leave cannot have thrown the frame away. That is not the member receiving it:
 * a sender that needs receipt has the member answer.
 */
public suspend fun flushThenLeaveSample(
    room: Room,
    member: PeerId,
    lastFrame: ByteArray,
    delayYouHadBefore: Duration,
): Boolean {
    room.sendTo(member, lastFrame)
    val outcome = room.flush(2.seconds)
    // Most rooms answer Unsupported today, at once. Keep your delay or an ack until your fabric answers.
    if (outcome == FlushOutcome.Unsupported) delay(delayYouHadBefore)
    room.leave()
    return when (outcome) {
        FlushOutcome.Drained -> true
        // Not proven, not lost. This fabric cannot tell, a link stalled, or the room already ended.
        FlushOutcome.Unsupported, FlushOutcome.TimedOut, is FlushOutcome.Failed -> false
    }
}

/**
 * Decide who gets into a room you host, and for how long each may come back without being asked
 * again. A friend's pass is good in any room you host and never runs out; a stranger's is good in
 * this room for an hour after it is issued, renewed every half hour while the stranger stays
 * connected; a device the app has blocked gets the app's own refusal
 * code, until the app unblocks it.
 *
 * Revoking and blocking are separate. Revoking a key voids the passes it already holds, so it has to
 * knock again as a newcomer; it is not a ban, and this policy lets a revoked friend straight back in
 * with a fresh pass. [blocked] is what keeps a device out: the app's own set, which it can undo, held
 * in a `StateFlow` because the app changes the set while hosting, so the read must be live. Keep the
 * set across restarts as you keep the revocations: a forgotten block lets a revoked device back in as
 * a newcomer. To keep a device out from its next return, block it and revoke it: blocking alone
 * leaves a held seat or a pass it holds working, and revoking alone only sends it back to this
 * policy. Neither removes a device that is in the room now.
 */
public suspend fun hostAdmissionSample(
    friends: Set<DidKey>,
    blocked: StateFlow<Set<DidKey>>,
    store: DurableStore,
): HostAdmission {
    // RejectCode is open. The joiner does not know this code, so it arrives as
    // RejectCode.Unknown("not-welcome", retryable = false), and a reconnecting device stops knocking.
    val notWelcome = object : RejectCode {
        override val id: String = "not-welcome"
        override val retryable: Boolean = false
    }
    // The room checks returning passes against this list. Kept in the store, since a friend's pass
    // never expires: an in-memory list forgets on restart, and the voided passes work again.
    val revocations = RevocationList.open(store)
    val policy = AdmissionPolicy { request ->
        // `key` is the device key the joiner proved it holds. `displayName` is only its say-so.
        when {
            // The app's blocked set, never the revocations: a revocation cannot be taken back, so
            // refusing every revoked key here would ban it for good.
            request.key in blocked.value -> AdmissionDecision.Reject(notWelcome)
            request.key in friends -> AdmissionDecision.Admit(GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer))
            else -> AdmissionDecision.Admit(GrantTerms(readmitFor = 1.hours))
        }
    }
    return HostAdmission(policy, revocations)
}

/**
 * Stop a lost or stolen device coming back on the pass a room you host gave it: revoke its key in the
 * revocations the room's [HostAdmission] holds, which are the ones it checks every returning pass
 * against. Every pass issued to that key before the cutoff is void from then on. When the device next
 * says Hello your admission policy decides it as a newcomer, and a seat the room still holds for it is
 * let go rather than handed back.
 *
 * It removes nobody. A device in the room now keeps its seat, but the host stops renewing its pass, so
 * the pass runs out. Nor is it a ban: if the policy admits the device again, the fresh pass it gets
 * works. Refuse it in the policy as well to keep it out ([hostAdmissionSample]).
 */
public suspend fun revokeALostDeviceSample(admission: HostAdmission, lost: DidKey, clock: () -> Instant) {
    // One past the clock, so a pass issued in this same millisecond is void too.
    admission.revocations.revoke(lost, notBefore = clock().toEpochMilliseconds() + 1)
}

/**
 * The whole of an app's part in a device coming back: one [DeviceKeyStore] per device, on the factory.
 *
 * Every room the factory makes proves this device's key when it says Hello, and the host hands back
 * the seat it holds for that key: after a torn link, through the room's own reconnect, and after an
 * app kill, through the launch's ordinary `join`. `RoomConformanceSuite`'s return rows check that a
 * room comes back by itself, through its own reconnect, inside a bounded wait.
 */
public suspend fun returnIsAutomaticSample(
    loom: Loom,
    scope: CoroutineScope,
    clock: () -> Instant,
    keyStore: DeviceKeyStore,
    tag: Tag,
): Room {
    // One store per device, opened once at launch and shared by every room: it IS the device. In an
    // app that is DeviceKeyStore.platform(…); a fresh InMemoryDeviceKeyStore() every launch makes a
    // new device every launch, and nothing comes back.
    val factory = SeamRoomFactory(loom = loom, scope = scope, clock = clock, deviceKeyStore = keyStore)
    // The first join and the join after an app kill are the same call. A torn link needs none: keep
    // the room, and on a loom that heals its seam in place it says Hello again by itself.
    return factory.join(tag, memberName = "Ada")
}

/**
 * Tell a member who came back from a stranger, on the host or on anyone else in the room. The key the
 * returning device proved decides it, so these events are the host's word, not a guess from a name.
 */
public suspend fun whoCameBackSample(room: Room) {
    room.events.collect { event ->
        when (event) {
            // The same seat under the same peer id: nothing on anyone's screen needs to change.
            is MembershipEvent.Resumed -> Unit
            is MembershipEvent.Left -> when (event.reason) {
                // The same device under a new peer id, which joins next. Move whatever you keyed on
                // event.peerId over to the reason's `by`: that Joined is not a stranger. On this
                // room's own id it means another process of this device took the seat: this room is over.
                is LeaveReason.Superseded -> Unit // "Ada is back"
                else -> Unit // gone
            }
            // A new membership. Marked when the device came back on the pass its host gave it, after
            // its seat was let go: move whatever you keyed on the mark's `previousId` over to
            // event.member.id (it is null once the host has forgotten the old id). Unmarked, it is a
            // newcomer the host's admission policy let in.
            is MembershipEvent.Joined -> when (event.returning) {
                null -> Unit // a stranger
                else -> Unit // "Ada is back", on her pass
            }
            else -> Unit
        }
    }
}
