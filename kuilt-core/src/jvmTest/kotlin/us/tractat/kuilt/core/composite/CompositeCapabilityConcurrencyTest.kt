@file:Suppress("ForbiddenImport") // deliberate: the lost-update race only manifests under a real multi-threaded dispatcher — a virtual/single-threaded one serialises the recomputes and hides it entirely.

package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.core.runConcurrencyStress
import us.tractat.kuilt.test.FakeSeam
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Real-threaded stress probe for [CompositeSeam]'s capability rollup (#1712).
 *
 * `recomputeCapability` is a read-modify-write: snapshot the woven plies under the lock, fold, publish to
 * `_capability`. The publish cannot happen under the lock — emitting to a `StateFlow` can resume an
 * unconfined collector inline, and running arbitrary consumer code under a lock this class treats as
 * non-reentrant risks deadlock — so without serialisation two concurrent recomputes can interleave and let
 * a **stale** publish land last. The composite would then advertise a confident `Available` for a path that
 * had already dropped, and nothing would correct it until some later emission happened to fire. That is the
 * #1712 defect one layer up, so it must be structurally impossible, not merely unlikely.
 *
 * The fix is a single writer coroutine draining a conflated channel. This probe hammers a composite with
 * concurrent capability transitions from several real threads, then settles on one known final value and
 * asserts the composite converges to it. Against the un-serialised version a stale publish wins the last
 * write and the convergence await never completes, so the harness fails with a stage label and thread dump.
 *
 * **Not reproducible under `runTest`.** `recomputeCapability` contains no suspension point between snapshot
 * and publish, so a single-threaded test dispatcher runs the whole read-modify-write atomically with respect
 * to coroutine scheduling — the interleaving simply cannot occur. Only genuine OS threads expose it, which
 * is why this lives here rather than as a deterministic unit test.
 *
 * **JVM-hosted, `-Pconcurrency.stress.tests`-gated** (matches the other seam probes): excluded from the
 * normal `jvmTest` run and executed on the dedicated concurrency-stress CI job.
 */
class CompositeCapabilityConcurrencyTest {

    @Test
    fun aStaleRecomputeNeverWinsTheLastWrite() = runConcurrencyStress { stage ->
        // TWO plies, deliberately. Each ply gets its own `plyScope`, so each capability pump is a SEPARATE
        // collector coroutine — that is where recomputes genuinely overlap. A single ply cannot expose this:
        // its one pump serialises its own recomputes (verified — a one-ply probe passes against the racy
        // implementation).
        val plyA = FlippableLoom("a")
        val plyB = FlippableLoom("b")
        val composite = CompositeLoom(
            plies = listOf(PlyId("a") to plyA, PlyId("b") to plyB),
            dispatcher = Dispatchers.Default,
        ).host(Pattern("host"))

        // MANY SHORT ROUNDS, not one long flood. The bug only shows when a stale publish lands LAST, so
        // every round must end in a quiescent check — a single flood offers exactly one such opportunity
        // and is far too insensitive (also verified). Each round drives both plies down FROM SEPARATE
        // THREADS, so the two pumps recompute concurrently across the transition that matters.
        val rounds = 4000
        repeat(rounds) { round ->
            stage.at("round=$round up") {
                "round=$round composite=${composite.capability.value.availability} " +
                    "a=${plyA.seam.capability.value.availability} b=${plyB.seam.capability.value.availability}"
            }
            plyA.seam.publish(FabricAvailability.Available)
            plyB.seam.publish(FabricAvailability.Available)
            composite.capability.first { it.availability is FabricAvailability.Available }

            stage.at("round=$round converge-down") {
                "round=$round composite=${composite.capability.value.availability} (expected Unavailable) " +
                    "a=${plyA.seam.capability.value.availability} b=${plyB.seam.capability.value.availability}"
            }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val drops = listOf(plyA, plyB).map { ply ->
                    async(Dispatchers.Default) {
                        ready.await()
                        ply.seam.publish(FabricAvailability.Unavailable("path lost"))
                    }
                }
                ready.complete(Unit)
                awaitAll(*drops.toTypedArray())
            }

            // Both paths are down and every writer has stopped, so the composite MUST settle on
            // Unavailable. A stale publish landing after the correct one strands it on Available (the fold
            // is any-Available-wins) with no further emission to correct it — so this await hangs and the
            // harness fails with the stage label and thread dump.
            //
            // Asserted by TYPE, not value: the fold normalises the reason (a composite reports its own
            // rollup reason, not the ply's), so an `==` against a ply's reason could never match.
            composite.capability.first { it.availability is FabricAvailability.Unavailable }
            assertIs<FabricAvailability.Unavailable>(
                composite.capability.value.availability,
                "round=$round: composite settled on a STALE availability — a recompute published out of order",
            )
        }
        composite.close(CloseReason.Normal)
    }

    /** A [Loom] weaving the one [FlippableSeam] the probe drives. */
    private class FlippableLoom(name: String) : Loom {
        val seam = FlippableSeam(name)
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam
        override fun capability() = TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    /** A permanently-[us.tractat.kuilt.core.SeamState.Woven] seam whose live capability the probe flips. */
    private class FlippableSeam(
        name: String,
        private val delegate: FakeSeam = FakeSeam(selfId = PeerId("flip-ply-$name")),
    ) : Seam by delegate {
        private val _capability =
            MutableStateFlow(TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available))
        override val capability: StateFlow<TransportCapability> = _capability.asStateFlow()

        fun publish(availability: FabricAvailability) {
            _capability.value = TransportCapability(_capability.value.roles, availability)
        }
    }
}
