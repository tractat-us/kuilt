package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
 *  - **Attested *provenance*** — the roster value is a function of what the **host verified**
 *    alone. What the peer itself says never becomes an attestation, and never outranks the host's
 *    ([peerAssertedPrincipalNeverReachesTheRoster], [hostVerifiedPrincipalOutranksThePeersClaim]).
 *  - **Per-peer keying** — distinct peers' principals are keyed independently, and an unattested
 *    peer is simply absent, not `null`-valued ([distinctPrincipalsAreKeyedByPeer],
 *    [unattestedPeerIsAbsentFromRoster]).
 *  - **No unattested erasure** — a second, concurrent link that claims a live attested peer's id
 *    while presenting no credential of its own cannot erase that peer's attestation
 *    ([unattestedClaimCannotEraseAnAttestation]).
 *  - **Reconnect refresh** — a peer that reconnects with a new principal supersedes the old
 *    ([rosterUpdatesPrincipalOnReconnect]).
 *  - **Drop / close cleanup** — a dropped peer leaves the roster; a closed seam empties it
 *    ([droppedPeerLeavesTheRoster], [closingTheSeamEmptiesTheRoster]).
 *
 * ## Carriage is not provenance (#2316)
 *
 * Carriage — "the principal I handed in comes back out" — is the weaker half, and on its own it is
 * satisfied by a hub that reads the identity out of the **joiner's own handshake frame** instead of
 * the host-stamped connection. Every carriage property reads back a value the suite itself supplied,
 * so none of them can tell the two sources apart: a fabric that lets a peer assert whatever identity
 * it likes passes all of them. That is the hole this suite was written for
 * ("satisfied `: Seam` without carrying attestation"), one level up.
 *
 * What states the difference is [AttestationHarness.admitClaiming]: it splits the single principal
 * into the one the **host verified** and the one the **peer asserts**, and the two provenance
 * properties require the roster to be a function of the first alone. The hook is **non-nullable and
 * has no default** on purpose. An "my fabric gives a joiner no way to assert an identity" opt-out
 * would move the vacuity one level up, where it is harder to see — and it would also be false:
 * a [Seam] *is* a channel over which a peer sends bytes to the hub, so a joiner can always **utter**
 * a claim. The only question is whether the hub believes it, which is exactly what is under test.
 * There is therefore no "unreachable" arm here and no two-armed fixture; there is one hook every
 * implementor can honour.
 *
 * A harness that quietly *ignored* `claimed` would make both properties vacuous — the recorded
 * "permissive fake makes an enforcement test vacuous" failure. So each property asserts its own
 * **precondition** before it asserts anything else: it collects the seam's inbound frames and waits
 * to hear the claim come off the wire. A harness that never utters it does not pass; it wedges at
 * [TEST_WEDGE_BACKSTOP] inside the property that names the claim.
 *
 * **What the provenance properties cannot detect.** They drive exactly one channel — the bytes of
 * the first frame the joiner sends, plus the identity it self-asserts in its preamble. A hub that
 * believed a claim arriving some *other* way — a later frame, a side-band control message, a
 * transport header the harness does not set — still passes. They also say nothing about whether the
 * host's verification was any good: `verified` is taken on trust, as the fabric's accept handler
 * takes `call.principal()` on trust.
 *
 * ## Mutation receipt (#2316)
 *
 * Baseline and the reverted control arm are 16/16 green (the 8 properties this suite had at #2316,
 * × 2 subclasses; [unattestedClaimCannotEraseAnAttestation] arrived later and carries its own
 * receipt on #2357).
 *
 * | Mutation | Reds | Stays green |
 * |---|---|---|
 * | M1 `MuxServerLoom.readLoop`: `principal ?: payloadAsPrincipal(body)` — fall back to the identity the client announced in its first frame | `peerAssertedPrincipalNeverReachesTheRoster` (RoomHub) — 1/16 | all 6 carriage properties, both subclasses |
 * | M2 `MuxServerLoom.readLoop`: `payloadAsPrincipal(body) ?: principal` — prefer the client's announcement | both provenance properties (RoomHub) — 2/16 | all 6 carriage properties, both subclasses |
 * | M3 `MeshSeam.readLoop`: re-stamp the link's principal from each inbound frame body | both provenance properties (Mesh) — 2/16 | all 6 carriage properties, both subclasses |
 * | M4 `MeshSeam.handshakeLink`: `principal ?: Principal(remote.peerId.value)` — trust the self-asserted id | `peerAssertedPrincipalNeverReachesTheRoster` **and** `unattestedPeerIsAbsentFromRoster`, on **both** subclasses (a room rides a per-connection `hubMesh`) — 4/16 | `hostVerifiedPrincipalOutranksThePeersClaim` |
 *
 * The greens are the point of the table. M1–M3 are invisible to every pre-existing property here:
 * they can only fire on a frame body, and no carriage property sends one carrying an identity — so
 * those three are what the new properties buy. M4 is the converse, listed to mark where they are
 * *not* load-bearing: `unattestedPeerIsAbsentFromRoster` already caught it. M4's own green is honest
 * too — its `?:` cannot fire when the host verified something, which is exactly the half
 * [hostVerifiedPrincipalOutranksThePeersClaim] exists for and M2/M3 do red.
 *
 * Read the shape, not just the count: under M1 the two refusal assertions red while the rig
 * assertion ("a host-verified admission is reported in this run") stays green — the roster is alive
 * and the failure is a refusal that did not happen, not a roster that never filled. Under M2/M3
 * `hostVerifiedPrincipalOutranksThePeersClaim` reds with
 * `expected verified-guest but was verified-admin` — the forged value in the roster by name.
 *
 * **Virtual time convention:** every test runs under [StandardTestDispatcher] and awaits
 * admission/removal on observable roster state (`attestedPrincipals.first { … }`) rather than polling
 * after `advanceUntilIdle`, so the admit/drop path is driven deterministically. The `runTest` ceiling
 * is [TEST_WEDGE_BACKSTOP] — a generous wedge backstop, never a tight assertion (#1739).
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
        /**
         * The seam under test, for the suite's **single** collection of [Seam.incoming]
         * (ADR-034) — the channel a joiner's claim arrives on. A harness must not collect it.
         *
         * Usually the same object as [roster]; the suite never assumes so.
         */
        public val seam: Seam

        /** The [PrincipalRoster] view of the seam under test. */
        public val roster: PrincipalRoster

        /**
         * Admit [peer] over a fresh link stamped with [principal] (`null` = an unattested
         * connection). A repeat call for an already-present [peer] models a **reconnect**: the
         * prior link is torn and replaced, and the new [principal] supersedes the old.
         *
         * Must not return until the admission is established **and the roster has decided about
         * this peer** — an entry published, or deliberately withheld for an unattested link — so a
         * subsequent roster read is not racing the handshake. Membership alone is too weak: a fabric
         * that published membership and the roster on separate flows would let a later
         * `attestedPrincipals` read sample a roster that has not yet seen this admission, and
         * [unattestedPeerIsAbsentFromRoster] would pass for having asked too early (#2316). Both
         * reference harnesses satisfy it by construction — membership and roster are republished in
         * one critical section — so awaiting membership is enough *there*, not in general.
         */
        public suspend fun admit(peer: PeerId, principal: Principal?)

        /**
         * Admit [peer] over a link the host verified as [verified] (`null` = an unattested
         * connection) **while the peer itself asserts [claimed]** — the adversarial case [admit]
         * cannot state, because there the suite supplies the one principal and then reads it back.
         *
         * Route [claimed] through the channel the fabric gives a joiner to say who it is. Every
         * fabric has one, so there is no honest way to implement this by ignoring [claimed]: send
         * `claimed.value.encodeToByteArray()` as the first frame the peer puts on the wire (for a
         * hub that registers a connection on its first frame, that *is* the registering frame). If
         * the fabric also lets a joiner assert an identity somewhere else — a preamble field, a
         * transport header — set it there too; the suite additionally chooses [peer]'s own id to
         * spell the claim, since the self-asserted peer id is a channel every fabric has.
         *
         * Must not return until the admission is established, on the same terms as [admit]. The
         * suite then waits to hear [claimed] arrive on [seam] before asserting anything, so a
         * harness that silently dropped the claim wedges rather than passing.
         */
        public suspend fun admitClaiming(peer: PeerId, verified: Principal?, claimed: Principal)

        /**
         * Attach a **second, concurrent** link that self-asserts [peer]'s id over a connection the
         * host verified as **nothing at all**, and have that link utter [claimed] as the first thing
         * it says. [peer]'s existing link is **not** torn first.
         *
         * This is the state [admit] and [admitClaiming] cannot reach. There, a repeat call for a
         * live peer is a *reconnect* — the prior link is torn and replaced — so the attested link is
         * already gone before the unattested one arrives and there is no live attestation left to
         * erase. The impostor of #2357 does not wait for the peer to leave: it dials alongside, and
         * the only thing it needs is the peer id, which is the self-asserted preamble field every
         * peer broadcasts.
         *
         * Route [claimed] exactly as [admitClaiming] does — the channel the fabric gives a joiner to
         * say who it is, spelled into the first frame the impostor puts on the wire — and do not
         * return until that frame has been sent. The suite then waits to hear it arrive on [seam]
         * before it judges the roster, so a harness that honoured this hook by doing nothing wedges
         * rather than passing.
         *
         * **Non-nullable, no default, no opt-out arm**, for the reason [admitClaiming] gives: a "my
         * fabric cannot hold two links for one id" escape hatch would move the vacuity one level up,
         * where it is harder to see. The honest limitation runs the other way — a fabric that
         * **refuses** the duplicate claim outright at the transport (the strongest defence there is)
         * can never deliver the frame, so it would wedge here rather than pass. Neither reference
         * hub does that. The day one does, this wants a two-armed outcome (admitted / refused) with
         * *both* arms asserting, not a nullable hook.
         */
        public suspend fun admitConcurrentClaim(peer: PeerId, claimed: Principal)

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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(3L))
            val alice = PeerId("alice")
            val bob = PeerId("bob")
            val aliceKey = Principal("verified-alice")

            // bob (unattested) FIRST, alice (attested) second. `admit` returns only once its peer is
            // established, so alice's admission is strictly after bob's, and the sample below —
            // taken on alice's arrival — cannot precede the hub's decision about bob. The old order
            // sampled on alice while bob's admission was still in flight and was non-vacuous only by
            // luck: both reference harnesses happen to publish membership and roster in one critical
            // section, so a fabric that published them on separate flows passed for having asked too
            // early (#2316). Belt and braces with the strengthened [AttestationHarness.admit]
            // postcondition — this ordering holds even for a harness that honours only the weaker one.
            harness.admit(bob, principal = null)
            harness.admit(alice, aliceKey)
            val roster = harness.roster.attestedPrincipals.first { it[alice] == aliceKey }

            assertAll(
                { assertEquals(aliceKey, roster[alice], "the attested peer is on the roster (admission works)") },
                { assertFalse(bob in roster, "the unattested peer must be absent, not a null-valued entry") },
            )
        }

    // ── attested provenance ───────────────────────────────────────────────────

    /**
     * **Security.** An identity a peer asserts about *itself* never becomes an attestation. The
     * impostor loads both channels a joiner controls with the same claim — the id it self-asserts in
     * its preamble, and the body of the frame it sends — over a link the host verified as nothing at
     * all. It must stay absent from the roster, and its claim must not be attributed to anyone.
     *
     * The `witness` is the rig: a peer admitted the ordinary attested way in the same run, so the
     * impostor's absence is a refusal and not an artefact of a roster that never fills. The
     * `heard.first { … }` await is the precondition — the claim is proven to have crossed the wire
     * before the roster is judged, so a harness that dropped [AttestationHarness.admitClaiming]'s
     * `claimed` on the floor cannot pass by never asserting anything.
     */
    @Test
    public fun peerAssertedPrincipalNeverReachesTheRoster(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(7L))
            val heard = backgroundScope.utterances(harness.seam)
            val witness = PeerId("witness")
            val witnessKey = Principal("verified-witness")
            // The impostor's own id spells the claim, so a hub that trusts the self-asserted peer id
            // is caught by the same assertion as one that trusts the frame body.
            val impostor = PeerId("verified-admin")
            val claimed = Principal(impostor.value)

            harness.admit(witness, witnessKey)
            harness.admitClaiming(impostor, verified = null, claimed = claimed)
            heard.first { claimed.value in it } // precondition: the claim really crossed the wire
            // Both reference hubs record the principal before emitting the frame, so the claim's
            // arrival already implies the roster decision; drain the current queue anyway rather than
            // rest the property on that ordering.
            testScheduler.runCurrent()
            val roster = harness.roster.attestedPrincipals.first { it[witness] == witnessKey }

            assertAll(
                { assertEquals(witnessKey, roster[witness], "rig: a host-verified admission is reported in this run") },
                { assertFalse(impostor in roster, "a peer's own claim must not attest it") },
                { assertFalse(claimed in roster.values, "the claim must not be attributed to any peer") },
            )
        }

    /**
     * **Security.** When the host's verification and the peer's claim disagree, the host wins. This
     * is the half [peerAssertedPrincipalNeverReachesTheRoster] cannot reach: a hub that consults the
     * peer's claim *only when it has no stamp of its own* passes that one and fails this one, and a
     * hub that lets the claim override passes neither.
     *
     * `mallory` is attested, so the roster entry read here is one the attestation path genuinely
     * produced — the assertion distinguishes two live values rather than confirming an empty map.
     */
    @Test
    public fun hostVerifiedPrincipalOutranksThePeersClaim(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(8L))
            val heard = backgroundScope.utterances(harness.seam)
            val mallory = PeerId("mallory")
            val verified = Principal("verified-guest")
            val claimed = Principal("verified-admin")

            harness.admitClaiming(mallory, verified = verified, claimed = claimed)
            heard.first { claimed.value in it } // precondition: the claim really crossed the wire
            testScheduler.runCurrent()
            val roster = harness.roster.attestedPrincipals.first { mallory in it }

            assertAll(
                { assertEquals(verified, roster[mallory], "the roster must report what the HOST verified") },
                { assertFalse(claimed in roster.values, "the peer's claim must not outrank the host's verification") },
            )
        }

    // ── no unattested erasure ─────────────────────────────────────────────────

    /**
     * **Security (#2357).** A link the host verified as **nothing** cannot **erase** an attestation
     * the host already verified, merely by self-asserting the attested peer's id alongside it.
     *
     * The impostor presents no credential — it does not need one. It dials a *second, concurrent*
     * link (alice's is never torn) and announces alice's id, which is public: it is the preamble
     * field every peer broadcasts. What it must not be able to do is make the hub forget that it
     * verified alice.
     *
     * **What this does and does not pin.** It pins the `null` half only: an *unattested* link may
     * not erase an attestation. It says nothing about attested → attested supersession, which
     * [rosterUpdatesPrincipalOnReconnect] still mandates, and nothing about which link the fabric
     * chooses to *route* to afterwards — a hub that lets the impostor capture `sendTo(alice, …)`
     * while keeping alice's roster entry passes this. The roster is the only surface under test, and
     * that residual is #2357's own recorded caveat, not an oversight here.
     *
     * The `first { it[alice] == aliceKey }` before the claim is the rig: the attestation is proven
     * *established* first, so a roster that never filled cannot pass this by being empty at both
     * ends. The `heard.first { … }` after it is the precondition: the impostor's claim is proven to
     * have crossed the wire — which for both reference hubs also proves its link won whatever
     * duplicate-link contest the fabric runs, since a loser's connection is closed and can never
     * utter anything. A harness that dropped the claim, or one whose impostor lost, wedges at
     * [TEST_WEDGE_BACKSTOP] rather than passing on a displacement that never happened.
     *
     * The final read is `.value`, deliberately not `first { … }`: this property asserts that a value
     * did **not** change, and awaiting that predicate would turn a real regression into a wedge
     * instead of a legible red.
     */
    @Test
    public fun unattestedClaimCannotEraseAnAttestation(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(9L))
            val heard = backgroundScope.utterances(harness.seam)
            val alice = PeerId("alice")
            val aliceKey = Principal("verified-alice")
            val claimed = Principal("verified-mallory")

            harness.admit(alice, aliceKey)
            harness.roster.attestedPrincipals.first { it[alice] == aliceKey } // rig: alice IS attested

            harness.admitConcurrentClaim(alice, claimed)
            heard.first { claimed.value in it } // precondition: the impostor's link is live and spoke
            testScheduler.runCurrent()
            val roster = harness.roster.attestedPrincipals.value

            assertAll(
                { assertEquals(aliceKey, roster[alice], "an unattested claim must not erase the host's attestation") },
                { assertFalse(claimed in roster.values, "the impostor's claim must not be attributed to any peer") },
            )
        }

    // ── reconnect refresh ─────────────────────────────────────────────────────

    /**
     * A peer that reconnects with a **new principal** supersedes its prior roster entry.
     *
     * **What this still mandates, after #2357: attested → attested supersession only.** Both
     * principals here are non-null, so this property obliges the hub to let a link the host verified
     * as `verified-alice-2` replace one it verified as `verified-alice-1`. Since the peer id is
     * self-asserted, the harness cannot distinguish "alice reconnecting" from "someone else the host
     * also verified, claiming alice's id", so that displacement remains reachable by an impostor who
     * holds *some* valid credential. Whether a hub should bind an id to an attestation is a
     * behaviour decision with consumer impact and stays open — `RoomAuthorizer` cannot even express
     * it today (#2357).
     *
     * **What it no longer mandates: unattested displacement.** It used to be read as also obliging a
     * hub to let a link the host verified as *nothing* erase an attestation, because a second link
     * always won. It never actually exercised that case — both principals are non-null — and
     * [unattestedClaimCannotEraseAnAttestation] now forbids it outright. The two properties are
     * complementary, not in tension.
     */
    @Test
    public fun rosterUpdatesPrincipalOnReconnect(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val harness = newHarness(backgroundScope, dispatcher(), Random(6L))
            val alice = PeerId("alice")
            val aliceKey = Principal("verified-alice")

            harness.admit(alice, aliceKey)
            harness.roster.attestedPrincipals.first { it[alice] == aliceKey }

            harness.close()
            val roster = harness.roster.attestedPrincipals.first { it.isEmpty() }

            assertTrue(roster.isEmpty(), "closing the seam must clear the roster with the membership")
        }

    /**
     * Collect [seam]'s inbound frames into an observable set of their UTF-8 bodies — the suite's
     * evidence that a peer's claim really crossed the wire, rather than the harness's word for it.
     *
     * [Seam.incoming] is single-collection (ADR-034), so this is the one collection and an
     * [AttestationHarness] must leave it to the suite. The set only grows, so awaiting a body on it
     * is order-independent and cannot miss a frame that arrived before the await.
     */
    private fun CoroutineScope.utterances(seam: Seam): StateFlow<Set<String>> {
        val heard = MutableStateFlow(emptySet<String>())
        launch { seam.incoming.collect { frame -> heard.update { seen -> seen + frame.decodeToString() } } }
        return heard.asStateFlow()
    }

    private fun kotlinx.coroutines.test.TestScope.dispatcher(): CoroutineContext =
        requireNotNull(coroutineContext[ContinuationInterceptor]) {
            "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
        }
}
