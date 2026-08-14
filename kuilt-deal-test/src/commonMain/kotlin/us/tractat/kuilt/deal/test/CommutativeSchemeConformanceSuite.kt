package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.CommutativeScheme
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Conformance TCK for [CommutativeScheme]. Validate a new scheme by subclassing
 * this suite and overriding [newScheme], [newPeerScheme] and [proofStrength]:
 *
 * ```kotlin
 * class MySchemeConformanceTest : CommutativeSchemeConformanceSuite() {
 *     override fun newScheme() = MyScheme()
 *     override fun newPeerScheme() = MyScheme()
 *     override fun proofStrength() = ProofStrength.RejectsForgeries
 * }
 * ```
 *
 * The suite verifies the commutative-encryption laws the card-deal protocol
 * relies on (round-trip, commutativity, multi-layer strip-order independence,
 * key distinctness) — both within one scheme instance and **across the separate
 * instances real peers actually run**. It tests the raw [CommutativeScheme]
 * contract on in-domain byte messages — plaintext domain encoding (e.g. SRA's
 * marker codec) is a scheme-layer concern and is out of scope here; override
 * [validPlaintexts] if your scheme's valid domain differs from short ASCII.
 *
 * **Every law above is satisfied by a scheme that does nothing.** A `encrypt` returning its
 * argument round-trips, commutes, strips in any order and — because
 * [distinctKeysProduceDistinctCiphertexts] compares two keys rather than a key against the
 * plaintext — survives even that. So two properties here are about the laws being *worth*
 * something rather than about them holding: [encryptHidesThePlaintextAndStripRecoversIt] and the
 * two multi-layer properties assert a layer **changes** what it covers and keeps changing it until
 * the last one comes off; [verifyAnswersForgedTransitionsAsDeclared] calls the scheme's `verify*`
 * pair with transitions it did **not** produce. Before #2313 the suite touched `verify*` only on
 * honest input, which is the accept branch of a predicate every shipped scheme stubs to `true` —
 * coverage in appearance and nothing in fact.
 */
public abstract class CommutativeSchemeConformanceSuite {

    /** A fresh scheme instance under test. */
    public abstract fun newScheme(): CommutativeScheme

    /**
     * A **second, independently constructed** scheme instance — what a remote peer runs, in its
     * own process, on its own device.
     *
     * **Why this is a hook at all.** A card deal is not one object encrypting twice: `DealSession`
     * holds `private val scheme: CommutativeScheme` and one key, one session per player, and the
     * law the protocol rests on is therefore *cross-instance* —
     * `alice.encrypt(bob.encrypt(m, b), a) == bob.encrypt(alice.encrypt(m, a), b)` where `alice`
     * and `bob` are different objects. Drawing every key from a single instance, as this suite did
     * before #2311, tests the one arrangement that cannot fail. Textbook SRA has each player
     * choose their **own** modulus; a scheme written that way — the natural first draft — passes
     * every single-instance property here and produces garbage on the second layer of a real deal.
     * Neither [distinctKeysProduceDistinctCiphertexts] nor round-trip catches it, because a single
     * peer's encrypt/strip pair still inverts.
     *
     * **Non-nullable and abstract on purpose.** An "my scheme cannot do this" opt-out would move
     * the vacuity one level up, where it is harder to see; and a default of `= newScheme()` would
     * let an implementor whose group parameters are per-instance never confront the question at
     * all — the compiler puts this KDoc in front of them instead. A scheme that cannot agree with
     * a second instance of itself cannot participate in a deal, and that must fail here rather
     * than in production.
     *
     * **It returns an instance, never a key.** The suite generates every key itself, from the
     * instance that will apply it, so a fixture cannot hand back two keys minted by one scheme.
     *
     * **Called more than once per property.** Every call must return a freshly constructed
     * instance; the cross-peer properties assert pairwise referential distinctness and fail loudly
     * on a cached one. They also assert the peers' single-layer ciphertexts differ, so a fixture
     * that seeds two instances identically — distinct objects, identical keys, layers that cancel
     * — reds instead of passing.
     *
     * **What this cannot detect.** The suite can demand a second independently constructed
     * instance; it cannot inspect *how* the subclass built it. A fixture that deliberately hands
     * both instances the group parameters production would have each peer roll separately still
     * passes. That is the residual, and it is one an implementor has to write on purpose.
     */
    public abstract fun newPeerScheme(): CommutativeScheme

    /**
     * What this scheme's [CommutativeScheme.verifyEncrypt] / [CommutativeScheme.verifyStrip] pair
     * does with a transition it did **not** produce — a declaration the subclass makes and
     * [verifyAnswersForgedTransitionsAsDeclared] then holds it to.
     *
     * **Why a declaration and not simply a property.** `CommutativeScheme` documents that an
     * implementation may `return true` unconditionally until real proofs land, and both schemes in
     * this repo take that option; a suite that flatly asserted rejection would fail them, and one
     * that asserted nothing — this suite, before #2313 — pins only the accept branch of a
     * predicate that is unconditionally true. Neither is the truth. The declaration puts the
     * scheme's posture in the fixture, in one word, where the next reader of
     * `XorKeystreamSchemeConformanceTest` cannot mistake "the TCK exercises verification" for
     * "the TCK detects cheating".
     *
     * **Both arms are checkable, which is what makes this different from a skip.** A hook that
     * says "I cannot reach that state" is an opt-out, and #2247's finding is that an opt-out moves
     * the vacuity one level up. Here neither arm opts out: [ProofStrength.RejectsForgeries] fails
     * if a forgery is accepted, and [ProofStrength.AcceptsEverything] fails if one is *rejected*.
     * A stub-true scheme that grows a real verifier reds until it updates this line, and a real
     * verifier that regresses to `return true` reds immediately — which is the direction that
     * matters.
     *
     * **What no declaration can detect.** Nothing here inspects *how* a verifier decides, so a
     * [ProofStrength.RejectsForgeries] scheme that rejects the six forgeries
     * [verifyAnswersForgedTransitionsAsDeclared] derives and accepts a seventh nobody thought of
     * is invisible. The suite tests a floor, not soundness; a real proof system needs its own
     * adversary, not a TCK.
     */
    public abstract fun proofStrength(): ProofStrength

    /** Sample messages guaranteed to lie in the scheme's valid input domain. */
    public open fun validPlaintexts(): List<ByteArray> = listOf(
        "card:ACE_OF_SPADES".encodeToByteArray(),
        "card:KING_OF_HEARTS".encodeToByteArray(),
        "7".encodeToByteArray(),
    )

    /**
     * Round-trip — `strip(encrypt(m, k), k) == m` — **and the secrecy floor**: the ciphertext is
     * not the plaintext.
     *
     * The second half is one `assertNotEquals` and it is the whole of what this suite says about
     * hiding. Without it a scheme whose `encrypt` returns its argument passes every property here:
     * the round-trip inverts trivially, both commutativity properties hold because nothing moves,
     * strip order is free, and [distinctKeysProduceDistinctCiphertexts] compares two *keys* — so
     * one degenerate key beside one healthy key still yields differing ciphertexts and stays
     * green. Since a card deal's entire purpose is that no player can read a card another player
     * covered, that is the one law worth having if you may only have one.
     *
     * It costs nothing: the ciphertext is already in hand for the round-trip, and this reuses the
     * loop rather than adding a second one — deliberately, because [newScheme] may be SRA doing
     * 2048-bit modular exponentiation per layer.
     *
     * **Reachable, not hypothetical, on the real scheme.** `SraScheme.generateKey` rejects an
     * exponent only when `gcd(e, p-1) != 1`, which admits `e = 1` — the identity. That an
     * astronomically-improbable draw is needed to hit it is a property of the CSPRNG, not of the
     * check, and a scheme's key generator is exactly the kind of code a later optimisation edits.
     */
    @Test
    public fun encryptHidesThePlaintextAndStripRecoversIt() {
        val scheme = newScheme()
        val key = scheme.generateKey()
        val messages = validPlaintexts()
        // Empty is the setting at which this property switches itself off — both assertions below
        // live inside the loop, so an empty domain passes by arithmetic. See #2347.
        assertTrue(messages.isNotEmpty(), "validPlaintexts() is empty, so this property asserts nothing")
        for (m in messages) {
            val (cipher, _) = scheme.encrypt(m, key.encryptKey)
            val (recovered, _) = scheme.strip(cipher, key.stripKey)
            assertAll(
                {
                    assertNotEquals(
                        m.toList(),
                        cipher.toList(),
                        "encrypt returned its own argument for ${m.toList()} — the layer hid nothing, and " +
                            "every other law in this suite holds vacuously for a scheme that does that",
                    )
                },
                { assertEquals(m.toList(), recovered.toList(), "round-trip failed for ${m.toList()}") },
            )
        }
    }

    @Test
    public fun encryptionIsCommutative() {
        val scheme = newScheme()
        val a = scheme.generateKey()
        val b = scheme.generateKey()
        for (m in validPlaintexts()) {
            val ab = scheme.encrypt(scheme.encrypt(m, a.encryptKey).first, b.encryptKey).first
            val ba = scheme.encrypt(scheme.encrypt(m, b.encryptKey).first, a.encryptKey).first
            assertEquals(ab.toList(), ba.toList(), "commutativity failed for ${m.toList()}")
        }
    }

    /**
     * The same law across the instance boundary a real deal actually has: `a` is applied by the
     * scheme that minted it and `b` by the scheme that minted *it*, so nothing about the two
     * layers passes through one object. [encryptionIsCommutative] one screen up is the degenerate
     * case of this — and the only case the suite had before #2311.
     *
     * Assertions, in the order they appear below, and the numbering the receipts use:
     *
     * 1. **precondition**, checked eagerly — the two schemes are distinct objects, so a fixture
     *    caching one instance reds here rather than re-running [encryptionIsCommutative] under a
     *    new name;
     * 2. **precondition**, checked eagerly — their single-layer ciphertexts differ, so a fixture
     *    seeding two instances identically (distinct objects, identical keys, layers that cancel)
     *    reds too.
     *    This is [distinctKeysProduceDistinctCiphertexts] in its cross-peer form, and it is free:
     *    both ciphertexts are already needed as the second layer's input;
     * 3. **the law** — `E_bob(E_alice(m)) == E_alice(E_bob(m))` for every valid plaintext, over a
     *    [validPlaintexts] asserted non-empty. That knob's empty setting is the one that switches
     *    this property off wholesale: the law asserts nothing over an empty list *and* the rig
     *    below expects zero encryptions, so both would pass by arithmetic;
     * 4. **the rig fired** — each peer performed exactly two encryptions per message, its own
     *    layer once as the outer and once as the inner. Rewriting the body to route both layers
     *    through one peer is precisely how this property decays back into its single-instance
     *    sibling, and a count is what notices; a passing `assertEquals` on the ciphertexts would
     *    not, since that mutation makes the law *more* true.
     *
     * [SchemePeer] carries the other half: a peer owns its key privately and exposes only
     * `encrypt`/`strip`, so applying one peer's key through another peer's scheme — a mis-crossing
     * that would pass on any stateless scheme and hide a per-instance one — is not something this
     * test body can express at all.
     *
     * **Mutation receipts**, measured on this branch (JVM, `--rerun-tasks`), each applied alone and
     * reverted, the verdict read out of the results XML:
     *
     * | Mutation | Reds |
     * |---|---|
     * | `SraScheme` given a **per-instance modulus** — textbook SRA | 3, here and in the sibling below — **and nothing else in the suite** |
     * | Fixture: `newPeerScheme()` hands back a cached instance | 1 |
     * | Fixture: two distinct instances seeded identically | 2 |
     * | …that one again, with assertion 2 removed | **nothing** — which is why 2 exists |
     * | Body: compute the *same* crossing twice, so the law is trivially true | **4 only — 3 stays green** |
     * | Fixture: `validPlaintexts()` overridden to empty | the non-empty check |
     *
     * The fifth row is the one worth reading twice: that mutation makes assertion 3 *more* likely
     * to pass, so no ciphertext comparison can notice it, and assertion 4 is the whole of what
     * stands between this property and its single-instance sibling.
     */
    @Test
    public fun encryptionIsCommutativeAcrossPeerInstances() {
        val alice = SchemePeer(newScheme())
        val bob = SchemePeer(newPeerScheme())
        val peers = listOf(alice, bob)
        val messages = validPlaintexts()
        // [validPlaintexts] is the one free knob this property has, and empty is the setting at
        // which it switches itself off: the law below would assert nothing over an empty list, and
        // the rig would expect — and get — zero encryptions, so the whole test would pass by
        // arithmetic. Named here rather than left to the reader to notice.
        assertTrue(messages.isNotEmpty(), "validPlaintexts() is empty, so this property asserts nothing")
        val crossed = messages.map { m ->
            val (byAlice, byBob) = independentSingleLayers(peers, m)
            Triple(m, bob.encrypt(byAlice), alice.encrypt(byBob))
        }
        assertAll(
            {
                crossed.forEach { (m, ab, ba) ->
                    assertEquals(
                        ab.toList(),
                        ba.toList(),
                        "cross-peer commutativity failed for ${m.toList()}: the two layers were applied " +
                            "by the instances that minted them, which is what a real deal does",
                    )
                }
            },
            { assertRigCrossed(peers, expectedEncryptions = 2 * messages.size, expectedStrips = 0) },
        )
    }

    /**
     * Three layers on, three off in a deranged order, and the plaintext comes back — **and the
     * card is unreadable at every step in between**.
     *
     * The second half is the deal's actual secrecy claim, and it is strictly stronger than
     * [encryptHidesThePlaintextAndStripRecoversIt]'s: that one says a single layer changes the
     * value, this one says the value stays changed while *any* layer remains, which is what
     * "nobody can read a card until the last player releases it" means. It is free — the
     * intermediates are computed anyway — and it is the assertion that reds if some later key
     * turns out to cancel an earlier one.
     */
    @Test
    public fun multiLayerDealRecoversPlaintextRegardlessOfStripOrder() {
        val scheme = newScheme()
        val keys = List(3) { scheme.generateKey() }
        // One representative plaintext: three independent keys exercising a deranged
        // strip order is what proves order-independence — extra plaintexts add cost
        // without strengthening the law (and overrun the wasmJs 2s test budget, since
        // a heavyweight scheme like SRA-2048 does real big-integer work per layer).
        val m = validPlaintexts().first()
        // Layer all three encryptions (order k0, k1, k2).
        var cipher = m
        val underCover = mutableListOf<ByteArray>()
        for (k in keys) {
            cipher = scheme.encrypt(cipher, k.encryptKey).first
            underCover += cipher
        }
        // Strip in a fully deranged order (k2, k0, k1) — no layer in its encryption
        // position — so commutativity is genuinely exercised. Every value up to and
        // including the second-last strip still carries at least one layer.
        val stripOrder = listOf(keys[2], keys[0], keys[1])
        for (k in stripOrder.dropLast(1)) {
            cipher = scheme.strip(cipher, k.stripKey).first
            underCover += cipher
        }
        val recovered = scheme.strip(cipher, stripOrder.last().stripKey).first
        assertAll(
            { assertEquals(m.toList(), recovered.toList(), "multi-layer recovery failed for ${m.toList()}") },
            { assertStayedCovered(underCover, m) },
        )
    }

    /**
     * A three-player deal in the arrangement a real one has: three separately constructed schemes,
     * each holding only its own key, each applying and later removing only its own layer. This is
     * the only property in the suite that drives [CommutativeScheme.strip] across the instance
     * boundary — [encryptionIsCommutativeAcrossPeerInstances] covers encrypt alone, and the
     * single-instance sibling above covers both with the boundary removed.
     *
     * The shape mirrors that sibling exactly — three keys, one representative plaintext, a fully
     * deranged strip order — so the *only* difference between the two properties is where the keys
     * come from, which is the thing under test. Three is the sibling's number and the smallest one
     * at which a strip order can put no layer back in its own encryption position.
     *
     * Assertions, in the order they appear below, and the numbering the receipts use:
     *
     * 1. **precondition** — the three schemes are distinct objects (checked eagerly, before any
     *    layering, so a broken fixture reds as a fixture rather than as a failed law);
     * 2. **precondition** — their single-layer ciphertexts are pairwise distinct, so three peers
     *    that happen to share a key cannot make the deranged strip trivially unwind;
     * 3. **the law** — the deranged strip recovers the plaintext;
     * 4. **the rig fired** — every peer encrypted twice (its solo layer for assertion 2, then its
     *    layer in the chain) and stripped exactly once. This is what pins assertion 2 *in place*:
     *    dropping the precondition call is a one-line edit that leaves the law passing, and the
     *    counts are the only thing that reds on it;
     * 5. **the card stayed covered** — no intermediate in the chain, from the first layer on to the
     *    second-last strip, equals the plaintext. Appended as 5 rather than inserted so the
     *    numbering above (and the receipts below, measured on #2311) still reads true. Free, since
     *    the intermediates exist regardless, and it is the only assertion here that would notice
     *    three peers whose layers cancelled each other out mid-chain.
     *
     * **Mutation receipts**, measured as for the sibling property above:
     *
     * | Mutation | Reds |
     * |---|---|
     * | `SraScheme` given a **per-instance modulus** — textbook SRA | 3, and nothing pre-existing |
     * | Fixture: `newPeerScheme()` hands back a cached instance | 1 |
     * | Fixture: two distinct instances seeded identically | 2 |
     * | Body: delete the assertion-2 call | **4 only — 3 stays green**, both subclasses |
     *
     * **What this cannot reach.** It drives one deranged order out of the six; a scheme that
     * commuted for some permutations and not others would need the full sweep, and no scheme that
     * satisfies [encryptionIsCommutativeAcrossPeerInstances] can be one. And three peers is the
     * sibling's number, not a proof that four would not break something a three-cycle cannot.
     */
    @Test
    public fun multiLayerDealAcrossPeerInstancesRecoversPlaintextRegardlessOfStripOrder() {
        val peers = listOf(SchemePeer(newScheme()), SchemePeer(newPeerScheme()), SchemePeer(newPeerScheme()))
        val m = validPlaintexts().first()
        independentSingleLayers(peers, m)
        // Layer all three encryptions (order peer0, peer1, peer2), each applied by its own instance.
        var cipher = m
        val underCover = mutableListOf<ByteArray>()
        for (peer in peers) {
            cipher = peer.encrypt(cipher)
            underCover += cipher
        }
        // Strip in a fully deranged order (peer2, peer0, peer1) — no layer in its encryption
        // position — again each by its own instance, which is what every player does for real.
        val stripOrder = listOf(peers[2], peers[0], peers[1])
        for (peer in stripOrder.dropLast(1)) {
            cipher = peer.strip(cipher)
            underCover += cipher
        }
        val recovered = stripOrder.last().strip(cipher)
        assertAll(
            {
                assertEquals(
                    m.toList(),
                    recovered.toList(),
                    "cross-peer multi-layer recovery failed for ${m.toList()}",
                )
            },
            { assertRigCrossed(peers, expectedEncryptions = 2, expectedStrips = 1) },
            { assertStayedCovered(underCover, m) },
        )
    }

    @Test
    public fun distinctKeysProduceDistinctCiphertexts() {
        val scheme = newScheme()
        val a = scheme.generateKey()
        val b = scheme.generateKey()
        val m = validPlaintexts().first()
        assertNotEquals(
            scheme.encrypt(m, a.encryptKey).first.toList(),
            scheme.encrypt(m, b.encryptKey).first.toList(),
            "distinct keys produced identical ciphertext",
        )
    }

    /**
     * Every pair [CommutativeScheme.generateKey] hands out both round-trips **and hides** — the
     * secrecy floor said of the *generator* rather than of one key.
     *
     * The distinction is the whole reason the second assertion is here rather than left to
     * [encryptHidesThePlaintextAndStripRecoversIt]. That property draws one key, so it catches a
     * generator that is degenerate *always*; a generator degenerate only *sometimes* — SRA's
     * rejection loop tests `gcd(e, p-1) != 1` and nothing else, so `e = 1` is inside its output
     * domain — slips past it whenever the healthy key is the one drawn. Round-tripping is no
     * defence either: the identity key round-trips perfectly, which is exactly what makes it hard
     * to see.
     *
     * **What this cannot detect.** Two draws. A generator that emits a degenerate key one time in
     * a thousand is invisible here and would stay invisible at any count a TCK can afford; the
     * assertion narrows the window rather than closing it. Raising the count is the wrong lever —
     * it multiplies a heavyweight scheme's key generation for a linear gain against an exponential
     * problem.
     */
    @Test
    public fun generatedKeyPairsAreUsable() {
        val scheme = newScheme()
        // Two independently generated pairs — enough to show generateKey() yields
        // usable, distinct pairs. (Kept low so a heavyweight scheme's key generation
        // stays within the wasmJs 2s test budget.)
        repeat(2) { draw ->
            val key = scheme.generateKey()
            val m = validPlaintexts().first()
            val (cipher, _) = scheme.encrypt(m, key.encryptKey)
            val (recovered, _) = scheme.strip(cipher, key.stripKey)
            assertAll(
                { assertTrue(m.contentEquals(recovered), "generated key pair $draw failed to round-trip") },
                {
                    assertNotEquals(
                        m.toList(),
                        cipher.toList(),
                        "generated key pair $draw is the identity — it round-trips, and hides nothing",
                    )
                },
            )
        }
    }

    /**
     * Honest-path verification: an [CommutativeScheme.encrypt]/[CommutativeScheme.strip]
     * transition that the scheme itself produced must verify. This is the **completeness** half —
     * a verifier that rejects everything is as useless as one that accepts everything, and only
     * this property refuses it.
     *
     * On its own it proves very little, and for years it was the suite's only mention of
     * `verify*`: on a scheme that stubs both to `true` — which every scheme in this repo does, and
     * which [CommutativeScheme] expressly permits — it pins the accept branch of a predicate with
     * no other branch. [verifyAnswersForgedTransitionsAsDeclared] is the half that has teeth;
     * this one is what stops that half from being satisfiable by `return false`.
     */
    @Test
    public fun verifyAcceptsHonestTransitions() {
        val scheme = newScheme()
        val key = scheme.generateKey()
        val m = validPlaintexts().first()
        val (cipher, encryptProof) = scheme.encrypt(m, key.encryptKey)
        assertTrue(
            scheme.verifyEncrypt(m, cipher, encryptProof, key.encryptKey),
            "verifyEncrypt rejected an honestly-produced encryption",
        )
        val (recovered, stripProof) = scheme.strip(cipher, key.stripKey)
        assertTrue(
            scheme.verifyStrip(cipher, recovered, stripProof, key.encryptKey),
            "verifyStrip rejected an honestly-produced strip",
        )
    }

    /**
     * `verify*` called with six transitions the scheme did **not** produce, and held to the answer
     * [proofStrength] declared for all six.
     *
     * **The hole this closes.** Every other mention of `verify*` in this suite hands it material
     * the scheme just made itself. Against an accept-only predicate that is indistinguishable from
     * not calling it at all — and both shipped schemes *are* accept-only, and no production caller
     * consults either method, so "the TCK covers verification" was a sentence with nothing behind
     * it. Forcing a predicate always-true proves it is **consulted**, never that it is
     * **sufficient**; here it was not even consulted with anything that could fail.
     *
     * **The forgeries are derived, never supplied.** There is no `newForgery()` hook, because a
     * fixture that hands back an answer can hand back a wrong one — the cheapest wrong one being a
     * "forgery" that is really an honest transition, which every arm accepts and nobody notices.
     * The suite mints two keys from the scheme under test and crosses honest material with itself:
     * a ciphertext produced under the *other* key (the substituted-layer cheat), a transition where
     * nothing happened at all (`next == prev`, the skipped-layer cheat — a player who pockets a
     * card without covering it), and an honest transition re-attributed to the *other* player's
     * public key (the framed-peer cheat). Three shapes, once for `verifyEncrypt` and once for
     * `verifyStrip`.
     *
     * **Each of the six is checked to be a forgery before it is used**, eagerly, so a scheme on
     * which they degenerate into honest transitions reds as a fixture problem rather than passing
     * the [ProofStrength.RejectsForgeries] arm by accident. Those preconditions are the rig, and
     * on this property the rig is the whole risk: an "adversarial" input that is not adversarial
     * makes the arm assert the opposite of what it reads as.
     *
     * **Not forged: a garbled proof.** `verifyEncrypt(m, cipher, EncryptProof(garbage), pubKey)`
     * looks like the obvious seventh case and is deliberately absent — a scheme whose transition is
     * recomputable from `prev`, `next` and `pubKey` may legitimately ignore the proof bytes
     * entirely, so rejection is not a contract obligation and asserting it would fail a correct
     * implementation. (`RecomputingXorScheme`, the binding that exercises the rejecting arm, is
     * exactly such a scheme.)
     *
     * **Mutation receipts**, measured on this branch — each applied alone, verdict read out of the
     * results XML, reverted, and the revert grep-verified:
     *
     * | Mutation | Reds |
     * |---|---|
     * | `RecomputingXorScheme.verifyEncrypt`/`verifyStrip` → `return true` | this property, on that binding **only** — every other property in the suite stays green on all three bindings |
     * | `SraScheme.encrypt` returns its `plaintext` argument | [encryptHidesThePlaintextAndStripRecoversIt] and both multi-layer properties — and **not** commutativity, key distinctness, cross-peer commutativity or `verifyAcceptsHonestTransitions` |
     * | Fixture: `RecomputingXorSchemeConformanceTest` declares `AcceptsEverything` | this property, on that binding — the declaration cannot drift silently in either direction |
     * | Body: replace the impostor's key with the honest one throughout | the preconditions, not the arm |
     */
    @Test
    public fun verifyAnswersForgedTransitionsAsDeclared() {
        val scheme = newScheme()
        val messages = validPlaintexts()
        assertTrue(messages.isNotEmpty(), "validPlaintexts() is empty, so this property asserts nothing")
        val strength = proofStrength()
        val accepts = strength == ProofStrength.AcceptsEverything
        val note = if (accepts) {
            "this scheme DETECTS that cheat — declare ProofStrength.RejectsForgeries and get the credit"
        } else {
            "a scheme that accepts it cannot tell an honest deal from a rigged one"
        }
        assertAll(
            *forgeriesOf(scheme, messages.first()).map { (what, accepted) ->
                {
                    assertEquals(
                        accepts,
                        accepted,
                        "declared $strength, yet $what was ${if (accepted) "ACCEPTED" else "REJECTED"} — $note",
                    )
                }
            }.toTypedArray(),
        )
    }

    /**
     * Six transitions the scheme did not produce, each paired with the verdict its own `verify*`
     * returned — and each proved to be a forgery first.
     *
     * The preconditions run before any verdict is read, and every one of them says the same thing
     * in a different place: *the value being passed off differs from the value that key really
     * produces*. Drop one and the corresponding row stops being adversarial while still reading as
     * if it were.
     */
    private fun forgeriesOf(scheme: CommutativeScheme, m: ByteArray): List<Pair<String, Boolean>> {
        val honest = scheme.generateKey()
        val impostor = scheme.generateKey()
        val (cipher, encryptProof) = scheme.encrypt(m, honest.encryptKey)
        val underImpostorsKey = scheme.encrypt(m, impostor.encryptKey).first
        val (stripped, stripProof) = scheme.strip(cipher, honest.stripKey)
        val strippedByImpostor = scheme.strip(cipher, impostor.stripKey).first
        val rigged = "is not a forgery at all on this scheme, so the arm below asserts the opposite of what it reads as:"
        assertAll(
            { assertNotEquals(honest.encryptKey, impostor.encryptKey, "$rigged two generateKey() calls returned the same key") },
            { assertNotEquals(m.toList(), cipher.toList(), "$rigged encrypting left the plaintext unchanged") },
            { assertNotEquals(cipher.toList(), underImpostorsKey.toList(), "$rigged two keys encrypt m identically") },
            { assertNotEquals(cipher.toList(), stripped.toList(), "$rigged stripping left the ciphertext unchanged") },
            { assertNotEquals(stripped.toList(), strippedByImpostor.toList(), "$rigged two keys strip identically") },
        )
        return listOf(
            "an encryption attributed to a key that did not produce it" to
                scheme.verifyEncrypt(m, underImpostorsKey, encryptProof, honest.encryptKey),
            "an encryption that never happened (next == prev — a card pocketed uncovered)" to
                scheme.verifyEncrypt(m, m, encryptProof, honest.encryptKey),
            "an honest encryption re-attributed to another player's public key" to
                scheme.verifyEncrypt(m, cipher, encryptProof, impostor.encryptKey),
            "a strip attributed to a key that did not produce it" to
                scheme.verifyStrip(cipher, strippedByImpostor, stripProof, honest.encryptKey),
            "a strip that never happened (next == prev)" to
                scheme.verifyStrip(cipher, cipher, stripProof, honest.encryptKey),
            "an honest strip re-attributed to another player's public key" to
                scheme.verifyStrip(cipher, stripped, stripProof, impostor.encryptKey),
        )
    }

    /**
     * Asserts no value in [underCover] — every intermediate of a multi-layer deal that still
     * carries at least one layer — is the [plaintext].
     *
     * Non-empty is checked too: the callers build the list in a loop, and a loop that ran zero
     * times would satisfy "none of them is readable" without covering anything.
     */
    private fun assertStayedCovered(underCover: List<ByteArray>, plaintext: ByteArray) {
        assertTrue(underCover.isNotEmpty(), "no intermediate was captured, so this assertion covered nothing")
        underCover.forEachIndexed { step, value ->
            assertNotEquals(
                plaintext.toList(),
                value.toList(),
                "the card was readable at intermediate $step of ${underCover.size}, every one of which still " +
                    "carries at least one unreleased layer — a layer that cancels another is a player " +
                    "reading a card nobody released",
            )
        }
    }

    /**
     * The cross-peer preconditions, checked **eagerly** — a fixture that is not what the property
     * needs must fail as a fixture, before any law is evaluated, rather than surfacing as a
     * mysterious green.
     *
     * Two checks, and neither is redundant. Referential distinctness catches a subclass caching
     * one instance, which would silently re-run the single-instance properties under a cross-peer
     * name. Distinct single-layer ciphertexts catch the subtler one: two genuinely separate
     * objects seeded identically hold identical keys, and on a self-inverse scheme identical
     * layers *cancel*, so both cross-peer properties would pass without ever crossing anything.
     *
     * Hands the single-layer ciphertexts back so a caller can reuse them as the next layer's
     * input instead of paying for them twice — for [encryptionIsCommutativeAcrossPeerInstances]
     * this precondition is therefore free.
     */
    private fun independentSingleLayers(peers: List<SchemePeer>, message: ByteArray): List<ByteArray> {
        for (i in peers.indices) {
            for (j in i + 1 until peers.size) {
                assertNotSame(
                    peers[i].scheme,
                    peers[j].scheme,
                    "newScheme()/newPeerScheme() handed back the SAME instance twice — a cross-peer " +
                        "property over one object is the single-instance property it exists to distinguish " +
                        "itself from. Return a freshly constructed instance from every call.",
                )
            }
        }
        val layers = peers.map { it.encrypt(message) }
        for (i in layers.indices) {
            for (j in i + 1 until layers.size) {
                assertNotEquals(
                    layers[i].toList(),
                    layers[j].toList(),
                    "peers $i and $j encrypted the same message to the same ciphertext, so they hold the " +
                        "same key — seed each instance distinctly, or these properties hold vacuously",
                )
            }
        }
        return layers
    }

    /**
     * Asserts each peer's rig fired: that it applied its own layer exactly as many times as the
     * property intended. Green by absence is the failure mode this closes — a body that routed
     * two layers through one peer would make every ciphertext comparison *more* likely to pass,
     * so the law cannot notice, and only the counts can.
     */
    private fun assertRigCrossed(peers: List<SchemePeer>, expectedEncryptions: Int, expectedStrips: Int) {
        peers.forEachIndexed { index, peer ->
            assertEquals(
                expectedEncryptions to expectedStrips,
                peer.encryptions to peer.strips,
                "peer $index did not apply its own layer the expected number of times — the property " +
                    "did not cross the peers it claims to",
            )
        }
    }
}

/**
 * What a subclass claims its scheme's [CommutativeScheme.verifyEncrypt] /
 * [CommutativeScheme.verifyStrip] pair does with a transition the scheme did not produce — the
 * answer [CommutativeSchemeConformanceSuite.verifyAnswersForgedTransitionsAsDeclared] holds it to.
 *
 * **Sealed and two-armed rather than a nullable forgery fixture.** A nullable hook reads "I cannot
 * reach that state", which is an opt-out, and #2247's finding is that an opt-out relocates the
 * vacuity one level up where it is harder to see. Neither arm here opts out: each asserts the
 * answer its own name promises, over the same six derived forgeries, so a mis-declaration reds in
 * **either** direction and the fixture cannot quietly stop testing anything.
 *
 * Top-level rather than nested so a fixture helper outside a suite subclass can name one, matching
 * `DurabilityFixture` in `:kuilt-bolt`.
 */
public sealed interface ProofStrength {

    /**
     * `verify*` accept every transition, forged or honest — the `return true` stub
     * [CommutativeScheme] permits until real proofs exist, and what both `SraScheme` and
     * [XorKeystreamScheme] do today.
     *
     * **What this arm cannot detect: any cheat whatsoever.** It is not a security property and no
     * amount of green here is evidence a deal is safe against a malicious peer. Its entire value is
     * that the claim is now *written down in the fixture* and *pinned by the build* — a scheme that
     * grows a verifier has to come here and say so, instead of the suite silently continuing to
     * report "honest-transition verification" coverage over a predicate with one branch.
     */
    public data object AcceptsEverything : ProofStrength

    /**
     * `verify*` reject a transition that was not produced by applying the key behind the named
     * `pubKey` to `prev` — the substituted-layer, skipped-layer and framed-peer cheats
     * [CommutativeSchemeConformanceSuite.verifyAnswersForgedTransitionsAsDeclared] derives.
     *
     * **What this arm cannot detect.** It fixes a floor, not soundness: rejecting those six says
     * nothing about a seventh forgery nobody enumerated, and nothing at all about the *proof
     * system* — a scheme is free to satisfy this arm by recomputing the transition from material a
     * real verifier would not hold. Declaring it is a claim about the six, and only the six.
     */
    public data object RejectsForgeries : ProofStrength
}

/**
 * One player: a scheme instance plus the key **that instance minted**, bound together so the pair
 * cannot come apart.
 *
 * The binding is the point. Applying alice's key through bob's scheme is the mis-crossing that
 * passes on any stateless scheme and hides a per-instance one, and it is the shape a cross-peer
 * test body drifts into as it is edited. Here the key is private and never leaves, so
 * [encrypt]/[strip] are the only moves available and the wrong one is not expressible.
 *
 * [encryptions] and [strips] are the rig counters — see
 * [CommutativeSchemeConformanceSuite.assertRigCrossed] for what they refuse.
 *
 * Not thread-safe, and deliberately not: every caller is a single test body on one thread.
 */
private class SchemePeer(val scheme: CommutativeScheme) {

    private val key = scheme.generateKey()

    var encryptions: Int = 0
        private set

    var strips: Int = 0
        private set

    fun encrypt(bytes: ByteArray): ByteArray {
        encryptions++
        return scheme.encrypt(bytes, key.encryptKey).first
    }

    fun strip(bytes: ByteArray): ByteArray {
        strips++
        return scheme.strip(bytes, key.stripKey).first
    }
}
