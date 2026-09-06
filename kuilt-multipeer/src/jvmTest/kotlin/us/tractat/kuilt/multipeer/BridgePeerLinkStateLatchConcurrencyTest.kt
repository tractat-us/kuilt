@file:Suppress("ForbiddenImport") // deliberate: see the ALLOW-realDispatcher marker on the import below.

package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the promotion and close() must run on DIFFERENT OS threads. The window under test is between a plain read of `_state` and the write that follows it, with no suspension point in between — under any test dispatcher the two paths are one thread and the window cannot exist at all.
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.multipeer.internal.BridgePeerLink
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.runConcurrencyStress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The terminal-`Torn` latch against an in-flight roster promotion on the JVM/JNA half of the
 * Multipeer fabric (#1803, Shape A).
 *
 * [BridgePeerLink] promoted `Weaving → Woven` with a check-then-act on `_state`:
 *
 * ```
 * _peers.value = registry.peers + selfId
 * if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven   // ← read and write are not atomic
 * ```
 *
 * The read runs on a **JNA trampoline thread** — native calls `peerStateCallback` from whatever
 * thread MultipeerConnectivity is on — while `close()` runs on the consumer's. Between that read and
 * that write, `closeNow` can run to completion: CAS `closing`, clear the registry, collapse `_peers`,
 * CAS `tornDown`, publish `Torn`, close the bridge and spool, cancel the scope. The callback's
 * in-flight write then stamps `Woven` over the terminal `Torn`.
 *
 * The resulting state is contract-impossible and **permanent**: the spool is closed, `incoming` has
 * completed, `peers` has collapsed to `{ selfId }`, and no further emission can correct `state`
 * because the only two writers have both retired. Everything downstream that waits on
 * `state.first { it is Torn }` hangs forever — and the owning factory's `ActiveSeamSlot` reads
 * exactly that latched `Torn` to free its single-session slot, so **no later `weave()` on this
 * device can ever succeed**. That is the wedge #1803 ranks at item 6.
 *
 * The fix routes both writers through `:kuilt-core`'s [us.tractat.kuilt.core.SeamStateGate], which
 * fuses the latch check and the flow write into one critical section, so a late promotion is a no-op
 * rather than a clobber.
 *
 * ## The same race on the sibling flow — `peers` (#2626)
 *
 * This probe drove **both** halves of that sentence from the day it was written and only ever
 * checked one. Its race arm's failure message asserts, as established fact, that on a clobbered
 * iteration "`incoming` has completed and `peers` has collapsed" — and nothing looked at `peers`.
 *
 * The roster publish is the identical shape one flow over:
 *
 * ```
 * _peers.value = registry.peers + selfId      // ← read the registry, then store; not atomic
 * ```
 *
 * against a `tearDown` that does `registry.clear(); _peers.value = setOf(selfId)`. A promoter that
 * evaluates the right-hand side to `{ self, remote }` and is preempted through a **complete**
 * teardown stores that stale roster over the collapsed one — and a promoter arriving entirely
 * *after* the teardown binds into the freshly-cleared registry and publishes the same thing, which
 * is the half no amount of read-modify-write atomicity would fix. Either way the seam ends `Torn`
 * while `peers` still names a peer that is gone, breaking the collapse-on-tear invariant of #1816 /
 * #1851 permanently: both writers have retired, so nothing is left to correct it.
 *
 * The window is **wider** than the `state` one — `registry.peers` takes the registry's lock and
 * copies a map's key set before the `+` allocates again — so the roster arm reds at a far higher
 * rate than the seven-in-twenty-thousand the `state` arm measured.
 *
 * The fix guards every roster publish with a `peersLock` and a `collapsed` marker set inside the
 * same critical section as the collapse — the shape `NearbySeam.collapseRoster` and
 * `TieredSeam` already use, and the one `SeamStateGate`'s own KDoc points at ("a seam may `tear`
 * while holding its roster lock"). `SeamStateGate` itself cannot cover this: it holds `SeamState`,
 * and keying the roster guard on `state` would leave the window *between* the collapse and the
 * latch, where the seam is not yet `Torn` — the argument `NearbySeam`'s `collapsed` field spells
 * out.
 *
 * ## Why this is a stress probe and not a deterministic one
 *
 * Its sibling `NearbySeamStateLatchConcurrencyTest` reproduces the same class deterministically,
 * because there the promotion reads a **test-supplied** roster inside the window
 * (`_state.value is Weaving && session.any { … }` — `&&` is short-circuit, so the predicate is
 * evaluated after the read and before the write, and a trap set can block there). This site has no
 * such hook: the window is a bare `is` check between two adjacent `StateFlow` accesses, with no
 * production code in it that a test can reach. So the window has to be hit by scheduling rather than
 * held open, which is what [arm 2][aPromotionCaughtMidFlightCannotStampWovenOverTheTerminalTorn]
 * does — the same instrument, and for the same reason, as `:kuilt-core`'s own
 * `SeamStateGateLatchCapabilityConcurrencyTest`.
 *
 * That makes the probe's own sensitivity a thing to state rather than assume, which is what
 * [arm 1][aPeerConnectedCallbackOnItsOwnPromotesTheSeamToWoven] is for: a stress arm that never
 * reaches the promotion at all is green by absence, and would read exactly like a fixed bug. Arm 1
 * pins that the callback this probe fires does drive the promotion, so arm 2's green means "no
 * clobber" rather than "nothing happened".
 *
 * ## Why this is gated behind `-Pconcurrency.stress.tests=true`
 *
 * The repo-wide `*ConcurrencyTest` contract: a real-threaded probe depends on the OS scheduling two
 * `Dispatchers.Default` workers, and on a saturated runner that dispatch is delayed past any budget
 * set here — reddening the merge gate for a reason unrelated to this code (#1135 / #1158). Excluded
 * from the normal run by `kuilt-multipeer/build.gradle.kts`; run by the `concurrency-probes` job in
 * `ci.yml`, which names `:kuilt-multipeer:jvmTest` in its own step (added with this probe — before
 * that no workflow referenced this module at all, so a probe claiming a CI home would have had
 * none).
 *
 * The honest consequence, the same one `:kuilt-nearby` records: that job is non-blocking, so
 * **`ci-required` does not pin this fix.** Nothing cheaper exists — a single-threaded test cannot
 * distinguish the check-then-act from the gate, because the check-then-act is *correct* whenever
 * nothing runs between the read and the write, which is exactly what a test dispatcher guarantees.
 *
 * ## What this covers, and what it does not
 *
 * It drives [BridgePeerLink] over [CapturingFakeMultipeerNativeLib], which moves no bytes. It proves
 * the *seam's* state machine survives its own peer-state callback racing its own `close()` — which
 * is where the defect lives. It says nothing about the real native bridge: whether MC delivers two
 * callbacks concurrently, and on which threads, is a property of `libkuilt`/MultipeerConnectivity
 * that no unit test in this module can observe.
 */
class BridgePeerLinkStateLatchConcurrencyTest {

    private val self = PeerId("self")
    private val handle = Pointer(0xDEADBEEFL)

    /**
     * Rig control for arm 2, and the reason its green is worth anything.
     *
     * Arm 2 asserts an *absence* — that no iteration ended non-`Torn`, and that none ended with a
     * roster naming the remote. An absence assertion passes trivially if the writer it races never
     * ran, so this arm pins the other half: the very callback arm 2 fires, on a link built exactly
     * the way arm 2 builds one, does reach **both** writes — the `Weaving → Woven` promotion and
     * the `_peers.value = registry.peers + selfId` roster publish. Without it, deleting either
     * outright would leave arm 2 green.
     */
    @Test
    fun aPeerConnectedCallbackOnItsOwnPromotesTheSeamToWovenAndPublishesTheRemote() {
        val fake = CapturingFakeMultipeerNativeLib()
        val link = BridgePeerLink(nativeLib = fake, sessionHandle = handle, selfId = self)
        assertAll(
            { assertIs<SeamState.Weaving>(link.state.value, "precondition: nothing has promoted this link yet") },
            {
                assertEquals(
                    setOf(self),
                    link.peers.value,
                    "precondition: nothing has published a roster onto this link yet",
                )
            },
        )

        fake.firePeerState("remote", isConnected = 1)

        assertAll(
            {
                assertIs<SeamState.Woven>(
                    link.state.value,
                    "rig precondition: firing a `connected` peer-state callback must drive the " +
                        "promotion arm 2 races. If this fails, arm 2 proves nothing — it would be " +
                        "asserting that a promotion which never happens cannot clobber anything",
                )
            },
            {
                assertEquals(
                    setOf(self, PeerId("remote")),
                    link.peers.value,
                    "rig precondition: the same callback must also drive the ROSTER publish arm 2 " +
                        "races (#2626). If this fails, arm 2's roster assertion proves nothing — a " +
                        "roster that never gains the remote can never keep it past a tear",
                )
            },
        )
    }

    /**
     * The race: one peer-connected callback and one `close()`, released together, many times over.
     *
     * Each iteration gets a **fresh** link, because the promotion is single-shot — `registry.bind`
     * answers `BOUND` once per peer, and the `is Weaving` test is false ever after — so a link gets
     * exactly one pass through the window and reusing one would race nothing after the first.
     *
     * Two clobbers are checked, not one: the `state` clobber of #1803 and the **roster** clobber of
     * #2626, which this arm has driven since it was written while its message merely *claimed* the
     * roster had collapsed. They are counted separately because they are separate defects with
     * separate fixes — a run that reds only the roster list is a `SeamStateGate` doing its job over
     * an unguarded `_peers`, and folding the two into one list would hide exactly that.
     */
    @Test
    fun aPromotionCaughtMidFlightCannotStampWovenOverTheTerminalTorn() = runConcurrencyStress { stage ->
        val survivors = mutableListOf<String>()
        val rosterSurvivors = mutableListOf<String>()
        var completed = 0
        repeat(ITERATIONS) { iter ->
            val fake = CapturingFakeMultipeerNativeLib()
            val link = BridgePeerLink(nativeLib = fake, sessionHandle = handle, selfId = self)
            stage.at("iter=$iter race") { "iter=$iter state=${link.state.value} peers=${link.peers.value}" }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val promoter = async(Dispatchers.Default) {
                    ready.await()
                    fake.firePeerState("remote", isConnected = 1)
                }
                val closer = async(Dispatchers.Default) {
                    ready.await()
                    link.closeNow(CloseReason.Normal)
                }
                ready.complete(Unit)
                awaitAll(promoter, closer)
            }
            // Both writers have retired, so these reads are final — nothing can correct them
            // afterwards, which is precisely what makes a bad value here permanent rather than
            // transient.
            val settled = link.state.value
            if (settled !is SeamState.Torn) survivors += "iter=$iter settled=$settled"
            val roster = link.peers.value
            if (roster != setOf(self)) rosterSurvivors += "iter=$iter peers=$roster"
            completed++
        }

        // Built once, outside `assertAll`, so both rig preconditions below interrogate the SAME
        // unraced link: "close still latches Torn" and "close still collapses the roster" are two
        // claims about one teardown, and asserting them on two different links would leave open the
        // case where each holds only on its own.
        //
        // It is given a peer BEFORE the close, and that is the whole point of the second
        // precondition. On a link that never met anybody `peers` is `{ selfId }` from construction,
        // so "the roster collapsed" would hold by never having changed — the fixture would be
        // configured into the one state at which the assertion cannot fail. Firing the connect first
        // makes the collapse load-bearing: the roster genuinely holds two entries, and only a
        // teardown that still collapses can bring it back to one.
        val unracedFake = CapturingFakeMultipeerNativeLib()
        val unraced = BridgePeerLink(nativeLib = unracedFake, sessionHandle = handle, selfId = self)
        unracedFake.firePeerState("remote", isConnected = 1)
        val unracedRosterBeforeClose = unraced.peers.value
        unraced.closeNow(CloseReason.Normal)

        assertAll(
            {
                // Rig precondition: the loop actually ran every iteration it claims. Asserted rather
                // than inferred from the reported duration, because on this module's sibling native
                // probe the reported duration is `0.0` for a run that provably did all 3 000 of its
                // iterations. A race arm that asserts only an ABSENCE must prove it did the work, or
                // a loop that never ran reads as a clean pass.
                assertEquals(ITERATIONS, completed, "the race loop did not complete every iteration")
            },
            {
                assertEquals(
                    emptyList(),
                    survivors.take(MAX_REPORTED_SURVIVORS),
                    "a peer-state promotion caught mid-flight stamped a non-terminal state over the " +
                        "terminal `Torn` in ${survivors.size} of $ITERATIONS iterations (#1803). The " +
                        "link is closed, `incoming` has completed and `peers` has collapsed, so this " +
                        "value is contract-impossible AND permanent: no writer is left to correct it, " +
                        "every `state.first { it is Torn }` waiter hangs forever, and the factory's " +
                        "`ActiveSeamSlot` never frees — no later weave() on this device can succeed",
                )
            },
            {
                assertEquals(
                    emptyList(),
                    rosterSurvivors.take(MAX_REPORTED_SURVIVORS),
                    "a peer-state ROSTER publish caught mid-flight stood over the tear-time collapse " +
                        "in ${rosterSurvivors.size} of $ITERATIONS iterations (#2626). The link is " +
                        "`Torn` — `incoming` has completed and every send is refused — yet `peers` " +
                        "still names a peer this fabric can no longer reach, which is the " +
                        "collapse-on-tear invariant of #1816/#1851 broken PERMANENTLY: both writers " +
                        "have retired, so no later emission can correct it, and a decorator folding " +
                        "this seam goes on treating that peer as reachable. This is the clause the " +
                        "sibling assertion's own failure message above has always asserted as fact",
                )
            },
            {
                assertIs<SeamState.Torn>(
                    unraced.state.value,
                    "rig precondition: an unraced closeNow() must still latch `Torn`, so a green above " +
                        "cannot be explained by close() having stopped publishing the terminal state",
                )
            },
            {
                assertEquals(
                    setOf(self, PeerId("remote")),
                    unracedRosterBeforeClose,
                    "rig precondition: the unraced link must actually HOLD the remote before its " +
                        "close, or the collapse assertion below is asserting that `{ selfId }` stayed " +
                        "`{ selfId }`",
                )
            },
            {
                assertEquals(
                    setOf(self),
                    unraced.peers.value,
                    "rig precondition: an unraced closeNow() must still COLLAPSE the roster, so a " +
                        "green above cannot be explained by the collapse having been removed — with " +
                        "no collapse to race, `peers == setOf(selfId)` would hold by never having " +
                        "changed rather than by the publish being refused",
                )
            },
        )
    }

    private companion object {
        /**
         * The window is two adjacent `StateFlow` accesses, so a hit needs the closer to land inside a
         * few tens of nanoseconds — hit rate per iteration is low and the count is what buys
         * sensitivity, not the per-iteration budget. Sized from the measured revert-check: at this
         * count the unfixed code reds well inside one run. It is a **detection floor, not a timing
         * assertion** — the wall-clock cap belongs to `runConcurrencyStress`.
         */
        const val ITERATIONS = 20_000

        /** Cap on the failure message only; `survivors.size` still reports the true total. */
        const val MAX_REPORTED_SURVIVORS = 10
    }
}
