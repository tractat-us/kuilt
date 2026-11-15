package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.EC
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
import java.security.PublicKey
import java.util.UUID

/**
 * [AndroidKeystoreDeviceKeyStore] on **Android 13 and later** against the whole
 * [DeviceKeyStoreConformanceSuite], as a [DeviceKeyStoreFixture.Durable] store over a [FakeKeystore] and
 * a fresh temp directory per fixture. Every reopen is a new store over the same fake Keystore, alias and
 * directory, as a new launch.
 *
 * **Why only 33 and later.** That is where a Keystore failure says whose it is, so the suite's two
 * damage properties hold as written: damage is replaced at once, and a Keystore failing right now is
 * refused. Below 33 neither holds as the suite states it, by design or by platform:
 * - every failure there is of unknown cause and replaces the key only after an hour of failures
 *   (Decided 29), not at once, so `aCorruptKeyIsReplacedNotFatal` would red;
 *   `AndroidKeystoreDeviceKeyStoreTest` covers the hour instead;
 * - on Android 11 and earlier `getKey` answers "no key" when the Keystore fails, so there is no state a
 *   `makeKeyUnreadable` hook could rig that the store can see. That is a platform gap the store cannot
 *   close, and claiming either arm for it would be false.
 *
 * **What this covers, and what it does not.** It runs the store's own logic over a fake of the Keystore,
 * because a JVM unit test has no `AndroidKeyStore` provider and Robolectric does not shadow one. The
 * Keystore exceptions are the framework's own classes, built as AOSP builds them ([AospKeystore]). It
 * does **not** run the real Keystore: [AndroidKeystoreBackend], the key it generates, and which exception
 * a real Keystore throws for each fault are covered by no automated test in this repo.
 *
 * Each damage hook leaves the fake Keystore as that failure leaves the real one, per AOSP's source:
 *
 * - **corrupt key:** every signature fails with `InvalidKeyException("Keystore operation failed")`
 *   caused by a `KeyStoreException` carrying `VALUE_CORRUPTED` (public code 7), the one code the
 *   Keystore uses to blame a corrupted key, so the store replaces it at once. Not `INVALID_KEY_BLOB`:
 *   that lands in public code 10 beside passing TEE faults, so it is of unknown cause and waits an hour,
 *   which this property, asking for an immediate replacement, cannot express;
 * - **mismatched key:** one key's certificate beside another key's private half, so the entry loads and
 *   fails only the self-test;
 * - **cleared key:** the alias deleted;
 * - **corrupt grants:** three bytes that are not a grants record, through the grant store itself;
 * - **unreadable key** (`Rigged`): `getKey` throws `UnrecoverableKeyException` caused by a
 *   `KeyStoreException` carrying `SYSTEM_ERROR` — the Keystore failing, not the key — while
 *   `getCertificate` answers `null`, as keystore2's does on an error;
 * - **interrupted write** (`AtomicMedium`): for the reason the hook gives;
 * - **unreadable grants:** every permission taken off the grants record, so opening it fails with
 *   `EACCES` while the directory still takes a new file; the rig checks it took and says at restore
 *   whether anything rewrote the record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidKeystoreDeviceKeyStoreConformanceTest : DeviceKeyStoreConformanceSuite() {
    override suspend fun newFixture(): DeviceKeyStoreFixture {
        val keystore = FakeKeystore()
        val alias = "kuilt-identity-test-${UUID.randomUUID()}"
        val directory = Files.createTempDirectory("kuilt-keystore-grants").toFile()
        return DeviceKeyStoreFixture.Durable(
            reopen = { beforeKeyIsMade ->
                AndroidKeystoreDeviceKeyStore(alias, directory, keystore, SDK, { NOW }, beforeKeyIsMade)
            },
            corruptKey = { keystore.damage(alias) { AospKeystore.signFailure(AospKeystore.VALUE_CORRUPTED) } },
            plantMismatchedKey = {
                val named = FakeKeystore.newPair()
                val signer = FakeKeystore.newPair()
                keystore.plant(alias, named.public, signer.private)
                independentNameOf(named.public)
            },
            clearKey = {
                check(keystore.holds(alias)) { "precondition: there was a key to clear" }
                keystore.delete(alias)
            },
            interruptKeyWrite = {
                KeyWriteInterruption.AtomicMedium(
                    "A crash while the Keystore makes a key can leave nothing beside the held key, because the " +
                        "Keystore holds one entry per alias and the store writes only when it holds no usable key. " +
                        "On Android 12 and later a key and its certificate are stored in one transaction. On " +
                        "Android 11 and earlier they are stored separately, and a crash can leave a key without " +
                        "its certificate, but only under the alias itself, where no held key can be; " +
                        "AndroidKeystoreDeviceKeyStoreTest covers that residue directly.",
                )
            },
            corruptGrants = {
                FileChannelDurableStore(directory).write(AndroidKeystoreDeviceKeyStore.GRANTS, byteArrayOf(9, 9, 9))
            },
            makeKeyUnreadable = {
                keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
                UnreadableKey.Rigged { keystore.loadFault = null }
            },
            makeGrantsUnreadable = { unreadableGrantsRecord(directory) },
        )
    }

    /**
     * Every permission taken off the grants record in [directory], so opening it fails with `EACCES`,
     * while the directory still takes a new file: the write probe keeps a sibling record there through
     * the same backend. The rig checks it took (a mode of `000` is not enforced for root), and restoring answers whether the record was written while unreadable: a
     * write renames a new, readable file into place, and a delete leaves none.
     */
    private fun unreadableGrantsRecord(directory: File): UnreadableGrants {
        // `grants` needs no escaping, so the record's filename is the key's name.
        val record = directory.toPath().resolve(AndroidKeystoreDeviceKeyStore.GRANTS.name)
        check(Files.exists(record, LinkOption.NOFOLLOW_LINKS)) { "precondition: a grants record at $record to fault" }
        Files.setPosixFilePermissions(record, emptySet())
        check(!Files.isReadable(record)) { "rig: $record is still readable after chmod 000 — running as root? The fault did not take" }
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
        const val SDK = 33

        /** No property here waits on the clock: no rig's failure is of unknown cause. */
        const val NOW = 1_700_000_000_000L

        /** A record beside the grants, written only by the unreadable-grants rig's write probe. */
        val PROBE_WRITE = StoreKey("probe-write")
    }
}

/**
 * The name [publicKey] should carry, worked out apart from the store's own `nameOf`: its X.509 encoding
 * decoded by the platform provider and re-encoded compressed.
 */
internal suspend fun independentNameOf(publicKey: PublicKey): DidKey {
    val decoded = algorithms().ecdsa.publicKeyDecoder(EC.Curve.P256)
        .decodeFromByteArray(EC.PublicKey.Format.DER, publicKey.encoded)
    return DidKey.ofCompressedPoint(decoded.encodeToByteArray(EC.PublicKey.Format.RAW.Compressed))
}
