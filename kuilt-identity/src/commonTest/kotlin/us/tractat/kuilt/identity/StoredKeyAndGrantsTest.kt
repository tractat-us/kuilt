package us.tractat.kuilt.identity

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.cancellation.CancellationException
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
     * A JWK whose `d` is another key's is refused on every target — and *where* it is refused differs,
     * which is why this runs on all of them. The JDK and CryptoKit decode the pair and the self-test
     * refuses it; WebCrypto checks the pair itself on import, so there the decoder refuses it first.
     * CryptoKit is the one that made this a cross-target property: its private decoder derives the
     * public half from `d`, so a self-test against the provider's public half passed there, and only
     * `iosSimulatorArm64` saw it (#3224). [FileDeviceKeyStoreTest] pins the self-test's own reason on
     * the JVM.
     */
    @Test
    fun halvesOfDifferentKeysAreRefused() = runTest {
        val named = SoftwareDeviceKey.generate().encodeJwk().decodeToString()
        val signer = SoftwareDeviceKey.generate().encodeJwk().decodeToString()
        val d = Regex("\"d\"\\s*:\\s*\"([^\"]+)\"")
        val spliced = named.replace(
            checkNotNull(d.find(named)).groupValues[1],
            checkNotNull(d.find(signer)).groupValues[1],
        )

        val read = SoftwareDeviceKey.readBack(spliced.encodeToByteArray())

        assertIs<StoredKey.Refused>(read, "a pair whose halves belong to different keys is refused: $read")
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

    /**
     * A fault in the provider while it signs the self-test says nothing about the stored bytes, so it
     * throws [IllegalStateException] instead of condemning the key. A store that read it as damage
     * would replace a sound key every time the provider stumbled. A cancellation the provider mints
     * while this job is alive is such a fault too, and is reported the same way rather than ending the
     * caller silently.
     */
    @Test
    fun aProviderFaultWhileSigningThrowsRatherThanRefusing() = runTest {
        val jwk = SoftwareDeviceKey.generate().encodeJwk()
        val faults = listOf<Throwable>(RuntimeException("signer unavailable"), CancellationException("minted by the provider"))
        val outcomes = faults.map { fault ->
            try {
                "returned ${SoftwareDeviceKey.readBack(jwk) { _, _ -> throw fault }}"
            } catch (e: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                "threw ${e::class.simpleName}"
            }
        }
        val unfaulted = SoftwareDeviceKey.readBack(jwk)
        assertAll(
            { assertIs<StoredKey.Usable>(unfaulted, "precondition: the key itself reads back") },
            *outcomes.zip(faults).map { (outcome, fault) ->
                { assertEquals("threw IllegalStateException", outcome, "a ${fault::class.simpleName} while signing") }
            }.toTypedArray(),
        )
    }

    /**
     * A `d` that is not a P-256 scalar — zero, or the group order — is damage in the stored bytes and is
     * refused like any other, whatever the provider would do with it at signing time.
     */
    @Test
    fun aPrivatePartOutOfRangeIsRefused() = runTest {
        val jwk = SoftwareDeviceKey.generate().encodeJwk().decodeToString()
        val d = checkNotNull(Regex("\"d\"\\s*:\\s*\"([^\"]+)\"").find(jwk)).groupValues[1]
        val zero = jwk.replace(d, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val order = jwk.replace(d, "_____wAAAAD__________7zm-q2nF56E87nKwvxjJVE")
        val reads = listOf(zero, order).map { SoftwareDeviceKey.readBack(it.encodeToByteArray()) }
        assertAll(
            *reads.map { read -> { assertIs<StoredKey.Refused>(read, "$read"); Unit } }.toTypedArray(),
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
