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

/** Marker value stored under a replica's slot to declare "I am the host". */
private const val HOST_DECLARED = "host"

/** Marker value stored under a replica's slot to declare "I am present (a non-host participant)". */
private const val PRESENT_DECLARED = "present"

/**
 * Lobby presence over [seam], backed by an [EphemeralMap] replicated by [Quilter].
 *
 * Carries each peer's host-declaration flag so the game host entry point can fail
 * fast when a duplicate host is detected.
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
    scope: CoroutineScope,
    expectVirtualTime: Boolean = false,
) {
    private val quilter: Quilter<EphemeralMap<String>> = Quilter(
        seam = seam,
        initial = EphemeralMap.empty(),
        valueSerializer = EphemeralMap.serializer(String.serializer()),
        scope = scope,
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
            .stateIn(scope, SharingStarted.Eagerly, quilter.state.value.entries.keys)

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
}
