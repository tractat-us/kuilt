package us.tractat.kuilt.identity

import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess

/**
 * A [KeychainBackend] over a map, answering the `OSStatus`es the real Keychain answers for the same
 * calls: `errSecItemNotFound` for a missing item, `errSecDuplicateItem` for adding one that exists.
 *
 * What it cannot be: the Keychain. It keeps no accessibility class or sync flag — those are the add
 * query's, pinned by `KeychainDeviceKeyStoreTest.keychainItemIsThisDeviceOnlyAndUnsynced` — and which
 * status a real Keychain answers for each fault is this fake's assumption. The run against a real
 * Keychain on a booted simulator is #3310's.
 *
 * Not thread-safe: the tests drive it from one `runTest` thread.
 */
internal class FakeKeychain : KeychainBackend {
    private val items = HashMap<Pair<String, String>, ByteArray>()

    /** While set, every call answers this status, as a Keychain that cannot be reached right now. */
    var refusing: Int? = null

    /** Runs once, inside the next [add], before it looks: how another process gets there first. */
    var beforeNextAdd: (() -> Unit)? = null

    var adds: Int = 0
        private set

    var updates: Int = 0
        private set

    override fun copy(service: String, account: String): KeychainCopy {
        refusing?.let { return KeychainCopy(it, null) }
        val held = items[service to account] ?: return KeychainCopy(errSecItemNotFound, null)
        return KeychainCopy(errSecSuccess, held.copyOf())
    }

    override fun add(service: String, account: String, data: ByteArray): Int {
        refusing?.let { return it }
        beforeNextAdd?.also { beforeNextAdd = null }?.invoke()
        if (service to account in items) return errSecDuplicateItem
        adds++
        items[service to account] = data.copyOf()
        return errSecSuccess
    }

    override fun update(service: String, account: String, data: ByteArray): Int {
        refusing?.let { return it }
        if (service to account !in items) return errSecItemNotFound
        updates++
        items[service to account] = data.copyOf()
        return errSecSuccess
    }

    fun held(service: String, account: String): ByteArray? = items[service to account]?.copyOf()

    /** Holds [data] under [service] and [account], whatever it is. */
    fun plant(service: String, account: String, data: ByteArray) {
        items[service to account] = data.copyOf()
    }

    /** Removes the item; whether there was one. */
    fun remove(service: String, account: String): Boolean = items.remove(service to account) != null
}
