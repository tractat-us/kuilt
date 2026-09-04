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
 * `SeamStateGateConcurrencyTest`.
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
     * Arm 2 asserts an *absence* — that no iteration ended non-`Torn`. An absence assertion passes
     * trivially if the promotion never ran, so this arm pins the other half: the very callback arm 2
     * fires, on a link built exactly the way arm 2 builds one, does reach the `Weaving → Woven`
     * promotion. Without it, deleting the promotion outright would leave arm 2 green.
     */
    @Test
    fun aPeerConnectedCallbackOnItsOwnPromotesTheSeamToWoven() {
        val fake = CapturingFakeMultipeerNativeLib()
        val link = BridgePeerLink(nativeLib = fake, sessionHandle = handle, selfId = self)
        assertIs<SeamState.Weaving>(link.state.value, "precondition: nothing has promoted this link yet")

        fake.firePeerState("remote", isConnected = 1)

        assertIs<SeamState.Woven>(
            link.state.value,
            "rig precondition: firing a `connected` peer-state callback must drive the promotion " +
                "arm 2 races. If this fails, arm 2 proves nothing — it would be asserting that a " +
                "promotion which never happens cannot clobber anything",
        )
    }

    /**
     * The race: one peer-connected callback and one `close()`, released together, many times over.
     *
     * Each iteration gets a **fresh** link, because the promotion is single-shot — `registry.bind`
     * answers `BOUND` once per peer, and the `is Weaving` test is false ever after — so a link gets
     * exactly one pass through the window and reusing one would race nothing after the first.
     */
    @Test
    fun aPromotionCaughtMidFlightCannotStampWovenOverTheTerminalTorn() = runConcurrencyStress { stage ->
        val survivors = mutableListOf<String>()
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
            // Both writers have retired, so this read is final — nothing can correct it afterwards,
            // which is precisely what makes a non-`Torn` value here permanent rather than transient.
            val settled = link.state.value
            if (settled !is SeamState.Torn) survivors += "iter=$iter settled=$settled"
            completed++
        }

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
                assertIs<SeamState.Torn>(
                    BridgePeerLink(nativeLib = CapturingFakeMultipeerNativeLib(), sessionHandle = handle, selfId = self)
                        .also { it.closeNow(CloseReason.Normal) }
                        .state.value,
                    "rig precondition: an unraced closeNow() must still latch `Torn`, so a green above " +
                        "cannot be explained by close() having stopped publishing the terminal state",
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
