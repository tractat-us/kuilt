@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.GrantTrust
import us.tractat.kuilt.identity.GrantVerdict
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmissionDecision
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.GrantIssuer
import us.tractat.kuilt.session.admit.GrantTerms
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A renewal that fails to sign or to send is tried again with a backoff that closes in on the end of
 * the last pass the member was sent, rather than left until the next period (#3711). Every timing is
 * virtual, and every rig counts the failures it caused and the times of the tries that followed.
 */
class GrantRenewalRetryTest {
    private val bob = PeerId("bob")
    private val bobTab = PeerId("bob-tab")
    private val bobAgain = PeerId("bob-again")
    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val hostKey: DidKey = fakeDidKey(HOST_DEVICE)

    /** A term whose period, 20 s, is long against the first retry waits, so the tries are told apart. */
    private val term: Duration = 40.seconds
    private val periodMs: Long = assertNotNull(GrantIssuer.refreshPeriodMillis(GrantTerms(term)))

    private fun admitting(revocations: RevocationList = RevocationList.inMemory()) =
        HostAdmission(AdmissionPolicy { AdmissionDecision.Admit(GrantTerms(term)) }, revocations)

    private suspend fun trust() = GrantTrust(hostKey, RevocationList.inMemory(), fakeKeyStore(HOST_DEVICE).keyScheme)

    private fun RawHost.renewalsTo(peer: PeerId): List<AdmitMessage.GrantRenewal> = sentTo(peer).filterIsInstance<AdmitMessage.GrantRenewal>()

    /**
     * A host store whose key refuses to sign every pass for which [fails] answers true, numbered from
     * 0 for the Welcome's: a platform signer failing. [attempts] is the virtual time of every pass it
     * was asked to sign, refused or not.
     */
    private class FlakySigner(
        private val inner: DeviceKeyStore,
        private val now: () -> Long,
        private val fails: (attempt: Int) -> Boolean,
    ) : DeviceKeyStore by inner {
        val attempts = mutableListOf<Long>()
        var refused = 0

        override suspend fun deviceKey(): DeviceKey {
            val key = inner.deviceKey()
            return object : DeviceKey {
                override val id: DidKey = key.id

                @us.tractat.kuilt.identity.IdentityInternalApi
                override suspend fun sign(message: ByteArray): ByteArray {
                    if ("kuilt/grant" in message.decodeToString()) {
                        val attempt = attempts.size
                        attempts += now()
                        if (fails(attempt)) {
                            refused++
                            error("the platform signer failed")
                        }
                    }
                    return key.sign(message)
                }
            }
        }
    }

    private fun TestScope.flaky(fails: (Int) -> Boolean) = FlakySigner(fakeKeyStore(HOST_DEVICE), { testScheduler.currentTime }, fails)

    /**
     * The wait itself: doubling from a second, never past the period, at most a quarter of what is
     * left on the pass last sent but not under a quarter second, the plain doubling once a try would
     * land past that pass's end, and never under 1 ms. No pass sent, a pass that never ends and one
     * already over set no cap of their own.
     */
    @Test
    fun theRetryWaitDoublesAndClosesInOnTheLastPassSent() {
        fun wait(retry: Int, now: Long = 0, sentNotAfter: Long? = null, period: Long = 20_000) =
            GrantIssuer.retryDelayMillis(retry, now, sentNotAfter, period)
        assertAll(
            { assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 20_000L, 20_000L), (0..6).map { wait(it) }, "doubling, capped at the period") },
            { assertEquals(20_000L, wait(Int.MAX_VALUE), "however many tries, never past the period") },
            { assertEquals(2_500L, wait(5, now = 50_000, sentNotAfter = 60_000), "a quarter of the 10 s left") },
            { assertEquals(1_000L, wait(0, now = 50_000, sentNotAfter = 60_000), "the doubling still rules while it is the shorter") },
            { assertEquals(GrantIssuer.RETRY_MIN_DELAY_MS, wait(5, now = 59_500, sentNotAfter = 60_000), "the cap stops at a quarter second, not a quarter of the 500 ms left") },
            { assertEquals(20_000L, wait(5, now = 59_800, sentNotAfter = 60_000), "a try that would land past the end waits the doubling") },
            { assertEquals(20_000L, wait(5, now = 61_000, sentNotAfter = 60_000), "a pass already over sets no cap") },
            { assertEquals(20_000L, wait(5, sentNotAfter = us.tractat.kuilt.identity.Grant.NO_EXPIRY), "nor does one that never ends") },
            { assertEquals(1L, wait(0, period = 1), "a 1 ms period waits 1 ms") },
        )
    }

    /**
     * Eight renewals in a row fail to sign, and the ninth try goes. The tries start a second after
     * the failed renewal and double, each waits at most a quarter of what is left on the pass Bob was
     * last sent, so the waits close up as it nears its end, and all of them land before it runs out.
     * The one that goes leaves Bob holding a fresh pass.
     */
    @Test
    fun aRenewalThatFailsToSignIsRetriedClosingInOnTheLastPassSent() = runTest {
        val failures = 8
        val store = flaky { it in 1..failures }
        val host = rawHost(joiners = setOf(bob), store = store, admission = admitting())
        val admittedAt = testScheduler.currentTime
        host.admit(bob, bobKey, nonce = 1)
        val welcomed = host.passesTo(bob).single()

        host.advanceKeeping(setOf(bob), (welcomed.notAfter - admittedAt).milliseconds)

        val tries = store.attempts.drop(1)
        val retries = tries.take(failures + 1)
        val waits = retries.zipWithNext { a, b -> b - a }
        val renewals = host.renewalsTo(bob)
        val renewed = renewals.singleOrNull()?.let { assertNotNull(us.tractat.kuilt.identity.Grant.decode(it.grant)) }
        val verdict = renewed?.let { trust().verify(it, bobKey.id, KEY_SEAT_ROOM.value, testScheduler.currentTime) }
        assertAll(
            { assertEquals(failures, store.refused, "rig: the signer refused $failures renewals") },
            { assertEquals(admittedAt + periodMs, tries.first(), "rig: the first renewal fell due a period in") },
            { assertEquals(failures + 1, tries.size, "every failure is tried again, and the try that goes is the last before Bob's pass runs out: $tries") },
            { assertEquals(listOf(1_000L, 2_000L, 4_000L), waits.take(3), "the waits start at a second and double") },
            {
                val overCap = retries.zipWithNext().filter { (a, b) -> b - a > (welcomed.notAfter - a) / GrantIssuer.RETRY_SHARE_OF_REMAINING }
                assertEquals(emptyList(), overCap, "no wait exceeds a quarter of what is left on Bob's pass")
            },
            { assertEquals(waits.drop(3).sortedDescending(), waits.drop(3), "past the doubling, the waits close up: $waits") },
            { assertTrue(waits.size > 4 && waits.last() < waits[3], "and the last is the closest: $waits") },
            { assertTrue(retries.all { it < welcomed.notAfter }, "every try lands before Bob's pass runs out") },
            { assertEquals(retries.last(), renewed?.issuedAt, "the try that goes is the pass Bob is sent") },
            { assertEquals<GrantVerdict?>(GrantVerdict.Valid, verdict, "and he holds a fresh pass") },
        )
        host.room.leave()
    }

    /**
     * A Welcome whose pass failed to sign leaves Bob holding none, so its first try is a retry's, a
     * second in, not a whole period away. That failure counts like any other: when the first try fails
     * too, the next waits two seconds, not one again.
     */
    @Test
    fun aWelcomeWithoutItsPassIsRetriedWithinASecond() = runTest {
        val store = flaky { it <= 1 }
        val host = rawHost(joiners = setOf(bob), store = store, admission = admitting())
        val admittedAt = testScheduler.currentTime
        host.admit(bob, bobKey, nonce = 1)
        val direct = host.sentTo(bob).filterIsInstance<AdmitMessage.Welcome>().single { it.membership != null }

        host.advanceKeeping(setOf(bob), (GrantIssuer.RETRY_FIRST_DELAY_MS * 3).milliseconds)

        assertAll(
            { assertEquals(2, store.refused, "rig: the Welcome's pass and the first try failed to sign") },
            { assertEquals(null, direct.grant, "rig: so the Welcome carries none") },
            { assertTrue(GrantIssuer.RETRY_FIRST_DELAY_MS < periodMs, "rig: a retry's wait is shorter than a period") },
            {
                val first = GrantIssuer.RETRY_FIRST_DELAY_MS
                assertEquals(listOf(admittedAt, admittedAt + first, admittedAt + 3 * first), store.attempts, "tried again a second in, then two seconds after that")
            },
            { assertEquals(1, host.renewalsTo(bob).size, "and the pass reaches Bob") },
        )
        host.room.leave()
    }

    /** A seam whose sends of a [AdmitMessage.GrantRenewal] throw while [failing] lasts: a link refusing frames. */
    private class RefusesRenewals(private val inner: FakeSeam, var failing: Int) : Seam by inner {
        var refused = 0

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (failing > 0 && AdmitMessage.decode(payload) is AdmitMessage.GrantRenewal) {
                failing--
                refused++
                throw IllegalStateException("the link refused the frame")
            }
            inner.sendTo(peer, payload)
        }
    }

    /**
     * A renewal whose send throws is tried again the same way, signed afresh, and the try that goes
     * reaches Bob. The waits are capped by the pass Bob was last *sent*, the Welcome's, since none
     * of the refused renewals reached him.
     */
    @Test
    fun aRenewalThatFailsToSendIsRetried() = runTest {
        val fake = FakeSeam(selfId = KEY_SEAT_HOST, initialPeers = setOf(KEY_SEAT_HOST, bob))
        val link = RefusesRenewals(fake, failing = 3)
        val store = flaky { false }
        val room = SeamRoomFactory(
            loom = InMemoryLoom(),
            scope = backgroundScope,
            clock = virtualClock(),
            deviceKeyStore = store,
            heartbeatConfig = KEY_SEAT_HEARTBEAT,
            admitTimeout = KEY_SEAT_ADMIT_TIMEOUT,
        ).adopt(link, AdoptRole.Host(admitting(), roomId = KEY_SEAT_ROOM), memberName = "Host")
        testScheduler.runCurrent()
        val host = RawHost(this, fake, room, ObservedHold(), EventLog(room, backgroundScope), hostKey)
        val admittedAt = testScheduler.currentTime
        host.admit(bob, bobKey, nonce = 1)

        host.advanceKeeping(setOf(bob), (periodMs + 7_000).milliseconds)

        val renewed = host.renewalsTo(bob).map { assertNotNull(us.tractat.kuilt.identity.Grant.decode(it.grant)) }
        val first = admittedAt + periodMs
        assertAll(
            { assertEquals(3, link.refused, "rig: the link refused three renewals") },
            { assertEquals(listOf(admittedAt, first, first + 1_000, first + 3_000, first + 7_000), store.attempts, "each refusal is signed and tried again, a second, then two, then four later") },
            { assertEquals(listOf(first + 7_000), renewed.map { it.issuedAt }, "the try that goes reaches Bob") },
        )
        room.leave()
    }

    /** Whether the last of [tries] before [end] lands within a quarter second of it: the retries closed in on it. */
    private fun closesInOn(end: Long, tries: List<Long>): Boolean =
        tries.filter { it < end }.maxOrNull()?.let { end - it <= GrantIssuer.RETRY_MIN_DELAY_MS } == true

    /**
     * The cap is the pass Bob was last *sent*, not the last one signed: every renewal's send throws,
     * so Bob still holds only the Welcome's, and the tries close in on that one's end. Counting an
     * unsent renewal's end would set the cap a term later, and the tries would stop a long wait short.
     */
    @Test
    fun theRetriesCloseInOnThePassLastSentNotTheLastSigned() = runTest {
        val fake = FakeSeam(selfId = KEY_SEAT_HOST, initialPeers = setOf(KEY_SEAT_HOST, bob))
        val link = RefusesRenewals(fake, failing = Int.MAX_VALUE)
        val store = flaky { false }
        val room = hostOver(link, store)
        val host = RawHost(this, fake, room, ObservedHold(), EventLog(room, backgroundScope), hostKey)
        host.admit(bob, bobKey, nonce = 1)
        val welcomed = host.passesTo(bob).single()

        host.advanceKeeping(setOf(bob), (welcomed.notAfter - testScheduler.currentTime).milliseconds + 1.seconds)

        val tries = store.attempts.drop(1)
        assertAll(
            { assertTrue(link.refused >= 10, "rig: the link refused every renewal: ${link.refused}") },
            { assertEquals(emptyList(), host.renewalsTo(bob), "rig: none reached Bob") },
            { assertTrue(closesInOn(welcomed.notAfter, tries), "the tries close in on the end of the Welcome's pass: $tries") },
            { assertTrue(tries.zipWithNext { x, y -> y - x }.all { it >= GrantIssuer.RETRY_MIN_DELAY_MS }, "and never bunch closer than a quarter second: $tries") },
        )
        room.leave()
    }

    /**
     * A Welcome whose send threw delivered no pass, so it sets no cap: the retries after it keep to
     * the plain doubling, and never close up on the end of a pass Bob does not hold.
     */
    @Test
    fun aWelcomeThatFailedToSendSetsNoCap() = runTest {
        val fake = FakeSeam(selfId = KEY_SEAT_HOST, initialPeers = setOf(KEY_SEAT_HOST, bob))
        val link = RefusesDirectWelcome(fake)
        val store = flaky { it >= 1 }
        val room = hostOver(link, store)
        val host = RawHost(this, fake, room, ObservedHold(), EventLog(room, backgroundScope), hostKey)
        host.admit(bob, bobKey, nonce = 1)

        host.advanceKeeping(setOf(bob), term * 2)

        val tries = store.attempts.drop(1)
        val waits = tries.zipWithNext { a, b -> b - a }
        assertAll(
            { assertEquals(1, link.refused, "rig: the direct Welcome's send threw") },
            { assertTrue(waits.size >= 6, "rig: the renewals kept failing: $tries") },
            { assertEquals(waits.sorted(), waits, "the waits never shrink: $waits") },
            { assertEquals(periodMs, waits.last(), "they settle at the period: $waits") },
        )
        room.leave()
    }

    /**
     * A reseating whose own Welcome failed to sign still closes its retries in on the pass the device
     * holds (#3711 review). A restore keeps the seat's membership and the pass it was sent; a takeover
     * holds the pass it had under its old id; a return holds the pass it presented. Each Welcome's pass
     * fails to sign, and so does every try after it.
     */
    @Test
    fun aReseatingWhosePassFailedClosesInOnThePassTheDeviceHolds() = runTest {
        suspend fun reseated(how: String): Pair<Long, List<Long>> {
            val store = flaky { it >= 1 }
            val host = rawHost(joiners = setOf(bob, bobTab, bobAgain), store = store, admission = admitting())
            host.admit(bob, bobKey, nonce = 1)
            val held = host.passesTo(bob).single()
            val seat = when (how) {
                "restore" -> { host.partition(bob, alive = emptySet()); host.admit(bob, bobKey, nonce = 2); bob }
                "takeover" -> { host.partition(bob, alive = emptySet()); host.admit(bobTab, bobKey, nonce = 2); bobTab }
                else -> { host.say(bob, AdmitMessage.Goodbye); host.admit(bobAgain, bobKey, nonce = 2, grants = listOf(held)); bobAgain }
            }
            val reseatedAt = testScheduler.currentTime
            assertTrue(host.member(seat)?.liveness == Liveness.Connected, "rig: the $how seated the device as ${seat.value}")
            assertEquals(2, store.attempts.size, "rig: the $how's Welcome pass was tried and refused")
            host.advanceKeeping(setOf(seat), (held.notAfter - testScheduler.currentTime).milliseconds + 1.seconds)
            host.room.leave()
            return held.notAfter to store.attempts.drop(1).filter { it >= reseatedAt }
        }
        val restore = reseated("restore")
        val takeover = reseated("takeover")
        val back = reseated("return")
        assertAll(
            { assertTrue(closesInOn(restore.first, restore.second), "a restore closes in on the pass it was sent: ${restore.second}") },
            { assertTrue(closesInOn(takeover.first, takeover.second), "a takeover closes in on the pass it had: ${takeover.second}") },
            { assertTrue(closesInOn(back.first, back.second), "a return closes in on the pass it presented: ${back.second}") },
        )
    }

    /** A seam whose send of the direct `Welcome` throws, once: the burst's pass never reached the joiner. */
    private class RefusesDirectWelcome(private val inner: FakeSeam) : Seam by inner {
        var refused = 0

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            val message = AdmitMessage.decode(payload)
            if (refused == 0 && message is AdmitMessage.Welcome && message.membership != null) {
                refused++
                throw IllegalStateException("the link refused the frame")
            }
            inner.sendTo(peer, payload)
        }
    }

    private suspend fun TestScope.hostOver(link: Seam, store: DeviceKeyStore): Room {
        val room = SeamRoomFactory(
            loom = InMemoryLoom(),
            scope = backgroundScope,
            clock = virtualClock(),
            deviceKeyStore = store,
            heartbeatConfig = KEY_SEAT_HEARTBEAT,
            admitTimeout = KEY_SEAT_ADMIT_TIMEOUT,
        ).adopt(link, AdoptRole.Host(admitting(), roomId = KEY_SEAT_ROOM), memberName = "Host")
        testScheduler.runCurrent()
        return room
    }

    /**
     * A chain of retries ends with the membership: Bob says goodbye while his renewals keep failing,
     * and no try follows. Its control is the tries before he left.
     */
    @Test
    fun aMemberThatLeavesStopsTheRetries() = runTest {
        val store = flaky { it >= 1 }
        val host = rawHost(joiners = setOf(bob), store = store, admission = admitting())
        host.admit(bob, bobKey, nonce = 1)
        host.advanceKeeping(setOf(bob), (periodMs + 1_500).milliseconds)
        val beforeLeaving = store.attempts.size

        host.say(bob, AdmitMessage.Goodbye)
        testScheduler.advanceTimeBy(term)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(3, beforeLeaving, "rig: the Welcome's pass, the failed renewal and one retry") },
            { assertEquals(beforeLeaving, store.attempts.size, "no try after Bob left") },
            { assertFalse((host.room as SeamRoom).renewsPassForTest(bob), "his renewals stopped") },
        )
        host.room.leave()
    }

    /**
     * A chain of retries ends when the device is revoked: the next try finds it may not be issued a
     * pass and stops, without asking the signer.
     */
    @Test
    fun aRevokedMemberStopsTheRetries() = runTest {
        val revocations = RevocationList.inMemory()
        val store = flaky { it >= 1 }
        val host = rawHost(joiners = setOf(bob), store = store, admission = admitting(revocations))
        host.admit(bob, bobKey, nonce = 1)
        host.advanceKeeping(setOf(bob), (periodMs + 1_500).milliseconds)
        val beforeRevoking = store.attempts.size

        revocations.revoke(bobKey.id, notBefore = testScheduler.currentTime)
        host.advanceKeeping(setOf(bob), term)

        assertAll(
            { assertEquals(3, beforeRevoking, "rig: the Welcome's pass, the failed renewal and one retry") },
            { assertEquals(beforeRevoking, store.attempts.size, "no try after the revocation") },
            { assertFalse((host.room as SeamRoom).renewsPassForTest(bob), "his renewals stopped") },
            { assertTrue(host.member(bob)?.liveness == Liveness.Connected, "rig: Bob keeps his seat") },
        )
        host.room.leave()
    }

    /**
     * A chain of retries ends when a partitioned member's hold runs out: the room lets the seat go,
     * and with it the membership's renewals.
     */
    @Test
    fun aMemberPartitionedPastItsHoldStopsTheRetries() = runTest {
        val store = flaky { it >= 1 }
        val host = rawHost(joiners = setOf(bob), store = store, admission = admitting())
        host.admit(bob, bobKey, nonce = 1)
        host.advanceKeeping(setOf(bob), (periodMs + 1_500).milliseconds)
        val beforeSilence = store.attempts.size

        host.advanceKeeping(emptySet(), PARTITION_DETECTED + KEY_SEAT_HEARTBEAT.reconnectWindow + 1.seconds)
        val seatAfter = host.member(bob)
        host.advanceKeeping(emptySet(), term)

        assertAll(
            { assertEquals(3, beforeSilence, "rig: the Welcome's pass, the failed renewal and one retry") },
            { assertEquals(null, seatAfter, "rig: the host let Bob's seat go") },
            { assertTrue(store.attempts.size <= beforeSilence + 1, "at most the try already due before the host saw Bob go quiet: ${store.attempts}") },
            { assertFalse((host.room as SeamRoom).renewsPassForTest(bob), "his renewals stopped") },
        )
        host.room.leave()
    }
}
