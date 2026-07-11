package us.tractat.kuilt.conformance

/**
 * A fabric's declared behaviour against the [us.tractat.kuilt.core.Seam] contract.
 *
 * Not every fabric can honor every corner of the contract — a plaintext fabric
 * isn't encrypted on the wire, and a relay-only fabric never delivers
 * peer-to-peer. Rather than let each conformance test carry its own bespoke
 * `@Ignore`, a fabric declares one [SeamCapabilities] value and the shared suite
 * consults it to skip only the specific assertions that don't apply — everything
 * else still runs.
 *
 * The eight flags cover the **historical** `@Ignore` escape hatches
 * ([terminatesIncomingOnClose], [staysTornAfterClose], [throwsOnSendToTorn]) — WebRTC
 * #335 and Multipeer/Gossip #1390, both since fixed — that motivated making
 * capabilities explicit, so a *future* fabric with a real gap in one of those
 * dimensions can declare it without inventing a bespoke `@Ignore`, plus the
 * remaining dimensions ([ordersDelivery], [reportsPeerLoss], [supportsSendTo],
 * [securesTransport], [meshDelivery]) fabrics already vary on.
 */
public data class SeamCapabilities(
    /** FIFO to a single collector. */
    val ordersDelivery: Boolean,
    /** Peer-drop reflected in peers/state. */
    val reportsPeerLoss: Boolean,
    /** `incoming` completes when the seam goes [us.tractat.kuilt.core.SeamState.Torn] (was WebRTC's hatch, #335, since fixed). */
    val terminatesIncomingOnClose: Boolean,
    /** [us.tractat.kuilt.core.SeamState.Torn] is terminal under churn (hatch 2). */
    val staysTornAfterClose: Boolean,
    /** Sending on a [us.tractat.kuilt.core.SeamState.Torn] seam throws (was Multipeer/Gossip's hatch, #1390, since fixed). */
    val throwsOnSendToTorn: Boolean,
    /** Directed send DELIVERS; an absent peer throws [us.tractat.kuilt.core.PeerNotConnected]. */
    val supportsSendTo: Boolean,
    /** Encrypted on the wire (honest — see the fabric's own TLS-PSK threat model). */
    val securesTransport: Boolean,
    /**
     * Peer-to-peer delivery with no relay hop.
     *
     * A fabric declaring `meshDelivery = true` that supports three or more peers MUST
     * also subclass `MeshConformanceSuite` (which exercises N-peer roster convergence,
     * broadcast sender-attribution, directed routing, peer-leave, and dial dedup) —
     * `SeamConformanceSuite` is fixed at two Looms by ADR-001 and cannot host a third
     * peer. A strictly 2-peer fabric may declare `meshDelivery = true` vacuously (no
     * third peer to relay to) and records that vacuity in its capability-matrix entry.
     * This obligation is enforced by the capability matrix (`meshEvidence`, see
     * `CapabilityMatrix`), not by a runtime meta-test.
     */
    val meshDelivery: Boolean,
) {
    /**
     * The canonical names of the flags that are `false` on this value.
     *
     * The names are the property names, and they are also the keys a fabric's
     * `capabilityGaps()` must supply an issue URL for — every `false` flag is a
     * gap that must be declared, so the two are keyed identically. Task 1.8's
     * rendered capability matrix consumes the same names.
     */
    public fun falseFlags(): Set<String> = buildSet {
        if (!ordersDelivery) add("ordersDelivery")
        if (!reportsPeerLoss) add("reportsPeerLoss")
        if (!terminatesIncomingOnClose) add("terminatesIncomingOnClose")
        if (!staysTornAfterClose) add("staysTornAfterClose")
        if (!throwsOnSendToTorn) add("throwsOnSendToTorn")
        if (!supportsSendTo) add("supportsSendTo")
        if (!securesTransport) add("securesTransport")
        if (!meshDelivery) add("meshDelivery")
    }

    public companion object {
        /**
         * A fully-featured direct-mesh fabric — most fabrics start here and flip
         * individual flags off to describe where they fall short of the contract.
         */
        public val FULL: SeamCapabilities = SeamCapabilities(
            ordersDelivery = true,
            reportsPeerLoss = true,
            terminatesIncomingOnClose = true,
            staysTornAfterClose = true,
            throwsOnSendToTorn = true,
            supportsSendTo = true,
            securesTransport = true,
            meshDelivery = true,
        )
    }
}
