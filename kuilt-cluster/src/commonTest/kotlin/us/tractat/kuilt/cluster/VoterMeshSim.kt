@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * # VoterMeshSim — deterministic virtual-time harness for the real voter-mesh seam stack
 *
 * The fixture to reach for when a test needs to drive the **real** [assembleVoterMesh] — the
 * production K_M voter-mesh assembly (accept-pumps, formation, redial supervisors, `ownsSeams`
 * teardown) — under virtual time, without any real sockets. It stands up an N-voter [VoterMesh]
 * over an [InMemoryVoterFabric] and exposes bounded await helpers that fail fast with a state dump.
 *
 * It drives the same code path a WebSocket deployment does; it does **not** re-implement a friendlier
 * mesh. If formation or a commit does not converge under virtual time that is a real finding — the
 * bounded awaits surface it as a fast, diagnosable failure ([dumpState]), never a hang.
 *
 * ## Determinism contract (this repo's multi-node discipline)
 *
 * - Runs under `runTest(StandardTestDispatcher(), timeout = 5.seconds)` — see [voterMeshSimTest].
 * - One **seeded** [RaftConfig] shared across voters (matching the real path — [assembleVoterMesh]
 *   takes one config); `expectVirtualTime = true` suppresses the real-dispatcher guard. Voters draw
 *   distinct election timeouts from the shared seeded [Random] as the sequence advances, so a leader
 *   wins (symmetry-breaking) deterministically.
 * - The mesh's pumps, supervisors, and nodes run on a scope rooted at [TestScope.backgroundScope],
 *   so the infinite election/heartbeat loops cancel cleanly at teardown; [close] additionally
 *   gracefully closes the internally-owned `hubMesh` seams (their read loops live on their own
 *   `SupervisorJob` scopes, so cancelling the mesh scope alone would leak them —
 *   `UncompletedCoroutinesError`). [voterMeshSimTest] calls [close] for you.
 * - Every await advances virtual time in bounded 1 ms steps ([tick]) under a [withTimeout] — never
 *   `advanceUntilIdle()` (the election/heartbeat timers re-arm forever).
 *
 * ## Setup ceremony
 *
 * ```kotlin
 * @Test
 * fun formsAndCommits() = voterMeshSimTest(n = 3) { sim ->
 *     sim.awaitLeader()
 *     sim.proposeOnLeader("hello".encodeToByteArray())
 *     sim.awaitCommit("hello".encodeToByteArray())
 * }
 * ```
 */
internal class VoterMeshSim internal constructor(
    /** The live [VoterMesh] under test — built by the real [assembleVoterMesh]. */
    val mesh: VoterMesh,
    private val scope: TestScope,
    nodeScope: CoroutineScope,
    /** The in-memory fabric the mesh runs over — reused by reconnection harnesses that extend it. */
    val fabric: InMemoryVoterFabric,
    /** The voter ids, in the caller's order. */
    val voterIds: List<NodeId>,
) {
    // Per-voter applied commands, folded from each node's replaying committed stream. Polled (by
    // .value) in awaitCommit rather than collecting commitIndex.first/filter — matches a specific
    // command, robust to subscribing after it already committed (committedFrom replays from index 1).
    private val applied: Map<NodeId, MutableStateFlow<List<ByteArray>>> =
        voterIds.associateWith { MutableStateFlow(emptyList()) }

    init {
        mesh.voterNodes.forEach { (id, node) ->
            // Collect on nodeScope (backgroundScope), NOT the test scope — a hot collect on the test
            // scope would never complete and trip UncompletedCoroutinesError at teardown.
            nodeScope.launch {
                node.committedFrom(1).collect { committed ->
                    when (committed) {
                        is Committed.Entry -> applied.getValue(id).update { it + committed.entry.command }
                        is Committed.Install -> applied.getValue(id).value = emptyList()
                        // Raft bookkeeping: no application command, so applied state is unchanged.
                        is Committed.Internal -> Unit
                    }
                }
            }
        }
    }

    // ── Bounded await helpers — build cluster-state assertions on these ────────────────────────────
    // Each advances virtual time in bounded 1 ms steps and fails fast with a dumpState() on timeout.

    /** Suspend until some voter holds [RaftRole.Leader]; return it, or fail fast with a state dump. */
    suspend fun awaitLeader(within: Duration = DEFAULT_AWAIT): RaftNode =
        awaitOrDump("awaitLeader", within) { pollUntil { leader() } }

    /** Suspend until [command] is committed on every voter in [on]; fail fast with a state dump otherwise. */
    suspend fun awaitCommit(
        command: ByteArray,
        on: Collection<NodeId> = voterIds,
        within: Duration = DEFAULT_AWAIT,
    ) {
        awaitOrDump("awaitCommit on $on", within) {
            pollUntil { true.takeIf { on.all { id -> hasCommitted(id, command) } } }
        }
    }

    /** Suspend until [peer] is present in [seam]'s roster; fail fast with a state dump on timeout. */
    suspend fun awaitPeer(seam: Seam, peer: PeerId, within: Duration = DEFAULT_AWAIT) {
        awaitOrDump("awaitPeer($peer)", within) { pollUntil { true.takeIf { peer in seam.peers.value } } }
    }

    /** Suspend until [peer] is absent from [seam]'s roster; fail fast with a state dump on timeout. */
    suspend fun awaitNoPeer(seam: Seam, peer: PeerId, within: Duration = DEFAULT_AWAIT) {
        awaitOrDump("awaitNoPeer($peer)", within) { pollUntil { true.takeIf { peer !in seam.peers.value } } }
    }

    /**
     * Propose [command] on the current leader, re-acquiring and retrying on [NotLeaderException]
     * (a transient can arise if leadership moved between [awaitLeader] and here). Returns the
     * committed [LogEntry].
     */
    suspend fun proposeOnLeader(command: ByteArray, within: Duration = DEFAULT_AWAIT): LogEntry =
        awaitOrDump("proposeOnLeader", within) {
            while (true) {
                val l = leader()
                if (l == null) { tick(); continue }
                try {
                    return@awaitOrDump l.propose(command)
                } catch (_: NotLeaderException) {
                    tick()
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    // ── Inspection ────────────────────────────────────────────────────────────────────────────────

    /** The current leader voter, or `null` if none is known. */
    fun leader(): RaftNode? = mesh.voterNodes.values.firstOrNull { it.role.value is RaftRole.Leader }

    /** The commands voter [id] has applied from its committed stream, in order. */
    fun appliedCommands(id: NodeId): List<ByteArray> = applied.getValue(id).value

    /** Whether voter [id] has committed [command]. */
    fun hasCommitted(id: NodeId, command: ByteArray): Boolean =
        appliedCommands(id).any { it.contentEquals(command) }

    /** Voter [id]'s inter-server seam (its view of the other voters). */
    fun seamOf(id: NodeId): Seam = requireNotNull(mesh.voterSeams) { "mesh has no voterSeams" }.getValue(id)

    /**
     * Cancel the mesh (pumps + supervisors + nodes) and gracefully close its internally-owned seams.
     * Called for you by [voterMeshSimTest]; call directly only in a hand-wired `runTest` body.
     */
    suspend fun close() {
        withContext(NonCancellable) { mesh.close() }
    }

    /** Per-voter role/leader/commit/peers — the body of the [AssertionError] a failed await throws. */
    fun dumpState(reason: String): String = buildString {
        appendLine("VoterMeshSim state dump — $reason")
        voterIds.forEach { id ->
            val node = mesh.voterNodes[id]
            val peers = mesh.voterSeams?.get(id)?.peers?.value
            appendLine(
                "  $id: role=${node?.role?.value?.let { it::class.simpleName }} " +
                    "leader=${node?.leader?.value} commitIndex=${node?.commitIndex?.value} " +
                    "applied=${appliedCommands(id).size} peers=$peers",
            )
        }
    }

    // ── Private virtual-time internals ──────────────────────────────────────────────────────────────

    /** One poll step: advance virtual time by 1 ms (driving the engine one step). NEVER advanceUntilIdle. */
    private suspend fun tick() = delay(1)

    private suspend fun <T : Any> pollUntil(probe: () -> T?): T {
        while (true) {
            probe()?.let { return it }
            tick()
        }
    }

    private suspend fun <T> awaitOrDump(what: String, within: Duration, block: suspend () -> T): T =
        try {
            withTimeout(within) { block() }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(dumpState("$what timed out after $within"))
        }

    internal companion object {
        /** Default bound on a single await — tight enough to surface a hang fast under the 5 s test timeout. */
        val DEFAULT_AWAIT: Duration = 2.seconds
    }
}

/**
 * Fast, seeded Raft timing config for [VoterMeshSim] — elections fire in tens of virtual ms. One
 * config is shared across every voter (matching [assembleVoterMesh]'s single-config real path); the
 * seeded [Random] hands each voter a distinct election-timeout draw as the sequence advances, so a
 * leader wins deterministically. `expectVirtualTime = true` suppresses the real-dispatcher guard.
 *
 * A fresh [RaftConfig] (with a fresh [Random]) is built per call so tests don't share RNG state.
 *
 * **Never use in production** — fast timings are meaningless on real networks and `expectVirtualTime`
 * suppresses a safety guard.
 */
internal fun voterMeshSimConfig(seed: Long = VOTER_MESH_SIM_SEED): RaftConfig = RaftConfig(
    electionTimeoutMin = 50.milliseconds,
    electionTimeoutMax = 150.milliseconds,
    heartbeatInterval = 10.milliseconds,
    expectVirtualTime = true,
    random = Random(seed),
)

/** Stable seed for [VoterMeshSim]'s election RNG and mesh-nonce RNG. Change to explore other orderings. */
internal const val VOTER_MESH_SIM_SEED: Long = 794L

/**
 * Build an N-voter [VoterMeshSim] by driving the real [assembleVoterMesh] over an
 * [InMemoryVoterFabric], under virtual time. The mesh's lifecycle scope is rooted at [nodeScope]
 * (pass [TestScope.backgroundScope]) so its infinite loops cancel at teardown.
 *
 * Prefer [voterMeshSimTest]; call this directly only when hand-wiring a `runTest` body.
 */
internal suspend fun TestScope.buildVoterMeshSim(
    voterIds: List<NodeId>,
    nodeScope: CoroutineScope,
    seed: Long = VOTER_MESH_SIM_SEED,
    fabric: InMemoryVoterFabric = InMemoryVoterFabric(voterIds),
): VoterMeshSim {
    // A dispatcher derived from the test scheduler (NOT a production dispatcher) for the mesh read
    // loops — everything advances on the one virtual clock runTest drives.
    val dispatcher = StandardTestDispatcher(testScheduler)
    // The assembly's receiver scope: a child of nodeScope so meshScope (and the pumps/supervisors/
    // nodes it hosts) cancel when backgroundScope is cancelled at teardown.
    val hostScope = CoroutineScope(nodeScope.coroutineContext + Job(nodeScope.coroutineContext[Job]))
    val mesh = hostScope.assembleVoterMesh(
        voters = voterIds,
        sourceOf = fabric::sourceOf,
        dial = fabric::dial,
        dispatcher = dispatcher,
        raftConfig = voterMeshSimConfig(seed),
        random = Random(seed xor MESH_NONCE_SEED_MIX),
        handshakeTimeout = 2.seconds,
        dialTimeout = 2.seconds,
        formationTimeout = 5.seconds,
        backoffBase = 20.milliseconds,
        backoffCap = 200.milliseconds,
    )
    return VoterMeshSim(mesh = mesh, scope = this, nodeScope = nodeScope, fabric = fabric, voterIds = voterIds)
}

/** Distinct seed offset for the mesh-nonce/backoff RNG so it never mirrors the election RNG sequence. */
private const val MESH_NONCE_SEED_MIX: Long = 0x5EED

/**
 * Run [body] against an N-voter [VoterMeshSim] under `runTest(StandardTestDispatcher(), timeout = 5s)`
 * — the canonical harness for a voter-mesh consensus test. Closes the mesh at teardown so the owned
 * `hubMesh` seams' read loops don't leak.
 *
 * @param n voter count (default 3 — minimum for one-fault tolerance; use an odd count for clean quorum).
 * @param seed election/nonce RNG seed. @param timeout test timeout (default 5 s — keep it tight).
 * @param fabricFactory Builds the [InMemoryVoterFabric] the mesh runs over, given the voter ids and the
 *   node scope ([TestScope.backgroundScope]). Defaults to the non-severable [InMemoryVoterFabric]; a
 *   reconnection test passes a [SeverableInMemoryVoterFabric] factory (it needs the scope to arm its
 *   virtual reaper). The built fabric is reachable as [VoterMeshSim.fabric] for `sever`/`restore`.
 */
internal fun voterMeshSimTest(
    n: Int = 3,
    seed: Long = VOTER_MESH_SIM_SEED,
    timeout: Duration = 5.seconds,
    fabricFactory: (List<NodeId>, CoroutineScope) -> InMemoryVoterFabric = { ids, _ -> InMemoryVoterFabric(ids) },
    body: suspend TestScope.(VoterMeshSim) -> Unit,
): TestResult = runTest(StandardTestDispatcher(), timeout = timeout) {
    val voterIds = (1..n).map { NodeId("v$it") }
    val sim = buildVoterMeshSim(
        voterIds = voterIds,
        nodeScope = backgroundScope,
        seed = seed,
        fabric = fabricFactory(voterIds, backgroundScope),
    )
    try {
        body(sim)
    } finally {
        sim.close()
    }
}
