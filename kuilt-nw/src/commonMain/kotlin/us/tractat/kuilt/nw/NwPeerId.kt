package us.tractat.kuilt.nw

import us.tractat.kuilt.core.PeerId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Mint a fresh, globally-unique [PeerId] for one peer.
 *
 * Uses a random UUID (v4) so identities are unique across devices without
 * coordination. This replaces the per-loom monotonic counter used by the Nearby
 * fabric, which mints the same `peer-0`/`peer-1` sequence on every device and so
 * collides the instant two devices meet (#1405). `NwLoom` (Task 2.7) calls this
 * for its default self-identity.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun freshPeerId(): PeerId = PeerId(Uuid.random().toString())
