package us.tractat.kuilt.core

class InMemoryLoomConformanceTest : SeamConformanceSuite() {
    override fun newLoom(): Loom = InMemoryLoom()
}
