@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.FakeLoom
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A [RoomId] names **one room instance**, not the device that hosted it (#1594), and both roles can
 * read the one they agreed on ([Room.roomId]).
 *
 * ## Why the fixture pins `selfId`
 *
 * The bug only shows on a host whose `selfId` is *stable* — in production a durable device id. A
 * fabric that mints a fresh peer id per weave (e.g. [InMemoryLoom]) hands consecutive rooms two
 * different `selfId`s, so `selfId + "-room"` looks unique and the test proves nothing. [FakeLoom]
 * derives `selfId` from the pattern's session name, so hosting the same name twice reproduces one
 * device hosting two rooms — which is the case that collided.
 *
 * ## Why the clock is frozen
 *
 * Both rooms are minted at the *same* instant. A mint that leant on the clock alone would collide
 * here (and does, for two factories sharing one virtual clock), so freezing it is what makes the
 * monotonic counter load-bearing rather than incidental.
 */
class RoomIdentityTest {
    /** The number the next fake device gets: every store made in a test is a new device, so no two
     * rooms share a key unless a test hands one store to both. */
    private var nextDevice = 0

    private fun factory(loom: Loom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { FROZEN }, deviceKeyStore = fakeKeyStore(nextDevice++))

    // ── The bug: one device, two rooms, one id ────────────────────────────────

    @Test
    fun `two rooms hosted by one device get different room ids`() =
        runTest {
            val f = factory(FakeLoom(), backgroundScope)

            val first = f.host(Pattern(DEVICE), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))
            val second = f.host(Pattern(DEVICE), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))

            assertAll(
                { assertNotNull(first.roomId.value, "a host knows its room id at construction") },
                { assertNotNull(second.roomId.value, "a host knows its room id at construction") },
                {
                    assertNotEquals(
                        first.roomId.value,
                        second.roomId.value,
                        "two rooms hosted by the same device must not share one RoomId",
                    )
                },
            )
        }

    @Test
    fun `two rooms adopted by one device get different room ids`() =
        runTest {
            val f = factory(FakeLoom(), backgroundScope)

            val first = f.adopt(FakeSeam(selfId = PeerId(DEVICE)), AdoptRole.Host(HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())))
            val second = f.adopt(FakeSeam(selfId = PeerId(DEVICE)), AdoptRole.Host(HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())))

            assertNotEquals(
                first.roomId.value,
                second.roomId.value,
                "two rooms adopted by the same device must not share one RoomId",
            )
        }

    /**
     * Two factories that share one virtual clock **and** one device id — the shape a test harness
     * produces routinely. Nothing but a process-wide counter separates them, so this is the case
     * that rules out a per-factory sequence.
     */
    @Test
    fun `two factories sharing one clock and one device still mint different room ids`() =
        runTest {
            val first = factory(FakeLoom(), backgroundScope).host(Pattern(DEVICE), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))
            val second = factory(FakeLoom(), backgroundScope).host(Pattern(DEVICE), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))

            assertNotEquals(first.roomId.value, second.roomId.value)
        }

    // ── The caller-supplied override ──────────────────────────────────────────

    @Test
    fun `host uses the caller-supplied room id verbatim`() =
        runTest {
            val supplied = RoomId("table-7")
            val room = factory(FakeLoom(), backgroundScope).host(Pattern(DEVICE), roomId = supplied, admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))

            assertEquals(supplied, room.roomId.value, "a supplied RoomId must not be overwritten by a mint")
        }

    @Test
    fun `adopt uses the caller-supplied room id verbatim`() =
        runTest {
            val supplied = RoomId("table-9")
            val room = factory(FakeLoom(), backgroundScope)
                .adopt(FakeSeam(selfId = PeerId(DEVICE)), AdoptRole.Host(HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()), roomId = supplied))

            assertEquals(supplied, room.roomId.value, "a supplied RoomId must not be overwritten by a mint")
        }

    /**
     * The reason the override exists: an identity decided *outside* kuilt — a lobby code, an invite
     * link, a matchmaker-assigned game id — is adopted by every room told to use it, so two rooms
     * naming one logical game agree rather than each inventing an id.
     *
     * Deliberately **not** framed as host-restart resume. Reusing an id does not give a restarted
     * host its seats back: it has an empty roster, so a returning device is admitted as a new member.
     */
    @Test
    fun `two rooms told to use one id adopt it rather than minting`() =
        runTest {
            val lobbyCode = RoomId("table-7")
            val f = factory(FakeLoom(), backgroundScope)

            val first = f.host(Pattern(DEVICE), roomId = lobbyCode, admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))
            val second = f.host(Pattern(DEVICE), roomId = lobbyCode, admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))

            assertAll(
                { assertEquals(lobbyCode, first.roomId.value) },
                { assertEquals(lobbyCode, second.roomId.value) },
            )
        }

    // A joiner adopt cannot carry a room id at all: `AdoptRole.Joiner` has no field for one, so the
    // `require` this file used to pin is a type now (#3220).

    // ── The mint's own parts ──────────────────────────────────────────────────

    /**
     * Pins the **clock** segment, which nothing else here can see: every other test freezes the
     * clock, so replacing `clock().toEpochMilliseconds()` with a constant leaves them all green and
     * the counter silently carries every assertion. [FROZEN] is deliberately a non-zero instant for
     * the same reason — against `FROZEN = 0` the obvious `0` mutation would be invisible too.
     */
    @Test
    fun `a minted room id carries the clock's instant`() =
        runTest {
            val room = factory(FakeLoom(), backgroundScope).host(Pattern(DEVICE), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))

            val id = assertNotNull(room.roomId.value).value
            assertTrue(
                id.startsWith("$DEVICE-room-${FROZEN.toEpochMilliseconds()}-"),
                "expected the injected clock's instant in the minted id, got: $id",
            )
        }

    /** And it *moves* with the clock — a constant in that position would pass the test above. */
    @Test
    fun `rooms minted at different instants carry different timestamps`() =
        runTest {
            var now = FROZEN
            val f = SeamRoomFactory(FakeLoom(), backgroundScope, clock = { now }, deviceKeyStore = fakeKeyStore(nextDevice++))

            val first = assertNotNull(f.host(Pattern(DEVICE), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())).roomId.value).value
            now = FROZEN + 5.seconds
            val second = assertNotNull(f.host(Pattern(DEVICE), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())).roomId.value).value

            assertAll(
                { assertTrue(first.startsWith("$DEVICE-room-${FROZEN.toEpochMilliseconds()}-"), first) },
                {
                    assertTrue(
                        second.startsWith("$DEVICE-room-${(FROZEN + 5.seconds).toEpochMilliseconds()}-"),
                        second,
                    )
                },
            )
        }

    // ── Both roles read the agreed id ─────────────────────────────────────────

    @Test
    fun `a joiner learns the host's room id on admission`() =
        runTest {
            val loom = InMemoryLoom()
            val f = factory(loom, backgroundScope)

            val hostRoom = f.host(Pattern(SESSION), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))
            val joinerRoom = f.join(InMemoryTag(SESSION))
            val beforeAdmission = joinerRoom.roomId.value

            joinerRoom.roster.first { it.isNotEmpty() }

            assertAll(
                { assertNull(beforeAdmission, "a joiner has no room id before it is admitted") },
                { assertNotNull(hostRoom.roomId.value, "a host knows its room id at construction") },
                {
                    assertEquals(
                        hostRoom.roomId.value,
                        joinerRoom.roomId.value,
                        "both roles must read the id they agreed on in the Welcome",
                    )
                },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }

    /**
     * A regression guard, not a demonstration of the bug — it holds before and after the mint
     * changes, and that is the point: a mint that moved the host's id without moving the one the
     * `Welcome` carries would leave the joiner naming another room.
     */
    @Test
    fun `a joiner learns the same room id the host holds`() =
        runTest {
            val loom = InMemoryLoom()
            val f = factory(loom, backgroundScope)

            val hostRoom = f.host(Pattern(SESSION), admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))
            val joinerRoom = f.join(InMemoryTag(SESSION))

            joinerRoom.roster.first { it.isNotEmpty() }

            assertEquals(hostRoom.roomId.value, assertNotNull(joinerRoom.roomId.value, "joiner must be admitted"))

            joinerRoom.leave()
            hostRoom.leave()
        }

    private companion object {
        /** One stable device identity — [FakeLoom] derives `selfId` from the session name. */
        const val DEVICE = "device-7"

        /** A session name for the real-handshake tests over [InMemoryLoom]. */
        const val SESSION = "s"

        /**
         * Both rooms are minted at the same instant, so only the counter can separate them.
         *
         * **Non-zero on purpose.** At epoch 0 the timestamp segment renders as `"0"`, so replacing
         * `clock().toEpochMilliseconds()` with a literal `0` would be undetectable — the fixture
         * would have picked the one value at which the clock cannot be told from a constant.
         */
        val FROZEN: Instant = Instant.fromEpochMilliseconds(1_762_000_000_000L)
    }
}
