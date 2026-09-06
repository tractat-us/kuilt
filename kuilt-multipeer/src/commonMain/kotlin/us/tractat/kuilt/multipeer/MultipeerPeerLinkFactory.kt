package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.freshPeerId

/**
 * `Loom` backed by Apple's MultipeerConnectivity framework.
 *
 * Common API only — implementations live in the platform-specific source sets.
 * `appleMain` provides the real implementation against
 * `platform.MultipeerConnectivity.*`; `jvmMain` wraps a
 * `libkuilt.dylib` over JNA so macOS desktop builds can talk to
 * iPhones.
 *
 * The factory is **stateful** — it owns the underlying `MCPeerID`/`MCSession`
 * instances and the in-flight discovery map that gives
 * [MultipeerAdvertisement.handle] its meaning. Construct one per device, share
 * it via DI, and rely on `Loom.open` / `Loom.join` to
 * spin up a session.
 *
 * @param displayName Local display name surfaced to remote peers for **display
 *   only** — it is not the identity. Keep it short and recognisable (device name
 *   is the conventional choice). At most **26 bytes** of it survive: it shares
 *   Apple's 63-byte `MCPeerID.displayName` budget with [selfId], and the identity
 *   is kept whole. Longer names are trimmed, never rejected.
 * @param serviceType MultipeerConnectivity service-type string. Must be 1–15 ASCII letters,
 *   digits, or hyphens (same rules as Bonjour `_service._tcp.` minus underscores).
 *   Callers supply their own value; kuilt does not provide a default.
 * @param selfId This peer's wire identity. It **is** the [PeerId] every remote
 *   observes: it is baked into the advertised `MCPeerID.displayName` and both ends
 *   derive it back out of that one string. Must not contain `#` (the delimiter) and
 *   must be short enough to leave room for a display name — a `freshPeerId()` UUID
 *   costs 37 of the 63 bytes. A violation throws from **this constructor**, not from
 *   [weave].
 */
public expect class MultipeerPeerLinkFactory(
    displayName: String,
    serviceType: String,
    selfId: PeerId = freshPeerId(),
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam

    /**
     * Snapshot of advertisements visible to this factory's current browse
     * session. Updated reactively by the browse delegate on both `foundPeer`
     * and `lostPeer` events, so the lobby's peer list reflects current
     * reality (a host that stops advertising drops out of the set, even on
     * the same device). Emitted as a [StateFlow] so the UI can `collectAsState`.
     *
     * Empty when no `MultipeerServiceBrowser.discoveries` flow is currently
     * being collected.
     */
    public val visiblePeers: StateFlow<Set<MultipeerAdvertisement>>

    /**
     * Stops advertising / browsing / disconnects any active session.
     * Safe to call multiple times. The factory remains usable: a fresh
     * `open` / `join` call re-creates the session.
     */
    public fun close()
}
