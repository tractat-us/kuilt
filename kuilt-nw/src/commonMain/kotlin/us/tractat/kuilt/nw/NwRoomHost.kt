package us.tractat.kuilt.nw

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.LoomRoomHost
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost

/**
 * Network.framework room host — the lobby surface for the peer-to-peer fabric.
 *
 * Wraps a [Loom] (typically an [NwLoom]) and a [Pattern], opens a host session,
 * and exposes it as a single [Room]. This is the drop-in counterpart to the
 * Multipeer room host: a consuming app swaps its Multipeer types for the `Nw`
 * ones mechanically — `NwLoom` for the peer-link factory (its
 * [NwLoom.visiblePeers] mirrors the Multipeer factory's discovery roster) and
 * this class for the room host — with no change to the room-driving code.
 *
 * Single-room lifecycle: one [NwRoomHost] hosts one session. The session
 * lifecycle (host once → [start]'s `onRoom` → suspend → leave on cancel) is the
 * transport-agnostic [LoomRoomHost] behaviour, which this delegates to; a second
 * [start] on the same host throws.
 *
 * Frame routing, per-peer addressing, and membership tracking are owned by
 * [Room] — this class adds no transport-specific demux. It is pure-commonMain
 * Kotlin over [Loom]/[Room] with no native-code path; the Network.framework
 * binding lives entirely behind [NwLoom]'s [NwApi].
 */
public class NwRoomHost(
    loom: Loom,
    sessionConfig: Pattern,
) : RoomHost by LoomRoomHost(loom, sessionConfig)
