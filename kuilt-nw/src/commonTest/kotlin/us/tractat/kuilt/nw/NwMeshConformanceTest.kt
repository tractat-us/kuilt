package us.tractat.kuilt.nw

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.conformance.MeshConformanceSuite
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam

/**
 * Verifies that a full mesh of [NwLoom]s satisfies every invariant in [MeshConformanceSuite] —
 * the REAL N-peer evidence that earns `meshDelivery = true` in [NwConformanceTest] (not a vacuous
 * 2-peer claim). Absorbs the old "3-loom smoke" into the shared TCK.
 *
 * ## How the mesh forms
 * [newMeshOfSize] stands up `n` distinct [NwLoom]s (one [FakeNwApi] per simulated device) on ONE
 * [FakeNwRadio], all sharing the same [SERVICE_TYPE]. It weaves them **concurrently** (peer 0 hosts,
 * the rest join — the roles are symmetric anyway: every peer advertises + browses + auto-dials). As
 * each device comes up its advertise notifies existing browsers and its browse finds existing
 * listeners, so every pair discovers each other and dials; the double-dial is deduped by [NwSeam].
 * After all weaves return (each already has ≥1 peer), it waits for every seam's roster to converge
 * to all `n` identities before returning — satisfying the suite's "all connections established" precondition.
 */
class NwMeshConformanceTest : MeshConformanceSuite() {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
    }

    override suspend fun newMeshOfSize(n: Int): List<Seam> = coroutineScope {
        val radio = FakeNwRadio()
        val looms = (0 until n).map { i ->
            NwLoom(FakeNwApi(radio, deviceId = "dev-$i", serviceName = "dev-$i"), serviceType = SERVICE_TYPE)
        }
        // Weave concurrently so the peers can discover and dial each other while each awaits its
        // first connection (a sequential weave would deadlock: the first peer has no one to connect to yet).
        val seams = looms.mapIndexed { i, loom ->
            async {
                if (i == 0) loom.host(Pattern("mesh"))
                else loom.join(InMemoryTag(sessionName = "mesh", peerKey = "dev-$i"))
            }
        }.awaitAll()
        // Await full-mesh convergence: every peer's roster reaches all n identities.
        seams.forEach { seam -> seam.peers.first { it.size == n } }
        seams
    }
}
