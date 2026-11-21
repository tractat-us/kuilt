@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.test.FlakyLifecycleSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// DRAFT (#3159, Task 1.6): uncompiled. Assumes LeaveReason.Superseded(by: PeerId) and
// Farewell.supersededBy: String? from the plan's Task 1.6, plus everything 1.5 assumes. See
// DRAFT-NOTES-tests.md on branch draft/3159-tests-1.5-1.7.

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
        val rebuilt = boardRoom(board, second, bobStore, SessionRole.Joiner, name = "Bob")
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
            { assertTrue(board.sent(from = KEY_SEAT_HOST).none { it is AdmitMessage.ResumeAck }, "no stray ResumeAck") },
            { assertEquals(setOf(second, carol), host.roster.value.map { it.id }.toSet()) },
            { assertEquals(bobsKey, host.roster.value.single { it.id == second }.key, "the new seat inherits the key") },
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
        val comebacks = board.sent(from = a).drop(sentByA).filter { it is AdmitMessage.Hello || it is AdmitMessage.Resume }

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
            { assertEquals(1, board.sent(from = t1).count { it is AdmitMessage.Hello }, "tab 1 said Hello once") },
            { assertEquals(1, board.sent(from = t2).count { it is AdmitMessage.Hello }, "tab 2 said Hello once") },
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
            { assertIs<Liveness.Partitioned>(host.roster.value.single { it.id == t1 }.liveness, "tab 1's seat is still held") },
            { assertEquals(setOf(t1, t3), host.roster.value.map { it.id }.toSet()) },
        )
        tab3.leave()
        tab1.leave()
        tab2.leave()
        host.leave()
    }
}
