@file:OptIn(IdentityInternalApi::class)

package us.tractat.kuilt.identity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [DeviceKeyStore] that keeps everything in memory, and forgets everything with the process.
 *
 * Its key is made in software on the first [deviceKey] call and is gone when the process ends, so a
 * device using this store is a new device every time the app starts: nothing it was granted, and no
 * seat it held, can be recognised after a restart. That is right for tests and for an app that has
 * chosen not to be recognised; an app that wants to be remembered uses a platform store.
 *
 * Safe under a multi-threaded dispatcher: one [Mutex] guards the key and the grants, and is held
 * across the first key's generation, so concurrent first calls wait for that one key rather than
 * each making their own.
 */
public class InMemoryDeviceKeyStore private constructor(
    /** Called each time this store is about to make a key, and at no other time: the TCK's gate. */
    private val beforeKeyIsMade: suspend () -> Unit,
    private val scheme: KeyScheme,
) : DeviceKeyStore {
    /** A store whose key, made on first use, is [DeviceKey.generate]'s. */
    public constructor() : this({}, KeyScheme.P256)

    /**
     * A store whose key, made on first use, is [scheme]'s, and whose [keyScheme] is [scheme], with the
     * same grant rules as every other store. It takes any scheme: the opt-in is the only fence.
     */
    @IdentityInternalApi
    public constructor(scheme: KeyScheme) : this({}, scheme)

    @IdentityInternalApi
    override val keyScheme: KeyScheme get() = scheme

    private val mutex = Mutex()
    private var key: DeviceKey? = null
    private var held: List<Grant> = emptyList()

    override suspend fun deviceKey(): DeviceKey = mutex.withLock {
        key ?: run {
            beforeKeyIsMade()
            scheme.generate()
        }.also { key = it }
    }

    override suspend fun grants(): List<Grant> = mutex.withLock { held }

    override suspend fun keepGrant(grant: Grant): Unit = mutex.withLock { held = held.keeping(grant) }

    internal companion object {
        /**
         * A store that calls [beforeKeyIsMade] each time it is about to make a key: the conformance
         * suite's gate.
         */
        fun gatedBy(beforeKeyIsMade: suspend () -> Unit): InMemoryDeviceKeyStore =
            InMemoryDeviceKeyStore(beforeKeyIsMade, KeyScheme.P256)
    }
}
