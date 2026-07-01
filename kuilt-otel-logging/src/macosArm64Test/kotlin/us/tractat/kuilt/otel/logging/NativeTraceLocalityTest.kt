@file:Suppress("ForbiddenImport") // deliberate real-threading regression test: the Apple identity guard is only observable across genuine OS threads (the slot is a Kotlin/Native `@ThreadLocal`), which virtual-time `runTest` cannot provide — the production-dispatcher-in-tests ban is exempted here per the module's coroutine-determinism policy.

package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Real-threading regression test for the identity-guarded restore in the Apple/native
 * `withActiveTrace` (see `WithActiveTrace.apple.kt`):
 *
 * ```
 * finally { if (currentActiveTrace() === trace) setActiveTrace(prev) }
 * ```
 *
 * The active-trace slot is a Kotlin/Native `@ThreadLocal`, so every existing test —
 * all single-threaded under `runTest`'s virtual clock — leaves the concurrent
 * cross-thread restore path unexercised. This test stands up two real worker threads
 * and drives the exact interleaving the guard defends against: scope A's `finally`
 * runs on a thread whose slot a *different* still-active scope (B) owns. With the
 * `=== trace` identity check present, A leaves B's stamp intact; remove the check and
 * A's unconditional restore clobbers B.
 *
 * Deterministic despite the real threads: the two coroutines rendezvous through
 * [CompletableDeferred]s so the release order is fixed, not raced.
 */
class NativeTraceLocalityTest {
    private fun trace(tag: Byte) =
        ActiveTrace(ByteString(ByteArray(16) { tag }), ByteString(ByteArray(8) { tag }), sampled = true)

    @AfterTest fun clear() { setActiveTrace(null) }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun identityGuardHoldsWhenAFinallyLandsOnAnotherScopesThread() {
        val a = trace(0xA)
        val b = trace(0xB)
        val threadA = newSingleThreadContext("trace-thread-A")
        val threadB = newSingleThreadContext("trace-thread-B")
        try {
            runBlocking {
                // A signals it has hopped to threadB (freeing threadA); carries threadB's
                // slot at that instant so we can assert A's stamp did not leak across threads.
                val aHoppedToB = CompletableDeferred<ActiveTrace?>()
                // B signals it has stamped threadA (slot := b) and is now parked.
                val bStamped = CompletableDeferred<Unit>()
                // Main releases A from its park on threadB.
                val releaseAWork = CompletableDeferred<Unit>()
                // Main releases B once A's finally has run.
                val releaseB = CompletableDeferred<Unit>()
                // What threadA's slot holds after A's identity-guarded finally has executed.
                val threadASlotAfterAFinally = CompletableDeferred<ActiveTrace?>()

                // Scope A: home thread is threadA. It stamps threadA, then hops to threadB
                // for its "work" and parks there — freeing threadA so B can stamp it. When
                // released, A resumes on threadA and runs its finally there, where the slot
                // now belongs to B.
                val jobA = launch(threadA) {
                    withActiveTrace(a) {
                        // On threadA the stamp is live (threadA slot == a).
                        withContext(threadB) {
                            // Hopped to threadB. The slot is a `@ThreadLocal`, so A's stamp
                            // must NOT be visible here — threadB has its own (empty) slot.
                            aHoppedToB.complete(currentActiveTrace())
                            releaseAWork.await() // park on threadB while B stamps threadA
                        }
                        // Resumed on threadA; A's block ends and its finally runs next.
                    }
                    threadASlotAfterAFinally.complete(currentActiveTrace())
                }

                val slotSeenOnThreadBDuringHop = aHoppedToB.await()

                // Scope B: also homed on threadA. threadA is free now (A is parked on
                // threadB), so B stamps threadA (slot := b, prior == a) and parks, holding
                // b active across A's finally.
                val jobB = launch(threadA) {
                    withActiveTrace(b) {
                        bStamped.complete(Unit)
                        releaseB.await()
                    }
                }
                bStamped.await() // threadA slot == b; both A and B parked.

                // Release A: it unwinds back onto threadA and runs its finally. threadA's
                // slot is b (B's), not a, so the identity guard MUST skip the restore.
                // Without the guard, A restores its prior (null) and clobbers B's stamp.
                releaseAWork.complete(Unit)
                val slotAfterAFinally = threadASlotAfterAFinally.await()
                jobA.join()

                // Let B unwind cleanly.
                releaseB.complete(Unit)
                jobB.join()

                assertAll(
                    {
                        assertNull(
                            slotSeenOnThreadBDuringHop,
                            "A's @ThreadLocal stamp must not leak from threadA onto threadB",
                        )
                    },
                    {
                        assertEquals(
                            b,
                            slotAfterAFinally,
                            "identity guard must leave B's stamp intact when A's finally lands on B's thread",
                        )
                    },
                )
            }
        } finally {
            threadA.close()
            threadB.close()
        }
    }
}
