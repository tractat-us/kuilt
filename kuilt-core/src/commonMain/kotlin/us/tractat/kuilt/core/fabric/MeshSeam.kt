package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as withMutex
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalAttested
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.SeamStateGate
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [Seam] over a fully-connected N-peer mesh of point-to-point [Connection]s, with support for
 * admitting links that arrive after construction.
 *
 * Obtain one from [meshSeam]. Beyond the [Seam] contract it adds [addLink] for dynamic peer-join,
 * and it is a [PrincipalRoster]: host-verified principals attached to admitted connections (via
 * [us.tractat.kuilt.core.withPrincipal]) are observable per peer, maintained atomically with the
 * link set.
 */
public interface Mesh : Seam, PrincipalRoster {
    /**
     * Admit a [Connection] to a peer that dialed in after construction.
     *
     * Exchanges the mesh preamble to learn the remote [PeerId], applies the mesh's
     * [LinkAdmission] policy, dedups against existing links using the same canonical rule as
     * construction (see [meshSeam]), updates [peers], and launches the link's read loop.
     * Suspends until the preamble exchange completes.
     *
     * The admission check runs **between** the handshake and link publication — the first
     * moment the joiner's self-asserted [PeerId] is known, and the last moment before the
     * link can contend in duplicate-link dedup or receive frames. A rejected connection is
     * closed and never published — it never reaches the dedup tiebreak (so a forged link can
     * never displace a live one), never joins [peers], and never lands in [attestedPrincipals].
     *
     * **Reject-and-continue:** rejection affects **only** the one link. It is surfaced as a
     * [LinkRejectedException] — the per-link signal a hub accept-pump (`hostedOverlay`) absorbs and
     * debug-logs — and never tears down the seam or any other admitted link. Because the mesh
     * admits many joiners one at a time, an unauthorized joiner cannot disturb the live session or
     * concurrent legitimate ones.
     *
     * @throws LinkRejectedException if the mesh's [LinkAdmission] policy rejects the link — a
     *   non-fatal per-link signal; the seam and all other links stay intact.
     * @param conn A fresh, unread [Connection]. The mesh wraps it with [singleCollection] before reading,
     *   so the preamble read and the read loop share ONE collection of [Connection.incoming] — a cold,
     *   single-collection conn (a stream fabric's `framed()`) works as well as a hot channel-backed
     *   one, exactly as construction does.
     */
    public suspend fun addLink(conn: Connection)
}

/**
 * The mesh handshake preamble: this peer's [PeerId] plus a per-connection [nonce].
 *
 * Distinct from [Hello] (which carries only the id) because mesh dedup needs a value that BOTH
 * ends of the same physical link share. Each side draws a random nonce; the canonical link
 * identity is a pure, order-independent function of the two nonces, so both ends derive the same
 * survivor when a duplicate link to the same peer exists — cross-node dedup agreement with no
 * coordination.
 *
 * Wire format — the BODY of a [MeshFrameType.Hello] frame:
 * `[VERSION_BYTES version][4-byte big-endian id length][id UTF-8 bytes][NONCE_BYTES nonce bytes]`.
 * No delimiter: the id length field makes the body self-describing. The nonce is raw bytes (not
 * hex-encoded) and always exactly [NONCE_BYTES] bytes long — enforced by both [encode] and [decode],
 * not merely documented (#1812).
 *
 * These bytes are the frame's body only: the [MeshFrameType] discriminator that says *this is a
 * hello* lives one layer out, in [MeshWire]. The **version** lives HERE rather than beside the type
 * byte because it versions this body's layout, not the type space — and because a hello is the
 * first thing a peer sends, so the version is known before anything else is interpreted (#2474).
 */
internal data class MeshHello(val peerId: PeerId, val nonce: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MeshHello && peerId == other.peerId && nonce.contentEquals(other.nonce))

    override fun hashCode(): Int = 31 * peerId.hashCode() + nonce.toList().hashCode()

    public companion object {
        public fun encode(peerId: PeerId, nonce: ByteArray): ByteArray {
            require(nonce.size == NONCE_BYTES) {
                "malformed MeshHello: nonce is ${nonce.size} bytes, expected exactly $NONCE_BYTES"
            }
            val idBytes = peerId.value.encodeToByteArray()
            return ByteArray(VERSION_BYTES + Int.SIZE_BYTES + idBytes.size + nonce.size).also { buf ->
                buf[0] = MESH_WIRE_VERSION.toByte()
                buf.writeInt(idBytes.size, offset = VERSION_BYTES)
                idBytes.copyInto(buf, destinationOffset = VERSION_BYTES + Int.SIZE_BYTES)
                nonce.copyInto(buf, destinationOffset = VERSION_BYTES + Int.SIZE_BYTES + idBytes.size)
            }
        }

        /**
         * Decode a preamble **body** (the frame minus its [MeshFrameType] byte), throwing
         * [IllegalArgumentException] if it is malformed.
         *
         * The body is the **first bytes a remote sends**, so every check runs before the read it
         * protects (#1788): a body shorter than the 4-byte length prefix would index-fault inside
         * [readInt], a negative declared length passes any `size >= 4 + idLen` test (the prefix is read
         * as a signed [Int]), and a large one wraps `4 + idLen` negative so that test passes too — hence
         * the subtraction. (Additive checks are fine in `NamedFrame`/`GossipFrame` only because their
         * length fields are 8 and 16 bits wide; a signed 32-bit length cannot use one.)
         *
         * **What this changes for a caller is the diagnosis, not the liveness.** Neither call site was
         * crashing: `handshakeLink` throws into `buildMesh`'s `coroutineScope`/`async`, so a malformed
         * preamble still fails whole-mesh construction, and `addLink`'s throw is absorbed by a hub's
         * accept pump (`HostedOverlay`). Rejecting in the decoder replaces an `IndexOutOfBoundsException`
         * raised from inside [readInt] — which says only that *something* was wrong — with a message that
         * names *what*.
         *
         * ## The version is read FIRST, and a version we do not know ends the decode (#2474)
         * Every field after it is laid out *by* that version, so reading one before checking the
         * version is reading a body whose shape is not yet established. A mismatch raises
         * [MeshUnsupportedWireVersionException], which names both versions — never a generic
         * malformed-frame error, because on a version break "malformed" is precisely the wrong
         * diagnosis and the one a reader will act on.
         *
         * ## The nonce is a fixed-width field, and a wrong width is REJECTED, never reshaped (#1812)
         *
         * The decoded nonce is not inert data: `canonicalLinkNonce` hex-encodes both endpoints' nonces,
         * sorts the two strings and joins them, and that string **is** the link identity both ends of a
         * mesh dedup on. Taking whatever bytes remain made that identity entirely peer-controlled — a
         * zero-length nonce hex-encodes to the empty string, so two *distinct* misbehaving peers derive
         * the *same* canonical identity and dedup can drop a link that is genuinely distinct.
         *
         * A length is a quantity and could be clamped; a nonce is not. A wrong-width nonce is proof of a
         * malformed or forged preamble, and truncating or padding it to [NONCE_BYTES] would launder that
         * proof into a valid-looking identity — the forger simply gets whichever in-range value the
         * reshaping picks. The frame is dropped instead.
         */
        public fun decode(body: ByteArray): MeshHello {
            if (body.size < VERSION_BYTES) {
                throw MeshTruncatedFrameException(
                    "truncated MeshHello: ${body.size} bytes cannot hold the $VERSION_BYTES-byte wire version",
                )
            }
            val version = body[0].toInt() and 0xff
            if (version != MESH_WIRE_VERSION) throw MeshUnsupportedWireVersionException(version, MESH_WIRE_VERSION)
            val idOffset = VERSION_BYTES + Int.SIZE_BYTES
            require(body.size >= idOffset) {
                "truncated MeshHello: ${body.size} bytes cannot hold the ${Int.SIZE_BYTES}-byte id length"
            }
            val idLen = body.readInt(offset = VERSION_BYTES)
            require(idLen >= 0) { "malformed MeshHello: negative declared id length $idLen" }
            require(body.size - idOffset >= idLen) {
                "truncated MeshHello: declared id length $idLen exceeds the ${body.size}-byte body"
            }
            // Safe subtraction: the two checks above pin `0 <= idLen <= body.size - idOffset`.
            val nonceLen = body.size - idOffset - idLen
            require(nonceLen == NONCE_BYTES) {
                "malformed MeshHello: nonce is $nonceLen bytes, expected exactly $NONCE_BYTES"
            }
            val peerId = PeerId(body.decodeToString(startIndex = idOffset, endIndex = idOffset + idLen))
            val nonce = body.copyOfRange(idOffset + idLen, body.size)
            return MeshHello(peerId, nonce)
        }
    }
}

private const val NONCE_BYTES = 16

/** Length of the leading wire-version field, in bytes. */
private const val VERSION_BYTES = 1

/** Write [value] as a 4-byte big-endian integer into [this] at [offset]. */
private fun ByteArray.writeInt(value: Int, offset: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

/** Read a 4-byte big-endian integer from [this] at [offset]. */
private fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)

/** Hex-encode [this] for use in the canonical link-nonce comparison string. */
private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/**
 * A handshaked link: the remote peer, its conn, the canonical link nonce both ends agree on, and
 * the host-verified [Principal] that rode the original connection (`null` = unattested).
 */
private class Link(
    val remoteId: PeerId,
    val conn: Connection,
    val linkNonce: String,
    val principal: Principal?,
)

/**
 * Build a fully-connected N-peer mesh [Seam] from a set of raw point-to-point connections.
 *
 * For each [Connection] in [connections], [meshSeam] exchanges a [MeshHello] preamble (this peer's id plus a
 * random per-connection nonce) to learn the remote [PeerId]. Both sides of a link exchange their
 * preamble concurrently, so all exchanges in [connections] run in parallel — this function suspends
 * until every handshake completes.
 *
 * **Dedup (cross-node agreement):** if two connections resolve the same remote id (duplicate links from
 * a simultaneous dial), both ends keep the link with the lexicographically smallest *link nonce* —
 * a canonical, order-independent function of the two per-connection nonces. Because both ends see
 * both nonces, they derive the same survivor and close the same loser, with no coordination. The
 * old self-relative `selfId < remoteId` rule could leave a link half-open (the two ends disagreed
 * on the survivor); the nonce-based rule cannot.
 *
 * **Per-link failure:** if a link's remote peer disconnects or errors, that peer is removed from
 * [Seam.peers] and the mesh continues operating. The seam remains [SeamState.Woven] until
 * [Seam.close] is called.
 *
 * **Dynamic join:** admit a link that arrives later via [Mesh.addLink].
 *
 * @param selfId This peer's identity. Sent in the [MeshHello] preamble on each conn.
 * @param connections Raw [Connection]s to each prospective peer. These must be fresh and unread. Each is
 *   wrapped with [singleCollection] before reading, so the preamble read and the per-link read
 *   loop share ONE collection of [Connection.incoming] — a cold, single-collection connection (a stream
 *   fabric's `framed()`) works as well as a hot channel-backed one
 *   ([us.tractat.kuilt.test.fabric.connectionPair]).
 * @param dispatcher The scope for the per-link `readLoop` coroutines (scheduling only — see the
 *   thread-safety note on the returned seam). Production callers pass `Dispatchers.Default`; test
 *   callers pass a dispatcher derived from the test scheduler so seam internals share the same
 *   virtual clock as the test's `withTimeout`.
 * @param random Source of per-connection nonces. Production defaults to [Random.Default]; tests
 *   pass a seeded [Random] so the dedup tiebreak is deterministic.
 * @param policy Delivery policy for the seam's inbound [Spool]. Defaults to [DeliveryPolicy.Reliable]
 *   (bounded, backpressured). Pass [DeliveryPolicy.Lossy] for a lossy radio-style fabric or
 *   [DeliveryPolicy.Strict] in tests that assert no overflow.
 * @param admission Per-link admission policy, applied after each link's handshake and before it is
 *   published — both to construction-time connections and to every later [Mesh.addLink]. Defaults
 *   to [LinkAdmission.AcceptAll] (byte-identical to today's open behaviour); once supplied, the
 *   policy is authoritative for **every** link, including unattested ones. **Reject-and-continue:**
 *   a construction-time connection the policy declines is closed and dropped, and the mesh is still
 *   built from the surviving links — one rejected joiner never fails construction nor cancels the
 *   concurrent sibling handshakes. A rejected link is never published, so it can never contend in
 *   dedup, join [Seam.peers], or land in the [attestedPrincipals] roster.
 * @param drainBound how long a deduplicated loser is drained before the seam gives up on the
 *   remote's goodbye and disposes of the link anyway (#2474). A **zombie-link backstop**, never the
 *   mechanism: the healthy path terminates in-band on the goodbye, milliseconds after the swap, with
 *   no timer involved. Production default [DEFAULT_MESH_DRAIN_BOUND]; tests inject a small value and
 *   drive it with `advanceTimeBy`. Runs on [dispatcher], so it advances under virtual time.
 * @param orderingHoldCapacity how many live-link frames a peer's receiver ordering hold buffers
 *   before it releases early and accepts the reorder (#2474). Bounded, never unbounded;
 *   backpressuring instead would stall staging on a release only the drained link can perform.
 *   Production default [DEFAULT_MESH_ORDERING_HOLD_CAPACITY].
 * @param onDisplacement raised when a displaced link finishes draining, and when a peer's ordering
 *   hold overflows. `kuilt-core` is logger-free, so this is how those surface to a consumer's own
 *   logger. Best-effort and non-suspending; defaults to a silent absorb. See [MeshDisplacement].
 */
@Deprecated(
    message = "meshSeam is ambiguous — it carries hub semantics (never self-torns on drain) under a " +
        "name that reads like a generic mesh. Choose by role: a peer/spoke session whose life ends " +
        "when its last/only peer leaves wants peerMesh (latches Torn on drain, honouring the " +
        "incoming-completes-on-Torn contract); a start-empty-and-grow host that legitimately sits " +
        "empty between joiners wants hubMesh (the byte-identical successor to this behaviour). " +
        "Pre-1.0: no reason to carry three names for two behaviours. The mechanical replacement is " +
        "hubMesh (behaviour-preserving); switch to peerMesh where the site is a genuine spoke.",
    replaceWith = ReplaceWith("hubMesh(selfId, connections, dispatcher, random, policy, admission)"),
    level = DeprecationLevel.WARNING,
)
public suspend fun meshSeam(
    selfId: PeerId,
    connections: List<Connection>,
    dispatcher: CoroutineContext,
    random: Random = Random.Default,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    admission: LinkAdmission = LinkAdmission.AcceptAll,
    drainBound: Duration = DEFAULT_MESH_DRAIN_BOUND,
    orderingHoldCapacity: Int = DEFAULT_MESH_ORDERING_HOLD_CAPACITY,
    onDisplacement: (MeshDisplacement) -> Unit = {},
): Mesh = buildMesh(
    selfId, connections, dispatcher, random, policy, admission,
    latchTornWhenDrained = false, drainBound, orderingHoldCapacity, onDisplacement,
)

/**
 * Build a symmetric **peer-mesh** [Seam] — the contract-conforming N-peer fabric.
 *
 * Identical to [meshSeam]/[hubMesh] except for the drain contract: when a peer-mesh that was
 * non-empty loses its **last** link, it latches [SeamState.Torn] and completes [Seam.incoming]
 * together — single-shot and ordered (`Torn` is latched inside the teardown lock; the inbound
 * spool then closes) — honouring the "`incoming` completes once the seam reaches `Torn`, whether
 * via local close or a remote disconnect" invariant on [Seam.incoming]. Use this for a genuine
 * peer-to-peer session whose life ends when every peer has gone.
 *
 * Because "was non-empty, now empty" cannot distinguish a drained peer-mesh from a hub that simply
 * lost its one joiner, the role is explicit at construction — it is not inferred. A hub uses
 * [hubMesh] instead.
 *
 * **A peer-mesh MAY start empty and grow.** Pass an empty [connections] list for the deliberate
 * start-empty → grow-via-[Mesh.addLink] → drain lifecycle (e.g. a voter mesh both ends of which
 * dial each other in via `addLink`): the seam is born [SeamState.Woven], and only latches
 * [SeamState.Torn] once it has been **non-empty and then drained back to empty**. The drain latch
 * keys on that runtime "was non-empty, now empty" transition (via [Mesh.addLink] then link loss) —
 * never on the construction list.
 *
 * **Reject-all at construction is dead on arrival.** A construction that *requests* connections but
 * has them **all** rejected by [admission] is born [SeamState.Torn] (the "A2" rule) — it asked for
 * peers, got none, and has nothing that could ever drain, so it must not sit `Woven` forever with an
 * empty roster. This is a quiet born-`Torn`, consistent with "rejection never fails construction" —
 * not a thrown constructor.
 *
 * This makes for one honest asymmetry: `peerMesh(listOf(rejectedConn))` is born-`Torn`, but a
 * `peerMesh(emptyList())` whose *first* [Mesh.addLink] is later rejected stays `Woven`. Construction
 * is a closed roster claim (you named the peers you expect); `addLink` is open-ended (a hub-style
 * accept-pump feeding an initially empty roster). Requesting peers and being denied all of them is
 * failure; requesting none and then having a speculative joiner declined is not.
 *
 * All other parameters behave exactly as documented on [meshSeam].
 */
public suspend fun peerMesh(
    selfId: PeerId,
    connections: List<Connection>,
    dispatcher: CoroutineContext,
    random: Random = Random.Default,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    admission: LinkAdmission = LinkAdmission.AcceptAll,
    drainBound: Duration = DEFAULT_MESH_DRAIN_BOUND,
    orderingHoldCapacity: Int = DEFAULT_MESH_ORDERING_HOLD_CAPACITY,
    onDisplacement: (MeshDisplacement) -> Unit = {},
): Mesh = buildMesh(
    selfId, connections, dispatcher, random, policy, admission,
    latchTornWhenDrained = true, drainBound, orderingHoldCapacity, onDisplacement,
)

/**
 * Build a **hub-mesh** [Seam] — the start-empty-and-grow topology (a host admitting joiners).
 *
 * A hub legitimately sits at an **empty** link set: it is constructed with `connections = emptyList()`
 * and grows via [Mesh.addLink] as joiners dial in, and it may drain back to empty between joiners
 * without its session ending. A hub therefore **never** self-torns on drain — it terminates only via
 * [Seam.close]. This is exactly today's [meshSeam] behaviour, named explicitly.
 *
 * Contrast [peerMesh], which latches [SeamState.Torn] when its last link drops. All parameters
 * behave exactly as documented on [meshSeam].
 */
public suspend fun hubMesh(
    selfId: PeerId,
    connections: List<Connection>,
    dispatcher: CoroutineContext,
    random: Random = Random.Default,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    admission: LinkAdmission = LinkAdmission.AcceptAll,
    drainBound: Duration = DEFAULT_MESH_DRAIN_BOUND,
    orderingHoldCapacity: Int = DEFAULT_MESH_ORDERING_HOLD_CAPACITY,
    onDisplacement: (MeshDisplacement) -> Unit = {},
): Mesh = buildMesh(
    selfId, connections, dispatcher, random, policy, admission,
    latchTornWhenDrained = false, drainBound, orderingHoldCapacity, onDisplacement,
)

/**
 * How long a deduplicated loser is drained before the seam gives up on the remote's goodbye (#2474).
 *
 * A **zombie-link backstop**, not the mechanism: a healthy drain terminates in-band on the goodbye,
 * milliseconds after the swap, with no timer involved. Two seconds because the drain holds two things
 * open while it runs — a connection, and the peer's receiver ordering hold — and frame-scale transport
 * hiccups are milliseconds, so this never truncates a real drain.
 */
public val DEFAULT_MESH_DRAIN_BOUND: Duration = 2.seconds

/**
 * Default depth of a peer's receiver ordering hold (#2474): how far the live link may run ahead of a
 * drained link's tail before the seam gives up on ordering the two and releases early.
 *
 * The same shape as [DeliveryPolicy.DEFAULT_CAPACITY] because it absorbs the same kind of transient.
 * Bounded, never unbounded — see [MeshDisplacement.OrderingHoldOverflowed] for why backpressuring at
 * the bound is the one option not available.
 */
public const val DEFAULT_MESH_ORDERING_HOLD_CAPACITY: Int = DeliveryPolicy.DEFAULT_CAPACITY

/**
 * Shared construction for the mesh factories: handshake every [connections] entry, apply
 * [admission], dedup duplicate links, and build the [MeshSeam]. [latchTornWhenDrained] carries the
 * role (peer-mesh `true`, hub `false`) into the seam — the ONLY behavioural difference between the
 * public factories.
 */
private suspend fun buildMesh(
    selfId: PeerId,
    connections: List<Connection>,
    dispatcher: CoroutineContext,
    random: Random,
    policy: DeliveryPolicy,
    admission: LinkAdmission,
    latchTornWhenDrained: Boolean,
    drainBound: Duration,
    orderingHoldCapacity: Int,
    onDisplacement: (MeshDisplacement) -> Unit,
): Mesh {
    // A non-positive hold would give up on the FIRST live-link frame of every drain — the send-order
    // promise silently switched off by a tuning knob. Refused here rather than absorbed: this is the
    // shape of "optional ≠ tuning", and a caller that means "no hold" means "no drain", which is not
    // on offer.
    require(orderingHoldCapacity > 0) {
        "orderingHoldCapacity must be positive (got $orderingHoldCapacity): a hold of zero depth " +
            "disables the mesh's send-order guarantee across a dedup swap rather than tuning it"
    }
    val handshaked = coroutineScope {
        connections.map { conn -> async { handshakeLink(selfId, conn, dispatcher, random) } }.awaitAll()
    }
    // Reject-and-continue: drop (and close) the links the policy declines; keep the survivors. A
    // rejected link never reaches the dedup lottery below or publication, and a rejection neither
    // fails construction nor tears down the concurrent sibling handshakes above.
    val links = handshaked.filter { admitLink(selfId, admission, it) }

    // Dedup duplicate links to the same peer, keeping the canonical survivor on every node.
    val winners = mutableMapOf<PeerId, Link>()
    val losers = mutableListOf<Link>()
    for (link in links) {
        val existing = winners[link.remoteId]
        when {
            existing == null -> winners[link.remoteId] = link
            link.linkNonce < existing.linkNonce -> { losers += existing; winners[link.remoteId] = link }
            else -> losers += link
        }
    }
    // The losers are DRAINED, not closed (#2485) — handed to the seam, which starts their drains from
    // `init`. They are not closed here, and `buildMesh` must not close them: disposal is owed at drain
    // end, by `MeshSeam.endDrain`, exactly as it is for a loser displaced by `Mesh.addLink`.
    //
    // What this closes is narrower than `addLink`'s window and worth stating precisely: no peer has been
    // published locally yet, so there is no LOCAL publish-then-swap window at all. The residual is the
    // REMOTE's — it deduped first and wrote into the link before this seam existed — and on a
    // simultaneous dial caught at construction it is the symmetric case, where BOTH ends drain and
    // "terminate on the remote's abrupt close" is therefore no termination condition at all. That is
    // precisely why the in-band goodbye, not the close, is what ends the drain.

    // A2 (born-dead): a peer-mesh that REQUESTED connections but had them ALL rejected/deduped away
    // asked for peers and got none — it has nothing that could ever drain, so it must latch Torn at
    // birth rather than sit Woven forever with an empty roster. A start-empty peer-mesh
    // (`connections` empty) requested nothing, so it is NOT born-dead: it stays Woven and grows via
    // addLink. Hubs never latch, so bornDead is always false for them (latchTornWhenDrained = false).
    //
    // `winners.isEmpty()` also implies `losers.isEmpty()`: a loser is only ever produced against an
    // incumbent winner for the same peer, and that peer keeps a winner thereafter. So a born-dead seam
    // never carries a drain, and the two never interact.
    val bornDead = latchTornWhenDrained && connections.isNotEmpty() && winners.isEmpty()
    return MeshSeam(
        selfId, winners, losers, dispatcher, random, policy, admission, latchTornWhenDrained, bornDead,
        drainBound, orderingHoldCapacity, onDisplacement,
    )
}

/**
 * Exchange the mesh preamble on [conn] and return the resulting [Link] with its canonical nonce.
 *
 * The conn is wrapped with [singleCollection] before the preamble read, so the read loop launched
 * later collects the SAME single upstream collection — cold, single-collection connections (a stream
 * fabric's `framed()`) work, not just hot channel-backed ones. The wrapper conn is what the [Link]
 * carries, so dedup/teardown closes and the read loop all operate on it.
 */
private suspend fun handshakeLink(selfId: PeerId, conn: Connection, dispatcher: CoroutineContext, random: Random): Link {
    // Read the attestation off the ORIGINAL connection before wrapping — the singleCollection
    // wrapper is a plain Connection and would hide the PrincipalAttested marker.
    val principal = (conn as? PrincipalAttested)?.principal
    val single = conn.singleCollection(dispatcher)
    val myNonce = random.nextBytes(NONCE_BYTES)
    single.send(MeshWire.encodeHello(selfId, myNonce))
    // Classification is by TYPE, not by position (#2474). The preamble used to be "whatever the first
    // frame is", so a peer that opened with data had those bytes fed to `MeshHello.decode`; now the
    // frame says what it is and anything other than a hello here is refused by name.
    val remote = when (val first = MeshWire.decode(single.firstFrame())) {
        is MeshWireFrame.Hello -> first.hello
        MeshWireFrame.Data, MeshWireFrame.Goodbye ->
            throw MeshWireOutOfOrderException("mesh handshake: expected a HELLO frame first, got $first")
    }
    return Link(remote.peerId, single, canonicalLinkNonce(myNonce, remote.nonce), principal)
}

/** A frame whose type is known but whose POSITION is not one this build accepts — see [MeshWire]. */
internal class MeshWireOutOfOrderException(message: String) : MeshWireFormatException(message)

/**
 * Close [conn] best-effort: the *conn's* failure to close must not cancel the work that follows (#1834).
 *
 * Every unshielded close in this file goes through here. **The trigger is not "a loop" — it is whether
 * anything the mesh still owes follows the close.** #1834 originally scoped itself to the two multi-item
 * loops and filed the single-item closes as unaffected ("nothing follows them to skip"); that is wrong
 * for three of them, and the item count was never the right axis:
 *
 *  - [MeshSeam.close] — the rest of the roster follows. A half-open leak, and unbounded on a hub,
 *    which accepts arbitrarily many spokes.
 *  - `MeshSeam.endDrain`'s disposal of a drained dedup loser (#2474) — the peer's ordering-hold release
 *    and its [MeshDisplacement.Drained] report follow. A rethrow strands the hold, so that peer's live
 *    traffic buffers until it hits the cap and is then delivered OUT OF SEND ORDER — the one failure
 *    the hold exists to prevent, reached by way of a close that had nothing to do with it. **This is
 *    the site #1834 used to name as `addLink`'s displaced-loser close; that close no longer exists**
 *    — since #2474 `addLink` starts a drain instead, and the obligation moved here with the close.
 *    Since #2485 [buildMesh]'s own dedup close has moved here too, for the same reason: construction's
 *    losers are drained, so every dedup loser in this file is now disposed of by `endDrain`.
 *  - [admitLink]'s two rejection closes — a `return false` follows, consumed by [buildMesh]'s `filter`,
 *    so a rethrow fails WHOLE-MESH construction against a documented "rejection never fails construction".
 *
 * **Not `runCatchingCancellable`.** That helper discriminates on TYPE, and type cannot separate "my
 * job was cancelled" from "the callee minted one" — most often a `Connection.close` wrapped in
 * `withTimeout(closeTimeout)`, which throws `TimeoutCancellationException` *to its caller* without
 * cancelling that caller's job. `Seam.close` carries no *"must not report failure as cancellation"*
 * obligation — that contract sits only on `sendTo`/`broadcast`/`Loom.weave` — so a consumer minting
 * one here is not even a contract violation, and this library cannot trust consumer impls regardless.
 *
 * **The `ensureActive` is live, not dead.** This is the asymmetry with the `NonCancellable`-shielded
 * sites ([MeshSeam.removePeer]'s drain teardown, and the nine fixed in #1824): inside a shield our own
 * job can never be cancelled, so every `CancellationException` reachable there is necessarily
 * callee-minted and `ensureActive` cannot fire. Here there is no shield, so the caller's cancellation is
 * real and must still propagate — [ensureActive] is exactly the discriminator that lets it, while a
 * callee-minted one falls through. This is the remedy `Seam.sendTo`'s KDoc prescribes for callers that
 * cannot afford to trust the contract, and it is why #1824's lexical guard neither flags these sites nor
 * could: without a shield the two cases really are ambiguous.
 *
 * Shielding these sites instead would be the wrong trade — a caller that cancels `close()` would then be
 * unable to stop it — and it is also the shape #1824's guard rejects.
 *
 * `broadcast`/`sendTo`'s per-conn `runCatchingCancellable { conn.send(…) }` is deliberately NOT routed
 * here: those are sends, not closes, and rethrowing there propagates a cancellation the [Seam] contract
 * requires `Connection.send` not to have minted in the first place.
 */
private suspend fun closeBestEffort(conn: Connection) {
    try {
        conn.close()
    } catch (_: Throwable) {
        // Genuinely our own cancellation → rethrow. Anything else — including a CancellationException
        // the conn minted itself — is this conn's failure alone; whatever follows still happens.
        currentCoroutineContext().ensureActive()
    }
}

/** Order-independent link identity from the two endpoint nonces — identical on both ends. */
private fun canonicalLinkNonce(a: ByteArray, b: ByteArray): String {
    val (lo, hi) = listOf(a.toHex(), b.toHex()).sorted()
    return "$lo:$hi"
}

/**
 * THE CHECK (design 2026-07-07-hub-accept-attestation, P1 + P2): apply [admission] to a freshly
 * handshaked [link], after the `MeshHello` (the first moment the self-asserted remote id is known)
 * and before publication (the last moment before the link can contend in dedup or receive frames).
 * Returns `true` to admit; on rejection it closes the connection and returns `false` — the link is
 * simply dropped, never reaching the dedup lottery, so a forged link can never displace a live one.
 * Rejection is per-link and non-fatal: it never fails construction nor tears down sibling links.
 *
 * **Self-connection guard (#1488).** A link whose remote resolves to [selfId] is dropped here,
 * ahead of [admission] — a peer that dials its own advertised endpoint (both ends share a
 * `Rendezvous.New` service name) handshakes a `MeshHello` claiming [selfId]. Registering it would
 * put [selfId] in `links`: the node then echoes its own broadcasts, and the self-link skews
 * `peerMesh` drain accounting into a spurious `Torn`. It is closed and dropped before it can reach
 * dedup, `links`, or drain accounting — modelled on `NwSeam`'s self-connection drop (#1466/#1484).
 */
private suspend fun admitLink(selfId: PeerId, admission: LinkAdmission, link: Link): Boolean {
    // Both closes are best-effort (see [closeBestEffort]). Each sits directly before a `return false`
    // that `buildMesh` consumes through `filter`, so rethrowing a cancellation the CONN minted would
    // fail WHOLE-MESH construction — contradicting "rejection is per-link and non-fatal" above, and the
    // same promise on the factories' `admission` parameter. The reject decision is already made and
    // does not depend on the close succeeding (#1834).
    if (link.remoteId == selfId) {
        closeBestEffort(link.conn)
        return false
    }
    if (admission.admit(link.principal, link.remoteId)) return true
    closeBestEffort(link.conn)
    return false
}

/**
 * ## The graceful displacement drain (#2474) — a port of `:kuilt-nw`'s D+ (#2425)
 *
 * A displaced dedup loser used to be dropped from [links] and closed in the same breath. That loses
 * whatever the remote wrote into the publish-then-swap window, and it loses it *silently*: every mesh
 * link is wrapped in [singleCollection], whose `close` cancels its republishing pump **before**
 * closing the delegate, so bytes the delegate had already handed over are discarded no matter how
 * orderly the underlying transport's close is. On the keep arm it was worse — that loser never had a
 * read loop at all, so its whole tail went on the floor.
 *
 * Since #2474 a displaced link is instead marked **draining** ([draining]):
 *
 *  - it **keeps (or is given) a read loop**, so its inbound data is still attributed to its peer;
 *  - it is **never selected for a send** — it is not in [links], which is the one map [broadcast] and
 *    [sendTo] route through, so this is structural rather than a rule to remember;
 *  - it is sent exactly **one [MeshFrameType.Goodbye]**, outside [lock], as the last thing written to it;
 *  - it terminates on the remote's goodbye | the link dying under it | the injected [drainBound], and
 *    only THEN is it closed and forgotten.
 *
 * **Both dedup arms drain, and so does construction's (#2485).** The keep arm looks like the harmless
 * half — the peer was never published on that link locally — but both ends dedup onto the same physical
 * link, so *our* keep-arm loser is the *remote's* replace-arm loser, with its window frames in flight
 * toward us. [buildMesh]'s losers are the same case one step earlier: nothing is published locally at
 * construction, so they are all reported as [MeshDisplacement.Arm.Keep], and the remote's window is
 * still in flight. Their drains start from `init` — see [armOrderingHoldAtConstruction].
 *
 * The goodbye is what makes the drain sound rather than an optimisation of it. Terminating on "the
 * link died" is self-defeating once both ends drain: that signal exists only while the remote still
 * closes abruptly, and when neither does, every formation's drain runs to the full bound with the
 * ordering hold below stalling the healthy path for its whole duration. [drainBound] is a
 * **zombie-link backstop**, not the mechanism.
 *
 * ## The receiver ordering hold (#2474)
 * [Seam.incoming] promises frames in send order. A remote writes to exactly one link per peer at a
 * time (its own [links] lookup is a single locked read), so its stream across a swap is strictly
 * loser-then-winner — but the two links are read by two independent loops here, so *delivery* order
 * is not. Draining without a hold would trade silent loss for silent reordering. So a per-peer hold
 * ([orderingHolds]) is armed when a drain starts and released when that peer's last drain ends;
 * while it is armed, frames arriving on the peer's live link are buffered and frames arriving on a
 * DRAINING link are delivered immediately.
 *
 * **The hold buffers and continues; it never suspends the caller.** See [stageInbound] for the
 * no-circular-wait argument, which is the reason this shape is mandatory rather than tidy.
 *
 * **Sequence is stamped at release time**, inside [stageMutex], so stamped order is delivery order.
 * The buffer is bounded ([orderingHoldCapacity]); on overflow it releases early, reports
 * [MeshDisplacement.OrderingHoldOverflowed] and accepts the reorder rather than backpressuring.
 */
private class MeshSeam(
    override val selfId: PeerId,
    initialLinks: Map<PeerId, Link>,
    // Links [buildMesh]'s dedup displaced before this seam existed (#2485). Drained from `init`, never
    // published, never closed by the caller — see [armOrderingHoldAtConstruction].
    displacedAtConstruction: List<Link>,
    private val dispatcher: CoroutineContext,
    private val random: Random,
    policy: DeliveryPolicy,
    private val admission: LinkAdmission,
    // Role signal (peerMesh true, hubMesh false). When true, draining to an empty link set latches
    // Torn; when false, the seam sits at an empty link set (the hub pattern) until close().
    private val latchTornWhenDrained: Boolean,
    // A2: this peer-mesh REQUESTED construction connections but had them all rejected/deduped away —
    // dead on arrival, latch Torn at birth (see init). A start-empty peer-mesh is NOT bornDead.
    bornDead: Boolean,
    private val drainBound: Duration,
    private val orderingHoldCapacity: Int,
    private val onDisplacement: (MeshDisplacement) -> Unit,
) : Mesh {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // `links` is shared between `broadcast`/`sendTo` (caller threads) and each `readLoop`'s
    // teardown / per-link send-failure removal (dispatcher threads), and `addLink`. It is guarded
    // by `lock`: every read and mutation happens under the lock. Suspending `conn.send`/`conn.close`
    // are NEVER invoked while the lock is held — callers snapshot the target conn(s) under the lock,
    // release, then send/close outside it.
    private val lock = reentrantLock()
    private val links = mutableMapOf<PeerId, Link>()

    /**
     * Links currently DRAINING (#2474) → their bookkeeping. Keyed by [Link], which is a plain class
     * with **identity** equality on purpose: two links to one peer are distinguished by nothing else
     * (they differ only in a nonce this map does not read), and redial churn can leave two drains to
     * one peer in flight at once, which a peer-keyed map would silently collapse.
     *
     * A link in here is NOT in [links] — that is what makes "never selected for a send" structural.
     * Guarded by [lock].
     */
    private val draining = mutableMapOf<Link, Drain>()

    /**
     * A displaced loser being drained rather than closed (#2474).
     *
     * [bound] — the [drainBound] zombie backstop — is a **non-null `val` created in the same critical
     * section that registers the drain**, deliberately. Arming it in a second phase made it nullable
     * and needed an "did my drain survive long enough to arm?" branch, which is one more window in
     * which a terminator can win a race. `launch(LAZY)` neither suspends nor dispatches, so creating
     * it under [lock] is legal; nothing runs until [startDrain] calls `start()`.
     */
    private class Drain(val peer: PeerId, val arm: MeshDisplacement.Arm, val bound: Job) {
        /** Data frames the remote delivered on this link AFTER the drain began — what the drain saved. */
        var drainedFrames: Int = 0
    }

    /**
     * Serialises **every** delivery into [spool] with the ordering-hold decision that precedes it
     * (#2474), so a hold's flush and a fresh arrival can never interleave out of order.
     *
     * A coroutine [Mutex], not the seam-wide [lock]: the guarded region contains [Spool.deliver],
     * which suspends for backpressure. That is the exact opposite of [lock]'s no-suspend rule, and
     * the reason the two are separate primitives. **[lock] is never held while this is acquired, and
     * this is never held while [lock] is acquired** — the two are strictly disjoint, so there is no
     * ordering to invert.
     */
    private val stageMutex = Mutex()

    /**
     * One peer's ordering hold: the drained links it is still waiting on, and the live-link frames it
     * has buffered meanwhile, in arrival order. **Guarded by [stageMutex] only.**
     *
     * [waitingOn] is what makes arming and releasing **symmetric and per-drain**, and that is a
     * correctness property rather than tidiness. The first shape of this asked `lock` whether any
     * other drain to the peer was still running, then acted on that answer *after* a suspension —
     * so a redial that registered a second drain and adopted the hold in between had its hold torn
     * down and its frames flushed ahead of its own tail, silently, with no overflow report. A
     * release keyed on the drain that owns it cannot do that: it removes its own entry and flushes
     * only when it was the last.
     */
    private class OrderingHold {
        val waitingOn = mutableSetOf<Link>()
        val frames = mutableListOf<ByteArray>()
    }

    /**
     * Peers whose live-link frames are being held pending a drain-end (#2474). **Guarded by
     * [stageMutex] only** — never by [lock] — so the hold decision and the delivery it guards are one
     * atomic step.
     *
     * A peer's presence as a key IS the armed flag. Bounded by [orderingHoldCapacity], after which
     * the hold releases early and is removed outright (see [stageInbound]).
     */
    private val orderingHolds = mutableMapOf<PeerId, OrderingHold>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val stateGate = SeamStateGate(SeamState.Woven)
    override val state: StateFlow<SeamState> = stateGate.state

    /**
     * The **tightest** ceiling across the live links, since one payload goes to all of them
     * verbatim — a frame that overflows any single link is over budget for this seam (#2047).
     *
     * Links that cannot name a ceiling are skipped rather than collapsing the answer to `null`:
     * `null` means "unknown", so a mesh of one bounded and one unknown link is still bounded by
     * what it does know. An empty (or wholly unknown) link set reports `null` — there is nothing to
     * be bounded by yet, and a mesh's link set grows.
     *
     * So this value **tightens over a mesh's life**: a hub with no links yet, or a peer-mesh still
     * `Weaving`, reports "unknown", and the number appears — and may shrink again — as links
     * attach. Read it per send rather than caching it at construction.
     *
     * It is the tightest link ceiling **less [MeshWire.TYPE_BYTES]** (#2474): the self-describing
     * body spends one byte of every frame on its type, and that byte is taken out of the caller's
     * budget rather than added to the wire — which is why it is reported as `reservedBytes` on a
     * refusal and not hidden. Floored at zero, so a pathological one-byte link reports `0` rather
     * than a negative budget.
     */
    override val maxPayloadBytes: Int?
        get() = lock.withLock {
            links.values.mapNotNull { it.conn.maxFrameBytes }.minOrNull()
                ?.let { (it - MeshWire.TYPE_BYTES).coerceAtLeast(0) }
        }

    // Host-verified principals of currently-linked peers (PrincipalRoster). Updated ONLY under
    // `lock`, in the same critical sections that mutate `links`, so it can never desync from the
    // live link set.
    private val _attestedPrincipals = MutableStateFlow<Map<PeerId, Principal>>(emptyMap())
    override val attestedPrincipals: StateFlow<Map<PeerId, Principal>> = _attestedPrincipals.asStateFlow()

    private val spool = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = spool.incoming

    // Incremented from MULTIPLE per-link readLoops concurrently — must be atomic.
    private val seq = atomic(0L)

    init {
        // Populate the link map before any readLoop can observe it. No coroutine has started yet.
        links.putAll(initialLinks)
        _peers.value = buildPeerSet()
        _attestedPrincipals.value = buildRoster()

        // Register construction's dedup losers as draining and arm their peers' ordering holds — ALL of
        // it before a single read loop is launched below, so no live-link frame can outrun a hold (#2485).
        // This is the arm `addLink`'s keep arm cannot have: there the winner has been reading all along.
        displacedAtConstruction.forEach { loser ->
            draining[loser] = Drain(loser.remoteId, MeshDisplacement.Arm.Keep, boundFor(loser))
            armOrderingHoldAtConstruction(loser)
        }

        // Launch a supervised reader for each initial link.
        initialLinks.values.forEach { link -> scope.launch { readLoop(link) } }

        // …and for each drained loser: the zombie backstop, the read loop that IS the drain, and the
        // goodbye. [startDrain]'s remaining steps, minus its arm (done above) and minus its undo —
        // nothing can have terminated a drain registered a few instructions ago with no coroutine yet
        // running, which is why `getValue` is a fail-fast assertion here rather than a hazard.
        displacedAtConstruction.forEach { loser ->
            draining.getValue(loser).bound.start()
            scope.launch { readLoop(loser) }
            scope.launch { runCatchingCancellable { sayGoodbye(loser) } }
        }

        // A2 born-dead: a peer-mesh that REQUESTED construction connections but had them all rejected
        // by admission (or deduped away) is born with zero links. The drain latch only fires from
        // `removePeer`, and there is nothing to remove — so without this the seam would sit `Woven`
        // forever with `peers == {selfId}`, the exact contract violation the peer-mesh role exists to
        // prevent. Latch `Torn` at birth. Consistent with "rejection never fails construction": a
        // quiet born-`Torn`, not a thrown constructor. Keyed on "connections were requested but all
        // rejected" (`bornDead`), NOT on `links.isEmpty()` — a peer-mesh that requested NOTHING
        // (`peerMesh(emptyList())`) is the deliberate start-empty case and stays `Woven`, growing via
        // `addLink`; its later drain-to-empty latches `Torn` through `removePeer` as usual. (Hubs
        // never latch, so an empty hub stays `Woven` awaiting joiners.)
        if (bornDead) tearDown(CloseReason.Unreachable)
    }

    private val closedMessage get() = "MeshSeam for $selfId is closed"

    override suspend fun addLink(conn: Connection) {
        check(state.value !is SeamState.Torn) { closedMessage }
        val link = handshakeLink(selfId, conn, dispatcher, random)
        // THE CHECK: between the handshake (remote id now known) and admitOrReject (link not yet
        // live). A rejected link is closed here and never published — it cannot contend in the
        // dedup lottery, join `peers`, or land in the roster. Reject-and-continue: the rejection is
        // surfaced as a LinkRejectedException — the per-link signal the accept-pump (hostedOverlay)
        // absorbs and debug-logs — so one rejected joiner never tears down the seam or other links.
        // (kuilt-core is logger-free by contract, so the signal is raised here and logged by the
        // pump, which owns a logger.)
        if (!admitLink(selfId, admission, link)) {
            // A self-connection (remote resolved to selfId) is dropped silently, like NwSeam — it is
            // not an admission decision, so it must not surface as a LinkRejectedException. A genuine
            // policy rejection is the per-link signal the accept-pump absorbs.
            if (link.remoteId == selfId) return
            throw LinkRejectedException(link.remoteId, attested = link.principal != null)
        }
        // Dedup against any existing link to the same peer using the canonical nonce, then publish.
        when (val outcome = admitOrReject(link)) {
            // First link to this peer: nothing to drain, just start reading it.
            Admission.First -> scope.launch { readLoop(link) }

            // REPLACE arm: the incoming link wins and displaces a PUBLISHED one. `startDrain` runs
            // BEFORE the winner's read loop is launched, so the ordering hold is armed before the
            // winner can deliver a single frame — the swap is airtight on this arm.
            is Admission.Replaced -> {
                startDrain(outcome.loser)
                scope.launch { readLoop(link) }
            }

            // KEEP arm: the incoming link loses. It has no read loop yet — one is launched HERE, and
            // it is the drain reader; before #2474 this loser was closed unread and its whole tail
            // was lost. `startDrain` precedes that launch so the hold is armed before the drained
            // link can deliver.
            is Admission.Kept -> {
                startDrain(link)
                scope.launch { readLoop(link) }
            }

            // The seam tore between the handshake and the lock. Best-effort close (see
            // [closeBestEffort]): a rethrown callee-minted cancellation would escape `addLink` into
            // `acceptPump` and `superviseVoterReconnection`, skipping their cleanup and cancelling
            // that peer's supervision outright (#1834).
            Admission.Refused -> closeBestEffort(link.conn)
        }
    }

    /** What [admitOrReject] decided — see its KDoc. */
    private sealed interface Admission {
        /** First link to this peer; it is installed and live. */
        data object First : Admission

        /** The incoming link won and displaced [loser], which is now draining. */
        data class Replaced(val loser: Link) : Admission

        /** The incoming link lost to the live one and is itself now draining. */
        data object Kept : Admission

        /** The seam is [SeamState.Torn]; the link is not admitted at all. */
        data object Refused : Admission
    }

    /**
     * Install [link] under the lock if it wins dedup, and register the loser as **draining** rather
     * than dropping it (#2474). Returns what the caller must do outside the lock: every arm that
     * produces a drain has already put the loser in [draining], so from the instant this returns the
     * loser's frames are attributed and bypass the ordering hold, while [links] — the one map the
     * send path reads — names only the winner.
     */
    private fun admitOrReject(link: Link): Admission = lock.withLock {
        if (state.value is SeamState.Torn) return@withLock Admission.Refused
        val existing = links[link.remoteId]
        when {
            existing == null -> { links[link.remoteId] = link; publishRosters(); Admission.First }
            // Displacement keeps the peer set identical but may change the peer's attestation —
            // including to NONE, and that is deliberate (#2357). The displaced conn is closed, so
            // after this there is exactly one live link for the id; the roster is derived from that
            // link and must describe IT. Having the survivor inherit the loser's principal was tried
            // and reverted: it keeps the entry alive at the price of asserting a verification for a
            // connection the host verified as nothing, while routing follows the survivor — a
            // fail-open for any consumer that gates a send on the roster.
            //
            // Refusing the displacement instead is not available here. The tiebreak is a pure
            // function of the two nonces precisely so both ends derive the same survivor with no
            // coordination; a veto on local attestation would have each end keep a different link
            // and close the one its peer kept, which is the half-open failure the nonce rule exists
            // to prevent, in both directions at once. So an unattested link CAN still take an
            // attested peer's id here, and the defence is deployment policy — unlike `RoomAuthorizer`,
            // `LinkAdmission` receives the principal and runs BEFORE this lottery. `RoomHubSeam`
            // refuses in code instead, because it holds both links live and can.
            link.linkNonce < existing.linkNonce -> {
                links[link.remoteId] = link
                publishRosters()
                draining[existing] = Drain(existing.remoteId, MeshDisplacement.Arm.Replace, boundFor(existing))
                Admission.Replaced(existing)
            }
            else -> {
                draining[link] = Drain(link.remoteId, MeshDisplacement.Arm.Keep, boundFor(link))
                Admission.Kept
            }
        }
    }

    /**
     * The [drainBound] zombie backstop for [loser], created **lazily and unstarted**, in the same
     * critical section that registers its [Drain]. Legal under [lock]: `launch(LAZY)` neither
     * suspends nor dispatches. [startDrain] starts it; every terminator cancels it.
     */
    private fun boundFor(loser: Link): Job = scope.launch(start = CoroutineStart.LAZY) {
        delay(drainBound)
        // Best-effort: nothing follows this, and a delivery-policy overflow while flushing the hold
        // must not surface as an unhandled exception on the seam's supervisor scope.
        runCatchingCancellable { endDrain(loser, MeshDisplacement.Outcome.Bound) }
    }

    /**
     * Republish [peers] and [attestedPrincipals] from the live link map. MUST be called with
     * [lock] held, in the same critical section as the link mutation, so the published rosters
     * can never desync from [links].
     */
    private fun publishRosters() {
        _peers.value = buildPeerSet()
        _attestedPrincipals.value = buildRoster()
    }

    // Both sends pre-check the chosen link's own ceiling before writing (#2069). Without it an
    // oversize frame reached `conn.send`, failed there, and fell into the `onFailure` below — which
    // exists for a DEAD LINK and so EVICTS the peer. A healthy recipient was dropped from the
    // roster because the caller mis-sized one payload, and `runCatchingCancellable` swallowed the
    // throwable, so nothing anywhere recorded that the frame had been oversize.
    //
    // Per link, not against `maxPayloadBytes`: that aggregate is the live minimum and can tighten
    // between a caller's read and this write, and it would refuse a payload the addressed link can
    // carry perfectly well. See `Connection.oversizeOrNull`.

    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { closedMessage }
        // Snapshot the live links under the lock, then send OUTSIDE it. `links` never names a
        // draining link (#2474), so "a drained loser is never written to" is structural here.
        val targets = lock.withLock { links.values.map { it.remoteId to it.conn } }
        val frame = MeshWire.encodeData(payload)
        targets.forEach { (remoteId, conn) ->
            // Best-effort by contract, and per link: a link too tight for this payload is skipped,
            // while the links that can carry it still get it. Skipping is not a link failure.
            if (conn.oversizeOrNull(payload, reservedBytes = MeshWire.TYPE_BYTES) != null) return@forEach
            runCatchingCancellable { conn.send(frame) }
                .onFailure { removePeer(remoteId, conn) }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { closedMessage }
        // Ahead of the `links` lookup on purpose: `links` holds remotes only, so without this a
        // self-send fell out as PeerNotConnected — false, since `peers` names selfId (#2428).
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        val conn = lock.withLock { links[peer]?.conn } ?: throw PeerNotConnected(peer)
        conn.oversizeOrNull(payload, reservedBytes = MeshWire.TYPE_BYTES)?.let { throw it }
        runCatchingCancellable { conn.send(MeshWire.encodeData(payload)) }
            .onFailure { removePeer(peer, conn) }
    }

    override suspend fun close(reason: CloseReason) {
        // Snapshot the connections to close under the lock; perform the suspending closes outside it.
        // Per-conn best-effort (see [closeBestEffort]): one wedged or timing-out peer must not strand
        // the rest of the roster half-open, and the roster is unbounded on a hub (#1834).
        val toClose = tearDown(reason) ?: return
        toClose.forEach { conn -> closeBestEffort(conn) }
    }

    /**
     * One link's reader. Classifies each frame by its leading [MeshFrameType] (#2474) rather than by
     * position, so a duplicate hello can no longer reach the consumer as data and a goodbye is a
     * message rather than a payload.
     *
     * The loop runs on a DRAINING link too — that is the whole drain: its data still belongs to its
     * peer and is delivered ahead of the live link's held frames, and its goodbye is what ends the
     * drain and disposes of it.
     */
    private suspend fun readLoop(link: Link) {
        try {
            link.conn.incoming.collect { bytes ->
                if (state.value is SeamState.Torn) return@collect
                // A frame this build cannot classify is a fault of THIS link and nothing else: the
                // collect below rethrows out of the lambda, the catch treats it as a remote
                // disconnect, and `finally` disposes of exactly this link.
                when (MeshWire.decode(bytes)) {
                    is MeshWireFrame.Hello -> throw MeshWireOutOfOrderException(
                        "mesh link to ${link.remoteId.value}: a second HELLO arrived on an already " +
                            "handshaked link — refusing the link rather than delivering it as data",
                    )
                    MeshWireFrame.Data -> stageInbound(link, bytes)
                    // The drain terminator (#2474). On a link we are NOT draining it is the remote
                    // telling us it has deduped and stopped writing here; our own dedup has not run
                    // yet (or ran the other way). Nothing to end, and nothing to do — the remote
                    // keeps reading this link for its whole drain, so writes we make meanwhile still
                    // land, and our dedup will follow.
                    MeshWireFrame.Goodbye -> endDrain(link, MeshDisplacement.Outcome.Goodbye)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Connection errored, or sent a frame this build refuses — treat as remote disconnect.
        } finally {
            // NonCancellable is NOT used here (matching the pre-#2474 shape): `removePeer` already
            // shields its own closes. What is new is that a drained link also has to settle — its
            // ordering hold must be released whether the drain ended in a goodbye or in the link
            // dying under it, or the peer's live frames buffer to the cap for nothing.
            removePeer(link.remoteId, link.conn)
            endDrain(link, MeshDisplacement.Outcome.LinkLoss)
        }
    }

    // ── the graceful displacement drain (#2474) ─────────────────────────────────

    /**
     * Begin draining [loser], a link this seam's dedup has just displaced. Runs OUTSIDE [lock], with
     * the [Drain] already registered by [admitOrReject]'s critical section.
     *
     * Three things happen, in this order, and the order is load-bearing:
     *  1. **arm the peer's ordering hold on this drain**, so the very next frame staged from its live
     *     link is buffered rather than delivered ahead of the drained link's tail;
     *  2. **start [drainBound]**, the zombie-link backstop already created by [boundFor];
     *  3. **write exactly one [MeshFrameType.Goodbye]**, the last thing this seam ever puts on this
     *     link. It is FIFO behind every window frame, which is what makes it a sound end-of-tail
     *     marker one layer above a `Connection.close` that guarantees no flush at all.
     *
     * A goodbye the transport refuses outright means the link is already gone, so the drain ends
     * immediately rather than waiting out a bound on a link that can never deliver anything.
     *
     * **The one race, and why the undo is now a no-op rather than a decision.** A terminator can end
     * this drain between [admitOrReject] releasing [lock] and step 1 — the replace arm's loser is
     * already being read, so its link can die in that window. The [endDrain] that won then released a
     * hold entry that did not yet exist, and step 1 would leave one behind that nothing ever releases.
     * So step 1 is followed by a re-check, and the undo simply calls [releaseOrderingHold] for **this
     * drain's link**. Because a release is keyed on the link that armed it, an undo can never disturb
     * a sibling drain to the same peer — which the previous "is any other drain still running?" form
     * could, by asking [lock] and then acting on the answer across a suspension.
     *
     * **The residual on the keep arm, stated rather than hidden.** On the replace arm the winner's
     * read loop has not started when this returns, so no live-link frame can outrun the hold. On the
     * keep arm the winner has been reading all along, and the hold is armed a few instructions after
     * the dedup decision — but the wider and unavoidable window is earlier still: the loser's frames
     * sit unread in its [singleCollection] inbox for the whole handshake, so anything the remote had
     * already moved to the winner is past us before this seam knows a second link exists. What bounds
     * both is that a remote writes to exactly ONE link per peer at a time, so it has nothing on the
     * winner until it has finished with the loser.
     */
    private suspend fun startDrain(loser: Link) {
        // Step 1 runs in the CALLER, so `addLink` can rely on the hold being armed before it launches
        // the winner's read loop.
        armOrderingHold(loser)
        val drain = lock.withLock { draining[loser] }
        if (drain == null) {
            // A terminator won the race described above. Undo our own arm; keyed on this link, so a
            // sibling drain to the same peer keeps its hold and its contents.
            releaseOrderingHold(loser)
            return
        }
        drain.bound.start()
        // Step 3 does NOT run in the caller: it touches the drained link, and a loser whose `send`
        // wedges must not be able to keep the winner from being read, nor to hold `addLink`'s caller
        // (an accept pump) open. Best-effort because nothing follows it.
        scope.launch { runCatchingCancellable { sayGoodbye(loser) } }
    }

    /** Step 3 of [startDrain] — the last frame this seam ever writes to [loser]. */
    private suspend fun sayGoodbye(loser: Link) {
        val sent = runCatchingCancellable { loser.conn.send(MeshWire.encodeGoodbye()) }
        if (sent.isFailure) endDrain(loser, MeshDisplacement.Outcome.LinkLoss)
    }

    /**
     * End the drain of [loser] and dispose of the link: close it, release its ordering-hold claim,
     * and report.
     *
     * Idempotent and safe from every terminator — the remote's goodbye, [drainBound], a refused
     * goodbye, and the link dying under the drain all funnel here, and only the first one to take the
     * [Drain] out of [draining] does any work.
     *
     * The hold release is **unconditional and keyed on [loser]**, never on a "was I the last drain to
     * this peer?" question answered under [lock] and acted on after the suspending close below. That
     * earlier shape had a redial adopt the hold in the gap and then lose it — its buffered frames
     * flushed ahead of its own tail, silently, with no overflow report. See [OrderingHold].
     *
     * ## The residual this cannot close, recorded rather than hidden
     * Disposing on the remote's goodbye can still destroy bytes of OUR OWN that a transport has
     * accepted but not yet put on the wire. It is a far smaller window than the one this closes —
     * today's close lands immediately after the swap, this one lands after a full goodbye exchange —
     * but it is not zero, and the honest bound on it is the transport's, not ours.
     */
    private suspend fun endDrain(loser: Link, outcome: MeshDisplacement.Outcome) {
        val drain = lock.withLock { draining.remove(loser) } ?: return

        // Cancel the backstop unless it is the job currently running — cancelling ourselves here
        // would abort the rest of this function, which is precisely the hold release and the report
        // the [MeshDisplacement.Outcome.Bound] path exists to perform.
        val thisJob = currentCoroutineContext()[Job]
        drain.bound.takeIf { it !== thisJob }?.cancel()

        closeBestEffort(loser.conn)
        releaseOrderingHold(loser)
        onDisplacement(MeshDisplacement.Drained(drain.peer, drain.arm, outcome, drain.drainedFrames))
    }

    // ── the receiver ordering hold (#2474) ──────────────────────────────────────

    /**
     * Register [loser]'s claim on its peer's ordering hold: from now until every drain to that peer
     * has released, frames arriving on the peer's LIVE link are buffered instead of delivered.
     *
     * A second concurrent drain to one peer joins the existing hold and its contents rather than
     * starting a fresh, empty one — and, unlike a bare "the key is present" flag, it is *recorded* as
     * a second claim, so releasing the first cannot release the second's.
     */
    private suspend fun armOrderingHold(loser: Link) {
        stageMutex.withMutex { orderingHolds.getOrPut(loser.remoteId) { OrderingHold() }.waitingOn += loser }
    }

    /**
     * [armOrderingHold] without [stageMutex] — **callable from `init` and nowhere else** (#2485).
     *
     * The mutex exists to make the hold decision and the delivery it guards one atomic step against
     * concurrent read loops. In `init` there are no read loops: this seam has not been published to its
     * caller, `scope` has launched nothing, and every drain registration and arm happens before the first
     * `scope.launch`. Exclusivity is therefore **structural** rather than acquired — the same argument the
     * `links.putAll(initialLinks)` above rests on, and the reason `orderingHolds`'s "guarded by
     * [stageMutex] only" is not weakened by this: there is nothing here to be guarded against. `launch`
     * publishes these writes to the coroutines that follow.
     *
     * This is what lets construction arm before ANY read loop starts, which is strictly tighter than
     * [startDrain] manages on either [addLink] arm — and it is why `init` needs no `startDrain` undo: the
     * race that undo exists for is a terminator ending the drain across [armOrderingHold]'s suspension,
     * and this does not suspend.
     *
     * Calling it after construction would be a data race on [orderingHolds]. Use [armOrderingHold].
     */
    private fun armOrderingHoldAtConstruction(loser: Link) {
        orderingHolds.getOrPut(loser.remoteId) { OrderingHold() }.waitingOn += loser
    }

    /**
     * Drop [loser]'s claim on its peer's hold, and — only when it was the last claim — deliver
     * everything the hold buffered, in arrival order.
     *
     * Idempotent and total: a link with no claim (already released, or a hold the overflow path
     * discarded outright) is a structural no-op rather than a decision, which is what makes it safe
     * to call from [startDrain]'s undo and from every [endDrain] terminator alike.
     *
     * The flush happens under [stageMutex], so a frame arriving on another loop mid-flush queues
     * behind the buffer rather than overtaking it. That is the entire reason the mutex exists.
     */
    private suspend fun releaseOrderingHold(loser: Link) {
        stageMutex.withMutex {
            val hold = orderingHolds[loser.remoteId] ?: return@withMutex
            if (!hold.waitingOn.remove(loser)) return@withMutex
            if (hold.waitingOn.isNotEmpty()) return@withMutex
            orderingHolds.remove(loser.remoteId)
            for (frame in hold.frames) deliverStagedLocked(loser.remoteId, frame)
        }
    }

    /**
     * The ONE path from a [readLoop] to [spool], and the place the ordering hold is applied.
     *
     * ## Buffer-and-continue, never suspend — the deadlock this shape exists to make impossible
     * The hold's release is performed by [endDrain], reached from the DRAINED link's own read loop
     * when the remote's goodbye arrives. That loop reaches [spool] through this same [stageMutex].
     * So if a live-link frame *suspended* here while holding the mutex, waiting for the drain to end:
     * the drained loop could not deliver its tail (it needs the mutex), [releaseOrderingHold] could
     * not run (it needs the mutex), and the goodbye that would release the hold could never be
     * processed — a permanent wedge, on a per-link-loop fabric just as surely as on `:kuilt-nw`'s
     * single demux loop.
     *
     * The claim that rules it out is **no circular wait**, not "this returns quickly":
     *  - when the hold is armed and has room, this appends and returns without touching [spool] at all;
     *  - every other holder of [stageMutex] — the pass-through below, the overflow flush, and
     *    [releaseOrderingHold] — waits on nothing but [Spool.deliver], i.e. on the CONSUMER of
     *    [Seam.incoming], which is external to this seam and produced by no read loop;
     *  - [lock] is never held while [stageMutex] is acquired, and [stageMutex] is never held while
     *    [lock] is acquired, so the two cannot be taken in opposite orders.
     *
     * A frame from a DRAINING link is never held — it is the tail the hold is waiting for.
     *
     * ## Overflow releases EARLY rather than backpressuring
     * A bounded buffer must do something at the bound, and backpressure is the one option that is not
     * available: suspending here would reintroduce the deadlock by another route. So the hold is
     * released early, the reorder is accepted, and it is reported as
     * [MeshDisplacement.OrderingHoldOverflowed] — a loud, bounded admission that [Seam.incoming]'s
     * send-order promise was traded for liveness on this peer, in preference to a silent wedge.
     *
     * ## Sequence is stamped HERE, at release time
     * [Swatch.sequence] is assigned inside [stageMutex] immediately before delivery, so stamped order
     * is delivery order for held and unheld frames alike. Stamping at arrival time would have
     * numbered a held frame ahead of the live-link frames delivered while it waited.
     */
    private suspend fun stageInbound(link: Link, frame: ByteArray) {
        val fromDrainingLink = lock.withLock { draining[link]?.also { it.drainedFrames += 1 } != null }
        // Nullable rather than a count, because the count is the wrong discriminator: at a capacity of
        // zero the hold gives up on its FIRST frame with nothing buffered, and keying the report on
        // `> 0` would trade the send-order promise away in exactly the silence this event exists to
        // prevent. `buildMesh` also refuses a non-positive capacity outright, so that is belt and
        // braces — but the belt is the one that survives a future caller.
        var overflowedAt: Int? = null
        stageMutex.withMutex {
            val hold = if (fromDrainingLink) null else orderingHolds[link.remoteId]
            if (hold != null) {
                if (hold.frames.size < orderingHoldCapacity) {
                    hold.frames += frame
                    return@withMutex
                }
                overflowedAt = hold.frames.size
                // Removed OUTRIGHT, claims and all: the hold has given up on ordering for this peer,
                // so a later `releaseOrderingHold` from either drain must find nothing and do nothing.
                orderingHolds.remove(link.remoteId)
                for (buffered in hold.frames) deliverStagedLocked(link.remoteId, buffered)
            }
            deliverStagedLocked(link.remoteId, frame)
        }
        overflowedAt?.let {
            onDisplacement(MeshDisplacement.OrderingHoldOverflowed(link.remoteId, it, orderingHoldCapacity))
        }
    }

    /**
     * Stamp [frame] and deliver it. **Called only under [stageMutex]**, which is what makes the stamp
     * order and the delivery order the same order.
     *
     * `dropFirst` strips the type byte as a ZERO-COPY view — the pattern [Swatch] documents for
     * framing layers — rather than copying every received payload a second time.
     */
    private suspend fun deliverStagedLocked(sender: PeerId, frame: ByteArray) {
        spool.deliver(
            Swatch(payload = frame, sender = sender, sequence = seq.incrementAndGet())
                .dropFirst(MeshWire.TYPE_BYTES),
        )
    }

    /**
     * Single-shot seam teardown. The first caller collapses the rosters and latches [SeamState.Torn]
     * in ONE critical section under [lock], returning the connections to close; every later caller
     * sees the latch already set and returns `null`. Doing the roster-collapse and the `tear()` in the
     * same lock keeps "peers before state" atomic — a consumer that observes `Torn` already sees the
     * collapsed roster — and means a post-tear [removePeer]/[admitOrReject] (which take the same lock)
     * see `Torn` and cannot republish a stale roster. The suspending `conn.close()` happens OUTSIDE the
     * lock, in the caller. `scope.cancel()` cancels every `readLoop`, whose `finally` calls back into
     * [removePeer] — a no-op once `links` is cleared. The gate ([stateGate]) subsumes the old `closed`
     * atomic; `tear()` is safe to call from `readLoop`'s `finally` (a plain latched write, no join).
     */
    private fun tearDown(reason: CloseReason): List<Connection>? {
        val conns = lock.withLock {
            // Single-shot INSIDE the lock: a loser sees Torn already latched and bails.
            if (state.value is SeamState.Torn) return null
            // DRAINING links are in the snapshot too (#2474). They are deliberately absent from
            // `links`, so without this a teardown mid-drain would cancel their read loops and leave
            // the connections open forever — exactly the half-open leak this snapshot exists to
            // prevent, reintroduced by the one map that does not appear in it.
            val snapshot = links.values.map { it.conn } + draining.keys.map { it.conn }
            links.clear()
            draining.values.forEach { it.bound.cancel() }
            draining.clear()
            _peers.value = setOf(selfId)
            _attestedPrincipals.value = emptyMap()
            // Latch Torn LAST in this section so the collapsed roster is published before Torn.
            stateGate.tear(reason)
            snapshot
        }
        spool.close()
        scope.cancel()
        return conns
    }

    /**
     * Remove a peer from the live link map and update the peer set. Thread-safe.
     *
     * When [conn] is given, only remove the peer if the live link is THAT conn — so a stale
     * readLoop for a deduped/replaced link can't evict the surviving link to the same peer.
     */
    private suspend fun removePeer(remoteId: PeerId, conn: Connection? = null) {
        // buildPeerSet and _peers.value assignment are inside the same lock acquisition as the
        // remove so that tearDown's peers-collapse (also inside the lock) cannot be overwritten
        // by a stale buildPeerSet result computed before tearDown cleared links. The drain check
        // reads `links` in the SAME critical section as the removal so the "last link gone"
        // decision cannot race a concurrent removal/admission.
        val drained = lock.withLock {
            val live = links[remoteId] ?: return@withLock false
            if (conn != null && live.conn !== conn) return@withLock false
            links.remove(remoteId)
            publishRosters()
            latchTornWhenDrained && links.isEmpty()
        }
        if (!drained) return

        // Drain-latch (peer-mesh only): the last link dropped after the mesh was non-empty. Funnel
        // through the same single-shot teardown as close() — latch Torn, close the inbound spool, and
        // cancel the read scope, single-shot and ordered (Torn is latched inside the lock; the spool
        // close/scope cancel follow, matching close()). A concurrent removePeer that also observes the
        // empty set is a no-op (tearDown returns null the second time).
        //
        // We MUST close the returned conns, exactly as close() does. Usually the map is already empty
        // here (this removal emptied it), so there is nothing to close — but an addLink that admitted a
        // fresh link in the window between this lock release and tearDown re-acquiring the lock leaves
        // that just-installed conn in tearDown's snapshot; dropping it on the floor would leak a
        // half-open connection (seam Torn, scope cancelled, conn never closed).
        val toClose = tearDown(CloseReason.RemoteRequested).orEmpty()
        // NonCancellable: the drain path can run inside readLoop's `finally`, and tearDown's
        // scope.cancel() cancels exactly that readLoop's coroutine — without NonCancellable the closes
        // below would be skipped by the in-flight cancellation, reintroducing the leak.
        withContext(NonCancellable) {
            // Per-conn `try`/`catch (Throwable)` rather than `runCatchingCancellable`: inside the shield
            // this block's Job is parented to [NonCancellable], so a `CancellationException` arriving here
            // can only be one the consumer's `Connection.close` minted itself (a close-handshake
            // `withTimeout`) — never our own cancellation. `runCatchingCancellable` would rethrow that one
            // case, abandoning every remaining conn and reintroducing the very half-open-connection leak
            // this shield exists to prevent (#1803).
            toClose.forEach {
                try {
                    it.close()
                } catch (_: Throwable) {
                    // Best-effort: one conn refusing to close must not stop its siblings being closed.
                }
            }
        }
    }

    private fun buildPeerSet(): Set<PeerId> =
        buildSet {
            add(selfId)
            addAll(links.keys)
        }

    /** Attested principals of the live links. MUST be called with [lock] held (reads [links]). */
    private fun buildRoster(): Map<PeerId, Principal> =
        buildMap {
            links.values.forEach { link -> link.principal?.let { put(link.remoteId, it) } }
        }
}
