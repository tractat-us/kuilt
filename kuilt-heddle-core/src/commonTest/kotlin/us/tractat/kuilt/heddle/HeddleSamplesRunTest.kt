package us.tractat.kuilt.heddle

import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-heddle-core`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * `:kuilt-heddle`'s own samples (`sampleHeddleNode`, `sampleHeddleGoverned`) are not here: they
 * take a `Seam` and a `CoroutineScope` receiver, so they cannot be called without a fixture, and
 * `verifySamplesAreRun` exempts every parameterised sample for that reason.
 */
class HeddleSamplesRunTest {

    @Test
    fun entitlementLedgerMergeHolds() = sampleEntitlementLedgerMerge()

    @Test
    fun entitlementLedgerLifecycleHolds() = sampleEntitlementLedgerLifecycle()

    @Test
    fun weightOrderingHolds() = sampleWeightOrdering()

    @Test
    fun policyPickHolds() = samplePolicyPick()

    @Test
    fun fairShareAllocatorHolds() = sampleFairShareAllocator()

    @Test
    fun topologyFromDocumentHolds() = sampleTopologyFromDocument()

    @Test
    fun topologyDraftHolds() = sampleTopologyDraft()

    @Test
    fun topologyDiffHolds() = sampleTopologyDiff()

    @Test
    fun runtimeChildrenHold() = sampleRuntimeChildren()

    @Test
    fun localLeaseHolds() = sampleLocalLease()

    @Test
    fun explainWaitHolds() = sampleExplainWait()

    @Test
    fun reconcileLeasesHolds() = sampleReconcileLeases()

    @Test
    fun localReloadHolds() = sampleLocalReload()

    @Test
    fun localPersistenceHolds() = sampleLocalPersistence()
}
