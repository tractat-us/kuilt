@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.identity

import kotlinx.coroutines.test.runTest
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanFalse
import platform.Foundation.NSFileManager
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecValueData
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the iOS store promises beyond the shared contract ([KeychainDeviceKeyStoreConformanceTest]): the
 * attributes that keep the item on this device, which Keychain answers it refuses rather than replaces,
 * a second process adding first, and the one-line default's names. Over [FakeKeychain], except the
 * attribute test, which reads the real `SecItemAdd` query the real adapter builds.
 */
class KeychainDeviceKeyStoreTest {

    /**
     * The item the real adapter adds is a generic password kept `AfterFirstUnlockThisDeviceOnly` and not
     * synchronizable: no backup restores it to another phone and iCloud Keychain never carries it, so a
     * key stays on one device (Decided 4).
     *
     * Read off the CoreFoundation dictionary passed to `SecItemAdd`, compared with the Security
     * framework's own constants. It cannot see what a real Keychain stores, which is #3310's; and it
     * pins what [addQuery] builds, not that [SecItemKeychain.add] passes that dictionary on.
     */
    @Test
    fun keychainItemIsThisDeviceOnlyAndUnsynced() {
        val data = byteArrayOf(1, 2, 3)
        val query = addQuery("us.tractat.kuilt.identity.my-app", KEYCHAIN_ACCOUNT, data)
        try {
            val itemClass = CFDictionaryGetValue(query, kSecClass)
            val accessible = CFDictionaryGetValue(query, kSecAttrAccessible)
            val synchronizable = CFDictionaryGetValue(query, kSecAttrSynchronizable)
            val value = CFDictionaryGetValue(query, kSecValueData)
            // Identity, not equality: the dictionary retains the very constants the adapter put in.
            assertAll(
                { assertEquals<Any?>(kSecClassGenericPassword, itemClass, "a generic-password item") },
                { assertEquals<Any?>(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, accessible, "kept this device only, readable after first unlock") },
                { assertEquals<Any?>(kCFBooleanFalse, synchronizable, "never synchronized to iCloud Keychain") },
                { assertNotNull(value, "carrying the key's bytes") },
            )
        } finally {
            CFRelease(query)
        }
    }

    /**
     * Every Keychain answer but "found" and "not found" throws, makes nothing and keeps the key: before
     * the first unlock, without the Keychain entitlement, and with no Keychain at all. Once the Keychain
     * answers again the key is the same key. The store has no list of "transient" statuses to get wrong;
     * this pins that.
     */
    @Test
    fun everyKeychainRefusalThrowsAndKeepsTheKey() = runTest {
        val keychain = FakeKeychain()
        val appId = freshAppId()
        val support = freshDirectory("kuilt-keychain")
        val held = keychainDeviceKeyStore(appId, support, keychain) {}.deviceKey().id
        val statuses = listOf(errSecInteractionNotAllowed, errSecMissingEntitlement, errSecNotAvailable)

        val outcomes = statuses.map { status ->
            keychain.refusing = status
            var made = 0
            val failure = failureOf { keychainDeviceKeyStore(appId, support, keychain) { made++ }.deviceKey() }
            keychain.refusing = null
            Triple(status, failure, made)
        }
        val after = keychainDeviceKeyStore(appId, support, keychain) {}.deviceKey().id

        assertAll(
            *outcomes.map { (status, failure, made) ->
                {
                    assertTrue(failure is IllegalStateException, "OSStatus $status throws IllegalStateException: $failure")
                    assertTrue("OSStatus $status" in failure?.message.orEmpty(), "naming the status: ${failure?.message}")
                    assertEquals(0, made, "and OSStatus $status makes no key")
                }
            }.toTypedArray<() -> Unit>(),
            { assertEquals(1, keychain.adds, "one key was ever added") },
            { assertEquals(held, after, "once the Keychain answers, the key is the same key") },
        )
    }

    /**
     * Another process sharing the Keychain access group adds a key between this store's read and its
     * add. The store's add is refused (`errSecDuplicateItem`) and throws; it does not overwrite the
     * other process's key, and the next call adopts it.
     */
    @Test
    fun aKeyAnotherProcessAddedFirstIsAdoptedNotOverwritten() = runTest {
        val keychain = FakeKeychain()
        val appId = freshAppId()
        val support = freshDirectory("kuilt-keychain")
        val service = KEYCHAIN_SERVICE_PREFIX + appId
        val other = SoftwareDeviceKey.generate()
        val othersJwk = other.encodeJwk()
        keychain.beforeNextAdd = { keychain.plant(service, KEYCHAIN_ACCOUNT, othersJwk) }

        val failure = failureOf { keychainDeviceKeyStore(appId, support, keychain) {}.deviceKey() }
        val stillTheirs = keychain.held(service, KEYCHAIN_ACCOUNT)?.contentEquals(othersJwk)
        val adopted = keychainDeviceKeyStore(appId, support, keychain) {}.deviceKey().id

        assertAll(
            { assertTrue(failure is IllegalStateException, "the refused add throws: $failure") },
            { assertEquals(true, stillTheirs, "the other process's key is not overwritten") },
            { assertEquals(other.id, adopted, "and the next call adopts it") },
        )
    }

    /** A refused item is updated in place, never added beside or deleted first. */
    @Test
    fun aRefusedItemIsOverwrittenInPlace() = runTest {
        val keychain = FakeKeychain()
        val appId = freshAppId()
        val support = freshDirectory("kuilt-keychain")
        val service = KEYCHAIN_SERVICE_PREFIX + appId
        val first = keychainDeviceKeyStore(appId, support, keychain) {}.deviceKey().id
        keychain.plant(service, KEYCHAIN_ACCOUNT, byteArrayOf(1, 2, 3))

        val replacement = keychainDeviceKeyStore(appId, support, keychain) {}.deviceKey().id

        assertAll(
            { assertNotEquals(first, replacement, "precondition: the planted bytes were refused") },
            { assertEquals(1, keychain.adds, "the replacement is not a second add") },
            { assertEquals(1, keychain.updates, "it is one update of the item") },
        )
    }

    /**
     * The key is under service `us.tractat.kuilt.identity.<appId>` and account `device-key`, and the
     * grants under `<Application Support>/<appId>/kuilt-identity`; another app id is another device.
     */
    @Test
    fun platformKeepsEachAppApartUnderItsOwnService() = runTest {
        val keychain = FakeKeychain()
        val support = freshDirectory("kuilt-keychain")
        val store = keychainDeviceKeyStore("my-app", support, keychain) {}
        val me = store.deviceKey().id
        store.keepGrant(Grant.issue(DeviceKey.generate(), me, GrantScope.AnyRoomOfIssuer, T, T + HOUR))

        val keptAfterReopen = keychainDeviceKeyStore("my-app", support, keychain) {}.grants().size
        val otherApp = keychainDeviceKeyStore("other-app", support, keychain) {}.deviceKey().id
        val grantsDirectory = "$support/my-app/kuilt-identity"

        assertAll(
            { assertNotNull(keychain.held("us.tractat.kuilt.identity.my-app", "device-key"), "the key's item is named for the app") },
            { assertTrue(NSFileManager.defaultManager.fileExistsAtPath(grantsDirectory), "the grants are under $grantsDirectory") },
            { assertEquals(1, keptAfterReopen, "and kept there") },
            { assertNotEquals(me, otherApp, "another appId is another device") },
        )
    }

    @Test
    fun platformRefusesAnAppIdThatIsNotOneSegment() {
        val support = freshDirectory("kuilt-keychain")
        val bad = listOf("", ".", "..", "a/b", "a b", "../x")
        val refused = bad.filter { appId ->
            try {
                keychainDeviceKeyStore(appId, support, FakeKeychain()) {}
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
        assertEquals(bad, refused)
    }

    /** What [block] threw, or `null` if it returned. This test's own cancellation is never a verdict. */
    private suspend fun failureOf(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    private companion object {
        const val T = 1_700_000_000_000L
        const val HOUR = 3_600_000L
    }
}
