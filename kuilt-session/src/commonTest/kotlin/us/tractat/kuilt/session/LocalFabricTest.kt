@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

    // ── Harness ──────────────────────────────────────────────────────────────

    /**
     * A host room over a seam whose [Seam.capability] the test drives, on [TestScope.backgroundScope]
     * with a fixed clock. Host role and a `null` roomId keep every other room loop inert: there is no
     * reconnect controller, the torn watcher parks on a `Woven` seam, and the main loop only collects
     * `incoming`.
     */
    private fun TestScope.roomOverCapability(capability: StateFlow<TransportCapability>): SeamRoom =
        SeamRoom(
            seam = CapabilitySeam(FakeSeam(PeerId("self")), capability),
            role = SessionRole.Host,
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
