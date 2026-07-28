@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression for #1855: the #1833 term plausibility bound must also cover **init-restore**.
 *
 * #1846 put `MAX_PLAUSIBLE_TERM` at the `onMessage` wire boundary, so an implausible term arriving in
 * a frame is dropped. It deliberately left `state.currentTerm = storage.term()` unbounded, on the
 * reading that a poisoned durable term costs only that one node's liveness while the cluster keeps
 * electing. Measurement says otherwise on both halves of that reading:
 *
 * - **The overflow is still reachable.** A one-voter cluster — the shape `kuilt-game`'s appoint-the-host
 *   bootstrap starts in — restores `Long.MAX_VALUE`, wins its own election, computes `currentTerm + 1`,
 *   and **persists `Long.MIN_VALUE`**. So the engine itself drives the durable term *backwards*, against
 *   [RaftStorage.term]'s own "increases monotonically; it is never safe to decrease it" contract, and a
 *   node whose term went backwards has forgotten every vote it cast — the §5.2 double-vote exposure that
 *   #1855 explicitly refused to trade a liveness bug for.
 * - **It is not only a migration concern.** kuilt ships no durable [RaftStorage] at all
 *   ([InMemoryRaftStorage] is the only production implementation), so every persistent one is consumer
 *   code, and [us.tractat.kuilt.conformance.RaftStorageConformanceSuite] constrains `term()` to nothing
 *   but "starts at 0" and "round-trips 7". A conforming third-party store can return garbage from a bad
 *   column, a sign-extended `Int`, or a torn read — no pre-fix binary and no attacker required.
 *
 * Disposition is **refuse to start**, not clamp: rewriting persisted consensus state on load lets a node
 * vote twice in a term it forgot. Throwing here is not the #1818 failure mode (a `require` inside the
 * actor loop turning a hostile frame into node death) — this input is the consumer's *own* storage, read
 * once at startup before the actor exists, and no remote party can reach it.
 */
internal class TermRestoreBoundTest {

    /** Mirrors `RaftEngine.MAX_PLAUSIBLE_TERM`; a restored term at or below it must still be honoured. */
    private val maxPlausibleTerm = 1L shl 60

    @Test
    fun implausiblyHighDurableTerm_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(Long.MAX_VALUE, null)

        val failure = awaitRestoreFailure(storage)

        assertAll(
            { assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure") },
            {
                assertTrue(
                    failure?.message.orEmpty().contains(Long.MAX_VALUE.toString()),
                    "the diagnostic must name the offending term: ${failure?.message}",
                )
            },
        )
    }

    @Test
    fun negativeDurableTerm_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(-1L, null)

        val failure = awaitRestoreFailure(storage)

        assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure")
    }

    /**
     * The arithmetic half, pinned independently of the disposition: whatever a poisoned restore does, it
     * must never write a *negative* term back to storage.
     *
     * This is #1833's own failure — `currentTerm + 1` wrapping to `Long.MIN_VALUE` — reached through the
     * path #1846 excluded, and it is what makes "the node is merely isolated" wrong. Deliberately asserts
     * on durable state rather than on the exception, so it keeps its teeth if the refusal mechanism is
     * ever changed.
     */
    @Test
    fun poisonedDurableTerm_neverWrapsToANegativePersistedTerm() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(Long.MAX_VALUE, null)

        awaitRestoreFailure(storage)

        val persisted = storage.term()
        assertTrue(
            persisted >= 0L,
            "a poisoned restore must not drive the durable term backwards through the `+ 1` wrap: $persisted",
        )
    }

    /**
     * The other direction: the bound must not reject a legitimate restore. A term far above anything a
     * real deployment reaches — but inside the ceiling — must still start, restore verbatim, and elect.
     */
    @Test
    fun plausibleDurableTerm_restoresAndElectsNormally() = raftRunTest {
        val storage = InMemoryRaftStorage()
        val restored = 1L shl 40
        storage.saveTermAndVotedFor(restored, null)

        val h = singleVoterNode(backgroundScope, storage)
        h.awaitCommit(1L)

        val persisted = storage.term()
        val role = h.node.role.value
        assertAll(
            { assertEquals(restored + 1L, persisted, "a plausible term restores verbatim and elects once") },
            { assertTrue(role is RaftRole.Leader, "the single voter must still reach leadership") },
        )
    }

    /**
     * The ceiling is **inclusive**, matching `onMessage`'s own `> MAX_PLAUSIBLE_TERM` test. Keeping the two
     * bounds identical is the point: a durable term can only arrive from a wire term the boundary already
     * admitted, or from a self-increment off one, so a restore rule stricter than the wire rule would
     * refuse to start a node that the wire rule had just told it was fine to become.
     *
     * This stays correct if #1886 makes the *wire* bound exclusive: a durable `2^60` would then only be
     * reachable by a self-increment off `2^60 - 1`, which is exactly the case a restore must still accept.
     */
    @Test
    fun durableTermExactlyAtTheCeiling_stillStarts() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(maxPlausibleTerm, null)

        val failure = awaitRestoreFailure(storage)

        assertNull(failure, "a term exactly at the ceiling is admissible, matching the wire bound")
    }

    /**
     * Starts a node over [storage] in a scope whose failures are captured rather than propagated, and
     * returns the throwable the init-restore surfaced — or `null` if it started cleanly.
     *
     * The restore runs in a coroutine (`storage.term()` suspends, so it cannot happen in the `raftNode`
     * call itself), which is why the failure is observed through a [CoroutineExceptionHandler] on a
     * [SupervisorJob] rather than as a thrown constructor exception. `backgroundScope` is deliberately not
     * used: it reports uncaught exceptions as test failures, which would make the expected failure
     * unassertable.
     *
     * Bounded by [withTimeoutOrNull] on virtual time, so a node that *does* start returns `null` promptly
     * instead of hanging on its perpetually re-arming election timer.
     */
    private suspend fun TestScope.awaitRestoreFailure(storage: InMemoryRaftStorage): Throwable? {
        val caught = CompletableDeferred<Throwable>()
        val nodeScope = CoroutineScope(
            StandardTestDispatcher(testScheduler) +
                SupervisorJob() +
                CoroutineExceptionHandler { _, e -> caught.complete(e) },
        )
        try {
            singleVoterNode(nodeScope, storage)
            return withTimeoutOrNull(2.seconds) { caught.await() }
        } finally {
            nodeScope.cancel()
        }
    }
}
