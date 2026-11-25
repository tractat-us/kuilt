@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.conformance

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultyLoom
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TeardownFault
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.session.AdmissionFailure
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.FramesLost
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.ReconnectReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.RoomFrame
import us.tractat.kuilt.session.RoomFramePrefix
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Reusable contract test suite for [RoomFactory] implementations.
 *
 * Subclass and implement [newHarness] to bind a [RoomFactory] under test.
 * Every [Test] encodes a required invariant of the Room lifecycle state machine.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`)
 * so every [RoomFactory] adapter can subclass it from its own test source set.
 *
 * **Virtual time convention:** all partition tests advance in 100 ms steps using
 * [fastHeartbeatConfig] (interval=100ms, timeout=200ms, reconnectWindow=500ms):
 *  - 4 × 100 ms → [MembershipEvent.Partitioned] fires.
 *  - 9 × 100 ms → past reconnect window → PeerLost / [MembershipEvent.HostLost].
 *
 * **Scope contract:** [newHarness] receives the test's [CoroutineScope] (typically
 * `backgroundScope` from [runTest]) so [FaultyLoom] and [SeamRoomFactory] are
 * correctly structured under the test's virtual-time scheduler.
 *
 * **Fault injection:** tests that require partition or teardown behaviour go through [RoomHarness.faults], a
 * two-armed [FaultInjection] fixture. A harness that cannot break its own links declares
 * [FaultInjection.Unsupported] **with a tracking URL** — it cannot decline silently, because the
 * arm has nowhere to put a refusal that is not also a declaration (#2306).
 *
 * **Every wait is bounded, and names what it saw.** The population running this suite is by
 * definition implementors whose fabric does *not* work yet, so no obligation here may be guarded by
 * a suspending wait that simply never returns. Waits go through [awaitRoster] / [awaitEvent] /
 * [awaitFrame], each bounded by [awaitBudget] in **virtual** time and each failing with an
 * [AssertionError] that prints the roster (or the events) actually observed — see #2284, where a
 * fabric that never admitted burned the whole `runTest` ceiling and then reported
 * `UncompletedCoroutinesError`, naming neither the room nor its roster.
 *
 * ## No obligation here returns silently — #2306
 *
 * There is no skip API in common `kotlin-test`, so an obligation that early-returns reports **PASS**
 * — worse than a JVM-visible `@Ignore`, because nothing anywhere records that it did not run. This
 * suite had four such returns and, until #2306, exactly one subclass: the reference. Every escape
 * hatch had therefore fired zero times in its life, and nothing had ever checked that a harness
 * taking one survives the suite at all.
 *
 * They were closed by three different mechanisms, chosen by *whose* limitation the missing state
 * was. Two went with the surface they guarded: a loud precondition on the joiner's resume
 * credential, retired with it (#3215), and a refusal property for a credential naming another room,
 * retired when the credential stopped being presented (#3159 Task 1.7). The one left:
 *
 *  - **Fault injection — a two-armed sealed fixture** ([FaultInjection]). Here the missing state
 *    genuinely belongs to the harness: a room over a fabric whose links the test cannot reach
 *    cannot be partitioned by anyone. A nullable hook was the wrong shape for it — `null` moves the
 *    vacuity one level up, where it is a value nobody has to justify. The sealed arm makes
 *    declining *representable only together with its declaration*, so the pairing is the compiler's
 *    job rather than a meta-test's, and the arm that declines still ends in an assertion
 *    ([injectorOrDeclaredGap]) rather than in a bare `return`.
 *
 * **What the fixture still cannot detect, said plainly.** A harness that *could* fault-inject and
 * declares [FaultInjection.Unsupported] anyway is invisible here — there is no capability on
 * [RoomFactory] to check the claim against, and inventing one would put a knob in the contract no
 * consumer asked for. What the arm does buy is that the claim now exists, is attributable, and
 * carries a URL somebody has to keep alive. The residual is narrower, not gone.
 *
 * ### Mutation receipt
 *
 * JVM, `--rerun-tasks` (27/27 EXECUTED). Subjects: `InMemoryRoomConformanceTest` (the reference, 14
 * tests) and `RoomConformanceGapDeclarationTest` (6). **Real** = a defect an implementation could
 * plausibly ship; **synthetic** = a change made purely to reach an assertion no real defect reaches;
 * **rig** = a mutation of this suite itself. The "before" column is the measurement of the hole —
 * what the *pre-#2306* suite did under the same mutation.
 *
 * Two rows, M1 and M4, are gone: they measured properties of the resume credential, which #3159
 * Task 1.7 and #3215 retired along with the credential.
 *
 * | # | Mutation | Kind | after | before |
 * |---|----------|------|-------|--------|
 * | M2 | [injectorOrDeclaredGap] drops its assertion — i.e. the pre-#2306 silent `?: return@runTest`, exactly | rig | RED: all four `blankTrackingUrl*` **as measured — the set was seven from #2501, six since #3159 Task 1.7, five since #3215** | all green; the four gated obligations passed under a gap declaring nothing |
 * | M3 | delete the `proof.roomId != roomId` check in `KeyAdmission.checkProof` — a proof for another room admits | real | **re-pointed (#3212):** RED in `:kuilt-session`'s `KeyAdmissionTest.proofForAnotherRoomIsRejected`, 1 of 23, 2 of its assertions. The controller's own guard, this row's old subject, reds nothing in this suite (40 of 40 green) — see below | every pre-existing test of this suite **green** |
 *
 * **M3's claim is narrower than it looks, and the narrowing matters.** It used to delete the
 * room-id guard in `DefaultJoinerReconnectController.tryResume`. That guard is now
 * unreachable through a room: the room builds every `ProvenResume` from its own id, on a proof whose
 * room id it has already checked. Measured on #3212: deleting it reds `:kuilt-session`'s own
 * `JoinerReconnectControllerTest` (2 of 19) and nothing in this suite (40 of 40 green). Since #3215
 * retired the `Resume` frame, the room-id property rests on one room check, the proof's, which
 * `proofForAnotherRoomIsRejected` pins. Either way the defect is visible to the tree but not to the
 * *contract*, which is precisely what a second [RoomFactory] inherits nothing of: an
 * implementation's private suite is not a conformance obligation.
 *
 * **One assertion has no red anywhere**, and that is correct:
 * `RoomConformanceGapDeclarationTest.aDeclaredGapSkipsEveryGatedObligationCleanly`. It does not
 * describe behaviour under test — it is the survivability check, and its falsifying input is a
 * future edit that moves work *above* the gate (a suspending wait, a `links[0]` access), not any
 * defect present today. Stating it rather than hiding it: an all-red table would mean the table was
 * measuring blast radius instead of diagnoses.
 *
 * ### Mutation receipt — `Room.leave`'s two obligations (#2501)
 *
 * JVM, `--rerun-tasks`, subjects `InMemoryRoomConformanceTest` (19 tests) and
 * `RoomConformanceGapDeclarationTest` (9). Same kinds as above. **All five new properties are green
 * against unmutated `main`, and that is correct** — `SeamRoom.leave` calls `seam.close(...)`
 * unguarded, so nothing mints today; these are live guards, not regression tests for a fix.
 *
 * Every row below was **re-measured** after [theTeardownFaultReallyFires] moved its arms onto links
 * no [Room] owns. One verdict changed, and it changed to a *narrower* one — see the T7 note.
 *
 * | # | Mutation | Kind | verdict |
 * |---|----------|------|---------|
 * | T1 | `SeamRoom.leave` wraps its `seam.close(...)` in `withTimeout(100.milliseconds)` | real | **RED — [leaveDoesNotMintACancellationWhenTeardownIsSlow]**, 1 of its 2 assertions: `Got: kotlinx.coroutines.TimeoutCancellationException: Timed out after 100ms of _virtual_ … time`. Nothing else moves. |
 * | T2 | `closed = true` moves out of `leave`'s opening `lock.withLock` to after `seam.close(...)` | real | **RED — [leaveIsIdempotentEvenWhenTeardownFails]**, 1 of 2, naming both calls' failures. **[leaveIsIdempotent] stays GREEN.** |
 * | T3 | `leave`'s `if (closed) return` fast path deleted outright | real | **RED — [leaveIsIdempotentEvenWhenTeardownFails]** *only*. Both ungated properties GREEN. |
 * | T4 | [TeardownFault.Fails] delegates but does not throw | rig | **RED — [theTeardownFaultReallyFires]**, 1 of 4: `Got: no exception — the close completed`. **[leaveIsIdempotentEvenWhenTeardownFails] goes GREEN BY ABSENCE.** |
 * | T5 | [TeardownFault.Slow] does not delay | rig | **RED — [theTeardownFaultReallyFires]**, 1 of 4: `Expected at least 1000 ms of virtual time to pass, got 0 ms`. |
 * | T6 | T1 **and** T5 together | rig | **RED — [theTeardownFaultReallyFires]** only. [leaveDoesNotMintACancellationWhenTeardownIsSlow] is **GREEN** — T1's real defect has gone invisible. |
 * | T7 | `leave` throws `IllegalStateException("already left")` on a second call | synthetic | **RED — [leaveIsIdempotent]**, [leaveDoesNotReportFailureAsCancellation], [leaveIsIdempotentEvenWhenTeardownFails] and `aDeclaredGapDoesNotExcuseTheUngatedObligations`, each on the raw throw. [theTeardownFaultReallyFires] **is not among them** — see below. |
 *
 * **T3 is the measurement of the gap this whole section closes, and it is the most damning row.**
 * Deleting `leave`'s idempotency guard *entirely* is invisible to [leaveIsIdempotent] — because on
 * a reference whose `close` is synchronous and infallible, re-running the whole teardown is simply
 * harmless. Only the fault-gated companion sees it. That is the #2244 shape stated as a measurement
 * rather than as a worry: a property whose reference cannot reach the failure does not test the
 * failure, and the ungated half of an obligation is not a substitute for the gated half.
 *
 * **T2 says the same thing from the other side.** A successful teardown still reaches the flag, so
 * the happy-path property cannot distinguish "idempotent" from "idempotent only when the first call
 * succeeded". The contrast between T2/T3 and T7 is why both halves are here.
 *
 * **T4 is exactly what [theTeardownFaultReallyFires] exists for, and it also shows what the
 * rig-fired counter alone does not buy.** Under T4 the counter still increments — the arm *was*
 * selected — so [leaveIsIdempotentEvenWhenTeardownFails]'s own precondition holds and its remaining
 * assertion passes on a leave that never failed. The two mechanisms divide the labour cleanly:
 * `FaultySeam.teardownFaultsFired` proves the fault **reached this seam**, and only this test proves
 * the fault **does anything**. Neither subsumes the other.
 *
 * **T5 and T6 are why the `Slow` arm is measured at all — and T5 was GREEN in the first draft.**
 * This test originally measured only [TeardownFault.Fails], and a no-op `Slow` reddened nothing
 * anywhere in the tree: `SeamRoom.leave` bounds nothing today, so a zero-duration teardown mints no
 * cancellation either. T6 is the proof that the omission mattered rather than a guess about it —
 * with `Slow` silently no-op, T1's genuine defect stops being detectable at all. The fix for one
 * vacuity had landed one level up inside itself, which is the recurrence this repo's conventions
 * warn about, caught by asking of the fix what the fix was now unpinned on.
 *
 * **T7 is marked synthetic because no real implementation refuses a second leave on purpose** — it
 * is here to show the two ungated properties *can* red, since T1–T6 never touch them.
 *
 * **T7's blast radius is the receipt for [theTeardownFaultReallyFires]'s bare links.** As first
 * measured, T7 *also* reddened that test: the fixture then armed its fault on a seam a room owned,
 * so closing it made the room self-leave in reaction and the trailing explicit `leave()` was the
 * *second* one. That was blast radius rather than a diagnosis — the row said four tests while the
 * prose explained a fifth, which is a receipt disagreeing with itself. Re-measured after the
 * fixture moved to links no [Room] owns, the fifth hit is **gone**: nothing self-leaves, because
 * nothing owns the links being closed. The row is four, and it is four for a structural reason
 * rather than because the number was edited to agree with a prediction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public abstract class RoomConformanceSuite {

    /**
     * Fast heartbeat config shared by all tests so virtual-time advancement is cheap.
     * Advancing 4 × 100 ms triggers [MembershipEvent.Partitioned];
     * advancing 9 × 100 ms exhausts the reconnect window (PeerLost / HostLost).
     */
    public val fastHeartbeatConfig: HeartbeatConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    /**
     * How long [awaitRoster] / [awaitEvent] / [awaitFrame] wait before failing with the state they
     * observed — **virtual** time, `null` to wait unbounded.
     *
     * Virtual, not wall-clock, is the whole point: this suite's harness runs on the test scheduler
     * ([newHarness] takes the test's scope, and the partition tests drive `advanceTimeBy` +
     * [RoomHarness.advanceClock] in lockstep), so the trajectory is identical on every run and a
     * bound over it is deterministic. A wall-clock bound would assert "this host retires N units of
     * work in T seconds", which is false exactly when the box is busy — the defect
     * `forbidTightRunTestTimeout` exists to stop (#1739 / #1891).
     *
     * Generous relative to this suite's own timescale rather than tight: [fastHeartbeatConfig] ticks
     * every 100 ms and the longest deliberate advancement in the suite is ~1 s, so this leaves ~5×
     * headroom. It is a **wedge** bound, not a latency assertion — nothing here asserts a fabric
     * admits *quickly*, only that it admits. Expiry costs no wall-clock time worth measuring: the
     * scheduler fast-forwards through the intervening virtual ticks.
     *
     * ## Override to `null` if your harness does real I/O
     *
     * A fabric whose delivery does **not** run on the test scheduler — a real socket, a real radio —
     * does not advance the virtual clock, so `runTest` fast-forwards the entire budget while the
     * frame is still on the wire and a working fabric fails. That is not hypothetical: it is
     * kuilt #2069 / #2115, where a `withTimeout(30.seconds)` added to a [SeamConformanceSuite]
     * obligation red-lit `TcpConformanceTest` on a 16 MiB frame that was crossing loopback fine.
     * Such a harness sets this to `null` and takes `runTest`'s own [TEST_WEDGE_BACKSTOP] ceiling as
     * its backstop — losing the named failure, which is the honest trade for a real-I/O fabric.
     */
    public open val awaitBudget: Duration? = 5.seconds

    /**
     * How many unread frames the rooms under test hold per member for [Room.incomingFrom] — the depth
     * the boundary and overflow obligations (4c)–(4e) measure against.
     *
     * Defaulted to the reference's own value because the reference is what this suite builds. A harness
     * over a room with a different depth overrides it; leaving it at the default would put those
     * obligations one boundary away from the room's real one, where they measure nothing.
     */
    public open val memberInboxCapacity: Int = 64

    /**
     * Whether this harness can break and heal the links under the rooms it builds — and, when it
     * cannot, **where that is written down**.
     *
     * Two arms rather than a nullable `FaultyLoom?`, because the two carry different claims and only
     * one of them is a limitation anybody has to justify. `null` said "no fault injection" in a value
     * that costs nothing to write and that no reader has to defend; the second arm below cannot be
     * constructed without naming an issue. That is the difference between a gap that is *declared*
     * and a gap that is merely *taken* — and the whole of #2306, whose four silent returns had never
     * once been exercised because the only subclass is the reference, which takes the first arm.
     *
     * Modelled on `BoltConformanceSuite`'s `DurabilityFixture`, with one deliberate difference:
     * there, both arms are *correct* states a backend may legitimately be in, so both assert
     * behaviour. Here the second arm is a shortfall, so what it asserts is its own accountability
     * ([injectorOrDeclaredGap] fails a blank URL) and the obligation stays unproven. That is the
     * honest shape — inventing an assertion for a state the harness cannot reach would be the
     * vacuity this fixture exists to remove, one level down.
     */
    public sealed interface FaultInjection {

        /**
         * The harness can partition and heal: [loom] wraps the fabric **both** factories weave over,
         * so [setFaultProfileOnAll][FaultyLoom.setFaultProfileOnAll] breaks every link atomically and
         * [links][FaultyLoom.links] reaches one side at a time — index 0 is the host's seam (created
         * by the first `host()` call), index 1 the joiner's (created by `join()`).
         */
        public data class Supported(val loom: FaultyLoom) : FaultInjection

        /**
         * The harness cannot reach the links under its rooms, so this suite's partition, resume,
         * host-loss **and teardown-fault** obligations are **unproven** on it.
         *
         * That last group is the one most worth reading before declaring this arm, because it
         * includes [leaveIsIdempotentEvenWhenTeardownFails] — and the mutation receipt below records
         * that deleting `SeamRoom.leave`'s idempotency guard outright is invisible to the *ungated*
         * [leaveIsIdempotent] and caught **only** there. Declining fault injection therefore gives up
         * the only place `Room.leave`'s idempotency is actually testable, not merely the partition
         * scenarios.
         *
         * [trackingUrl] is not optional and not defaulted: an umbrella constant would let every
         * subclass point at the same permanently-open issue, which is how a declaration decays back
         * into a skip. Point it at the harness's *own* blocking issue — the thing that would have to
         * change for this arm to become [Supported].
         */
        public data class Unsupported(val trackingUrl: String) : FaultInjection
    }

    /**
     * A harness that bundles host and joiner [RoomFactory]s plus its [FaultInjection] fixture.
     *
     * [hostFactory] and [joinerFactory] are **distinct devices**, each with its own device key, and
     * every room [joinerFactory] makes is a device of its own: rooms that share one key store are one
     * device, which the host's key-to-seat table treats as one key holding several seats (#3159).
     *
     * [clock] and [advanceClock] are shared across the harness so the injected
     * clock stays in sync with virtual-time advancement.
     */
    public data class RoomHarness(
        val hostFactory: RoomFactory,
        val joinerFactory: RoomFactory,
        val faults: FaultInjection,
        val clock: () -> Instant,
        val advanceClock: (Long) -> Unit,
    )

    /**
     * Provide a fresh [RoomHarness] for one test, using [scope] as the coroutine
     * scope for background loops ([SeamRoomFactory], [FaultyLoom]).
     *
     * The default implementation returns an [InMemoryLoom]-backed harness wrapped in
     * a [FaultyLoom] so partition tests work out of the box. Subclasses backed by
     * a different fabric should override this method.
     */
    public open fun newHarness(scope: CoroutineScope): RoomHarness = defaultHarness(scope)

    /**
     * Bind the return rows — the friction bar (#3159, Task 1.9) — to the implementation under test.
     *
     * Abstract and not defaulted, unlike [newHarness]: a default reference rig would let a room over
     * another fabric pass these rows by testing the reference instead of itself. A harness that cannot
     * make a device drop off and come back says so with [ReturnFixture.Unsupported] and an issue.
     *
     * [scope] is the test's own: the rows wait in virtual time, so the rig's rooms, clock and fabric
     * must run on its scheduler.
     */
    public abstract fun newReturnHarness(scope: TestScope): ReturnFixture

    // ── (0) The friction bar: a device that comes back (#3159, Task 1.9) ─────
    //
    // Each row is one line of the host's key → seat table, reached through the device's own return:
    // the room's reconnect after a torn link, or the one `join` a relaunched process makes, which is
    // the only app call a row allows (plan Amendment H). What proves the return was the room's own is
    // a bounded wait for it and, where a link healed, [ReturnDevice.heals]; [AppCalls] only stops the
    // row itself from growing a call in the span. Each row also asserts the rig reached the state it is
    // about before reading the outcome.
    //
    // Table row "new id, same key, seat held partitioned" has no tear-and-heal row: a seam healed in
    // place keeps its peer id (`Room.selfId` is the seam's, fixed for the room's life), so a device
    // reaches the host under a new id only as a new room — [processRebuiltWithFreshIdRestores]
    // (Amendment H). "New id, same key, every seat connected" runs on a second live process
    // ([ReturnDevice.launchBeside], #3656), and its host-key form on [ReturnHarness.hostTab].

    /**
     * A device whose link tears and heals comes back to the seat the host held for it: the same id,
     * the same key, one `Resumed`, and no departure or new member — with no call from the app.
     */
    @Test
    public fun returnRestoresHeldSeatSameId(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a healed link restores the held seat under the same id")
                ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val device = h.newDevice()
            val app = AppCalls()
            val joiner = app.factory(device.launch()).join(h.joinTag)
            try {
                val me = joiner.selfId
                val admitted = host.awaitRoster("the device is admitted") { roster -> roster.any { it.id == me } }
                joiner.awaitRoster("the device sees the host") { it.isNotEmpty() }
                val key = admitted.single { it.id == me }.key
                val hostTape = EventTape(host, backgroundScope)
                val joinerTape = EventTape(joiner, backgroundScope)
                val callsAtDrop = app.snapshot()
                val healsAtDrop = device.heals

                device.drop()
                host.awaitRoster("the host holds the dropped device's seat Partitioned") { roster ->
                    roster.any { it.id == me && it.liveness is Liveness.Partitioned }
                }
                device.restore()
                val back = host.awaitRoster("the held seat is Connected again") { roster ->
                    roster.any { it.id == me && it.liveness == Liveness.Connected }
                }
                joiner.awaitRoster("the device's room sees the host connected again") { roster ->
                    roster.any { it.liveness == Liveness.Connected }
                }
                runCurrent() // let the events that announce the return reach the tapes
                val callsAtReturn = app.snapshot()
                val hostEdges = hostTape.edges()
                val joinerEvents = joinerTape.events()
                assertAll(
                    { assertEquals(listOf("join"), callsAtDrop, "rig: the call counter saw the launch's join") },
                    { assertEquals(callsAtDrop, callsAtReturn, "the app made no room or factory call between drop and return") },
                    { assertTrue(device.heals > healsAtDrop, "rig: the device's own reconnect healed its seam (heals ${device.heals}, was $healsAtDrop)") },
                    { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(me)), hostEdges, "the host restores the seat: one Resumed, no Left or Joined") },
                    { assertEquals(listOf(me), back.map { it.id }, "one seat, the same one") },
                    { assertEquals(key, back.single().key, "under the key it was admitted with") },
                    { assertTrue(joinerEvents.none { it is MembershipEvent.HostLost }, "the device's room did not end: $joinerEvents") },
                )
                joiner.broadcast(applicationPayload("back"))
                val reached = host.awaitHeld(me, count = 1, expected = "a frame the restored device sent")
                assertEquals(listOf("back"), reached.map { it.payload.decodeToString() }, "the room the app already held is the one back in session")
            } finally {
                joiner.leave()
                host.leave()
            }
        }

    /**
     * `Resumed` marks the end of a partition, once per partition (plan Amendment E): two drops, two
     * `Resumed`s on the host, and the device's room says it was restored each time. Then a return the
     * host never saw as a partition — a process killed and relaunched under the same id before the host
     * noticed it go — gets its seat back with no `Resumed` at all, since nothing was partitioned.
     *
     * That third arm is what separates "once per partition" from "once per return": every drop in the
     * first two is a partition the host sees, so a room announcing every restore would pass them.
     */
    @Test
    public fun resumeWithinWindowFiresResumed(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("Resumed fires once per partition") ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val device = h.newDevice()
            val app = AppCalls()
            val joiner = app.factory(device.launch()).join(h.joinTag)
            var live: Room = joiner
            try {
                val me = joiner.selfId
                val admitted = host.awaitRoster("the device is admitted") { roster -> roster.any { it.id == me } }
                joiner.awaitRoster("the device sees the host") { it.isNotEmpty() }
                val key = admitted.single { it.id == me }.key
                val hostTape = EventTape(host, backgroundScope)
                val joinerTape = EventTape(joiner, backgroundScope)
                val callsAtFirstDrop = app.snapshot()
                val counts = mutableListOf<Pair<Int, Int>>()
                repeat(2) { episode ->
                    device.drop()
                    host.awaitRoster("partition ${episode + 1}: the host holds the seat Partitioned") { roster ->
                        roster.any { it.id == me && it.liveness is Liveness.Partitioned }
                    }
                    device.restore()
                    host.awaitRoster("partition ${episode + 1}: the seat is Connected again") { roster ->
                        roster.any { it.id == me && it.liveness == Liveness.Connected }
                    }
                    joiner.awaitRoster("partition ${episode + 1}: the device's room sees the host again") { roster ->
                        roster.any { it.liveness == Liveness.Connected }
                    }
                    // Let every retry the episode started land before counting.
                    advanceTimeBy(h.joinerHeartbeat.reconnectWindow)
                    counts += hostTape.events().count { it == MembershipEvent.Resumed(me) } to
                        joinerTape.events().count { it is MembershipEvent.Resumed }
                }
                assertAll(
                    { assertEquals(listOf(1 to 1, 2 to 2), counts, "(host Resumed, device Resumed) after each partition") },
                    { assertEquals(callsAtFirstDrop, app.snapshot(), "the app made no room or factory call across both partitions") },
                    { assertTrue(device.heals >= 2, "rig: the device's own reconnect healed its seam each time (heals ${device.heals})") },
                    {
                        val partitions = hostTape.events().count { it is MembershipEvent.Partitioned && it.peerId == me }
                        assertEquals(2, partitions, "rig: the host saw two partitions, not one long one")
                    },
                )

                // A return the host never saw as a partition: the process dies with its link still open,
                // and the next one, under the same id, says Hello before the host's detector fires.
                device.kill(socketCloses = false)
                val fast = app.factory(device.launch(reusePeerId = true)).join(h.joinTag).also { live = it }
                fast.awaitRoster("the relaunched room sees the host") { roster -> roster.any { it.liveness == Liveness.Connected } }
                val back = host.awaitRoster("the seat is Connected under the same id") { roster ->
                    roster.any { it.id == me && it.liveness == Liveness.Connected }
                }
                advanceTimeBy(h.joinerHeartbeat.reconnectWindow)
                runCurrent()
                assertAll(
                    { assertEquals(me, fast.selfId, "rig: the relaunched process presents the same id") },
                    {
                        val partitions = hostTape.events().count { it is MembershipEvent.Partitioned && it.peerId == me }
                        assertEquals(2, partitions, "rig: the host never saw the relaunch as a partition")
                    },
                    { assertEquals(2, hostTape.events().count { it == MembershipEvent.Resumed(me) }, "no partition, so no Resumed") },
                    { assertEquals(listOf(me), back.map { it.id }, "one seat, the same one") },
                    { assertEquals(key, back.single().key, "under the device's key") },
                    { assertEquals(callsAtFirstDrop + "join", app.snapshot(), "the relaunch's join is the app's only call") },
                    { assertTrue(hostTape.edges().none { it is MembershipEvent.Joined || it is MembershipEvent.Left }, "no departure and no new member: ${hostTape.edges()}") },
                )
            } finally {
                live.leave()
                host.leave()
            }
        }

    /**
     * A different device presenting a connected member's id is refused `KeyConflict`, terminally, and
     * the member keeps its seat and its key (Decided 18). Then the impostor's hold ends: the member's
     * link to the host is torn, and its room comes back through its own reconnect — a re-weave, a
     * Hello, the seat handed back — before its app makes any call.
     *
     * The return is read off the member's own `Resumed` and the rig's [ReturnDevice.heals], never off
     * a frame the app sends: a send from the member would itself put its link back in use, and the
     * row would then be measuring the app's call rather than the room's reconnect.
     */
    @Test
    public fun differentKeyOnLiveIdIsRefused(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a different key on a connected member's id is refused")
                ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val victimDevice = h.newDevice()
            val app = AppCalls()
            val victim = app.factory(victimDevice.launch()).join(h.joinTag)
            var impostor: Room? = null
            try {
                val me = victim.selfId
                val admitted = host.awaitRoster("the member is admitted") { roster -> roster.any { it.id == me } }
                victim.awaitRoster("the member sees the host") { it.isNotEmpty() }
                val key = admitted.single { it.id == me }.key
                val hostTape = EventTape(host, backgroundScope)
                val victimTape = EventTape(victim, backgroundScope)
                val callsBefore = app.snapshot()

                val arrived = victimDevice.impostor().join(h.joinTag).also { impostor = it }
                val impostorTape = EventTape(arrived, backgroundScope)
                val refused = arrived.awaitEvent("the impostor's AdmissionFailed") { it as? MembershipEvent.AdmissionFailed }
                runCurrent()
                val seatAtRefusal = host.roster.value.singleOrNull { it.id == me }
                val edgesAtRefusal = hostTape.edges()
                assertAll(
                    { assertEquals(me, arrived.selfId, "rig: the impostor presents the member's id") },
                    { assertEquals(RejectCode.KeyConflict, (refused.reason as? AdmissionFailure.Rejected)?.code, "refused for the key, terminally: $refused") },
                    { assertEquals(Liveness.Connected, seatAtRefusal?.liveness, "rig: the seat was connected when the impostor was refused") },
                    { assertEquals(key, seatAtRefusal?.key, "the seat keeps the member's key") },
                    { assertEquals(emptyList(), edgesAtRefusal, "the host neither drops the member nor admits the impostor") },
                    { assertTrue(impostorTape.events().none { it is MembershipEvent.Joined }, "the impostor never entered a roster") },
                )

                val healsBeforeReturn = victimDevice.heals
                victimDevice.restore()
                victim.awaitEvent("the member's own Resumed") { (it as? MembershipEvent.Resumed)?.takeIf { e -> e.peerId == me } }
                val back = host.awaitRoster("the member's seat is Connected again") { roster ->
                    roster.any { it.id == me && it.liveness == Liveness.Connected }
                }
                val callsAtReturn = app.snapshot()
                val healsAtReturn = victimDevice.heals
                victim.broadcast(applicationPayload("still-here"))
                val reached = host.awaitHeld(me, count = 1, expected = "a frame the member sent after the impostor left")
                assertAll(
                    { assertEquals(listOf("join"), callsBefore, "rig: the call counter saw the launch's join") },
                    { assertTrue(healsAtReturn > healsBeforeReturn, "rig: the member's own reconnect re-wove after the hold (heals $healsAtReturn, was $healsBeforeReturn)") },
                    { assertEquals(callsBefore, callsAtReturn, "the member's app made no call before its room was back") },
                    { assertTrue(victimTape.events().none { it is MembershipEvent.HostLost }, "the member's room never ended: ${victimTape.events()}") },
                    { assertEquals(listOf(me), back.map { it.id }, "one seat, still the member's") },
                    { assertEquals(key, back.single().key, "under the member's key") },
                    { assertEquals(listOf("still-here"), reached.map { it.payload.decodeToString() }) },
                    { assertTrue(hostTape.edges().none { it is MembershipEvent.Joined || it is MembershipEvent.Left }, "no departure and no new member: ${hostTape.edges()}") },
                )
            } finally {
                impostor?.leave()
                victim.leave()
                host.leave()
            }
        }

    /**
     * A device whose key store was cleared while its peer id persisted comes back under that id with a
     * key it never held, while the host holds the old seat partitioned. The host lets that seat go and
     * admits the newcomer as an ordinary new member under the new key (Decided 18, 23): the old
     * membership leaves `PartitionExpired`, a new one joins, and the old key holds nothing. The only
     * app call is the relaunch's `join`.
     *
     * An expired hold followed by an ordinary admission emits the same two events, so the row also
     * times it: the newcomer is seated well inside the host's hold, which the hold's own timer could
     * not have ended. The release came from the newcomer's Proof.
     */
    @Test
    public fun aDifferentKeyOnAPartitionedIdReleasesTheSeat(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a different key on a partitioned member's id releases the seat")
                ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val device = h.newDevice()
            val app = AppCalls()
            val first = app.factory(device.launch()).join(h.joinTag)
            var cleared: Room? = null
            try {
                val me = first.selfId
                val admitted = host.awaitRoster("the device is admitted") { roster -> roster.any { it.id == me } }
                first.awaitRoster("the device sees the host") { it.isNotEmpty() }
                val oldKey = admitted.single { it.id == me }.key
                val hostTape = EventTape(host, backgroundScope)

                device.kill()
                host.awaitRoster("the host holds the dead process's seat Partitioned") { roster ->
                    roster.any { it.id == me && it.liveness is Liveness.Partitioned }
                }
                val partitionedAt = testScheduler.currentTime
                val callsAtKill = app.snapshot()
                val second = app.factory(device.impostor()).join(h.joinTag).also { cleared = it }
                val after = host.awaitRoster("the id is seated again, under a new key") { roster ->
                    roster.any { it.id == me && it.key != oldKey && it.liveness == Liveness.Connected }
                }
                val seatedAfter = (testScheduler.currentTime - partitionedAt).milliseconds
                val seen = second.awaitRoster("the newcomer is admitted and sees the host") { it.isNotEmpty() }
                runCurrent() // let the events that announce the admission reach the tapes
                assertAll(
                    { assertEquals(me, second.selfId, "rig: the newcomer presents the old id") },
                    {
                        assertTrue(
                            seatedAfter < h.hostHeartbeat.reconnectWindow,
                            "the seat was released at the Proof, not by its hold expiring: seated $seatedAfter after " +
                                "the seat went Partitioned, against a ${h.hostHeartbeat.reconnectWindow} hold",
                        )
                    },
                    { assertEquals(callsAtKill + "join", app.snapshot(), "the relaunch's join is the app's only call") },
                    {
                        assertEquals(
                            listOf("Left(${me.value}, ${LeaveReason.PartitionExpired})", "Joined(${me.value})"),
                            hostTape.edges().map { it.edge() },
                            "the old seat is let go, then the newcomer joins as a new member",
                        )
                    },
                    { assertEquals(1, after.count { it.id == me }, "one seat under the id") },
                    { assertTrue(after.none { it.key == oldKey }, "the old key holds no seat: ${after.map { it.id to it.key }}") },
                    { assertTrue(hostTape.events().none { it is MembershipEvent.Resumed }, "a new key is never a restore") },
                    { assertTrue(seen.isNotEmpty(), "the newcomer is admitted, not refused") },
                )
            } finally {
                cleared?.leave()
                host.leave()
            }
        }

    /**
     * A process killed without a goodbye and started again on the same device, under the same peer
     * id, inside the host's hold, gets the held seat back. The only app call is the relaunch's `join`:
     * no token, no `resume`.
     */
    @Test
    public fun processRebuiltInsideWindowRestores(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a rebuilt process with the same id restores the held seat")
                ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val device = h.newDevice()
            val app = AppCalls()
            val first = app.factory(device.launch()).join(h.joinTag)
            var rebuilt: Room? = null
            try {
                val me = first.selfId
                val admitted = host.awaitRoster("the device is admitted") { roster -> roster.any { it.id == me } }
                first.awaitRoster("the device sees the host") { it.isNotEmpty() }
                val key = admitted.single { it.id == me }.key
                val hostTape = EventTape(host, backgroundScope)
                val callsAtKill = app.snapshot()

                device.kill()
                host.awaitRoster("the host holds the dead process's seat Partitioned") { roster ->
                    roster.any { it.id == me && it.liveness is Liveness.Partitioned }
                }
                val second = app.factory(device.launch(reusePeerId = true)).join(h.joinTag).also { rebuilt = it }
                host.awaitEvent("the host's Resumed") { it as? MembershipEvent.Resumed }
                val back = host.awaitRoster("the seat is Connected again") { roster ->
                    roster.any { it.id == me && it.liveness == Liveness.Connected }
                }
                second.awaitRoster("the rebuilt room sees the host") { it.isNotEmpty() }
                runCurrent() // let the events that announce the return reach the tapes
                val callsAtReturn = app.snapshot()
                assertAll(
                    { assertEquals(me, second.selfId, "rig: the rebuilt process presents the same id") },
                    { assertEquals(callsAtKill + "join", callsAtReturn, "the relaunch's join is the app's only call") },
                    { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(me)), hostTape.edges(), "one Resumed, no Left or Joined") },
                    { assertEquals(listOf(me), back.map { it.id }, "one seat, the same one") },
                    { assertEquals(key, back.single().key, "under the device's key") },
                )
                second.broadcast(applicationPayload("Rebuilt"))
                val reached = host.awaitHeld(me, count = 1, expected = "a frame the rebuilt process sent")
                assertEquals(listOf("Rebuilt"), reached.map { it.payload.decodeToString() })
            } finally {
                rebuilt?.leave()
                host.leave()
            }
        }

    /**
     * A process killed and started again under a **fresh** peer id, while the host holds its old seat,
     * inherits that seat: the old id leaves `Superseded(by)` the new one, which joins — one pair of
     * events, heard by the host and by a member who stayed — and the key holds one seat, not two.
     */
    @Test
    public fun processRebuiltWithFreshIdRestores(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a rebuilt process with a fresh id inherits the held seat")
                ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val device = h.newDevice()
            val bystander = h.newDevice().launch().join(h.joinTag)
            val app = AppCalls()
            val first = app.factory(device.launch()).join(h.joinTag)
            var rebuilt: Room? = null
            try {
                val old = first.selfId
                val admitted = host.awaitRoster("both devices are admitted") { roster ->
                    roster.any { it.id == old } && roster.any { it.id == bystander.selfId }
                }
                bystander.awaitRoster("the bystander lists the device") { roster -> roster.any { it.id == old } }
                val key = admitted.single { it.id == old }.key
                val hostTape = EventTape(host, backgroundScope)
                val bystanderTape = EventTape(bystander, backgroundScope)
                val callsAtKill = app.snapshot()

                device.kill()
                host.awaitRoster("the host holds the dead process's seat Partitioned") { roster ->
                    roster.any { it.id == old && it.liveness is Liveness.Partitioned }
                }
                val second = app.factory(device.launch(reusePeerId = false)).join(h.joinTag).also { rebuilt = it }
                val fresh = second.selfId
                val after = host.awaitRoster("the new id holds the seat and the old one is gone") { roster ->
                    roster.any { it.id == fresh } && roster.none { it.id == old }
                }
                bystander.awaitRoster("the bystander lists the new id in place of the old") { roster ->
                    roster.any { it.id == fresh } && roster.none { it.id == old }
                }
                runCurrent() // let the events that announce the return reach the tapes
                val callsAtReturn = app.snapshot()
                val expected = listOf("Left(${old.value}, ${LeaveReason.Superseded(fresh)})", "Joined(${fresh.value})")
                assertAll(
                    { assertTrue(fresh != old, "rig: the rebuilt process presents a fresh id") },
                    { assertEquals(callsAtKill + "join", callsAtReturn, "the relaunch's join is the app's only call") },
                    { assertEquals(expected, hostTape.edges().map { it.edge() }, "the host: the old id superseded, then the new one joined") },
                    { assertEquals(expected, bystanderTape.edges().map { it.edge() }, "a member present for it hears the same pair") },
                    { assertEquals(listOf(key), after.filter { it.id == fresh || it.id == old }.map { it.key }, "the key holds one seat") },
                    { assertTrue(hostTape.edges().none { it is MembershipEvent.Left && it.reason == LeaveReason.PartitionExpired }, "the seat was inherited, not let go") },
                )
            } finally {
                rebuilt?.leave()
                bystander.leave()
                host.leave()
            }
        }

    /**
     * A second live process on one device — a second tab over the same key store — gets a seat of
     * its own (Decided 30): the host meets a known key under a new id while that key's only seat is
     * still connected, so it adds a seat and supersedes nothing. Both processes stay members under
     * the one key, and each lists the other.
     *
     * Then the first process's link dies. Its seat is not handed to the second, which already holds
     * one: the host holds it partitioned, then lets it go `PartitionExpired`, and the second process
     * keeps its seat throughout. The only app calls are the two processes' own `join`s.
     */
    @Test
    public fun aSecondLiveProcessGetsAnAdditionalSeat(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a second live process on one key gets an additional seat")
                ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val device = h.newDevice()
            val app = AppCalls()
            val first = app.factory(device.launch()).join(h.joinTag)
            var second: Room? = null
            try {
                val one = first.selfId
                val admitted = host.awaitRoster("the first process is admitted") { roster -> roster.any { it.id == one } }
                first.awaitRoster("the first process sees the host") { it.isNotEmpty() }
                val key = admitted.single { it.id == one }.key
                val hostTape = EventTape(host, backgroundScope)
                val firstTape = EventTape(first, backgroundScope)

                val tab = app.factory(device.launchBeside()).join(h.joinTag).also { second = it }
                val two = tab.selfId
                val tabTape = EventTape(tab, backgroundScope)
                host.awaitRoster("the second process holds a seat") { roster -> roster.any { it.id == two } }
                tab.awaitRoster("the second process sees the host") { it.isNotEmpty() }
                // Long enough for a host that supersedes late, or a room that says Hello again, to show.
                advanceTimeBy(h.hostHeartbeat.timeout + h.hostHeartbeat.reconnectWindow)
                runCurrent()
                val both = listOf(one, two).map { id -> host.roster.value.singleOrNull { it.id == id } }
                val callsWhileBoth = app.snapshot()
                assertAll(
                    { assertTrue(two != one, "rig: the second process presents an id of its own") },
                    { assertEquals(key, both[1]?.key, "rig: the second process proved the device's key: ${host.roster.value.map { it.id to it.key }}") },
                    { assertEquals(listOf(key, key), both.map { it?.key }, "the key holds two seats, the first process's and the second's: ${host.roster.value.map { it.id to it.key }}") },
                    { assertEquals(listOf(Liveness.Connected, Liveness.Connected), both.map { it?.liveness }, "both seats stay connected: $both") },
                    { assertTrue(first.roster.value.any { it.id == two } && tab.roster.value.any { it.id == one }, "each process lists the other") },
                    { assertEquals(listOf("Joined(${two.value})"), hostTape.edges().map { it.edge() }, "the host adds a seat and supersedes nothing") },
                    { assertTrue(firstTape.events().none { it is MembershipEvent.Left || it is MembershipEvent.HostLost }, "the first process was never displaced: ${firstTape.events()}") },
                    { assertTrue(tabTape.events().none { it is MembershipEvent.Left || it is MembershipEvent.HostLost }, "nor the second: ${tabTape.events()}") },
                    { assertEquals(listOf("join", "join"), callsWhileBoth, "each process's own join is the app's only call") },
                )

                device.kill()
                host.awaitRoster("the host holds the first process's seat Partitioned") { roster ->
                    roster.any { it.id == one && it.liveness is Liveness.Partitioned }
                }
                val partitionedAt = testScheduler.currentTime
                val mark = hostTape.events().size
                val after = host.awaitRoster("the host lets the first process's seat go") { roster -> roster.none { it.id == one } }
                val heldFor = (testScheduler.currentTime - partitionedAt).milliseconds
                tab.awaitRoster("the second process drops the first's seat too") { roster -> roster.none { it.id == one } }
                runCurrent()
                assertAll(
                    {
                        assertEquals(
                            listOf("Left(${one.value}, ${LeaveReason.PartitionExpired})"),
                            hostTape.events().drop(mark).filter { it is MembershipEvent.Joined || it is MembershipEvent.Left || it is MembershipEvent.Resumed }.map { it.edge() },
                            "the dead process's seat expires: not superseded by the live one, which already has a seat",
                        )
                    },
                    {
                        assertTrue(
                            heldFor >= h.hostHeartbeat.reconnectWindow,
                            "the seat went when its hold ran out ($heldFor after it went Partitioned, against a ${h.hostHeartbeat.reconnectWindow} hold)",
                        )
                    },
                    { assertEquals(Liveness.Connected, after.singleOrNull { it.id == two }?.liveness, "the second process keeps its seat: $after") },
                    { assertEquals(key, after.singleOrNull { it.id == two }?.key, "under the device's key") },
                    {
                        assertEquals(
                            listOf<MembershipEvent>(MembershipEvent.Left(one, LeaveReason.PartitionExpired)),
                            tabTape.events().filter { it is MembershipEvent.Left },
                            "a member present for it hears the same expiry",
                        )
                    },
                    { assertEquals(callsWhileBoth, app.snapshot(), "the app made no call while the first process's seat went") },
                )
            } finally {
                second?.leave()
                host.leave()
            }
        }

    /**
     * A joiner presenting the **host's own** key — the host's second tab — gets a joiner seat of its
     * own, and the host stays the host (Decided 30, the design's host-key row): the host decides that
     * key against the joiner seats it holds, never against its own membership. So the first tab is
     * admitted beside the host rather than refused or taken for it, and a second tab, arriving while
     * the first is connected, gets an additional seat. A member present for both hears two `Joined`s,
     * and still lists the host under its own id and key.
     *
     * What the first tab's admission is *called* is open for slice 2 (plan Amendment I: rules 6–7, or
     * an additional seat), so the row asserts the seat and not which admission path made it.
     */
    @Test
    public fun aJoinerPresentingTheHostsKeyGetsAJoinerSeat(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a joiner presenting the host's own key gets a joiner seat")
                ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val bystander = h.newDevice().launch().join(h.joinTag)
            val app = AppCalls()
            var firstTab: Room? = null
            var secondTab: Room? = null
            try {
                val hostId = host.selfId
                host.awaitRoster("the bystander is admitted") { roster -> roster.any { it.id == bystander.selfId } }
                val hostAsSeen = bystander.awaitRoster("the bystander sees the host") { roster -> roster.any { it.id == hostId } }
                val hostKey = hostAsSeen.single { it.id == hostId }.key
                val bystanderKey = host.roster.value.single { it.id == bystander.selfId }.key
                val hostTape = EventTape(host, backgroundScope)
                val bystanderTape = EventTape(bystander, backgroundScope)

                val tab1 = app.factory(h.hostTab()).join(h.joinTag).also { firstTab = it }
                val tab1Tape = EventTape(tab1, backgroundScope)
                // A bounded settle rather than a wait for the seat, so a host that refuses the tab reds
                // on the assertion below, naming the refusal, instead of on a wait's budget.
                advanceTimeBy(h.hostHeartbeat.timeout + h.hostHeartbeat.reconnectWindow)
                runCurrent()
                val firstSeat = host.roster.value.singleOrNull { it.id == tab1.selfId }
                assertAll(
                    { assertTrue(tab1Tape.events().none { it is MembershipEvent.AdmissionFailed }, "the host's first tab is not refused: ${tab1Tape.events()}") },
                    { assertEquals(Liveness.Connected, firstSeat?.liveness, "the host's first tab holds a connected joiner seat: ${host.roster.value}") },
                    { assertTrue(firstSeat == null || firstSeat.key == hostKey, "rig: the first tab proved the host's own key: ${firstSeat?.key}") },
                    { assertEquals(Liveness.Connected, tab1.roster.value.singleOrNull { it.id == hostId }?.liveness, "the first tab sees the host") },
                )
                val tab2 = app.factory(h.hostTab()).join(h.joinTag).also { secondTab = it }
                host.awaitRoster("the host's second tab holds a seat") { roster -> roster.any { it.id == tab2.selfId && it.liveness == Liveness.Connected } }
                tab2.awaitRoster("the second tab sees the host") { roster -> roster.any { it.id == hostId } }
                // Long enough for a host that supersedes late, or a fan-out to land, to show.
                advanceTimeBy(h.hostHeartbeat.timeout + h.hostHeartbeat.reconnectWindow)
                runCurrent()
                val seated = host.roster.value
                val tabs = listOf(tab1.selfId, tab2.selfId)
                val tabSeats = tabs.map { id -> seated.singleOrNull { it.id == id } }
                assertAll(
                    { assertTrue(hostKey != bystanderKey, "rig: the bystander's key is its own, so the host's key is told apart") },
                    { assertEquals(hostKey, tabSeats[1]?.key, "rig: the second tab proved the host's own key") },
                    { assertEquals(listOf(hostKey, hostKey), tabSeats.map { it?.key }, "the host's key holds both tab seats: ${seated.map { it.id to it.key }}") },
                    { assertTrue(hostId !in tabs && tab1.selfId != tab2.selfId, "rig: each tab presents an id of its own: $tabs") },
                    { assertEquals(listOf(Liveness.Connected, Liveness.Connected), tabSeats.map { it?.liveness }, "each tab holds a connected joiner seat") },
                    { assertEquals(SessionRole.Host, host.role.value, "the host is still the host") },
                    { assertEquals(listOf(SessionRole.Joiner, SessionRole.Joiner), listOf(tab1.role.value, tab2.role.value), "each tab is a joiner") },
                    { assertEquals(tabs.map { "Joined(${it.value})" }, hostTape.edges().map { it.edge() }, "the host adds two seats and lets none go") },
                    { assertEquals(tabs.map { "Joined(${it.value})" }, bystanderTape.edges().map { it.edge() }, "a member present for it hears the same two joins") },
                    { assertEquals(hostKey, bystander.roster.value.singleOrNull { it.id == hostId }?.key, "the bystander still lists the host under its own id and key") },
                    { assertEquals(hostKey, tab1.roster.value.singleOrNull { it.id == hostId }?.key, "the first tab lists the host as the host") },
                    { assertTrue(bystander.roster.value.map { it.id }.containsAll(tabs), "the bystander lists both tabs: ${bystander.roster.value.map { it.id }}") },
                    { assertTrue(tab2.roster.value.any { it.id == tab1.selfId }, "the second tab lists the first") },
                    { assertEquals(listOf("join", "join"), app.snapshot(), "each tab's own join is the app's only call") },
                )
            } finally {
                secondTab?.leave()
                firstTab?.leave()
                bystander.leave()
                host.leave()
            }
        }

    /**
     * A device whose link heals only after the host let its seat go is admitted afresh, and its room
     * says so: `Readmitted(self)`, never `Recovered(host)` (plan Amendment G). The room stays in
     * session, the host sees the old membership leave and a new one join, and the app makes no call.
     */
    @Test
    public fun aReturnAfterTheSeatWentIsReadmitted(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a return after the seat went is readmitted") ?: return@runTest
            h.requireReadmissionReachable()
            val host = h.hostFactory.host(h.hostPattern)
            val device = h.newDevice()
            val app = AppCalls()
            val joiner = app.factory(device.launch()).join(h.joinTag)
            try {
                val me = joiner.selfId
                host.awaitRoster("the device is admitted") { roster -> roster.any { it.id == me } }
                joiner.awaitRoster("the device sees the host") { it.isNotEmpty() }
                val hostTape = EventTape(host, backgroundScope)
                val joinerTape = EventTape(joiner, backgroundScope)
                val callsAtDrop = app.snapshot()
                val healsAtDrop = device.heals

                device.drop()
                host.awaitRoster("the host lets the dropped device's seat go") { roster -> roster.none { it.id == me } }
                val joinerBeforeReturn = joinerTape.events()
                device.restore()
                joiner.awaitEvent("the device's Readmitted") { it as? MembershipEvent.Readmitted }
                host.awaitRoster("the device is a member again") { roster ->
                    roster.any { it.id == me && it.liveness == Liveness.Connected }
                }
                runCurrent() // let the events that announce the return reach the tapes
                val callsAtReturn = app.snapshot()
                val joinerEvents = joinerTape.events()
                assertAll(
                    { assertEquals(listOf("join"), callsAtDrop, "rig: the call counter saw the launch's join") },
                    { assertTrue(joinerBeforeReturn.none { it is MembershipEvent.HostLost }, "rig: the device was still trying when its seat went: $joinerBeforeReturn") },
                    { assertTrue(device.heals > healsAtDrop, "rig: the device's own reconnect healed its seam (heals ${device.heals}, was $healsAtDrop)") },
                    { assertEquals(callsAtDrop, callsAtReturn, "the app made no room or factory call between drop and return") },
                    { assertEquals(listOf<MembershipEvent>(MembershipEvent.Readmitted(me)), joinerEvents.filterIsInstance<MembershipEvent.Readmitted>(), "one Readmitted, naming this room") },
                    { assertTrue(joinerEvents.none { it is MembershipEvent.Recovered }, "a readmission is never Recovered(host): $joinerEvents") },
                    { assertTrue(joinerEvents.none { it is MembershipEvent.Resumed || it is MembershipEvent.HostLost }, "neither restored nor ended: $joinerEvents") },
                    {
                        assertEquals(
                            listOf("Left(${me.value}, ${LeaveReason.PartitionExpired})", "Joined(${me.value})"),
                            hostTape.edges().map { it.edge() },
                            "the host: the old membership expired, then a new one joined",
                        )
                    },
                )
                joiner.broadcast(applicationPayload("Readmitted"))
                val reached = host.awaitHeld(me, count = 1, expected = "a frame the readmitted device sent")
                assertEquals(listOf("Readmitted"), reached.map { it.payload.decodeToString() }, "the room the app already held is the one in session")
            } finally {
                joiner.leave()
                host.leave()
            }
        }

    /**
     * A readmitted device rebuilds its roster from the host's admission burst: a member who left while
     * its link was down — whose departure went down that link and never arrived — is dropped with
     * `LeaveReason.Unobserved`, and a member who stayed connected throughout is still listed (plan
     * Amendment G).
     *
     * The row first proves the ghost is real: until the return, the device's room still lists the
     * member who left, with no `Left` for it. A rig whose departures reached the dropped device would
     * leave nothing for the rebuild to do, and the row would pass on a room that rebuilds nothing.
     */
    @Test
    public fun aReadmittedJoinerDropsAPeerWhoLeftDuringTheOutage(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("a readmitted joiner drops a peer who left during the outage")
                ?: return@runTest
            h.requireReadmissionReachable()
            val host = h.hostFactory.host(h.hostPattern)
            val hostTape = EventTape(host, backgroundScope)
            val stayer = h.newDevice().launch().join(h.joinTag)
            val leaver = h.newDevice().launch().join(h.joinTag)
            val device = h.newDevice()
            val app = AppCalls()
            val joiner = app.factory(device.launch()).join(h.joinTag)
            try {
                val me = joiner.selfId
                val ghost = leaver.selfId
                val bystander = stayer.selfId
                host.awaitRoster("all three devices are admitted") { roster -> roster.map { it.id }.containsAll(listOf(me, ghost, bystander)) }
                joiner.awaitRoster("the device lists both other members") { roster -> roster.map { it.id }.containsAll(listOf(ghost, bystander)) }
                val joinerTape = EventTape(joiner, backgroundScope)
                val stayerTape = EventTape(stayer, backgroundScope)
                val callsAtDrop = app.snapshot()

                device.drop()
                host.awaitRoster("the host holds the dropped device's seat Partitioned") { roster ->
                    roster.any { it.id == me && it.liveness is Liveness.Partitioned }
                }
                leaver.leave()
                host.awaitRoster("the leaver is gone from the host") { roster -> roster.none { it.id == ghost } }
                host.awaitRoster("the host lets the dropped device's seat go") { roster -> roster.none { it.id == me } }
                runCurrent()
                val ghostBeforeReturn = joiner.roster.value.any { it.id == ghost }
                val heardTheLeave = joinerTape.events().any { it is MembershipEvent.Left && it.peerId == ghost }

                device.restore()
                joiner.awaitEvent("the device's Readmitted") { it as? MembershipEvent.Readmitted }
                val rebuilt = joiner.awaitRoster("the rebuilt roster drops the leaver") { roster -> roster.none { it.id == ghost } }
                runCurrent()
                val joinerEvents = joinerTape.events()
                assertAll(
                    { assertTrue(ghostBeforeReturn, "rig: the device still listed the leaver before its return — the ghost was reached") },
                    { assertTrue(!heardTheLeave, "rig: the leaver's departure never reached the dropped device") },
                    { assertEquals(callsAtDrop, app.snapshot(), "the app made no room or factory call between drop and return") },
                    {
                        assertEquals(
                            listOf<MembershipEvent>(MembershipEvent.Left(ghost, LeaveReason.Unobserved)),
                            joinerEvents.filter { it is MembershipEvent.Left && it.peerId == ghost },
                            "the leaver leaves the device's roster once, as Unobserved: $joinerEvents",
                        )
                    },
                    {
                        assertEquals(
                            Liveness.Connected,
                            rebuilt.singleOrNull { it.id == bystander }?.liveness,
                            "the member connected throughout is still listed: ${rebuilt.map { it.id }}",
                        )
                    },
                    { assertTrue(joinerEvents.none { it is MembershipEvent.Left && it.peerId == bystander }, "the bystander never left the device's roster") },
                    { assertTrue(stayerTape.events().none { it is MembershipEvent.Partitioned && it.peerId == host.selfId }, "rig: the bystander never lost the host") },
                    { assertTrue(hostTape.events().none { it is MembershipEvent.Partitioned && it.peerId == bystander }, "rig: the host never lost the bystander") },
                )
            } finally {
                joiner.leave()
                stayer.leave()
                host.leave()
            }
        }

    /**
     * `Superseded` is promised to the members present for it (plan Amendment G). A member that was
     * away when another device's process came back under a fresh id and took its old seat over hears
     * neither half of the takeover: the host's `Farewell` naming the successor went down its dead
     * link. When it comes back inside the host's hold, the host restores its seat and re-sends the
     * burst, so it hears `Joined(new)` from the burst, and its roster rebuild then drops the old id as
     * `LeaveReason.Unobserved`. It never says `Superseded`, which would claim a takeover it did not
     * see, and keeps no ghost of the old id. The away member's app makes no call.
     *
     * The row first proves the ghost is real, as [aReadmittedJoinerDropsAPeerWhoLeftDuringTheOutage]
     * does: until the return, the away member still lists the old id and has heard nothing of the new.
     * It also proves the return was a **restore**, not a readmission, since that row already covers a
     * readmission's rebuild.
     */
    @Test
    public fun anAwayJoinerHearsASupersedeThroughItsRosterRebuild(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = returnHarnessOrDeclaredGap("an away joiner hears a supersede through its roster rebuild")
                ?: return@runTest
            val host = h.hostFactory.host(h.hostPattern)
            val rebuiltDevice = h.newDevice()
            val firstProcess = rebuiltDevice.launch().join(h.joinTag)
            val awayDevice = h.newDevice()
            val app = AppCalls()
            val away = app.factory(awayDevice.launch()).join(h.joinTag)
            var successor: Room? = null
            try {
                val old = firstProcess.selfId
                val me = away.selfId
                host.awaitRoster("both devices are admitted") { roster -> roster.any { it.id == old } && roster.any { it.id == me } }
                away.awaitRoster("the away member lists the other device") { roster -> roster.any { it.id == old } }
                val hostTape = EventTape(host, backgroundScope)
                val awayTape = EventTape(away, backgroundScope)
                val callsAtDrop = app.snapshot()
                val healsAtDrop = awayDevice.heals

                // The member goes away, and the other device's process dies.
                awayDevice.drop()
                host.awaitRoster("the host holds the away member's seat Partitioned") { roster ->
                    roster.any { it.id == me && it.liveness is Liveness.Partitioned }
                }
                val droppedAt = testScheduler.currentTime
                rebuiltDevice.kill()
                host.awaitRoster("the host holds the dead process's seat Partitioned") { roster ->
                    roster.any { it.id == old && it.liveness is Liveness.Partitioned }
                }

                // It comes back under a fresh id and takes its old seat over while the member is away.
                val rebuilt = rebuiltDevice.launch(reusePeerId = false).join(h.joinTag).also { successor = it }
                val new = rebuilt.selfId
                host.awaitRoster("the new id took the old seat over") { roster -> roster.any { it.id == new } && roster.none { it.id == old } }
                runCurrent()
                val hostEdgesAtTakeover = hostTape.edges().map { it.edge() }
                val awayHeldAtTakeover = host.roster.value.singleOrNull { it.id == me }?.liveness
                val ghostBeforeReturn = away.roster.value.any { it.id == old }
                val heardBeforeReturn = awayTape.events().filter { it.isAbout(old, new) }

                awayDevice.restore()
                away.awaitEvent("the away member's own Resumed") { (it as? MembershipEvent.Resumed)?.takeIf { e -> e.peerId == me } }
                away.awaitRoster("the away member lists the new id") { roster -> roster.any { it.id == new } }
                runCurrent()
                val rosterAtReturn = away.roster.value.map { it.id }.toSet()
                val returnedAfter = (testScheduler.currentTime - droppedAt).milliseconds
                // Then past every window, so a later drop of the old id by some other route would show.
                advanceTimeBy(h.joinerHeartbeat.reconnectWindow * 2)
                runCurrent()
                val awayEvents = awayTape.events()
                assertAll(
                    { assertTrue(new != old, "rig: the rebuilt process presents a fresh id") },
                    {
                        assertEquals(
                            listOf("Left(${old.value}, ${LeaveReason.Superseded(new)})", "Joined(${new.value})"),
                            hostEdgesAtTakeover,
                            "rig: the host superseded the old id while the member was away",
                        )
                    },
                    { assertIs<Liveness.Partitioned>(awayHeldAtTakeover, "rig: the host held the away member's seat, so its return is a restore") },
                    { assertTrue(ghostBeforeReturn, "rig: the away member still listed the old id before its return — the ghost was reached") },
                    { assertEquals(emptyList(), heardBeforeReturn, "rig: nothing of the takeover reached the away member") },
                    { assertTrue(awayDevice.heals > healsAtDrop, "rig: the member's own reconnect healed its seam (heals ${awayDevice.heals}, was $healsAtDrop)") },
                    { assertTrue(awayEvents.none { it is MembershipEvent.Readmitted }, "rig: a restore, not a readmission: $awayEvents") },
                    {
                        assertTrue(
                            returnedAfter < h.joinerHeartbeat.reconnectWindow,
                            "rig: the member was back ($returnedAfter after its drop) before its own ${h.joinerHeartbeat.reconnectWindow} window could drop the old id",
                        )
                    },
                    {
                        assertEquals(
                            listOf("Joined(${new.value})", "Left(${old.value}, ${LeaveReason.Unobserved})"),
                            awayEvents.filter { it.isAbout(old, new) }.map { it.edge() },
                            "the away member hears the successor in its burst, then drops the old id at the rebuild: $awayEvents",
                        )
                    },
                    { assertTrue(awayEvents.none { it is MembershipEvent.Left && it.reason is LeaveReason.Superseded }, "it never says Superseded: $awayEvents") },
                    { assertTrue(old !in rosterAtReturn && new in rosterAtReturn, "no ghost of the old id once it is back: $rosterAtReturn") },
                    { assertTrue(away.roster.value.none { it.id == old }, "nor later: ${away.roster.value.map { it.id }}") },
                    { assertEquals(callsAtDrop, app.snapshot(), "the away member's app made no call between drop and return") },
                )
            } finally {
                successor?.leave()
                away.leave()
                host.leave()
            }
        }

    // ── (1) host → role = Host, selfId is non-blank ──────────────────────────

    @Test
    public fun hostFactoryAssignsHostRole(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val room = h.hostFactory.host(Pattern("Alice"))
            assertEquals(SessionRole.Host, room.role.value, "host() must produce SessionRole.Host")
            assertTrue(room.selfId.value.isNotBlank(), "selfId must be non-blank")
            room.leave()
        }

    // ── (2) join → role = Joiner; both peers admitted ────────────────────────

    @Test
    public fun joinFactoryAssignsJoinerRole(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            h.hostFactory.host(Pattern("Alice"))
            val joiner = h.joinerFactory.join(InMemoryTag("Bob"))
            assertEquals(SessionRole.Joiner, joiner.role.value, "join() must produce SessionRole.Joiner")
            joiner.leave()
        }

    // ── (2a) Room.roomId: the session identity both roles agree on (#1594) ───

    /**
     * A host knows which room it is **at construction** — [Room.roomId] is non-null the moment
     * [RoomFactory.host] returns, with no round trip and nothing to wait for.
     *
     * And it names the *room*, not the *host*: a second room from the same factory (hence the same
     * `selfId`) gets a different id. That is the whole of #1594 — a fabric whose `selfId` happens to
     * be fresh per weave would make the first assertion pass and this one vacuous, so both are here.
     */
    @Test
    public fun hostRoomKnowsItsRoomIdAtConstruction(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val first = h.hostFactory.host(Pattern("Alice"))
            val firstId = first.roomId.value
            assertTrue(firstId != null, "a host room must know its RoomId at construction")
            first.leave()

            val second = h.hostFactory.host(Pattern("Alice"))
            assertTrue(
                second.roomId.value != firstId,
                "a RoomId must name one room, not the host: two rooms shared $firstId",
            )
            second.leave()
        }

    /**
     * A joiner has **no** identity until it is admitted, then reads the host's — one transition, to
     * the value the host already held.
     *
     * Sampled before the wait, not after, so this cannot be satisfied by a fabric that admits so
     * fast the null phase is never observable; and the post-condition is keyed to the roster rather
     * than to a `roomId` collect, so a fabric that never delivers the id **fails** rather than
     * hanging on a flow that never emits.
     */
    @Test
    public fun joinerLearnsHostRoomIdOnAdmission(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val host = h.hostFactory.host(Pattern("Alice"))
            val joiner = h.joinerFactory.join(InMemoryTag("Alice"))
            val beforeAdmission = joiner.roomId.value

            joiner.awaitRoster("roster.isNotEmpty() — the joiner is admitted and sees the host") {
                it.isNotEmpty()
            }

            assertAll(
                { assertEquals(null, beforeAdmission, "a joiner must not claim a RoomId before admission") },
                {
                    assertEquals(
                        host.roomId.value,
                        joiner.roomId.value,
                        "an admitted joiner must read the host's RoomId",
                    )
                },
            )

            joiner.leave()
            host.leave()
        }

    // ── (2b) a member's roster identity is its own member name, never the ──
    // ── discovered session name (#1177) ──────────────────────────────────────

    @Test
    public fun membersAppearUnderTheirOwnMemberName(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val host = h.hostFactory.host(Pattern("Alice's game"), memberName = "Alice")
            val joiner = h.joinerFactory.join(InMemoryTag("Alice's game"), memberName = "Bob")

            val hostRoster = host.awaitRoster("roster.size == 1 — Bob is admitted") { it.size == 1 }
            val joinerRoster = joiner.awaitRoster("roster.isNotEmpty() — Alice is visible") { it.isNotEmpty() }
            // List-shaped, not `roster.single().identity`: `single()` throws a non-AssertionError on
            // a roster that is not exactly one member. Since #2283 the sibling diagnoses ride along
            // on that throw rather than being discarded, but "Collection has more than one element"
            // is still what the implementor reads first — this names the wrongly-admitted member.
            assertAll(
                // The joiner shows under its OWN name, not the discovered session name.
                { assertEquals(listOf("Bob"), hostRoster.map { it.identity.displayName }) },
                { assertEquals(listOf("Alice"), joinerRoster.map { it.identity.displayName }) },
            )

            joiner.leave()
            host.leave()
        }

    // ── (3) roster is empty before any admit handshake ───────────────────────

    @Test
    public fun rosterIsEmptyBeforeAnyHandshake(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            assertEquals(
                emptySet<Member>(),
                hostRoom.roster.value,
                "roster must be empty before any peer completes the admit handshake",
            )
            hostRoom.leave()
        }

    // ── (4) broadcast → RoomFrame tagged with admitted-member sender ─────────

    @Test
    public fun broadcastDeliversRoomFrameTaggedWithSender(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val payload = byteArrayOf(1, 2, 3)
            val frameDeferred = async { joinerRoom.awaitFrame("the host's broadcast") }
            hostRoom.broadcast(payload)

            val frame = frameDeferred.await()
            assertEquals(hostRoom.selfId, frame.sender, "frame sender must be the host's selfId")
            assertTrue(payload.contentEquals(frame.payload), "frame payload must match")

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (4a) incomingFrom holds an admitted member's frames from admission (#2802) ──

    /**
     * Frames an admitted member sends before anything collects [Room.incomingFrom] still reach the
     * collector that starts afterwards, in arrival order.
     *
     * The ordering is **forced**, not raced. A probe on [Room.incoming] counts both frames being
     * routed on the host and is cancelled before [Room.incomingFrom] is collected, so by collection
     * time the frames have already passed through `incoming`. That is the `host { onRoom }` shape: a
     * room admits and routes before its consumer runs. The probe count is asserted, because a probe
     * that saw nothing would make the held-frames assertion pass for a room that simply had not
     * routed yet.
     */
    @Test
    public fun incomingFromHoldsFramesAMemberSentBeforeCollection(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            var routed = 0
            val probe = launch(start = CoroutineStart.UNDISPATCHED) {
                hostRoom.incoming.collect { if (it.sender == joinerId) routed++ }
            }
            joinerRoom.broadcast(applicationPayload("first"))
            joinerRoom.broadcast(applicationPayload("second"))
            advanceTimeBy(100L)
            probe.cancel()
            // Asserted before the held-frames wait, which throws on its own budget: a rig that never
            // fired and a frame that was never held must not share one red.
            assertEquals(2, routed, "rig: both frames must have been routed on the host before incomingFrom was collected")

            val held = hostRoom.awaitHeld(joinerId, count = 2, expected = "the joiner's two frames")
            assertAll(
                { assertEquals(listOf("first", "second"), held.map { it.payload.decodeToString() }, "held frames, in arrival order") },
                { assertTrue(held.all { it.sender == joinerId }, "every held frame is from the member whose inbox was read") },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (4b) each member's inbox holds that member's frames and no other's (#2802) ──

    /**
     * Two members, and each [Room.incomingFrom] holds only its own member's frames. The second member
     * speaks first, so a room that put every frame in every inbox would hand the first member's reader
     * the second member's frame at the head of its stream.
     */
    @Test
    public fun incomingFromHoldsEachMembersFramesApart(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val first = h.joinerFactory.join(InMemoryTag("Bob"), memberName = "Bob")
            val firstId = hostRoom.awaitRoster("roster.size == 1 — the first joiner is admitted") { it.size == 1 }.single().id
            val second = h.joinerFactory.join(InMemoryTag("Bob"), memberName = "Carol")
            val secondId = hostRoom.awaitRoster("roster.size == 2 — both joiners are admitted") { it.size == 2 }
                .single { it.id != firstId }.id
            first.awaitRoster("roster.isNotEmpty() — the first joiner sees the host") { it.isNotEmpty() }
            second.awaitRoster("roster.isNotEmpty() — the second joiner sees the host") { it.isNotEmpty() }

            second.broadcast(applicationPayload("second-0"))
            advanceTimeBy(100L)
            first.broadcast(applicationPayload("first-0"))
            first.broadcast(applicationPayload("first-1"))
            advanceTimeBy(100L)
            second.broadcast(applicationPayload("second-1"))
            advanceTimeBy(100L)

            // Both readers take two, so a room that put every frame in every inbox is caught from either
            // side: each member's own frames are split by the other's.
            val firstHeld = hostRoom.awaitHeld(firstId, count = 2, expected = "the first joiner's two frames")
            val secondHeld = hostRoom.awaitHeld(secondId, count = 2, expected = "the second joiner's two frames")
            assertAll(
                { assertEquals(listOf("first-0", "first-1"), firstHeld.map { it.payload.decodeToString() }, "the first member's inbox holds its own frames only") },
                { assertEquals(listOf("second-0", "second-1"), secondHeld.map { it.payload.decodeToString() }, "the second member's inbox holds its own frames only") },
            )

            first.leave()
            second.leave()
            hostRoom.leave()
        }

    // ── (4c) an unclaimed inbox holds exactly memberInboxCapacity frames (#2802) ──

    @Test
    public fun incomingFromHoldsAFullInboxUnclaimed(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            joinerRoom.broadcastNumbered("f", memberInboxCapacity)
            advanceTimeBy(200L)

            val held = hostRoom.awaitHeld(joinerId, count = memberInboxCapacity, expected = "exactly a full inbox of frames")
            assertEquals((0 until memberInboxCapacity).map { "f$it" }, held.map { it.payload.decodeToString() }, "a full inbox is held whole, in order")

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (4d) a claim after an unclaimed overflow fails, naming the loss (#2802) ──

    @Test
    public fun incomingFromClaimedAfterAnUnclaimedOverflowFailsNamingTheLoss(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            joinerRoom.broadcastNumbered("f", memberInboxCapacity + 1)
            advanceTimeBy(200L)
            val late = hostRoom.incomingFrom(joinerId)
            // Routed after the claim, and counted: `dropped` is read when the reader reaches the end, not
            // when the loss began. Its first byte must be unreserved or a live room refuses it at send —
            // see [applicationPayload], which is why this assertion used to pin the wrong number.
            joinerRoom.broadcast(applicationPayload("late-after-claim"))
            advanceTimeBy(100L)

            val got = mutableListOf<RoomFrame>()
            val failure = hostRoom.awaitEnd(late, got, "a claim made after the unclaimed inbox overflowed")
            assertAll(
                { assertIs<FramesLost>(failure, "a late claim must fail, not hand back a healthy-looking stream") },
                { assertEquals(false, (failure as? FramesLost)?.claimed, "the loss happened before anything claimed the inbox") },
                {
                    assertEquals(
                        memberInboxCapacity + 2L,
                        (failure as? FramesLost)?.dropped,
                        "dropped counts every frame lost by the time the reader reaches the end — the full " +
                            "inbox released before the claim, and the one routed after it",
                    )
                },
                { assertEquals(emptyList<String>(), got.map { it.payload.decodeToString() }, "a failed claim delivers nothing, not even a frame sent after it") },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (4e) a claimed inbox that falls behind delivers what it held, then fails (#2802) ──

    @Test
    public fun incomingFromClaimedOverflowDeliversTheHeldFramesThenFails(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val claimed = hostRoom.incomingFrom(joinerId)
            joinerRoom.broadcastNumbered("f", memberInboxCapacity + 1)
            advanceTimeBy(200L)

            val got = mutableListOf<RoomFrame>()
            val failure = hostRoom.awaitEnd(claimed, got, "a claimed inbox that fell a full inbox behind")
            assertAll(
                { assertIs<FramesLost>(failure, "a claimed overflow must fail the flow, not drop frames silently") },
                { assertEquals(true, (failure as? FramesLost)?.claimed, "the loss happened to a claimed inbox") },
                { assertEquals((0 until memberInboxCapacity).map { "f$it" }, got.map { it.payload.decodeToString() }, "every held frame is delivered before the failure") },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (4f) the flow completes when the member's admission ends (#2802) ──────

    @Test
    public fun incomingFromCompletesWhenTheMemberLeaves(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val got = mutableListOf<RoomFrame>()
            var rosterAtCompletion: Set<PeerId>? = null
            // Unconfined, so the reader resumes in place the instant the room ends the inbox and reads the
            // roster exactly as the room had published it then — not after the room's own next step, which
            // on the ordinary test scheduler would hide an inbox completed before the member left the roster.
            val reader = async(UnconfinedTestDispatcher(testScheduler)) {
                hostRoom.awaitEnd(hostRoom.incomingFrom(joinerId), got, "the reader of a member that left")
                    .also { rosterAtCompletion = hostRoom.roster.value.mapTo(mutableSetOf()) { it.id } }
            }
            joinerRoom.broadcast(applicationPayload("before-leave"))
            advanceTimeBy(100L)
            joinerRoom.leave(LeaveReason.Normal)
            hostRoom.awaitRoster("roster.isEmpty() — the joiner's leave is observed") { it.isEmpty() }

            val failure = reader.await()
            assertAll(
                { assertEquals(null, failure, "an ended admission completes the flow; it must neither fail nor hang") },
                { assertEquals(listOf("before-leave"), got.map { it.payload.decodeToString() }, "frames held before the leave are delivered first") },
                {
                    assertEquals(
                        false,
                        rosterAtCompletion?.contains(joinerId),
                        "when the member's flow completes, the published roster no longer holds that member",
                    )
                },
            )

            hostRoom.leave()
        }

    // ── (4g) incomingFrom for a peer with no admission completes (#2802) ──────

    /**
     * A peer with no current admission reads as an empty, completed stream — never a failure. From a
     * consumer's side "there is no admission" and "the admission just ended" are the same fact, and a
     * consumer claiming from a roster snapshot legitimately races both (a snapshot read just before an
     * eviction, or just before its own [Room.leave]).
     */
    @Test
    public fun incomingFromForAPeerWithNoAdmissionCompletes(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))

            val got = mutableListOf<RoomFrame>()
            val failure = hostRoom.awaitEnd(hostRoom.incomingFrom(PeerId("never-admitted")), got, "a peer that was never admitted")
            assertAll(
                { assertEquals(null, failure, "no admission is an ended stream, not a failure") },
                { assertEquals(emptyList<String>(), got.map { it.payload.decodeToString() }, "and it delivers nothing") },
            )

            hostRoom.leave()
        }

    // ── (4h) single collection: a concurrent collector throws, a sequential one resumes (#2802) ──

    @Test
    public fun incomingFromRefusesAConcurrentCollectorAndResumesASequentialOne(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            joinerRoom.broadcastNumbered("f", 3)
            advanceTimeBy(100L)
            val frames = hostRoom.incomingFrom(joinerId)
            val firstRead = frames.take(1).toList().map { it.payload.decodeToString() }
            val secondRead = frames.take(1).toList().map { it.payload.decodeToString() }

            val holder = launch(start = CoroutineStart.UNDISPATCHED) { frames.take(1).toList() }
            // holder took f2 and completed; park a collector that has nothing left to read.
            val parked = launch(start = CoroutineStart.UNDISPATCHED) { frames.collect { } }
            var concurrent: IllegalStateException? = null
            withTimeoutOrNull(awaitBudget ?: Duration.INFINITE) {
                try {
                    frames.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    concurrent = e
                }
            }
            parked.cancel()
            holder.cancel()

            assertAll(
                { assertEquals(listOf("f0"), firstRead, "the first collection reads the first frame") },
                { assertEquals(listOf("f1"), secondRead, "a sequential re-collection resumes where the last stopped") },
                { assertTrue(concurrent != null, "a second collection while one is active must throw IllegalStateException") },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (4i) a collection cancelled as its frame arrives leaves that frame for the next (#2802) ──

    /**
     * The first collection is cancelled at the instant a frame reaches the member's inbox, and the next
     * collection still receives that frame.
     *
     * The rig: a probe on [Room.incoming] cancels the reader when it sees the frame. The reference room
     * routes a frame onto `incoming` before offering it to the member's inbox, and the test scheduler runs
     * resumed coroutines in order, so the probe's cancel lands after the inbox was offered the frame and
     * before the reader runs — the window in which a `Channel`-backed inbox lost it. The rig's own
     * preconditions are asserted, so a harness that orders delivery differently fails here loudly rather
     * than passing without having reached the window.
     */
    @Test
    public fun incomingFromCollectionCancelledAsAFrameArrivesLeavesItForTheNext(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val delivered = mutableListOf<RoomFrame>()
            val reader = launch(start = CoroutineStart.UNDISPATCHED) { hostRoom.incomingFrom(joinerId).toList(delivered) }
            var probeCancels = 0
            val probe = launch(start = CoroutineStart.UNDISPATCHED) {
                hostRoom.incoming.collect {
                    if (it.sender == joinerId) {
                        probeCancels++
                        reader.cancel()
                    }
                }
            }
            joinerRoom.broadcast(applicationPayload("f-raced"))
            advanceTimeBy(100L)
            probe.cancel()

            assertAll(
                { assertEquals(1, probeCancels, "rig: the probe must have cancelled the reader as the frame was routed") },
                { assertTrue(reader.isCancelled, "rig: the first collection was cancelled") },
                { assertEquals(emptyList<String>(), delivered.map { it.payload.decodeToString() }, "rig: the cancelled collection delivered nothing") },
            )
            val held = hostRoom.awaitHeld(joinerId, count = 1, expected = "the frame the cancelled collection never delivered")
            assertEquals(listOf("f-raced"), held.map { it.payload.decodeToString() }, "the next collection receives the frame the cancelled one never delivered")

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (4j) the room going terminal completes every member's flow (#2802) ──────

    @Test
    public fun incomingFromCompletesWhenTheRoomItselfLeaves(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val got = mutableListOf<RoomFrame>()
            val reader = async { hostRoom.awaitEnd(hostRoom.incomingFrom(joinerId), got, "the reader of a member when the room itself left") }
            joinerRoom.broadcast(applicationPayload("before-room-leave"))
            advanceTimeBy(100L)
            hostRoom.leave()

            val failure = reader.await()
            assertAll(
                { assertEquals(null, failure, "a terminal room ends every admission: the flow completes, and neither fails nor hangs") },
                { assertEquals(listOf("before-room-leave"), got.map { it.payload.decodeToString() }, "frames held before the room left are delivered first") },
            )

            joinerRoom.leave()
        }

    // ── (4k) a collection cancelled between taking a frame and handing it over loses nothing (#2802) ──

    /**
     * Runs dispatched tasks only when told to, one at a time — so a property can place a cancellation
     * between two steps of a reader, a window the test scheduler cannot open because it runs everything that
     * is ready before returning.
     */
    private class StepDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        val pending: Int get() = tasks.size

        fun step(): Boolean {
            val task = tasks.removeFirstOrNull() ?: return false
            task.run()
            return true
        }

        fun drain() {
            while (step()) Unit
        }
    }

    /**
     * (4i) cancels the reader before it resumes, so it cannot see a room that suspends *between* taking a
     * frame and handing it over — the second window in which a frame can leave an inbox unseen. Here the
     * reader runs on a [StepDispatcher]. The frame is routed on the test scheduler, which queues exactly one
     * task for the parked reader; the reader runs exactly that task and is cancelled wherever it left off.
     * Across that collection and the next, both frames must arrive exactly once, in order: delivered by the
     * cancelled collection, or left for the next one — never lost.
     */
    @Test
    public fun incomingFromCollectionCancelledBetweenTakeAndHandOverLosesNothing(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val steps = StepDispatcher()
            val delivered = mutableListOf<String>()
            val reader = CoroutineScope(steps + Job()).launch {
                hostRoom.incomingFrom(joinerId).collect { delivered += it.payload.decodeToString() }
            }
            steps.drain() // parked, waiting for a frame

            joinerRoom.broadcast(applicationPayload("f-0"))
            advanceTimeBy(100L) // routed on the test scheduler; the parked reader's resumption queues on `steps`
            val wakeTasks = steps.pending
            steps.step() // the reader runs exactly the step that takes the frame...
            reader.cancel() // ...and is cancelled wherever that step left it
            steps.drain()
            joinerRoom.broadcast(applicationPayload("f-1"))
            advanceTimeBy(100L)

            assertAll(
                { assertEquals(1, wakeTasks, "rig: routing the frame queued exactly one task for the parked reader") },
                { assertTrue(reader.isCompleted, "rig: the cancelled collection has ended") },
            )
            val rest = hostRoom.awaitHeld(joinerId, count = 2 - delivered.size, expected = "the frames the cancelled collection did not deliver")
            assertEquals(
                listOf("f-0", "f-1"),
                delivered + rest.map { it.payload.decodeToString() },
                "each frame arrives exactly once, in order — delivered by the cancelled collection or left for the next",
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (4l) a terminal room has no members, and says so before its readers complete (#2822) ──

    /**
     * "This member's reader completed while it is still listed" means a newer admission, and nothing
     * else: eviction updates the roster before it closes the inbox. A room's own [Room.leave] has to
     * keep that sentence true, or a consumer holding one reader per member reads the end of the room
     * as a re-admission, claims again, completes at once, and spins.
     */
    @Test
    public fun leaveEmptiesAHostsRosterBeforeItCompletesAMembersReader(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val joinerId = hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }.single().id
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            assertLeaveEndsTheRosterFirst(hostRoom, member = joinerId)

            joinerRoom.leave()
        }

    /** The same contract from a joiner's seat, whose one member is its host. */
    @Test
    public fun leaveEmptiesAJoinersRosterBeforeItCompletesTheHostsReader(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }
            val hostId = joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }.single().id

            assertLeaveEndsTheRosterFirst(joinerRoom, member = hostId)

            hostRoom.leave()
        }

    /** Losing the host ends a joiner's room the same way, so the same inference has to hold. */
    @Test
    public fun hostLostEmptiesTheRosterBeforeItCompletesTheHostsReader(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faults.injectorOrDeclaredGap("HostLost empties the roster before completing the host's reader")
                ?: return@runTest

            h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            val hostId = joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }.single().id

            var rosterAtCompletion: Set<PeerId>? = null
            val reader = async(UnconfinedTestDispatcher(testScheduler)) {
                joinerRoom.awaitEnd(joinerRoom.incomingFrom(hostId), mutableListOf(), "the host's reader when the host was lost")
                    .also { rosterAtCompletion = joinerRoom.roster.value.mapTo(mutableSetOf()) { it.id } }
            }
            val hostLost = async {
                joinerRoom.awaitEvent("MembershipEvent.HostLost") { it as? MembershipEvent.HostLost }
            }
            faultyLoom.setFaultProfileOnAll(FaultProfile.DropAll(Direction.Both))
            repeat(9) { h.advanceClock(100L); advanceTimeBy(100L) }

            val event = hostLost.await()
            val failure = reader.await()
            assertAll(
                { assertIs<MembershipEvent.HostLost>(event, "rig: the host really was lost") },
                { assertEquals(null, failure, "rig: the reader completed, which is what makes the next line a claim about completion") },
                { assertEquals(emptySet(), rosterAtCompletion, "when the host's flow completes, the published roster is already empty") },
                { assertEquals(emptySet(), joinerRoom.roster.value, "a room that lost its host has no members") },
            )
        }

    /**
     * **A room that has left reads [SeamState.Torn] on its channel views, on both roles (#3129).**
     *
     * A channel view's `state` forwards the room's own seam state ([Room.channel]), so
     * `room.channel(id).state` is the public signal for "this room has ended", whether it left or
     * its transport tore. It is what a handler parked inside a host's `onRoom` can wait on. A room
     * whose views still read `Woven` after it left gives that handler nothing to wake on, and every
     * consumer of such a room inherits a handler that never ends.
     *
     * Two views per room: one taken **before** the leave, which is the view a consumer already
     * holds, and one taken **after**, so a room cannot pass by tearing only the views it had already
     * handed out. The joiner leaves first so that its `Torn` is caused by its own leave: the host
     * leaving first would end the joiner's room by host loss, a different path. On a per-connection
     * fabric the joiner's leave also tears the host's seam, so the host room may already have left
     * by itself and the host's `leave()` here is the idempotent second call. The obligation holds
     * either way: after `leave()` returns, the views read `Torn`.
     *
     * The precondition is part of the obligation, not decoration: a room whose views read `Torn`
     * from the start would satisfy every later line.
     */
    @Test
    public fun channelStateTornAfterLeave(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val hostView = hostRoom.channel(TERMINAL_STATE_CHANNEL)
            val joinerView = joinerRoom.channel(TERMINAL_STATE_CHANNEL)
            val hostBefore = hostView.state.value
            val joinerBefore = joinerView.state.value

            joinerRoom.leave()
            joinerRoom.awaitTorn(joinerView, "the joiner's view taken before it left")
            joinerRoom.awaitTorn(
                joinerRoom.channel(TERMINAL_STATE_CHANNEL_AFTER),
                "a view the joiner minted after it left",
            )

            hostRoom.leave()
            hostRoom.awaitTorn(hostView, "the host's view taken before it left")
            hostRoom.awaitTorn(
                hostRoom.channel(TERMINAL_STATE_CHANNEL_AFTER),
                "a view the host minted after it left",
            )

            assertAll(
                { assertFalse(hostBefore is SeamState.Torn, "rig: the host's view is live before anything leaves, got $hostBefore") },
                { assertFalse(joinerBefore is SeamState.Torn, "rig: the joiner's view is live before anything leaves, got $joinerBefore") },
            )
        }

    private suspend fun TestScope.assertLeaveEndsTheRosterFirst(room: Room, member: PeerId) {
        val channelView = room.channel("terminal-room")
        val leftEvents = mutableListOf<MembershipEvent.Left>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            room.events.collect { if (it is MembershipEvent.Left) leftEvents += it }
        }
        var rosterAtCompletion: Set<PeerId>? = null
        // Unconfined, so the reader resumes in place the instant the room ends the inbox and reads the
        // roster exactly as the room had published it then. See (4f) for what the ordinary scheduler hides.
        val reader = async(UnconfinedTestDispatcher(testScheduler)) {
            room.awaitEnd(room.incomingFrom(member), mutableListOf(), "the reader of a member when the room itself left")
                .also { rosterAtCompletion = room.roster.value.mapTo(mutableSetOf()) { it.id } }
        }
        assertEquals(setOf(member), room.roster.value.mapTo(mutableSetOf()) { it.id }, "rig: the member is listed before the leave")

        room.leave()

        val failure = reader.await()
        assertAll(
            { assertEquals(null, failure, "rig: the reader completed, which is what makes the next line a claim about completion") },
            { assertEquals(emptySet(), rosterAtCompletion, "when a member's flow completes because the room left, the published roster is already empty") },
            { assertEquals(emptySet(), room.roster.value, "a terminal room has no members") },
            { assertEquals(setOf(room.selfId), channelView.peers.value, "a channel of a terminal room can address nobody else") },
            { assertEquals(emptyList(), leftEvents, "nobody left: this room stopped, so it must not report a member's departure") },
        )
    }

    // ── (5) leave(Normal) → Left event; roster shrinks ──────────────────────

    @Test
    public fun leaveNormalFiresLeftEventAndShrinksRoster(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }

            val leftDeferred = async {
                hostRoom.awaitEvent("MembershipEvent.Left") { it as? MembershipEvent.Left }
            }

            joinerRoom.leave(LeaveReason.Normal)

            val event = leftDeferred.await()
            assertIs<MembershipEvent.Left>(event)
            assertEquals(0, hostRoom.roster.value.size, "roster must shrink after Leave")

            hostRoom.leave()
        }

    // ── (6) round-trip: join → leave → rejoin; fresh session ────────────────

    @Test
    public fun rejoinAfterLeaveWorks(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))

            val firstJoiner = h.joinerFactory.join(InMemoryTag("Bob"), memberName = "Bob")
            hostRoom.awaitRoster("roster.size == 1 — Bob's first join is admitted") { it.size == 1 }
            firstJoiner.leave()
            hostRoom.awaitRoster("roster.isEmpty() — Bob's leave is observed") { it.isEmpty() }

            val secondJoiner = h.joinerFactory.join(InMemoryTag("Bob"), memberName = "Bob")
            val rosterAfterRejoin =
                hostRoom.awaitRoster("roster.size == 1 — Bob's rejoin is admitted") { it.size == 1 }

            assertEquals(1, rosterAfterRejoin.size, "roster must contain the rejoiner")
            assertEquals("Bob", rosterAfterRejoin.first().identity.displayName, "rejoiner display name must match")

            secondJoiner.leave()
            hostRoom.leave()
        }

    // ── (7) Partitioned / Recovered fire on liveness transitions ────────────

    /**
     * Faults only the host's [FaultySeam] ([FaultyLoom.links][0]) with [FaultProfile.DropAll]
     * in both directions. The joiner's seam ([FaultyLoom.links][1]) stays Healthy, mirroring
     * [us.tractat.kuilt.session.PartitionRoleTest]'s proven partition/recovery pattern.
     *
     * With only the host's seam faulted, neither side can exchange ping/pong:
     * - Host can't send pings (outbound dropped).
     * - Joiner's pings to host are dropped at host's inbound.
     * Both detectors fire [MembershipEvent.Partitioned] within the timeout.
     *
     * After healing, both sides exchange ping/pong again. Both detectors fire
     * [MembershipEvent.Recovered] before the reconnect window expires.
     *
     * **Tick pattern** (mirrored from [us.tractat.kuilt.session.PartitionRoleTest]):
     * 1. 4 ticks → [MembershipEvent.Partitioned] fires.
     * 2. Heal host seam.
     * 3. 1 tick (allow ping/pong exchange to update [lastSeenEpochMs]).
     * 4. Start recovered collector to avoid missing the event on the hot flow.
     * 5. 5 ticks → [MembershipEvent.Recovered] fires.
     */
    @Test
    public fun partitionedAndRecoveredFireOnLivenessTransitions(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faults.injectorOrDeclaredGap("Partitioned/Recovered fire on liveness transitions")
                ?: return@runTest

            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }

            // After host() and join(), links[0] = host's seam, links[1] = joiner's seam.
            val hostSeam = faultyLoom.links[0]

            val partitionedDeferred = async {
                hostRoom.awaitEvent("MembershipEvent.Partitioned") { it as? MembershipEvent.Partitioned }
            }
            // Fault only the host's seam (both directions) — joiner seam stays Healthy.
            hostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))
            // Advance past heartbeat timeout (200 ms) — 4 steps gives margin.
            repeat(4) { h.advanceClock(100L); advanceTimeBy(100L) }

            val partitioned = partitionedDeferred.await()
            assertIs<MembershipEvent.Partitioned>(partitioned)
            assertEquals(ReconnectReason.LinkTimeout, partitioned.reason)
            assertIs<Liveness.Partitioned>(hostRoom.roster.value.first().liveness)

            // Heal the host seam then advance one tick for ping/pong exchange.
            hostSeam.heal()
            h.advanceClock(100L); advanceTimeBy(100L)

            // Start the recovered collector AFTER one pong exchange but BEFORE the next
            // poll cycle where PeerRecovered fires — mirrors PartitionRoleTest exactly.
            val recoveredDeferred = async {
                hostRoom.awaitEvent("MembershipEvent.Recovered") { it as? MembershipEvent.Recovered }
            }
            repeat(5) { h.advanceClock(100L); advanceTimeBy(100L) }

            val recovered = recoveredDeferred.await()
            assertIs<MembershipEvent.Recovered>(recovered)
            assertEquals(Liveness.Connected, hostRoom.roster.value.first().liveness)

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (8) retired: Resumed fires on Room.resume(token) within the window ───
    //
    // `resumeWithinWindowFiresResumed` partitioned the link silently, healed it, and called
    // `Room.resume`. #3215 retired the call, and a silent partition that heals is a `Recovered`, not a
    // `Resumed`: only a return over a torn link says Hello again. The property moves to the identity
    // plan's Task 1.9 with the return rows above.

    // ── (8a) retired: a resume token minted for another room ─────────────────
    //
    // `aTokenMintedForAnotherRoomIsRefused` presented a token naming another room through
    // `Room.resume` and required a terminal refusal. Since #3159 Task 1.7 a return says Hello again and
    // presents no token, so through `Room.resume` there is nothing for a room to refuse; the property a
    // second implementation owes in its place is that a proof naming another room admits nobody, which
    // `:kuilt-session`'s `KeyAdmissionTest.proofForAnotherRoomIsRejected` pins.
    // ── (9) HostLost is terminal — broadcast after HostLost is a no-op ──────

    @Test
    public fun hostLostIsTerminalBroadcastIsNoOp(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faults.injectorOrDeclaredGap("HostLost is terminal and broadcast after it is a no-op")
                ?: return@runTest

            h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val hostLostDeferred = async {
                joinerRoom.awaitEvent("MembershipEvent.HostLost") { it as? MembershipEvent.HostLost }
            }
            faultyLoom.setFaultProfileOnAll(FaultProfile.DropAll(Direction.Both))
            repeat(9) { h.advanceClock(100L); advanceTimeBy(100L) }

            assertIs<MembershipEvent.HostLost>(hostLostDeferred.await())

            // Nothing is asserted about this frame — the property is that the call itself is a no-op rather
            // than a throw or a wedge — but it goes through [applicationPayload] like every other payload
            // in this suite, so the fixture also works on a live room, which refuses reserved payloads at send.
            joinerRoom.broadcast(applicationPayload("post-host-lost"))
        }

    // ── (10) Left member no longer receives broadcast frames ─────────────────

    @Test
    public fun memberThatLeftNoLongerReceivesFrames(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            joinerRoom.leave(LeaveReason.Normal)
            hostRoom.awaitRoster("roster.isEmpty() — the joiner's leave is observed") { it.isEmpty() }

            var received = false
            val collectJob = launch { joinerRoom.incoming.collect { received = true } }

            // Through [applicationPayload] because this assertion is an ABSENCE: the previous payload here
            // led with the admit byte, so a room that had gone on delivering to a left member would have
            // classified it as an admit frame and `received` would have stayed false anyway. The property
            // could not fail. A payload the room really routes as application data is what gives the
            // assertion a way to be wrong.
            hostRoom.broadcast(applicationPayload("post-leave"))
            advanceTimeBy(100L)
            collectJob.cancel()

            assertFalse(received, "a member that has Left must not receive broadcast frames")

            hostRoom.leave()
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  (11) Room.leave's TWO documented obligations — #2501
    //
    //  `Room.leave` is documented "Idempotent", and a leave failure "must not be reported as a
    //  cancellation" (#1826, the obligation `Seam.close` states in full and whose exhaustive list of
    //  carrying surfaces names `Room.leave`). Neither had a property here. They were UNREACHABLE
    //  rather than merely unwritten: this suite's one subclass is the reference, whose `InMemorySeam`
    //  close contains no suspension point and cannot fail, and `FaultySeam.close` was an
    //  unconditional passthrough — so the one component in the tree whose job is to make a transport
    //  misbehave could not make a teardown slow, suspend, or fail. See [TeardownFault].
    //
    //  The first two are UNGATED CORE, mirroring `SeamConformanceSuite.closeIsIdempotent` and
    //  `closeDoesNotReportFailureAsCancellation`: no fabric may excuse either. Minting a cancellation
    //  is a *choice* (`withTimeout` instead of `withTimeoutOrNull` plus an explicit throw), never a
    //  property of a transport.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **UNGATED CORE.** `leave()` twice must not throw — on a host and on a joiner, whose paths
     * differ: a joiner's leave announces a `Goodbye` on the still-live seam first.
     *
     * No fault injection needed, and none wanted: this is the happy-path half, and it is the half a
     * room gets wrong by forgetting a closed-flag entirely.
     * [leaveIsIdempotentEvenWhenTeardownFails] is the other half — idempotency that survives a first
     * call which *failed* — and only that one needs a fault.
     */
    @Test
    public fun leaveIsIdempotent(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Alice"))

            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            joinerRoom.leave()
            joinerRoom.leave() // must not throw

            hostRoom.leave()
            hostRoom.leave() // must not throw
        }

    /**
     * **UNGATED CORE.** No `leave()` may report its failure as a cancellation (#1826).
     *
     * A caller cannot tell a callee-minted `CancellationException` from its own by type, so one that
     * escapes here **cancels** the caller rather than failing it: no handler runs, no stack trace,
     * and every obligation behind the call is skipped. A host calls `leave` from a teardown path
     * that is itself usually already cancelled, under a best-effort guard, so the cost is the rest
     * of that host's cleanup rather than one leave — which is why both `RoomHost` implementations
     * hand-write a `catch (Throwable)` around it.
     *
     * **Honest limit, carried over from the `Seam` analogue.** This proves the obligation on the
     * paths a 2-peer harness can reach with no fault at all — a live leave, the idempotent second
     * leave, and a leave whose remote has already gone (the one most likely to be bounded by a
     * timeout). It cannot reach a leave racing a genuinely wedged transport;
     * [leaveDoesNotMintACancellationWhenTeardownIsSlow] is the arm that reaches the *suspending*
     * teardown, and it needs the injector.
     */
    @Test
    public fun leaveDoesNotReportFailureAsCancellation(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Alice"))

            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            assertNoMintedCancellation("leave() on a live room") { hostRoom.leave() }
            assertNoMintedCancellation("a second, idempotent leave()") { hostRoom.leave() }
            // The joiner's remote is already gone: bounding teardown on a peer that will never answer
            // is exactly where an implementation reaches for `withTimeout`.
            assertNoMintedCancellation("leave() after the host has gone") { joinerRoom.leave() }
        }

    /**
     * **The precondition** of [leaveIsIdempotentEvenWhenTeardownFails] and
     * [leaveDoesNotMintACancellationWhenTeardownIsSlow], asserted rather than assumed.
     *
     * Those two properties are satisfied by a room that never sees a failing teardown at all — a rig
     * whose [TeardownFault] arms were quietly no-ops, or a `FaultyLoom` that is simply not the loom
     * the harness's factories weave through. Either makes both pass **for the wrong reason**, which
     * is the vacuity this whole axis exists to remove rather than relocate. So this measures the
     * fixture *directly*: arm a link, close it, and check that the close threw the very throwable
     * that was injected and that the seam counted the arm firing.
     *
     * Same role as `MeshDisplacementDrainConformanceSuite.theFixtureReallyDiscardsOnClose`, and it
     * fails by name for the same reason: a fixture that does not do what the properties behind it
     * assume must red *here*, loudly, not evaporate into two quiet greens over there.
     *
     * **Both arms, not just the failing one, and the second one is here because it was missing.**
     * The first draft of this test measured only [TeardownFault.Fails] — and a mutation that made
     * [TeardownFault.Slow] stop delaying was **green everywhere in the tree**, because
     * `SeamRoom.leave` bounds nothing today so a teardown of zero duration mints no cancellation
     * either. The `Slow` arm would have quietly stopped being about a *slow* teardown, and the
     * property built on it would have gone on passing. Virtual time is the direct measurement, so
     * that is what is measured.
     *
     * The identity check (`===`, not a type match) is deliberate — a rig that threw *some*
     * exception, or wrapped the injected one, would still be lying about what a transport's close
     * does, and a type match would wave both through.
     *
     * ## The links measured here carry no [Room], and that is load-bearing
     *
     * An earlier draft armed the fault on the seams *under the harness's rooms*, and its
     * exactly-once assertions passed only because of the reference's shape — the third instance in
     * this change of a fix resting on the thing it was fixing. Two windows were open in it, both
     * closed by `InMemoryLoom` alone:
     *
     *  - **The [TeardownFault.Fails] window.** The delegate's close runs while the arm is still
     *    live. `InMemorySeam.close` publishes `Torn` and then does only non-suspending work, so the
     *    room's torn-watcher cannot interleave. Any fabric whose close genuinely *suspends* after
     *    publishing `Torn` — a WebSocket close handshake, a TCP shutdown — lets that watcher
     *    re-enter `close` with `Fails` still armed: the count becomes 2, and the injected throwable
     *    is thrown a second time on a coroutine where nothing catches it.
     *  - **The [TeardownFault.Slow] window.** [SLOW_TEARDOWN] is several times
     *    [fastHeartbeatConfig]'s timeout and twice its reconnect window. It is inert only because
     *    the default harness keeps its injected clock decoupled from virtual time; a harness wiring
     *    the two together — defensible, arguably more faithful — has a peer reach
     *    [MembershipEvent.HostLost] *inside* the delay and leave, closing again.
     *
     * Either way the red would arrive blaming the **fixture** for a fault of the room, which is the
     * wrong diagnosis handed to the wrong reader. So both arms are measured on links woven straight
     * off the injector with no [Room] attached: nothing can race a close nobody owns, and
     * "exactly once per close" becomes a property of `FaultySeam` rather than an accident of
     * `SeamRoom`'s teardown. The host room exists only so the fabric has something to join.
     */
    @Test
    public fun theTeardownFaultReallyFires(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faults.injectorOrDeclaredGap("the teardown fault injector really fails a close")
                ?: return@runTest

            // One room, purely so the fabric is joinable; the two links measured below are woven
            // straight off the injector and no Room owns either. See the KDoc — this is what makes
            // the exactly-once claims measurements of the fixture rather than of the reference.
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val failingSeam = faultyLoom.join(InMemoryTag("Alice"))
            val slowSeam = faultyLoom.join(InMemoryTag("Alice"))

            val injected = IllegalStateException(INJECTED_TEARDOWN_FAILURE)
            failingSeam.setTeardownFault(TeardownFault.Fails(injected))
            val failure = failureOf { failingSeam.close() }
            val firedByFailure = failingSeam.teardownFaultsFired

            slowSeam.setTeardownFault(TeardownFault.Slow(SLOW_TEARDOWN))
            val before = testScheduler.currentTime
            slowSeam.close()
            val elapsed = testScheduler.currentTime - before
            val firedBySlow = slowSeam.teardownFaultsFired

            assertAll(
                {
                    assertTrue(
                        failure === injected,
                        "harness contract: FaultInjection.Supported's FaultyLoom must produce links whose " +
                            "close FAILS when TeardownFault.Fails is armed — otherwise the property built " +
                            "on it is satisfied by a teardown that never failed. Got: " +
                            (failure ?: "no exception — the close completed"),
                    )
                },
                {
                    assertEquals(
                        1L,
                        firedByFailure,
                        "the armed teardown fault must be counted exactly once per close, so a property " +
                            "downstream can assert its rig fired instead of inferring it",
                    )
                },
                {
                    assertTrue(
                        elapsed >= SLOW_TEARDOWN.inWholeMilliseconds,
                        "harness contract: TeardownFault.Slow must actually SUSPEND the close — the " +
                            "property built on it is about a teardown with a suspension point in it, and a " +
                            "zero-duration one mints no cancellation whether or not the room bounds its " +
                            "close. Expected at least ${SLOW_TEARDOWN.inWholeMilliseconds} ms of virtual " +
                            "time to pass, got $elapsed ms",
                    )
                },
                {
                    assertEquals(1L, firedBySlow, "the suspending arm must be counted too")
                },
            )

            hostRoom.leave()
        }

    /**
     * **Idempotent even when the first call FAILED** — the half [leaveIsIdempotent] cannot reach.
     *
     * A room that marks itself closed only *after* a successful teardown is idempotent on the happy
     * path and not idempotent at all where it matters: the caller retries, the whole teardown runs
     * again, and it throws again. That is the shape of a leave whose closed-flag sits at the bottom
     * of the method instead of the top, and it is invisible to every test whose teardown succeeds.
     *
     * **What is asserted, and what deliberately is not.** The obligation is "a second `leave()` must
     * not throw", so that is the assertion. Whether the *first* one propagates the transport's
     * failure is **not** asserted: a room may legitimately swallow a close failure it can do nothing
     * about, and demanding a throw would fail a conforming room. Nor is "the second call must not
     * re-run the teardown" — a room that retried and succeeded is not obviously non-conforming, and
     * inventing that demand here would be over-specification. The first call's outcome is carried
     * into the failure message instead, where it is diagnosis rather than contract.
     *
     * The rig-fired count is what stops the remaining assertion from being vacuous: without it, a
     * harness whose injected fault never reached the room's seam passes by absence — an unexercised
     * rig is green, and green by absence is the failure mode this suite's own KDoc warns about.
     */
    @Test
    public fun leaveIsIdempotentEvenWhenTeardownFails(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val faultyLoom =
                h.faults.injectorOrDeclaredGap("leave() stays idempotent when the transport's teardown fails")
                    ?: return@runTest

            val room = h.hostFactory.host(Pattern("Alice"))
            val seam = faultyLoom.requireLink()
            seam.setTeardownFault(TeardownFault.Fails(IllegalStateException(INJECTED_TEARDOWN_FAILURE)))

            val first = failureOf { room.leave() }
            val second = failureOf { room.leave() }

            assertAll(
                {
                    assertTrue(
                        seam.teardownFaultsFired > 0L,
                        "precondition: the injected teardown fault never fired, so this room's leave() " +
                            "did not close the FaultyLoom's link and idempotency-under-failure was never " +
                            "exercised. Check that the harness's factories weave through the loom it " +
                            "returns as FaultInjection.Supported. First leave() gave: " +
                            (first ?: "no exception"),
                    )
                },
                {
                    assertTrue(
                        second == null,
                        "Room.leave is documented idempotent, and that must hold when the FIRST call " +
                            "failed: a room whose closed-flag is set only after a successful teardown " +
                            "re-runs the whole thing and throws again, so every caller retrying a failed " +
                            "leave gets the same failure forever. First leave() gave: " +
                            (first ?: "no exception") + "; second threw: $second",
                    )
                },
            )
        }

    /**
     * **No cancellation may be minted when the transport's teardown SUSPENDS** — the #2286 mechanism,
     * reachable only with the injector.
     *
     * [leaveDoesNotReportFailureAsCancellation] proves the obligation on teardowns that return
     * promptly, which is every teardown a 2-peer in-memory harness can produce. The trap it cannot
     * reach is the one that actually happened: a `seam.close` that suspends, bounded by a
     * `withTimeout`, throwing a `TimeoutCancellationException` **to its caller** while that caller's
     * job stays perfectly alive. `SeamRoom.leave` calls `seam.close(...)` unguarded today, so this is
     * a **live guard** against someone later bounding it — not a regression test for a fix.
     *
     * [TeardownFault.Slow] suspends through `delay`, so the whole thing runs in virtual time and
     * costs no wall clock. A harness whose fabric does real I/O gets a virtual delay here too, which
     * is the same trade [FaultProfile.DelayAll] already makes: what is being modelled is a teardown
     * with a suspension point in it, not a slow socket.
     */
    @Test
    public fun leaveDoesNotMintACancellationWhenTeardownIsSlow(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val faultyLoom =
                h.faults.injectorOrDeclaredGap("leave() mints no cancellation when the transport's teardown suspends")
                    ?: return@runTest

            val room = h.hostFactory.host(Pattern("Alice"))
            val seam = faultyLoom.requireLink()
            seam.setTeardownFault(TeardownFault.Slow(SLOW_TEARDOWN))

            val failure = failureOf { room.leave() }

            assertAll(
                {
                    assertTrue(
                        seam.teardownFaultsFired > 0L,
                        "precondition: the suspending teardown never fired, so leave() did not close the " +
                            "FaultyLoom's link and nothing below was exercised. Check that the harness's " +
                            "factories weave through the loom it returns as FaultInjection.Supported. " +
                            "leave() gave: " + (failure ?: "no exception"),
                    )
                },
                {
                    assertFalse(
                        failure is CancellationException,
                        "leave() must not report failure as a cancellation (Room.leave / Seam.close, " +
                            "#1826): a caller cannot distinguish it from its own cancellation, so it " +
                            "CANCELS the caller instead of failing it — every best-effort teardown loop " +
                            "stops there, leaking the rest. A suspending seam.close bounded by " +
                            "withTimeout is exactly how one gets minted; convert it before it escapes " +
                            "(withTimeoutOrNull plus an explicit throw). Got: $failure",
                    )
                },
            )
        }

    // ── Declared gaps ─────────────────────────────────────────────────────────

    /**
     * The [FaultyLoom] to partition with, or `null` after **asserting** that the harness declared
     * why it has none.
     *
     * This is what stops the gated obligations from returning silently. On
     * [FaultInjection.Unsupported] the caller still skips — nobody can partition links they cannot
     * reach — but it skips *through an assertion that can fail*, so the weakest thing this suite
     * says about such a harness is "its gap is on record", never nothing at all.
     *
     * The blank check is the only runtime part left: the sealed arm already makes "declined without
     * declaring" unrepresentable, and `""` is the one remaining way to write a declaration that
     * declares nothing. `RoomConformanceGapDeclarationTest` pins both directions against
     * purpose-built harnesses — the first time in this suite's life that the skip path has been
     * executed at all.
     */
    private fun FaultInjection.injectorOrDeclaredGap(obligation: String): FaultyLoom? =
        when (this) {
            is FaultInjection.Supported -> loom
            is FaultInjection.Unsupported -> {
                assertTrue(
                    trackingUrl.isNotBlank(),
                    "RoomConformanceSuite: \"$obligation\" is unproven on this harness, and an unproven " +
                        "obligation must be tracked — FaultInjection.Unsupported was declared with a blank " +
                        "trackingUrl. Point it at the issue that would have to close for this harness to " +
                        "return FaultInjection.Supported, or return Supported and prove the obligation.",
                )
                null
            }
        }

    /**
     * The return rig for one row, or `null` when the harness declared it cannot build one — after
     * asserting the declaration names an issue, as [injectorOrDeclaredGap] does.
     */
    private fun TestScope.returnHarnessOrDeclaredGap(obligation: String): ReturnHarness? =
        when (val fixture = newReturnHarness(this)) {
            is ReturnFixture.Supported -> fixture.harness
            is ReturnFixture.Unsupported -> {
                assertTrue(
                    fixture.trackingUrl.isNotBlank(),
                    "RoomConformanceSuite: \"$obligation\" is unproven on this harness, and an unproven " +
                        "obligation must be tracked — ReturnFixture.Unsupported was declared with a blank " +
                        "trackingUrl. Point it at the issue that would have to close for this harness to " +
                        "return ReturnFixture.Supported, or return Supported and prove the obligation.",
                )
                null
            }
        }

    /**
     * Fail unless this rig makes a readmission reachable: a device whose reconnect window ends before
     * the host has noticed it and let its seat go gives up first, and a readmission row would then be
     * measuring a `HostLost` the fixture chose. Only the rows that need a readmission call it.
     */
    private fun ReturnHarness.requireReadmissionReachable() {
        val hostGivesUp = hostHeartbeat.timeout + hostHeartbeat.reconnectWindow
        assertTrue(
            joinerHeartbeat.reconnectWindow > hostGivesUp + joinerHeartbeat.interval,
            "RoomConformanceSuite: the return rig's joiner window (${joinerHeartbeat.reconnectWindow}) " +
                "must outlast the host's detection plus hold ($hostGivesUp) by more than one retry " +
                "interval (${joinerHeartbeat.interval}), or a device stops trying before the host " +
                "lets its seat go and no readmission is reachable.",
        )
    }

    /** Whether this is [old] leaving or [new] joining: the two halves of a takeover. */
    private fun MembershipEvent.isAbout(old: PeerId, new: PeerId): Boolean =
        (this is MembershipEvent.Left && peerId == old) || (this is MembershipEvent.Joined && member.id == new)

    /** A membership edge as one line naming its peer, for comparing whole sequences. */
    private fun MembershipEvent.edge(): String = when (this) {
        is MembershipEvent.Joined -> "Joined(${member.id.value})"
        is MembershipEvent.Left -> "Left(${peerId.value}, $reason)"
        is MembershipEvent.Resumed -> "Resumed(${peerId.value})"
        else -> toString()
    }

    /**
     * The [FaultySeam] this loom wove at [index], or fail naming what it actually has.
     *
     * A **loud precondition**, not a skip: an empty (or
     * short) link list at this point means the harness's factories do not weave through the loom it
     * returned as [FaultInjection.Supported], so no teardown fault it arms can reach the room under
     * test. Left as an `IndexOutOfBoundsException` that would read as a suite bug.
     */
    private fun FaultyLoom.requireLink(index: Int = 0): FaultySeam =
        links.getOrNull(index) ?: throw AssertionError(
            "RoomConformanceSuite: FaultInjection.Supported's FaultyLoom has no link at index $index — " +
                "it has ${links.size}.\n" +
                "  The harness's RoomFactory must weave THROUGH this loom. A loom the factories do not " +
                "use can neither partition a link nor fault its teardown, so every fault-gated " +
                "obligation here would run against a room it cannot touch.",
        )

    // ── Cancellation discrimination ───────────────────────────────────────────
    //
    // Lifted from `SeamConformanceSuite`, which carries the same two helpers for the same obligation
    // on `Seam.close`/`sendTo`. The discrimination is done by **state**, not by type: a
    // `CancellationException` caught here is this coroutine's own only if this coroutine is in fact
    // cancelled, and `ensureActive()` is the exact test for that. If it is ours it propagates (never
    // swallow a structured-concurrency cancel); if it is not, the callee minted it.

    /**
     * Run [op] and fail — rather than be cancelled — if it reports its failure as a cancellation.
     *
     * Rethrowing a callee-minted one would inflict on this test precisely the damage the obligation
     * forbids: a silent cancel with no assertion failure and no stack trace. So it is converted to a
     * failure instead. Anything that is *not* a cancellation propagates untouched — this helper has
     * no opinion about whether an operation may fail, only about how it reports failing.
     */
    private suspend inline fun assertNoMintedCancellation(what: String, op: () -> Unit) {
        try {
            op()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw AssertionError(
                "RoomConformanceSuite: $what must not report failure as a cancellation " +
                    "(Room.leave / Seam.close, #1826): a caller cannot distinguish it from its own " +
                    "cancellation, so it CANCELS the caller instead of failing it — every best-effort " +
                    "teardown loop stops there. Convert it before it escapes (withTimeoutOrNull plus " +
                    "an explicit throw). Got: $e",
            )
        }
    }

    /**
     * Run [op] and hand back whatever it threw, or `null` if it completed.
     *
     * The capture form, used where the verdict has to be read *alongside* something else — a
     * rig-fired count, a second call's outcome — so both land in one [assertAll] instead of the
     * first failure shadowing the diagnosis that explains it. `ensureActive()` does the same job it
     * does above: this test's own cancellation still propagates, a minted one is returned as a value
     * for an assertion to name.
     */
    private suspend inline fun failureOf(op: () -> Unit): Throwable? =
        try {
            op()
            null
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            failure
        }

    // ── Bounded waits ─────────────────────────────────────────────────────────
    //
    // Build every obligation on these — never on a raw `roster.first { … }` or
    // `events.filterIsInstance<…>().first()`. An unsatisfiable predicate on a StateFlow suspends
    // forever, and "forever" in a `runTest` body means the wall-clock ceiling fires and reports
    // `UncompletedCoroutinesError` — a message that names neither the room, nor the roster it saw,
    // nor the assertion that was about to run (#2284).

    /**
     * Suspend until [predicate] holds over this room's [Room.roster] and return that roster; on
     * expiry of [awaitBudget] fail with an [AssertionError] quoting the roster actually observed.
     *
     * [expected] states the predicate in the suite's own words ("roster.size == 1 — the joiner is
     * admitted"), because the predicate itself is a lambda and cannot print itself.
     */
    private suspend fun Room.awaitRoster(
        expected: String,
        predicate: (Set<Member>) -> Boolean,
    ): Set<Member> {
        val budget = awaitBudget ?: return roster.first(predicate)
        return withTimeoutOrNull(budget) { roster.first(predicate) }
            ?: fail(
                "roster never satisfied: $expected",
                budget,
                // Only when the roster is EMPTY. Said unconditionally it is a lie on the failure
                // where the roster is *correctly* empty — which is how it read on the very first
                // `awaitEvent` failure this change was proved against.
                if (roster.value.isEmpty()) {
                    "An empty roster is a fabric whose admit/identify handshake never completed: " +
                        "check that the joiner's Hello reaches the host and the host's admit " +
                        "reaches back."
                } else {
                    "The roster is non-empty but never took the expected shape — compare the " +
                        "members above against what the obligation asks for."
                },
            )
    }

    /**
     * Suspend until this room emits a [MembershipEvent] that [select] accepts and return it; on
     * expiry of [awaitBudget] fail with an [AssertionError] quoting **every event that did arrive**
     * plus the roster, so "the wrong event fired" and "nothing fired at all" are different messages.
     *
     * Collection starts where the call is made, so the `async { … }`-before-the-trigger placement
     * the partition tests rely on is unchanged.
     */
    private suspend fun <E : MembershipEvent> Room.awaitEvent(
        expected: String,
        select: (MembershipEvent) -> E?,
    ): E {
        val observed = mutableListOf<MembershipEvent>()
        val budget = awaitBudget ?: return events.mapNotNull(select).first()
        return withTimeoutOrNull(budget) {
            events.onEach { observed += it }.mapNotNull(select).first()
        } ?: fail(
            "no $expected event arrived",
            budget,
            "events observed while waiting (${observed.size}): " +
                if (observed.isEmpty()) "none" else observed.joinToString(prefix = "[", postfix = "]"),
            "The roster above is the authoritative level and the events are notifications that it " +
                "moved, so a roster that already shows the change is a fabric that made the " +
                "transition without announcing it.",
        )
    }

    /**
     * Suspend until this room delivers a [RoomFrame] and return it; on expiry of [awaitBudget] fail
     * with an [AssertionError] quoting the roster, since a frame that never arrives is usually a
     * membership problem rather than a transport one.
     */
    private suspend fun Room.awaitFrame(expected: String): RoomFrame {
        val budget = awaitBudget ?: return incoming.first()
        return withTimeoutOrNull(budget) { incoming.first() }
            ?: fail(
                "no frame arrived: $expected",
                budget,
                "`Room.incoming` drops frames from peers that are not admitted, so an unexpected " +
                    "roster above explains a missing frame before the transport does.",
            )
    }

    /**
     * Collect [count] frames from [member]'s [Room.incomingFrom] inbox; on expiry of [awaitBudget]
     * fail naming how many of them did arrive, since "some were held" and "none were" point at
     * different defects.
     */
    private suspend fun Room.awaitHeld(member: PeerId, count: Int, expected: String): List<RoomFrame> {
        val held = mutableListOf<RoomFrame>()
        val budget = awaitBudget ?: return incomingFrom(member).take(count).toList(held)
        return withTimeoutOrNull(budget) { incomingFrom(member).take(count).toList(held) }
            ?: fail(
                "only ${held.size} of $count held frames arrived: $expected",
                budget,
                "`Room.incomingFrom` holds a member's frames from its admission, so a frame missing here " +
                    "was routed before the collector started and was not held for it.",
            )
    }

    /**
     * Collect [flow] to its end, recording frames into [into], and return the [FramesLost] it
     * failed with, or `null` if it completed. On expiry of [awaitBudget] fail naming how many frames
     * arrived: a per-member stream must end by completing or failing, and one that does neither is the
     * silent loss these obligations exist to rule out.
     */
    private suspend fun Room.awaitEnd(flow: Flow<RoomFrame>, into: MutableList<RoomFrame>, expected: String): FramesLost? {
        var failure: FramesLost? = null
        val budget = awaitBudget ?: Duration.INFINITE
        val ended = withTimeoutOrNull(budget) {
            try {
                flow.toList(into)
            } catch (e: FramesLost) {
                failure = e
            }
            true
        }
        return if (ended == true) {
            failure
        } else {
            fail(
                "the incomingFrom flow neither completed nor failed: $expected (${into.size} frame(s) arrived)",
                budget,
                "A per-member stream ends by completing (the admission ended) or failing with a " +
                    "FramesLost (frames were lost). Hanging is the silent loss it must not have.",
            )
        }
    }

    /**
     * Suspend until [view]'s state reads [SeamState.Torn] and return it; on expiry of [awaitBudget]
     * fail naming the state the view was left in. A view stuck at `Woven` after its room left is
     * the defect [channelStateTornAfterLeave] exists to catch, so the message has to say which.
     */
    private suspend fun Room.awaitTorn(view: Seam, expected: String): SeamState.Torn {
        val budget = awaitBudget ?: return view.state.filterIsInstance<SeamState.Torn>().first()
        return withTimeoutOrNull(budget) { view.state.filterIsInstance<SeamState.Torn>().first() }
            ?: fail(
                "the channel view never read Torn: $expected (last state ${view.state.value})",
                budget,
                "A channel view's state forwards the room's seam state, and a room's leave ends its " +
                    "seam. A view still live after leave() is a room whose end no consumer can observe.",
            )
    }

    /** Broadcast [count] frames `"<prefix>0"`, `"<prefix>1"`, … in order. */
    private suspend fun Room.broadcastNumbered(prefix: String, count: Int) {
        repeat(count) { broadcast(applicationPayload("$prefix$it")) }
    }

    /**
     * [text] as an application payload, refusing text a [RoomFramePrefix] family would claim.
     *
     * A live room now refuses protocol-classified raw payloads at send with
     * [us.tractat.kuilt.session.ReservedRoomPrefix] (#2806). Previously, dispatch consumed them before
     * application routing: `"after-claim"` was treated as an admit frame, making the dropped-frame
     * count assertion in (4d) vacuous.
     *
     * Keep this fixture guard for third-party [Room] implementations and for its stricter payload
     * selection: [RoomFramePrefix.matches] rejects every reserved leading byte, whereas
     * [RoomFramePrefix.classifies] also checks the channel header length or heartbeat prefix.
     * Even short channel-leading payloads and ordinary `k` text, which live rooms accept, are excluded
     * here so the conformance rig uses unambiguously application-only text across implementations.
     */
    private fun applicationPayload(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        val reserved = RoomFramePrefix.entries.filter { it.matches(bytes) }
        require(reserved.isEmpty()) {
            "conformance payload \"$text\" leads with a byte reserved by $reserved, so it is not an " +
                "unambiguously application-only fixture — choose unreserved text"
        }
        return bytes
    }

    /**
     * The one failure renderer the three helpers share — the actual deliverable of #2284.
     *
     * Names the room (role / selfId / roomId), prints the roster it observed member by member with
     * liveness, and states the bound as virtual so nobody reads the expiry as a slow machine. Every
     * interpretive line is passed in by the caller as [extra]: a hint that is right for one helper's
     * failure is wrong for another's, and a wrong hint costs more than no hint.
     */
    private fun Room.fail(headline: String, budget: Duration, vararg extra: String): Nothing {
        val members = roster.value
        throw AssertionError(
            buildString {
                appendLine("RoomConformanceSuite: $headline")
                appendLine("  waited $budget of VIRTUAL time (RoomConformanceSuite.awaitBudget)")
                appendLine("  room: role=${role.value} selfId=${selfId.value} roomId=${roomId.value}")
                appendLine(
                    "  roster (${members.size} member(s)): " +
                        members.joinToString(prefix = "[", postfix = "]") {
                            "${it.identity.displayName}/${it.id.value} liveness=${it.liveness}"
                        },
                )
                append(extra.joinToString(separator = "\n") { "  $it" })
            },
        )
    }

    // ── Default harness ───────────────────────────────────────────────────────

    private fun defaultHarness(scope: CoroutineScope): RoomHarness {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val innerLoom = InMemoryLoom()
        val faultyLoom = FaultyLoom(innerLoom, scope)
        // Fake device keys, so the suite stays on virtual time on wasmJs, where a real key is a
        // WebCrypto promise. The host is one device and every room the joiner factory makes is
        // another: two joins from one shared store would be one device holding two seats.
        fun factory(device: Int) = SeamRoomFactory(
            loom = faultyLoom,
            scope = scope,
            clock = clock,
            deviceKeyStore = fakeKeyStore(device),
            heartbeatConfig = fastHeartbeatConfig,
        )
        val nextJoinerDevice = atomic(1)
        val joinerFactory = object : RoomFactory {
            override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room =
                factory(nextJoinerDevice.getAndIncrement()).host(pattern, memberName, roomId)

            override suspend fun join(tag: Tag, memberName: String?, deviceId: String?): Room =
                factory(nextJoinerDevice.getAndIncrement()).join(tag, memberName, deviceId)
        }
        return RoomHarness(
            hostFactory = factory(device = 0),
            joinerFactory = joinerFactory,
            faults = FaultInjection.Supported(faultyLoom),
            clock = clock,
            advanceClock = { ms -> clockMs += ms },
        )
    }

    private companion object {
        /**
         * How long [TeardownFault.Slow] suspends a teardown in
         * [leaveDoesNotMintACancellationWhenTeardownIsSlow] — **virtual** time, so it costs nothing.
         *
         * Generous relative to the suite's own timescale ([fastHeartbeatConfig] ticks every 100 ms)
         * for one reason: the property is falsified by an implementation bounding `seam.close` in a
         * `withTimeout`, and it can only be falsified if that bound is *shorter* than this. A value
         * near the tick rate would let a plausible one-second bound pass, which is a fixture
         * configured at exactly the setting where the assertion cannot fail. Well under
         * [awaitBudget], so it can never be the thing that expires a wait.
         */
        val SLOW_TEARDOWN = 1.seconds

        /** The channel [channelStateTornAfterLeave] takes its views on before the leave. */
        const val TERMINAL_STATE_CHANNEL = "terminal-state"

        /** A second channel, first minted after the leave, so its view cannot pre-date it. */
        const val TERMINAL_STATE_CHANNEL_AFTER = "terminal-state-after-leave"

        /** The message on the throwable the failing-teardown properties inject, so a red names itself. */
        const val INJECTED_TEARDOWN_FAILURE =
            "injected by RoomConformanceSuite: the transport's close() threw"
    }
}
