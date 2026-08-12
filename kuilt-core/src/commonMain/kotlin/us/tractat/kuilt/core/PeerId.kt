package us.tractat.kuilt.core

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Stable, comparable identifier for one peer within one session. */
@Serializable
@JvmInline
public value class PeerId(
    public val value: String,
)

/**
 * Mint a fresh, globally-unique [PeerId] for one peer.
 *
 * Uses a random UUID (v4) so two devices never mint the same identity without
 * coordinating first. This is the default a fabric's `selfId` parameter falls back to
 * when the consumer has no identity of its own to supply.
 *
 * **Why a UUID and not a counter.** Every fabric that once minted identities from a
 * per-loom monotonic counter restarted that counter at the same value on every device,
 * so two devices meeting for the first time each believed they were `peer-1` — the
 * `:kuilt-nw` defect (#1405) and, independently, the `:kuilt-nearby` one (#1432). A
 * random UUID removes the failure mode outright rather than narrowing it.
 *
 * **This is a seam-identity mint, not an RNG utility.** It deliberately reads the
 * platform's unseeded entropy source and takes no [kotlin.random.Random] parameter,
 * because a peer identity is not a simulation input: nothing in kuilt asserts on the
 * *value* it returns, only on distinctness. That exemption does not generalise —
 * anything whose behaviour a test needs to reproduce (dedup nonces, election jitter,
 * gossip fanout) still takes an injected `Random`.
 */
@OptIn(ExperimentalUuidApi::class)
public fun freshPeerId(): PeerId = PeerId(Uuid.random().toString())
