package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultyLoom
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.ReconnectReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.RoomFrame
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
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
 * Subclass and implement [newHarness] to bind any [RoomFactory] under test.
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
 * **Fault injection:** tests that require partition behaviour go through [RoomHarness.faults], a
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
 * They are closed by three different mechanisms, and which one applies is a judgement about *whose*
 * limitation the missing state is:
 *
 *  - **`resumeToken` — a loud precondition.** [Room.resumeToken] is documented non-null on an
 *    admitted joiner, so a null there is a contract violation by the room, not a limitation of the
 *    harness, and [requireResumeToken] fails naming the room. The early return was not even
 *    protecting anyone: [joinerLearnsHostRoomIdOnAdmission] already compares
 *    `resumeToken?.roomId` against a non-null host id, so the same room was *already* failing one
 *    test of this suite while silently skipping another. Two tests, one fabric, opposite verdicts.
 *  - **Fault injection — a two-armed sealed fixture** ([FaultInjection]). Here the missing state
 *    genuinely belongs to the harness: a room over a fabric whose links the test cannot reach
 *    cannot be partitioned by anyone. A nullable hook was the wrong shape for it — `null` moves the
 *    vacuity one level up, where it is a value nobody has to justify. The sealed arm makes
 *    declining *representable only together with its declaration*, so the pairing is the compiler's
 *    job rather than a meta-test's, and the arm that declines still ends in an assertion
 *    ([injectorOrDeclaredGap]) rather than in a bare `return`.
 *  - **The refusal branch — a property that needed no hatch at all.**
 *    [aTokenMintedForAnotherRoomIsRefused] was simply never written; see its KDoc for what the
 *    `Room.resume` surface can and cannot observe.
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
 * | # | Mutation | Kind | after | before |
 * |---|----------|------|-------|--------|
 * | M1 | `SeamRoom.resumeToken` returns `null` — a room opting out of resume entirely | real | RED: [resumeWithinWindowFiresResumed] and [aTokenMintedForAnotherRoomIsRefused] on the loud precondition, naming `role=Joiner … roster=1 member(s)` | [joinerLearnsHostRoomIdOnAdmission] RED — but [resumeWithinWindowFiresResumed] **green by absence** |
 * | M2 | [injectorOrDeclaredGap] drops its assertion — i.e. the pre-#2306 silent `?: return@runTest`, exactly | rig | RED: all four `blankTrackingUrl*` | all green; the four gated obligations passed under a gap declaring nothing |
 * | M3 | delete the `token.roomId != roomId` guard in `DefaultJoinerReconnectController.tryResume` | real | RED: [aTokenMintedForAnotherRoomIsRefused], all 3 assertions — `got Success`, then `WindowClosed` for the genuine token | every pre-existing test of this suite **green** |
 * | M4 | the host drops a foreign token silently instead of refusing it | synthetic | RED: [aTokenMintedForAnotherRoomIsRefused], 2 of 3 — `Got TimedOut` | all green |
 *
 * **M1 is the argument for [requireResumeToken].** The same fabric was *already* failing
 * [joinerLearnsHostRoomIdOnAdmission] while [resumeWithinWindowFiresResumed] returned green without
 * asserting anything — two tests of one suite, opposite verdicts on one room. The early return was
 * not protecting a population; it was hiding a contradiction.
 *
 * **M4 is why the "verdict, not silence" assertion is not decoration.** Under it the first
 * assertion — "must not be [ResumeResult.Success]" — stays **green**, because `TimedOut` is not
 * `Success`; only the second reds. A lone refusal check would have passed a host that never
 * answered at all. (Its third assertion also reds under M4, but as *blast radius*: the window
 * elapses during the resume timeout. Not an independent diagnosis, and not claimed as one.)
 *
 * **M3's claim is narrower than it looks, and the narrowing matters.** It also reds two tests in
 * `:kuilt-session`'s own `JoinerReconnectControllerTest`, so the defect is not invisible to the
 * tree — only to the *contract*. That is precisely the thing a second [RoomFactory] inherits
 * nothing of: an implementation's private suite is not a conformance obligation.
 *
 * **One assertion has no red anywhere**, and that is correct:
 * `RoomConformanceGapDeclarationTest.aDeclaredGapSkipsEveryGatedObligationCleanly`. It does not
 * describe behaviour under test — it is the survivability check, and its falsifying input is a
 * future edit that moves work *above* the gate (a suspending wait, a `links[0]` access), not any
 * defect present today. Stating it rather than hiding it: an all-red table would mean the table was
 * measuring blast radius instead of diagnoses.
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
         * The harness cannot reach the links under its rooms, so this suite's partition, resume and
         * host-loss obligations are **unproven** on it.
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
                // The token is validated against exactly this id, so a room whose two surfaces
                // disagreed would refuse its own members' resumes.
                { assertEquals(host.roomId.value, joiner.resumeToken?.roomId) },
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

    // ── (8) Resumed fires on Room.resume(token) within the window ───────────

    @Test
    public fun resumeWithinWindowFiresResumed(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faults.injectorOrDeclaredGap("Resumed fires on resume() within the window")
                ?: return@runTest

            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val token = joinerRoom.requireResumeToken()

            faultyLoom.setFaultProfileOnAll(FaultProfile.DropAll(Direction.Both))
            repeat(4) { h.advanceClock(100L); advanceTimeBy(100L) }

            val hostResumed = async {
                hostRoom.awaitEvent("MembershipEvent.Resumed") { it as? MembershipEvent.Resumed }
            }
            val joinerResumed = async {
                joinerRoom.awaitEvent("MembershipEvent.Resumed") { it as? MembershipEvent.Resumed }
            }

            faultyLoom.setFaultProfileOnAll(FaultProfile.Healthy)
            advanceTimeBy(50L)

            val result = joinerRoom.resume(token)
            assertIs<ResumeResult.Success>(result)
            assertIs<MembershipEvent.Resumed>(hostResumed.await())
            assertIs<MembershipEvent.Resumed>(joinerResumed.await())

            hostRoom.leave()
            joinerRoom.leave()
        }

    // ── (8a) a resume token minted for another room is refused ───────────────

    /**
     * **The negative half of [joinerLearnsHostRoomIdOnAdmission].** That test asserts a joiner's
     * token names the host's room, and its own comment reasons about a room that would "refuse its
     * own members' resumes" — but nothing ever presented a room with a token naming a *different*
     * room and checked that it said no. Only [ResumeResult.Success] had a property in this suite;
     * every refusal branch was covered by the reference implementation's private tests
     * (`RoomResumeTest`, `JoinerReconnectControllerTest`), which a second [RoomFactory] inherits
     * nothing from.
     *
     * ### The contrast pair is the property, not the refusal alone
     *
     * A lone "the foreign token is refused" would be satisfied by a room that refuses *everything* —
     * a dead host, a window that never opened, a resume path that silently drops. So the two resumes
     * happen against the **same host at the same instant**, one with a token whose [RoomId] has been
     * tampered with and one with the genuine token, and the property is that they get different
     * answers. That contrast is what pins the refusal to the *token* rather than to the moment; it
     * is also what proves the refused attempt did not spend the single-use window, which is the
     * denial-of-service shape a room that consumed a window on an invalid token would have.
     *
     * ### What the `Room.resume` surface can and cannot observe — read before strengthening this
     *
     * #2306 asked for [ResumeResult.TokenInvalid] here. **It is not observable through
     * [Room.resume].** The reference host does produce it (`DefaultJoinerReconnectController`
     * checks the room id before it touches any window, which is why this test needs no window state
     * of its own for the *refusal* half), but it travels the wire as an
     * `AdmitMessage.Reject(RejectCode.ResumeTokenInvalid)` and the joiner's resume machine completes
     * **every** reject as [ResumeResult.WindowClosed] — deliberately, so its retry loop survives the
     * transient window-not-yet-open race. So `Room.resume`'s reachable range on the reference is
     * `{Success, WindowClosed, TimedOut}`; [ResumeResult.TokenInvalid] and
     * [ResumeResult.WindowNotYetOpen] are host-internal verdicts. Asserting `TokenInvalid` here
     * would have pinned a value no implementation can return.
     *
     * This test therefore asserts the strongest thing the surface admits — a **terminal refusal**,
     * either spelling — and deliberately excludes [ResumeResult.TimedOut], which is the rig receipt:
     * `TimedOut` means no verdict arrived at all, so a host that never saw the frame reds here
     * instead of passing as a refusal it never made.
     *
     * ### Mutation receipt
     *
     * Deleting the `token.roomId != roomId` guard in
     * [us.tractat.kuilt.session.partition.DefaultJoinerReconnectController.tryResume] makes the
     * foreign token indistinguishable from the genuine one: it consumes the window, so the first
     * resume returns [ResumeResult.Success] (reddening the refusal assertions) and the second
     * returns [ResumeResult.WindowClosed] (reddening the positive control). Measured: all three
     * assertions red, every pre-existing test of this suite green.
     *
     * **Not invisible everywhere, though — say what the row actually claims.** That same mutation
     * reds two tests in `:kuilt-session`'s own `JoinerReconnectControllerTest`. What was missing is
     * narrower: the guard was proven by the *implementation's private suite* and by nothing in the
     * *contract*, so a second [RoomFactory] subclassing this suite inherited no such property at
     * all. Making a room refuse a foreign token is the kind of obligation every implementation owes
     * and only one had been asked for.
     */
    @Test
    public fun aTokenMintedForAnotherRoomIsRefused(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faults.injectorOrDeclaredGap("a resume token minted for another room is refused")
                ?: return@runTest

            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.awaitRoster("roster.size == 1 — the joiner is admitted") { it.size == 1 }
            joinerRoom.awaitRoster("roster.isNotEmpty() — the host is visible") { it.isNotEmpty() }

            val token = joinerRoom.requireResumeToken()
            // Same peer, same issue time, one room id away — so the only thing that can distinguish
            // the two attempts below is the claim the host is supposed to be checking.
            val foreign = token.copy(roomId = RoomId("${token.roomId.value}-a-different-room"))

            // Open the window: partition long enough for the host's detector to fire, then heal, so
            // both resumes below are presented to a host that WOULD accept a valid one.
            faultyLoom.setFaultProfileOnAll(FaultProfile.DropAll(Direction.Both))
            repeat(4) { h.advanceClock(100L); advanceTimeBy(100L) }
            faultyLoom.setFaultProfileOnAll(FaultProfile.Healthy)
            advanceTimeBy(50L)

            val refused = joinerRoom.resume(foreign)
            val accepted = joinerRoom.resume(token)

            assertAll(
                {
                    assertFalse(
                        refused is ResumeResult.Success,
                        "a room must not resume a token minted for a different room; got $refused",
                    )
                },
                {
                    assertTrue(
                        refused is ResumeResult.WindowClosed || refused is ResumeResult.TokenInvalid,
                        "the refusal must be a VERDICT, not silence: ResumeResult.TimedOut means no reply " +
                            "arrived, which would make the assertion above vacuous. Got $refused",
                    )
                },
                {
                    assertIs<ResumeResult.Success>(
                        accepted,
                        "the genuine token must still resume: a refused token must not spend the window, " +
                            "and this is also what proves the host was reachable when it refused",
                    )
                },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

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

            joinerRoom.broadcast("after-host-lost".encodeToByteArray())
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

            hostRoom.broadcast("after-leave".encodeToByteArray())
            advanceTimeBy(100L)
            collectJob.cancel()

            assertFalse(received, "a member that has Left must not receive broadcast frames")

            hostRoom.leave()
        }

    // ── Declared gaps ─────────────────────────────────────────────────────────

    /**
     * The [FaultyLoom] to partition with, or `null` after **asserting** that the harness declared
     * why it has none.
     *
     * This is what stops the three partition obligations from returning silently. On
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
     * This joiner's [Room.resumeToken], or fail naming the room.
     *
     * A **loud precondition**, not a skip, because the missing state is the *room's* fault and not
     * the harness's: [Room.resumeToken] is documented non-null on a [SessionRole.Joiner] room once
     * the host's Welcome has landed, and every caller here has already awaited a non-empty roster.
     * A `null` at that point is a room opting out of the entire resume/reconnect obligation with no
     * assertion and no declaration — the "optional ≠ tuning" shape, one nullable gating a whole
     * functional path.
     *
     * The early return this replaces was not even load-bearing: [joinerLearnsHostRoomIdOnAdmission]
     * compares `resumeToken?.roomId` against a non-null host [RoomId], so a room with no token was
     * *already* failing that test while silently skipping this one.
     */
    private fun Room.requireResumeToken(): ResumeToken =
        resumeToken ?: throw AssertionError(
            "RoomConformanceSuite: an admitted joiner has no resumeToken.\n" +
                "  room: role=${role.value} selfId=${selfId.value} roomId=${roomId.value} " +
                "roster=${roster.value.size} member(s)\n" +
                "  Room.resumeToken is non-null on a Joiner room once the host's Welcome has landed, and " +
                "this room's roster is already non-empty. A room that answers null here opts out of the " +
                "whole resume/reconnect obligation; there is no gap hook for it because it is a contract " +
                "violation, not a harness limitation.",
        )

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
        val factory = SeamRoomFactory(
            loom = faultyLoom,
            scope = scope,
            clock = clock,
            heartbeatConfig = fastHeartbeatConfig,
        )
        return RoomHarness(
            hostFactory = factory,
            joinerFactory = factory,
            faults = FaultInjection.Supported(faultyLoom),
            clock = clock,
            advanceClock = { ms -> clockMs += ms },
        )
    }
}
