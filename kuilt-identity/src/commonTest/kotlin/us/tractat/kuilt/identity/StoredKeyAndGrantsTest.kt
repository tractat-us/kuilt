package us.tractat.kuilt.identity

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two records a store keeps, on every target: the key as a self-tested JWK, and the grants as one
 * strict record. The desktop store is the first user of both; the Apple and browser stores reuse them,
 * so a provider that cannot write or read a P-256 JWK shows up here, on its own target, first.
 */
class StoredKeyAndGrantsTest {

    @Test
    fun aKeyReadsBackAsTheSameKey() = runTest {
        val key = SoftwareDeviceKey.generate()
        val read = SoftwareDeviceKey.readBack(key.encodeJwk())
        val usable = assertIs<StoredKey.Usable>(read, "a key's own JWK reads back: $read")
        val verifies = key.id.verify(MESSAGE, usable.key.sign(MESSAGE))
        assertAll(
            { assertEquals(key.id, usable.key.id, "under the same name") },
            { assertTrue(verifies, "and signing as that key") },
        )
    }

    @Test
    fun theJwkCarriesBothHalves() = runTest {
        val jwk = SoftwareDeviceKey.generate().encodeJwk().decodeToString()
        assertAll(
            *listOf("\"kty\"", "\"crv\"", "\"x\"", "\"y\"", "\"d\"").map { field ->
                { assertTrue(field in jwk, "the JWK carries $field") }
            }.toTypedArray(),
        )
    }

    /**
     * A JWK whose `d` is another key's decodes on every provider and fails only the self-test. The
     * precondition is that the spliced JWK still decodes, which is what makes the refusal the self-test's.
     */
    @Test
    fun halvesOfDifferentKeysAreRefusedByTheSelfTest() = runTest {
        val named = SoftwareDeviceKey.generate().encodeJwk().decodeToString()
        val signer = SoftwareDeviceKey.generate().encodeJwk().decodeToString()
        val d = Regex("\"d\"\\s*:\\s*\"([^\"]+)\"")
        val spliced = named.replace(
            checkNotNull(d.find(named)).groupValues[1],
            checkNotNull(d.find(signer)).groupValues[1],
        )

        val read = SoftwareDeviceKey.readBack(spliced.encodeToByteArray())

        val refused = assertIs<StoredKey.Refused>(read)
        assertTrue("does not sign" in refused.reason, "refused by the self-test, not the decoder: ${refused.reason}")
    }

    @Test
    fun bytesThatAreNotAJwkAreRefused() = runTest {
        val whole = SoftwareDeviceKey.generate().encodeJwk()
        val refusals = listOf(ByteArray(0), byteArrayOf(1, 2, 3), whole.copyOf(whole.size / 2)).map {
            SoftwareDeviceKey.readBack(it)
        }
        assertAll(
            *refusals.map { read -> { assertIs<StoredKey.Refused>(read); Unit } }.toTypedArray(),
        )
    }

    @Test
    fun grantsRoundTripAsOneRecord() = runTest {
        val host = DeviceKey.generate()
        val me = DeviceKey.generate().id
        val grants = listOf(
            Grant.issue(host, me, GrantScope.Room("A"), T, T + HOUR),
            Grant.issue(host, me, GrantScope.AnyRoomOfIssuer, T, Grant.NO_EXPIRY),
        )
        assertAll(
            { assertEquals(grants, StoredGrants.decode(StoredGrants.encode(grants))) },
            { assertEquals(emptyList(), StoredGrants.decode(StoredGrants.encode(emptyList()))) },
        )
    }

    /** Every way a record can be damaged makes the whole of it unreadable; none yields part of it. */
    @Test
    fun aDamagedGrantsRecordIsUnreadable() = runTest {
        val grant = Grant.issue(DeviceKey.generate(), DeviceKey.generate().id, GrantScope.AnyRoomOfIssuer, T, T + HOUR)
        val record = StoredGrants.encode(listOf(grant))
        val wrongVersion = record.copyOf().also { it[0] = 2 }
        val truncated = record.copyOf(record.size - 1)
        val trailing = record + byteArrayOf(0)
        // Byte 6 is the low byte of the grant's own tag length (after the version byte and the 4-byte
        // record length): the record frames it intact, and the grant inside no longer decodes. A flipped
        // signature byte would not do — that grant still decodes, and only its signature check fails.
        val badGrant = record.copyOf().also { it[6] = (it[6] + 1).toByte() }
        assertAll(
            { assertEquals(listOf(grant), StoredGrants.decode(record), "precondition: the undamaged record reads") },
            { assertNull(StoredGrants.decode(ByteArray(0)), "empty") },
            { assertNull(StoredGrants.decode(wrongVersion), "another version") },
            { assertNull(StoredGrants.decode(truncated), "a length running past the end") },
            { assertNull(StoredGrants.decode(trailing), "bytes left over") },
            { assertNull(StoredGrants.decode(badGrant), "a grant that does not decode") },
        )
    }

    private companion object {
        const val T = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        val MESSAGE = "signed by a read-back key".encodeToByteArray()
    }
}
