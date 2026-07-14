package us.tractat.kuilt.conformance

/** Stable [CapabilityGaps] URLs for by-design capability limitations (see docs/architecture.md#capability-gaps-by-design). */
public object CapabilityGaps {
    /** Fabrics unencrypted on the wire by design (plaintext ws://, raw TCP, in-memory). */
    public const val SECURES_TRANSPORT: String =
        "https://github.com/tractat-us/kuilt/blob/main/docs/architecture.md#securestransport--fabrics-without-wire-encryption"

    /** Relay / multi-hop fabrics that do not deliver directly peer-to-peer. */
    public const val MESH_DELIVERY: String =
        "https://github.com/tractat-us/kuilt/blob/main/docs/architecture.md#meshdelivery--relay-and-multi-hop-fabrics"

    /**
     * A conformance harness that has not implemented mid-session transport-death injection
     * (see [SeamConformanceSuite.midSessionDeathGap]). Not a *fabric* gap — a *harness* gap:
     * the harness cannot reach a raw transport handle to drop out from under a live session,
     * so the remote-disconnect half of the `incoming`-completes-on-Torn obligation is unproven.
     */
    public const val MID_SESSION_DEATH: String =
        "https://github.com/tractat-us/kuilt/issues/1442"
}
