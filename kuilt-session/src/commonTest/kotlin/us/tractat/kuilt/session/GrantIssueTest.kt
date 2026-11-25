@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.GrantTrust
import us.tractat.kuilt.identity.GrantVerdict
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmissionDecision
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.GrantReach
import us.tractat.kuilt.session.admit.GrantTerms
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A host issues every member it seats a pass back in, on the direct `Welcome`, and renews it every
 * half lifetime while the member stays connected (identity Task 2.5, #3221).
 *
 * The pass is what lets a device that drops come back without being vetted (2.6 reads it), so what
 * matters here is the pass the device ends up holding: whose it is, for where, until when, and that
 * a device away from the host stops receiving fresh ones. Every timing is virtual.
 */
class GrantIssueTest {
    private val bob = PeerId("bob")
    private val bobTab = PeerId("bob-tab")
    private val carol = PeerId("carol")

    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)
    private val hostKey: DidKey = fakeDidKey(HOST_DEVICE)

    /** The host's own trust: what 2.6 will check a presented pass with. */
    private suspend fun trust(revocations: RevocationList = RevocationList.inMemory()) =
        GrantTrust(hostKey, revocations, fakeKeyStore(HOST_DEVICE).keyScheme)

    private fun TestScope.now(): Long = testScheduler.currentTime

    private fun admitting(terms: GrantTerms, revocations: RevocationList = RevocationList.inMemory()) =
        HostAdmission(AdmissionPolicy { AdmissionDecision.Admit(terms) }, revocations)

    private fun RawHost.renewalsTo(peer: PeerId): Int = sentTo(peer).count { it is AdmitMessage.GrantRenewal }

    /**
     * A [RawHost] whose reconnect window is [reconnectWindow], long enough that a partitioned seat is
     * still held when a renewal falls due: with [KEY_SEAT_HEARTBEAT]'s two seconds, the seat would be
     * gone first, and a host that renewed a partitioned member would never be seen doing it.
     */
    private suspend fun TestScope.longHoldHost(admission: HostAdmission, reconnectWindow: Duration = 1.hours): RawHost {
        val seam = FakeSeam(selfId = KEY_SEAT_HOST, initialPeers = setOf(KEY_SEAT_HOST, bob, carol))
        val room = SeamRoomFactory(
            loom = InMemoryLoom(),
            scope = backgroundScope,
            clock = virtualClock(),
            deviceKeyStore = fakeKeyStore(HOST_DEVICE),
            heartbeatConfig = KEY_SEAT_HEARTBEAT.copy(reconnectWindow = reconnectWindow),
            admitTimeout = KEY_SEAT_ADMIT_TIMEOUT,
        ).adopt(seam, AdoptRole.Host(admission, roomId = KEY_SEAT_ROOM), memberName = "Host")
        val events = EventLog(room, backgroundScope)
        testScheduler.runCurrent()
        return RawHost(this, seam, room, ObservedHold(), events, hostKey)
    }

    // ── Issue ────────────────────────────────────────────────────────────────

    /**
     * The direct Welcome carries a pass the host signed for the member's own key, for this room, good
     * for the policy's term from the instant it was issued. Checked the way a host will check it on
     * return: valid now, and expired at the end of its term.
     */
    @Test
    fun welcomeCarriesAGrantForTheMembersKey() = runTest {
        val host = rawHost(joiners = setOf(bob), admission = admitting(GrantTerms(readmitFor = 1.hours)))
        val admittedAt = now()

        host.admit(bob, bobKey, nonce = 1)

        val direct = host.sentTo(bob).filterIsInstance<AdmitMessage.Welcome>().single { it.membership != null }
        val pass = assertNotNull(direct.grant?.let(Grant::decode), "the direct Welcome carries a pass")
        val trust = trust()
        val validNow = trust.verify(pass, bobKey.id, KEY_SEAT_ROOM.value, now())
        val atItsEnd = trust.verify(pass, bobKey.id, KEY_SEAT_ROOM.value, pass.notAfter)
        assertAll(
            { assertEquals(hostKey, pass.issuer, "the host issued it") },
            { assertEquals(bobKey.id, pass.subject, "for Bob's key") },
            { assertEquals<GrantScope>(GrantScope.Room(KEY_SEAT_ROOM.value), pass.scope, "for this room") },
            { assertEquals(admittedAt, pass.issuedAt) },
            { assertEquals(admittedAt + 1.hours.inWholeMilliseconds, pass.notAfter) },
            { assertEquals<GrantVerdict>(GrantVerdict.Valid, validNow) },
            { assertEquals<GrantVerdict>(GrantVerdict.Expired, atItsEnd) },
            {
                val bystanderCopies = host.sentToAnyone().filterIsInstance<AdmitMessage.Welcome>().filter { it.membership == null }
                assertTrue(bystanderCopies.none { it.grant != null }, "no other Welcome carries a pass")
            },
        )
    }

    /** A real joiner keeps the pass its Welcome carried in its own device key store. */
    @Test
    fun aJoinerKeepsThePassItsWelcomeCarries() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, admission = admitting(GrantTerms(1.hours)))
        val bobStore = fakeKeyStore(1)
        val joiner = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()

        val kept = bobStore.grants()
        val verdict = kept.singleOrNull()?.let { trust().verify(it, bobStore.deviceKey().id, KEY_SEAT_ROOM.value, now()) }
        assertAll(
            { assertTrue(host.roster.value.any { it.id == bob }, "rig: Bob was admitted") },
            { assertEquals(1, kept.size, "Bob keeps one pass: $kept") },
            { assertEquals<GrantVerdict?>(GrantVerdict.Valid, verdict) },
        )
        joiner.leave()
        host.leave()
    }

    /**
     * A pass a device holds from a clock that ran ahead — dated beyond the skew allowance — gives way
     * to the one its admission hands it, so the device is not vetted on every return until the bad
     * date comes near (Amendment G, Decided 24).
     */
    @Test
    fun aPostDatedPassGivesWayToTheOneAdmissionIssues() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, admission = admitting(GrantTerms(1.hours)))
        val bobStore = fakeKeyStore(1)
        val bobId = bobStore.deviceKey().id
        val postDated = Grant.issue(fakeDeviceKey(HOST_DEVICE), bobId, GrantScope.Room(KEY_SEAT_ROOM.value), now() + 1.hours.inWholeMilliseconds, now() + 2.hours.inWholeMilliseconds)
        bobStore.keepGrant(postDated)
        val joiner = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()

        val kept = bobStore.grants()
        val heldVerdict = trust().verify(postDated, bobId, KEY_SEAT_ROOM.value, now())
        val keptVerdict = kept.singleOrNull()?.let { trust().verify(it, bobId, KEY_SEAT_ROOM.value, now()) }
        assertAll(
            { assertEquals<GrantVerdict>(GrantVerdict.NotYetValid, heldVerdict, "rig: the held pass is refused") },
            { assertTrue(host.roster.value.any { it.id == bob }, "rig: Bob was admitted") },
            { assertEquals(1, kept.size, "one pass per issuer and scope: $kept") },
            { assertEquals<GrantVerdict?>(GrantVerdict.Valid, keptVerdict, "the fresh pass replaced it") },
        )
        joiner.leave()
        host.leave()
    }

    /**
     * A restore and a takeover hand the member a fresh pass on the terms its membership was admitted
     * on, not the room's default: a friend's no-expiry, any-room pass survives its own return.
     */
    @Test
    fun aRestoredOrTakenOverSeatIsIssuedAPassOnItsOwnTerms() = runTest {
        val friend = GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer)
        val host = rawHost(joiners = setOf(bob, bobTab, carol), admission = admitting(friend))
        host.admit(bob, bobKey, nonce = 1)
        host.partition(bob, alive = emptySet())
        host.admit(bob, bobKey, nonce = 2) // restored, same id
        host.partition(bob, alive = emptySet())
        host.admit(bobTab, bobKey, nonce = 3) // taken over under a new id

        val passes = host.passesTo(bob) + host.passesTo(bobTab)
        assertAll(
            { assertTrue(host.events.since(0).any { it is MembershipEvent.Resumed && it.peerId == bob }, "rig: the seat was restored") },
            { assertTrue(host.member(bobTab) != null && host.member(bob) == null, "rig: the new id took the seat over") },
            { assertEquals(3, passes.size, "one pass per Welcome: $passes") },
            { assertTrue(passes.all { it.scope == GrantScope.AnyRoomOfIssuer && it.notAfter == Grant.NO_EXPIRY }, "every pass on the friend's terms: $passes") },
            { assertEquals(passes.map { it.issuedAt }.sorted().distinct(), passes.map { it.issuedAt }, "each dated by its own Welcome") },
        )
    }

    // ── Renewal ──────────────────────────────────────────────────────────────

    /**
     * A connected member is sent a fresh pass every half of its lifetime, and not before: none at
     * just under half, one at half, a second at a full lifetime.
     */
    @Test
    fun refreshArrivesAtHalfLifeWhileConnected() = runTest {
        val host = rawHost(joiners = setOf(bob), admission = admitting(GrantTerms(2.seconds)))
        val admittedAt = now()
        host.admit(bob, bobKey, nonce = 1)

        host.advanceKeeping(setOf(bob), 900.milliseconds)
        val beforeHalf = host.renewalsTo(bob)
        host.advanceKeeping(setOf(bob), 100.milliseconds)
        val atHalf = host.renewalsTo(bob)
        host.advanceKeeping(setOf(bob), 1.seconds)

        val passes = host.passesTo(bob)
        val lastVerdict = trust().verify(passes.last(), bobKey.id, KEY_SEAT_ROOM.value, now())
        assertAll(
            { assertEquals(0, beforeHalf, "nothing before half the lifetime") },
            { assertEquals(1, atHalf, "a renewal at half the lifetime") },
            { assertEquals(listOf(admittedAt, admittedAt + 1_000, admittedAt + 2_000), passes.map { it.issuedAt }, "Welcome, then one renewal per half lifetime") },
            { assertTrue(passes.all { it.notAfter - it.issuedAt == 2_000L }, "each renewal on the same terms") },
            { assertEquals<GrantVerdict>(GrantVerdict.Valid, lastVerdict) },
        )
    }

    /** A host store whose key refuses to sign the first pass it is asked to: a platform signer failing once. */
    private class FailsFirstPass(private val inner: DeviceKeyStore) : DeviceKeyStore by inner {
        var refused = 0

        override suspend fun deviceKey(): DeviceKey {
            val key = inner.deviceKey()
            return object : DeviceKey {
                override val id: DidKey = key.id

                @us.tractat.kuilt.identity.IdentityInternalApi
                override suspend fun sign(message: ByteArray): ByteArray {
                    if (refused == 0 && "kuilt/grant" in message.decodeToString()) {
                        refused++
                        error("the platform signer failed")
                    }
                    return key.sign(message)
                }
            }
        }
    }

    /**
     * A pass the host fails to sign at seating leaves the Welcome without one, and renewals still
     * start: the next pass arrives a period later. Renewals run on the membership's terms, not on a
     * pass that happened to be signed.
     */
    @Test
    fun aPassThatFailsToSignAtSeatingIsRenewedAPeriodLater() = runTest {
        val store = FailsFirstPass(fakeKeyStore(HOST_DEVICE))
        val host = rawHost(joiners = setOf(bob), store = store, admission = admitting(GrantTerms(2.seconds)))
        host.admit(bob, bobKey, nonce = 1)
        val direct = host.sentTo(bob).filterIsInstance<AdmitMessage.Welcome>().single { it.membership != null }

        host.advanceKeeping(setOf(bob), 1.seconds)

        val passes = host.passesTo(bob)
        val verdict = passes.singleOrNull()?.let { trust().verify(it, bobKey.id, KEY_SEAT_ROOM.value, now()) }
        assertAll(
            { assertEquals(1, store.refused, "rig: the signer refused the Welcome's pass") },
            { assertEquals(null, direct.grant, "rig: so the Welcome carries none") },
            { assertEquals(1, host.renewalsTo(bob), "a renewal still arrives a period later") },
            { assertEquals<GrantVerdict?>(GrantVerdict.Valid, verdict) },
        )
    }

    /**
     * A member the host holds as partitioned is sent nothing, so the pass it left with runs out while
     * it is away: it counts from its last contact. The renewal that fell due meanwhile goes once the
     * member is heard from again.
     */
    @Test
    fun noRefreshWhilePartitioned() = runTest {
        val host = longHoldHost(admitting(GrantTerms(2.seconds)))
        host.admit(bob, bobKey, nonce = 1)
        host.advanceKeeping(setOf(bob), 1_100.milliseconds)
        val whileConnected = host.renewalsTo(bob)

        host.partition(bob, alive = emptySet())
        host.advanceKeeping(emptySet(), 5.seconds)
        val whileAway = host.renewalsTo(bob)
        val heldAfterAway = host.passesTo(bob).last()
        val stillHeld = host.member(bob)?.liveness
        val awayVerdict = trust().verify(heldAfterAway, bobKey.id, KEY_SEAT_ROOM.value, now())

        host.ping(bob)
        host.advanceKeeping(setOf(bob), 200.milliseconds)
        val backVerdict = trust().verify(host.passesTo(bob).last(), bobKey.id, KEY_SEAT_ROOM.value, now())

        assertAll(
            { assertEquals(1, whileConnected, "rig: Bob was renewed while connected") },
            { assertIs<Liveness.Partitioned>(stillHeld, "rig: the seat is still held, partitioned") },
            { assertEquals(whileConnected, whileAway, "nothing while partitioned") },
            { assertEquals<GrantVerdict>(GrantVerdict.Expired, awayVerdict, "the pass Bob left with ran out") },
            { assertEquals(whileAway + 1, host.renewalsTo(bob), "the renewal due meanwhile goes on contact") },
            { assertEquals<GrantVerdict>(GrantVerdict.Valid, backVerdict) },
        )
    }

    /**
     * A pass that never expires is never renewed: the host runs no renewal loop for it at all. Its
     * control, a member on finite terms beside it, has one.
     */
    @Test
    fun noExpiryGrantIsNeverRefreshed() = runTest {
        val friend = GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer)
        val stranger = GrantTerms(2.seconds)
        val admission = HostAdmission(
            AdmissionPolicy { request -> AdmissionDecision.Admit(if (request.key == bobKey.id) friend else stranger) },
            RevocationList.inMemory(),
        )
        val host = rawHost(joiners = setOf(bob, carol), admission = admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.advanceKeeping(setOf(bob, carol), 3.seconds)

        val room = host.room as SeamRoom
        val bobsPass = host.passesTo(bob).single()
        assertAll(
            { assertEquals(Grant.NO_EXPIRY, bobsPass.notAfter, "Bob's pass never expires") },
            { assertEquals<GrantScope>(GrantScope.AnyRoomOfIssuer, bobsPass.scope, "and is good in any room of this host") },
            { assertFalse(room.renewsPassForTest(bob), "no renewal loop for a pass that never expires") },
            { assertEquals(0, host.renewalsTo(bob)) },
            { assertTrue(room.renewsPassForTest(carol), "control: Carol's finite pass is renewed") },
            { assertEquals(3, host.renewalsTo(carol), "control: Carol was renewed each half lifetime") },
        )
    }

    /**
     * A device revoked after its membership was admitted keeps its seat — revoking does not evict
     * (Decided 12) — but gets no more passes (Decided 14, R2(i)), so the one it holds runs out. A
     * member beside it, not revoked, carries on being renewed.
     */
    @Test
    fun revokedPresentMemberStopsReceivingRefreshes() = runTest {
        val revocations = RevocationList.inMemory()
        val host = rawHost(joiners = setOf(bob, carol), admission = admitting(GrantTerms(2.seconds), revocations))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.advanceKeeping(setOf(bob, carol), 1_100.milliseconds)
        val beforeRevocation = host.renewalsTo(bob)

        revocations.revoke(bobKey.id, notBefore = now())
        host.advanceKeeping(setOf(bob, carol), 3.seconds)

        val room = host.room as SeamRoom
        val lastVerdict = trust(revocations).verify(host.passesTo(bob).last(), bobKey.id, KEY_SEAT_ROOM.value, now())
        assertAll(
            { assertEquals(1, beforeRevocation, "rig: Bob was renewed before the revocation") },
            { assertEquals(beforeRevocation, host.renewalsTo(bob), "no renewal after it") },
            { assertFalse(room.renewsPassForTest(bob), "Bob's renewals stopped") },
            { assertTrue(host.member(bob)?.liveness == Liveness.Connected, "Bob keeps his seat") },
            { assertEquals(4, host.renewalsTo(carol), "control: Carol is still renewed") },
            { assertEquals<GrantVerdict>(GrantVerdict.Revoked, lastVerdict, "the last pass Bob holds is void") },
        )
    }

    // ── What a joiner keeps ──────────────────────────────────────────────────

    /**
     * A joiner keeps a renewed pass only from its host, issued by the key that host's challenge
     * proved, for this device, and signed by that key. Every other pass is dropped and the one it
     * holds stays. The control, a genuine renewal, is kept.
     */
    @Test
    fun joinerIgnoresAGrantFromAnotherIssuer() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob, carol))
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, admission = admitting(GrantTerms(1.hours)))
        val bobStore = fakeKeyStore(1)
        val joiner = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()
        val bobId = bobStore.deviceKey().id
        val welcomed = bobStore.grants()
        val room = GrantScope.Room(KEY_SEAT_ROOM.value)

        suspend fun later(issuer: DeviceKey, subject: DidKey = bobId) =
            Grant.issue(issuer, subject, room, now() + 1, now() + 1.hours.inWholeMilliseconds)
        val hostDevice = fakeDeviceKey(HOST_DEVICE)
        // Names the host as issuer, but another key signed it.
        val forged = later(object : DeviceKey {
            override val id: DidKey = hostDevice.id

            @us.tractat.kuilt.identity.IdentityInternalApi
            override suspend fun sign(message: ByteArray): ByteArray = fakeDeviceKey(9).sign(message)
        })
        suspend fun renew(from: PeerId, wire: ByteArray) {
            board.inject(from = from, to = bob, message = AdmitMessage.GrantRenewal(wire))
            testScheduler.runCurrent()
        }
        renew(KEY_SEAT_HOST, later(fakeDeviceKey(9)).wire) // another issuer
        renew(KEY_SEAT_HOST, forged.wire) // the host's name, not its signature
        renew(KEY_SEAT_HOST, later(hostDevice, subject = fakeDidKey(7)).wire) // another device
        renew(KEY_SEAT_HOST, byteArrayOf(1, 2, 3)) // not a grant
        renew(carol, later(hostDevice).wire) // a genuine pass, from someone who is not the host
        // A co-joiner on a flat fabric whose stray challenge Bob answers, which proves Carol's own key
        // to Bob, then hands him a pass under that key. Only the host's renewals count.
        val bobsHello = board.sent(from = bob).filterIsInstance<AdmitMessage.Hello>().last()
        val carolDevice = fakeDeviceKey(9)
        val stray = Challenge.sign(
            carolDevice,
            KEY_SEAT_ROOM.value,
            hostPeerId = carol.value,
            joinerPeerId = bob.value,
            joinerNonce = assertNotNull(bobsHello.joinerNonce),
            hostNonce = ByteArray(Challenge.NONCE_SIZE) { 4 },
        )
        board.inject(from = carol, to = bob, message = AdmitMessage.Challenge(stray.wire))
        testScheduler.runCurrent()
        val bobAnsweredCarol = board.sent(from = bob, to = carol).any { it is AdmitMessage.Proof }
        renew(carol, later(carolDevice).wire)
        val afterForgeries = bobStore.grants()
        // An hour ahead, so the older renewal below is more than the skew allowance behind it.
        val genuine = Grant.issue(hostDevice, bobId, room, now() + 1.hours.inWholeMilliseconds, now() + 2.hours.inWholeMilliseconds)
        renew(KEY_SEAT_HOST, genuine.wire)
        val afterGenuine = bobStore.grants()
        // A genuine but older renewal, replayed or late: a renewal names no Hello, so it never
        // displaces the pass held, however far behind it is (Decided 21).
        renew(KEY_SEAT_HOST, later(hostDevice).wire)
        val afterOlderRenewal = bobStore.grants()

        assertAll(
            { assertEquals(1, welcomed.size, "rig: Bob holds the pass his Welcome carried") },
            { assertTrue(bobAnsweredCarol, "rig: Bob answered Carol's stray challenge, so he holds her key as proven") },
            { assertEquals(welcomed, afterForgeries, "none of them displaced it") },
            { assertEquals(listOf(genuine), afterGenuine, "control: a genuine renewal from the host is kept") },
            { assertEquals(listOf(genuine), afterOlderRenewal, "an older renewal, however far behind, leaves it held") },
        )
        joiner.leave()
        host.leave()
    }

    // ── A refusal's retryability (Decided 25) ────────────────────────────────

    /**
     * An app's own terminal refusal reaches the joiner terminal, though the joiner does not know the
     * code: before v8 it arrived as `Unknown(id)`, retryable, and a reconnecting device would have
     * said Hello again until its deadline.
     */
    @Test
    fun anAppsTerminalCodeReachesTheJoinerTerminal() = runTest {
        val banned = object : RejectCode {
            override val id = "banned"
            override val retryable = false
        }
        val loom = InMemoryLoom()
        val host = SeamRoomFactory(loom, backgroundScope, virtualClock(), fakeKeyStore(HOST_DEVICE))
            .host(us.tractat.kuilt.core.Pattern("room"), memberName = "Host", admission = HostAdmission(AdmissionPolicy { AdmissionDecision.Reject(banned) }, RevocationList.inMemory()))
        val joiner = SeamRoomFactory(loom, backgroundScope, virtualClock(), fakeKeyStore(1)).join(us.tractat.kuilt.core.InMemoryTag("room"), memberName = "Bob")

        val failed = joiner.events.filterIsInstance<MembershipEvent.AdmissionFailed>().first()
        val code = (failed.reason as? AdmissionFailure.Rejected)?.code

        assertAll(
            { assertEquals("banned", code?.id, "rig: the policy's code reached the joiner: $failed") },
            { assertEquals(false, code?.retryable, "and it is terminal there") },
        )
        joiner.leave()
        host.leave()
    }
}
