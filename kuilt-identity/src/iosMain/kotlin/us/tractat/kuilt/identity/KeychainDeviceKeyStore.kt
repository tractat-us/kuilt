@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package us.tractat.kuilt.identity

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy
import us.tractat.kuilt.store.NSFileManagerDurableStore

/**
 * This device's key store on iOS: the key in a Keychain item that never leaves this device, and the
 * grants in a file under the app's Application Support directory. Opening it again with the same
 * [appId] after a restart is the same device; calling it twice gives two stores that are one device.
 *
 * The key is a JWK (`x`, `y` and `d`) in a generic-password item, service
 * `us.tractat.kuilt.identity.<appId>`, account `device-key`, with
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and `kSecAttrSynchronizable = false`. So the key
 * is never synced to iCloud Keychain, is not restored to another phone from a backup, and stays
 * readable in the background once the phone has been unlocked after it started, which a device that
 * reconnects without the person looking needs. A restored or replacement phone is therefore a new
 * device, which is the point: a key on two phones would make them one device. The key is read back
 * with a sign-then-verify self-test, named from the item's own `x` and `y`.
 *
 * The grants are in `Application Support/<appId>/kuilt-identity`. A grant is not a secret and names
 * this device's key, so the copy a backup restores to another phone is useless there and harmless.
 *
 * ## Damage is replaced; a Keychain that cannot answer is not
 *
 * An item that was read and is not a P-256 JWK, or fails its self-test, is overwritten with a new key,
 * with one warning in the log and no throw: the app carries on as a new device. Deleting the item is
 * the deliberate way to start again.
 *
 * Anything else the Keychain answers throws [IllegalStateException] naming its `OSStatus`, and nothing
 * is written: `errSecInteractionNotAllowed` before the first unlock after a restart,
 * `errSecMissingEntitlement` in a binary with no Keychain access, `errSecNotAvailable` where there is
 * no Keychain at all. Only `errSecItemNotFound` means "no key yet". Replacing a key the store could
 * not look at would turn a passing condition into a new identity on every launch it lasts for.
 *
 * Two processes sharing one Keychain access group (an app and its extension) can both find no key and
 * both try to add one. The Keychain adds one item and answers the other `errSecDuplicateItem`, which
 * throws; the next call reads the key the first process kept. Neither overwrites the other.
 *
 * No Keychain call is made until the first [DeviceKeyStore.deviceKey]; that call, and every grant call,
 * does Keychain and file work on the caller's dispatcher.
 *
 * @param appId one segment naming the app: letters, digits, `.`, `_` and `-`, and neither `.` nor `..`.
 * @throws IllegalArgumentException when [appId] is not one such segment.
 */
public fun DeviceKeyStore.Companion.platform(appId: String): DeviceKeyStore =
    keychainDeviceKeyStore(appId, applicationSupportDirectory(), SecItemKeychain) {}

/**
 * [platform] over [applicationSupport] and [keychain]: the tests' way in, with a fake Keychain.
 * [beforeKeyIsMade] is the conformance suite's gate.
 */
internal fun keychainDeviceKeyStore(
    appId: String,
    applicationSupport: String,
    keychain: KeychainBackend,
    beforeKeyIsMade: suspend () -> Unit,
): DeviceKeyStore {
    requireAppId(appId)
    val service = KEYCHAIN_SERVICE_PREFIX + appId
    return AppleDeviceKeyStore(
        medium = KeychainKeyMedium(service, keychain),
        grantStore = NSFileManagerDurableStore(identityDirectory(applicationSupport, appId)),
        mediumId = "keychain:$service",
        beforeKeyIsMade = beforeKeyIsMade,
    )
}

/** The Keychain service a store for an app id keeps its key under: this prefix, then the id. */
internal const val KEYCHAIN_SERVICE_PREFIX: String = "us.tractat.kuilt.identity."

/** The Keychain account the key's item is under, within its service. */
internal const val KEYCHAIN_ACCOUNT: String = "device-key"

/**
 * The key as one Keychain item, under [service]. A Keychain item is added or updated in one call, so
 * a key write leaves the item as it was or as written, never a part; there is nothing to clear.
 *
 * Every `OSStatus` but success and `errSecItemNotFound` throws: which answers mean "cannot read now"
 * rather than "damaged" is not this medium's guess to make, and only the bytes can be damaged.
 */
internal class KeychainKeyMedium(private val service: String, private val keychain: KeychainBackend) : KeyMedium {
    override val description: String = "the Keychain item for service \"$service\""

    /** No lock across processes: a Keychain add refuses a second item instead (see [write]). */
    override suspend fun <T> exclusively(block: suspend () -> T): T = block()

    override fun read(): ByteArray? {
        val found = keychain.copy(service, KEYCHAIN_ACCOUNT)
        return when (found.status) {
            errSecSuccess -> found.data ?: ByteArray(0)
            errSecItemNotFound -> null
            else -> throw keychainFailed("cannot read the device key now, so it is kept and no key is made", found.status)
        }
    }

    override fun write(jwk: ByteArray) {
        when (val status = keychain.add(service, KEYCHAIN_ACCOUNT, jwk)) {
            errSecSuccess -> Unit
            errSecDuplicateItem -> throw keychainFailed(
                "already holds a device key another process stored first; the next call reads that key",
                status,
            )
            else -> throw keychainFailed("could not store a new device key", status)
        }
    }

    override fun replaceRefused(jwk: ByteArray): String {
        val status = keychain.update(service, KEYCHAIN_ACCOUNT, jwk)
        if (status != errSecSuccess) throw keychainFailed("could not replace a device key it cannot use", status)
        return "overwritten"
    }

    private fun keychainFailed(what: String, status: Int) =
        IllegalStateException("kuilt-identity: the Keychain item for service \"$service\" $what (OSStatus $status)")
}

/**
 * The Keychain, as narrow as the store needs it: one generic-password item per service and account.
 * A seam only so the store can be tested where there is no Keychain, which is every iOS simulator test
 * binary a default Gradle run spawns (`errSecNotAvailable`). Each member answers the `OSStatus` the
 * Keychain gave; the medium decides what it means.
 */
internal interface KeychainBackend {
    /** `SecItemCopyMatching` for the item's data. */
    fun copy(service: String, account: String): KeychainCopy

    /** `SecItemAdd` of a new item holding [data], with [SecItemKeychain]'s fixed attributes. */
    fun add(service: String, account: String, data: ByteArray): Int

    /** `SecItemUpdate` of the existing item's data to [data], leaving its other attributes alone. */
    fun update(service: String, account: String, data: ByteArray): Int
}

/** What `SecItemCopyMatching` answered: its status and, on success, the item's data. */
internal class KeychainCopy(val status: Int, val data: ByteArray?)

/**
 * The real Keychain, through `SecItem*`. Nothing here decides anything: each member builds its query,
 * makes one call, and hands back the status. The key's bytes pass through an `NSData` that Foundation
 * frees without zeroing; the Keychain API takes nothing else.
 */
internal object SecItemKeychain : KeychainBackend {
    override fun copy(service: String, account: String): KeychainCopy = memScoped {
        val query = itemQuery(service, account)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = try {
            SecItemCopyMatching(query, result.ptr)
        } finally {
            CFRelease(query)
        }
        val data = result.value?.let { (CFBridgingRelease(it) as? NSData)?.toByteArray() }
        KeychainCopy(status, data)
    }

    override fun add(service: String, account: String, data: ByteArray): Int {
        val query = addQuery(service, account, data)
        return try {
            SecItemAdd(query, null)
        } finally {
            CFRelease(query)
        }
    }

    override fun update(service: String, account: String, data: ByteArray): Int {
        val query = itemQuery(service, account)
        val change = cfDictionary()
        return try {
            putBridged(change, kSecValueData, data.toNSData())
            SecItemUpdate(query, change)
        } finally {
            CFRelease(query)
            CFRelease(change)
        }
    }
}

/**
 * The attributes a new key item is added with: the item, its data, and the two that keep it on this
 * device — `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (no backup restores it elsewhere, and it
 * reads in the background once the phone has been unlocked after starting) and
 * `kSecAttrSynchronizable = false` (never iCloud Keychain). The caller releases it.
 */
internal fun addQuery(service: String, account: String, data: ByteArray): CFMutableDictionaryRef {
    val query = itemQuery(service, account)
    putBridged(query, kSecValueData, data.toNSData())
    CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
    CFDictionaryAddValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
    return query
}

/** Class, service and account: what names one item. The caller releases it. */
private fun itemQuery(service: String, account: String): CFMutableDictionaryRef {
    val query = cfDictionary()
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    putBridged(query, kSecAttrService, service as NSString)
    putBridged(query, kSecAttrAccount, account as NSString)
    return query
}

private fun cfDictionary(): CFMutableDictionaryRef =
    checkNotNull(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)) {
        "kuilt-identity: CoreFoundation could not allocate a dictionary"
    }

/** Adds [value] under [key]; the dictionary retains it, so the bridged reference is released here. */
private fun putBridged(dictionary: CFMutableDictionaryRef, key: CFTypeRef?, value: Any) {
    val bridged = CFBridgingRetain(value)
    try {
        CFDictionaryAddValue(dictionary, key, bridged)
    } finally {
        CFRelease(bridged)
    }
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
