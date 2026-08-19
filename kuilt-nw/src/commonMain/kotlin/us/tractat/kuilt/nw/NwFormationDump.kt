package us.tractat.kuilt.nw

/**
 * The formation-stuck STATE dump (#2420) — the pieces each collaborator contributes, and the one line
 * they render into.
 *
 * ## Why a state dump rather than more events
 * `:kuilt-nw`'s characteristic failure is **silence**. A redialer parked on a poisoned
 * [NwSeam.settledEndpoints] emits nothing for the seam's whole lifetime, so a wedged device and an idle
 * one produce identical logs. No amount of *event* logging fixes that for a reader who arrives after the
 * fact: the decisive lines are minutes back in the trail, and on a bounded on-device store they may be
 * gone. What is missing is a periodic emission of the state the fabric is *currently in* — the fabric-side
 * equivalent of the Raft/warp sim harnesses' `dumpState()`.
 *
 * ## Why ONE line
 * A field capture is merged across two devices, re-ordered by write time, and read with `grep`. A
 * multi-line dump interleaves with everything else in the buffer and stops being one observation. So every
 * dump is a single `nw.loom.formation-stuck` line: `key=value` throughout, lists in brackets, no newlines.
 *
 * ## What it deliberately does NOT do
 * It reads state and renders it. It takes no lock it does not already need, changes no decision, and
 * cannot tear, dial or evict anything — so however the schedule is tuned, it can never race the formation
 * it is describing.
 */

/** One armed redialer's live campaign state, as [RedialCoordinator] holds it (#2420). */
internal data class NwRedialerState(
    val endpointId: String,
    val serviceName: String,
    /** The wait the NEXT dial will use, in milliseconds — [NwLoom.INITIAL_REDIAL_BACKOFF] doubling to the ceiling. */
    val backoffMs: Long,
    /** Dials issued on this endpoint since the campaign last started (or restarted after an un-settle). */
    val attempts: Long,
    /**
     * Whether the loop is PARKED on [NwSeam.settledEndpoints] — the terminal-looking, silent state a
     * wrongly-settled endpoint leaves a peer in (#2416/#2417). Read from the loop's own flag rather than
     * derived from the settled set, so a disagreement between the two is visible instead of smoothed over.
     */
    val parked: Boolean,
) {
    /** `<id>(name=… backoff=…ms attempts=… parked=…)` — the endpoint id first, so the field sorts and greps. */
    fun render(): String =
        "$endpointId(name=$serviceName backoff=${backoffMs}ms attempts=$attempts parked=$parked)"
}

/** [RedialCoordinator]'s read-only view for the dump (#2420). */
internal data class NwRedialSnapshot(
    val redialers: List<NwRedialerState>,
    /** Bonjour names with a pending #1709 identity-grace deferral — armed for nothing until it expires. */
    val deferrals: List<String>,
    /** Bonjour name → the first resolved peer id seen holding it; a second owner is the #2416 root condition. */
    val nameOwners: Map<String, String>,
) {
    /**
     * Whether this coordinator has seen ANY endpoint — the "we can see somebody" half of the dump trigger.
     *
     * Deliberately wider than "is [NwLoom.visiblePeers] non-empty": a Bonjour removal prunes that roster but
     * NOT the redialer (removals are often transient interface churn, and #1513's redial is what recovers a
     * flapping peer). An endpoint discovered and then pruned while its redialer keeps dialling is precisely a
     * wedge shape, and keying the trigger on the roster alone would go silent exactly there.
     */
    val sawSomebody: Boolean get() = redialers.isNotEmpty() || deferrals.isNotEmpty()
}

/** One connection the seam is tracking, as it appears in a dump (#2420). */
internal data class NwLinkState(
    val connId: String,
    /** The endpoint this link was dialled on, or [NwSeam.INBOUND_LINK] for one the far end opened. */
    val dialled: String,
    /** The remote identity the [NwHello] handshake resolved, or `null` while the link is still anonymous. */
    val resolvedPeer: String?,
    /** `live` (the registry's link for its peer), `draining` (#2425 displacement), or `unbound`. */
    val role: String,
    /** The transport's own latest verdict on the link, or `unknown` when the binding publishes none. */
    val connState: String,
    val framesIn: Long,
    val framesOut: Long,
) {
    fun render(): String =
        "$connId(dialled=$dialled peer=${resolvedPeer ?: "UNRESOLVED"} role=$role " +
            "state=$connState in=$framesIn out=$framesOut)"
}

/** [NwSeam]'s read-only view for the dump (#2420). */
internal data class NwSeamSnapshot(
    val state: String,
    val peers: List<String>,
    /** Settled endpoint ids, each with the `self`/`peer=<id>` provenance that says WHY it parks a redialer. */
    val settled: List<String>,
    val links: List<NwLinkState>,
    /**
     * The seam's LIVE roles — the base fabric pair plus whichever medium the path monitor observed
     * (#1554). `WifiDirect` present says this session is on AWDL; its absence on a formation that
     * cannot form says the peer-to-peer path never came up, which is a different problem from a peer
     * that cannot be dialled.
     */
    val roles: List<String>,
    /** The seam's live availability verdict, which is `Unknown` until a path is actually observed (#1712). */
    val availability: String,
)

/**
 * Render one formation's state as a single `key=value` line (#2420).
 *
 * Field order is the order a diagnostician asks the questions in: *who am I and what am I advertising* →
 * *did I connect* → *who can I see* → *what have I stopped dialling, and why* → *what am I still dialling*
 * → *what links exist* → *what does the radio say*. Nothing is elided when empty: an empty `settled=[]` is
 * as much a finding as a full one, and a field that disappears when empty makes two captures
 * non-comparable.
 *
 * [effectiveName] is what the OS says this device is ACTUALLY advertising, which differs from
 * [requestedName] exactly when mDNS resolved a name collision by renaming us (`… (2)`) — the fact #2416
 * could only be recovered from the *other* phone's capture. `?` means the binding publishes no advertised
 * name at all (the JVM bridge, the fakes that model none), which is not the same as "not renamed".
 */
@Suppress("LongParameterList") // one parameter per collaborator's snapshot; collapsing them hides the source of each field
internal fun renderFormationDump(
    selfId: String,
    serviceType: String,
    requestedName: String,
    effectiveName: String?,
    seam: NwSeamSnapshot,
    redial: NwRedialSnapshot,
    visible: Set<NwEndpoint>,
    path: NwPathState?,
    listener: NwListenerState,
): String = buildString {
    append("self=").append(selfId)
    append(" serviceType=").append(serviceType)
    append(" advertised=").append(requestedName)
    append("→").append(effectiveName ?: "?")
    append(" renamed=").append(effectiveName != null && effectiveName != requestedName)
    append(" state=").append(seam.state)
    append(" peers=").append(seam.peers.sorted())
    append(" visible=").append(visible.map { "${it.id}(name=${it.serviceName} resolved=${it.identityResolved})" }.sorted())
    append(" settled=").append(seam.settled)
    append(" redialers=").append(redial.redialers.map { it.render() })
    append(" deferrals=").append(redial.deferrals)
    append(" name-owners=").append(redial.nameOwners.entries.sortedBy { it.key }.map { "${it.key}→${it.value}" })
    append(" links=").append(seam.links.map { it.render() })
    append(" roles=").append(seam.roles.sorted())
    append(" availability=").append(seam.availability)
    append(" path=").append(
        path?.let {
            "${it.status}(ifaces=${it.interfaces.map(NwInterfaceType::name).sorted()} " +
                "expensive=${it.isExpensive} reason=${it.unsatisfiedReason})"
        } ?: "unknown",
    )
    append(" listener=").append(listener)
}
