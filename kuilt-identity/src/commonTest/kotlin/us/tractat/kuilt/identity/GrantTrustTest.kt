package us.tractat.kuilt.identity

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whether a presented grant is good in this host's room now. Every refusal test is the one valid
 * grant with exactly one thing wrong, so a verdict cannot be produced by some other defect riding
 * along; [validGrantIsValid] is the control every one of them is a single edit away from.
 */
class GrantTrustTest {
    @Test
    fun validGrantIsValid() = runTest {
        val f = fixture()
        assertEquals(GrantVerdict.Valid, f.verify(f.grant()))
    }

    @Test
    fun anotherHostsGrantIsUntrustedIssuer() = runTest {
        val f = fixture()
        val otherHost = DeviceKey.generate()
        assertEquals(GrantVerdict.UntrustedIssuer, f.verify(f.grant(issuer = otherHost)))
    }

    @Test
    fun damagedSignatureIsBadSignature() = runTest {
        val f = fixture()
        assertEquals(GrantVerdict.BadSignature, f.verify(f.grant().withDamagedSignature()))
    }

    @Test
    fun forgedTermsUnderTheHostsNameAreBadSignature() = runTest {
        // Names the host as issuer but is signed by someone else: the issuer check passes, so only
        // the signature can catch it.
        val f = fixture()
        val forger = Impostor(claimed = f.host.id, actual = DeviceKey.generate())
        assertEquals(GrantVerdict.BadSignature, f.verify(f.grant(issuer = forger)))
    }

    @Test
    fun grantForAnotherDeviceIsWrongSubject() = runTest {
        val f = fixture()
        val otherDevice = DeviceKey.generate().id
        assertEquals(GrantVerdict.WrongSubject, f.verify(f.grant(), subject = otherDevice))
    }

    @Test
    fun grantForAnotherRoomIsWrongScope() = runTest {
        val f = fixture()
        assertEquals(GrantVerdict.WrongScope, f.verify(f.grant(scope = GrantScope.Room("table-8"))))
    }

    @Test
    fun revokedSubjectIsRevoked() = runTest {
        val f = fixture()
        f.revocations.revoke(f.phone.id, notBefore = ISSUED_AT + 1)
        assertEquals(GrantVerdict.Revoked, f.verify(f.grant()))
    }

    @Test
    fun futureDatedGrantIsNotYetValid() = runTest {
        val f = fixture()
        val postDated = f.grant(issuedAt = ISSUED_AT + SKEW + 1)
        assertEquals(GrantVerdict.NotYetValid, f.verify(postDated, now = ISSUED_AT))
    }

    @Test
    fun skewAllowanceEndsExactlyAtTheAllowance() = runTest {
        // Checked at one fixed instant, so only the grant's date moves: dated exactly the allowance
        // ahead is the last one accepted, and one millisecond more is the first refused.
        val f = fixture()
        val now = ISSUED_AT
        val atTheAllowance = f.verify(f.grant(issuedAt = now + SKEW), now = now)
        val oneMsPast = f.verify(f.grant(issuedAt = now + SKEW + 1), now = now)
        assertAll(
            { assertEquals(5 * 60 * 1000L, SKEW, "the allowance is five minutes") },
            { assertEquals(GrantVerdict.Valid, atTheAllowance, "dated exactly the allowance ahead") },
            { assertEquals(GrantVerdict.NotYetValid, oneMsPast, "one millisecond past it") },
        )
    }

    @Test
    fun aPassFromAClockThatRanAheadIsRefusedUntilItsDate() = runTest {
        // The case the check exists for. The host's clock ran a year ahead when it issued the pass,
        // and has since been corrected. Revoking at the clock now sets a cutoff before the pass's
        // date, so the revocation alone does not touch it.
        val f = fixture()
        val now = ISSUED_AT
        val aYearOut = now + 365L * 24 * 3_600_000
        val pass = f.grant(issuedAt = aYearOut, notAfter = Grant.NO_EXPIRY)
        f.revocations.revoke(f.phone.id, notBefore = now + 1)
        val afterRevoking = f.verify(pass, now = now)
        // From the allowance before its date on, the pass is good again: this check does not void
        // it, and a host that must picks a cutoff past its date.
        val nearItsDate = f.verify(pass, now = aYearOut - SKEW)
        assertAll(
            { assertEquals(GrantVerdict.NotYetValid, afterRevoking, "refused, so the device is admitted afresh") },
            { assertEquals(GrantVerdict.Valid, nearItsDate, "the check bounds the skew; the cutoff still decides") },
        )
    }

    @Test
    fun expiredGrantIsExpired() = runTest {
        val f = fixture()
        assertEquals(GrantVerdict.Expired, f.verify(f.grant(), now = NOT_AFTER))
    }

    @Test
    fun expiryStartsExactlyAtNotAfter() = runTest {
        val f = fixture()
        val grant = f.grant()
        val lastGood = f.verify(grant, now = NOT_AFTER - 1)
        val firstBad = f.verify(grant, now = NOT_AFTER)
        assertAll(
            { assertEquals(GrantVerdict.Valid, lastGood, "the last good millisecond") },
            { assertEquals(GrantVerdict.Expired, firstBad, "the first bad one") },
        )
    }

    @Test
    fun revokedOnlyBeforeCutoff() = runTest {
        val f = fixture()
        val cutoff = ISSUED_AT + 10_000
        f.revocations.revoke(f.phone.id, notBefore = cutoff)
        val justBefore = f.verify(f.grant(issuedAt = cutoff - 1), now = cutoff)
        val atCutoff = f.verify(f.grant(issuedAt = cutoff), now = cutoff)
        assertAll(
            { assertEquals(GrantVerdict.Revoked, justBefore, "issued a millisecond before the cutoff") },
            { assertEquals(GrantVerdict.Valid, atCutoff, "issued at the cutoff: a fresh grant after a revocation works") },
        )
    }

    @Test
    fun revokingAnotherSubjectRevokesNothingHere() = runTest {
        val f = fixture()
        val stranger = DeviceKey.generate().id
        f.revocations.revoke(stranger, notBefore = NOT_AFTER)
        assertEquals(GrantVerdict.Valid, f.verify(f.grant()))
    }

    @Test
    fun anyRoomOfIssuerIsValidInEveryRoomOfThatHost() = runTest {
        val f = fixture()
        val pass = f.grant(scope = GrantScope.AnyRoomOfIssuer)
        val elsewhere = Fixture(DeviceKey.generate(), f.phone).grant(scope = GrantScope.AnyRoomOfIssuer)
        val verdicts = listOf(ROOM, "table-8", "").map { f.verify(pass, roomId = it) }
        val otherHosts = f.verify(elsewhere)
        assertAll(
            { assertEquals(List(3) { GrantVerdict.Valid }, verdicts, "this host's rooms, whatever their id") },
            { assertEquals(GrantVerdict.UntrustedIssuer, otherHosts, "another host's any-room pass") },
        )
    }

    @Test
    fun noExpiryNeverExpires() = runTest {
        val f = fixture()
        val forever = f.grant(notAfter = Grant.NO_EXPIRY)
        val nearTheEnd = f.verify(forever, now = Long.MAX_VALUE - 1)
        val atTheEnd = f.verify(forever, now = Long.MAX_VALUE)
        assertAll(
            { assertEquals(GrantVerdict.Valid, nearTheEnd) },
            { assertEquals(GrantVerdict.Valid, atTheEnd, "not even at the last instant") },
        )
    }

    @Test
    fun checkOrderIsFixed() = runTest {
        // The plan's named case: expired AND from an untrusted issuer reports the issuer.
        val f = fixture()
        val otherHost = DeviceKey.generate()
        assertEquals(GrantVerdict.UntrustedIssuer, f.verify(f.grant(issuer = otherHost), now = NOT_AFTER))
    }

    @Test
    fun everyDefectAtOnceReportsInTheFixedOrder() = runTest {
        // Start from a grant with all six defects, and clear them one at a time in the documented
        // order. Each step must report the first defect still present, so every adjacent pair of
        // checks is pinned, not only the first.
        val f = fixture()
        val stranger = DeviceKey.generate().id
        f.revocations.revoke(f.phone.id, notBefore = ISSUED_AT + 1)
        f.revocations.revoke(stranger, notBefore = ISSUED_AT + 1)
        val otherHost = DeviceKey.generate()

        suspend fun withDefects(vararg present: GrantVerdict): GrantVerdict {
            val issuer = if (GrantVerdict.UntrustedIssuer in present) otherHost else f.host
            val scope = if (GrantVerdict.WrongScope in present) GrantScope.Room("table-8") else GrantScope.Room(ROOM)
            val issuedAt = if (GrantVerdict.Revoked in present) ISSUED_AT else ISSUED_AT + 1
            var grant = f.grant(issuer = issuer, scope = scope, issuedAt = issuedAt)
            if (GrantVerdict.BadSignature in present) grant = grant.withDamagedSignature()
            val subject = if (GrantVerdict.WrongSubject in present) stranger else f.phone.id
            val now = if (GrantVerdict.Expired in present) NOT_AFTER else ISSUED_AT + 2
            return f.verify(grant, subject = subject, now = now)
        }

        val order = listOf(
            GrantVerdict.UntrustedIssuer,
            GrantVerdict.BadSignature,
            GrantVerdict.WrongSubject,
            GrantVerdict.WrongScope,
            GrantVerdict.Revoked,
            GrantVerdict.Expired,
        )
        val observed = order.indices.map { withDefects(*order.drop(it).toTypedArray()) }
        val noneLeft = withDefects()
        assertAll(
            { assertEquals(order, observed, "each step reports the first defect left") },
            { assertEquals(GrantVerdict.Valid, noneLeft, "and with none left, the grant is good") },
        )
    }

    @Test
    fun aFutureDateIsReportedAfterEveryDefectBeforeIt() = runTest {
        // The same walk as above for NotYetValid, which sits between Revoked and Expired. It cannot
        // share that walk: a grant dated after `now` has not expired, so the two never meet, and
        // NotYetValid's only neighbour that can be pinned is the check before it. The revocation is
        // toggled by which list the trust reads, so one grant date can carry both defects at once.
        val f = fixture()
        val stranger = DeviceKey.generate().id
        val otherHost = DeviceKey.generate()
        val now = ISSUED_AT
        val revoked = RevocationList.inMemory()
        revoked.revoke(f.phone.id, notBefore = now + SKEW + 2)
        revoked.revoke(stranger, notBefore = now + SKEW + 2)

        suspend fun withDefects(vararg present: GrantVerdict): GrantVerdict {
            val issuer = if (GrantVerdict.UntrustedIssuer in present) otherHost else f.host
            val scope = if (GrantVerdict.WrongScope in present) GrantScope.Room("table-8") else GrantScope.Room(ROOM)
            val issuedAt = if (GrantVerdict.NotYetValid in present) now + SKEW + 1 else now
            var grant = f.grant(issuer = issuer, scope = scope, issuedAt = issuedAt)
            if (GrantVerdict.BadSignature in present) grant = grant.withDamagedSignature()
            val subject = if (GrantVerdict.WrongSubject in present) stranger else f.phone.id
            val revocations = if (GrantVerdict.Revoked in present) revoked else RevocationList.inMemory()
            return GrantTrust(f.host.id, revocations).verify(grant, subject, ROOM, now)
        }

        val order = listOf(
            GrantVerdict.UntrustedIssuer,
            GrantVerdict.BadSignature,
            GrantVerdict.WrongSubject,
            GrantVerdict.WrongScope,
            GrantVerdict.Revoked,
            GrantVerdict.NotYetValid,
        )
        val observed = order.indices.map { withDefects(*order.drop(it).toTypedArray()) }
        val noneLeft = withDefects()
        assertAll(
            { assertEquals(order, observed, "each step reports the first defect left") },
            { assertEquals(GrantVerdict.Valid, noneLeft, "and with none left, the grant is good") },
        )
    }

    /** One host, one device and the host's revocation list; every grant defaults to the good one. */
    private class Fixture(val host: DeviceKey, val phone: DeviceKey) {
        val revocations = RevocationList.inMemory()
        val trust = GrantTrust(host.id, revocations)

        suspend fun grant(
            issuer: DeviceKey = host,
            scope: GrantScope = GrantScope.Room(ROOM),
            issuedAt: Long = ISSUED_AT,
            notAfter: Long = NOT_AFTER,
        ): Grant = Grant.issue(issuer, phone.id, scope, issuedAt, notAfter)

        suspend fun verify(grant: Grant, subject: DidKey = phone.id, roomId: String = ROOM, now: Long = ISSUED_AT + 60_000): GrantVerdict =
            trust.verify(grant, subject, roomId, now)
    }

    private suspend fun fixture(): Fixture = Fixture(host = DeviceKey.generate(), phone = DeviceKey.generate())

    private companion object {
        const val ROOM = "table-7"
        const val ISSUED_AT = 1_700_000_000_000L
        const val NOT_AFTER = ISSUED_AT + 3_600_000L
        const val SKEW = GrantTrust.CLOCK_SKEW_ALLOWANCE_MILLIS
    }
}

/** This grant with the last byte of its signature changed: same terms, no longer the issuer's signature. */
internal fun Grant.withDamagedSignature(): Grant {
    val bytes = wire
    bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
    return checkNotNull(Grant.decode(bytes)) { "damaging a signature byte must leave the layout decodable" }
}
