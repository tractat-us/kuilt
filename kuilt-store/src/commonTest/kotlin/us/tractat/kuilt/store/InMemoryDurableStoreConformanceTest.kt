package us.tractat.kuilt.store

import us.tractat.kuilt.conformance.DurableStoreConformanceSuite
import us.tractat.kuilt.conformance.RestartFixture

/**
 * Verifies the reference [InMemoryDurableStore] satisfies the whole
 * [DurableStoreConformanceSuite] — the first tests this class has ever had.
 *
 * In `commonTest`, so it runs on every target `:kuilt-store` builds for: JVM, Android, iOS, macOS and
 * wasmJs. That is deliberate. The reference is the only backend that exists on all of them, so it is
 * the only one that can prove the *suite itself* compiles and runs everywhere — a property with no
 * subclass on a target is a property that target never checks.
 */
class InMemoryDurableStoreConformanceTest : DurableStoreConformanceSuite() {

    override suspend fun newStore(): DurableStore = InMemoryDurableStore()

    /**
     * A restart, for a store whose own KDoc says it is **not crash-safe**: everything is gone.
     *
     * This is the arm [RestartFixture.KeepsNothing] exists for, and handing back a fresh instance is
     * not a formality — the suite asserts the fresh handle reads *empty*, so returning `store` here,
     * or wiring this backend to the [RestartFixture.Durable] arm, both fail loudly.
     *
     * What it cannot detect is anything about durability; there is no medium here to lose a write to.
     * The obligation is on the three file- and browser-backed stores, which is exactly why it lives
     * in the TCK rather than in one backend's own tests.
     */
    override suspend fun restart(store: DurableStore): RestartFixture =
        RestartFixture.KeepsNothing(InMemoryDurableStore())
}
