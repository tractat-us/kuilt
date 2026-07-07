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
 * Wrap [base] in the star-relay policy layer: a [GossipSeam] with [FullFanout] and **zero
 * recompute jitter**, started on the receiver scope.
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
): Seam = GossipSeam(
    base = base,
    random = random,
    clock = clock,
    topology = FullFanout,
    jitter = Duration.ZERO..Duration.ZERO,
).also { it.start(this) }

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
): Seam {
    val hubMesh = meshSeam(selfId = selfId, connections = emptyList(), dispatcher = dispatcher, admission = admission)
    val hub = starOverlay(hubMesh, random, clock)
    launch {
        while (isActive) {
            val conn = source.accept()
            runCatchingCancellable { hubMesh.addLink(conn) }
                .onFailure {
                    // Reject-and-continue: a torn/garbled spoke (client dropped during the MeshHello
                    // preamble) or an admission-rejected link (LinkRejectedException) surfaces here.
                    // Log the one dropped spoke at debug and keep accepting — the hub and every
                    // admitted link stay intact.
                    logger.debug { "hostedOverlay: dropping rejected/torn spoke — ${it.message}" }
                }
        }
    }
    return hub
}
