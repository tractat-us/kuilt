package us.tractat.kuilt.conformance

/**
 * A fabric's declared behaviour against the [us.tractat.kuilt.core.Seam] contract.
 *
 * Not every fabric can honor every corner of the contract — a browser WebRTC
 * data channel can't throw synchronously on a torn send the way an in-process
 * loopback can, and a relay-only fabric never delivers peer-to-peer. Rather than
 * let each conformance test carry its own bespoke `@Ignore`, a fabric declares
 * one [SeamCapabilities] value and the shared suite consults it to skip only the
 * specific assertions that don't apply — everything else still runs.
 *
 * The eight flags cover **all three** current `@Ignore` escape hatches
 * ([terminatesIncomingOnClose], [staysTornAfterClose], [throwsOnSendToTorn]) so
 * every fabric can migrate onto this declaration without inventing new flags
 * mid-flight, plus the remaining dimensions ([ordersDelivery], [reportsPeerLoss],
 * [supportsSendTo], [securesTransport], [meshDelivery]) fabrics already vary on.
 */
public data class SeamCapabilities(
    /** FIFO to a single collector. */
    val ordersDelivery: Boolean,
    /** Peer-drop reflected in peers/state. */
    val reportsPeerLoss: Boolean,
    /** `incoming` completes when the seam goes [us.tractat.kuilt.core.SeamState.Torn] (hatch 1, WebRTC #335). */
    val terminatesIncomingOnClose: Boolean,
    /** [us.tractat.kuilt.core.SeamState.Torn] is terminal under churn (hatch 2). */
    val staysTornAfterClose: Boolean,
    /** Sending on a [us.tractat.kuilt.core.SeamState.Torn] seam throws (hatch 3, #1390). */
    val throwsOnSendToTorn: Boolean,
    /** Directed send DELIVERS; an absent peer throws [us.tractat.kuilt.core.PeerNotConnected]. */
    val supportsSendTo: Boolean,
    /** Encrypted on the wire (honest — see the fabric's own TLS-PSK threat model). */
    val securesTransport: Boolean,
    /** Peer-to-peer delivery with no relay hop. */
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
