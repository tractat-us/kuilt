@file:Suppress("ForbiddenImport") // deliberate: see the ALLOW-realDispatcher marker on the import below.

package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the promotion and close() must run on DIFFERENT OS threads. The window under test is between a plain read of the state flow and the write that follows it, with no suspension point in between — under any test dispatcher the two paths are one thread and the window cannot exist at all.
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import platform.MultipeerConnectivity.MCEncryptionRequired
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionState
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.multipeer.internal.MCSessionLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The Apple half of the #1803 Shape A fix — the terminal-`Torn` latch against an in-flight roster
 * promotion in [MCSessionLink].
 *
 * [MCSessionLink] promoted `Weaving → Woven` with a check-then-act on a bare state flow:
 *
 * ```
 * _peers.value = registry.peers + selfId
 * if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven   // ← read and write are not atomic
 * ```
 *
 * The read runs on **MC's private delegate queue** — the framework calls
 * `session:peer:didChangeState:` from a queue this code does not own — while `close()` runs on the
 * consumer's. A tear landing between that read and that write is stamped over with `Woven`,
 * permanently: the spool is closed, `incoming` has completed, `peers` has collapsed to `{ selfId }`,
 * and neither writer is left to correct it. The owning factory's `ActiveSeamSlot` reads exactly that
 * latched `Torn` to free its single-session slot, so the device can never weave again.
 *
 * This is the same defect, in the same spelling, as the JVM twin `BridgePeerLink` — the two files
 * cross-reference each other throughout and were written together. Both now route through
 * `:kuilt-core`'s `SeamStateGate`.
 *
 * ## What this probe proves, and what the JVM twin proves that this cannot
 *
 * `BridgePeerLinkStateLatchConcurrencyTest` is the **quantified** evidence: on the JVM the unfixed
 * code reds at a measured rate (7 clobbers in 20 000 races), so its green after the fix is a real
 * before/after. This probe is the Apple-side counterpart and is deliberately the weaker of the two,
 * for a reason worth stating rather than discovering later: the delegate here is driven **by the
 * test**, not by MultipeerConnectivity, because a real `MCSession` only ever fires its delegate on
 * live hardware from the framework's own queue (the constraint `FakeMCSessionBus` documents). So
 * this exercises the seam's state machine under two genuinely concurrent Kotlin/Native threads,
 * which is where the defect lives — but the *thread MC actually uses* is out of reach of any unit
 * test in this module.
 *
 * Read the pair as: the JVM probe establishes that this spelling loses the race, and this probe
 * establishes that the Apple class's own writers are latched under real concurrency.
 *
 * ## Gating
 *
 * Excluded from the normal run by this module's `build.gradle.kts` unless
 * `-Pconcurrency.stress.tests=true`, so the probe never reds the merge gate for a scheduling delay
 * on a saturated runner (#1135 / #1158). The exclusion is at the **task** level rather than the
 * env-var-plus-`getenv`-self-skip `:kuilt-nw` uses for its native probe — one mechanism for this
 * module's JVM and native probes instead of two, and an excluded test is *absent* from the results
 * XML rather than present and passing.
 *
 * ## Do not trust this task's reported duration — assert the work instead
 *
 * `macosArm64Test` reported `time="0.0"` for a run of this very test that provably completed all
 * [ITERATIONS] iterations. That is why the race arm asserts its own loop count: a probe whose only
 * evidence of having run is a duration has no evidence at all on this target. An earlier revision of
 * this file concluded from that same 0.0 that the env-var gating had silently failed and the probe
 * had self-skipped; it had not — the variable arrives fine, and the clock was the thing lying. The
 * general shape is worth keeping in mind here: a contract-impossible reading is a fork between a
 * measurement bug and a real one, and this was the measurement branch.
 *
 * ## This probe runs NOWHERE in CI — it is a local-only instrument
 *
 * Stated plainly because the alternative is a comment that lies. `ci.yml`'s `concurrency-probes` job
 * is `runs-on: ubuntu-latest` and structurally cannot host a `macosArm64Test`. `apple-nightly.yml`
 * is the only macOS lane, and it runs `macosArm64Test iosSimulatorArm64Test` **without**
 * `-Pconcurrency.stress.tests`, so the exclusion above silences this class there too.
 *
 * That is deliberate rather than an oversight to be tidied up later. The nightly's own header
 * records a 10–20x wall-clock slowdown on its 3 vCPU runner, and Kotlin/Native has no
 * `runConcurrencyStress` equivalent — no wall-clock cap, no coroutine census, no thread dump — so a
 * probe that wedges there hangs the task and writes **no XML at all**, which is precisely the #1135
 * shape this whole family exists to avoid. A 3 000-iteration real-threaded probe is a bad tenant for
 * that runner until the cap exists.
 *
 * So the standing instrument for this defect class is the JVM twin, which does have a CI home; this
 * arm is run by hand (`./gradlew :kuilt-multipeer:macosArm64Test -Pconcurrency.stress.tests=true`)
 * when the Apple side is touched. Anyone wiring it into the nightly should solve the cap first.
 */
@OptIn(ExperimentalForeignApi::class)
class MCSessionLinkStateLatchConcurrencyTest {

    /**
     * Rig control for the race arm, and the reason its green is worth anything.
     *
     * The race arm asserts an *absence* — that no iteration ended non-`Torn` — which passes
     * trivially if the promotion never ran. This pins the other half: the delegate callback the race
     * arm fires, on a link built exactly the way it builds one, does reach the `Weaving → Woven`
     * promotion.
     */
    @Test
    fun aConnectedDelegateCallbackOnItsOwnPromotesTheSeamToWoven() {
        val self = MCPeerID(displayName = "self")
        val session = newSession(self)
        val link = MCSessionLink(self, session)
        assertIs<SeamState.Weaving>(link.state.value, "precondition: nothing has promoted this link yet")

        link.delegate.session(session, MCPeerID(displayName = "guest"), MCSessionState.MCSessionStateConnected)

        assertIs<SeamState.Woven>(
            link.state.value,
            "rig precondition: a `connected` delegate callback must drive the promotion the race arm " +
                "races. If this fails, the race arm proves nothing — it would be asserting that a " +
                "promotion which never happens cannot clobber anything",
        )
    }

    /**
     * The race: one connected-delegate callback and one `close()`, released together, many times.
     *
     * Each iteration gets a **fresh** link, because the promotion is single-shot — `registry.bind`
     * answers `BOUND` once per peer and the promotion is skipped ever after — so a link gets exactly
     * one pass through the window.
     */
    @Test
    fun aPromotionCaughtMidFlightCannotStampWovenOverTheTerminalTorn() = runBlocking {
        val survivors = mutableListOf<String>()
        var completed = 0
        repeat(ITERATIONS) { iter ->
            val self = MCPeerID(displayName = "self")
            val session = newSession(self)
            val link = MCSessionLink(self, session)
            val guest = MCPeerID(displayName = "guest")
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val promoter = async(Dispatchers.Default) {
                    ready.await()
                    link.delegate.session(session, guest, MCSessionState.MCSessionStateConnected)
                }
                val closer = async(Dispatchers.Default) {
                    ready.await()
                    link.close(CloseReason.Normal)
                }
                ready.complete(Unit)
                awaitAll(promoter, closer)
            }
            // Both writers have retired, so this read is final — which is what makes a non-`Torn`
            // value here permanent rather than transient.
            val settled = link.state.value
            if (settled !is SeamState.Torn) survivors += "iter=$iter settled=$settled"
            completed++
        }

        // Rig precondition: the loop actually ran every iteration it claims. Asserted rather than
        // inferred from the reported duration, because the duration is exactly what lied earlier in
        // this PR — a self-skipping probe reported 3 000 races as a PASS in 0.0s, and the K/N result
        // XML's `time` attribute is not a trustworthy witness either. A race arm that asserts only an
        // ABSENCE must prove it did the work, or a loop that never executed reads as a clean pass.
        assertEquals(ITERATIONS, completed, "the race loop did not complete every iteration")

        assertEquals(
            emptyList(),
            survivors.take(MAX_REPORTED_SURVIVORS),
            "a delegate promotion caught mid-flight stamped a non-terminal state over the terminal " +
                "`Torn` in ${survivors.size} of $ITERATIONS iterations (#1803). The link is closed, " +
                "`incoming` has completed and `peers` has collapsed, so this value is " +
                "contract-impossible AND permanent: no writer is left to correct it, every " +
                "`state.first { it is Torn }` waiter hangs forever, and the factory's " +
                "`ActiveSeamSlot` never frees — no later weave() on this device can succeed",
        )
    }

    private fun newSession(self: MCPeerID): MCSession =
        MCSession(peer = self, securityIdentity = null, encryptionPreference = MCEncryptionRequired)

    private companion object {
        /**
         * Each iteration builds a real `MCSession` and `MCPeerID`, which is far heavier than the JVM
         * twin's fake — so the count is lower there than the 20 000 that probe uses, and buys
         * correspondingly less sensitivity. That is stated because it bounds what a green here means.
         */
        const val ITERATIONS = 3_000

        /** Cap on the failure message only; `survivors.size` still reports the true total. */
        const val MAX_REPORTED_SURVIVORS = 10
    }
}
