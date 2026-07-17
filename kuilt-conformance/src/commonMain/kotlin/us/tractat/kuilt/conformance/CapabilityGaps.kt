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

    /**
     * A conformance harness that cannot inject a **membership drain** — a peer leaving
     * `Seam.peers` while the survivor's `state` stays [us.tractat.kuilt.core.SeamState.Woven]
     * (no transport tear), the distinct event #1466 relied on (see
     * [SeamConformanceSuite.membershipDrainGap]). Not a *fabric* gap — a *harness* gap: a
     * strictly-2-peer mesh must latch [us.tractat.kuilt.core.SeamState.Torn] when its only
     * link drops, so it cannot model a drain-without-tear at all; only an N-peer harness
     * (e.g. the shared-roster `InMemoryLoom`) can drop one peer and leave the survivor Woven.
     */
    public const val MEMBERSHIP_DRAIN: String =
        "https://github.com/tractat-us/kuilt/issues/1474"

    /**
     * A conformance harness that cannot inject a **self-dial** — a peer resolving a connection to its
     * OWN `selfId`, the #1466 class (symmetric advertise+browse fabrics are delivered their own
     * advertisement and dial it; see [SeamConformanceSuite.selfDialGap]). Not a *fabric* gap — a
     * *harness* gap: only a harness that can drive a live seam to see a connection to its own identity
     * (e.g. the `FakeNwRadio` self-endpoint delivery) can prove the guard drops it; a relay/2-peer
     * harness with no self-discovery cannot self-dial at all.
     */
    public const val SELF_DIAL: String =
        "https://github.com/tractat-us/kuilt/issues/1502"
}
