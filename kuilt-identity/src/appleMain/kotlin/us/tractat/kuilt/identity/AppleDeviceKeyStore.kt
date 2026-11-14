package us.tractat.kuilt.identity

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.concurrent.Volatile

private val logger = KotlinLogging.logger("us.tractat.kuilt.identity.AppleDeviceKeyStore")

/**
 * The device-key store both Apple platforms share: the key kept as a self-tested JWK in a [KeyMedium],
 * and the grants in a [DurableStore] beside it. What differs between iOS and macOS is only the medium
 * — a Keychain item on iOS, a file on macOS (Decided 28) — so the decisions live here once and both
 * platforms' conformance runs test them.
 *
 * ## Damage is replaced; a medium that cannot answer is not
 *
 * A key that was read and is not a P-256 JWK, or fails its sign-then-verify self-test, is replaced
 * with a new key, with one warning in the log and no throw: the app carries on as a new device. The
 * medium says what became of the refused key ([KeyMedium.replaceRefused]). Only a medium that reports
 * the key **absent** means "no key yet"; a medium that cannot read the key right now throws from
 * [KeyMedium.read], and that throw reaches the caller with nothing made or written. A grants record
 * that cannot be decoded is dropped with one warning; a lost grant costs only an ordinary admission.
 *
 * A signing fault during the self-test is the provider failing, not the key: [SoftwareDeviceKey.readBack]
 * throws [IllegalStateException] for it, and so does this store, keeping the key.
 *
 * ## Several stores over one medium
 *
 * Every call holds a mutex shared by every store over the same [mediumId] in this process, and then
 * whatever cross-process exclusion the medium offers ([KeyMedium.exclusively]), and reads the medium
 * afresh inside both. So two stores over one medium are one device: the first to make a key makes the
 * only key, and neither loses the grants the other kept. A store remembers the key it read, so a key
 * replaced by another store afterwards is seen at the next launch.
 *
 * Safe under a multi-threaded dispatcher. Medium and file I/O run on the caller's dispatcher.
 *
 * @param mediumId names the medium for the in-process mutex: two stores with one id take turns.
 * @param beforeKeyIsMade called each time this store is about to make a key, and at no other time:
 *   the conformance suite's gate.
 */
internal class AppleDeviceKeyStore(
    private val medium: KeyMedium,
    private val grantStore: DurableStore,
    mediumId: String,
    private val beforeKeyIsMade: suspend () -> Unit,
) : DeviceKeyStore {
    private val inProcess: Mutex = mutexFor(mediumId)

    @Volatile
    private var key: DeviceKey? = null

    /** Guarded by [inProcess]: one warning per store for an unreadable grants record. */
    private var warnedAboutGrants = false

    override suspend fun deviceKey(): DeviceKey = key ?: exclusively { key ?: readOrMake().also { key = it } }

    override suspend fun grants(): List<Grant> = exclusively { readGrants() }

    override suspend fun keepGrant(grant: Grant): Unit = exclusively {
        val current = readGrants()
        val next = current.keeping(grant)
        if (next !== current) grantStore.write(GRANTS, StoredGrants.encode(next))
    }

    private suspend fun <T> exclusively(block: suspend () -> T): T = inProcess.withLock { medium.exclusively(block) }

    private suspend fun readOrMake(): DeviceKey {
        val stored = medium.read() ?: return make(medium::write)
        val read = try {
            SoftwareDeviceKey.readBack(stored)
        } finally {
            stored.fill(0)
        }
        return when (read) {
            is StoredKey.Usable -> read.key
            is StoredKey.Refused -> make { jwk ->
                val fate = medium.replaceRefused(jwk)
                logger.warn {
                    "kuilt-identity: the device key in ${medium.description} cannot be used: ${read.reason}. " +
                        "It is $fate, and replaced with a new key, so this device is a new device to every " +
                        "room it returns to."
                }
            }
        }
    }

    private suspend fun make(write: (ByteArray) -> Unit): DeviceKey {
        beforeKeyIsMade()
        val made = SoftwareDeviceKey.generate()
        val jwk = made.encodeJwk()
        try {
            write(jwk)
        } finally {
            jwk.fill(0)
        }
        return made
    }

    private suspend fun readGrants(): List<Grant> {
        val record = grantStore.read(GRANTS) ?: return emptyList()
        return StoredGrants.decode(record) ?: emptyList<Grant>().also {
            if (!warnedAboutGrants) {
                warnedAboutGrants = true
                logger.warn {
                    "kuilt-identity: the grants kept beside ${medium.description} cannot be read and are " +
                        "dropped; this device returns to each room as an ordinary admission until it is " +
                        "granted again."
                }
            }
        }
    }

    internal companion object {
        /** The [StoreKey] the grants record is kept under. */
        val GRANTS: StoreKey = StoreKey("grants")

        private val registryLock = reentrantLock()

        /** One mutex per medium for the life of the process; a handful of entries. */
        private val mutexes = HashMap<String, Mutex>()

        private fun mutexFor(mediumId: String): Mutex = registryLock.withLock { mutexes.getOrPut(mediumId) { Mutex() } }
    }
}

/**
 * Where an [AppleDeviceKeyStore] keeps the key's JWK bytes. Each member is called inside
 * [exclusively], under the store's in-process mutex.
 */
internal interface KeyMedium {
    /** Where the key lives, for a log line: a path, or a Keychain service. Never key material. */
    val description: String

    /** Runs [block] holding whatever lock this medium offers across processes, or none. */
    suspend fun <T> exclusively(block: suspend () -> T): T

    /** The stored key's bytes, or `null` only when no key is stored. Throws when it cannot be read now. */
    fun read(): ByteArray?

    /** Stores [jwk] as the key where none is stored. */
    fun write(jwk: ByteArray)

    /**
     * Stores [jwk] in place of a key that was read and refused, and says what became of the refused
     * one, as the rest of a sentence beginning "It is": "kept as …", "overwritten".
     */
    fun replaceRefused(jwk: ByteArray): String
}

/** Letters, digits, `.`, `_` and `-`, and neither `.` nor `..`: one path segment, one Keychain service suffix. */
internal fun requireAppId(appId: String) {
    require(APP_ID.matches(appId) && appId != "." && appId != "..") {
        "appId must be one path segment of letters, digits, '.', '_' and '-', and not '.' or '..': \"$appId\""
    }
}

private val APP_ID = Regex("[A-Za-z0-9._-]+")
