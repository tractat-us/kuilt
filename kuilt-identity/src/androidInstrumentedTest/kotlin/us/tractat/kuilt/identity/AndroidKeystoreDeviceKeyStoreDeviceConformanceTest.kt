package us.tractat.kuilt.identity

import android.os.Build
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import dev.whyoleg.cryptography.algorithms.EC
import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.KeyWriteInterruption
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.UnreadableGrants
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.UnreadableKey
import us.tractat.kuilt.store.FileChannelDurableStore
import us.tractat.kuilt.store.StoreKey
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest

/**
 * [AndroidKeystoreDeviceKeyStore] over the device's **real** `AndroidKeyStore` ([AndroidKeystoreBackend])
 * against the whole [DeviceKeyStoreConformanceSuite], at the device's own Android version (#3226). The
 * unit-test subclass runs the same suite over `FakeKeystore`; this one is where its claims meet a Keystore.
 *
 * Each damage hook is a fault the real Keystore really holds, never a fake standing in:
 *
 * - **corrupt key:** a key that needs the screen lock, planted under the alias, then the screen lock
 *   removed, so the Keystore is done with it for good. Needs a device with no screen lock of its own,
 *   and is skipped elsewhere. A corrupted key *blob* is a different fault: Decided 29 waits out an hour
 *   on the answer a real Keystore gives it, which this property's "replaced at once" cannot express, so
 *   [KeystoreFaultDeviceTest] measures it;
 * - **mismatched key:** the certificate of one Keystore key imported beside a different private key;
 * - **cleared key:** the alias deleted;
 * - **interrupted write** (`AtomicMedium`): as the unit-test subclass gives it;
 * - **corrupt grants** and **unreadable grants:** the grants file, as the unit-test subclass rigs it;
 * - **unreadable key** (`Rigged`): the Keystore daemon stopped. Needs root, so it runs on an emulator's
 *   `google_apis` image and is skipped on a retail phone. On Android 11 and earlier the store sees "no
 *   key" there, and this property is the check on its made-here marker.
 */
class AndroidKeystoreDeviceKeyStoreDeviceConformanceTest : DeviceKeyStoreConformanceSuite() {
    private val aliases = mutableListOf<String>()
    private val directories = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        if (DeviceShell.hasRoot) KeystoreFaults.startKeystore()
        aliases.forEach(KeystoreFaults::deleteTolerantly)
        directories.forEach { it.deleteRecursively() }
    }

    override suspend fun newFixture(): DeviceKeyStoreFixture {
        // As the unit-test subclass, for the reason it gives: below 33 every failure is of unknown cause
        // and waits out an hour, and on Android 11 and earlier a cleared key under the store's marker is
        // a failure too, so the suite's damage properties do not hold as it states them. Measured on an
        // Android 11 emulator, the cleared-key and interrupted-write rows red for exactly that reason.
        // KeystoreFaultDeviceTest covers those versions.
        assumeTrue("the suite's damage properties hold as written from Android 13 (API 33)", Build.VERSION.SDK_INT >= KEYSTORE_EXCEPTION_API)
        val alias = "kuilt-identity-device-test-${UUID.randomUUID()}".also(aliases::add)
        val directory = Files.createTempDirectory("kuilt-keystore-device").toFile().also(directories::add)
        return DeviceKeyStoreFixture.Durable(
            reopen = { beforeKeyIsMade ->
                AndroidKeystoreDeviceKeyStore(alias, directory, AndroidKeystoreBackend, Build.VERSION.SDK_INT, { NOW }, beforeKeyIsMade = beforeKeyIsMade)
            },
            corruptKey = { KeystoreFaults.plantKeyInvalidatedByRemovingTheScreenLock(alias) },
            plantMismatchedKey = { plantMismatchedKey(alias) },
            clearKey = {
                check(AndroidKeystoreBackend.getCertificatePublicKey(alias) != null) { "precondition: there was a key to clear" }
                AndroidKeystoreBackend.delete(alias)
            },
            interruptKeyWrite = {
                KeyWriteInterruption.AtomicMedium(
                    "A crash while the Keystore makes a key can leave nothing beside the held key: the Keystore holds " +
                        "one entry per alias, and the store writes only when it holds no usable key.",
                )
            },
            corruptGrants = {
                FileChannelDurableStore(directory).write(AndroidKeystoreDeviceKeyStore.GRANTS, byteArrayOf(9, 9, 9))
            },
            makeKeyUnreadable = {
                KeystoreFaults.stopKeystore()
                UnreadableKey.Rigged { KeystoreFaults.startKeystore() }
            },
            makeGrantsUnreadable = { unreadableGrantsRecord(directory) },
        )
    }

    /**
     * Under [alias], a software P-256 private key beside the certificate of a different key the Keystore
     * made, imported as one entry, so it loads and fails only the self-test. Returns the name the
     * certificate claims.
     */
    private suspend fun plantMismatchedKey(alias: String): DidKey {
        val donor = "$alias-donor"
        AndroidKeystoreBackend.generate(donor)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val certificate: Certificate = checkNotNull(keyStore.getCertificate(donor)) { "rig: the donor key has a certificate" }
        AndroidKeystoreBackend.delete(donor)
        val signer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        keyStore.setEntry(
            alias,
            KeyStore.PrivateKeyEntry(signer.private, arrayOf(certificate)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build(),
        )
        return independentNameOf(certificate.publicKey)
    }

    /** As the unit-test subclass: every permission off the grants record, the directory still writable. */
    private fun unreadableGrantsRecord(directory: File): UnreadableGrants {
        val record = directory.toPath().resolve(AndroidKeystoreDeviceKeyStore.GRANTS.name)
        check(Files.exists(record, LinkOption.NOFOLLOW_LINKS)) { "precondition: a grants record at $record to fault" }
        Files.setPosixFilePermissions(record, emptySet())
        check(!Files.isReadable(record)) { "rig: $record is still readable after chmod 000; the fault did not take" }
        return UnreadableGrants(
            probe = { FileChannelDurableStore(directory).read(AndroidKeystoreDeviceKeyStore.GRANTS) },
            probeWrite = { FileChannelDurableStore(directory).write(PROBE_WRITE, byteArrayOf(1)) },
            restore = {
                val rewritten = !Files.exists(record, LinkOption.NOFOLLOW_LINKS) || Files.isReadable(record)
                if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(record, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                }
                rewritten
            },
        )
    }

    private companion object {
        /** A fixed clock: no rig here is of unknown cause on the versions it runs on. */
        const val NOW = 1_700_000_000_000L

        val PROBE_WRITE = StoreKey("probe-write")
    }
}

/** The name [publicKey] should carry, worked out apart from the store's `nameOf`, as the unit tests do. */
internal suspend fun independentNameOf(publicKey: PublicKey): DidKey {
    val decoded = algorithms().ecdsa.publicKeyDecoder(EC.Curve.P256)
        .decodeFromByteArray(EC.PublicKey.Format.DER, publicKey.encoded)
    return DidKey.ofCompressedPoint(decoded.encodeToByteArray(EC.PublicKey.Format.RAW.Compressed))
}
