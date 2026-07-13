package us.tractat.kuilt.nw

import com.sun.jna.Pointer
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag

/**
 * Start hosting a peer-to-peer session over Apple's local radios from a **macOS
 * desktop JVM**, encrypted with a code you share out of band.
 *
 * The JVM counterpart of the appleMain `nwHost`: it derives the TLS pre-shared
 * key from [Pattern.roomKey] (via HKDF-SHA256, JVM-side), then hands the raw key
 * bytes across JNA to the macOS `libkuilt.dylib`, which drives the real
 * `RealNwApi` over Network.framework. Everyone who wants in must be given the same
 * `roomKey` (the session's password) ahead of time.
 *
 * - **The `roomKey` is a bearer secret, not a label** — a `null` [Pattern.roomKey]
 *   throws immediately, before any network is touched. An open, unencrypted
 *   session is not allowed on this fabric.
 * - **macOS-only** — on a non-macOS JVM (or without the dylib) this throws an
 *   [IllegalStateException] pointing at the macOS-only limitation; probe
 *   [NwNativeLib.jvmAvailability] first if you need to branch gracefully.
 *
 * @param pattern     the session to open; its [Pattern.roomKey] is the required bearer secret.
 * @param serviceType the Bonjour service type to advertise and browse (e.g. `"_kuilt._tcp"`).
 * @throws IllegalArgumentException if [Pattern.roomKey] is `null`.
 * @throws IllegalStateException if the macOS bridge dylib cannot be loaded (non-macOS JVM).
 */
public suspend fun nwHost(pattern: Pattern, serviceType: String): Seam {
    val secret = requireNotNull(pattern.roomKey) {
        "nwHost requires a non-null Pattern.roomKey: on the Apple Network.framework fabric the " +
            "roomKey is the bearer secret the TLS pre-shared key is derived from — an open, " +
            "unencrypted session is not allowed. Set Pattern.roomKey to the code you share " +
            "out of band with joiners."
    }
    val (lib, handle) = createRuntime(NwPsk.derive(secret, serviceType))
    return NwLoom(BridgeNwApi(lib, handle), serviceType).host(pattern)
}

/**
 * Join a peer-to-peer session over Apple's local radios from a **macOS desktop
 * JVM**, encrypted with a code you were given out of band.
 *
 * The JVM counterpart of the appleMain `nwJoin`. You must supply the same
 * `roomKey` (the [Tag.roomKey]) the host used.
 *
 * - **The `roomKey` is a bearer secret, not a label** — a `null` [Tag.roomKey]
 *   throws immediately. You cannot join an open, unencrypted session because
 *   there is no such thing here.
 * - **macOS-only** — throws an [IllegalStateException] on a non-macOS JVM.
 *
 * @param tag         the session to join; its [Tag.roomKey] is the required bearer secret.
 * @param serviceType the Bonjour service type to browse (e.g. `"_kuilt._tcp"`).
 * @throws IllegalArgumentException if [Tag.roomKey] is `null`.
 * @throws IllegalStateException if the macOS bridge dylib cannot be loaded (non-macOS JVM).
 */
public suspend fun nwJoin(tag: Tag, serviceType: String): Seam {
    val secret = requireNotNull(tag.roomKey) {
        "nwJoin requires a non-null Tag.roomKey: on the Apple Network.framework fabric the " +
            "roomKey is the bearer secret the TLS pre-shared key is derived from — you cannot " +
            "join an open, unencrypted session. Set Tag.roomKey to the code the host shared " +
            "with you out of band."
    }
    val (lib, handle) = createRuntime(NwPsk.derive(secret, serviceType))
    return NwLoom(BridgeNwApi(lib, handle), serviceType).join(tag)
}

/**
 * Load the dylib (fail fast with the macOS-only message off macOS) and create a
 * native runtime seeded with the derived PSK/identity bytes.
 */
private fun createRuntime(psk: NwPskMaterial): Pair<NwNativeLib, Pointer> {
    val lib = NwNativeLib.load() ?: error(NwNativeLib.UNAVAILABLE_REASON)
    // Fail fast on an ABI mismatch (a stale/wrong-arch libkuilt.dylib on the classpath) before we
    // pass any pointers across the cdecl boundary — a version skew there is otherwise a silent UAF.
    val abi = lib.kuilt_protocol_version()
    check(abi == NwNativeLib.EXPECTED_PROTOCOL_VERSION) {
        "stale or mismatched libkuilt.dylib: bridge ABI $abi != expected ${NwNativeLib.EXPECTED_PROTOCOL_VERSION}"
    }
    val handle = lib.nw_runtime_create(psk.psk, psk.psk.size, psk.identity, psk.identity.size)
        ?: error("nw_runtime_create returned null on a macOS host — likely a stale or wrong-arch dylib")
    return lib to handle
}
