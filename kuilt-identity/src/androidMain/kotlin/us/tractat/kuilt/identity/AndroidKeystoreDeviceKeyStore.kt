package us.tractat.kuilt.identity

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.store.FileChannelDurableStore
import us.tractat.kuilt.store.StoreKey
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger("us.tractat.kuilt.identity.AndroidKeystoreDeviceKeyStore")

/**
 * This device's key store on Android: the key in the Android Keystore, which signs with it and never
 * hands its private half to the app, and the grants in a file under [Context.getNoBackupFilesDir], the
 * one app directory Android's backup never copies to another phone.
 *
 * The key is an EC P-256 Keystore key under the alias `kuilt-identity-<appId>`, made for signing
 * alone (`PURPOSE_SIGN`, SHA-256). Reopening the app is the same device; uninstalling it, or clearing
 * its data, deletes the key and makes the next launch a new device. Neither the key nor the grants
 * travel with a backup or a phone-to-phone transfer, so a restored phone is a new device too — which
 * is what keeps one key on one device.
 *
 * A Keystore key cannot be read as bytes, only asked to sign. The Keystore holds the private half and
 * a certificate carrying the public half, and the store names the key from that certificate. On every
 * launch it signs a fixed message with the key and checks the signature under that name, so a key that
 * does not sign for its name is never handed out.
 *
 * Opening it needs no permission and asks the person nothing. Safe to call from many coroutines at
 * once, on a multi-threaded dispatcher; Keystore calls run on the caller's dispatcher, as a file
 * store's I/O does.
 *
 * ## Damage is replaced; a Keystore failing right now is not
 *
 * The hard part is telling the two apart, because the Keystore's own API blurs them ([KeystoreBackend]
 * lists where). [judge] holds the rule, Decided 29:
 *
 * - **Replaced at once, with one warning:** a certificate whose public half is not P-256; a key whose
 *   signature does not verify under its certificate's name; a key the Keystore reports permanently
 *   invalidated; and, on Android 13 and later, a failure whose `KeyStoreException` blames the key
 *   (not found, permanently invalidated, corrupted) or an `UnrecoverableKeyException` with no Keystore
 *   cause.
 * - **Thrown, key kept:** on Android 13 and later, a failure the Keystore calls transient, a system
 *   error or a wait for the user, however long it lasts.
 * - **Thrown, key kept for an hour:** a failure of unknown cause — every other failure, which below
 *   Android 13 is all of them. The store keeps one timestamp beside the grants, the start of the current
 *   run of failures; any successful read clears it. A failure of unknown cause once that run is an hour
 *   old replaces the key. Which process failed, and how often, does not matter.

 * Unlike the desktop file store, a replaced key cannot be kept aside: the Keystore will not copy a key.
 *
 * ## A key that was made here
 *
 * On Android 11 and earlier `getKey` answers "no key" when the Keystore fails, the same answer as a
 * first launch. So once the store has made a key and read it back, it writes a small marker beside the
 * grants, and there a "no key" with the marker present is a failure of unknown cause, not a reason to
 * make a new key. On Android 12 and later "no key" means only that, and a key is made at once whatever the
 * marker says. The marker lives where the grants do, so it is cleared with the app's data and never
 * restored to another phone, just as the key is not. It is written after the key, and a launch that reads
 * the key writes it if it is missing, so a crash between the two costs nothing.
 *
 * ## Several stores over one key
 *
 * Every call takes a lock keyed by the alias in this process, and a file lock in the grants directory
 * across processes, and reads the Keystore afresh inside them. So two `platform` calls, or two
 * processes of one app, are one device: the first to make a key makes the only key, and neither loses
 * the grants the other kept. A store remembers the key it read, so a key replaced by another store
 * afterwards is seen at the next launch.
 *
 * @param alias the Keystore alias the key is kept under.
 * @param directory where the grants, the failing-since time, the marker and the lock file live.
 * @param backend the Keystore itself; a seam only so the store can be tested off a device.
 * @param sdkInt the Android version, which decides how much a Keystore failure can say ([judge]).
 * @param now the time in epoch milliseconds, read when a failure of unknown cause starts or continues.
 * @param beforeKeyIsMade called each time this store is about to make a key, and at no other time:
 *   the conformance suite's gate.
 */
internal class AndroidKeystoreDeviceKeyStore(
    private val alias: String,
    directory: File,
    private val backend: KeystoreBackend,
    private val sdkInt: Int,
    private val now: () -> Long,
    private val beforeKeyIsMade: suspend () -> Unit,
) : DeviceKeyStore {
    private val directory: File = directory.absoluteFile

    // Constructed first: it creates the directory.
    private val store = FileChannelDurableStore(this.directory)
    private val lockFile = File(this.directory, LOCK_FILE)

    /** Shared by every store over this alias in this process; the file lock orders processes. */
    private val inProcess: Mutex = ALIAS_MUTEXES.computeIfAbsent(alias) { Mutex() }

    @Volatile
    private var key: DeviceKey? = null

    /** Guarded by [inProcess]: one warning per store for an unreadable grants record. */
    private var warnedAboutGrants = false

    override suspend fun deviceKey(): DeviceKey = key ?: exclusively { key ?: readOrMake().also { key = it } }

    override suspend fun grants(): List<Grant> = exclusively { readGrants() }

    override suspend fun keepGrant(grant: Grant): Unit = exclusively {
        val current = readGrants()
        val next = current.keeping(grant)
        if (next !== current) store.write(GRANTS, StoredGrants.encode(next))
    }

    private suspend fun <T> exclusively(block: suspend () -> T): T = inProcess.withLock {
        RandomAccessFile(lockFile, "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    private suspend fun readOrMake(): DeviceKey {
        val signer = try {
            backend.getKey(alias)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return afterFailure(KeystoreFailure.Thrown("loading the key", e))
        } ?: return noKey()
        val publicKey = backend.getCertificatePublicKey(alias) ?: return afterFailure(KeystoreFailure.NoCertificate)
        return when (val read = readBack(publicKey, signer)) {
            is KeystoreRead.Usable -> {
                forgetFailingSince()
                markKeyMade() // Heals a launch that crashed between making the key and marking it.
                read.key
            }
            is KeystoreRead.Refused -> replace(read.reason, cause = null)
            is KeystoreRead.Failed -> afterFailure(read.failure)
        }
    }

    /**
     * `getKey` answered "no key". On Android 12 and later that is `KEY_NOT_FOUND` and nothing else, so it
     * is a first launch, or a key deleted by someone else: make one. On Android 11 and earlier it is also
     * how a Keystore that did not answer looks, so where this store has made a key before — its marker
     * is beside the grants — it is a failure of unknown cause, not a first launch.
     */
    private suspend fun noKey(): DeviceKey =
        if (sdkInt <= LAST_API_WHERE_GET_KEY_HIDES_FAILURE && store.read(KEY_MADE) != null) {
            afterFailure(KeystoreFailure.NoKeyThoughOneWasMade)
        } else {
            make()
        }

    /** Decided 29: replace, keep, or wait — see [judge]. Returns only by replacing; otherwise throws. */
    private suspend fun afterFailure(failure: KeystoreFailure): DeviceKey {
        when (val verdict = judge(failure, sdkInt)) {
            is FailureVerdict.Replace -> return replace("${verdict.why} (${failure.description})", failure.cause)
            FailureVerdict.Keep -> Unit
            FailureVerdict.Unknown -> {
                val failingFor = (now() - failingSince()).milliseconds
                if (failingFor >= UNKNOWN_CAUSE_PATIENCE) {
                    return replace("the Keystore has failed for $failingFor with no good read; the last: ${failure.description}", failure.cause)
                }
            }
        }
        logger.warn(failure.cause) {
            "kuilt-identity: the device key under Android Keystore alias \"$alias\" could not be read " +
                "(${failure.description}); it is kept, and this call fails."
        }
        throw keystoreFailed(failure)
    }

    /** Replacing a key is the most destructive thing the store does, so its warning carries the [cause]. */
    private suspend fun replace(reason: String, cause: Throwable?): DeviceKey {
        logger.warn(cause) {
            "kuilt-identity: the device key under Android Keystore alias \"$alias\" cannot be used: $reason. " +
                "It is deleted and replaced with a new key, so this device is a new device to every room it " +
                "returns to."
        }
        // The marker goes first: a crash before the key is deleted leaves a key that the next launch reads
        // and re-marks, and a crash after it leaves no key and no marker, which is a first launch.
        store.delete(KEY_MADE)
        backend.delete(alias)
        forgetFailingSince()
        return make()
    }

    private suspend fun make(): DeviceKey {
        beforeKeyIsMade()
        backend.generate(alias)
        val signer = backend.getKey(alias)
        val publicKey = backend.getCertificatePublicKey(alias)
        val read = if (signer != null && publicKey != null) readBack(publicKey, signer) else null
        if (read is KeystoreRead.Usable) {
            // After the key is made and read back, never before: a crash before this line leaves a key with
            // no marker, which the next launch reads and marks, rather than a marker with no key, which on
            // Android 11 and earlier would make the next launches wait out an hour before making a key at all.
            markKeyMade()
            return read.key
        }
        throw IllegalStateException(
            "kuilt-identity: the Android Keystore made a key under \"$alias\" and could not read it back: " +
                when (read) {
                    null -> "the key or its certificate did not come back"
                    is KeystoreRead.Refused -> read.reason
                    is KeystoreRead.Failed -> read.failure.description
                    is KeystoreRead.Usable -> "unreachable"
                },
            (read as? KeystoreRead.Failed)?.failure?.let { (it as? KeystoreFailure.Thrown)?.error },
        )
    }

    /**
     * When the current run of failures began, starting one now if none is running. A start in the future
     * — the clock moved back — is read as now.
     */
    private suspend fun failingSince(): Long {
        val at = now()
        val held = store.read(FAILING_SINCE)?.takeIf { it.size == Long.SIZE_BYTES }?.let { ByteBuffer.wrap(it).long }
        if (held != null && held <= at) return held
        store.write(FAILING_SINCE, ByteBuffer.allocate(Long.SIZE_BYTES).putLong(at).array())
        return at
    }

    private suspend fun markKeyMade() {
        if (store.read(KEY_MADE) == null) store.write(KEY_MADE, byteArrayOf(1))
    }

    private suspend fun forgetFailingSince() {
        if (store.read(FAILING_SINCE) != null) store.delete(FAILING_SINCE)
    }

    private suspend fun readGrants(): List<Grant> {
        val record = store.read(GRANTS) ?: return emptyList()
        return StoredGrants.decode(record) ?: emptyList<Grant>().also {
            if (!warnedAboutGrants) {
                warnedAboutGrants = true
                logger.warn {
                    "kuilt-identity: the grants kept in $directory cannot be read and are dropped; this device " +
                        "returns to each room as an ordinary admission until it is granted again."
                }
            }
        }
    }

    internal companion object {
        /** The file every call locks, so stores over one key take turns across processes. */
        const val LOCK_FILE: String = "device-key.lock"

        /** The [StoreKey] the grants record is kept under. */
        val GRANTS: StoreKey = StoreKey("grants")

        /** The [StoreKey] of the start of the current run of failures, 8 bytes of epoch milliseconds. */
        val FAILING_SINCE: StoreKey = StoreKey("keystore-failing-since")

        /**
         * The [StoreKey] of the marker that says this store has made a key ([noKey]). Beside the grants in
         * the no-backup directory, so it goes wherever the key goes: cleared with the app's data, never
         * restored to another phone.
         */
        val KEY_MADE: StoreKey = StoreKey("device-key-made")

        /**
         * The last Android version whose `getKey` answers `null` when the Keystore fails. keystore2, from
         * Android 12 (API 31), answers `null` only for `KEY_NOT_FOUND`.
         */
        const val LAST_API_WHERE_GET_KEY_HIDES_FAILURE: Int = 30

        /** One mutex per alias for the life of the process; a handful of entries. */
        private val ALIAS_MUTEXES = ConcurrentHashMap<String, Mutex>()
    }
}

/**
 * The key [signer] holds, checked against the name [publicKey] gives — the half the Keystore stored,
 * never one derived from the private key: sign [SELF_TEST], convert DER to raw `r ‖ s`, and verify under
 * the name through [DidKey.verify], so through the on-curve check every other device applies to it.
 *
 * [KeystoreRead.Refused] is a verdict on what the Keystore holds: a public half that is not P-256, or a
 * signature that does not verify. A signature that fails to *happen* is [KeystoreRead.Failed], for
 * [judge] to weigh. A DER signature that does not parse is the Keystore misbehaving and throws.
 */
internal suspend fun readBack(publicKey: PublicKey, signer: KeystoreSigner): KeystoreRead {
    val name = nameOf(publicKey)
        ?: return KeystoreRead.Refused("its certificate's public half is not a P-256 point (${publicKey.algorithm})")
    val der = try {
        signer.signDer(SELF_TEST)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        return KeystoreRead.Failed(KeystoreFailure.Thrown("signing its self-test", e))
    }
    val signature = derSignatureToRaw(der) ?: throw notDer()
    if (!name.verify(SELF_TEST, signature)) {
        return KeystoreRead.Refused("its private key does not sign for the name its certificate gives, ${name.value}")
    }
    return KeystoreRead.Usable(KeystoreDeviceKey(name, signer))
}

/** What [readBack] found. */
internal sealed interface KeystoreRead {
    class Usable(val key: DeviceKey) : KeystoreRead

    /** Not a usable key, and [reason] says why in words that carry no key material. */
    class Refused(val reason: String) : KeystoreRead {
        override fun toString(): String = "Refused($reason)"
    }

    /** The self-test signature could not be made. */
    class Failed(val failure: KeystoreFailure) : KeystoreRead
}

/**
 * The `did:key` [publicKey] names: its `x`, behind the lead byte its `y`'s parity picks. `null` when
 * it is not an EC key on P-256 (by field size and group order), or is the point at infinity. Whether
 * `x` is on the curve is [DidKey.verify]'s question.
 */
internal fun nameOf(publicKey: PublicKey): DidKey? {
    val ec = publicKey as? ECPublicKey ?: return null
    val params = ec.params ?: return null
    if (params.curve.field.fieldSize != COORDINATE_BITS || params.order != P256_ORDER) return null
    val point = ec.w
    if (point == ECPoint.POINT_INFINITY) return null
    val x = point.affineX.toFixedWidth() ?: return null
    val lead: Byte = if (point.affineY.testBit(0)) 0x03 else 0x02
    return DidKey.ofCompressedPoint(byteArrayOf(lead) + x)
}

private fun BigInteger.toFixedWidth(): ByteArray? {
    if (signum() < 0) return null
    val bytes = toByteArray().let { if (it.size > COORDINATE_BYTES && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    if (bytes.size > COORDINATE_BYTES) return null
    return ByteArray(COORDINATE_BYTES - bytes.size) + bytes
}

/**
 * A key the Keystore holds: it signs through [signer] and converts the DER signature JCA returns to the
 * raw `r ‖ s` [DeviceKey.sign] promises. Holds no key bytes, since the Keystore never hands them over.
 */
internal class KeystoreDeviceKey(override val id: DidKey, private val signer: KeystoreSigner) : DeviceKey {
    override suspend fun sign(message: ByteArray): ByteArray {
        val der = try {
            signer.signDer(message)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(e) { "kuilt-identity: the Android Keystore failed to sign with the device key ${id.value}" }
            throw signingFailed(e)
        }
        return derSignatureToRaw(der) ?: throw notDer()
    }

    override fun toString(): String = "KeystoreDeviceKey(${id.value})"
}

/**
 * This device's key store on Android: the key in the Android Keystore under the alias
 * `kuilt-identity-<appId>`, and the grants under `context.noBackupFilesDir/kuilt-identity-<appId>`.
 * Opening it again with the same [appId] after a restart is the same device; calling it twice gives
 * two stores that are one device.
 *
 * Nothing is chosen for the app here that could leave the phone: Keystore keys are never backed up,
 * and the grants sit in the one directory Android's backup and device-to-device transfer skip. A
 * restored or replacement phone is therefore a new device, which is the point — a key on two phones
 * would make them one device.
 *
 * A damaged key is replaced with a warning and the app carries on as a new device; a Keystore that
 * cannot answer right now makes [DeviceKeyStore.deviceKey] throw and keeps the key. Where the Keystore
 * does not say whose failure it is — always below Android 13 — the key is kept until the Keystore has
 * been failing for an hour with no good read in between, and then replaced.
 *
 * Requires Android 8.0 (API 26): the grants are kept through `java.nio.file`.
 *
 * No Keystore call is made until the first [DeviceKeyStore.deviceKey]; that call, and every grant
 * call, does disk and Keystore work on the caller's dispatcher.
 *
 * @param appId one segment naming the app: letters, digits, `.`, `_` and `-`, and neither `.` nor `..`.
 * @param context any context of the app; only its no-backup directory is read.
 * @throws IllegalArgumentException when [appId] is not one such segment.
 */
@RequiresApi(MIN_API)
public fun DeviceKeyStore.Companion.platform(appId: String, context: Context): DeviceKeyStore =
    platformStore(appId, context.noBackupFilesDir, AndroidKeystoreBackend, Build.VERSION.SDK_INT, System::currentTimeMillis)

/** The lowest Android version the store runs on: its grants store uses `java.nio.file`. */
private const val MIN_API = 26

/** [platform] over [noBackupDir], [backend], [sdkInt] and [now]: the test's way in, with a fake Keystore. */
internal fun platformStore(appId: String, noBackupDir: File, backend: KeystoreBackend, sdkInt: Int, now: () -> Long): DeviceKeyStore {
    require(APP_ID.matches(appId) && appId != "." && appId != "..") {
        "appId must be one segment of letters, digits, '.', '_' and '-', and not '.' or '..': \"$appId\""
    }
    val name = "kuilt-identity-$appId"
    return AndroidKeystoreDeviceKeyStore(name, File(noBackupDir, name), backend, sdkInt, now) {}
}

private val APP_ID = Regex("[A-Za-z0-9._-]+")

/** What the self-test signs: fixed, since it proves only that the key signs for its name. */
private val SELF_TEST = "kuilt-identity device-key self-test".encodeToByteArray()

private const val COORDINATE_BITS = 256
private const val COORDINATE_BYTES = 32
private val P256_ORDER = BigInteger(P256.N.toString(16), 16)

private fun signingFailed(cause: Exception) =
    IllegalStateException("kuilt-identity: the Android Keystore failed to sign (${cause::class.simpleName})", cause)

private fun keystoreFailed(failure: KeystoreFailure) =
    IllegalStateException("kuilt-identity: the Android Keystore could not read the device key: ${failure.description}", failure.cause)

private fun notDer() =
    IllegalStateException("kuilt-identity: the Android Keystore returned a signature that is not a P-256 ECDSA-Sig-Value")
