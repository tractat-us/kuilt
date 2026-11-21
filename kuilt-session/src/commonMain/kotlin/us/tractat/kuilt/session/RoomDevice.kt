package us.tractat.kuilt.session

import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.KeyScheme

/**
 * This device as a [SeamRoom] holds it: its key store, and the key already read from that store.
 *
 * The key is read once, by [of], before the room exists, and the room never reads the store for it
 * again (#3210). A room's admit path — the joiner's `Hello`, the host's intro `Welcome` — runs inside
 * launched loops where a throw has nowhere to go: on the host it would escape a `scope.launch`, which
 * cancels sibling work or aborts a Kotlin/Native process and leaves the joiner with no answer; on the
 * joiner it would end the main loop before the admit deadline is armed. A platform store can fail on
 * any read (a Keychain item is unavailable while the phone is locked), so the one read happens where a
 * failure reaches the caller of `host`/`join`/`adopt`, and the room holds a value.
 *
 * [scheme] is the store's [DeviceKeyStore.keyScheme], read once here with the key: everything the
 * room signs and checks uses it, so one room never mixes two schemes (#3211).
 *
 * [store] is kept for what a room reads from it later: grants.
 */
internal class RoomDevice private constructor(
    val store: DeviceKeyStore,
    val key: DeviceKey,
    val scheme: KeyScheme,
) {
    internal companion object {
        /** Reads [store]'s key and scheme; a store that cannot produce its key throws here, to the caller. */
        suspend fun of(store: DeviceKeyStore): RoomDevice = RoomDevice(store, store.deviceKey(), store.keyScheme)
    }
}
