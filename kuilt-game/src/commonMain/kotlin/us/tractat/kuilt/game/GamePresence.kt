@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig

/** Marker value stored under a replica's slot to declare "I am the host". */
private const val HOST_DECLARED = "host"

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

    /** Declare this peer as the game host. */
    public fun declareHost() {
        val nextClock = (quilter.state.value.entries[quilter.replica]?.clock ?: 0L) + 1L
        quilter.apply(Patch(quilter.state.value.put(quilter.replica, HOST_DECLARED, nextClock)))
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
