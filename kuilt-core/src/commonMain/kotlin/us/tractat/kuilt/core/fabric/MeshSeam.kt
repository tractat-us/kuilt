package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Wire format: length-prefix — `[4-byte big-endian id length][id UTF-8 bytes][NONCE_BYTES nonce bytes]`.
 * No delimiter: the id length field makes the frame self-describing. The nonce is raw bytes (not
 * hex-encoded) and always exactly [NONCE_BYTES] bytes long — enforced by both [encode] and [decode],
 * not merely documented (#1812).
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
            return ByteArray(4 + idBytes.size + nonce.size).also { buf ->
                buf.writeInt(idBytes.size, offset = 0)
                idBytes.copyInto(buf, destinationOffset = 4)
                nonce.copyInto(buf, destinationOffset = 4 + idBytes.size)
            }
        }

        /**
         * Decode a preamble frame, throwing [IllegalArgumentException] if it is malformed.
         *
         * The frame is the **first bytes a remote sends**, so every check runs before the read it
         * protects (#1788): a frame shorter than the 4-byte length prefix would index-fault inside
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
        public fun decode(frame: ByteArray): MeshHello {
            require(frame.size >= Int.SIZE_BYTES) {
                "truncated MeshHello: ${frame.size} bytes cannot hold the ${Int.SIZE_BYTES}-byte id length"
            }
            val idLen = frame.readInt(offset = 0)
            require(idLen >= 0) { "malformed MeshHello: negative declared id length $idLen" }
            require(frame.size - Int.SIZE_BYTES >= idLen) {
                "truncated MeshHello: declared id length $idLen exceeds the ${frame.size}-byte frame"
            }
            // Safe subtraction: the two checks above pin `0 <= idLen <= frame.size - 4`.
            val nonceLen = frame.size - Int.SIZE_BYTES - idLen
            require(nonceLen == NONCE_BYTES) {
                "malformed MeshHello: nonce is $nonceLen bytes, expected exactly $NONCE_BYTES"
            }
            val peerId = PeerId(frame.decodeToString(startIndex = Int.SIZE_BYTES, endIndex = Int.SIZE_BYTES + idLen))
            val nonce = frame.copyOfRange(Int.SIZE_BYTES + idLen, frame.size)
            return MeshHello(peerId, nonce)
        }
    }
}

private const val NONCE_BYTES = 16

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
): Mesh = buildMesh(selfId, connections, dispatcher, random, policy, admission, latchTornWhenDrained = false)

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
): Mesh = buildMesh(selfId, connections, dispatcher, random, policy, admission, latchTornWhenDrained = true)

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
): Mesh = buildMesh(selfId, connections, dispatcher, random, policy, admission, latchTornWhenDrained = false)

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
): Mesh {
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
    losers.forEach { closeBestEffort(it.conn) }

    // A2 (born-dead): a peer-mesh that REQUESTED connections but had them ALL rejected/deduped away
    // asked for peers and got none — it has nothing that could ever drain, so it must latch Torn at
    // birth rather than sit Woven forever with an empty roster. A start-empty peer-mesh
    // (`connections` empty) requested nothing, so it is NOT born-dead: it stays Woven and grows via
    // addLink. Hubs never latch, so bornDead is always false for them (latchTornWhenDrained = false).
    val bornDead = latchTornWhenDrained && connections.isNotEmpty() && winners.isEmpty()
    return MeshSeam(selfId, winners, dispatcher, random, policy, admission, latchTornWhenDrained, bornDead)
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
    single.send(MeshHello.encode(selfId, myNonce))
    val remote = MeshHello.decode(single.firstFrame())
    return Link(remote.peerId, single, canonicalLinkNonce(myNonce, remote.nonce), principal)
}

/**
 * Close [conn] best-effort: the *conn's* failure to close must not cancel the work that follows (#1834).
 *
 * Every unshielded close in this file goes through here. **The trigger is not "a loop" — it is whether
 * anything the mesh still owes follows the close.** #1834 originally scoped itself to the two multi-item
 * loops and filed the single-item closes as unaffected ("nothing follows them to skip"); that is wrong
 * for three of them, and the item count was never the right axis:
 *
 *  - [MeshSeam.close] / [buildMesh]'s dedup — the rest of the roster follows. A half-open leak, and
 *    unbounded on a hub, which accepts arbitrarily many spokes.
 *  - [Mesh.addLink]'s displaced loser — the WINNER'S `readLoop` launch follows, and `admitOrReject` has
 *    already installed that winner and published the rosters. A rethrow leaves a **zombie link**: the
 *    peer sits in [Seam.peers] with nothing ever reading its conn, so its frames are never delivered
 *    and its disconnect is never noticed. The escaping `CancellationException` then skips `acceptPump`'s
 *    cleanup and permanently cancels `superviseVoterReconnection`'s per-peer coroutine.
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

private class MeshSeam(
    override val selfId: PeerId,
    initialLinks: Map<PeerId, Link>,
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
) : Mesh {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // `links` is shared between `broadcast`/`sendTo` (caller threads) and each `readLoop`'s
    // teardown / per-link send-failure removal (dispatcher threads), and `addLink`. It is guarded
    // by `lock`: every read and mutation happens under the lock. Suspending `conn.send`/`conn.close`
    // are NEVER invoked while the lock is held — callers snapshot the target conn(s) under the lock,
    // release, then send/close outside it.
    private val lock = reentrantLock()
    private val links = mutableMapOf<PeerId, Link>()

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
     */
    override val maxPayloadBytes: Int?
        get() = lock.withLock { links.values.mapNotNull { it.conn.maxFrameBytes }.minOrNull() }

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

        // Launch a supervised reader for each initial link.
        initialLinks.values.forEach { link -> scope.launch { readLoop(link.remoteId, link.conn) } }

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
        // Snapshot the loser under the lock, close it outside.
        val loser = admitOrReject(link)
        // Best-effort (see [closeBestEffort]). This closes ONE conn, but item count is the wrong axis:
        // what matters is that work which MUST happen follows it. `admitOrReject` has already installed
        // the winner in `links` and published the rosters, and the winner's readLoop launches on the
        // next line — so rethrowing a cancellation the loser's conn minted would leave a ZOMBIE LINK
        // (peer in `peers`, nothing ever reading its conn, its disconnect never noticed) and let a bare
        // CancellationException escape `addLink` into `acceptPump` and `superviseVoterReconnection`,
        // skipping their cleanup and cancelling that peer's supervision outright (#1834).
        loser?.let { closeBestEffort(it) }
        if (loser != link.conn) scope.launch { readLoop(link.remoteId, link.conn) }
    }

    /**
     * Install [link] under the lock if it wins dedup, returning the conn to close (the loser) — the
     * incoming conn itself if it lost, the displaced existing conn if it won, or `null` if it is the
     * first link to that peer. Suspending closes happen in the caller, outside the lock.
     */
    private fun admitOrReject(link: Link): Connection? = lock.withLock {
        if (state.value is SeamState.Torn) return@withLock link.conn
        val existing = links[link.remoteId]
        when {
            existing == null -> { links[link.remoteId] = link; publishRosters(); null }
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
            link.linkNonce < existing.linkNonce -> { links[link.remoteId] = link; publishRosters(); existing.conn }
            else -> link.conn
        }
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
        // Snapshot the live links under the lock, then send OUTSIDE it.
        val targets = lock.withLock { links.values.map { it.remoteId to it.conn } }
        targets.forEach { (remoteId, conn) ->
            // Best-effort by contract, and per link: a link too tight for this payload is skipped,
            // while the links that can carry it still get it. Skipping is not a link failure.
            if (conn.oversizeOrNull(payload) != null) return@forEach
            runCatchingCancellable { conn.send(payload) }
                .onFailure { removePeer(remoteId, conn) }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { closedMessage }
        val conn = lock.withLock { links[peer]?.conn } ?: throw PeerNotConnected(peer)
        conn.oversizeOrNull(payload)?.let { throw it }
        runCatchingCancellable { conn.send(payload) }
            .onFailure { removePeer(peer, conn) }
    }

    override suspend fun close(reason: CloseReason) {
        // Snapshot the connections to close under the lock; perform the suspending closes outside it.
        // Per-conn best-effort (see [closeBestEffort]): one wedged or timing-out peer must not strand
        // the rest of the roster half-open, and the roster is unbounded on a hub (#1834).
        val toClose = tearDown(reason) ?: return
        toClose.forEach { conn -> closeBestEffort(conn) }
    }

    private suspend fun readLoop(remoteId: PeerId, conn: Connection) {
        try {
            conn.incoming.collect { bytes ->
                if (state.value !is SeamState.Torn) {
                    // Sequence number is assigned atomically outside the lock. `deliver` SUSPENDS
                    // for backpressure (SUSPEND policy) — never called while holding `lock`.
                    spool.deliver(Swatch(payload = bytes, sender = remoteId, sequence = seq.incrementAndGet()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Connection errored — treat as remote disconnect.
        } finally {
            removePeer(remoteId, conn)
        }
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
            val snapshot = links.values.map { it.conn }
            links.clear()
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
