package us.tractat.kuilt.identity

/**
 * Where a device keeps its own [DeviceKey] and the [Grant]s that let it back into rooms.
 *
 * A device is recognised when it comes back only if it comes back with the *same* key, so the key
 * has to outlive the app: a closed and reopened app, a restarted phone. That is this store's whole
 * job. The first [deviceKey] call makes the key and every later call returns it. Beside it the store
 * keeps the device's grants, the newest from each host for each scope, so a returning device has a
 * pass to show in every room it may come back to.
 *
 * A grant is not a secret — it names this device's key and is useless to anyone who cannot sign
 * with it — so a store keeps grants in plain view. The key is the secret, and how well it is kept is
 * what tells one store from another. [InMemoryDeviceKeyStore] keeps both only as long as the
 * process lives, which suits tests and an app that has chosen not to be recognised.
 *
 * Every implementation must be safe to call from many coroutines at once, on a multi-threaded
 * dispatcher: concurrent first calls to [deviceKey] agree on one key.
 *
 * @sample us.tractat.kuilt.identity.sampleDeviceKeyStore
 */
public interface DeviceKeyStore {
    /** This device's key: created on first call, the same on every call after. */
    public suspend fun deviceKey(): DeviceKey

    /** The grants this device holds: the newest per issuer and scope. Not secret — useless without the key. */
    public suspend fun grants(): List<Grant>

    /**
     * Keeps [grant], replacing the one held from the same [Grant.issuer] for the same [Grant.scope]
     * when [grant] is newer by [Grant.issuedAt]. One host key can host many rooms, so a pass into
     * `Room("A")` and one into `Room("B")` from the same issuer are both kept, and so is a grant from
     * another issuer. A grant that is not newer — older, or issued at the same instant — is ignored,
     * so a replayed or reordered copy can never displace the grant already held.
     *
     * The store does not check the grant: whether it is signed, names this device or is still good
     * is the room's question when the grant is presented.
     */
    public suspend fun keepGrant(grant: Grant)

    /**
     * Where each platform's one-line default hangs: `DeviceKeyStore.platform(appId, …)`, declared in
     * that platform's own source set, since what a platform needs to find its storage differs — a
     * directory on the desktop JVM.
     */
    public companion object
}

/**
 * These grants with [grant] kept: it replaces the grant from the same issuer for the same scope when
 * strictly newer by [Grant.issuedAt], is ignored when not, and is added when none for its issuer and
 * scope is held. Scopes compare by value. The one rule every [DeviceKeyStore] applies, so stores
 * cannot disagree about which grant survives.
 */
internal fun List<Grant>.keeping(grant: Grant): List<Grant> {
    val held = firstOrNull { it.issuer == grant.issuer && it.scope == grant.scope }
    return when {
        held == null -> this + grant
        grant.issuedAt > held.issuedAt -> map { if (it === held) grant else it }
        else -> this
    }
}
