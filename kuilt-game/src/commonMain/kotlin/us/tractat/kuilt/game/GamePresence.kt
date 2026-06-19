@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.Seam
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
 * The full value is `"$ADMISSION_CLOSED_PREFIX<id1>,<id2>,…"` — the final voter set
 * encoded as a comma-separated list of [NodeId] string values.
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
        val scSuffix = if (spectatorsClosedFrom(quilter.state.value)) SPECTATORS_CLOSED_SUFFIX else ""
        val encoded = ADMISSION_CLOSED_PREFIX + voters.joinToString(",") { it.value } + scSuffix
        declare(encoded)
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
        val current = quilter.state.value.entries[quilter.replica]?.value ?: HOST_DECLARED
        if (!current.endsWith(SPECTATORS_CLOSED_SUFFIX)) declare(current + SPECTATORS_CLOSED_SUFFIX)
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
        val current = quilter.state.value.entries[quilter.replica]?.value
        if (current != HOST_DECLARED) declare(HOST_DECLARED)
    }

    private fun declare(value: String) {
        val nextClock = (quilter.state.value.entries[quilter.replica]?.clock ?: 0L) + 1L
        quilter.apply(Patch(quilter.state.value.put(quilter.replica, value, nextClock)))
    }

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
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.map { NodeId(it) }
            ?.toSet()

    private fun spectatorsClosedFrom(map: EphemeralMap<String>): Boolean =
        map.entries.values.any { entry -> entry.value?.endsWith(SPECTATORS_CLOSED_SUFFIX) == true }
}
