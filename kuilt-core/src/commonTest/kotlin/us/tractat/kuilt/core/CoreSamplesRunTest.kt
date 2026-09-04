package us.tractat.kuilt.core

import kotlinx.coroutines.test.TestResult
import us.tractat.kuilt.core.discovery.sampleDiscoveryRoster
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-core`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * Most of these are `runTest`-based and so return a [TestResult]; each test returns it rather than
 * swallowing it, because on JS and wasm the result is a promise the framework must receive to
 * await. Discarding it would make those tests pass without running.
 *
 * `sampleIncomingFanout` and `sampleCloseIsIdempotent` assert nothing — the first collects three
 * frames and stops, the second calls `close()` twice under a comment saying it must not throw.
 * Running them buys exactly that "does not throw", which for the second is the whole claim and for
 * the first is less than the prose promises. `sampleFabricAvailability` `when`s over the three
 * availability cases and `error`s on one branch, so running it exercises the branch
 * `InMemoryLoom` actually takes and no other.
 */
class CoreSamplesRunTest {

    @Test
    fun hostAndJoinHolds(): TestResult = sampleHostAndJoin()

    @Test
    fun incomingFanoutHolds(): TestResult = sampleIncomingFanout()

    @Test
    fun fabricAvailabilityHolds() = sampleFabricAvailability()

    @Test
    fun muxSeamChannelsHolds(): TestResult = sampleMuxSeamChannels()

    @Test
    fun namedMuxChannelsHolds(): TestResult = sampleNamedMuxChannels()

    @Test
    fun muxClientLoomHolds(): TestResult = sampleMuxClientLoom()

    @Test
    fun weftHolds(): TestResult = sampleWeft()

    @Test
    fun swatchSenderFieldHolds(): TestResult = sampleSwatchSenderField()

    @Test
    fun closeIsIdempotentHolds(): TestResult = sampleCloseIsIdempotent()

    @Test
    fun closeRemovesPeerHolds(): TestResult = sampleCloseRemovesPeer()

    @Test
    fun sequenceMonotonicallyIncreasingHolds(): TestResult = sampleSequenceMonotonicallyIncreasing()

    @Test
    fun broadcastReceivedHolds(): TestResult = sampleBroadcastReceived()

    @Test
    fun joinPeerSetHolds(): TestResult = sampleJoinPeerSet()

    @Test
    fun discoveryRosterHolds(): TestResult = sampleDiscoveryRoster()

    @Test
    fun pumpInHolds(): TestResult = samplePumpIn()

    @Test
    fun seamStateGateHolds(): TestResult = sampleSeamStateGate()
}
