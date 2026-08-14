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
 * The nine flags cover the **historical** `@Ignore` escape hatches
 * ([terminatesIncomingOnClose], [staysTornAfterClose], [throwsOnSendToTorn]) — WebRTC
 * #335 and Multipeer/Gossip #1390, both since fixed — that motivated making
 * capabilities explicit, so a *future* fabric with a real gap in one of those
 * dimensions can declare it without inventing a bespoke `@Ignore`, plus the
 * remaining dimensions ([reportsPeerLoss], [supportsSendTo], [securesTransport],
 * [meshDelivery], [reportsLiveCapability], [collapsesPeersOnTear]) fabrics already
 * vary on.
 *
 * ## Two kinds of flag, and the one that was neither (#2304)
 *
 * Publishing a value here **subscribes the fabric to every suite case selected on it**, so a flag
 * earns its place only if some property reads it. Two shapes qualify:
 *  - a **gated obligation** — a property early-returns on `false`, and the shortfall is made loud by
 *    [SeamConformanceSuite.everyFalseCapabilityDeclaresAGap] demanding a tracking URL. All of
 *    [terminatesIncomingOnClose], [staysTornAfterClose], [throwsOnSendToTorn], [supportsSendTo],
 *    [reportsPeerLoss] and [collapsesPeersOnTear] are this;
 *  - a **selector** — the flag picks *which* assertion applies and never skips ([reportsLiveCapability]).
 *
 * `ordersDelivery` ("FIFO to a single collector") was **neither**, and was deleted here. No property
 * read it, while the obligation it named —
 * [SeamConformanceSuite.incomingPreservesSendOrderToSingleCollector] — sat in the *ungated core*
 * block, whose defining guarantee (pinned by `SeamConformanceUngatedCoreTest`'s hostile harness,
 * which made reading `capabilities()` throw) is that no flag can suppress it. So the one value the
 * flag existed to express was unreachable: a fabric declaring `ordersDelivery = false` and supplying
 * a gap URL — the whole documented workflow for a shortfall — was still held to the order property
 * and still failed, while paying the permanently-open-issue toll for the privilege. Of #2304's two
 * options (delete the flag, or move ordering out of core and gate it), deletion is the one that keeps
 * a decision already made: ordering is a *contract* property of [us.tractat.kuilt.core.Seam.incoming],
 * not a transport-shaped limitation a fabric may honestly lack, no in-tree fabric declared it `false`,
 * and the core/ungated split is deliberate and meta-tested. A fabric that reorders frames is
 * non-conforming, full stop.
 *
 * [securesTransport] is a **third** shape and is documented as such on its own declaration: a
 * standing declaration no property can read, because the suite has no wire tap.
 */
public data class SeamCapabilities(
    /**
     * Peer-drop reflected in peers/state — a peer that leaves the session must stop being advertised
     * as reachable by the peers that stay.
     *
     * Read by [SeamConformanceSuite.survivorStopsAdvertisingADepartedPeer], which is what makes this
     * a gated obligation rather than a free declaration (#2303/#2304). `false` means the survivor may
     * keep a departed peer in [us.tractat.kuilt.core.Seam.peers] while staying
     * [us.tractat.kuilt.core.SeamState.Woven] — i.e. it advertises a peer
     * [us.tractat.kuilt.core.Seam.sendTo] would refuse, which `Seam.peers`' own KDoc forbids. Every
     * `false` here is therefore a tracked bug, not a by-design gap.
     */
    val reportsPeerLoss: Boolean,
    /** `incoming` completes when the seam goes [us.tractat.kuilt.core.SeamState.Torn] (was WebRTC's hatch, #335, since fixed). */
    val terminatesIncomingOnClose: Boolean,
    /** [us.tractat.kuilt.core.SeamState.Torn] is terminal under churn (hatch 2). */
    val staysTornAfterClose: Boolean,
    /** Sending on a [us.tractat.kuilt.core.SeamState.Torn] seam throws (was Multipeer/Gossip's hatch, #1390, since fixed). */
    val throwsOnSendToTorn: Boolean,
    /** Directed send DELIVERS; an absent peer throws [us.tractat.kuilt.core.PeerNotConnected]. */
    val supportsSendTo: Boolean,
    /**
     * Encrypted on the wire (honest — see the fabric's own TLS-PSK threat model).
     *
     * **A standing declaration, not a gated obligation — no suite property reads this, and none can
     * (#2304).** Proving a fabric is encrypted takes a tap on the wire between the two seams;
     * [SeamConformanceSuite] holds only the two [us.tractat.kuilt.core.Seam] handles and has no such
     * access, and a property that inspected bytes at either endpoint would be reading plaintext by
     * construction on a conforming and a lying fabric alike. So this is the [SeamConformanceSuite.payloadBudgetGap]
     * shape — something written down so it is *declared* rather than assumed — and the next reader
     * should not go looking for the property that holds a fabric to it.
     *
     * What holds a fabric to it instead is **fabric-owned**: a harness that runs the suite over a link
     * that is genuinely encrypted, so the whole suite passing IS the evidence. `NwLoopbackConformanceTest`
     * is the in-tree example — it declares `securesTransport = true` because its loopback runs real
     * TLS-PSK, while the fake-radio `NwConformanceTest` declares `false` for the same fabric. That
     * split is the honest one, and it is a property of the *harness*, which is exactly why the suite
     * cannot decide it. `false` still costs a tracking URL via
     * [SeamConformanceSuite.everyFalseCapabilityDeclaresAGap] ([CapabilityGaps.SECURES_TRANSPORT] for
     * the by-design plaintext fabrics), so the declaration is never silent.
     */
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
    /**
     * [us.tractat.kuilt.core.Seam.capability] is driven by a **live** OS path observer, so its
     * [us.tractat.kuilt.core.FabricAvailability] tracks the device's real reachability.
     *
     * `false` means the fabric inherits the roleless [us.tractat.kuilt.core.FabricAvailability.Unknown]
     * floor and must not claim otherwise — `Room.localFabric` will read `Unknown` on it (#1712). Flip
     * to `true` only alongside a fabric-owned test proving the observer actually moves the value.
     */
    val reportsLiveCapability: Boolean,
    /**
     * A [us.tractat.kuilt.core.SeamState.Torn] seam's [us.tractat.kuilt.core.Seam.peers] is exactly
     * `{ selfId }` — the collapse obligation stated on `Seam.peers` (#1816).
     *
     * `false` means the fabric leaves a **remote** peer advertised as reachable after it tears (a
     * frozen pre-tear roster, or a shared session registry the closing seam removes *itself* from), so
     * a decorator that folds member seams — `CompositeSeam` — reads it as still reachable until the
     * member is detached. Every `false` here is a bug with a tracking issue, not a by-design gap.
     */
    val collapsesPeersOnTear: Boolean,
) {
    /**
     * The canonical names of the flags that are `false` on this value.
     *
     * The names are the property names, and they are also the keys a fabric's
     * `capabilityGaps()` must supply an issue URL for — every `false` flag is a
     * gap that must be declared, so the two are keyed identically. Task 1.8's
     * rendered capability matrix consumes the same names.
     *
     * Derived from [FLAGS] (the single source of truth) so it can never drift
     * from the capability-matrix column list or the data class itself.
     */
    public fun falseFlags(): Set<String> =
        FLAGS.filterNot { (_, read) -> read(this) }.mapTo(mutableSetOf()) { it.first }

    public companion object {
        /**
         * The single source of truth for the capability flags: each declared
         * boolean property paired with its accessor, in the fixed order the
         * capability matrix renders and [falseFlags] enumerates.
         *
         * Everything that needs to iterate the flags derives from this one list
         * — [falseFlags] filters it, and `CapabilityMatrix`'s render columns are
         * exactly this list — so the two hand-maintained parallel lists that used
         * to exist can no longer silently disagree. The remaining risk (this list
         * omitting a newly-added data-class property) is caught loudly by the JVM
         * reflection meta-test `SeamCapabilitiesReflectionTest`, which asserts
         * these names equal the data class's declared boolean properties.
         */
        internal val FLAGS: List<Pair<String, (SeamCapabilities) -> Boolean>> = listOf(
            "reportsPeerLoss" to SeamCapabilities::reportsPeerLoss,
            "terminatesIncomingOnClose" to SeamCapabilities::terminatesIncomingOnClose,
            "staysTornAfterClose" to SeamCapabilities::staysTornAfterClose,
            "throwsOnSendToTorn" to SeamCapabilities::throwsOnSendToTorn,
            "supportsSendTo" to SeamCapabilities::supportsSendTo,
            "securesTransport" to SeamCapabilities::securesTransport,
            "meshDelivery" to SeamCapabilities::meshDelivery,
            "reportsLiveCapability" to SeamCapabilities::reportsLiveCapability,
            "collapsesPeersOnTear" to SeamCapabilities::collapsesPeersOnTear,
        )

        /**
         * A fully-featured direct-mesh fabric — most fabrics start here and flip
         * individual flags off to describe where they fall short of the contract.
         */
        public val FULL: SeamCapabilities = SeamCapabilities(
            reportsPeerLoss = true,
            terminatesIncomingOnClose = true,
            staysTornAfterClose = true,
            throwsOnSendToTorn = true,
            supportsSendTo = true,
            securesTransport = true,
            meshDelivery = true,
            reportsLiveCapability = true,
            collapsesPeersOnTear = true,
        )
    }
}
