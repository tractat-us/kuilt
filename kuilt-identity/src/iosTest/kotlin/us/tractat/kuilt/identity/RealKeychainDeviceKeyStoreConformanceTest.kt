@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package us.tractat.kuilt.identity

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.test.runTest
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecInteractionNotAllowed
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
import platform.Security.kSecReturnAttributes
import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.KeyWriteInterruption
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.UnreadableKey
import us.tractat.kuilt.store.NSFileManagerDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The iOS store against the whole [DeviceKeyStoreConformanceSuite] over the **real** Keychain,
 * [SecItemKeychain], where [KeychainDeviceKeyStoreConformanceTest] runs the same suite over
 * [FakeKeychain] (#3310).
 *
 * **It runs only in `iosSimulatorArm64RealKeychainTest`**, which exists under
 * `-Pidentity.keychain.tests=true`: a test binary linked with `keychain-access-groups`, spawned into a
 * booted simulator. The default `iosSimulatorArm64Test` excludes this class by name, because its
 * spawn has no Keychain (`errSecNotAvailable`) and every row would fail. `apple-nightly.yml` runs it,
 * and the task fails unless this class reports rows, none of them skipped.
 *
 * What this adds over the fake: that `SecItemAdd`, `SecItemCopyMatching` and `SecItemUpdate` answer
 * the store the way [FakeKeychain] assumes — `errSecItemNotFound` for no key, the bytes back for one,
 * an update in place — and that a key the real Keychain holds survives every reopen, damage and
 * replacement the suite puts it through. The damage hooks write through `SecItem*` themselves:
 *
 * - **corrupt key:** the item's data updated to half its length, so it is no longer JSON;
 * - **mismatched key:** the item's data updated (or added) to a well-formed JWK whose `x` and `y` are
 *   one fresh key's and whose `d` is another's;
 * - **cleared key:** `SecItemDelete` of the item;
 * - **corrupt grants** and **unreadable grants:** as in [KeychainDeviceKeyStoreConformanceTest]; they
 *   are files, not Keychain items.
 *
 * What it still cannot do: make the real Keychain refuse. A booted simulator is unlocked, so
 * `errSecInteractionNotAllowed` (before the first unlock after a restart) cannot be produced here.
 * The **unreadable key** hook therefore puts a [RefusingKeychain] in front of the real one, answering
 * that status while rigged: what the property gains from the real Keychain is only that the key it
 * holds is still the key once the refusal lifts. **Interrupted write** is `AtomicMedium`, as in the
 * fake's fixture.
 *
 * Each fixture has a fresh app id, so its own service. Nothing is deleted after a row: the nightly
 * creates the simulator for the run and deletes it after, and its Keychain with it.
 */
class RealKeychainDeviceKeyStoreConformanceTest : DeviceKeyStoreConformanceSuite() {
    override suspend fun newFixture(): DeviceKeyStoreFixture {
        val keychain = RefusingKeychain(SecItemKeychain)
        val appId = freshAppId()
        val applicationSupport = freshDirectory("kuilt-real-keychain")
        val service = KEYCHAIN_SERVICE_PREFIX + appId
        return DeviceKeyStoreFixture.Durable(
            reopen = { beforeKeyIsMade -> keychainDeviceKeyStore(appId, applicationSupport, keychain, beforeKeyIsMade) },
            corruptKey = {
                val held = heldKey(service)
                checkStatus(SecItemKeychain.update(service, KEYCHAIN_ACCOUNT, held.copyOf(held.size / 2)), "corrupting the key")
            },
            plantMismatchedKey = {
                val (jwk, claimed) = mismatchedJwk()
                val updated = SecItemKeychain.update(service, KEYCHAIN_ACCOUNT, jwk)
                if (updated == errSecItemNotFound) {
                    checkStatus(SecItemKeychain.add(service, KEYCHAIN_ACCOUNT, jwk), "planting a mismatched key")
                } else {
                    checkStatus(updated, "planting a mismatched key")
                }
                claimed
            },
            clearKey = { checkStatus(deleteItem(service), "clearing the key") },
            interruptKeyWrite = {
                KeyWriteInterruption.AtomicMedium(
                    "The key is one Keychain item, and the store writes it with one SecItemAdd or one " +
                        "SecItemUpdate. The Keychain commits each call as one database transaction, so a crash " +
                        "leaves the item as it was or as written; there is no temporary item for it to leave.",
                )
            },
            corruptGrants = {
                NSFileManagerDurableStore(identityDirectory(applicationSupport, appId))
                    .write(AppleDeviceKeyStore.GRANTS, byteArrayOf(9, 9, 9))
            },
            makeKeyUnreadable = {
                keychain.refusing = errSecInteractionNotAllowed
                UnreadableKey.Rigged { keychain.refusing = null }
            },
            makeGrantsUnreadable = { unreadableGrantsRecord(identityDirectory(applicationSupport, appId)) },
        )
    }

    /**
     * The item the store adds is what its add query asks for, as the real Keychain keeps it: this
     * device only and readable after first unlock (`pdmn=cku`), never synchronized (`sync=0`).
     * `KeychainDeviceKeyStoreTest.keychainItemIsThisDeviceOnlyAndUnsynced` pins the query; this
     * reads the item back.
     */
    @Test
    fun theStoredItemIsThisDeviceOnlyAndUnsynced() = runTest {
        val appId = freshAppId()
        val service = KEYCHAIN_SERVICE_PREFIX + appId
        keychainDeviceKeyStore(appId, freshDirectory("kuilt-real-keychain"), SecItemKeychain) {}.deviceKey()

        val attributes = itemAttributes(service)
        val accessible = attributes[bridged(kSecAttrAccessible)]
        val synchronizable = attributes[bridged(kSecAttrSynchronizable)] as? NSNumber
        assertAll(
            { assertEquals<Any?>(bridged(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly), accessible, "kept this device only, readable after first unlock") },
            { assertNotNull(synchronizable, "the item says whether it synchronizes") },
            { assertEquals(false, synchronizable?.boolValue, "never synchronized to iCloud Keychain") },
        )
    }
}

/**
 * [delegate], except that while [refusing] is set every call answers it: the one Keychain answer a
 * booted, unlocked simulator cannot give on demand. Not thread-safe: the tests drive it from one
 * `runTest` thread.
 */
private class RefusingKeychain(private val delegate: KeychainBackend) : KeychainBackend {
    var refusing: Int? = null

    override fun copy(service: String, account: String): KeychainCopy =
        refusing?.let { KeychainCopy(it, null) } ?: delegate.copy(service, account)

    override fun add(service: String, account: String, data: ByteArray): Int =
        refusing ?: delegate.add(service, account, data)

    override fun update(service: String, account: String, data: ByteArray): Int =
        refusing ?: delegate.update(service, account, data)
}

private fun checkStatus(status: Int, what: String) =
    check(status == errSecSuccess) { "precondition: $what in the real Keychain answered OSStatus $status" }

private fun heldKey(service: String): ByteArray {
    val found = SecItemKeychain.copy(service, KEYCHAIN_ACCOUNT)
    checkStatus(found.status, "reading the key")
    return checkNotNull(found.data) { "precondition: a key to corrupt" }
}

private fun deleteItem(service: String): Int {
    val query = itemQuery(service)
    return try {
        SecItemDelete(query)
    } finally {
        CFRelease(query)
    }
}

/** The item's attributes, keyed and valued as Foundation bridges them. */
private fun itemAttributes(service: String): Map<Any?, Any?> = memScoped {
    val query = itemQuery(service)
    CFDictionaryAddValue(query, kSecReturnAttributes, kCFBooleanTrue)
    CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
    val result = alloc<CFTypeRefVar>()
    val status = try {
        SecItemCopyMatching(query, result.ptr)
    } finally {
        CFRelease(query)
    }
    checkStatus(status, "reading the item's attributes")
    @Suppress("UNCHECKED_CAST")
    checkNotNull(CFBridgingRelease(result.value) as? Map<Any?, Any?>) { "precondition: the item's attributes" }
}

/** A Security constant as Foundation bridges it, retained first so the bridge's release balances. */
private fun bridged(constant: CFTypeRef?): Any? = CFBridgingRelease(CFRetain(constant))

private fun itemQuery(service: String): CFMutableDictionaryRef {
    val query = checkNotNull(
        CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr),
    ) { "CoreFoundation could not allocate a dictionary" }
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    putBridged(query, kSecAttrService, service as NSString)
    putBridged(query, kSecAttrAccount, KEYCHAIN_ACCOUNT as NSString)
    return query
}

private fun putBridged(dictionary: CFMutableDictionaryRef, key: CFTypeRef?, value: Any) {
    val retained = CFBridgingRetain(value)
    try {
        CFDictionaryAddValue(dictionary, key, retained)
    } finally {
        CFRelease(retained)
    }
}
