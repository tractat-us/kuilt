package us.tractat.kuilt.conformance

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.composite.CompositeLoom
import kotlin.coroutines.ContinuationInterceptor

/**
 * N-peer mesh evidence for [CompositeLoom] (#1408, Task 1.8): the multipath-bonding fabric
 * declares `meshDelivery = true` and supports ≥3 peers, so that claim is earned here against
 * the shared [MeshConformanceSuite] rather than left as a 2-peer vacuity note.
 *
 * ## How the mesh forms
 * Each of the `n` peers gets its own [CompositeLoom] bonded over ONE shared [InMemoryLoom]
 * ply. Peer 0 hosts, the rest join — symmetric anyway, since a [CompositeLoom]'s peer set is
 * the union of its plies' peer sets. As each ply seam reaches `Woven` its composite broadcasts
 * a `PlyFrame.Announce`, so every peer learns every other peer's `(plyId, transportId) →
 * compositeId` mapping over the shared ply mesh; [newMeshOfSize] then waits for every composite
 * seam's roster to converge to all `n` composite identities before returning, satisfying the
 * suite's "all connections established" precondition.
 *
 * The internal [CompositeSeam] pumps run on the test's dispatcher (taken from the calling
 * coroutine context) so peer discovery advances under the same virtual clock as the test.
 */
class CompositeLoomMeshConformanceTest : MeshConformanceSuite() {
    override suspend fun newMeshOfSize(n: Int): List<Seam> {
        val mem = InMemoryLoom()
        val dispatcher = requireNotNull(currentCoroutineContext()[ContinuationInterceptor]) {
            "CompositeLoom mesh harness: no dispatcher (ContinuationInterceptor) in coroutine context"
        }
        val looms = (0 until n).map { CompositeLoom(listOf(PlyId("mem") to mem), dispatcher = dispatcher) }
        val host = looms[0].host(Pattern("mesh"))
        val joiners = (1 until n).map { i -> looms[i].join(InMemoryTag(sessionName = "mesh", peerKey = "peer-$i")) }
        val seams = listOf(host) + joiners
        // Await full-mesh convergence: every composite peer's roster reaches all n identities.
        seams.forEach { seam -> seam.peers.first { it.size == n } }
        return seams
    }
}
