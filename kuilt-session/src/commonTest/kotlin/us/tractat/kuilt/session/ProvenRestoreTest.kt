@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.admit.ReturnKind
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A joiner that proves the key a held seat was admitted under gets that seat back (#3159, Task 1.5).
 *
 * The host decides at **Proof** time, under the room lock, from the key → seat table:
 * - the same id under the same key restores the same membership — `MembershipEvent.Resumed`, the
 *   Welcome burst re-sent to the joiner marked [ReturnKind.Restored], nothing new told to bystanders
 *   (bar the `Unpaused` a partitioned seat's recovery always sent);
 * - a partitioned seat is restored through the window controller's `tryResume`, a connected one
 *   without it;
 * - the same id under a **different** key is refused `KeyConflict` against a connected seat, and
 *   against a partitioned one releases the seat and decides the key again (Decided 18, 23, 30).
 *
 * Each test asserts its rig fired: the seat's level at the moment the Hello arrived, the challenge
 * the Hello drew, and how many times — and with what — the controller was asked. Outcomes are read
 * from the host's events, its roster and the frames it sent, each on its own.
 */
class ProvenRestoreTest {
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")
    private val dave = PeerId("dave")

    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)
    private val daveKey: DeviceKey = fakeDeviceKey(3)

    /** Bob's device after its key store was cleared: a new key, the same durable peer id. */
    private val bobReinstalled: DeviceKey = fakeDeviceKey(4)

    private fun List<MembershipEvent>.render() = joinToString { it.describe() }

    // ── Same id, same key ────────────────────────────────────────────────────

    /**
     * Bob's link goes quiet, the host holds his seat, and the healed link says Hello again under the
     * same key. Driven by raw frames (Amendment A § 1.5): until 1.7 a real joiner's reconnect still
     * sends `Resume`, which would pass this through the old path.
     */
    @Test
    fun healedLinkSameIdRestoresWithResumed() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        val toBob = host.sentTo(bob).size
        val toCarol = host.sentTo(carol).size
        host.admit(bob, bobKey, nonce = 3)

        val edges = host.events.edgesSince(mark)
        val burst = host.sentTo(bob).drop(toBob)
        val toCarolSince = host.sentTo(carol).drop(toCarol).filter { it.names(bob) }
        assertAll(
            { assertEquals(listOf(bob), host.hold.calls.map { it.peerId }, "rig: the partitioned seat went through tryResume") },
            { assertEquals(listOf(bobKey.id), host.hold.calls.map { it.key }, "the resume carries the proven key") },
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), host.hold.verdicts) },
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(bob)), edges, "one Resumed, no Left or Joined: ${edges.render()}") },
            { assertEquals(setOf(bob, carol), host.room.roster.value.map { it.id }.toSet()) },
            { assertEquals(bobKey.id, host.member(bob)?.key) },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness, "the restored seat is connected again") },
            { assertEquals(listOf(ReturnKind.Restored), burst.welcomesFor(bob).map { it.returnKind }, "the direct Welcome marks the restore") },
            { assertEquals(1, burst.welcomesFor(carol).size, "the burst re-sends each existing member") },
            { assertEquals(1, burst.welcomesFor(KEY_SEAT_HOST).size, "and the host's own intro") },
            {
                assertEquals(
                    listOf<AdmitMessage>(AdmitMessage.Unpaused(bob.value)),
                    toCarolSince,
                    "a bystander hears only the Unpaused that ends the hold (Amendment A § 1.5), never a Welcome or Farewell",
                )
            },
            { assertTrue(host.rejectsTo(bob).isEmpty()) },
        )
        host.room.leave()
    }

    /**
     * The process died — no Goodbye — and a new room over the same key store, on the same fabric id,
     * comes back inside the window. Real rooms on both sides: the friction bar is that the rebuilt
     * room is a plain `join`, with no token and no `resume` call.
     */
    @Test
    fun processRebuiltWithSameIdInsideWindowRestores() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob, carol))
        val hold = ObservedHold()
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, hold = hold)
        val hostLog = EventLog(host, backgroundScope)
        val bobStore = fakeKeyStore(1)
        val firstProcess = processScope()
        boardRoom(board, bob, bobStore, SessionRole.Joiner, scope = firstProcess)
        val carolRoom = boardRoom(board, carol, fakeKeyStore(2), SessionRole.Joiner)
        val carolLog = EventLog(carolRoom, backgroundScope)
        testScheduler.runCurrent()
        assertEquals(setOf(bob, carol), host.roster.value.map { it.id }.toSet(), "rig: both admitted")

        firstProcess.cancel()
        board.unplug(bob)
        testScheduler.advanceTimeBy(PARTITION_DETECTED)
        testScheduler.runCurrent()
        assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == bob }.liveness, "rig: the host holds the dead process's seat")

        val hostMark = hostLog.mark()
        val carolMark = carolLog.mark()
        val toBob = board.sent(from = KEY_SEAT_HOST, to = bob).size
        val rebuilt = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()

        val bobsKey = bobStore.deviceKey().id
        val hostEdges = hostLog.edgesSince(hostMark)
        val carolEdges = carolLog.edgesSince(carolMark).filter { it !is MembershipEvent.Resumed }
        assertAll(
            { assertEquals(listOf(bob), hold.calls.map { it.peerId }, "rig: the held seat was restored through tryResume") },
            { assertEquals(listOf(bobsKey), hold.calls.map { it.key }) },
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), hold.verdicts) },
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(bob)), hostEdges, "host: ${hostEdges.render()}") },
            { assertTrue(carolEdges.isEmpty(), "a bystander sees no Left or Joined: ${carolEdges.render()}") },
            { assertEquals(setOf(bob, carol), host.roster.value.map { it.id }.toSet()) },
            {
                assertEquals(
                    listOf(ReturnKind.Restored),
                    board.sent(from = KEY_SEAT_HOST, to = bob).drop(toBob).welcomesFor(bob).map { it.returnKind },
                )
            },
            {
                assertEquals(
                    setOf(KEY_SEAT_HOST, carol),
                    rebuilt.roster.value.map { it.id }.toSet(),
                    "the rebuilt process holds the room again, from the re-sent burst",
                )
            },
        )
        rebuilt.leave()
        carolRoom.leave()
        host.leave()
    }

    /**
     * Bob says Hello again while his seat is still connected — a restart faster than the host's
     * timeout. The seat is restored without asking the controller, which would answer
     * `WindowNotYetOpen` for a window never opened (Amendment A § 1.5). The host never saw Bob go, so
     * it announces nothing (#3215: `Resumed` is once per partition), and its Welcome names the
     * membership Bob was admitted under, which is how Bob tells this from a fresh admission.
     */
    @Test
    fun stillConnectedSeatRestoresAndSupersedesOldLink() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        assertEquals(Liveness.Connected, host.member(bob)?.liveness, "rig: Bob's seat is connected when he says Hello again")
        val admittedAs = host.sentTo(bob).welcomesFor(bob).single().membership

        val mark = host.events.mark()
        val toBob = host.sentTo(bob).size
        val toCarol = host.sentTo(carol).size
        host.admit(bob, bobKey, nonce = 3)

        val edges = host.events.edgesSince(mark)
        val restored = host.sentTo(bob).drop(toBob).welcomesFor(bob)
        assertAll(
            { assertTrue(host.hold.calls.isEmpty(), "a connected seat is restored without tryResume") },
            { assertEquals(emptyList(), edges, "a connected seat's restore is silent: ${edges.render()}") },
            { assertEquals(listOf(ReturnKind.Restored), restored.map { it.returnKind }) },
            { assertNotNull(admittedAs, "rig: the admission named a membership") },
            { assertEquals(listOf(admittedAs), restored.map { it.membership }, "the restore names the same membership") },
            { assertTrue(host.sentTo(carol).drop(toCarol).none { it.names(bob) }, "nothing at all reaches a bystander") },
            { assertEquals(bobKey.id, host.member(bob)?.key) },
            { assertEquals(2, host.room.roster.value.size, "still one seat for Bob") },
        )
        host.room.leave()
    }

    // ── Resumed fires once per partition (Amendment E, #3215) ────────────────

    /**
     * Bob's held seat comes back, and a retry Hello reaches it: the joiner's attempt timed out before
     * the host's `Welcome` arrived, so it said Hello again. The retry is challenged and its proof
     * restores the connected seat, and the joiner gets its `Welcome` again. The partition ended once,
     * so the host emits one `Resumed`.
     */
    @Test
    fun retryHelloToASeatRestoredForThisReturnEmitsNoSecondResumed() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        val toBob = host.sentTo(bob).size
        host.admit(bob, bobKey, nonce = 3)
        assertEquals(Liveness.Connected, host.member(bob)?.liveness, "rig: the first proof restored the seat")
        host.admit(bob, bobKey, nonce = 4)

        val edges = host.events.edgesSince(mark)
        assertAll(
            { assertEquals(listOf(bob), host.hold.calls.map { it.peerId }, "rig: only the first proof asked the controller") },
            {
                assertEquals(
                    listOf(ReturnKind.Restored, ReturnKind.Restored),
                    host.sentTo(bob).drop(toBob).welcomesFor(bob).map { it.returnKind },
                    "rig: the retry's proof restored the seat and was answered",
                )
            },
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(bob)), edges, "one Resumed per partition: ${edges.render()}") },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness) },
        )
        host.room.leave()
    }

    /**
     * The same, for a seat the host never partitioned: a restart faster than the host's timeout, and
     * then a retry. The host saw no partition, so neither restore announces anything (#3215). The
     * limit Amendment D accepted — a second quick restart that the host could not tell from a retry —
     * dissolves: the host says nothing, and the joiner reads its restore from the membership.
     */
    @Test
    fun aConnectedSeatRestoredTwiceEmitsNoResumed() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)

        val mark = host.events.mark()
        val toBob = host.sentTo(bob).size
        host.admit(bob, bobKey, nonce = 3)
        host.admit(bob, bobKey, nonce = 4)

        val edges = host.events.edgesSince(mark)
        assertAll(
            { assertTrue(host.hold.calls.isEmpty(), "rig: the seat was connected throughout") },
            {
                assertEquals(
                    listOf(ReturnKind.Restored, ReturnKind.Restored),
                    host.sentTo(bob).drop(toBob).welcomesFor(bob).map { it.returnKind },
                    "rig: both proofs restored the seat",
                )
            },
            { assertEquals(emptyList(), edges, "host: ${edges.render()}") },
        )
        host.room.leave()
    }

    /**
     * The converse: each partition has its own recovery. Bob's seat is restored, goes quiet a second
     * time, and comes back. That is a new partition ended, with its own `Resumed`.
     */
    @Test
    fun aSeatLostAgainAfterItsRestoreHasANewReturnWithItsOwnResumed() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        host.admit(bob, bobKey, nonce = 3)
        host.admit(bob, bobKey, nonce = 4)
        host.partition(bob, alive = setOf(carol))
        host.admit(bob, bobKey, nonce = 5)

        val edges = host.events.edgesSince(mark)
        assertAll(
            { assertEquals(listOf(bob, bob), host.hold.calls.map { it.peerId }, "rig: each partition's restore asked the controller") },
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success, ResumeResult.Success), host.hold.verdicts) },
            {
                assertEquals(
                    listOf<MembershipEvent>(MembershipEvent.Resumed(bob), MembershipEvent.Resumed(bob)),
                    edges,
                    "one Resumed for each partition: ${edges.render()}",
                )
            },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness) },
        )
        host.room.leave()
    }

    /**
     * A membership id is the membership's (#3215): Bob's seat is restored and keeps its id, he says
     * Goodbye, and he is admitted again as a new member under a new id, which his next restore keeps.
     * Neither restore ends a partition, so neither is announced.
     */
    @Test
    fun aMemberAdmittedAgainAfterLeavingHasANewMembership() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)

        val mark = host.events.mark()
        host.admit(bob, bobKey, nonce = 3)
        host.say(bob, AdmitMessage.Goodbye)
        assertEquals(null, host.member(bob), "rig: Bob's Goodbye ended his membership")
        host.admit(bob, bobKey, nonce = 4)
        host.admit(bob, bobKey, nonce = 5)

        val edges = host.events.edgesSince(mark).map { it.describe() }
        val memberships = host.sentTo(bob).welcomesFor(bob).map { it.membership }
        assertAll(
            { assertEquals(listOf("Left(bob, ${LeaveReason.Normal})", "Joined(bob)"), edges, "no Resumed for a connected seat") },
            { assertEquals(4, memberships.size, "rig: four Welcomes to Bob: $memberships") },
            { assertTrue(memberships.all { it != null }, "every direct Welcome names a membership: $memberships") },
            { assertEquals(memberships[0], memberships[1], "a restore keeps the membership") },
            { assertNotEquals(memberships[1], memberships[2], "a new admission is a new membership") },
            { assertEquals(memberships[2], memberships[3], "and its restore keeps that one") },
        )
        host.room.leave()
    }

    // ── Same id, different key ───────────────────────────────────────────────

    /**
     * Eve takes Bob's fabric id (self-chosen on a star) and proves her own key under it while Bob is
     * connected. Real rooms for Bob and Carol, so "the victim stays in every roster" is read off
     * every roster — including Bob's own, which the host's `Reject`, addressed to the id Eve claimed,
     * reaches on this fabric.
     */
    @Test
    fun bystanderClaimingALiveMembersIdIsRejectedKeyConflict() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob, carol))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host)
        val bobRoom = boardRoom(board, bob, fakeKeyStore(1), SessionRole.Joiner)
        val carolRoom = boardRoom(board, carol, fakeKeyStore(2), SessionRole.Joiner)
        val hostLog = EventLog(host, backgroundScope)
        val bobLog = EventLog(bobRoom, backgroundScope)
        testScheduler.runCurrent()
        val eveKey = fakeDeviceKey(5)
        val bobsKey = fakeDidKey(1)

        val hostMark = hostLog.mark()
        val bobMark = bobLog.mark()
        val challengesBefore = board.sent(from = KEY_SEAT_HOST, to = bob).filterIsInstance<AdmitMessage.Challenge>().size
        board.inject(
            from = bob,
            to = KEY_SEAT_HOST,
            message = AdmitMessage.Hello(
                displayName = "Eve",
                sessionId = "session-eve",
                protocolVersion = ProtocolVersion.CURRENT,
                key = eveKey.id,
                joinerNonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { 9 },
            ),
        )
        testScheduler.runCurrent()
        val challenge = assertNotNull(
            board.sent(from = KEY_SEAT_HOST, to = bob).filterIsInstance<AdmitMessage.Challenge>()
                .drop(challengesBefore).singleOrNull()?.let { Challenge.decode(it.statement) },
            "rig: Eve's Hello drew a challenge",
        )
        board.inject(
            from = bob,
            to = KEY_SEAT_HOST,
            message = AdmitMessage.Proof(
                AdmissionProof.sign(eveKey, challenge.roomId, challenge.hostKey, KEY_SEAT_HOST.value, bob.value, null, challenge.hostNonce).wire,
            ),
        )
        testScheduler.runCurrent()

        val rejects = board.sent(from = KEY_SEAT_HOST, to = bob).filterIsInstance<AdmitMessage.Reject>()
        assertAll(
            { assertEquals(listOf(RejectCode.KeyConflict), rejects.map { it.code }, "Eve's valid proof is refused KeyConflict") },
            { assertTrue(!RejectCode.KeyConflict.retryable, "and terminally") },
            { assertEquals(bobsKey, host.roster.value.single { it.id == bob }.key, "the host keeps Bob under his own key") },
            { assertTrue(hostLog.edgesSince(hostMark).isEmpty(), "host: ${hostLog.edgesSince(hostMark).render()}") },
            { assertTrue(carolRoom.roster.value.any { it.id == bob }, "Bob stays in a bystander's roster") },
            { assertTrue(bobRoom.roster.value.any { it.id == KEY_SEAT_HOST }, "Bob's own room survives the Reject sent to his id") },
            {
                val bobSince = bobLog.since(bobMark)
                assertTrue(
                    bobSince.none { it is MembershipEvent.AdmissionFailed || it is MembershipEvent.HostLost || it is MembershipEvent.Left },
                    "Bob's room ends nothing: ${bobSince.render()}",
                )
            },
        )
        carolRoom.leave()
        bobRoom.leave()
        host.leave()
    }

    /**
     * Decided 18: Bob's key store was cleared, his peer id is durable, and his old seat is
     * partitioned. The seat is released and the new key is admitted as a new member — no prompt, no
     * refusal. The old seat's window must not then evict the new membership when it runs out.
     */
    @Test
    fun clearedKeyStoreOnADurableIdIsAdmittedAfresh() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        val toCarol = host.sentTo(carol).size
        host.admit(bob, bobReinstalled, nonce = 3)
        val edges = host.events.edgesSince(mark)
        val leftAt = edges.indexOfFirst { it is MembershipEvent.Left && it.peerId == bob }
        val joinedAt = edges.indexOfFirst { it is MembershipEvent.Joined && it.member.id == bob }
        val aboutBob = host.sentTo(carol).drop(toCarol).filter { it.names(bob) }

        // The old seat's window would have closed by now; Bob keeps talking, so only a stale timer could evict him.
        host.advanceKeeping(alive = setOf(bob, carol), KEY_SEAT_HEARTBEAT.reconnectWindow * 2)

        assertAll(
            { assertTrue(host.rejectsTo(bob).isEmpty(), "a partitioned seat is no conflict (Decided 18)") },
            { assertTrue(host.hold.calls.isEmpty(), "rig: a different key never reaches the controller") },
            { assertTrue(leftAt >= 0 && joinedAt > leftAt, "the old seat leaves, then the new one joins: ${edges.render()}") },
            { assertEquals(LeaveReason.PartitionExpired, (edges.getOrNull(leftAt) as? MembershipEvent.Left)?.reason, "its hold ended unrestored") },
            { assertEquals(bobReinstalled.id, (edges.getOrNull(joinedAt) as? MembershipEvent.Joined)?.member?.key) },
            { assertTrue(edges.none { it is MembershipEvent.Resumed }) },
            {
                val farewell = aboutBob.indexOfFirst { it is AdmitMessage.Farewell }
                val welcome = aboutBob.indexOfFirst { it is AdmitMessage.Welcome }
                assertTrue(farewell >= 0 && welcome > farewell, "a bystander hears Farewell(bob) before Welcome(bob): $aboutBob")
            },
            { assertEquals(bobReinstalled.id, host.member(bob)?.key, "Bob is still a member, under his new key, after the old window ran out") },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness) },
        )
        host.room.leave()
    }

    /**
     * Amendment B § 1.5: when Decided 18 releases Bob's partitioned seat, the key that claimed it is
     * decided again under Decided 30 — not refused under Decided 23's retired "one key, two seats".
     * Here that key is Dave's, and Dave is connected, so it takes an additional seat under Bob's id.
     */
    @Test
    fun releasedSeatsClaimantIsDecidedAgainUnderDecided30() = runTest {
        val host = rawHost(joiners = setOf(bob, carol, dave))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.admit(dave, daveKey, nonce = 3)
        host.partition(bob, alive = setOf(carol, dave))

        val mark = host.events.mark()
        host.admit(bob, daveKey, nonce = 4)
        val edges = host.events.edgesSince(mark)

        assertAll(
            { assertTrue(host.rejectsTo(bob).isEmpty(), "no terminal conflict: ${host.rejectsTo(bob).map { it.code }}") },
            {
                assertEquals(
                    listOf("Left(bob, ${LeaveReason.PartitionExpired})", "Joined(bob)"),
                    edges.map { it.describe() },
                    "Bob's seat is released, then a new one is admitted under his id — not re-keyed in place",
                )
            },
            { assertEquals(daveKey.id, host.member(bob)?.key, "Bob's id now holds a seat under Dave's key") },
            { assertEquals(daveKey.id, host.member(dave)?.key, "and Dave keeps his own") },
            { assertEquals(Liveness.Connected, host.member(dave)?.liveness) },
            { assertTrue(edges.none { it is MembershipEvent.Left && it.peerId == dave }, "Dave's connected seat is never superseded") },
            // No seat but the released one leaves: nothing is superseded (a 1.6 reason, not yet a type).
            { assertTrue(edges.none { it is MembershipEvent.Left && it.peerId != bob }, edges.render()) },
            { assertTrue(host.hold.calls.isEmpty()) },
        )
        host.room.leave()
    }

    /**
     * The re-decision after a release is a real one, not a new membership by default. Dave's key
     * claims Bob's partitioned id while Dave's own seat is partitioned too: Bob's seat is released,
     * and deciding Dave's key again without it finds Dave's held seat, so Bob's id takes that seat
     * over. The two room tests above cannot tell this from "admit a new member", since an additional
     * seat and an ordinary admission both admit one.
     */
    @Test
    fun releasedSeatsClaimantTakesItsOwnPartitionedSeatOver() = runTest {
        val host = rawHost(joiners = setOf(bob, carol, dave))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.admit(dave, daveKey, nonce = 3)
        host.partition(bob, alive = setOf(carol, dave))
        host.partition(dave, alive = setOf(carol))
        val bobStillHeld = host.member(bob)?.liveness

        val mark = host.events.mark()
        host.admit(bob, daveKey, nonce = 4)
        val edges = host.events.edgesSince(mark)

        assertAll(
            { assertIs<Liveness.Partitioned>(bobStillHeld, "rig: Bob's seat was still held when Dave's key claimed it") },
            { assertTrue(host.rejectsTo(bob).isEmpty(), "no terminal conflict: ${host.rejectsTo(bob).map { it.code }}") },
            { assertEquals(listOf(dave), host.hold.calls.map { it.peerId }, "the re-decision asked about Dave's seat") },
            {
                assertEquals(
                    listOf(
                        "Left(bob, ${LeaveReason.PartitionExpired})",
                        "Left(dave, ${LeaveReason.Superseded(by = bob)})",
                        "Joined(bob)",
                    ),
                    edges.map { it.describe() },
                    "Bob's seat is released, then Dave's is taken over under Bob's id",
                )
            },
            { assertEquals(daveKey.id, host.member(bob)?.key) },
            { assertEquals(null, host.member(dave), "Dave's old id holds no seat") },
        )
        host.room.leave()
    }

    // ── The decision is made at Proof time ───────────────────────────────────

    /**
     * The controller refuses the restore — a hold policy that has given the seat up — so the seat is
     * treated as gone and the key is decided again without it: ordinary admission, as a new member.
     *
     * Only the `Ordinary` arm is here. The `Returning` arm (a valid grant) needs the host to verify a
     * presented grant, which lands in Task 2.6 with the per-key revocation check.
     */
    @Test
    fun windowExpiredDecidesAgainWithoutTheSeat() = runTest {
        val host = rawHost(
            joiners = setOf(bob, carol),
            hold = ObservedHold(verdict = { ResumeResult.WindowClosed }),
        )
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        val toBob = host.sentTo(bob).size
        host.admit(bob, bobKey, nonce = 3)
        val edges = host.events.edgesSince(mark)
        val leftAt = edges.indexOfFirst { it is MembershipEvent.Left && it.peerId == bob }
        val joinedAt = edges.indexOfFirst { it is MembershipEvent.Joined && it.member.id == bob }
        val direct = host.sentTo(bob).drop(toBob).welcomesFor(bob)

        // The refusing policy left the real controller's window armed underneath; it must not evict the new member.
        host.advanceKeeping(alive = setOf(bob, carol), KEY_SEAT_HEARTBEAT.reconnectWindow * 2)

        assertAll(
            { assertEquals(listOf(bob), host.hold.calls.map { it.peerId }, "rig: the restore was attempted") },
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.WindowClosed), host.hold.verdicts, "rig: and refused") },
            { assertTrue(edges.none { it is MembershipEvent.Resumed }, "a refused restore is not a Resumed") },
            { assertTrue(leftAt >= 0 && joinedAt > leftAt, "the held seat goes, then a new membership arrives: ${edges.render()}") },
            { assertEquals(listOf<ReturnKind?>(null), direct.map { it.returnKind }, "an ordinary admission is not marked") },
            { assertTrue(host.rejectsTo(bob).isEmpty()) },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness, "still a member after the old window would have run out") },
        )
        host.room.leave()
    }

    /**
     * The host has marked Bob's seat partitioned but its window controller has not opened the window
     * yet — the default controller opens it on a launched coroutine, and a hold policy may lag too —
     * so `tryResume` answers `WindowNotYetOpen`. That is a seat still held, not a seat gone: it is
     * restored, not released (controller reading on #3159).
     */
    @Test
    fun windowNotYetOpenAtProofTimeRestoresTheHeldSeat() = runTest {
        val host = rawHost(
            joiners = setOf(bob, carol),
            hold = ObservedHold(verdict = { ResumeResult.WindowNotYetOpen }),
        )
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        val toBob = host.sentTo(bob).size
        val toCarol = host.sentTo(carol).size
        host.admit(bob, bobKey, nonce = 3)
        val edges = host.events.edgesSince(mark)

        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.WindowNotYetOpen), host.hold.verdicts, "rig: the window was not open yet") },
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(bob)), edges, "restored, not released: ${edges.render()}") },
            { assertEquals(listOf(ReturnKind.Restored), host.sentTo(bob).drop(toBob).welcomesFor(bob).map { it.returnKind }) },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness) },
            {
                assertEquals(
                    listOf<AdmitMessage>(AdmitMessage.Unpaused(bob.value)),
                    host.sentTo(carol).drop(toCarol).filter { it.names(bob) },
                    "a bystander hears the hold end, and nothing else",
                )
            },
            { assertTrue(host.rejectsTo(bob).isEmpty()) },
        )
        host.room.leave()
    }

    /**
     * The hold policy is consumer code, and this one throws. The joiner is refused retryably, its
     * seat stays held, and the host keeps running: on Kotlin/Native an exception escaping the admit
     * coroutine would abort the process, and under `runTest` it fails the test.
     */
    @Test
    fun aThrowingHoldPolicyRefusesRetryablyAndKeepsTheSeat() = runTest {
        val host = rawHost(
            joiners = setOf(bob, carol),
            hold = ObservedHold(verdict = { error("the hold policy broke") }),
        )
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        host.admit(bob, bobKey, nonce = 3)
        val edges = host.events.edgesSince(mark)

        assertAll(
            { assertEquals(listOf(bob), host.hold.calls.map { it.peerId }, "rig: the policy was asked") },
            { assertTrue(host.hold.verdicts.isEmpty(), "rig: and threw instead of answering") },
            { assertEquals(listOf(RejectCode.HostFailed), host.rejectsTo(bob).map { it.code }) },
            { assertTrue(RejectCode.HostFailed.retryable, "so the joiner tries again") },
            { assertTrue(edges.isEmpty(), "the seat is neither restored nor released: ${edges.render()}") },
            { assertIs<Liveness.Partitioned>(host.member(bob)?.liveness, "the seat is still held") },
        )
        host.room.leave()
    }

    /**
     * Bob says Goodbye while the host is still asking the controller about his seat. The decision is
     * taken again once the controller answers, and by then Bob's challenge has been dropped: nobody is
     * seated, so no ghost of Bob comes back after his Goodbye. Read only at the first decision, the
     * seat is gone, the key would be decided again, and Bob admitted as a new member.
     */
    @Test
    fun goodbyeWhileTheWindowIsAskedSeatsNobody() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = rawHost(
            joiners = setOf(bob, carol),
            hold = ObservedHold(verdict = { ResumeResult.WindowClosed }, gate = gate),
        )
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        host.admit(bob, bobKey, nonce = 3)
        assertEquals(listOf(bob), host.hold.calls.map { it.peerId }, "rig: the host is mid-ask")
        assertTrue(host.hold.verdicts.isEmpty(), "rig: and the controller has not answered")

        host.say(bob, AdmitMessage.Goodbye)
        val mark = host.events.mark()
        gate.complete(Unit)
        testScheduler.runCurrent()
        val edges = host.events.edgesSince(mark)

        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.WindowClosed), host.hold.verdicts, "rig: the answer came after the Goodbye") },
            { assertTrue(edges.isEmpty(), "nothing after the Goodbye's own Left: ${edges.render()}") },
            { assertEquals(null, host.member(bob), "Bob stays gone") },
            { assertTrue(host.rejectsTo(bob).isEmpty()) },
        )
        host.room.leave()
    }

    /**
     * The window expires **between** the Challenge and the Proof. Read at Challenge time, the seat
     * would still be held and the host would ask the controller to restore it; read at Proof time,
     * under the lock, there is no seat and the joiner is admitted as a new member.
     */
    @Test
    fun seatChecksRunUnderTheLockAtProofTime() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        host.say(bob, host.hello("bob", bobKey.id, nonce = 3))
        val challenge = host.challengesTo(bob).last()
        assertIs<Liveness.Partitioned>(host.member(bob)?.liveness, "rig: the seat was held when the challenge went out")
        assertEquals(2, host.challengesTo(bob).size, "rig: the second Hello drew its own challenge")

        val mark = host.events.mark()
        assertNotNull(host.hold.real).expire(bob, testScheduler.currentTime)
        testScheduler.runCurrent()
        val expired = host.events.since(mark)
        assertTrue(
            expired.any { it is MembershipEvent.Left && it.peerId == bob && it.reason == LeaveReason.PartitionExpired },
            "rig: the window expired and the seat went before the proof: ${expired.render()}",
        )

        val toBob = host.sentTo(bob).size
        val proofMark = host.events.mark()
        host.say(bob, host.proof(challenge, bobKey, bob))
        val edges = host.events.edgesSince(proofMark)

        assertAll(
            { assertTrue(host.hold.calls.isEmpty(), "no seat at Proof time, so nothing to ask the controller") },
            { assertEquals(listOf(bob), edges.filterIsInstance<MembershipEvent.Joined>().map { it.member.id }, edges.render()) },
            { assertTrue(edges.none { it is MembershipEvent.Resumed }) },
            { assertEquals(listOf<ReturnKind?>(null), host.sentTo(bob).drop(toBob).welcomesFor(bob).map { it.returnKind }) },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness) },
        )
        host.room.leave()
    }

    /**
     * Bob's old link heals while the host is still asking about his seat, and the hold policy then
     * refuses. The refusal was about a partition that is over: the seat is connected again, so it is
     * restored as a connected seat would be, never released (Decided 18, 30). Acted on as decided
     * before the heal, Bob would be evicted and admitted afresh.
     */
    @Test
    fun aSeatThatHealsWhileTheWindowIsAskedIsRestoredNotReleased() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = rawHost(
            joiners = setOf(bob, carol),
            hold = ObservedHold(verdict = { ResumeResult.WindowClosed }, gate = gate),
        )
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        val toBob = host.sentTo(bob).size
        val toCarol = host.sentTo(carol).size
        host.admit(bob, bobKey, nonce = 3)
        assertEquals(listOf(bob), host.hold.calls.map { it.peerId }, "rig: the host is mid-ask")
        assertTrue(host.hold.verdicts.isEmpty(), "rig: and the controller has not answered")

        host.advanceKeeping(alive = setOf(bob, carol), KEY_SEAT_HEARTBEAT.interval)
        assertEquals(Liveness.Connected, host.member(bob)?.liveness, "rig: the seat healed during the ask")

        gate.complete(Unit)
        testScheduler.runCurrent()
        val edges = host.events.edgesSince(mark)

        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.WindowClosed), host.hold.verdicts, "rig: the refusal came after the heal") },
            { assertTrue(edges.none { it is MembershipEvent.Left || it is MembershipEvent.Joined }, "restored, not released: ${edges.render()}") },
            { assertTrue(edges.none { it is MembershipEvent.Resumed }, "the heal ended the partition, so the restore is silent: ${edges.render()}") },
            { assertEquals(listOf(ReturnKind.Restored), host.sentTo(bob).drop(toBob).welcomesFor(bob).map { it.returnKind }) },
            {
                assertTrue(
                    host.sentTo(carol).drop(toCarol).none { it is AdmitMessage.Farewell || it is AdmitMessage.Welcome },
                    "a bystander is told of no departure and no arrival",
                )
            },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness) },
            { assertTrue(host.rejectsTo(bob).isEmpty()) },
        )
        host.room.leave()
    }

    /**
     * Bob's seat was restored once, spending its first window, and is partitioned again. The window
     * controller has not opened the second window yet, so it still holds the first, spent one and
     * answers `WindowClosed`. That verdict is about the previous episode: until the room has seen
     * this episode's `WindowOpened`, a refusal means "not open yet", and the seat is restored. Read as
     * a refusal, Bob would be released and admitted afresh.
     *
     * The lagging controller stands in for the default one, which opens the window on a launched
     * coroutine: the same gap, held open long enough for a Proof to land in it.
     */
    @Test
    fun aRefusalFromBeforeThisEpisodesWindowOpenedRestores() = runTest {
        val hold = ObservedHold()
        val host = rawHost(joiners = setOf(bob, carol), hold = hold)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))
        host.admit(bob, bobKey, nonce = 3)
        assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), hold.verdicts, "rig: the first window was spent")

        val lag = CompletableDeferred<Unit>()
        hold.lagOpening = lag
        host.partition(bob, alive = setOf(carol))
        assertEquals(1, hold.opened.size, "rig: the controller has not been told of the second partition")

        val mark = host.events.mark()
        val toBob = host.sentTo(bob).size
        host.admit(bob, bobKey, nonce = 4)
        val edges = host.events.edgesSince(mark)
        val direct = host.sentTo(bob).drop(toBob).welcomesFor(bob)

        lag.complete(Unit)
        host.advanceKeeping(alive = setOf(bob, carol), KEY_SEAT_HEARTBEAT.reconnectWindow * 2)

        assertAll(
            {
                assertEquals(
                    listOf<ResumeResult.HostVerdict>(ResumeResult.Success, ResumeResult.WindowClosed),
                    hold.verdicts,
                    "rig: the second ask read the first window's spent state",
                )
            },
            { assertEquals(listOf<MembershipEvent>(MembershipEvent.Resumed(bob)), edges, "restored, not released: ${edges.render()}") },
            { assertEquals(listOf(ReturnKind.Restored), direct.map { it.returnKind }) },
            { assertEquals(2, hold.opened.size, "rig: the second window did open, late") },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness, "still a member after the late window would have run out") },
            { assertTrue(host.rejectsTo(bob).isEmpty()) },
        )
        host.room.leave()
    }

    /**
     * The window expires while the host is asking about Bob's seat, so the controller answers
     * `WindowClosed` for a seat the expiry has already taken, and Bob is admitted afresh. With the
     * default controller this is the ordinary way the two meet: the ask is refused exactly when the
     * window has closed. The new Bob must survive it, on the host and at a bystander: one `Left`, one
     * `Joined`, the bystander told `Farewell` before `Welcome`, and a detector watching the new seat.
     *
     * On one thread the two cannot interleave inside either side's decision, since neither suspends
     * between deciding and acting; this pins the outcome both orders must reach.
     */
    @Test
    fun anExpiryDuringTheAskLeavesTheReadmittedMemberSeated() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = rawHost(joiners = setOf(bob, carol), hold = ObservedHold(gate = gate))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        val toCarol = host.sentTo(carol).size
        host.admit(bob, bobKey, nonce = 3)
        assertTrue(host.hold.verdicts.isEmpty(), "rig: the host is mid-ask")
        assertNotNull(host.hold.real).expire(bob, testScheduler.currentTime)
        testScheduler.runCurrent()
        assertEquals(null, host.member(bob), "rig: the expiry took the seat during the ask")
        assertFalse((host.room as SeamRoom).hasDetector(bob), "the evicted seat's detector went with it")

        gate.complete(Unit)
        testScheduler.runCurrent()
        val edges = host.events.edgesSince(mark)
        val toCarolSince = host.sentTo(carol).drop(toCarol).filter { it.names(bob) }

        host.advanceKeeping(alive = setOf(bob, carol), KEY_SEAT_HEARTBEAT.reconnectWindow * 2)

        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.WindowClosed), host.hold.verdicts, "rig: the ask was refused") },
            {
                assertEquals(
                    listOf("Left(bob, ${LeaveReason.PartitionExpired})", "Joined(bob)"),
                    edges.map { it.describe() },
                    "one Left, one Joined",
                )
            },
            {
                assertEquals(
                    listOf("Farewell", "Welcome"),
                    toCarolSince.map { it::class.simpleName },
                    "the bystander hears the departure, then the arrival: $toCarolSince",
                )
            },
            { assertEquals(Liveness.Connected, host.member(bob)?.liveness, "the new Bob is still seated after the old window") },
            { assertTrue((host.room as SeamRoom).hasDetector(bob), "and watched by a detector") },
        )
        host.room.leave()
    }

    // ── The host's own key (Amendment A § 1.5, Amendment B) ──────────────────

    /**
     * A joiner on the host's own device key is admitted to a seat of its own (Amendment B), and that
     * seat is then restored like any other. The host-key check keeps the **host's** seat out of the
     * table; it must not send every Proof under the host's key to additional-seat admission, or this
     * tab could never get its own seat back.
     */
    @Test
    fun hostKeyJoinerSayingHelloAgainRestoresItsOwnSeat() = runTest {
        val tab = PeerId("tab")
        val host = rawHost(joiners = setOf(tab))
        val sameDeviceAsHost = fakeDeviceKey(HOST_DEVICE)
        host.admit(tab, sameDeviceAsHost, nonce = 1)
        assertEquals(host.hostKey, host.member(tab)?.key, "rig: the tab holds a seat under the host's key")

        val mark = host.events.mark()
        val toTab = host.sentTo(tab).size
        host.admit(tab, sameDeviceAsHost, nonce = 2)
        val edges = host.events.edgesSince(mark)

        assertAll(
            { assertEquals(emptyList(), edges, "a connected seat's restore is silent: ${edges.render()}") },
            { assertEquals(listOf(ReturnKind.Restored), host.sentTo(tab).drop(toTab).welcomesFor(tab).map { it.returnKind }) },
            { assertEquals(1, host.room.roster.value.size) },
        )
        host.room.leave()
    }
}
