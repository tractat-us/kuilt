package us.tractat.kuilt.bolt

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-bolt`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * Only one of this module's samples is callable. The other five take a `Bolt` and demonstrate how
 * to branch on a replay verdict, a durability state, or an append result — `verifySamplesAreRun`
 * exempts a parameterised sample, because calling one needs a fixture, and a fixture invented to
 * satisfy a guard is how a runner ends up proving nothing. `sampleBoltArchiveFormat` asserts
 * nothing either; running it buys "the format wires up and three ops append without throwing".
 */
class BoltSamplesRunTest {

    @Test
    fun boltArchiveFormatHolds(): TestResult = runTest { sampleBoltArchiveFormat() }
}
