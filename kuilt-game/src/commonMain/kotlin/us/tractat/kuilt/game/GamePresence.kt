@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlin.ExperimentalStdlibApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.EphemeralEntry
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.NodeId

/** Marker value stored under a replica's slot to declare "I am the host". */
private const val HOST_DECLARED = "host"

/** Marker value stored under a replica's slot to declare "I am present (a non-host participant)". */
private const val PRESENT_DECLARED = "present"

/** Marker value stored under a replica's slot to declare "I intend to spectate (non-voting learner)". */
private const val SPECTATE_DECLARED = "spectate"

/**
 * Marker value stored under a replica's slot to declare "I am voluntarily leaving".
 *
 * Published by a voter that calls [GameSession.leave] to signal a graceful departure to the
 * host. The host observes this via [vacaters] and immediately evicts the voter via
 * [RaftNode.changeMembership] without waiting the [HeartbeatConfig.reconnectWindow] — the
 * vacate signal is the explicit path; the dead-man's-switch timeout is the crash-only fallback.
 */
private const val VACATE_DECLARED = "vacate"

/**
 * Prefix for the value stored under the host's slot when admission is closed.
 *
 * The full value is `"$ADMISSION_CLOSED_PREFIX<hex>"` — the final voter set serialized
 * as CBOR (a list of [NodeId] string values) and hex-encoded. The hex body is `[0-9a-f]`
 * only, so it can carry any [NodeId] content — including `','` or `':sc'` — without
 * colliding with the prefix or the [SPECTATORS_CLOSED_SUFFIX] framing.
 */
private const val ADMISSION_CLOSED_PREFIX = "admission-closed:"

/**
 * Suffix appended to the host's slot value when the spectator gallery is closed.
 *
 * Applied to whatever the current host slot holds (e.g. `"host"`, `"admission-closed:…"`)
 * to indicate that no further spectators will be admitted. The suffix is chosen to be
 * structurally distinct from all other marker values.
 */
private const val SPECTATORS_CLOSED_SUFFIX = ":sc"

/** Serializer for the admission-closed voter list — CBOR bytes, hex-encoded into the slot string. */
private val VOTER_LIST_SERIALIZER = ListSerializer(String.serializer())

/**
 * Lobby presence over [seam], backed by an [EphemeralMap] replicated by [Quilter].
 *
 * Carries each peer's host-declaration flag so the game host entry point can fail
 * fast when a duplicate host is detected, and the host's admission-closed signal so
 * [gameJoin] can throw [RosterFullException] when the roster is already full.
 *
 * **Dedicated seam required.** Pass a [us.tractat.kuilt.core.MuxSeam] channel, not the
 * Raft seam — [Seam.incoming] is single-collection (ADR-034). Task 6 wires this to
 * the game host entry point.
 *
 * @param seam the [Seam] to replicate presence over.
 * @param scope the [CoroutineScope] whose [kotlinx.coroutines.Job] parents the
 *   replicator's owned child job. In tests, pass `backgroundScope` from
 *   [kotlinx.coroutines.test.TestScope] so the Quilter's infinite collectors cancel
 *   cleanly at test end.
 * @param expectVirtualTime suppress the [Quilter] TestDispatcher guard warning; set
 *   `true` in tests that run under [kotlinx.coroutines.test.UnconfinedTestDispatcher].
 */
public class GamePresence(
    seam: Seam,
    private val presenceScope: CoroutineScope,
    expectVirtualTime: Boolean = false,
) {
    private val quilter: Quilter<EphemeralMap<String>> = Quilter(
        seam = seam,
        initial = EphemeralMap.empty(),
        valueSerializer = EphemeralMap.serializer(String.serializer()),
        scope = presenceScope,
        config = QuilterConfig(expectVirtualTime = expectVirtualTime),
    )

    /** The [ReplicaId] assigned to this peer by the underlying [Quilter]. */
    public val replica: ReplicaId get() = quilter.replica

    /**
     * The set of replicas that have announced themselves on this presence channel — every
     * replica that has called [declareHost] or [declarePresent], as observed in the converged
     * map.
     *
     * This is the convergence signal the game host entry point waits on: once it contains an
     * entry for every connected peer, the host has heard everyone's declaration and can check
     * for a duplicate host against a genuinely-exchanged view rather than a fixed time window.
     */
    public val announced: StateFlow<Set<ReplicaId>> =
        quilter.state
            .map { it.entries.keys }
            .stateIn(presenceScope, SharingStarted.Eagerly, quilter.state.value.entries.keys)

    /**
     * The final voter set once admission has closed on this presence channel, `null` until then.
     *
     * Driven by [declareAdmissionClosed] on the host side; observed by [gameJoin] to detect
     * roster-full rejections. The value is `null` while the admission loop is still running or
     * has not yet converged. Once it becomes non-null it never reverts — the signal is monotone.
     */
    public val admissionClosed: StateFlow<Set<NodeId>?> =
        quilter.state
            .map { map -> admissionClosedFrom(map) }
            .stateIn(presenceScope, SharingStarted.Eagerly, admissionClosedFrom(quilter.state.value))

    /**
     * `true` once the host has signalled that the spectator gallery is closed — either because
     * spectators are disabled or because [maxSpectators] has been reached.
     *
     * Driven by [declareSpectatorsClosed] on the host side; observed by [gameSpectate] to detect
     * and throw [SpectatorsClosedException]. `false` until then; once `true`, never reverts.
     */
    public val spectatorsClosed: StateFlow<Boolean> =
        quilter.state
            .map { map -> spectatorsClosedFrom(map) }
            .stateIn(presenceScope, SharingStarted.Eagerly, spectatorsClosedFrom(quilter.state.value))

    /** Declare this peer as the game host. */
    public fun declareHost(): Unit = declare(HOST_DECLARED)

    /**
     * Declare this peer as a non-host participant ("present").
     *
     * Every peer that does *not* call [declareHost] should call this so the host's
     * convergence wait can observe contact with it — a connected peer that never announces
     * would otherwise hold the host's duplicate-host check open until its timeout elapses.
     */
    public fun declarePresent(): Unit = declare(PRESENT_DECLARED)

    /**
     * Declare this peer as a spectator (permanent non-voting learner).
     *
     * The host observes this declaration and either admits the replica as a learner-only
     * cluster member or rejects it if spectators are disabled or the cap is reached.
     * Call this instead of [declarePresent] from [gameSpectate].
     */
    public fun declareSpectate(): Unit = declare(SPECTATE_DECLARED)

    /**
     * Declare that this voter is voluntarily leaving the session.
     *
     * The host observes this via [vacaters] and immediately evicts the voter via
     * [RaftNode.changeMembership], without waiting the reconnect window. Call this from
     * [GameSession.leave] before closing the session so the seat is freed promptly.
     *
     * The vacate signal coexists with [PRESENT_DECLARED]: after calling this the replica's
     * slot holds [VACATE_DECLARED], overwriting [PRESENT_DECLARED]. The [Quilter] delivers
     * the delta to the host.
     */
    public fun declareVacate(): Unit = declare(VACATE_DECLARED)

    /**
     * Publishes the admission-closed signal on the host's presence slot, replacing
     * the `"host"` marker with an encoded form that carries the final voter set.
     *
     * Call this once the host's admission loop reaches `peerCount` and exits — both
     * in [ReturnPolicy.FullMembership] mode (synchronous path) and in
     * [ReturnPolicy.Quorum] mode (background loop). The signal converges to every
     * connected peer via the [Quilter] delta-exchange, where [gameJoin] observes it
     * via [admissionClosed].
     */
    public fun declareAdmissionClosed(voters: Set<NodeId>) {
        // Hex-encoded CBOR: the body is [0-9a-f] only, so it can carry any NodeId content —
        // commas, ':sc', anything — without colliding with the prefix/suffix framing. It does not
        // depend on the board, so it is encoded here rather than inside the locked section.
        val body = Cbor.encodeToByteArray(VOTER_LIST_SERIALIZER, voters.map { it.value }).toHexString()
        // The `:sc` read must share a critical section with the write, or a concurrent
        // declareSpectatorsClosed lands between them and this write retracts its signal (#2083).
        declareIf { board ->
            val scSuffix = if (spectatorsClosedFrom(board)) SPECTATORS_CLOSED_SUFFIX else ""
            ADMISSION_CLOSED_PREFIX + body + scSuffix
        }
    }

    /**
     * Publishes the spectators-closed signal on the host's presence slot.
     *
     * Call this when spectators are disabled ([gameHost] `allowSpectators = false`) or when
     * [maxSpectators] has been reached. Appends [SPECTATORS_CLOSED_SUFFIX] to whatever value
     * the host's slot currently holds, so the signal coexists with [admissionClosed].
     *
     * The signal is monotone — once published it is never retracted.
     */
    public fun declareSpectatorsClosed() {
        declareIf { board ->
            val current = board.entries[quilter.replica]?.value ?: HOST_DECLARED
            if (current.endsWith(SPECTATORS_CLOSED_SUFFIX)) null else current + SPECTATORS_CLOSED_SUFFIX
        }
    }

    /**
     * The converged set of replicas that have declared themselves as spectators.
     *
     * Returns replicas whose current slot value equals [SPECTATE_DECLARED]. Analogous to
     * [declaredHosts] but for the spectate path.
     */
    public fun spectators(): Set<ReplicaId> =
        quilter.state.value.entries
            .filterValues { entry -> entry.value == SPECTATE_DECLARED }
            .keys

    /**
     * The converged set of replicas that have declared a voluntary departure via [declareVacate].
     *
     * The host observes this to trigger immediate eviction without waiting the reconnect window.
     * Returns replicas whose current slot value equals [VACATE_DECLARED].
     */
    public fun vacaters(): Set<ReplicaId> =
        quilter.state.value.entries
            .filterValues { entry -> entry.value == VACATE_DECLARED }
            .keys

    /**
     * Re-opens admission by reverting the host's slot to [HOST_DECLARED].
     *
     * Called by the host after evicting a voter so that new [gameJoin] callers see
     * [admissionClosed] == `null` again and wait for the next admission rather than
     * immediately throwing [RosterFullException].
     *
     * This is safe to call multiple times — it is idempotent if the host slot already
     * holds [HOST_DECLARED].
     */
    public fun declareAdmissionOpen() {
        declareIf { board ->
            if (board.entries[quilter.replica]?.value == HOST_DECLARED) null else HOST_DECLARED
        }
    }

    /** Publish [value] under this peer's slot at the next clock. */
    private fun declare(value: String): Unit = declareIf { value }

    /**
     * The one path that writes this peer's presence slot: read the board, decide what to publish,
     * publish it — as a single atomic step.
     *
     * [next] receives the current board and returns the value to publish, or `null` to publish
     * nothing. It runs inside [Quilter.mutateOrSkip], so the board it sees is the board its patch
     * lands on: no other declaration can slip between the decision and the write.
     *
     * **Both halves have to be in here, and both for the same reason (#2083).** The board is an
     * [EphemeralMap], whose join keeps the higher clock and, at an equal clock for one replica,
     * keeps the entry already applied. Reading the clock outside the write therefore drops a
     * declaration outright — two callers read `c`, both publish at `c + 1`, and only one survives.
     * Reading the *value* outside is the same defect one layer up: [declareSpectatorsClosed] and
     * [declareAdmissionClosed] both run on the host, from independent coroutines, and both derive
     * what they publish from what is already there — so a stale read makes the loser overwrite a
     * signal that is documented never to be retracted. Guarding only the write leaves that intact.
     *
     * [next] runs in the Quilter's locked section, so it must stay pure and cheap: derive the
     * value from [board] only, and do any encoding before the call.
     *
     * A `null` decision publishes nothing at all — [Quilter.mutateOrSkip] returns without touching
     * the board or the wire, and the refusal decision itself stays inside the critical section. The
     * decision has to be in there: [declareAdmissionOpen] must leave admission open *when it
     * returns*, so a fast-refuse ahead of the lock could refuse against a board that has already
     * moved on. Until #2090 the refusal was expressed as an identity patch — the board's bottom
     * element, which every `piece` returns unchanged — holding the same guarantee at the cost of
     * one empty delta frame per refusal; `mutateOrSkip` keeps the guarantee and drops the frame.
     */
    private fun declareIf(next: (EphemeralMap<String>) -> String?) {
        quilter.mutateOrSkip { board ->
            next(board)?.let { value ->
                val nextClock = (board.entries[quilter.replica]?.clock ?: 0L) + 1L
                Patch(board.put(quilter.replica, value, nextClock))
            }
        }
    }

    /**
     * This peer's own presence slot — the value it last declared and the clock that
     * declaration was published at — or `null` if it has never declared.
     *
     * Test observability only. The two properties module tests need are exactly the two the
     * public [StateFlow]s cannot give them: the raw [EphemeralEntry.clock] (which counts this
     * replica's declarations, so `clock < declarations` *is* a lost update), and a slot read
     * that does not go through a `stateIn` collector — under a virtual-time dispatcher those
     * collectors have not run, so [admissionClosed] / [spectatorsClosed] still hold their
     * construction-time seed (#2083).
     */
    internal fun selfSlot(): EphemeralEntry<String>? = quilter.state.value.entries[quilter.replica]

    /**
     * The converged set of replicas that have declared themselves host.
     *
     * Returns the live (non-null-valued) entries — entries are not TTL-filtered here
     * because presence uses only [EphemeralMap.entries] directly (no receive-time
     * tracking). In this context TTL expiry is not required; the set reflects all
     * replicas that have called [declareHost] during this session.
     */
    public fun declaredHosts(): Set<ReplicaId> =
        quilter.state.value.entries
            .filterValues { entry -> entry.value == HOST_DECLARED }
            .keys

    private fun admissionClosedFrom(map: EphemeralMap<String>): Set<NodeId>? =
        map.entries.values
            .firstOrNull { entry -> entry.value?.startsWith(ADMISSION_CLOSED_PREFIX) == true }
            ?.value
            ?.removeSuffix(SPECTATORS_CLOSED_SUFFIX)
            ?.removePrefix(ADMISSION_CLOSED_PREFIX)
            ?.let { hex -> Cbor.decodeFromByteArray(VOTER_LIST_SERIALIZER, hex.hexToByteArray()) }
            ?.map { NodeId(it) }
            ?.toSet()

    private fun spectatorsClosedFrom(map: EphemeralMap<String>): Boolean =
        map.entries.values.any { entry -> entry.value?.endsWith(SPECTATORS_CLOSED_SUFFIX) == true }
}
