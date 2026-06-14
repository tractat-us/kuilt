package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.fabric.inMemoryMeshOfSize

/** The in-memory `meshSeam` satisfies the N-peer mesh contract. */
class MeshConformanceTest : MeshConformanceSuite() {
    override suspend fun newMeshOfSize(n: Int): List<Seam> = inMemoryMeshOfSize(n)
}
