package us.tractat.kuilt.gossip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.LinkAdmission
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.gossip.HostedOverlay")

/**
 * Wrap [base] in a gossip-relay layer under an explicit [topology] policy, with **zero recompute
 * jitter**, started on the receiver scope. The generalization of [starOverlay] (which is just
 * `policyOverlay(base, FullFanout, …)`): the same started-[GossipSeam] relay interior, but the
 * dissemination *shape* is whatever [TopologyPolicy] the caller passes — [FullFanout] (the hub
 * star), [RandomKRegular] (the k-regular partial mesh), or [TwoTier] (a federated server core +
 * client periphery). Broadcasts flood along the policy's active view; [Seam.sendTo]
 * [us.tractat.kuilt.core.Seam.sendTo] passes through unwrapped — the tested unicast invariant
 * that keeps per-recipient traffic off the flood path is independent of the shape.
 *
 * Zero jitter is deliberate, not tuning, and it carries across every shape passed here — the two
 * shipped structural/full policies ([FullFanout], [TwoTier]) are **deterministic** functions of
 * the roster (and, for [TwoTier], the core + attachment), so the anti-lockstep jitter buys them
 * nothing and only opens a window where a freshly admitted peer is missing from the flood targets
 * — a broadcast in that window would skip the peer and leave a per-origin seq gap it then
 * reorder-holds behind (#1309). The view recomputes synchronously with each roster change instead.
 * (A caller wanting jittered recompute for an isotropic random mesh constructs [GossipSeam]
 * directly; this overlay is the deterministic-shape composition path.)
 *
 * @param base the local endpoint this node relays over — a hub's fanout seam, a spoke's link to
 *   the hub, or a server/client's link into a two-tier graph.
 * @param topology the dissemination shape — who this node eager-floods broadcasts to. **Required**:
 *   the overlay's whole job is to name a shape, so there is no default (unlike [GossipSeam], whose
 *   default is a random mesh).
 * @param random RNG for overlay bookkeeping (and for a policy that owns selection randomness, e.g.
 *   [RandomKRegular]); tests inject a seeded instance.
 * @param clock Clock for the overlay's per-neighbour liveness detectors. **Required** — no
 *   wall-clock default, so a virtual-time caller can never silently fall through to the system
 *   clock. Production callers pass `{ kotlin.time.Clock.System.now() }`.
 */
public fun CoroutineScope.policyOverlay(
    base: Seam,
    topology: TopologyPolicy,
    random: Random = Random.Default,
    clock: () -> Instant,
): Seam = GossipSeam(
    base = base,
    random = random,
    clock = clock,
    topology = topology,
    jitter = Duration.ZERO..Duration.ZERO,
).also { it.start(this) }

/**
 * Wrap [base] in the star-relay policy layer: a [GossipSeam] with [FullFanout] and **zero
 * recompute jitter**, started on the receiver scope. A thin alias for
 * [policyOverlay]`(base, FullFanout, …)` — the star is the full-fanout special case; the general
 * form ([policyOverlay]) drives any [TopologyPolicy] with the same started-seam interior and the
 * same zero-jitter determinism.
 *
 * This is the relay interior every hub-star composition shares — [hostedOverlay] applies it to
 * an accept-pumped mesh, and a per-room game composition applies it to each
 * [us.tractat.kuilt.core.RoomHubSeam] (and, spoke-side, to each session-mux channel). Wrapped
 * this way, a spoke's broadcast is re-flooded by the hub to every other spoke while
 * [Seam.sendTo][us.tractat.kuilt.core.Seam.sendTo] passes through unwrapped — the tested
 * unicast invariant that keeps per-recipient traffic off the flood path.
 *
 * Zero jitter is deliberate, not tuning: the [FullFanout] view is deterministic (everyone), so
 * the anti-lockstep jitter buys nothing and only opens a window where a freshly admitted spoke
 * is missing from the flood targets — a broadcast in that window would skip the spoke and leave
 * a per-origin seq gap it then reorder-holds behind (#1309). The view recomputes synchronously
 * with each roster change instead.
 *
 * @param base the star's local endpoint — the hub's fanout seam, or a spoke's link to the hub.
 * @param random RNG for overlay bookkeeping; tests inject a seeded instance.
 * @param clock Clock for the overlay's per-neighbour liveness detectors. **Required** — no
 *   wall-clock default, so a virtual-time caller can never silently fall through to the system
 *   clock. Production callers pass `{ kotlin.time.Clock.System.now() }`.
 */
public fun CoroutineScope.starOverlay(
    base: Seam,
    random: Random = Random.Default,
    clock: () -> Instant,
): Seam = policyOverlay(base, FullFanout, random, clock)

/**
 * Compose a started hub [Seam] from a [ConnectionSource]: an initially-empty [meshSeam] wrapped in
 * a [GossipSeam] with [FullFanout] (the hub floods every broadcast to all spokes),
 * plus an accept-pump that [addLink][us.tractat.kuilt.core.fabric.Mesh.addLink]s each accepted
 * [us.tractat.kuilt.core.fabric.Connection] so clients join the running hub as they connect. The
 * pump coroutine lives on the receiver scope and is torn down with it.
 *
 * A failed admit (torn or garbled spoke — client drops before or during the [MeshHello][us.tractat.kuilt.core.fabric.meshSeam]
 * preamble, or a link the [admission] policy rejects) is best-effort: the bad connection is dropped
 * and the pump continues accepting the next one. This mirrors [us.tractat.kuilt.core.fabric.Mesh]'s
 * own per-link tolerance in its read loop. Cancellation still propagates so the pump exits cleanly
 * when the receiver scope is torn down.
 *
 * The returned hub seam is a [us.tractat.kuilt.core.PrincipalRoster]: principals attached to
 * accepted connections (a `KtorConnectionSource` `principalExtractor`, or
 * [us.tractat.kuilt.core.withPrincipal] in tests) are observable per admitted peer.
 *
 * This is the production form of the in-memory star the test harness composes by hand; the harness
 * is re-expressed on top of it so there is one composition path.
 *
 * @param admission Per-link admission policy, enforced at the hub mesh between each spoke's
 *   `MeshHello` handshake and its publication. Defaults to [LinkAdmission.AcceptAll] (today's open
 *   behaviour); once supplied, the policy is authoritative for **every** spoke, including
 *   unattested ones. A rejected spoke surfaces as one debug-logged drop and the hub keeps serving.
 */
public suspend fun CoroutineScope.hostedOverlay(
    selfId: PeerId,
    source: ConnectionSource,
    dispatcher: CoroutineContext,
    random: Random = Random.Default,
    clock: () -> Instant = { Clock.System.now() },
    admission: LinkAdmission = LinkAdmission.AcceptAll,
): Seam = starOverlay(hostedMesh(selfId, source, dispatcher, admission), random, clock)

/**
 * Compose the **raw** accept-pumped hub mesh from a [ConnectionSource]: an initially-empty
 * [meshSeam] plus the accept-pump that [addLink][us.tractat.kuilt.core.fabric.Mesh.addLink]s each
 * accepted [us.tractat.kuilt.core.fabric.Connection] as clients connect. This is [hostedOverlay]
 * **without** the star-relay flood — the seam whose `sendTo` reaches each spoke directly and whose
 * `broadcast` is *not* re-flooded.
 *
 * This is the seam a commit-safe game bootstrap wants as its **base**: pass it to
 * `gameHost(seam = hostedMesh(...), overlay = { starOverlay(it, …) })` so consensus (Raft +
 * heartbeat) is muxed **below** the flood while only the broadcast plane rides the star relay
 * (#1370). Do **not** re-introduce `gameHost(seam = hostedOverlay(...))` — that puts the whole mux,
 * Raft included, *above* the flood, so the overlay's origin-restamping can launder a forged
 * consensus frame.
 *
 * The returned mesh is a [us.tractat.kuilt.core.PrincipalRoster]: principals attached to accepted
 * connections are observable per admitted peer (see [hostedOverlay]). The pump coroutine lives on
 * the receiver scope and is torn down with it; a failed admit is best-effort (the bad connection is
 * dropped and the pump keeps accepting).
 *
 * @param admission Per-link admission policy, enforced at the hub mesh between each spoke's
 *   `MeshHello` handshake and its publication. Defaults to [LinkAdmission.AcceptAll].
 */
public suspend fun CoroutineScope.hostedMesh(
    selfId: PeerId,
    source: ConnectionSource,
    dispatcher: CoroutineContext,
    admission: LinkAdmission = LinkAdmission.AcceptAll,
): Seam {
    val hubMesh = meshSeam(selfId = selfId, connections = emptyList(), dispatcher = dispatcher, admission = admission)
    launch {
        while (isActive) {
            val conn = source.accept()
            runCatchingCancellable { hubMesh.addLink(conn) }
                .onFailure {
                    // Reject-and-continue: a torn/garbled spoke (client dropped during the MeshHello
                    // preamble) or an admission-rejected link (LinkRejectedException) surfaces here.
                    // Log the one dropped spoke at debug and keep accepting — the hub and every
                    // admitted link stay intact.
                    logger.debug { "hostedMesh: dropping rejected/torn spoke — ${it.message}" }
                }
        }
    }
    return hubMesh
}
