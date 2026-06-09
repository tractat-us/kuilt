@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.replicator.ReplicatorMessage
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig

/**
 * Creates a [SeamReplicator] over [Room.channel]`(id)` in one call.
 *
 * This is a thin convenience wrapper that eliminates the three-step wiring pattern:
 * ```kotlin
 * // Before:
 * SeamReplicator(
 *     replica = ReplicaId(room.selfId.value),
 *     seam = room.channel(channelId),
 *     initial = initial,
 *     messageSerializer = ReplicatorMessage.serializer(stateSerializer),
 *     scope = scope,
 *     config = config,
 * )
 *
 * // After:
 * RoomReplicator(room, channelId, initial, stateSerializer, scope)
 * ```
 *
 * The admit-gating guarantee of [Room.channel] is preserved unchanged — the
 * underlying [us.tractat.kuilt.core.Seam] only surfaces admitted peers, so this
 * replicator will never send state to unadmitted peers.
 *
 * @param room the [Room] whose [Room.channel] provides the admit-gated [us.tractat.kuilt.core.Seam].
 * @param id channel identifier passed to [Room.channel]. Same id on both sides of a
 *   2-peer room maps to the same logical channel.
 * @param initial the starting state (typically the CRDT's zero / empty value).
 * @param stateSerializer a [KSerializer] for [S]. The wrapper derives the
 *   [ReplicatorMessage] envelope serializer internally.
 * @param scope the [CoroutineScope] for background replication coroutines. In tests pass
 *   `backgroundScope` from [kotlinx.coroutines.test.TestScope].
 * @param config optional tuning; defaults to [SeamReplicatorConfig] production defaults.
 *
 * @return a fully wired [SeamReplicator]`<S>` whose [SeamReplicator.replica] is derived
 *   from [Room.selfId].
 */
public fun <S : Quilted<S>> RoomReplicator(
    room: Room,
    id: String,
    initial: S,
    stateSerializer: KSerializer<S>,
    scope: CoroutineScope,
    config: SeamReplicatorConfig = SeamReplicatorConfig(),
): SeamReplicator<S> = SeamReplicator(
    replica = ReplicaId(room.selfId.value),
    seam = room.channel(id),
    initial = initial,
    messageSerializer = ReplicatorMessage.serializer(stateSerializer),
    scope = scope,
    config = config,
)
