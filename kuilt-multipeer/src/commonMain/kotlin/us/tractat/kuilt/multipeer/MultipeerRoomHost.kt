package us.tractat.kuilt.multipeer

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.LoomRoomHost
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost

/**
 * Multipeer-Connectivity room host. Wraps a [Loom] and a [Pattern], opens a
 * host session, and exposes it as a single [Room].
 *
 * Single-room lifecycle: one [MultipeerRoomHost] hosts one session. The session
 * lifecycle (host once → [start]'s `onRoom` → suspend → leave on cancel) is the
 * transport-agnostic [LoomRoomHost] behaviour, which this delegates to.
 *
 * Frame routing, per-peer addressing, and membership tracking are owned by
 * [Room] — the manual demux that `MCLeaderListener` carried before
 * `:kuilt-session` existed is gone.
 *
 * **JNA dylib note:** this class is pure-commonMain Kotlin over [Loom]/[Room].
 * It has no native-code path. The [MultipeerPeerLinkFactory] dylib basenames and
 * JNA constants are unchanged and outside the scope of this class.
 */
public class MultipeerRoomHost(
    loom: Loom,
    sessionConfig: Pattern,
) : RoomHost by LoomRoomHost(loom, sessionConfig)
