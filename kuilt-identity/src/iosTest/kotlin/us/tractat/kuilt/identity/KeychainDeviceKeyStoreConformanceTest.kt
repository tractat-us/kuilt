package us.tractat.kuilt.identity

import platform.Security.errSecInteractionNotAllowed
import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.KeyWriteInterruption
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.UnreadableKey
import us.tractat.kuilt.store.NSFileManagerDurableStore

/**
 * The iOS store against the whole [DeviceKeyStoreConformanceSuite], as a [DeviceKeyStoreFixture.Durable]
 * store over a [FakeKeychain] and a fresh Application Support directory per fixture. Every reopen is a
 * new store over the same fake Keychain, service and directory, sharing no memory with the last.
 *
 * **What this covers, and what it does not.** It runs the store's own logic — what it reads, refuses,
 * replaces and throws on, its locking, its grants — over a fake of the Keychain, because the iOS
 * simulator test binary a default Gradle run spawns has no Keychain (`errSecNotAvailable`, measured on
 * #3225). It does **not** run [SecItemKeychain]: whether the real Keychain answers each fault with the
 * status the fake gives is #3310's nightly run on a booted simulator.
 *
 * Each damage hook leaves the fake as that failure would leave the real Keychain:
 *
 * - **corrupt key:** the item's data cut to half its length, so it is no longer JSON;
 * - **mismatched key:** a well-formed JWK whose `x` and `y` are one fresh key's and whose `d` is
 *   another's, so it decodes and fails only the self-test;
 * - **cleared key:** the item removed;
 * - **corrupt grants:** three bytes that are not a grants record, written through the grant store
 *   itself;
 * - **unreadable key:** every call answers `errSecInteractionNotAllowed`, what an item kept
 *   `AfterFirstUnlock` answers before the phone's first unlock after a restart, with the item itself
 *   untouched (`Rigged`);
 * - **interrupted write:** `AtomicMedium`, for the reason the hook gives below.
 */
class KeychainDeviceKeyStoreConformanceTest : DeviceKeyStoreConformanceSuite() {
    override suspend fun newFixture(): DeviceKeyStoreFixture {
        val keychain = FakeKeychain()
        val appId = freshAppId()
        val applicationSupport = freshDirectory("kuilt-keychain")
        val service = KEYCHAIN_SERVICE_PREFIX + appId
        return DeviceKeyStoreFixture.Durable(
            reopen = { beforeKeyIsMade -> keychainDeviceKeyStore(appId, applicationSupport, keychain, beforeKeyIsMade) },
            corruptKey = {
                val held = checkNotNull(keychain.held(service, KEYCHAIN_ACCOUNT)) { "precondition: a key to corrupt" }
                keychain.plant(service, KEYCHAIN_ACCOUNT, held.copyOf(held.size / 2))
            },
            plantMismatchedKey = {
                val (jwk, claimed) = mismatchedJwk()
                keychain.plant(service, KEYCHAIN_ACCOUNT, jwk)
                claimed
            },
            clearKey = { check(keychain.remove(service, KEYCHAIN_ACCOUNT)) { "precondition: there was a key to clear" } },
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
        )
    }
}
