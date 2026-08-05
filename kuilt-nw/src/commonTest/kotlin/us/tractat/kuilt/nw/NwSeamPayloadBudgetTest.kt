package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * [NwSeam] enforces its frame ceiling non-destructively (#2069).
 *
 * The fabric enforces a ceiling — [encodeFrame] throws `FrameTooLargeException` past [NwSeam]'s
 * `maxFrameBytes` — and both send paths used to turn a mis-sized payload into damage out of all
 * proportion to it:
 *
 *  - `sendTo` encoded *inside* `runCatchingCancellable`, so the oversize throw landed in the
 *    `onFailure` that exists for a **dead link** — **evicting a healthy peer** — and was then
 *    swallowed, leaving the caller told its send had been accepted. This is the `MeshSeam` defect
 *    of #2113, in a second fabric.
 *  - `broadcast` encoded *outside* the guard, so the throw escaped to the caller — breaking
 *    `broadcast`'s best-effort "drop, don't report" contract, whose most common caller is a
 *    timer-driven replication loop a throw kills outright.
 *
 * The TCK ([us.tractat.kuilt.conformance.SeamConformanceSuite]) cannot reach any of this: its
 * budget cases are selected by a non-null `maxPayloadBytes`, which this fabric deliberately does
 * not yet report (#2134), and proving a hidden 16 MiB bound would need a 16 MiB payload anyway.
 * That is what `NwSeam(maxFrameBytes = …)` is for — the same number reaches both edges of the
 * wire, so a [CEILING]-byte ceiling exercises exactly the production paths at a cost of bytes.
 */
class NwSeamPayloadBudgetTest {

    /** Small enough that an over-budget payload costs bytes; the production default is 16 MiB. */
    private val ceiling = CEILING

    /**
     * The seam enforces a ceiling and deliberately does **not** publish it — see the send-section
     * comment in [NwSeam]. Publishing is a promise that a payload of that size will cross, and this
     * fabric's receive path drops bytes under the multi-chunk burst such a payload arrives as
     * (#2134). Enforcing a bound and promising one are different claims.
     *
     * Pinned so that publishing becomes a deliberate act with this test as its checklist, rather
     * than something that drifts in: flipping it on while #2134 is open puts the fabric under
     * `SeamConformanceSuite.payloadOfExactlyTheBudgetIsCarried`, which then wedges roughly one run
     * in five — a flake in `ci-required` whose cause is two layers away from the change.
     */
    @Test
    fun theSeamDoesNotYetPromiseTheCeilingItEnforces() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val (a, _) = budgetedPair()

            assertNull(
                a.seam.maxPayloadBytes,
                "publishing a budget is a promise to CARRY it; until #2134 makes the receive path " +
                    "lossless this fabric can only promise to REFUSE above it",
            )
        }

    @Test
    fun anOverBudgetAddressedSendIsRefusedWithoutEvictingTheRecipient() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val (a, b) = budgetedPair()

            // Captured rather than `assertFailsWith`-ed, so the two defects red INDEPENDENTLY: the
            // eviction below is what the seam did *before* it declined to report anything, and an
            // assertion that short-circuited on the missing throw could never see it.
            val outcome = runCatchingCancellable { a.seam.sendTo(b.peerId, ByteArray(ceiling + 1)) }
            testScheduler.runCurrent()

            assertAll(
                {
                    assertContains(
                        a.seam.peers.value,
                        b.peerId,
                        "a payload the caller got wrong is not a dead link — the recipient must survive it",
                    )
                },
                { assertIs<SeamState.Woven>(a.seam.state.value, "nothing reached the wire, so nothing tore") },
                {
                    val refusal = assertIs<PayloadTooLarge>(
                        outcome.exceptionOrNull(),
                        "an over-budget addressed send must be REFUSED, and with PayloadTooLarge — " +
                            "reporting is the whole difference between sendTo and broadcast, and the " +
                            "fabric's own frame error names a limit the caller could not read",
                    )
                    assertAll(
                        { assertEquals(ceiling + 1, refusal.payloadBytes) },
                        { assertEquals(ceiling, refusal.budgetBytes, "the refusal names the ceiling the seam enforces") },
                        {
                            assertEquals(
                                0,
                                refusal.reservedBytes,
                                "the 4-byte length prefix rides ON TOP of the payload ceiling, not carved out of it",
                            )
                        },
                    )
                },
            )
        }

    @Test
    fun anOverBudgetBroadcastIsDroppedNotReported() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val (a, b) = budgetedPair()

            // Best-effort by contract: dropped, never thrown. A throw here kills the timer-driven
            // replication loops that are broadcast's most common caller.
            a.seam.broadcast(ByteArray(ceiling + 1))
            testScheduler.runCurrent()

            assertAll(
                { assertContains(a.seam.peers.value, b.peerId, "a dropped broadcast evicts nobody") },
                { assertIs<SeamState.Woven>(a.seam.state.value) },
                { assertEquals(0, b.received.size, "the over-budget frame must not reach the wire") },
            )

            // ...and the seam still works afterwards: the next in-budget frame crosses whole.
            val inBudget = ByteArray(ceiling) { 7 }
            a.seam.broadcast(inBudget)
            assertTrueOrDump(pumpUntil { b.received.isNotEmpty() }, b)
            assertContentEquals(
                inBudget,
                b.received.single().toByteArray(),
                "the dropped frame must not have been queued ahead of this one",
            )
        }

    @Test
    fun aPayloadOfExactlyTheBudgetCrossesWhole() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val (a, b) = budgetedPair()

            // Non-uniform fill, so a truncate-and-zero-pad cannot pass the content check.
            val atBudget = ByteArray(ceiling) { (it % PAYLOAD_FILL_MODULUS).toByte() }
            a.seam.sendTo(b.peerId, atBudget)
            assertTrueOrDump(pumpUntil { b.received.isNotEmpty() }, b)

            assertContentEquals(
                atBudget,
                b.received.single().toByteArray(),
                "a payload of exactly the ceiling crosses whole — the bound refuses ABOVE it, not at it",
            )
        }

    // ---------------------------------------------------------------- harness

    private class Device(val peerId: PeerId, val api: FakeNwApi, val seam: NwSeam) {
        val received = mutableListOf<Swatch>()
    }

    /**
     * A converged 2-node mesh over one [FakeNwRadio], both seams built with a [ceiling]-byte frame
     * ceiling — the same number [NwSeam] threads to [encodeFrame] and to each connection's
     * [NwFramer] in production, just small enough to test with.
     */
    private fun TestScope.budgetedPair(): Pair<Device, Device> {
        val radio = FakeNwRadio()
        val devices = (0 until 2).map { i ->
            val api = FakeNwApi(radio, deviceId = "dev-$i", serviceName = "svc-$i")
            Device(
                PeerId("peer-$i"),
                api,
                // Each seam owns a child scope with its OWN Job, so one seam's teardown cannot
                // cancel the other's loops or the assertion collectors.
                NwSeam(
                    selfId = PeerId("peer-$i"),
                    api = api,
                    scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])),
                    random = Random(i.toLong()),
                    maxFrameBytes = ceiling,
                ),
            )
        }
        // Single-collection (ADR-034): collect each seam's `incoming` exactly once, from
        // backgroundScope rather than the seam's own scope.
        for (d in devices) {
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                d.seam.incoming.collect { d.received += it }
            }
        }
        testScheduler.runCurrent()
        for (i in devices.indices) {
            for (j in devices.indices) {
                if (i != j) {
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        devices[i].api.connect(NwEndpoint(id = "ep-dev-$j", serviceName = "svc-$j"))
                    }
                }
            }
        }
        check(pumpUntil { devices.all { it.seam.peers.value.size == 2 } }) {
            "mesh did not converge: ${devices.map { it.peerId to it.seam.peers.value }}"
        }
        return devices[0] to devices[1]
    }

    private companion object {
        /** The test ceiling. Bytes, not megabytes — the point is the mechanism, not the number. */
        const val CEILING = 512

        /** Non-uniform fill for the at-budget payload, so truncation cannot pass as a zero-fill. */
        const val PAYLOAD_FILL_MODULUS = 251

        /** Bounded pump: run current-virtual-time tasks until [cond] or the cap. Never hangs. */
        fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
            repeat(maxPumps) {
                if (cond()) return true
                testScheduler.runCurrent()
            }
            return cond()
        }

        /** Fail with the receiver's actual frames rather than a bare `false`. */
        fun assertTrueOrDump(converged: Boolean, receiver: Device) {
            if (!converged) {
                throw AssertionError(
                    "expected a frame on ${receiver.peerId.value}; got ${receiver.received.size}: " +
                        receiver.received.map { it.payloadSize },
                )
            }
        }
    }
}
