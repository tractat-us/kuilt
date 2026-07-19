package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam

/**
 * N-peer mesh evidence for [InMemoryLoom] (#1408, Task 1.8): the reference in-memory
 * fabric declares `meshDelivery = true` in [InMemoryLoomConformanceTest] and supports ≥3
 * peers, so that claim is earned here against the shared [MeshConformanceSuite] rather than
 * left as a 2-peer vacuity note.
 *
 * A single [InMemoryLoom] instance is one flat mesh: one [InMemoryLoom.host] plus any number
 * of [InMemoryLoom.join]s all share the same peer set and every broadcast on it. So
 * [newMeshOfSize] weaves one host and `n − 1` joiners on ONE loom. Membership is established
 * synchronously under the loom's mutex as each seam is woven, so every returned seam already
 * sees the full `n`-peer roster — no convergence wait is needed.
 */
class InMemoryLoomMeshConformanceTest : MeshConformanceSuite() {
    override suspend fun newMeshOfSize(n: Int): List<Seam> {
        val loom = InMemoryLoom()
        val host = loom.host(Pattern("mesh"))
        val joiners = (1 until n).map { i -> loom.join(InMemoryTag(sessionName = "mesh", peerKey = "peer-$i")) }
        return listOf(host) + joiners
    }
}
