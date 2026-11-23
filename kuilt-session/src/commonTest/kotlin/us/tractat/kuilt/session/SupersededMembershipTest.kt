@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ReturnKind
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.test.FlakyLifecycleSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A known key under a new peer id (#3159, Task 1.6, as amended by Decided 30):
 * - restores that key's **partitioned** seat — the most recently connected, if there are several —
 *   and the old membership leaves with `LeaveReason.Superseded(by = new)`, one `Left` + `Joined`
 *   pair, `Farewell(old, supersededBy = new)` before `Welcome(new)` on every bystander's lane;
 * - and otherwise, when every seat of the key is **connected** (or the key is the host's own), takes
 *   an **additional seat**: live tabs coexist and nothing is superseded.
 *
 * Real rooms on a [Switchboard], so every peer id is the test's and the superseded joiner's own
 * reaction is observable. One key store per device; where two rooms share one it is on purpose — two
 * processes, or two tabs, of one device.
 */
class SupersededMembershipTest {
    private val first = PeerId("bob-1")
    private val second = PeerId("bob-2")
    private val carol = PeerId("carol")

    private fun List<MembershipEvent>.render() = joinToString { it.describe() }

    private fun superseded(old: PeerId, by: PeerId) = MembershipEvent.Left(old, LeaveReason.Superseded(by))

    /**
     * Bob's process dies and comes back on the same key store under a fresh fabric id while the host
     * still holds the old seat. The host and a bystander each see exactly one `Left(Superseded)` then
     * `Joined`; nobody sees a `Resumed`, and no `ResumeAck` goes anywhere (Amendment A § 1.6).
     */
    @Test
    fun processRebuiltWithFreshIdInsideWindowRestoresUnderNewId() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, first, second, carol))
        val hold = ObservedHold()
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, hold = hold)
        val hostLog = EventLog(host, backgroundScope)
        val bobStore = fakeKeyStore(1)
        val firstProcess = processScope()
        boardRoom(board, first, bobStore, SessionRole.Joiner, scope = firstProcess, name = "Bob")
        val carolRoom = boardRoom(board, carol, fakeKeyStore(2), SessionRole.Joiner)
        val carolLog = EventLog(carolRoom, backgroundScope)
        testScheduler.runCurrent()

        firstProcess.cancel()
        board.unplug(first)
        testScheduler.advanceTimeBy(PARTITION_DETECTED)
        testScheduler.runCurrent()
        assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == first }.liveness, "rig: the old seat is held, partitioned")

        val hostMark = hostLog.mark()
        val carolMark = carolLog.mark()
        val toCarol = board.delivered(from = KEY_SEAT_HOST, to = carol).size
        // Renamed, so the identity the new seat inherits can be told from the one its Hello carries.
        val rebuilt = boardRoom(board, second, bobStore, SessionRole.Joiner, name = "Bob, rebuilt")
        testScheduler.runCurrent()
        val bobsKey = bobStore.deviceKey().id

        val left = superseded(first, by = second)
        val hostEdges = hostLog.edgesSince(hostMark)
        val carolEdges = carolLog.edgesSince(carolMark)
        val carolHeard = board.delivered(from = KEY_SEAT_HOST, to = carol).drop(toCarol).filter { it.names(first) || it.names(second) }
        assertAll(
            { assertEquals(listOf(first), hold.calls.map { it.peerId }, "rig: the old seat was restored through tryResume") },
            { assertEquals(listOf(bobsKey), hold.calls.map { it.key }) },
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), hold.verdicts) },
            { assertEquals(2, hostEdges.size, "host: ${hostEdges.render()}") },
            { assertEquals(left, hostEdges.getOrNull(0), "host: ${hostEdges.render()}") },
            { assertEquals(second, (hostEdges.getOrNull(1) as? MembershipEvent.Joined)?.member?.id, "host: ${hostEdges.render()}") },
            { assertEquals(2, carolEdges.size, "bystander: ${carolEdges.render()}") },
            { assertEquals(left, carolEdges.getOrNull(0), "bystander: ${carolEdges.render()}") },
            { assertEquals(second, (carolEdges.getOrNull(1) as? MembershipEvent.Joined)?.member?.id, "bystander: ${carolEdges.render()}") },
            {
                assertEquals(
                    listOf<AdmitMessage>(AdmitMessage.Farewell(first.value, supersededBy = second.value)),
                    carolHeard.filterIsInstance<AdmitMessage.Farewell>(),
                    "the bystander's Farewell names its successor",
                )
            },
            {
                val farewell = carolHeard.indexOfFirst { it is AdmitMessage.Farewell }
                val welcome = carolHeard.indexOfFirst { it is AdmitMessage.Welcome && it.assignedPeerId == second.value }
                assertTrue(farewell >= 0 && welcome > farewell, "Farewell(old) before Welcome(new) on the wire: $carolHeard")
            },
            {
                assertEquals(
                    1,
                    board.sent(from = KEY_SEAT_HOST, to = first).count { it is AdmitMessage.Farewell && it.supersededBy == second.value },
                    "the superseded peer is told directly (Decided 19), here into a dead link",
                )
            },
            { assertEquals(setOf(second, carol), host.roster.value.map { it.id }.toSet()) },
            { assertEquals(bobsKey, host.roster.value.single { it.id == second }.key, "the new seat inherits the key") },
            {
                assertEquals(
                    "Bob",
                    host.roster.value.single { it.id == second }.identity.displayName,
                    "the new seat inherits the old seat's identity, not its Hello's",
                )
            },
            { assertEquals(setOf(KEY_SEAT_HOST, carol), rebuilt.roster.value.map { it.id }.toSet()) },
        )
        rebuilt.leave()
        carolRoom.leave()
        host.leave()
    }

    /**
     * The bystander's lane is slow: the supersession's `Farewell` stalls in `sendTo` for half a second
     * of virtual time. The `Welcome(new)` raised right after it must still arrive second, as
     * `AdmitFanOutOrderingTest` pins for the other fan-outs (#1781) — so the bystander's events say
     * the old member left before the new one joined.
     */
    @Test
    fun bystanderOrderingHoldsUnderConcurrentFarewell() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, first, second, carol))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host)
        val bobStore = fakeKeyStore(1)
        val firstProcess = processScope()
        boardRoom(board, first, bobStore, SessionRole.Joiner, scope = firstProcess, name = "Bob")
        val carolRoom = boardRoom(board, carol, fakeKeyStore(2), SessionRole.Joiner)
        val carolLog = EventLog(carolRoom, backgroundScope)
        testScheduler.runCurrent()
        firstProcess.cancel()
        board.unplug(first)
        testScheduler.advanceTimeBy(PARTITION_DETECTED)
        testScheduler.runCurrent()
        assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == first }.liveness, "rig: the old seat is held, partitioned")

        val stalled = board.stallFirst(from = KEY_SEAT_HOST, to = carol, duration = 500.milliseconds) {
            it is AdmitMessage.Farewell && it.peerId == first.value
        }
        val carolMark = carolLog.mark()
        val toCarol = board.delivered(from = KEY_SEAT_HOST, to = carol).size
        val rebuilt = boardRoom(board, second, bobStore, SessionRole.Joiner, name = "Bob")
        testScheduler.advanceTimeBy(1_000.milliseconds)
        testScheduler.runCurrent()

        val heard = board.delivered(from = KEY_SEAT_HOST, to = carol).drop(toCarol).filter { it.names(first) || it.names(second) }
        val edges = carolLog.edgesSince(carolMark)
        val leftAt = edges.indexOf(superseded(first, by = second))
        val joinedAt = edges.indexOfFirst { it is MembershipEvent.Joined && it.member.id == second }
        assertAll(
            { assertEquals(1, stalled(), "rig: the Farewell stalled on the bystander's lane") },
            {
                val farewell = heard.indexOfFirst { it is AdmitMessage.Farewell }
                val welcome = heard.indexOfFirst { it is AdmitMessage.Welcome }
                assertTrue(farewell >= 0 && welcome > farewell, "the stalled Farewell is not overtaken: $heard")
            },
            { assertTrue(leftAt >= 0 && joinedAt > leftAt, "bystander: ${edges.render()}") },
            { assertEquals(setOf(KEY_SEAT_HOST, second), carolRoom.roster.value.map { it.id }.toSet()) },
        )
        rebuilt.leave()
        carolRoom.leave()
        host.leave()
    }

    /**
     * Review Focus 1, as Amendment B rigs it: room A's link to the host goes one-way dead, so the host
     * partitions A's seat while A still hears the host. Room B, on the **same** key store, joins; the
     * host restores A's seat under B's id and tells A directly. A ends with
     * `Left(self, Superseded(by = B))` and never says Hello again.
     *
     * Counting A's Hellos means something only if A would have had cause to send one. So A is built
     * with a reweave over a [FlakyLifecycleSeam], and after the supersession its link is flapped — the
     * trigger that makes a live joiner reconnect — and the one-way cut is lifted so a Hello would
     * reach the host. Before Task 1.7 a reconnect sends `Resume` rather than Hello, so both are counted.
     */
    @Test
    fun supersededJoinerDoesNotReHello() = runTest {
        supersededJoinerDoesNotReHelloBody()
    }

    /**
     * Inherited obligation 3 (#3159, Task 1.7): A's link is dead **both ways** when B supersedes it,
     * so the direct `Farewell(supersededBy)` of Decided 19 goes into the dead link and A never hears
     * it. When A's link comes back its reconnect says Hello again — and the host, which remembers the
     * superseded id, answers that Hello with the same `Farewell` instead of a challenge. A ends with
     * `Left(self, Superseded(by = B))` after exactly one Hello, and the host keeps one membership.
     */
    @Test
    fun aSupersededProcessCutOffBothWaysEndsOnItsReHello() = runTest(timeout = us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP) {
        val a = PeerId("tab-a")
        val b = PeerId("tab-b")
        val board = Switchboard(setOf(KEY_SEAT_HOST, a, b))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host)
        val hostLog = EventLog(host, backgroundScope)
        val shared = fakeKeyStore(1)
        val aLink = FlakyLifecycleSeam(board.plug(a), backgroundScope)
        val roomA = boardRoom(board, a, shared, SessionRole.Joiner, seam = aLink, reweave = { aLink })
        val aLog = EventLog(roomA, backgroundScope)
        testScheduler.runCurrent()
        assertTrue(host.roster.value.any { it.id == a }, "rig: A was admitted")

        // Dead both ways: the host partitions A by silence, and A's link tears.
        board.mute(from = a, to = KEY_SEAT_HOST)
        board.mute(from = KEY_SEAT_HOST, to = a)
        aLink.enterWeaving()
        testScheduler.advanceTimeBy(PARTITION_DETECTED)
        testScheduler.runCurrent()
        assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == a }.liveness, "rig: the host holds A as partitioned")

        val hostMark = hostLog.mark()
        val roomB = boardRoom(board, b, shared, SessionRole.Joiner)
        testScheduler.runCurrent()
        val farewellsSentToA = board.sent(from = KEY_SEAT_HOST, to = a).count { it is AdmitMessage.Farewell && it.supersededBy == b.value }
        val farewellsDeliveredToA = board.delivered(from = KEY_SEAT_HOST, to = a).count { it is AdmitMessage.Farewell }
        val aLeftBeforeHeal = aLog.since(0).filterIsInstance<MembershipEvent.Left>()

        // A's link comes back; its reconnect has every route to the host.
        val sentByA = board.sent(from = a, to = KEY_SEAT_HOST).size
        board.unmute(from = a, to = KEY_SEAT_HOST)
        board.unmute(from = KEY_SEAT_HOST, to = a)
        aLink.recover()
        testScheduler.advanceTimeBy(KEY_SEAT_ADMIT_TIMEOUT * 10)
        testScheduler.runCurrent()
        val fromA = board.sent(from = a, to = KEY_SEAT_HOST).drop(sentByA)
        val aEvents = aLog.since(0)

        assertAll(
            { assertEquals(1, farewellsSentToA, "rig: the host told A directly, as Decided 19 says") },
            { assertEquals(0, farewellsDeliveredToA, "rig: that Farewell went into the dead link") },
            { assertEquals(emptyList(), aLeftBeforeHeal, "rig: A did not know before its link came back") },
            { assertEquals(1, fromA.count { it is AdmitMessage.Hello }, "A's reconnect said Hello once: ${fromA.map { it::class.simpleName }}") },
            { assertEquals(0, fromA.count { it is AdmitMessage.Proof }, "the host challenged nothing, so A proved nothing") },
            { assertEquals(listOf(superseded(a, by = b)), aEvents.filter { it is MembershipEvent.Left && it.peerId == a }, aEvents.render()) },
            { assertTrue(aEvents.none { it is MembershipEvent.HostLost }, aEvents.render()) },
            { assertTrue(roomA.roster.value.isEmpty(), "A's room has ended") },
            { assertEquals(listOf(superseded(a, by = b)), hostLog.edgesSince(hostMark).filterIsInstance<MembershipEvent.Left>()) },
            { assertEquals(setOf(b), host.roster.value.map { it.id }.toSet(), "one membership, B's") },
        )
        roomB.leave()
        host.leave()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.supersededJoinerDoesNotReHelloBody() {
        val a = PeerId("tab-a")
        val b = PeerId("tab-b")
        val board = Switchboard(setOf(KEY_SEAT_HOST, a, b))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host)
        val hostLog = EventLog(host, backgroundScope)
        val shared = fakeKeyStore(1)
        val aLink = FlakyLifecycleSeam(board.plug(a), backgroundScope)
        val roomA = boardRoom(board, a, shared, SessionRole.Joiner, seam = aLink, reweave = { aLink })
        val aLog = EventLog(roomA, backgroundScope)
        testScheduler.runCurrent()
        assertTrue(host.roster.value.any { it.id == a }, "rig: A was admitted")

        board.mute(from = a, to = KEY_SEAT_HOST)
        testScheduler.advanceTimeBy(PARTITION_DETECTED)
        testScheduler.runCurrent()
        assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == a }.liveness, "rig: the host holds A as partitioned")
        assertTrue(roomA.roster.value.any { it.id == KEY_SEAT_HOST }, "rig: A still hears its host")

        val hostMark = hostLog.mark()
        val roomB = boardRoom(board, b, shared, SessionRole.Joiner)
        testScheduler.runCurrent()
        val farewellsToA = board.delivered(from = KEY_SEAT_HOST, to = a).count { it is AdmitMessage.Farewell && it.supersededBy == b.value }
        val aEvents = aLog.since(0)

        // Give a still-live A every reason and every route to come back.
        board.unmute(from = a, to = KEY_SEAT_HOST)
        val sentByA = board.sent(from = a).size
        aLink.enterWeaving()
        testScheduler.advanceTimeBy(KEY_SEAT_HEARTBEAT.interval * 3)
        aLink.recover()
        testScheduler.advanceTimeBy(KEY_SEAT_ADMIT_TIMEOUT * 10)
        testScheduler.runCurrent()
        val comebacks = board.sent(from = a).drop(sentByA).filterIsInstance<AdmitMessage.Hello>()

        assertAll(
            { assertEquals(1, farewellsToA, "rig: A was told, directly, that B superseded it") },
            { assertEquals(listOf(superseded(a, by = b)), aEvents.filter { it is MembershipEvent.Left && it.peerId == a }, aEvents.render()) },
            { assertTrue(roomA.roster.value.isEmpty(), "A's room has ended") },
            { assertEquals(emptyList<AdmitMessage>(), comebacks, "a superseded joiner never comes back on its own") },
            { assertEquals(listOf(superseded(a, by = b)), hostLog.edgesSince(hostMark).filterIsInstance<MembershipEvent.Left>()) },
            { assertEquals(setOf(b), host.roster.value.map { it.id }.toSet(), "one membership, B's, and no loop") },
        )
        roomB.leave()
        host.leave()
    }

    /**
     * Decided 30: two connected tabs over one key store. The second is an additional seat, not a
     * supersession of the first; both stay members, nobody is superseded, and over ten admit
     * timeouts neither says Hello again.
     */
    @Test
    fun secondConnectedTabGetsAnAdditionalSeat() = runTest {
        val t1 = PeerId("tab-1")
        val t2 = PeerId("tab-2")
        val board = Switchboard(setOf(KEY_SEAT_HOST, t1, t2))
        val hold = ObservedHold()
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, hold = hold)
        val hostLog = EventLog(host, backgroundScope)
        val shared = fakeKeyStore(1)
        val tab1 = boardRoom(board, t1, shared, SessionRole.Joiner)
        testScheduler.runCurrent()
        assertEquals(Liveness.Connected, host.roster.value.single { it.id == t1 }.liveness, "rig: the first tab is connected")
        val tab2 = boardRoom(board, t2, shared, SessionRole.Joiner)
        val tab1Log = EventLog(tab1, backgroundScope)
        val tab2Log = EventLog(tab2, backgroundScope)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(KEY_SEAT_ADMIT_TIMEOUT * 10)
        testScheduler.runCurrent()
        val sharedKey = shared.deviceKey().id

        val everyEvent = hostLog.since(0) + tab1Log.since(0) + tab2Log.since(0)
        assertAll(
            { assertEquals(setOf(t1, t2), host.roster.value.map { it.id }.toSet()) },
            { assertEquals(listOf(sharedKey, sharedKey), host.roster.value.map { it.key }, "one key, two seats") },
            { assertTrue(hold.calls.isEmpty(), "a connected seat is never restored under another id") },
            { assertTrue(everyEvent.none { it is MembershipEvent.Left }, everyEvent.render()) },
            { assertEquals(listOf(t1, t2), hostLog.since(0).filterIsInstance<MembershipEvent.Joined>().map { it.member.id }) },
            // To the host: a Hello is broadcast, and the switchboard logs one frame per recipient.
            { assertEquals(1, board.sent(from = t1, to = KEY_SEAT_HOST).count { it is AdmitMessage.Hello }, "tab 1 said Hello once") },
            { assertEquals(1, board.sent(from = t2, to = KEY_SEAT_HOST).count { it is AdmitMessage.Hello }, "tab 2 said Hello once") },
            { assertTrue(tab1.roster.value.any { it.id == t2 } && tab2.roster.value.any { it.id == t1 }, "each tab sees the other") },
        )
        tab2.leave()
        tab1.leave()
        host.leave()
    }

    /**
     * Decided 30: the host's own key gets an ordinary additional seat, so the host's second tab — and
     * its third — join like anyone else. Neither displaces the host nor the other.
     */
    @Test
    fun hostSecondTabIsAdmittedAsMember() = runTest {
        val t1 = PeerId("tab-1")
        val t2 = PeerId("tab-2")
        val board = Switchboard(setOf(KEY_SEAT_HOST, t1, t2))
        val hold = ObservedHold()
        val hostStore = fakeKeyStore(HOST_DEVICE)
        val host = boardRoom(board, KEY_SEAT_HOST, hostStore, SessionRole.Host, hold = hold)
        val hostLog = EventLog(host, backgroundScope)
        val tab1 = boardRoom(board, t1, hostStore, SessionRole.Joiner)
        val tab2 = boardRoom(board, t2, hostStore, SessionRole.Joiner)
        testScheduler.runCurrent()
        val hostKey = hostStore.deviceKey().id

        assertAll(
            { assertEquals(setOf(t1, t2), host.roster.value.map { it.id }.toSet()) },
            { assertEquals(listOf(hostKey, hostKey), host.roster.value.map { it.key }, "rig: both tabs proved the host's own key") },
            { assertEquals(hostKey, tab1.roster.value.single { it.id == KEY_SEAT_HOST }.key, "the host is still the host, to tab 1") },
            { assertEquals(hostKey, tab2.roster.value.single { it.id == KEY_SEAT_HOST }.key) },
            { assertTrue(hold.calls.isEmpty()) },
            { assertTrue(hostLog.since(0).none { it is MembershipEvent.Left }, hostLog.since(0).render()) },
        )
        tab2.leave()
        tab1.leave()
        host.leave()
    }

    /**
     * Decided 30's tie-break: one key holds two partitioned seats, and a third process on it comes
     * back under a new id. It takes the seat that was **most recently connected**. Here that is not the
     * most recently *admitted* one, so the two readings are told apart.
     */
    @Test
    fun severalPartitionedSeatsRestoreTheMostRecentlyConnected() = runTest {
        val t1 = PeerId("tab-1")
        val t2 = PeerId("tab-2")
        val t3 = PeerId("tab-3")
        val board = Switchboard(setOf(KEY_SEAT_HOST, t1, t2, t3))
        val hold = ObservedHold()
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, hold = hold)
        val hostLog = EventLog(host, backgroundScope)
        val shared = fakeKeyStore(1)
        val tab2 = boardRoom(board, t2, shared, SessionRole.Joiner)
        testScheduler.runCurrent()
        val tab1 = boardRoom(board, t1, shared, SessionRole.Joiner)
        testScheduler.runCurrent()

        // Tab 1, admitted last, goes quiet first; tab 2 goes quiet a detection later.
        board.mute(from = t1, to = KEY_SEAT_HOST)
        testScheduler.advanceTimeBy(PARTITION_DETECTED)
        testScheduler.runCurrent()
        board.mute(from = t2, to = KEY_SEAT_HOST)
        testScheduler.advanceTimeBy(PARTITION_DETECTED)
        testScheduler.runCurrent()
        val since1 = assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == t1 }.liveness, "rig: tab 1 held").since
        val since2 = assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == t2 }.liveness, "rig: tab 2 held").since
        assertTrue(since2 > since1, "rig: tab 2 was in contact more recently ($since2 > $since1)")

        val mark = hostLog.mark()
        val tab3 = boardRoom(board, t3, shared, SessionRole.Joiner)
        testScheduler.runCurrent()
        val edges = hostLog.edgesSince(mark)

        assertAll(
            { assertEquals(listOf(t2), hold.calls.map { it.peerId }, "the most recently connected seat is the one restored") },
            { assertEquals(superseded(t2, by = t3), edges.firstOrNull { it is MembershipEvent.Left }, edges.render()) },
            { assertIs<Liveness.Partitioned>(host.roster.value.firstOrNull { it.id == t1 }?.liveness, "tab 1's seat is still held") },
            { assertEquals(setOf(t1, t3), host.roster.value.map { it.id }.toSet()) },
        )
        tab3.leave()
        tab1.leave()
        tab2.leave()
        host.leave()
    }

    /**
     * Decided 23 for a seat under another id: the hold policy refuses the old seat's window after it
     * opened, so the seat is gone. It is released as an expiry, and the key is decided again without
     * it: nothing else holds it, so the new id is an ordinary new member with its own Hello's
     * identity, and nobody is told anything was superseded.
     */
    @Test
    fun refusedWindowReleasesTheOldSeatAndAdmitsTheNewIdAfresh() = runTest {
        val hold = ObservedHold(verdict = { ResumeResult.WindowClosed })
        val raw = rawHost(setOf(first, second, carol), hold)
        val bob = fakeDeviceKey(1)
        raw.admit(first, bob, nonce = 1)
        raw.admit(carol, fakeDeviceKey(2), nonce = 2)
        raw.partition(first, alive = setOf(carol))

        val mark = raw.events.mark()
        val toCarol = raw.sentTo(carol).size
        raw.admit(second, bob, nonce = 3)
        val edges = raw.events.edgesSince(mark)
        val heard = raw.sentTo(carol).drop(toCarol)

        assertAll(
            { assertEquals(listOf(first), hold.calls.map { it.peerId }, "rig: the old seat's window was asked") },
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.WindowClosed), hold.verdicts) },
            { assertEquals(MembershipEvent.Left(first, LeaveReason.PartitionExpired), edges.getOrNull(0), edges.render()) },
            { assertEquals(second, (edges.getOrNull(1) as? MembershipEvent.Joined)?.member?.id, edges.render()) },
            { assertEquals(2, edges.size, edges.render()) },
            { assertEquals("bob-2", raw.member(second)?.identity?.displayName, "a fresh member carries its own Hello's identity") },
            { assertEquals(listOf(AdmitMessage.Farewell(first.value, expired = true)), heard.farewellsFor(first)) },
            { assertTrue(raw.sentTo(first).none { it is AdmitMessage.Farewell }, "nobody was superseded, so nobody is told so") },
            { assertEquals(null, raw.sentTo(second).welcomesFor(second).single().returnKind, "an ordinary admission") },
        )
    }

    /**
     * The controller has not opened the old seat's window yet when the new id's proof is decided, so
     * it answers `WindowNotYetOpen`. The seat is still held, so it is taken over, as a same-id restore
     * treats the same answer. When the window does open, late, its expiry finds nothing to evict.
     */
    @Test
    fun windowNotYetOpenStillTakesTheSeatOver() = runTest {
        val hold = ObservedHold()
        val raw = rawHost(setOf(first, second, carol), hold)
        val bob = fakeDeviceKey(1)
        raw.admit(first, bob, nonce = 1)
        raw.admit(carol, fakeDeviceKey(2), nonce = 2)
        val lag = CompletableDeferred<Unit>()
        hold.lagOpening = lag
        raw.partition(first, alive = setOf(carol))

        val mark = raw.events.mark()
        raw.admit(second, bob, nonce = 3)
        val edges = raw.events.edgesSince(mark)
        lag.complete(Unit)
        raw.advanceKeeping(setOf(second, carol), KEY_SEAT_HEARTBEAT.reconnectWindow * 2)
        val later = raw.events.edgesSince(mark)

        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.WindowNotYetOpen), hold.verdicts, "rig: no window yet") },
            { assertEquals(1, hold.opened.size, "rig: the window did open, late") },
            { assertEquals(listOf(superseded(first, by = second)), edges.filterIsInstance<MembershipEvent.Left>(), edges.render()) },
            { assertEquals(second, (edges.getOrNull(1) as? MembershipEvent.Joined)?.member?.id, edges.render()) },
            { assertEquals("bob-1", raw.member(second)?.identity?.displayName, "the old seat's identity") },
            { assertEquals(ReturnKind.Restored, raw.sentTo(second).welcomesFor(second).single().returnKind) },
            { assertEquals(edges, later, "the late window evicts nobody: ${later.render()}") },
            { assertEquals(setOf(second, carol), raw.room.roster.value.map { it.id }.toSet()) },
        )
    }

    /**
     * The window answers `Success` for the old seat, which spends it and stops its timer, and then
     * the new id's challenge goes stale before the room acts on the answer: the new tab says Goodbye.
     * Nobody can restore the seat now, and no expiry is left to end it, so it is released in the pass
     * that finds the answer stale. Carol, a star bystander, hears the expired `Farewell` then, and is
     * left with no ghost. A later Hello from the same tab is an ordinary admission.
     */
    @Test
    fun aStaleSuccessReleasesTheOldSeatAndTellsTheBystanders() = runTest {
        val hold = ObservedHold()
        val raw = rawHost(setOf(first, second, carol), hold)
        val bob = fakeDeviceKey(1)
        raw.admit(first, bob, nonce = 1)
        raw.admit(carol, fakeDeviceKey(2), nonce = 2)
        raw.partition(first, alive = setOf(carol))
        val answered = CompletableDeferred<Unit>()
        hold.holdAnswer = answered

        val mark = raw.events.mark()
        val toCarol = raw.sentTo(carol).size
        raw.admit(second, bob, nonce = 3)
        val verdictsWhileHeld = hold.verdicts.toList()
        raw.say(second, AdmitMessage.Goodbye)
        hold.holdAnswer = null
        answered.complete(Unit)
        testScheduler.runCurrent()
        val edges = raw.events.edgesSince(mark)
        val heard = raw.sentTo(carol).drop(toCarol)
        val welcomedSecond = raw.sentTo(second).welcomesFor(second).size

        raw.admit(second, bob, nonce = 4)
        val readmitted = raw.events.edgesSince(mark).drop(edges.size)

        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), verdictsWhileHeld, "rig: the window was spent first") },
            { assertEquals(listOf(MembershipEvent.Left(first, LeaveReason.PartitionExpired)), edges, edges.render()) },
            { assertEquals(listOf(AdmitMessage.Farewell(first.value, expired = true)), heard.farewellsFor(first)) },
            { assertTrue(first !in raw.rosterHeardBy(carol), "no ghost on the bystander: ${raw.rosterHeardBy(carol)}") },
            { assertEquals(null, raw.member(first), "the host holds no seat for the old id") },
            { assertEquals(0, welcomedSecond, "the stale exchange seated nobody") },
            { assertEquals(1, hold.calls.size, "the later Hello asks no window: the seat is gone") },
            { assertEquals(second, (readmitted.singleOrNull() as? MembershipEvent.Joined)?.member?.id, readmitted.render()) },
            { assertEquals(null, raw.sentTo(second).welcomesFor(second).lastOrNull()?.returnKind, "an ordinary admission") },
        )
    }

    /**
     * A `Success` the room no longer acts on ends its seat only in the episode it was about. Here the
     * old seat heals and partitions again while the answer is held, so the controller holds a fresh
     * window for it; then the new tab says Goodbye. The seat is left to that window: still held, and
     * nobody is told it left.
     */
    @Test
    fun aStaleSuccessLeavesASeatPartitionedAgainToItsNewWindow() = runTest {
        val hold = ObservedHold()
        val raw = rawHost(setOf(first, second, carol), hold)
        val bob = fakeDeviceKey(1)
        raw.admit(first, bob, nonce = 1)
        raw.admit(carol, fakeDeviceKey(2), nonce = 2)
        raw.partition(first, alive = setOf(carol))
        val episodeAsked = assertIs<Liveness.Partitioned>(raw.member(first)?.liveness).since
        val answered = CompletableDeferred<Unit>()
        hold.holdAnswer = answered

        val mark = raw.events.mark()
        val toCarol = raw.sentTo(carol).size
        raw.admit(second, bob, nonce = 3)
        raw.advanceKeeping(setOf(carol, first), KEY_SEAT_HEARTBEAT.interval * 2)
        assertEquals(Liveness.Connected, raw.member(first)?.liveness, "rig: the old seat healed while the answer was held")
        raw.partition(first, alive = setOf(carol))
        val episodeNow = assertIs<Liveness.Partitioned>(raw.member(first)?.liveness).since
        raw.say(second, AdmitMessage.Goodbye)
        hold.holdAnswer = null
        answered.complete(Unit)
        testScheduler.runCurrent()
        val edges = raw.events.edgesSince(mark)

        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), hold.verdicts, "rig: the window was spent") },
            { assertTrue(episodeNow > episodeAsked, "rig: a new partition episode ($episodeNow > $episodeAsked)") },
            { assertIs<Liveness.Partitioned>(raw.member(first)?.liveness, "the seat is still held, for its new window") },
            { assertTrue(edges.none { it is MembershipEvent.Left }, edges.render()) },
            { assertEquals(emptyList(), raw.sentTo(carol).drop(toCarol).farewellsFor(first), "nobody is told it left") },
            { assertTrue(first in raw.rosterHeardBy(carol), "the bystander still holds it") },
            { assertEquals(0, raw.sentTo(second).welcomesFor(second).size, "the stale exchange seated nobody") },
        )
    }

    /**
     * An exchange that moves on from the seat its `Success` was about forgets that answer. Bob's key
     * holds two partitioned seats, and a new tab's window `Success` about the newer one is held while
     * that seat heals. The tab's next pass finds the key's other seat partitioned, and a second new
     * tab already asking about it, so it waits. Meanwhile the healed seat partitions again and a third
     * tab takes it over. When the waiting tab decides again, the spent answer is about a seat that
     * has moved on twice: acting on it would send Carol an expired `Farewell` for a seat she has
     * already heard was superseded.
     */
    @Test
    fun aWaitForAnotherSeatForgetsTheSpentAnswer() = runTest {
        val waiter = PeerId("bob-3")
        val asker = PeerId("bob-4")
        val taker = PeerId("bob-5")
        val hold = ObservedHold()
        val waiterAnswer = CompletableDeferred<Unit>()
        val askerAnswer = CompletableDeferred<Unit>()
        hold.holdCall[0] = waiterAnswer
        hold.holdCall[1] = askerAnswer
        val raw = rawHost(setOf(first, second, waiter, asker, taker, carol), hold)
        val bob = fakeDeviceKey(1)
        raw.admit(first, bob, nonce = 1)
        raw.admit(second, bob, nonce = 2)
        raw.admit(carol, fakeDeviceKey(2), nonce = 3)
        // The second seat goes quiet first, so the first is the one heard from last.
        raw.partition(second, alive = setOf(carol, first))
        raw.partition(first, alive = setOf(carol))
        val episodeAsked = assertIs<Liveness.Partitioned>(raw.member(first)?.liveness).since

        val toCarol = raw.sentTo(carol).size
        raw.admit(waiter, bob, nonce = 4)
        raw.advanceKeeping(setOf(carol, first), KEY_SEAT_HEARTBEAT.interval * 2)
        val healed = raw.member(first)?.liveness
        raw.admit(asker, bob, nonce = 5)
        waiterAnswer.complete(Unit)
        testScheduler.runCurrent()
        val askedWhileWaiting = hold.calls.map { it.peerId }
        val waiterSeatedEarly = raw.sentTo(waiter).welcomesFor(waiter).size
        raw.partition(first, alive = setOf(carol))
        val episodeTaken = assertIs<Liveness.Partitioned>(raw.member(first)?.liveness).since
        raw.admit(taker, bob, nonce = 6)
        val heardBeforeWake = raw.sentTo(carol).drop(toCarol).farewellsFor(first)
        askerAnswer.complete(Unit)
        testScheduler.runCurrent()
        val heard = raw.sentTo(carol).drop(toCarol)

        assertAll(
            { assertEquals(Liveness.Connected, healed, "rig: the first seat healed while its Success was held") },
            { assertEquals(listOf(first, second), askedWhileWaiting, "rig: the waiter did not ask about the second seat") },
            { assertEquals(0, waiterSeatedEarly, "rig: the waiter was waiting, not seated") },
            { assertTrue(episodeTaken > episodeAsked, "rig: a new partition episode ($episodeTaken > $episodeAsked)") },
            { assertEquals(listOf(first, second, first), hold.calls.map { it.peerId }, "rig: the asks") },
            { assertEquals(List<ResumeResult.HostVerdict>(3) { ResumeResult.Success }, hold.verdicts, "rig: every window was spent") },
            {
                assertEquals(
                    listOf(AdmitMessage.Farewell(first.value, supersededBy = taker.value)),
                    heardBeforeWake,
                    "rig: the takeover was announced before the waiter woke",
                )
            },
            { assertEquals(listOf(AdmitMessage.Farewell(first.value, supersededBy = taker.value)), heard.farewellsFor(first)) },
            { assertEquals(listOf(AdmitMessage.Farewell(second.value, supersededBy = asker.value)), heard.farewellsFor(second)) },
            { assertEquals(null, raw.sentTo(waiter).welcomesFor(waiter).singleOrNull()?.returnKind, "the waiter gets an additional seat") },
            { assertEquals(setOf(KEY_SEAT_HOST, carol, waiter, asker, taker), raw.rosterHeardBy(carol)) },
        )
    }

    /**
     * The window answers `Success` for the old seat, and before the room acts on it the old seat's
     * own detector gives up on it: its `PeerLost` evicts the seat on the host and tells nobody, since
     * an expiry normally does that and this window, spent, will never expire. The pass that finds
     * the seat gone, with the window spent on it and no takeover, sends Carol the expired `Farewell`,
     * ahead of the new member's `Welcome`.
     */
    @Test
    fun aLossBetweenASuccessAndItsSeatingStillTellsTheBystanders() = runTest {
        val hold = ObservedHold()
        val raw = rawHost(setOf(first, second, carol), hold)
        val bob = fakeDeviceKey(1)
        raw.admit(first, bob, nonce = 1)
        raw.admit(carol, fakeDeviceKey(2), nonce = 2)
        raw.partition(first, alive = setOf(carol))
        // Most of the detector's window, so its loss lands inside the new id's admit timeout.
        raw.advanceKeeping(setOf(carol), KEY_SEAT_HEARTBEAT.reconnectWindow * 3 / 4)
        assertIs<Liveness.Partitioned>(raw.member(first)?.liveness, "rig: the old seat is still held")
        val answered = CompletableDeferred<Unit>()
        hold.holdAnswer = answered

        val mark = raw.events.mark()
        val toCarol = raw.sentTo(carol).size
        raw.admit(second, bob, nonce = 3)
        // Past the old seat's loss, and short of the new id's admit timeout, so its challenge is current.
        raw.advanceKeeping(setOf(carol), KEY_SEAT_ADMIT_TIMEOUT * 3 / 5)
        val lostWhileHeld = raw.events.edgesSince(mark)
        val heardWhileHeld = raw.sentTo(carol).drop(toCarol).farewellsFor(first)
        hold.holdAnswer = null
        answered.complete(Unit)
        testScheduler.runCurrent()
        val edges = raw.events.edgesSince(mark)
        val heard = raw.sentTo(carol).drop(toCarol).filter { it.names(first) || it.names(second) }

        assertAll(
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), hold.verdicts, "rig: the window was spent") },
            { assertEquals(listOf(MembershipEvent.Left(first, LeaveReason.PartitionExpired)), lostWhileHeld, "rig: the loss evicted the old seat") },
            { assertEquals(emptyList(), heardWhileHeld, "rig: the loss itself told the bystander nothing") },
            { assertEquals(listOf(AdmitMessage.Farewell(first.value, expired = true)), heard.farewellsFor(first)) },
            {
                val farewell = heard.indexOfFirst { it is AdmitMessage.Farewell && it.peerId == first.value }
                val welcome = heard.indexOfFirst { it is AdmitMessage.Welcome && it.assignedPeerId == second.value }
                assertTrue(farewell >= 0 && welcome > farewell, "Farewell(old) before Welcome(new): $heard")
            },
            { assertEquals(setOf(KEY_SEAT_HOST, carol, second), raw.rosterHeardBy(carol), "the bystander's roster has no ghost") },
            { assertEquals(second, (edges.lastOrNull() as? MembershipEvent.Joined)?.member?.id, edges.render()) },
            { assertTrue(edges.none { it is MembershipEvent.Resumed }, edges.render()) },
        )
    }

    /**
     * Two tabs of one device come back under two new ids against its one partitioned seat, and both
     * proofs are decided while the window is being asked about. The first exchange's `Success` is
     * then held before the room acts on it, which is where a second ask's refusal used to land and
     * release the seat the first one had just been given. Only one exchange asks: the other waits
     * for that one to act on its answer and then decides again. So the first takes the seat over,
     * the second finds the key's seat connected and gets one of its own (Decided 30), and no one
     * sees a `Resumed`, an `Unpaused` or a `ResumeAck` for the seat that left.
     */
    @Test
    fun twoNewIdsForOneSeatTakeItOverOnceAndSeatTheOtherBeside() = runTest {
        val third = PeerId("bob-3")
        val gate = CompletableDeferred<Unit>()
        val hold = ObservedHold(gate = gate)
        val firstAnswer = CompletableDeferred<Unit>()
        hold.holdCall[0] = firstAnswer
        val raw = rawHost(setOf(first, second, third, carol), hold)
        val bob = fakeDeviceKey(1)
        raw.admit(first, bob, nonce = 1)
        raw.admit(carol, fakeDeviceKey(2), nonce = 2)
        raw.partition(first, alive = setOf(carol))

        val mark = raw.events.mark()
        val toCarol = raw.sentTo(carol).size
        raw.admit(second, bob, nonce = 3)
        raw.admit(third, bob, nonce = 4)
        val welcomedThirdWhileAsking = raw.sentTo(third).welcomesFor(third).size
        gate.complete(Unit)
        testScheduler.runCurrent()
        val firstVerdict = hold.verdicts.firstOrNull()
        firstAnswer.complete(Unit)
        testScheduler.runCurrent()
        val events = raw.events.since(mark)
        val edges = raw.events.edgesSince(mark)
        val heard = raw.sentTo(carol).drop(toCarol)

        assertAll(
            { assertEquals(0, welcomedThirdWhileAsking, "rig: the second exchange was decided while the first asked") },
            { assertEquals(ResumeResult.Success, firstVerdict, "rig: the first exchange's Success was held before it was acted on") },
            { assertEquals(listOf(first), hold.calls.map { it.peerId }, "one exchange asks about the seat") },
            { assertEquals(listOf(superseded(first, by = second)), edges.filterIsInstance<MembershipEvent.Left>(), edges.render()) },
            { assertEquals(listOf(second, third), edges.filterIsInstance<MembershipEvent.Joined>().map { it.member.id }, edges.render()) },
            { assertTrue(events.none { it is MembershipEvent.Resumed || it is MembershipEvent.Recovered }, events.render()) },
            { assertTrue(raw.sentToAnyone().none { it is AdmitMessage.Unpaused }, "no stray Unpaused") },
            { assertEquals(setOf(second, third, carol), raw.room.roster.value.map { it.id }.toSet()) },
            { assertEquals(listOf(AdmitMessage.Farewell(first.value, supersededBy = second.value)), heard.farewellsFor(first)) },
            { assertEquals(setOf(KEY_SEAT_HOST, carol, second, third), raw.rosterHeardBy(carol), "the bystander's roster has no ghost") },
            { assertEquals(ReturnKind.Restored, raw.sentTo(second).welcomesFor(second).singleOrNull()?.returnKind, "the takeover") },
            { assertEquals(null, raw.sentTo(third).welcomesFor(third).singleOrNull()?.returnKind, "an additional seat") },
        )
    }

    /**
     * The old seat heals while the window is being asked about: a pong from it lands, the host marks
     * it recovered, and only then does the window answer `Success`. The seat is connected again, so
     * it is not taken over (Decided 30): the new id gets a seat of its own beside it, and nobody is
     * told the old one left.
     */
    @Test
    fun aHealDuringTheAskKeepsTheOldSeatAndSeatsTheNewIdBeside() = runTest {
        val hold = ObservedHold()
        val raw = rawHost(setOf(first, second, carol), hold)
        val bob = fakeDeviceKey(1)
        raw.admit(first, bob, nonce = 1)
        raw.admit(carol, fakeDeviceKey(2), nonce = 2)
        raw.partition(first, alive = setOf(carol))
        var healedBeforeAnswer = false
        hold.beforeAnswer = {
            raw.ping(first)
            raw.room.roster.first { roster -> roster.any { it.id == first && it.liveness == Liveness.Connected } }
            healedBeforeAnswer = true
        }

        val mark = raw.events.mark()
        val toCarol = raw.sentTo(carol).size
        raw.admit(second, bob, nonce = 3)
        // The healed seat keeps talking, so it is still connected when the test reads it.
        raw.advanceKeeping(setOf(carol, first), KEY_SEAT_HEARTBEAT.interval * 2)
        val events = raw.events.since(mark)
        val heard = raw.sentTo(carol).drop(toCarol)

        assertAll(
            { assertTrue(healedBeforeAnswer, "rig: the seat healed before the window answered") },
            { assertEquals(listOf<ResumeResult.HostVerdict>(ResumeResult.Success), hold.verdicts, "rig: the window answered Success") },
            { assertTrue(events.any { it is MembershipEvent.Recovered && it.peerId == first }, events.render()) },
            { assertTrue(events.none { it is MembershipEvent.Left }, events.render()) },
            { assertTrue(events.none { it is MembershipEvent.Resumed }, events.render()) },
            { assertEquals(second, events.filterIsInstance<MembershipEvent.Joined>().singleOrNull()?.member?.id, events.render()) },
            { assertEquals(Liveness.Connected, raw.member(first)?.liveness, "the old seat is kept") },
            { assertEquals(setOf(first, second, carol), raw.room.roster.value.map { it.id }.toSet()) },
            { assertEquals(emptyList(), heard.farewellsFor(first), "nobody is told the old seat left") },
            { assertTrue(raw.sentToAnyone().none { it is AdmitMessage.Farewell && it.supersededBy != null }, "nobody was superseded") },
            { assertEquals(null, raw.sentTo(second).welcomesFor(second).singleOrNull()?.returnKind, "an additional seat") },
            { assertEquals("bob-2", raw.member(second)?.identity?.displayName, "with its own Hello's identity") },
        )
    }

    /**
     * Decided 30's tie-break reads when each seat was last **heard from**, not when its silence was
     * noticed. Tab 2 is heard from after tab 1, but its transport then closes, which is noticed at
     * once, while tab 1's quiet link is noticed only a timeout later. So the noticing order is the
     * reverse of the hearing order, and a new id on the key must take tab 2's seat.
     */
    @Test
    fun tieBreakIsLastContactNotWhenTheSilenceWasNoticed() = runTest {
        val t1 = PeerId("tab-1")
        val t2 = PeerId("tab-2")
        val t3 = PeerId("tab-3")
        val hold = ObservedHold()
        val raw = rawHost(setOf(t1, t2, t3), hold)
        val shared = fakeDeviceKey(1)
        raw.admit(t1, shared, nonce = 1)
        raw.admit(t2, shared, nonce = 2)
        raw.advanceKeeping(setOf(t1, t2), KEY_SEAT_HEARTBEAT.interval * 3)
        val heardT1 = testScheduler.currentTime - KEY_SEAT_HEARTBEAT.interval.inWholeMilliseconds
        testScheduler.advanceTimeBy(50.milliseconds)
        raw.ping(t2)
        testScheduler.runCurrent()
        val heardT2 = testScheduler.currentTime
        testScheduler.advanceTimeBy(10.milliseconds)
        raw.seam.removePeer(t2)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(PARTITION_DETECTED)
        testScheduler.runCurrent()
        val noticed1 = assertIs<Liveness.Partitioned>(raw.member(t1)?.liveness, "rig: tab 1 held").since.toEpochMilliseconds()
        val noticed2 = assertIs<Liveness.Partitioned>(raw.member(t2)?.liveness, "rig: tab 2 held").since.toEpochMilliseconds()
        assertTrue(heardT1 < heardT2 && noticed2 < noticed1, "rig: heard 1=$heardT1 2=$heardT2, noticed 1=$noticed1 2=$noticed2")

        val mark = raw.events.mark()
        raw.admit(t3, shared, nonce = 3)
        val edges = raw.events.edgesSince(mark)

        assertAll(
            { assertEquals(listOf(t2), hold.calls.map { it.peerId }, "the seat heard from last is the one taken over") },
            { assertEquals(superseded(t2, by = t3), edges.firstOrNull { it is MembershipEvent.Left }, edges.render()) },
            { assertIs<Liveness.Partitioned>(raw.member(t1)?.liveness, "tab 1's seat is still held") },
        )
    }
}
