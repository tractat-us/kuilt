package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable

private val log = KotlinLogging.logger("us.tractat.kuilt.cluster.RoutedUnicastRouter")

/**
 * Carry one per-recipient message to *exactly one* player who sits behind a
 * *different* server — the **cross-core routed unicast**.
 *
 * In a federation, three servers form a fully-meshed core and a game's players
 * each connect to whichever server is nearest them: Alice through S1, Bob through
 * S2, Carol through S3. When a message is meant for only one of them — a card only
 * Bob may see — it must reach Bob and *nobody else*. If Bob is behind a different
 * server than the one that holds the message, the message takes a two-hop route:
 *
 * ```
 * spoke → server → core → server → spoke
 * ```
 *
 * The origin server asks the [attachment directory][AttachmentDirectory] "which
 * server is Bob behind right now?", hands the message across the core to *that one
 * server* (and no other), and that server hands it down to *that one player* (and
 * no other). At no point is the message ever copied to a second recipient.
 *
 * ## The leak boundary — why this is unicast, never broadcast
 *
 * kuilt keeps per-recipient secrets safe on one guarantee: a point-to-point send
 * ([Seam.sendTo]) reaches only its addressee; only a [broadcast][Seam.broadcast]
 * fans out. Federation needs a frame to *cross the core*, but it must do so
 * **without ever becoming a broadcast**. This router is that discipline made
 * cross-server: the core hop is a single-addressee `coreSeam.sendTo(theOneServer, …)`
 * — it **never** broadcasts to the core — and the last hop is delivered down the
 * player's own **two-peer** server↔player link (a [Seam.broadcast] on a two-peer
 * seam reaches exactly the one player, as `RaftRelayHub.sendToLearner` already does
 * for a learner seam). It never iterates a set of recipients for one message. A
 * frame for one player therefore reaches exactly that player; no other player, and
 * no non-destination server, ever observes it. That is the leak-boundary invariant
 * this slice exists to protect (ADR-005 / the spec's "honest seam").
 *
 * ## The generalization of the per-learner relay
 *
 * The single-server precedent is `RaftRelayHub`, which routes a learner's frames
 * to the *named* voter (by `dest`) and a voter's `sendToLearner` to *one* learner seam
 * — lock-guarded, best-effort, and explicitly warned against ever "falling back to
 * fan-all." This router is the cross-core generalization of exactly that
 * single-addressee shape: instead of "route to the one leader / one local learner,"
 * it is "route to the one server the directory names, which routes to the one
 * addressee." It reuses the same shape — a lock-guarded recipient→seam map, a relay
 * coroutine, and [runCatchingCancellable] best-effort sends.
 *
 * ## What a stale or missing directory entry does (safe by construction)
 *
 * The attachment directory is eventually consistent, so a lookup can be **stale**
 * (name a server the player just left) or **absent** (`null`, the player is
 * unknown/unattached here yet). Neither breaks the leak boundary:
 *
 * - **`null`** — the frame is **dropped at the origin**, never fanned. The sender
 *   resends once the directory converges. (That resend-on-convergence is slice 5D's
 *   concern — this slice only guarantees the drop is safe: it goes nowhere, not
 *   everywhere.)
 * - **stale** — the frame is delivered to *one* wrong server, which finds no such
 *   local spoke and drops it. Still exactly-one-addressee; still never fanned.
 *
 * ## Ownership & wiring
 *
 * The router takes **sole ownership** of [coreSeam]'s `incoming` stream (it runs
 * the relay that pulls routing envelopes off the core), per the single-collection
 * contract — do not run another collector over the same seam. In production the
 * core routing traffic rides its own channel over the inter-server mesh, distinct
 * from the channel the Raft transport uses and the channel the directory's
 * replicator uses; here it is any [Seam] into the core.
 *
 * A server registers each player attached to it via [registerLocalSpoke] (the
 * two-peer server↔player link) and, when the player disconnects, [removeLocalSpoke].
 * [route] is the entry a server calls when it holds a unicast for a recipient: it
 * decides, from the directory, whether the recipient is local (deliver straight
 * down the player's link) or remote (cross the core to the one server behind which
 * they sit).
 *
 * Not thread-confined: the recipient→seam map is guarded by an [reentrantLock] and
 * every suspend send is issued **outside** the locked section, so the router is
 * correct under a multi-threaded dispatcher. Construct one per server via
 * [routedUnicastRouter].
 *
 * @see routedUnicastRouter for construction and wiring over the inter-server seam.
 * @see AttachmentDirectory for the `(recipient) -> server` lookup this consumes.
 */
public class RoutedUnicastRouter internal constructor(
    private val self: PeerId,
    private val coreSeam: Seam,
    private val lookup: (PeerId) -> PeerId?,
    scope: CoroutineScope,
) {
    private val lock = reentrantLock()

    /** Recipient (a local player) → the two-peer server↔player seam to deliver down. */
    private val localSpokes: MutableMap<PeerId, Seam> = mutableMapOf()

    /**
     * The relay: pull routing envelopes off the core seam and hand each one down to
     * the single local player it names. Sole collector of [coreSeam]'s `incoming`.
     */
    private val relayJob: Job = scope.launch {
        runCatchingCancellable {
            coreSeam.incoming.collect { swatch ->
                val envelope = runCatchingCancellable { RoutingEnvelope.decode(swatch.toByteArray()) }.getOrNull()
                    ?: return@collect
                deliverLocal(envelope.recipient, envelope.payload)
            }
        }.onFailure { log.debug { "routed-unicast: $self relay ended: ${it.message}" } }
    }

    /**
     * Register a player attached to this server: [spoke]'s messages will be
     * delivered down [seam] (the two-peer server↔player link) when they arrive
     * across the core or are routed locally.
     */
    public fun registerLocalSpoke(spoke: PeerId, seam: Seam) {
        lock.withLock { localSpokes[spoke] = seam }
    }

    /** Deregister a player that has disconnected from this server. */
    public fun removeLocalSpoke(spoke: PeerId) {
        lock.withLock { localSpokes.remove(spoke) }
    }

    /**
     * Route [payload] to *exactly* [recipient], crossing the core if [recipient] is
     * behind another server.
     *
     * - Directory names **this** server ⇒ deliver straight down the player's local
     *   link.
     * - Directory names **another** server ⇒ a single-addressee [Seam.sendTo] across
     *   the core to that one server, which delivers it locally.
     * - Directory returns **`null`** ⇒ the frame is dropped here (the recipient is
     *   unattached/unknown; the caller resends on convergence). It is **never**
     *   fanned to more than one destination.
     */
    public suspend fun route(recipient: PeerId, payload: ByteArray) {
        val server = lookup(recipient)
        when (server) {
            null -> log.debug {
                "routed-unicast: $self has no attachment for $recipient — dropping (retried on convergence)"
            }
            self -> deliverLocal(recipient, payload)
            else -> {
                val envelope = RoutingEnvelope.encode(RoutingEnvelope(recipient, payload))
                runCatchingCancellable { coreSeam.sendTo(server, envelope) }
                    .onFailure { log.debug { "routed-unicast: $self cross-core send to $server for $recipient failed" } }
            }
        }
    }

    /**
     * Hand [payload] down the one local player [recipient]'s link, or drop if not
     * local. The link is the **two-peer** server↔player seam registered for
     * [recipient], so a [Seam.broadcast] on it reaches exactly that one player — the
     * same single-addressee delivery `RaftRelayHub.sendToLearner` uses on a learner
     * seam. [recipient] is thus a pure logical player id (the directory/envelope key),
     * decoupled from whatever id the transport assigns the link.
     */
    private suspend fun deliverLocal(recipient: PeerId, payload: ByteArray) {
        val seam = lock.withLock { localSpokes[recipient] }
        if (seam == null) {
            log.debug { "routed-unicast: $self has no local spoke $recipient — dropping" }
            return
        }
        runCatchingCancellable { seam.broadcast(payload) }
            .onFailure { log.debug { "routed-unicast: $self local deliver to $recipient failed" } }
    }

    /** Stop relaying and release the relay coroutine. Idempotent. */
    public fun close() {
        relayJob.cancel()
    }
}

/**
 * Build a [RoutedUnicastRouter] for one server, taking sole ownership of
 * [coreSeam]'s `incoming` for the routing relay.
 *
 * @param self this server's [PeerId] — the value the directory returns for a
 *   player attached here, and the address other servers reach it at over [coreSeam].
 *   Must equal [coreSeam]'s `selfId` (the identity this server carries into the core).
 * @param coreSeam this server's seam into the fully-meshed core; the router owns its
 *   `incoming` stream per the single-collection contract — do not run another
 *   collector over the same seam.
 * @param lookup the `(recipient) -> server` directory lookup — pass
 *   [AttachmentDirectory.lookup]. Returns the server a recipient is behind, or `null`
 *   when the recipient is unattached/unknown (the frame is then dropped at the origin).
 * @param scope the [CoroutineScope] whose [Job] parents the relay coroutine.
 *   **Required** — no real-dispatcher default; inject a test scope's `backgroundScope`
 *   under virtual time.
 */
public fun routedUnicastRouter(
    self: PeerId,
    coreSeam: Seam,
    lookup: (PeerId) -> PeerId?,
    scope: CoroutineScope,
): RoutedUnicastRouter = RoutedUnicastRouter(self = self, coreSeam = coreSeam, lookup = lookup, scope = scope)

/**
 * The wire envelope that names the ultimate recipient of a routed unicast so the
 * destination server delivers to precisely that one player.
 *
 * CBOR-encoded (the module's established binary frame format), it wraps the opaque
 * [payload] with the [recipient] it must reach. A server pulls it off the core,
 * reads [recipient], and hands [payload] down that one player's link.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal class RoutingEnvelope(
    val recipient: PeerId,
    val payload: ByteArray,
) {
    internal companion object {
        private val cbor = Cbor { ignoreUnknownKeys = true }

        fun encode(envelope: RoutingEnvelope): ByteArray = cbor.encodeToByteArray(envelope)

        fun decode(bytes: ByteArray): RoutingEnvelope = cbor.decodeFromByteArray(bytes)
    }
}
