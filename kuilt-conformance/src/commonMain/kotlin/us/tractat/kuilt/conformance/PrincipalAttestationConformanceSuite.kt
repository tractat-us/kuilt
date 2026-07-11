package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Reusable contract test suite for **hub seams that carry a host-verified identity** — any
 * [Seam] that is also a [PrincipalRoster].
 *
 * When a fabric admits a peer over a connection stamped with a verified identity (via
 * [us.tractat.kuilt.core.withPrincipal]), the seam must publish that identity on its
 * [PrincipalRoster.attestedPrincipals] roster, keyed by the peer it was verified against. This
 * suite pins that obligation so a future hub seam that silently drops the identity **fails the
 * suite** instead of shipping an empty roster (the exact hole a mux-hub seam once shipped: it
 * satisfied `: Seam` without carrying attestation, and every consumer reads
 * `as? PrincipalRoster ?: EMPTY`, so a dropped principal is a silent empty map, not a failure).
 *
 * Subclass and implement [newHarness] to bind any principal-bearing seam under test. Both reference
 * hub seams — the flat mesh ([us.tractat.kuilt.core.fabric.Mesh]) and the per-room mux hub
 * ([us.tractat.kuilt.core.RoomHubSeam]) — subclass it; the next hub seam that forgets the principal
 * is caught here.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`) so every
 * principal-bearing seam adapter can subclass it from its own test source set — the same
 * "one suite, every impl passes it" pattern as [SeamConformanceSuite] and
 * [RoomFanoutIsolationConformanceSuite].
 *
 * The invariants pinned:
 *  - **Attested carriage** — a peer admitted with a principal is reported for that peer
 *    ([attestedPrincipalIsReportedForAdmittedPeer]).
 *  - **Per-peer keying** — distinct peers' principals are keyed independently, and an unattested
 *    peer is simply absent, not `null`-valued ([distinctPrincipalsAreKeyedByPeer],
 *    [unattestedPeerIsAbsentFromRoster]).
 *  - **Reconnect refresh** — a peer that reconnects with a new principal supersedes the old
 *    ([rosterUpdatesPrincipalOnReconnect]).
 *  - **Drop / close cleanup** — a dropped peer leaves the roster; a closed seam empties it
 *    ([droppedPeerLeavesTheRoster], [closingTheSeamEmptiesTheRoster]).
 *
 * **Virtual time convention:** every test runs under [StandardTestDispatcher] with a tight 5 s
 * timeout, and awaits admission/removal on observable roster state (`attestedPrincipals.first { … }`)
 * rather than polling after `advanceUntilIdle`, so the admit/drop path is driven deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public abstract class PrincipalAttestationConformanceSuite {

    /**
     * A live principal-bearing seam under test plus the operations to admit, reconnect, drop, and
     * close peers whose connection carries a host-verified [Principal].
     *
     * An implementation owns whatever wiring its fabric needs (raw links, a mux server, …); the
     * suite drives it only through these four operations and reads [roster].
     */
    public interface AttestationHarness {
        /** The [PrincipalRoster] view of the seam under test. */
        public val roster: PrincipalRoster

        /**
         * Admit [peer] over a fresh link stamped with [principal] (`null` = an unattested
         * connection). A repeat call for an already-present [peer] models a **reconnect**: the
         * prior link is torn and replaced, and the new [principal] supersedes the old.
         *
         * Must not return until the admission is established (the peer is a live member of the
         * seam), so a subsequent roster read is not racing the handshake.
         */
        public suspend fun admit(peer: PeerId, principal: Principal?)

        /** Drop [peer]'s current link (a transport drop), leaving the seam open. */
        public suspend fun drop(peer: PeerId)

        /** Close the seam terminally. */
        public suspend fun close()
    }

    /**
     * Provide a fresh harness for one test.
     *
     * @param scope the test's coroutine scope (typically `backgroundScope`) for any accept pump,
     *   per-link read loops, or far-end handshakes the harness launches.
     * @param dispatcher the test dispatcher, so every seam shares the virtual clock.
     * @param random seeded [Random] for the seam's nonce generation — seeded for determinism.
     */
    public abstract suspend fun newHarness(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        random: Random,
    ): AttestationHarness

    // ── attested carriage ─────────────────────────────────────────────────────

    /** A peer admitted with a verified principal is reported on the roster for that peer. */
    @Test
    public fun attestedPrincipalIsReportedForAdmittedPeer(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(1L))
            val alice = PeerId("alice")
            val aliceKey = Principal("verified-alice")

            harness.admit(alice, aliceKey)
            val roster = harness.roster.attestedPrincipals.first { it[alice] == aliceKey }

            assertEquals(aliceKey, roster[alice], "the admitted peer's verified principal must be on the roster")
        }

    // ── per-peer keying ───────────────────────────────────────────────────────

    /** Two peers admitted with distinct principals are keyed independently by peer id. */
    @Test
    public fun distinctPrincipalsAreKeyedByPeer(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(2L))
            val alice = PeerId("alice")
            val bob = PeerId("bob")
            val aliceKey = Principal("verified-alice")
            val bobKey = Principal("verified-bob")

            harness.admit(alice, aliceKey)
            harness.admit(bob, bobKey)
            val roster = harness.roster.attestedPrincipals.first { alice in it && bob in it }

            assertEquals(
                mapOf(alice to aliceKey, bob to bobKey),
                roster,
                "each peer's principal must be keyed by that peer",
            )
        }

    /**
     * An admitted-but-unattested peer (a `null` principal) is **absent** from the roster — never a
     * `null` entry. Verified non-vacuously: an attested peer admitted the same way *is* present.
     */
    @Test
    public fun unattestedPeerIsAbsentFromRoster(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(3L))
            val alice = PeerId("alice")
            val bob = PeerId("bob")
            val aliceKey = Principal("verified-alice")

            harness.admit(alice, aliceKey)
            harness.admit(bob, principal = null)
            val roster = harness.roster.attestedPrincipals.first { it[alice] == aliceKey }

            assertAll(
                { assertEquals(aliceKey, roster[alice], "the attested peer is on the roster (admission works)") },
                { assertFalse(bob in roster, "the unattested peer must be absent, not a null-valued entry") },
            )
        }

    // ── reconnect refresh ─────────────────────────────────────────────────────

    /** A peer that reconnects with a new principal supersedes its prior roster entry. */
    @Test
    public fun rosterUpdatesPrincipalOnReconnect(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(4L))
            val alice = PeerId("alice")
            val firstKey = Principal("verified-alice-1")
            val secondKey = Principal("verified-alice-2")

            harness.admit(alice, firstKey)
            harness.roster.attestedPrincipals.first { it[alice] == firstKey }

            harness.admit(alice, secondKey) // reconnect over a fresh link
            val roster = harness.roster.attestedPrincipals.first { it[alice] == secondKey }

            assertEquals(secondKey, roster[alice], "a reconnect's principal must supersede the old one")
        }

    // ── drop / close cleanup ──────────────────────────────────────────────────

    /** A dropped peer's roster entry is removed; a bystander's entry survives. */
    @Test
    public fun droppedPeerLeavesTheRoster(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(5L))
            val alice = PeerId("alice")
            val bob = PeerId("bob")
            val aliceKey = Principal("verified-alice")
            val bobKey = Principal("verified-bob")

            harness.admit(alice, aliceKey)
            harness.admit(bob, bobKey)
            harness.roster.attestedPrincipals.first { alice in it && bob in it }

            harness.drop(alice)
            val roster = harness.roster.attestedPrincipals.first { alice !in it }

            assertAll(
                { assertFalse(alice in roster, "a dropped peer must leave the roster") },
                { assertEquals(bobKey, roster[bob], "a bystander's roster entry must survive an unrelated drop") },
            )
        }

    /** Closing the seam empties the roster with the membership. */
    @Test
    public fun closingTheSeamEmptiesTheRoster(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(6L))
            val alice = PeerId("alice")
            val aliceKey = Principal("verified-alice")

            harness.admit(alice, aliceKey)
            harness.roster.attestedPrincipals.first { it[alice] == aliceKey }

            harness.close()
            val roster = harness.roster.attestedPrincipals.first { it.isEmpty() }

            assertTrue(roster.isEmpty(), "closing the seam must clear the roster with the membership")
        }

    private fun kotlinx.coroutines.test.TestScope.dispatcher(): CoroutineContext =
        requireNotNull(coroutineContext[ContinuationInterceptor]) {
            "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
        }
}
