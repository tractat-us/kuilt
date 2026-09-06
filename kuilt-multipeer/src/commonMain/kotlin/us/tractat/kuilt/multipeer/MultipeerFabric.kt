package us.tractat.kuilt.multipeer

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.freshPeerId

/**
 * Join the Apple devices in one room into a session, with no Wi-Fi network to sign
 * on to and no server in the middle.
 *
 * Give it a service name both sides agree on and a name for this device to show up
 * as. One device hosts, the others see it in a nearby list and join; the phones and
 * Macs then talk directly to each other over whatever radio is available. Apple's
 * MultipeerConnectivity does the finding and the carrying — this returns it as a
 * kuilt [us.tractat.kuilt.core.Loom].
 *
 * ```kotlin
 * val loom = multipeerLoom(serviceType = "myapp-play", displayName = "Iain's iPhone")
 * ```
 *
 * This builds the same loom [MultipeerPeerLinkFactory]'s constructor does. What it
 * adds is the argument *order* every kuilt fabric factory shares (#1430): the
 * fabric's own required arguments first, then the universal knobs. The constructor
 * keeps its own older order and remains the surface for dependency injection and
 * tests.
 *
 * **[selfId] is the wire identity, and [displayName] is only a label.** That split
 * is new (#1430) and it is the point of this factory: the id a caller passes here
 * *is* the [PeerId] every remote peer sees for this device, so a consumer can name
 * its own peers instead of discovering after the fact what the fabric decided to
 * call it. The identity rides inside the advertised `MCPeerID.displayName` — Apple
 * exposes no other cross-process handle — which is why the two share one 63-byte
 * budget and why a long display name is trimmed.
 *
 * Three knobs the convention names are **deliberately absent**. An argument that is
 * accepted and then ignored — or whose arrival no test can witness — is worse than
 * one that does not exist:
 *
 * - **`weaveTimeout`** — no clock bounds `weave` on either path. Hosting starts an
 *   advertiser and returns immediately; joining sends `invitePeer` and returns
 *   immediately, and MC's own 30 s invitation timeout starts *after* `weave` has
 *   already handed back a seam, so it can never fail a `weave` call. It is a
 *   handshake timeout, not a weave timeout — the distinction `:kuilt-nearby` drew
 *   in #2333. Renaming it would publish a bound on `weave` that no path delivers.
 *
 * - **`policy`** — genuinely honoured at the layer below: `MCSessionLink` and
 *   `BridgePeerLink` both take a [us.tractat.kuilt.core.DeliveryPolicy] and both
 *   factories drop it, so every multipeer seam is pinned to `Reliable` today. It is
 *   omitted here anyway because it cannot yet be *shown* to arrive: both seams also
 *   hardcode `Dispatchers.Default` for their delivery drain, so a lossy-policy
 *   assertion through a woven seam races that drain instead of observing it. `policy`
 *   and `dispatcher` therefore have to land together, with a test like
 *   `NearbyLoomKnobsTest.deliveryPolicyReachesTheWovenSeamsInboundBuffer`. Adding an
 *   argument whose arrival no test can witness is the same defect as one that never
 *   arrives — the reader cannot tell them apart.
 *
 * - **`dispatcher`** — deferred with `policy`, and additionally awkward on the JVM:
 *   the seam lives *inside* the macOS dylib, so a dispatcher passed here schedules
 *   only the JNA-side half of the pipeline; the native half's scope is not reachable
 *   across the cdecl ABI.
 *
 * @param serviceType MultipeerConnectivity service-type string. 1–15 ASCII letters,
 *   digits or hyphens (Bonjour's `_service._tcp.` rules minus underscores). Both
 *   ends must use the same value; kuilt supplies no default.
 * @param displayName What a human sees for this device in a nearby list — a device
 *   name is the conventional choice. Cosmetic only. At most **26 bytes** of it
 *   survive, because it shares Apple's 63-byte name budget with [selfId] and the
 *   identity is kept whole; longer names are trimmed, never rejected.
 * @param selfId This peer's identity — the [PeerId] every remote observes. Defaults
 *   to a fresh random one, distinct on every call. Must not contain `#` and must be
 *   short enough to leave room for a display name; a violation throws from this
 *   call, not from a later `weave`.
 */
public fun multipeerLoom(
    serviceType: String,
    displayName: String,
    selfId: PeerId = freshPeerId(),
): MultipeerPeerLinkFactory =
    MultipeerPeerLinkFactory(
        displayName = displayName,
        serviceType = serviceType,
        selfId = selfId,
    )
