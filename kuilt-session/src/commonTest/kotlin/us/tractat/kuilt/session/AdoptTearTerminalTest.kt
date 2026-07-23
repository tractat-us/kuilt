@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.FlakyLifecycleLoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Reproduction + fix for #1618: the app's mesh path is
 * `electLobby → adopt → SeamRoom`. When [SeamRoomFactory.adopt] wires **no** `reweave`, a joiner
 * whose host link tears runs [us.tractat.kuilt.session.partition.JoinerResumeMachine.attemptReconnect]
 * and takes the **immediate-terminal** branch (`reweaveFn == null` → `onReconnectFailed(Unrecoverable)`),
 * so a transient blip kills the adopted room ([MembershipEvent.HostLost]) with no
 * [MembershipEvent.WindowOpened] — no room ever lives the ~window a resume needs. The fix threads a
 * `reweave` into [SeamRoomFactory.adopt] and wires `reweave = { seam }` on the election-lobby path,
 * so the resume machine runs the retry loop over the self-healing seam instead of going terminal.
 *
 * ## Harness note — why [FlakyLifecycleLoom], not `FaultyLoom.partition(Both)`
 *
 * The real fault this bug hits is a **transport tear**: the host endpoint drops (leaves the seam's
 * peer set) and the fabric re-forms. `NwSeam` models this as `Woven → Weaving → Woven` (it does NOT
 * latch `Torn` on peer loss; `NwLoom` redials and it heals in place). Only that shape reaches
 * `attemptReconnect`: [us.tractat.kuilt.session.SeamRoom]'s host-liveness handler calls it solely on
 * [us.tractat.kuilt.liveness.PartitionEvent.Reason.TransportClosed] (peer gone from `peers`), never
 * on a plain `Timeout`. A `FaultySeam.partition(Both)` only **drops frames** — the peer stays in
 * `peers`, so the detector fires `Timeout`, which routes to `markPartitioned` → `PeerLost` →
 * `HostLost` and **never enters the reweave path this fix changes**. [FlakyLifecycleLoom]'s
 * `enterWeaving()` / `recover()` / `tear()` reproduce the real transport-tear shape, so these tests
 * genuinely exercise the resume plumbing.
 *
 * **This is still session-layer plumbing only**, NOT the real `NwSeam` self-heal: these doubles
 * prove `reweave → wait-for-Woven → resume`, not that a real Network.framework link recovers.
 * #1618 cannot be closed by this PR — it needs a 2-phone hardware validation.
 *
 * Timing (fast config): interval 100 ms, timeout 200 ms, reconnect window 1000 ms.
 */
class AdoptTearTerminalTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 1000.milliseconds,
    )

    /**
     * **Task 1 repro (pins the bug; PASSES on current code).**
     *
     * An adopted joiner with **no** `reweave` (the pre-fix `electLobby → adopt` wiring) is torn by a
     * transient blip. Because `reweave == null`, the resume machine's immediate-terminal branch fires:
     * the joiner emits [MembershipEvent.HostLost] ([FailureReason.Unrecoverable]) and **never**
     * [MembershipEvent.WindowOpened] — even though the seam self-heals moments later (`recover()`).
     * That is the churn source: every transient mesh blip kills the adopted room.
     */
    @Test
    fun `adopted joiner with no reweave goes terminal on a transient tear without opening a window`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // FIXED clock: the detector measures silence as clock() - lastSeen, so a frozen clock
            // makes a plain `Timeout` impossible — the ONLY partition the detector can report is the
            // peers-based `TransportClosed` (host gone from `peers`), which is exactly the transport
            // tear this bug rides. (Mirrors JoinerReconnectTest's in-window resume harness.)
            val clock = { Instant.fromEpochMilliseconds(0L) }
            fun tick() {
                advanceTimeBy(100L)
                runCurrent()
            }

            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val factory = SeamRoomFactory(
                loom = loom,
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
            )

            // Weave the mesh ourselves (what the lobby does), then adopt with explicit roles — the
            // current `adopt` overload wires NO reweave.
            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))
            val hostRoom = factory.adopt(hostSeam, SessionRole.Host, memberName = "Host")
            val joinerRoom = factory.adopt(joinerSeam, SessionRole.Joiner, memberName = "Joiner")

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val windowOpened = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()
            }
            val hostLost = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }

            // Let the host-liveness detector run a few iterations while the host is present, so it
            // latches "host was seen" — the precondition for it to report the host's departure as a
            // definitive transport close rather than mere silence.
            repeat(3) { tick() }

            // Transient tear: the host link drops (host leaves the joiner's peer set) but the seam
            // does NOT latch Torn — the NwSeam Woven→Weaving shape.
            joinerSeam.enterWeaving()
            repeat(3) { tick() }

            // Even though the blip heals here, the room already died — a transient blip was terminal.
            joinerSeam.recover()
            repeat(3) { tick() }

            val lost = hostLost.await()
            assertIs<MembershipEvent.HostLost>(lost)
            assertEquals(
                FailureReason.Unrecoverable,
                lost.reason,
                "no reweave → the resume machine's immediate-terminal branch (Unrecoverable) fires",
            )
            assertFalse(
                windowOpened.isCompleted,
                "the immediate-terminal branch never opens a reconnect window",
            )
        }

    /**
     * **Task 2 fix (heal within the window; RED before the fix, GREEN after).**
     *
     * The same adopted joiner, now given `reweave = { seam }` (the electLobby → adopt wiring after
     * #1618). A transient host-link tear that **heals within the reconnect window** must open a
     * window and **resume** — emit [MembershipEvent.WindowOpened] then [MembershipEvent.Resumed],
     * NOT [MembershipEvent.HostLost]. Before the fix (`reweave == null`) this same blip went straight
     * to terminal; the `reweave = { seam }` non-null makes the resume path run first.
     */
    @Test
    fun `adopted joiner with reweave resumes when a transient tear heals within the window`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // Advancing clock: the host opens its reconnect window via a heartbeat-silence Timeout
            // while the joiner is away; the joiner's own tear is the peers-based TransportClosed
            // (independent of the clock) that reaches the reweave path.
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick() {
                nowMs += 100L
                advanceTimeBy(100L)
                runCurrent()
            }

            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val factory = SeamRoomFactory(
                loom = loom,
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
            )

            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))
            val hostRoom = factory.adopt(hostSeam, SessionRole.Host, memberName = "Host")
            // The fix: adopt the self-healing seam WITH reweave = { seam }.
            val joinerRoom =
                factory.adopt(joinerSeam, SessionRole.Joiner, memberName = "Joiner", reweave = { joinerSeam })

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val windowOpened = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()
            }
            val resumed = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first()
            }
            val hostLost = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }

            // Settle so both detectors latch "peer was seen".
            repeat(3) { tick() }

            // Transient tear (self-heal shape, not Torn). The host, still Woven, sees the joiner go
            // silent and opens its reconnect window; the joiner sees the host leave its peer set and
            // starts the resume attempt.
            joinerSeam.enterWeaving()
            repeat(4) { tick() }

            // Heal within the window: the seam re-forms in place.
            joinerSeam.recover()
            repeat(5) { tick() }

            assertIs<MembershipEvent.WindowOpened>(windowOpened.await())
            assertIs<MembershipEvent.Resumed>(resumed.await())
            assertFalse(
                hostLost.isCompleted,
                "a transient blip that heals in-window must resume, not fall to HostLost",
            )
        }

    /**
     * **Task 2 companion (sustained tear still goes terminal).**
     *
     * `reweave = { seam }` must not paper over a genuine host loss: a tear that never heals inside the
     * reconnect window still ends in [MembershipEvent.HostLost] ([FailureReason.WindowExpired]) — the
     * resume path is tried and, when the window elapses without recovery, correctly gives up.
     */
    @Test
    fun `adopted joiner with reweave still goes HostLost when the tear outlasts the window`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick() {
                nowMs += 100L
                advanceTimeBy(100L)
                runCurrent()
            }

            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val factory = SeamRoomFactory(
                loom = loom,
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
            )

            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))
            val hostRoom = factory.adopt(hostSeam, SessionRole.Host, memberName = "Host")
            val joinerRoom =
                factory.adopt(joinerSeam, SessionRole.Joiner, memberName = "Joiner", reweave = { joinerSeam })

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val hostLost = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }

            repeat(3) { tick() }

            // Tear and never heal — a sustained outage past the 1000 ms reconnect window.
            joinerSeam.enterWeaving()
            repeat(13) { tick() }

            val lost = hostLost.await()
            assertIs<MembershipEvent.HostLost>(lost)
            assertEquals(
                FailureReason.WindowExpired,
                lost.reason,
                "a sustained tear must expire the window and go terminal (WindowExpired)",
            )
        }
}
