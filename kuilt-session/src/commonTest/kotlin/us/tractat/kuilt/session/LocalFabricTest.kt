@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The room's injected clock is fixed: these tests assert on transitions, never on elapsed time. */
private val AT = Instant.fromEpochMilliseconds(1_000L)

/**
 * [Room.localFabric] — this peer's own end of the fabric, as a **level** plus its two **edges**.
 *
 * The distinction these tests protect is #1712's: the level ([Room.localFabric]) is the
 * authoritative, replay-safe answer to "can I carry frames right now?", and the edges
 * ([MembershipEvent.LocalFabricLost] / [MembershipEvent.LocalFabricRestored]) are only
 * notifications that it moved. A late subscriber reads the level and cannot miss a window; an
 * early subscriber reacting to an edge must find the level already agreeing with it.
 *
 * The failure modes pinned down here are all inviting ones:
 *
 * - **Emitting an edge before writing the level** (or mirroring the level into a separate
 *   `MutableStateFlow` a dispatch behind the source) lets a consumer handle `LocalFabricLost`
 *   and read `localFabric == Available` — the headline #1712 case exactly inverted.
 * - **Treating [FabricAvailability.Unknown] as either answer.** "We stopped being able to tell"
 *   is not a loss, and is not a recovery. On every fabric without a live OS path observer
 *   `Unknown` is the *only* value there will ever be, so an edge minted for it would be
 *   permanent noise.
 * - **Tracking only the previous value** instead of the last *decided* one, which swallows a
 *   recovery that passed through `Unknown` on the way back — the common real trajectory, since a
 *   radio coming back reports "cannot tell yet" before it reports "up".
 */
class LocalFabricTest {

    @Test
    fun availableToUnavailableToAvailableDrivesLevelAndEdges() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()
            capability.value = TransportCapability(emptySet(), FabricAvailability.Available)
            runCurrent()

            val lost = seen.filterIsInstance<MembershipEvent.LocalFabricLost>()
            val restored = seen.filterIsInstance<MembershipEvent.LocalFabricRestored>()
            assertAll(
                { assertIs<FabricAvailability.Available>(room.localFabric.value) },
                { assertEquals(1, lost.size, "exactly one Lost edge for one drop: $seen") },
                { assertEquals("radio off", lost.single().reason) },
                { assertEquals(AT, lost.single().at, "the edge is stamped from the room's clock") },
                { assertEquals(1, restored.size, "exactly one Restored edge for one recovery: $seen") },
            )
        }

    /** A consumer reacting to the edge must not be able to read a level that disagrees with it. */
    @Test
    fun levelIsVisibleFromInsideTheEdgeCollector() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            var levelAtEdge: FabricAvailability? = null
            backgroundScope.launch {
                room.events.filterIsInstance<MembershipEvent.LocalFabricLost>()
                    .collect { levelAtEdge = room.localFabric.value }
            }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()

            assertIs<FabricAvailability.Unavailable>(levelAtEdge)
        }

    /**
     * The level is a **projection of the seam, not a copy of it**: correct with *no* dispatch in
     * between.
     *
     * There is deliberately no `runCurrent()` between the write and the read, and that omission is
     * the whole test. A level mirrored into the room's own `MutableStateFlow` by [Room.events]'
     * collector would still read `Available` at this point, and every site that reads the level from
     * a *different* coroutine — #1712's precedence tag above all — would then report a fabric that
     * is already down as up. Note that the edge-collector test above cannot catch that: the room
     * buffers events rather than delivering them inline, so a mirror written even one line *after*
     * the edge has still landed by the time a subscriber runs.
     */
    @Test
    fun levelTracksTheSeamWithNoDispatchInBetween() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))

            assertIs<FabricAvailability.Unavailable>(room.localFabric.value)
        }

    /**
     * The level honours the [StateFlow] conflation contract across a role-only capability change.
     *
     * `TransportCapability → availability` is **not** injective: a fabric that gains a role while
     * its path stays up publishes a new capability whose availability is unchanged. A naive
     * projection would re-emit an equal value to every collector, which a `StateFlow` must never do.
     */
    @Test
    fun aRoleOnlyChangeIsNotAnEmission() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            val levels = mutableListOf<FabricAvailability>()
            backgroundScope.launch { room.localFabric.collect { levels += it } }
            runCurrent()

            capability.value = TransportCapability(setOf(TransportRole.WifiLan), FabricAvailability.Available)
            runCurrent()

            assertEquals(listOf<FabricAvailability>(FabricAvailability.Available), levels)
        }

    /** Unknown is level-only: entering it claims neither loss nor restoration. */
    @Test
    fun unknownEmitsNoEdge() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unknown("observer gone"))
            runCurrent()

            assertAll(
                { assertIs<FabricAvailability.Unknown>(room.localFabric.value) },
                { assertTrue(seen.none { it is MembershipEvent.LocalFabricLost }, "no Lost for Unknown: $seen") },
                {
                    assertTrue(
                        seen.none { it is MembershipEvent.LocalFabricRestored },
                        "no Restored for Unknown: $seen",
                    )
                },
            )
        }

    /**
     * Recovery THROUGH Unknown still restores. Tracking only the previous value would swallow
     * this: at the Available step the previous value is Unknown, not Unavailable.
     */
    @Test
    fun unavailableThroughUnknownToAvailableStillRestores() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()
            capability.value = TransportCapability(emptySet(), FabricAvailability.Unknown("observer gone"))
            runCurrent()
            capability.value = TransportCapability(emptySet(), FabricAvailability.Available)
            runCurrent()

            assertEquals(1, seen.filterIsInstance<MembershipEvent.LocalFabricRestored>().size, "$seen")
        }

    /**
     * The floor case, and the one every fabric but `nw` reports today: a seam that never overrides
     * [Seam.capability] inherits a roleless [FabricAvailability.Unknown], so the room's level is
     * `Unknown` and no edge is ever minted. "kuilt cannot tell" must stay visible as itself rather
     * than being coerced into either answer.
     */
    @Test
    fun aSeamWithNoPathObserverReportsUnknownAndNoEdges() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val room = SeamRoom(
                seam = FakeSeam(PeerId("self")),
                role = SessionRole.Host,
                memberName = "self",
                scope = backgroundScope,
                clock = { AT },
                heartbeatConfig = HeartbeatConfig(),
            ).also { it.start() }
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            assertAll(
                { assertIs<FabricAvailability.Unknown>(room.localFabric.value) },
                { assertTrue(seen.isEmpty(), "an observer-less fabric mints no fabric edge: $seen") },
            )
        }

    /**
     * **A joiner gets its own fabric edges.** The fold is not role-gated, and must not become so.
     *
     * #1712's headline scenario is the *dropped device* reporting its own outage instead of blaming
     * its peer — and the dropped device is usually a **joiner**. `localFabricLoop()` is therefore
     * launched from [SeamRoom.start]'s unconditional job list, deliberately outside every role gate:
     * self-reachability is a fact about this peer's own end of the fabric, so a host needs it exactly
     * as much as a joiner does.
     *
     * Every other test in this file drives a **host** room, so re-gating that launch to
     * `if (role == SessionRole.Host)` would leave all of them green while silently breaking the
     * feature's primary case. This test is the only thing standing between that regression and a
     * clean suite.
     *
     * (A joiner room is inert enough here to assert the event list exactly: its `Hello` broadcast is
     * only *recorded* by [FakeSeam], and its admit deadline is armed on virtual time that these
     * tests never advance — so the fabric edges are the whole transcript.)
     */
    @Test
    fun aJoinerGetsItsOwnFabricEdgesToo() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability, role = SessionRole.Joiner)
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()
            capability.value = TransportCapability(emptySet(), FabricAvailability.Available)
            runCurrent()

            assertEquals<List<MembershipEvent>>(
                listOf(
                    MembershipEvent.LocalFabricLost(AT, "radio off"),
                    MembershipEvent.LocalFabricRestored(AT),
                ),
                seen,
                "a joiner must get both of its own fabric edges — the fold is not host-gated",
            )
        }

    /**
     * A drop in the construction → [SeamRoom.start] window still reports a `Lost` edge.
     *
     * Seeding the fold from the value read at *loop start* rather than at construction would
     * swallow it: by then the level is already `Unavailable`, so the transition looks like it has
     * been announced when no consumer ever saw it.
     */
    @Test
    fun aDropBeforeTheLoopStartsStillReportsLost() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = SeamRoom(
                seam = CapabilitySeam(FakeSeam(PeerId("self")), capability),
                role = SessionRole.Host,
                memberName = "self",
                scope = backgroundScope,
                clock = { AT },
                heartbeatConfig = HeartbeatConfig(),
            )
            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            room.start()

            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            assertEquals<List<MembershipEvent>>(
                listOf(MembershipEvent.LocalFabricLost(AT, "radio off")),
                seen,
                "a drop between construction and start must still be announced",
            )
        }

    // ── The precedence tag (#1712) ───────────────────────────────────────────

    /**
     * **The headline #1712 case.** A joiner whose own radio dies must not report "the host is gone"
     * as if it were news about the host.
     *
     * Both events the drop produces — [MembershipEvent.Partitioned] for the host peer and the
     * terminal [MembershipEvent.HostLost] — carry this peer's own
     * [FabricAvailability.Unavailable], so a consumer branches on one event instead of correlating
     * the partition stream against the fabric stream by timestamp.
     */
    @Test
    fun ourOwnRadioDyingTagsBothPartitionedAndHostLostAsSelfAttributed() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, virtualClock(), dropConfig)
            factory.host(Pattern("Table"))

            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val radio = FaultySeam(loom.join(InMemoryTag("Table")), backgroundScope)
            val joiner = factory.adopt(CapabilitySeam(radio, capability), SessionRole.Joiner)
            joiner.roster.first { it.size == 1 }

            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { joiner.events.collect { seen += it } }
            runCurrent()

            // Airplane mode: frames stop flowing AND the platform reports our own path down.
            radio.partition()
            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            testScheduler.advanceTimeBy(
                dropConfig.timeout + dropConfig.reconnectWindow + dropConfig.interval * 6,
            )
            runCurrent()

            assertAll(
                {
                    assertIs<FabricAvailability.Unavailable>(
                        seen.filterIsInstance<MembershipEvent.Partitioned>().single().localFabric,
                        "the host going quiet while our own fabric is down is not evidence about " +
                            "the host: $seen",
                    )
                },
                {
                    assertIs<FabricAvailability.Unavailable>(
                        seen.filterIsInstance<MembershipEvent.HostLost>().single().localFabric,
                        "HostLost is the highest-value site — a joiner whose own radio died must " +
                            "not render 'the host is gone': $seen",
                    )
                },
            )
        }

    /**
     * The contrast that makes the tag worth carrying: *their* network died, not ours.
     *
     * Same injected drop as above, read from the other end. The host's own fabric never moved, so
     * its [MembershipEvent.Partitioned] carries [FabricAvailability.Available] — and the two cases
     * are now distinguishable from one event, which is the whole ask.
     */
    @Test
    fun aPeerDroppingWhileOurOwnFabricIsUpIsNotSelfAttributed() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, virtualClock(), dropConfig)

            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val host = factory.adopt(
                CapabilitySeam(loom.host(Pattern("Table")), capability),
                SessionRole.Host,
            )
            val joinerLink = FaultySeam(loom.join(InMemoryTag("Table")), backgroundScope)
            factory.adopt(joinerLink, SessionRole.Joiner)
            host.roster.first { it.size == 1 }

            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { host.events.collect { seen += it } }
            runCurrent()

            joinerLink.partition()
            // Detection only — short of the reconnect window, so the seat is still held.
            testScheduler.advanceTimeBy(dropConfig.timeout + dropConfig.interval * 3)
            runCurrent()

            assertIs<FabricAvailability.Available>(
                seen.filterIsInstance<MembershipEvent.Partitioned>().single().localFabric,
                "our own fabric never moved, so this partition IS evidence about the peer: $seen",
            )
        }

    /**
     * The tag is read from the **zero-lag projection**, correct with no dispatch in between.
     *
     * The ordering here is the entire test. The host's authoritative
     * [us.tractat.kuilt.session.admit.AdmitMessage.Paused] is already queued on this room's link
     * when our own fabric dies, and there is deliberately **no** `runCurrent()` between the two —
     * so when the room's main loop emits [MembershipEvent.Partitioned], the capability collector
     * has not yet been dispatched for the new value. A level mirrored into a `MutableStateFlow` by
     * that collector would still read `Available` at this instant, tagging a radio death as a
     * healthy-fabric peer drop: the #1712 headline case exactly inverted, and the worst possible
     * failure of this feature. Reading [Room.localFabric] — which projects [Seam.capability]
     * directly — is what makes it impossible.
     */
    @Test
    fun theTagIsCurrentWithNoDispatchBetweenTheFabricChangeAndTheEmission() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            // Detection an order of magnitude beyond this test's advancement budget: no detector can
            // fire on its own, so the only Partitioned is the one the Paused frame drives, at the
            // instant we choose.
            val factory = SeamRoomFactory(loom, backgroundScope, virtualClock(), inertConfig)

            // Adopting the host's seam keeps a handle on it, so the authoritative Paused can be sent
            // by hand rather than waiting for a detector inside an advanceTimeBy.
            val hostLink = loom.host(Pattern("Table"))
            factory.adopt(hostLink, SessionRole.Host)
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val self = factory.adopt(
                CapabilitySeam(loom.join(InMemoryTag("Table")), capability),
                SessionRole.Joiner,
            )
            val other = factory.join(InMemoryTag("Table"))
            self.roster.first { it.size == 2 }

            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { self.events.collect { seen += it } }
            runCurrent()

            hostLink.sendTo(
                self.selfId,
                AdmitMessage.encode(AdmitMessage.Paused(other.selfId.value, expiresAt = 60_000L)),
            )
            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()

            assertIs<FabricAvailability.Unavailable>(
                seen.filterIsInstance<MembershipEvent.Partitioned>().single().localFabric,
                "the tag must be read from the zero-lag projection, not a mirrored level one " +
                    "collector dispatch behind the seam: $seen",
            )
        }

    /**
     * The floor, and what a consumer observes on **every fabric but a path-observing one today**:
     * the tag reads [FabricAvailability.Unknown].
     *
     * `Unknown` is the normal value here, not an error and not missing data. It must stay readable
     * as itself — "kuilt cannot tell whether our own end was up" — rather than being coerced to
     * `Available` (which would silently re-assert the very claim #1712 exists to stop the library
     * making) or to `Unavailable` (which would suppress every honest peer-drop signal).
     */
    @Test
    fun aFabricWithNoPathObserverTagsTheEventsUnknown() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, virtualClock(), dropConfig)
            factory.host(Pattern("Table"))

            val radio = FaultySeam(loom.join(InMemoryTag("Table")), backgroundScope)
            val joiner = factory.adopt(radio, SessionRole.Joiner)
            joiner.roster.first { it.size == 1 }

            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { joiner.events.collect { seen += it } }
            runCurrent()

            radio.partition()
            testScheduler.advanceTimeBy(
                dropConfig.timeout + dropConfig.reconnectWindow + dropConfig.interval * 6,
            )
            runCurrent()

            assertAll(
                {
                    assertIs<FabricAvailability.Unknown>(
                        seen.filterIsInstance<MembershipEvent.Partitioned>().single().localFabric,
                        "an observer-less fabric must tag Partitioned Unknown, never Available: $seen",
                    )
                },
                {
                    assertIs<FabricAvailability.Unknown>(
                        seen.filterIsInstance<MembershipEvent.HostLost>().single().localFabric,
                        "…and must tag HostLost Unknown too: $seen",
                    )
                },
            )
        }

    // ── Harness ──────────────────────────────────────────────────────────────

    /**
     * Fast detection with a generous window, matching [MembershipEventDropContractTest]: the whole
     * `Partitioned → HostLost` arc fits in a couple of seconds of virtual time.
     */
    private val dropConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 2.seconds,
    )

    /**
     * Detection far beyond any advancement budget below, so no detector can reach a conclusion on
     * its own — the [StarTopologyPresenceFanoutTest] idiom for making a room's *only* partition
     * source an injected, hand-timed frame.
     */
    private val inertConfig = HeartbeatConfig(
        interval = 10.seconds,
        timeout = 60.seconds,
        reconnectWindow = 60.seconds,
    )

    /** Virtual-time clock for the multi-room tests, which assert on real detector trajectories. */
    private fun TestScope.virtualClock(): () -> Instant =
        { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

    /**
     * A room over a seam whose [Seam.capability] the test drives, on [TestScope.backgroundScope]
     * with a fixed clock. A `null` roomId keeps every other room loop inert: there is no reconnect
     * controller (host-only, and gated on a non-null roomId), the torn watcher parks on a `Woven`
     * seam, and the main loop only collects `incoming`.
     *
     * [role] defaults to [SessionRole.Host] — the inertest case — but is a parameter rather than a
     * constant precisely because the fold must run for a joiner too; see
     * [aJoinerGetsItsOwnFabricEdgesToo].
     */
    private fun TestScope.roomOverCapability(
        capability: StateFlow<TransportCapability>,
        role: SessionRole = SessionRole.Host,
    ): SeamRoom =
        SeamRoom(
            seam = CapabilitySeam(FakeSeam(PeerId("self")), capability),
            role = role,
            memberName = "self",
            scope = backgroundScope,
            clock = { AT },
            heartbeatConfig = HeartbeatConfig(),
        ).also { it.start() }
}

/**
 * A [Seam] decorator that replaces only [capability] — the one member [FakeSeam] cannot drive —
 * and delegates everything else untouched. Mirrors `FastReconnectRaceTest`'s `GatedPeersSeam`
 * idiom: the test, not a platform observer, decides what the fabric reports about itself.
 */
private class CapabilitySeam(
    delegate: Seam,
    override val capability: StateFlow<TransportCapability>,
) : Seam by delegate
