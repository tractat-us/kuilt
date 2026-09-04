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
     * A fabric with no live OS path observer, so [us.tractat.kuilt.core.Seam.capability] is a static
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor rather than a reactive value. A *fabric*
     * gap, not a harness gap: the platform observer simply has not been wired yet. Track B
     * (#1542 multipeer, #1543 nearby, #1544 webrtc, #1545 composite, #1546 mux, #1725 websocket)
     * closes these one lane at a time.
     */
    public const val LIVE_CAPABILITY: String =
        "https://github.com/tractat-us/kuilt/blob/main/docs/architecture.md#reportslivecapability--fabrics-without-a-path-observer"

    /**
     * A conformance harness that has not implemented mid-session transport-death injection
     * (the default [ObligationDeclaration.Gap] of [SeamConformanceSuite.midSessionDeathDeclaration]).
     * Not a *fabric* gap — a *harness* gap:
     * the harness cannot reach a raw transport handle to drop out from under a live session,
     * so the remote-disconnect half of the `incoming`-completes-on-Torn obligation is unproven.
     */
    public const val MID_SESSION_DEATH: String =
        "https://github.com/tractat-us/kuilt/issues/1442"

    /**
     * A conformance harness that cannot inject a **membership drain** — a peer leaving
     * `Seam.peers` while the survivor's `state` stays [us.tractat.kuilt.core.SeamState.Woven]
     * (no transport tear), the distinct event #1466 relied on (see
     * [SeamConformanceSuite.membershipDrainDeclaration]). Not a *fabric* gap — a *harness* gap: a
     * strictly-2-peer mesh must latch [us.tractat.kuilt.core.SeamState.Torn] when its only
     * link drops, so it cannot model a drain-without-tear at all; only an N-peer harness
     * (e.g. the shared-roster `InMemoryLoom`) can drop one peer and leave the survivor Woven.
     */
    public const val MEMBERSHIP_DRAIN: String =
        "https://github.com/tractat-us/kuilt/issues/1474"

    /**
     * A conformance harness that cannot inject a **self-dial** — a peer resolving a connection to its
     * OWN `selfId`, the #1466 class (symmetric advertise+browse fabrics are delivered their own
     * advertisement and dial it; see [SeamConformanceSuite.selfDialDeclaration]). Not a *fabric* gap — a
     * *harness* gap: only a harness that can drive a live seam to see a connection to its own identity
     * (e.g. the `FakeNwRadio` self-endpoint delivery) can prove the guard drops it; a relay/2-peer
     * harness with no self-discovery cannot self-dial at all.
     */
    public const val SELF_DIAL: String =
        "https://github.com/tractat-us/kuilt/issues/1502"

    /**
     * A fabric that names no frame ceiling — [us.tractat.kuilt.core.Seam.maxPayloadBytes] is `null`
     * (see [SeamConformanceSuite.payloadBudgetGap]).
     *
     * Unlike every other constant here this one is **usually not a shortfall at all**: `null` is the
     * honest answer from a fabric with no wire limit to name (in-memory, and every relay fabric whose
     * `Connection` publishes nothing), and the contract says so. It is *declared* rather than assumed
     * because of the one case the suite structurally cannot test — a fabric that **enforces** a
     * ceiling while publishing `null`, which proving would take a payload the size of the hidden
     * limit. `NwSeam` is the known instance (16 MiB via `encodeFrame`), and it is why a declaration
     * rather than an assertion is the right mechanism here: the *declaration* is what surfaced it.
     *
     * A fabric in that position should point [SeamConformanceSuite.payloadBudgetGap] at **its own**
     * blocking issue rather than at this constant, which says only "no ceiling to name". `NwSeam`
     * does: it has a ceiling and refuses above it, but cannot yet promise it, because its receive
     * path drops bytes under a multi-chunk burst (#2134). The doc section below tracks the rest.
     */
    public const val PAYLOAD_BUDGET: String =
        "https://github.com/tractat-us/kuilt/blob/main/docs/architecture.md#payloadbudget--fabrics-that-name-no-frame-ceiling"
}
