package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalAttested

/**
 * Admission policy for mesh links: decides, per link, whether a handshaked connection may
 * join the mesh.
 *
 * Enforced inside [Mesh.addLink] (and, symmetrically, on construction-time connections)
 * at the one point where both identity facts coexist on the same object *before* the link
 * is live: after the `MeshHello` handshake (the first moment the joiner's self-asserted
 * [PeerId] is known) and before the link is published (the moment it can contend in
 * duplicate-link dedup or receive frames). A rejected link is closed without ever reaching
 * the dedup tiebreak — a forged link can never displace a live one.
 *
 * **A supplied policy is authoritative for every link, including unattested ones**: a
 * connection with no verified principal reaches the policy with `principal = null` and the
 * policy decides. The default is [AcceptAll] — byte-identical to a mesh with no admission
 * policy.
 *
 * kuilt deliberately does **not** hardcode a `principal == peerId` binding — the
 * relationship between an auth subject and a mesh peer id is consumer-defined (one user
 * may run several devices). The spoofing check (verified principal ↔ claimed peer id) is
 * one line of consumer policy:
 *
 * ```kotlin
 * LinkAdmission { principal, remoteId -> principal?.value == remoteId.value }
 * ```
 */
public fun interface LinkAdmission {
    /**
     * Decide whether a handshaked link may join.
     *
     * @param principal the host-verified identity riding the connection (attached by the
     *   transport accept via [withPrincipal][us.tractat.kuilt.core.withPrincipal]);
     *   `null` = unattested.
     * @param remoteId the peer id the joiner claimed in its `MeshHello` preamble
     *   (self-asserted, unverified).
     * @return `true` to admit the link; `false` to close the connection and reject it
     *   with [LinkRejectedException].
     */
    public suspend fun admit(principal: Principal?, remoteId: PeerId): Boolean

    public companion object {
        /** Today's behaviour: every link joins. The open-by-default policy. */
        public val AcceptAll: LinkAdmission = LinkAdmission { _, _ -> true }

        /** Closed mode: only links carrying a verified [Principal] join ([PrincipalAttested]). */
        public val RequireAttested: LinkAdmission = LinkAdmission { principal, _ -> principal != null }
    }
}

/**
 * The per-link signal raised by [Mesh.addLink] when a handshaked link is rejected by the mesh's
 * [LinkAdmission] policy. The offending connection is already closed and was never published.
 *
 * **Non-fatal by design.** Rejection affects only the one link — it never tears down the seam or
 * any other admitted link. A hub accept-pump (`hostedOverlay`) absorbs this and debug-logs it, then
 * keeps serving; kuilt-core stays logger-free (its dependency contract), so the rejection is raised
 * here and logged by the pump, which owns a logger. `meshSeam` **construction** does not throw at
 * all: a rejected construction link is closed and dropped, and the mesh is built from the survivors
 * (reject-and-continue, no sibling teardown).
 *
 * The message deliberately reports only whether the link was attested — never the [Principal] value
 * itself, which may be sensitive and does not belong in logs.
 */
public class LinkRejectedException(
    /** The peer id the rejected link claimed in its `MeshHello` preamble. */
    public val remoteId: PeerId,
    /** Whether the rejected link carried a verified principal. */
    public val attested: Boolean,
) : Exception("mesh link claiming $remoteId rejected by admission policy (attested=$attested)")
