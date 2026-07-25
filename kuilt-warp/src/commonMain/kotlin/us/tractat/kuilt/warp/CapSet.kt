package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable

/**
 * The set of capabilities a peer advertises about **itself** — the value carried in a peer's
 * slot of the capability board (H8, design §14.6).
 *
 * A `CapSet` is **soft state**: each peer publishes its own slot into an
 * [`EphemeralMap`][us.tractat.kuilt.crdt.EphemeralMap] (best-effort broadcast, aged out by
 * local receive time), exactly like heddle's demand board. It is presence, not a ledger — a
 * stale, duplicated, or expiring advertisement is fine, because the worst it can do is briefly
 * *misplace* work, which warp's `Results` dedup and ring re-home already absorb. It can never
 * authorize anything: eligibility introduces no conserved quantity, so it cannot touch
 * conservation (design §14.6).
 *
 * Two shapes of attribute are supported, both opaque to warp core:
 * - **[tokens]** — boolean capabilities a peer either has or does not (`"GPU"`, `"AVX512"`,
 *   a held dataset id, a runtime name). Tested with [has].
 * - **[attributes]** — keyed capabilities with a value (`"region" → "us-east"`, a memory
 *   class, a cost tier). Tested with [attr].
 *
 * The token/attribute strings are caller-defined; warp core never interprets them — it only
 * evaluates an [Affinity] predicate against them.
 *
 * @property tokens the boolean capabilities this peer serves.
 * @property attributes the keyed capabilities this peer serves.
 */
@Serializable
public data class CapSet(
    public val tokens: Set<String> = emptySet(),
    public val attributes: Map<String, String> = emptyMap(),
) {
    /** Whether this peer advertises the boolean capability [token]. */
    public fun has(token: String): Boolean = token in tokens

    /** The value this peer advertises for keyed capability [key], or `null` if it advertises none. */
    public fun attr(key: String): String? = attributes[key]

    public companion object {
        /** A peer advertising nothing — eligible only for [Affinity.Anywhere] work. */
        public val EMPTY: CapSet = CapSet()
    }
}
