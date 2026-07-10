package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId
import kotlin.random.Random

/**
 * The **shape** of the gossip overlay for one node: given the live roster, which
 * peers this node eager-floods broadcasts to (and GCs against), and which peers it
 * may sample as anti-entropy partners.
 *
 * A policy answers *who*, not *how*: [GossipSeam]'s relay, dedup, and anti-entropy
 * backstop run unchanged over any shape, and unicast
 * ([us.tractat.kuilt.core.Seam.sendTo]) is never governed by the policy — it only
 * ever shapes broadcast dissemination.
 *
 * The two shipped policies are **isotropic** — any k peers are as good as any other
 * k: [FullFanout] (the hub star) and [RandomKRegular] (the k-regular partial mesh).
 * Structural topologies (e.g. a two-tier server core + client periphery) implement
 * the same interface by returning position-dependent views.
 *
 * Any selection randomness is owned by the policy instance (see [RandomKRegular]),
 * never by the caller, so results are deterministic for a given seed + arguments.
 * A stateful instance belongs to one node — [GossipView] calls it from a single
 * coroutine; don't share one across seams.
 */
public interface TopologyPolicy {
    /**
     * The peers this node eager-floods to and GCs against, selected from [roster]
     * (which includes [self]; the result never does). Called on every roster or
     * liveness change with the currently-live members.
     */
    public fun activeView(
        self: PeerId,
        roster: Set<PeerId>,
    ): Set<PeerId>

    /**
     * The pool this node samples anti-entropy partners from — usually every other
     * member ([roster] minus [self]); structural topologies may restrict it (e.g.
     * tier-local). Never contains [self].
     */
    public fun antiEntropyPool(
        self: PeerId,
        roster: Set<PeerId>,
    ): Set<PeerId>
}

/**
 * Flood **every** other peer: the active view is the whole roster minus self. The
 * server-hub (star) policy — the hub re-floods each spoke's broadcast to all the
 * others. A node under this policy keeps no spares: the active view already covers
 * everyone.
 */
public object FullFanout : TopologyPolicy {
    override fun activeView(
        self: PeerId,
        roster: Set<PeerId>,
    ): Set<PeerId> = roster - self

    override fun antiEntropyPool(
        self: PeerId,
        roster: Set<PeerId>,
    ): Set<PeerId> = roster - self
}

/**
 * The federated two-tier shape: a small **[core]** of servers fully meshed among
 * themselves, each fronting a periphery of clients that attach to exactly one of
 * them. Unlike [FullFanout]/[RandomKRegular], this policy is **anisotropic** — the
 * view a node gets depends on *where it sits*, not just how many peers there are:
 *
 * - A **server** (`self ∈ core`) floods the **other servers plus its own local
 *   clients** — `(core - self) ∪ { client : attachment(client) == self }` — so a
 *   broadcast crosses the core once and each server fans it out to its own periphery.
 *   No server ever floods another server's clients.
 * - A **client** (`self ∉ core`) floods **only the one server it attaches to**
 *   (`attachment(self)`); that server is its sole relay into the rest of the graph.
 *
 * The union of every node's active view covers the whole roster, so a broadcast
 * originating anywhere reaches everyone — via at most one core hop.
 *
 * [attachment] maps a client to the server it attaches to, or `null` when the client
 * is unattached/unknown. A `null` (or off-roster) server yields an **empty** view:
 * the client can't flood until it (re)attaches — the failover seam is handled by the
 * bootstrap layer, not here. Every result is intersected with [roster] so only *live*
 * peers are targeted, and never contains [self].
 *
 * All selection is **deterministic** — [TwoTier] owns no randomness; it is a pure
 * function of `(self, roster, core, attachment)`. One instance per node.
 */
public class TwoTier(
    private val core: Set<PeerId>,
    private val attachment: (PeerId) -> PeerId?,
) : TopologyPolicy {
    override fun activeView(
        self: PeerId,
        roster: Set<PeerId>,
    ): Set<PeerId> =
        if (self in core) {
            val otherServers = (core - self) intersect roster
            val localClients = roster.filter { it !in core && attachment(it) == self }
            otherServers + localClients
        } else {
            setOfNotNull(attachment(self)) intersect roster
        }

    override fun antiEntropyPool(
        self: PeerId,
        roster: Set<PeerId>,
    ): Set<PeerId> =
        if (self in core) {
            (core - self) intersect roster
        } else {
            setOfNotNull(attachment(self)) intersect roster
        }
}

/**
 * The k-regular partial mesh: a seeded random **k-out** sample of the roster
 * (`k = recommendedActiveViewSize(N)`, via [partialView]), re-drawn on roster
 * churn. The union of every peer's independent draw is connected with high
 * probability — see [recommendedActiveViewSize].
 *
 * [random] **must be seeded per-peer by the caller** (e.g. from the peer's
 * identity) so peers choose independently — a shared seed would make every peer
 * pick the same neighbours, collapsing the random graph. The policy owns all
 * selection randomness; use one instance per node.
 */
public class RandomKRegular(
    private val random: Random,
) : TopologyPolicy {
    override fun activeView(
        self: PeerId,
        roster: Set<PeerId>,
    ): Set<PeerId> =
        partialView(
            self = self,
            roster = roster,
            k = recommendedActiveViewSize(roster.size),
            spareCount = 0,
            random = random,
        ).active

    override fun antiEntropyPool(
        self: PeerId,
        roster: Set<PeerId>,
    ): Set<PeerId> = roster - self
}
