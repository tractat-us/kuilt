package us.tractat.kuilt.session.election

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamCollapsedException
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.raceCollapse
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.session.PerPeerSeam
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.election.SeamElectionLobby")

/**
 * Internal abort signal for the member path: the peer that was hosting left, and [roster] (the peer
 * set at that instant) still holds co-members, so this is a **promotion**, not a collapse. Never
 * escapes [SeamElectionLobby.awaitRoom] — it is converted to [ElectionOutcome.BecameHost] there. It
 * exists only because [SeamElectionLobby.guardElection] aborts its body by completing exceptionally.
 */
private class PromotedToHostSignal(val roster: Set<PeerId>) :
    Exception("promoted to host mid-election: roster=${roster.map { it.value }}")

/**
 * [Seam]-backed [ElectionLobby]. Owns the woven mesh [seam] until a [Room] adopts it.
 *
 * A single collector drains [Seam.incoming], decoding [LobbyMessage]s and republishing them on
 * [lobbyMessages] for [start]/[awaitRoom] to consume. At adopt time that collector is cancelled and
 * joined BEFORE [SeamRoomFactory.adopt] starts the room's own collector — preserving the
 * single-collection `incoming` contract (ADR-034).
 */
internal class SeamElectionLobby(
    private val seam: Seam,
    private val factory: SeamRoomFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val roomKey: String?,
    private val freezeTimeout: Duration = 10.seconds,
    private val commitTimeout: Duration = 10.seconds,
) : ElectionLobby {

    override val selfId: PeerId = seam.selfId

    // Seam.peers already includes selfId (documented invariant) — no ∪ {self} needed.
    override val peers: StateFlow<Set<PeerId>> = seam.peers

    // Lobby-scoped history, not per-call: recorded from lobby construction, which is strictly before
    // the app reads `host.value` and picks start() vs awaitRoom(). A host that leaves inside that
    // window is therefore still counted, and awaitRoom can still report BecameHost.
    private val everHadCoMembers = MutableStateFlow(false)
    private val everSawAnotherHost = MutableStateFlow(false)

    /** Latch the two history bits [hostLeftSignal] needs. Monotone; safe to call from any emission. */
    private fun recordLobbyHistory(roster: Set<PeerId>) {
        if (roster.size > 1) everHadCoMembers.value = true
        if (electHost(roster) != selfId) everSawAnotherHost.value = true
    }

    override val host: StateFlow<PeerId> =
        seam.peers
            // Piggyback on the eager collector `stateIn` already runs for the lobby's whole life,
            // rather than adding a second one with its own teardown.
            .onEach { recordLobbyHistory(it) }
            .map { electHost(it) }
            .stateIn(scope, SharingStarted.Eagerly, electHost(seam.peers.value))

    override val state: StateFlow<SeamState> = seam.state

    private val _lobbyMessages = MutableSharedFlow<Pair<PeerId, LobbyMessage>>(extraBufferCapacity = 64)
    private val lobbyMessages: SharedFlow<Pair<PeerId, LobbyMessage>> = _lobbyMessages.asSharedFlow()

    // Every inbound swatch is re-emitted here so per-co-elector [HeartbeatPartitionDetector]s (via
    // [PerPeerSeam]) can observe ping/pong + application traffic without contending for the
    // single-consumer [Seam.incoming] channel. Mirrors [SeamRoom]'s rawIncoming fan-out; the single
    // collector below preserves single-collection (ADR-034). Heartbeat frames fall out of
    // [_lobbyMessages] for free — [LobbyMessage.decode] returns null for them.
    private val rawIncoming = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)

    private val adoptMutex = Mutex()
    private var adopted = false
    private var epoch: Long = 0L

    private val collectorJob: Job = scope.launch {
        seam.incoming.collect { swatch ->
            rawIncoming.emit(swatch)
            val sender = swatch.sender ?: return@collect
            val msg = LobbyMessage.decode(swatch.toByteArray()) ?: return@collect
            _lobbyMessages.emit(sender to msg)
        }
    }

    override suspend fun start(memberName: String?): Room {
        // Host collapse (the transport-tear watcher in guardElection handles a fabric that latches Torn;
        // startElection itself handles the membership-drain case where the seam stays Woven).
        return try {
            // The freeze round runs INSIDE guardElection (collapse-abortable); adoption runs OUTSIDE it
            // (finding #1) so a collapse signal cannot cancel adoptRoom mid-commit and wedge the lobby.
            // Host monitors the current members (roster - self) for the silent-but-present collapse (#1480).
            guardElection(collapseSignals = listOf(coElectorLostSignal(peers.value - selfId))) {
                runHostElection()
            }
            adoptAfterCommit(SessionRole.Host, memberName)
        } catch (e: Throwable) {
            logger.debug { "lobby.start.exit self=${selfId.value} threw=${e::class.simpleName}: ${e.message}" }
            throw e
        }
    }

    /** Run the host freeze round until it commits (broadcasting `Commit`); adoption is the caller's job. */
    private suspend fun runHostElection() {
        // Read electHost(peers.value) rather than `host.value`: `host` is a stateIn-derived view of
        // the same roster and is at least one collector hop behind it, so a peer that has just been
        // told it was promoted (hostLeftSignal decides from `peers`) would otherwise race its own
        // documented BecameHost recovery and get NotElectedHostException.
        if (electHost(peers.value) != selfId) {
            throw NotElectedHostException("not the elected host: host=${electHost(peers.value)}, self=$selfId")
        }
        var everHadMembers = false
        while (true) {
            // Re-check role each attempt: a lower-id peer may have appeared between retries.
            if (electHost(peers.value) != selfId) {
                throw NotElectedHostException(
                    "lost host election mid-start: host=${electHost(peers.value)}, self=$selfId",
                )
            }
            val roster = peers.value
            val members = roster - selfId
            // Membership-drain abort (#1466 host path): if we were electing with members and the roster
            // has since collapsed to just us, surface a retryable signal instead of silently committing a
            // solo room. A host that started alone (never had members) still commits immediately below.
            if (members.isEmpty() && everHadMembers) {
                throw LobbyTornException(collapseReason())
            }
            if (members.isNotEmpty()) everHadMembers = true
            val myEpoch = ++epoch

            // awaitUnanimousAck subscribes to lobbyMessages BEFORE it broadcasts the Freeze, so a
            // FreezeAck landing in the replay-0 gap between broadcast and subscribe is never lost.
            val committed = withTimeoutOrNull(freezeTimeout) { awaitUnanimousAck(roster, members, myEpoch) } ?: false
            logger.info { "lobby.freeze-round self=${selfId.value} epoch=$myEpoch members=${members.map { it.value }} committed=$committed" }
            if (committed) {
                runCatchingCancellable {
                    seam.broadcast(LobbyMessage.encode(LobbyMessage.Commit(selfId.value, myEpoch)))
                }
                return
            }
            // Aborted (membership changed, lost host, or timed out): reopen and retry.
            runCatchingCancellable { seam.broadcast(LobbyMessage.encode(LobbyMessage.Reopen(myEpoch))) }
        }
    }

    /**
     * Broadcast the [LobbyMessage.Freeze] for [ackEpoch] and await a [LobbyMessage.FreezeAck] from
     * every peer in [members]. Returns true on unanimous ack; false (abort) if the peer set drifts
     * from [roster] (the set the Freeze advertised) or this peer stops being the elected host.
     *
     * **Subscribe-before-broadcast.** The ack/abort collectors are launched [CoroutineStart.UNDISPATCHED]
     * so each runs up to its `collect` subscription *before* control returns to broadcast the Freeze —
     * closing the window where an ack emitted on the replay-0 [lobbyMessages] would be dropped. The
     * membership baseline is [roster] (captured with [members] at Freeze time), not a later
     * `peers.value` re-read, so a peer that leaves between capture and here is detected rather than
     * silently awaited forever.
     */
    private suspend fun awaitUnanimousAck(roster: Set<PeerId>, members: Set<PeerId>, ackEpoch: Long): Boolean =
        coroutineScope {
            val outcome = CompletableDeferred<Boolean>()
            val needed = members.toMutableSet()
            val ackJob = launch(start = CoroutineStart.UNDISPATCHED) {
                lobbyMessages.collect { (sender, msg) ->
                    if (msg is LobbyMessage.FreezeAck && msg.hostId == selfId.value && msg.epoch == ackEpoch) {
                        needed.remove(sender)
                        if (needed.isEmpty()) outcome.complete(true)
                    }
                }
            }
            val membershipJob = launch(start = CoroutineStart.UNDISPATCHED) {
                peers.collect { if (it != roster) outcome.complete(false) }
            }
            val hostJob = launch(start = CoroutineStart.UNDISPATCHED) {
                host.collect { if (it != selfId) outcome.complete(false) }
            }
            // Subscriptions are now live (UNDISPATCHED ran each collector to its first suspend) — only
            // now broadcast the Freeze, so no FreezeAck can land in the replay-0 gap before we listen.
            runCatchingCancellable {
                seam.broadcast(
                    LobbyMessage.encode(
                        LobbyMessage.Freeze(selfId.value, roster.map { it.value }.toSet(), ackEpoch),
                    ),
                )
            }
            if (needed.isEmpty()) outcome.complete(true) // no members → immediate commit
            val result = outcome.await()
            ackJob.cancel(); membershipJob.cancel(); hostJob.cancel()
            result
        }

    override suspend fun awaitRoom(memberName: String?): ElectionOutcome {
        return try {
            guardElection(
                collapseSignals = listOf(
                    hostLeftSignal(),
                    // Silent-but-present collapse (#1480/#1478): the elected host stops answering
                    // heartbeat pings while `seam.peers` still lists it and `state` stays Woven — the
                    // host→self signal above never fires. The lobby heartbeat is the only detector.
                    coElectorLostSignal(setOf(host.value)),
                ),
            ) {
                runMemberElection()
            }
            // Adoption OUTSIDE guardElection (finding #1): once the Commit is received the race is over,
            // so the became-host/tear collapse signals can no longer cancel adoptRoom mid-commit.
            ElectionOutcome.Adopted(adoptAfterCommit(SessionRole.Joiner, memberName))
        } catch (e: PromotedToHostSignal) {
            logger.info { "lobby.awaitRoom.promoted self=${selfId.value} roster=${e.roster.map { it.value }} → BecameHost (recovery: start() on this lobby)" }
            ElectionOutcome.BecameHost
        } catch (e: LobbyTornException) {
            logger.debug { "lobby.awaitRoom.exit self=${selfId.value} collapsed: ${e.reason}" }
            ElectionOutcome.Torn(e.reason)
        } catch (e: Throwable) {
            logger.debug { "lobby.awaitRoom.exit self=${selfId.value} threw=${e::class.simpleName}: ${e.message}" }
            throw e
        }
    }

    /**
     * The member-path abort for "the peer that was hosting is gone" (#1466), split by what is *left*
     * (#1483). Either way this member can never receive a `Freeze` from a host that is now itself, so
     * the wait must end — but the two endings are not the same event:
     *
     * - **co-members still present** → [PromotedToHostSignal] → [ElectionOutcome.BecameHost]. Nothing
     *   collapsed; this peer simply inherited the host role and should call [start] on this lobby.
     * - **roster drained to `{self}`** → [LobbyTornException] → [ElectionOutcome.Torn]. The #1466
     *   membership drain: there is nobody left to host for.
     *
     * ## The weave-in discriminator
     *
     * `host` is `electHost(peers)`, and `peers` **grows** as the mesh weaves in — so `host == self` is
     * *also* the state of a peer that is merely the lowest id it has seen **so far**. A bare
     * `host.first { it == selfId }` (what shipped before #1483) fires on that transient, at the very
     * first emission, before any lower peer has propagated. Every member that entered `awaitRoom`
     * while transiently lowest was told the lobby had collapsed.
     *
     * A roster-size test cannot separate the two: `{self, higher-peer}` is a non-trivial roster in
     * both. What separates them is **history**, the same shape the host path's `everHadMembers` latch
     * uses: a promotion means some *other* peer was the elected host and then left. So the promotion
     * arm gates on [everSawAnotherHost], and only then treats `host == self` as terminal. A peer that
     * has never seen another host has not been promoted — it keeps waiting, and a lower peer weaving
     * in later just moves `host` away again.
     *
     * ## Two latches, and why history is lobby-scoped
     *
     * [everSawAnotherHost] arms the **promotion** arm; [everHadCoMembers] arms the **drain** arm
     * independently. They are not the same bit, and collapsing them regresses #1466: a peer that was
     * *always* the lowest id it could see (roster `{self, higher-peer}`) has never seen another host,
     * so a single latch would leave it with no abort at all — it would suspend forever when that peer
     * then left, where the pre-#1483 code at least terminated. [everHadCoMembers] is what ends that
     * wait, as [ElectionOutcome.Torn].
     *
     * Both are recorded from **lobby construction**, not from the [awaitRoom] call. The app reads
     * `host.value` and *then* decides between [start] and [awaitRoom]; a host that leaves inside that
     * window is invisible to a per-call latch, which would report neither outcome and strand the
     * co-member waiting on this peer. The lobby saw that host, so the lobby remembers it.
     *
     * What remains undecidable from local state: a lobby **constructed after** the host had already
     * gone looks exactly like weave-in, and waits. That is the caller's `host == selfId` check to
     * make, reactively — see [ElectionLobby.host].
     */
    private fun hostLeftSignal(): suspend () -> Throwable = {
        val roster = peers.first { current ->
            recordLobbyHistory(current)
            when {
                // Membership drain (#1466): the roster held co-electors and now holds none. Gated on
                // the history latch so a lobby nobody has joined YET keeps waiting rather than
                // aborting — an empty lobby is a lobby doing its job.
                (current - selfId).isEmpty() -> everHadCoMembers.value
                // Promotion (#1483): some other peer was the elected host, and now this peer is.
                electHost(current) == selfId -> everSawAnotherHost.value
                else -> false
            }
        }
        if ((roster - selfId).isEmpty()) {
            logger.info { "lobby.awaitRoom.collapse-signal self=${selfId.value} roster drained to {self} → Torn" }
            LobbyTornException(collapseReason())
        } else {
            PromotedToHostSignal(roster)
        }
    }

    /** Await a Freeze from the elected host and ack it until a `Commit` arrives; adoption is the caller's job. */
    private suspend fun runMemberElection() {
        while (true) {
            // Await a Freeze from THIS peer's currently-elected host that names us in the roster.
            // Ignore a Freeze whose host is ourselves (a member never joins itself) or a foreign host.
            val freeze = lobbyMessages
                .first { (sender, msg) ->
                    msg is LobbyMessage.Freeze &&
                        msg.hostId != selfId.value &&
                        sender.value == msg.hostId &&
                        PeerId(msg.hostId) == host.value &&
                        selfId.value in msg.roster
                }
                .second as LobbyMessage.Freeze
            logger.info { "lobby.freeze-matched self=${selfId.value} from-host=${freeze.hostId} epoch=${freeze.epoch} → ack + await Commit" }

            // Subscribe-before-broadcast: establish the Commit/Reopen subscription (UNDISPATCHED, so it
            // reaches `collect` before we return) BEFORE broadcasting the ack. Otherwise a Commit emitted
            // on the replay-0 lobbyMessages between the ack and the subscribe is lost — and since the host
            // adopts (and stops broadcasting) right after Commit, the member would wait out commitTimeout
            // and then loop for a fresh Freeze that never comes, wedging it out of the session forever.
            val resolution = coroutineScope {
                val resolved = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(commitTimeout) {
                        lobbyMessages.first { (_, msg) ->
                            (msg is LobbyMessage.Commit && msg.hostId == freeze.hostId && msg.epoch == freeze.epoch) ||
                                (msg is LobbyMessage.Reopen && msg.epoch == freeze.epoch)
                        }.second
                    }
                }
                runCatchingCancellable {
                    seam.broadcast(LobbyMessage.encode(LobbyMessage.FreezeAck(freeze.hostId, freeze.epoch)))
                }
                resolved.await()
            }
            logger.info { "lobby.resolution self=${selfId.value} epoch=${freeze.epoch} → ${resolution?.let { it::class.simpleName } ?: "TIMEOUT (retry for fresh Freeze)"}" }
            if (resolution is LobbyMessage.Commit) {
                return
            }
            // Reopen or timeout: discard and await a fresh Freeze.
        }
    }

    /**
     * Run [body] (an election handshake) but abort it with [LobbyTornException] the instant the
     * election becomes impossible to complete. Two mid-2PC peer-set collapses (#1466), surfacing
     * differently at the seam layer, must both fire:
     *
     * - **Transport tear** — the underlying [seam] latches [SeamState.Torn] (e.g. a peerMesh losing its
     *   last link). Owned by [Seam.raceCollapse]'s always-present torn watcher (+ eager torn check).
     * - **Membership drain** — the observed hardware failure: `Seam.peers` drops to `{self}` (and [host]
     *   recomputes to `self`) but `state` never becomes `Torn`. A torn watcher alone never fires; the
     *   role-specific [collapseSignals] key on [host] instead.
     *
     * The torn dimension is delegated to the core [Seam.raceCollapse] primitive; its [SeamCollapsedException]
     * is re-thrown as the lobby's [LobbyTornException]. The membership dimension is **not** a bare
     * peers-size predicate — a lone host legitimately runs solo (`start` from an empty roster commits
     * immediately) — so `abortWhen` is disabled and the lobby composes its own [collapseSignals] on top
     * (member: `host` became self; the host path handles drain inside [startElection]'s `everHadMembers`
     * re-check). Each signal and [body] run as [CoroutineStart.UNDISPATCHED] siblings so a collapse already
     * true at entry, or racing the body, is never missed.
     *
     * Without this, [awaitRoomElection]'s Freeze-wait and [startElection]'s ack-wait suspend forever:
     * [lobbyMessages] is a standalone flow that never completes when [Seam.incoming] does, so nothing
     * else wakes the waiter. The retryable throw lets the caller re-run `electLobby` to rejoin / re-elect.
     *
     * @param collapseSignals each suspends until its abort condition holds, then returns the [Throwable]
     *   to abort with — [LobbyTornException] for a genuine collapse, or (member path only)
     *   [PromotedToHostSignal] when the host merely handed the role over by leaving. Role-specific
     *   (member: [hostLeftSignal]; host: lost all members).
     */
    private suspend fun <T> guardElection(
        collapseSignals: List<suspend () -> Throwable>,
        body: suspend () -> T,
    ): T =
        try {
            seam.raceCollapse(abortWhen = { false }) {
                if (collapseSignals.isEmpty()) body() else raceCollapseSignals(collapseSignals, body)
            }
        } catch (e: SeamCollapsedException) {
            throw LobbyTornException(e.reason)
        }

    /** Race [body] against the lobby's role-specific [collapseSignals]; the first to resolve wins. */
    private suspend fun <T> raceCollapseSignals(
        collapseSignals: List<suspend () -> Throwable>,
        body: suspend () -> T,
    ): T =
        coroutineScope {
            val outcome = CompletableDeferred<T>()
            val work = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    outcome.complete(body())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    outcome.completeExceptionally(e)
                }
            }
            val signalJobs = collapseSignals.map { signal ->
                launch(start = CoroutineStart.UNDISPATCHED) { outcome.completeExceptionally(signal()) }
            }
            try {
                outcome.await()
            } finally {
                work.cancel()
                signalJobs.forEach { it.cancel() }
            }
        }

    /**
     * A [guardElection] collapse-signal that catches the **present-but-silent** co-elector — the
     * #1478 root condition (a path-lost `waiting` connection that never fires a close): `seam.peers`
     * still lists the peer and `state` stays [SeamState.Woven], so no set-based abort (the Torn
     * watcher, the `host → self` / membership-drain signals) can see it. The blind spot only silence
     * detection covers.
     *
     * Runs one [HeartbeatPartitionDetector] per [monitored] peer over a [PerPeerSeam] fed from
     * [rawIncoming] (the same mechanism [SeamRoom] uses post-adopt), and suspends until the first
     * **[PartitionEvent.PeerLost]** — the terminal event *after* the reconnect window, NOT the earlier
     * [PartitionEvent.PeerUnresponsive] (aborting on a transient blip would force a needless full 2PC
     * re-elect). On loss it returns the existing [LobbyTornException]; no new abort path is invented.
     *
     * The detectors are owned by a child [Job] cancelled when the signal resolves or is cancelled by
     * [raceCollapseSignals] (whichever collapse fires first wins). [selfId] is filtered out; an empty
     * monitored set (a lone host) yields a signal that simply never fires.
     */
    private fun coElectorLostSignal(monitored: Set<PeerId>): suspend () -> LobbyTornException = {
        val watched = monitored - selfId
        coroutineScope {
            if (watched.isEmpty()) {
                awaitCancellation()
            }
            val detectorJob = Job(coroutineContext[Job])
            val detectorScope = CoroutineScope(coroutineContext + detectorJob)
            val detectors = watched.map { peer ->
                HeartbeatPartitionDetector(
                    link = PerPeerSeam(seam, peer, rawIncoming),
                    peerId = peer,
                    config = LOBBY_HEARTBEAT,
                    clock = clock,
                ).also { it.start(detectorScope) }
            }
            try {
                detectors.map { it.events }.merge()
                    .first { it is PartitionEvent.PeerLost }
                logger.info { "lobby.heartbeat.collapse-signal self=${selfId.value} PeerLost among ${watched.map { it.value }} → LobbyTornException" }
                LobbyTornException(collapseReason())
            } finally {
                detectorJob.cancel()
            }
        }
    }

    /** [CloseReason] to report for a membership-drain collapse: the seam's own if torn, else [CloseReason.Unreachable]. */
    private fun collapseReason(): CloseReason = (seam.state.value as? SeamState.Torn)?.reason ?: CloseReason.Unreachable

    override suspend fun leave() {
        adoptMutex.withLock {
            if (adopted) return // seam ownership transferred to the Room; do not close it.
            collectorJob.cancel()
            seam.close(CloseReason.Normal)
        }
    }

    /**
     * Adopt the committed seam as [role] — run OUTSIDE [guardElection] so no collapse watcher can cancel
     * it mid-commit (finding #1: cancelling [adoptRoom] after it latched `adopted` but before/while
     * [SeamRoomFactory.adopt] ran wedged the lobby — `adopted == true` with no Room, `leave()` a no-op, a
     * live orphaned seam, and any retry hitting `check(!adopted)`). By the time this runs the 2PC has
     * committed and the watchers are cancelled, so adoption is uninterrupted. A tear that latched *before*
     * we adopt is surfaced as a clean retryable [LobbyTornException]; a tear *during* the fast, now
     * uncancellable adopt is handled by the room's own `runTornWatcher` (a born-dead room), not a wedge.
     */
    private suspend fun adoptAfterCommit(role: SessionRole, memberName: String?): Room {
        (seam.state.value as? SeamState.Torn)?.let { throw LobbyTornException(it.reason) }
        return adoptRoom(role, memberName)
    }

    /**
     * Stop the lobby's `incoming` collector, then adopt the seam as [role]. Cancel-and-join guarantees
     * the lobby collector has fully stopped before the room starts its own collector (single-collection).
     * Callable at most once.
     */
    private suspend fun adoptRoom(role: SessionRole, memberName: String?): Room =
        adoptMutex.withLock {
            check(!adopted) { "lobby already adopted a room" }
            adopted = true
            collectorJob.cancelAndJoin()
            // reweave = { seam }: the adopted mesh seam self-heals in place — a fabric that re-forms
            // Woven→Weaving→Woven on a peer drop (and redials) rather than latching Torn. Returning the
            // SAME seam lets a joiner's host-link tear run the resume path (wait for Woven, re-present
            // the token) instead of dying on the immediate-terminal branch (#1618).
            //
            // This is the recovery half of #1618 Track A. On the phone that LOST its path, Track A
            // drives every live connection to PathLost, the #1478 grace expires, NwSeam evicts the host
            // from `peers`, and the detector reports TransportClosed — the one reason SeamRoom routes to
            // attemptReconnect. Without a reweave that lane ends at HostLost(Unrecoverable) with no
            // WindowOpened; with it, a path that returns inside the window resumes the same room instead
            // of collapsing it and forcing a re-election. A tear past the window still ends terminal.
            factory.adopt(seam, role, memberName = memberName, roomKey = roomKey, reweave = { seam })
        }

    private companion object {
        /**
         * Lobby-tuned heartbeat profile. The default [HeartbeatConfig] (5s/15s/60s) targets a
         * long-lived [Room]; its 60s reconnect window outlives the entire lobby, so a dead co-elector
         * would only surface long after the 10s freeze/commit timeouts. This aggressive profile
         * surfaces [PartitionEvent.PeerLost] in ~5-6s (timeout + one reconnect window) — inside the
         * freeze/commit timeouts, yet past a transient Wi-Fi-roam/tunnel blip so a brief hiccup does
         * not force a full 2PC re-elect.
         */
        private val LOBBY_HEARTBEAT = HeartbeatConfig(
            interval = 1.seconds,
            timeout = 3.seconds,
            reconnectWindow = 3.seconds,
        )
    }
}
