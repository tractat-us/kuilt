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

/**
 * Prefix for the value stored under the host's slot when admission is closed.
 *
 * The full value is `"$ADMISSION_CLOSED_PREFIX<id1>,<id2>,…"` — the final voter set
 * encoded as a comma-separated list of [NodeId] string values.
 */
private const val ADMISSION_CLOSED_PREFIX = "admission-closed:"

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
        val encoded = ADMISSION_CLOSED_PREFIX + voters.joinToString(",") { it.value }
        declare(encoded)
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
            ?.removePrefix(ADMISSION_CLOSED_PREFIX)
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.map { NodeId(it) }
            ?.toSet()
}
