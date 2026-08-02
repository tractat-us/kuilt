package us.tractat.kuilt.raft

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tuning parameters for a Raft node's timing behaviour.
 *
 * **Election timeout** — the range `[electionTimeoutMin, electionTimeoutMax]`
 * from which each node picks a random deadline. When no heartbeat is received
 * within that window, the node starts an election. Randomisation reduces the
 * chance of split votes.
 *
 * **Heartbeat interval** — how often the leader sends a heartbeat (empty
 * AppendEntries) to suppress followers' election timers.
 *
 * **Constraint:** [heartbeatInterval] must be strictly less than
 * [electionTimeoutMin]. If heartbeats arrive at the election-timeout rate,
 * followers will time out spuriously. A ratio of roughly 1:3–1:10 is typical.
 *
 * **Tests** should use fast values (e.g. 20 ms / 40 ms / 5 ms) so elections
 * complete quickly without real-clock waits. The preferred test substitute is
 * `FakeRaftNode` from `:kuilt-raft-test`, which avoids real-clock delays
 * entirely — see [strictTestGuard] for misuse detection.
 *
 * @param electionTimeoutMin Lower bound of the randomised election timeout window.
 * @param electionTimeoutMax Upper bound of the randomised election timeout window.
 * @param heartbeatInterval How often the leader sends a heartbeat. Must be less
 *   than [electionTimeoutMin].
 * @param strictTestGuard When `true`, throw [IllegalStateException] at construction
 *   time if the owning [kotlinx.coroutines.CoroutineScope] contains a
 *   `kotlinx.coroutines.test.TestDispatcher`. When `false` (the default), emit a
 *   warning to stdout instead. Set to `true` in tests that want to assert the guard
 *   fires. Leave `false` in production — the guard is informational there.
 * @param expectVirtualTime Suppresses the TestDispatcher warning (see [strictTestGuard])
 *   for tests that intentionally run a real `RaftNode` under a `TestDispatcher` (both
 *   `StandardTestDispatcher` and `UnconfinedTestDispatcher` are supported). Under any
 *   `TestDispatcher`, `delay()` is virtual — the engine's election/heartbeat loops tick
 *   via the test scheduler. Has no effect in production. Default `false`: warn as usual.
 *
 *   The `:kuilt-raft` suite uses `StandardTestDispatcher` (see `RaftTestFixtures`). Set
 *   `true` in any config used by a test that constructs a real `RaftNode`. NEVER set in
 *   production code.
 * @param slowProposeThreshold Wall-time threshold for a propose round-trip (from accepted to
 *   applied). When the elapsed time exceeds this threshold, the engine logs at `warn` level.
 *   Below this threshold, the log entry is at `debug` level. Set to [Duration.ZERO] to treat
 *   every propose as slow (useful in tests that want to assert the warn path fires). Default
 *   `100ms` — appropriate for LAN clusters.
 * @param snapshotChunkCeiling Upper bound on the bytes carried in a single §7
 *   InstallSnapshot chunk. The actual chunk size is the lesser of this and the
 *   transport's [RaftTransport.maxPayloadBytes] (minus a small header budget), so
 *   a fabric with a tighter framing limit shrinks chunks automatically.
 * @param snapshotTotalCeiling Upper bound on the bytes a follower will accumulate
 *   reassembling one §7 snapshot. [snapshotChunkCeiling] bounds a single chunk; this
 *   bounds their **sum**. The sender chooses `done`, so without it a peer that keeps
 *   sending well-formed non-final chunks grows the follower's buffer without limit
 *   until the process dies (#1881). A chunk that would breach it discards the
 *   in-flight reassembly — the overshoot is never allocated — and the follower
 *   re-advertises offset 0, so an honest leader restarts the transfer.
 *
 *   The default is **64 MiB**, chosen from three directions rather than picked:
 *
 *   1. *Above it, the protocol cannot deliver anyway.* The transfer is stop-and-wait
 *      — one chunk in flight per peer, await-ack-then-next — so 64 MiB at the default
 *      16 KiB chunk is 4096 round trips: a few seconds on a 1 ms LAN, well over a
 *      minute on a 20 ms mobile link. A larger ceiling would bound nothing reachable.
 *   2. *Below it, no legitimate snapshot lives.* A snapshot is the application's state
 *      machine; the state kuilt's own consumers replicate is kilobytes to low
 *      megabytes. 64 MiB leaves three to four orders of magnitude of headroom.
 *   3. *It is sized for the smallest target, not the largest.* kuilt runs on wasmJs and
 *      iOS as well as JVM servers. Peak follower heap over an install is roughly twice
 *      the ceiling (the reassembly buffer plus the copy handed to the installer), so
 *      64 MiB means ~128 MiB — survivable in a browser heap or a mobile process, where
 *      a gigabyte is not. A server consumer that genuinely needs more raises it; that
 *      is a supportable outcome, and a loud rejection beats an out-of-memory kill.
 *
 *   The type is [Int], mirroring [snapshotChunkCeiling], which also means the ceiling
 *   can never be configured above what a single `ByteArray` can hold.
 * @param maxTermJump How far above this node's own term a frame may claim to be and still
 *   be admitted (#1897). A frame whose term exceeds `currentTerm + maxTermJump` is dropped
 *   at the dispatch boundary, before any adoption.
 *
 *   The bound is **relative**, and that is the whole point. Bounding the term's *value*
 *   against a constant `A` cannot avoid a cliff: closing it would need `T ≤ A ⟹ T + 1 ≤ A`,
 *   true only for `A = ∞`, so the one value such a filter admits is the value whose
 *   successor every peer — including its own author — refuses. Bounding the *jump* admits
 *   `currentTerm + 1` at every term, so no term has an unrepresentable successor. It is
 *   also the stronger test: an implausible term is implausible relative to what this node
 *   has seen, which is a local witness, where a constant is a guess about the deployment.
 *
 *   The default is **10 000**, sized between the two failure modes it sits between:
 *
 *   1. *Too small breaks rejoin.* A node that was away while the cluster held many
 *      elections must still be able to adopt the current term. Terms advance once per
 *      election, so 10 000 covers an absence spanning ten thousand of them — hours of
 *      pathological churn, and orders of magnitude beyond an ordinary partition, which
 *      costs a handful.
 *   2. *Too large weakens the bound.* The value is the attacker's step size: reaching the
 *      arithmetic danger zone near [Long.MAX_VALUE] costs roughly `2^63 / maxTermJump`
 *      accepted frames. At 10 000 that is ~10^15 — infeasible — where the absolute ceiling
 *      it replaces cost exactly one frame.
 *
 *   A deployment whose members routinely miss more than 10 000 elections has a liveness
 *   problem this knob would only hide; the documented route back for a node that has
 *   genuinely fallen outside the bound is a new identity plus an ordinary membership
 *   change, never a wiped disk under the same one. See `docs/raft-wedge-diagnosis-and-recovery.md`.
 *
 *   **Validated at construction to `1..2^20` (1 048 576), inclusive (#1972).** A knob whose whole
 *   job is to bound a safety property must not be settable to a value that disables it, and both
 *   ends of this one do:
 *
 *   - *At 0, elections stop.* A jump of exactly one is then refused (`1 > 0`), so no candidate's
 *     `currentTerm + 1` is ever admitted by anyone and no leader can be elected again. Below 0 the
 *     subtraction exceeds the bound for **every** frame at or above our own term, and the node
 *     goes silently deaf — nothing logs above `debug`. So 1, not 0, is the floor: it is the
 *     smallest value that preserves liveness, and it must be admitted rather than merely
 *     non-negative.
 *   - *Above 2^20, the bound stops bounding.* Large enough and the guard is vacuous, one frame
 *     carrying an arbitrary term is adopted again, and #1833's cluster-wide wedge returns in full.
 *
 *   The ceiling is derived from both directions rather than rounded:
 *
 *   1. *Attack cost.* This value is the attacker's step size, so climbing from term 0 to the
 *      storage-path ceiling `RaftEngine.MAX_PLAUSIBLE_TERM` (`2^60`) costs `2^60 / maxTermJump`
 *      accepted frames. At `2^20` that is `2^40` ≈ 1.1×10^12 — over twelve days of uninterrupted
 *      attack even at a sustained million admitted frames per second, a rate far above what any
 *      real Raft peer processes. Before #1897 the same climb cost exactly one frame.
 *   2. *Legitimate need.* Terms advance once per election, so `2^20` covers roughly 1.05 million
 *      missed elections — 100× the default above, and still some 29 hours of absence at a
 *      pathological ten elections per second. A deployment that genuinely exceeds it has the
 *      liveness problem described above, which this knob would hide rather than fix.
 *
 *   Throwing here is right, and is not in tension with `RaftEngine.onMessage`'s refusal to throw on
 *   a malformed frame (#1818): this is local, deterministic, consumer-supplied configuration
 *   evaluated once at construction, not a value a peer controls on the actor loop.
 * @param random Source of randomness for the election-timeout draw. Randomness is a
 *   dependency, like time: under virtual time a [kotlinx.coroutines.test.TestDispatcher]
 *   makes scheduling deterministic, but an unseeded RNG still injects non-determinism into
 *   the *durations* the engine waits. Production default is [Random.Default]. Tests that run
 *   under virtual time should inject a **seeded** `Random(<fixed seed>)` so every run draws
 *   identical election timeouts — making the whole engine deterministic. Multi-node tests
 *   must use **distinct seeds per node** so nodes draw different timeouts and symmetry-break
 *   into a leader; use `MultiNodeRaftSim` from `:kuilt-raft-test` which handles this
 *   automatically. NEVER seed in production: a fixed seed defeats the split-vote avoidance
 *   that timeout randomisation exists for.
 */
public data class RaftConfig(
    val electionTimeoutMin: Duration = 150.milliseconds,
    val electionTimeoutMax: Duration = 300.milliseconds,
    val heartbeatInterval: Duration = 50.milliseconds,
    val strictTestGuard: Boolean = false,
    val expectVirtualTime: Boolean = false,
    val slowProposeThreshold: Duration = 100.milliseconds,
    val snapshotChunkCeiling: Int = 16 * 1024,
    val snapshotTotalCeiling: Int = 64 * 1024 * 1024,
    val maxTermJump: Long = 10_000L,
    val random: Random = Random.Default,
) {
    init {
        require(maxTermJump in MIN_TERM_JUMP..MAX_TERM_JUMP) {
            "maxTermJump must be in $MIN_TERM_JUMP..$MAX_TERM_JUMP, was $maxTermJump. " +
                "At 0 a jump of exactly one is refused, so no candidate's currentTerm + 1 is ever " +
                "admitted and no leader can be elected again; below 0 every frame at or above this " +
                "node's own term is dropped and the node goes silently deaf. Above " +
                "$MAX_TERM_JUMP the bound stops pricing the attack it exists to price (#1972)."
        }
    }

    private companion object {
        /** Smallest jump that still admits `currentTerm + 1`, and so the smallest that keeps elections possible. */
        const val MIN_TERM_JUMP = 1L

        /** `2^20`. Derived on the [maxTermJump] KDoc from the attack cost and the largest honest absence. */
        const val MAX_TERM_JUMP = 1L shl 20
    }
}
