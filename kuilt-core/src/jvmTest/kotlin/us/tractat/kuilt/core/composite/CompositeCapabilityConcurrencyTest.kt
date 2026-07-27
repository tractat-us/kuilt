@file:Suppress("ForbiddenImport") // deliberate: this probe exists to run on real OS threads with real preemption — a virtual/single-threaded dispatcher serialises the pumps and the writer and hides the race entirely.

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
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Real-threaded stress probe for [CompositeSeam]'s capability rollup (#1712).
 *
 * The rollup is a read-modify-write: snapshot the woven plies under the lock, fold, publish to
 * `_capability`. The publish cannot happen under the lock — emitting to a `StateFlow` can resume an
 * unconfined collector inline, and running arbitrary consumer code under a lock this class treats as
 * non-reentrant risks deadlock. Serialising every snapshot→publish pair onto one writer coroutine keeps
 * two recomputes from interleaving, and this probe covers that.
 *
 * ### But serialising the writer is not what wedged it
 * The defect this probe actually caught was a lost **trigger**, not a lost update — and no amount of writer
 * serialisation could fix it, because the request for the final transition never existed. `StateFlow`
 * conflates emissions **per collector, against that collector's own last-emitted value**, so a ply whose
 * availability round-trips `X → Y → X` while its pump is descheduled announces **nothing**. A rollup that
 * re-read each ply's live value at fold time then drained some earlier request into a fold whose joint read
 * raced ahead of the silent ply's change, published a verdict already stale for it, and left nothing to
 * correct it — a confident `Available` for a dropped path, forever, since the fold is any-Available-wins.
 *
 * The fold therefore reads only values the plies have **announced**, mirrored onto their handles before the
 * request is issued, which makes a conflated-away notification harmless by construction: a delivery is
 * suppressed exactly when the ply's value already equals the mirror. See `publishCapability`.
 *
 * ### What each layer covers
 * The mechanism itself is pinned deterministically, on every platform, by
 * [CompositeCapabilityLostTriggerTest] — it drives the interleaving instead of waiting for one. This probe
 * is the real-threaded backstop that found it and that guards the whole read-modify-write under genuine OS
 * threads and genuine preemption, which no test dispatcher reproduces.
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

        // MANY SHORT ROUNDS, not one long flood. The wedge only shows when a fold lands between the plies'
        // transitions and the silent ply's own notification is then conflated away, so
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

            // Both paths are down, so the composite MUST CONVERGE on Unavailable. A rollup that folds a ply
            // value no surviving request will ever re-read strands on Available (the fold is
            // any-Available-wins) with nothing to correct it — so this await never completes and the harness
            // fails with the stage label and thread dump. That convergence is what this probe guards.
            //
            // Deliberately NOT re-read from `.value` afterwards (#1712). `capability` is an
            // eventually-consistent rollup of the plies' ANNOUNCED values and cannot be made monotone:
            //  - the fold must be a pure function of announced values, because a fold that re-reads a ply's
            //    LIVE value strands the instant that ply's own notification is conflated away — StateFlow
            //    emits only when the value it re-reads differs from that collector's last-emitted value, so
            //    a ply round-tripping X→Y→X past a descheduled pump announces nothing. That strand is the
            //    exact defect this probe exists to catch;
            //  - and a mirrored fold necessarily combines announcements written at different instants, so any
            //    one of them may already be superseded by fold time.
            // A pump preempted between StateFlow's read and its delivery therefore lands a genuine but
            // briefly-stale announcement after a sibling's drop, and the composite correctly publishes its
            // current knowledge before correcting itself microseconds later. Re-reading `.value` here
            // demanded monotonicity the architecture cannot provide, and every alternative design that
            // restores it either strands or spins waiting on a preempted pump.
            //
            // Asserting the REASON instead keeps this check non-vacuous where a bare type assert on the
            // awaited value would be tautological: it proves the value came from the composite's own fold
            // (which normalises the reason) rather than a ply's `Unavailable("path lost")` leaking through.
            val settled = composite.capability.first { it.availability is FabricAvailability.Unavailable }
            val availability = assertIs<FabricAvailability.Unavailable>(
                settled.availability,
                "round=$round: the composite must converge on Unavailable with both paths down",
            )
            assertEquals(
                "no woven ply reports an available path",
                availability.reason,
                "round=$round: the composite must publish its OWN rollup verdict, not a ply's",
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
