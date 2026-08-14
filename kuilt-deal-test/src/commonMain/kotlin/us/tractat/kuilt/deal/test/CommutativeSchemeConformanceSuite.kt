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
 * this suite and overriding [newScheme] and [newPeerScheme]:
 *
 * ```kotlin
 * class MySchemeConformanceTest : CommutativeSchemeConformanceSuite() {
 *     override fun newScheme() = MyScheme()
 *     override fun newPeerScheme() = MyScheme()
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

    /** Sample messages guaranteed to lie in the scheme's valid input domain. */
    public open fun validPlaintexts(): List<ByteArray> = listOf(
        "card:ACE_OF_SPADES".encodeToByteArray(),
        "card:KING_OF_HEARTS".encodeToByteArray(),
        "7".encodeToByteArray(),
    )

    @Test
    public fun encryptThenStripRecoversPlaintext() {
        val scheme = newScheme()
        val key = scheme.generateKey()
        for (m in validPlaintexts()) {
            val (cipher, _) = scheme.encrypt(m, key.encryptKey)
            val (recovered, _) = scheme.strip(cipher, key.stripKey)
            assertEquals(m.toList(), recovered.toList(), "round-trip failed for ${m.toList()}")
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
        for (k in keys) cipher = scheme.encrypt(cipher, k.encryptKey).first
        // Strip in a fully deranged order (k2, k0, k1) — no layer in its encryption
        // position — so commutativity is genuinely exercised.
        for (k in listOf(keys[2], keys[0], keys[1])) cipher = scheme.strip(cipher, k.stripKey).first
        assertEquals(m.toList(), cipher.toList(), "multi-layer recovery failed for ${m.toList()}")
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
     *    counts are the only thing that reds on it.
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
        for (peer in peers) cipher = peer.encrypt(cipher)
        // Strip in a fully deranged order (peer2, peer0, peer1) — no layer in its encryption
        // position — again each by its own instance, which is what every player does for real.
        for (peer in listOf(peers[2], peers[0], peers[1])) cipher = peer.strip(cipher)
        val recovered = cipher
        assertAll(
            {
                assertEquals(
                    m.toList(),
                    recovered.toList(),
                    "cross-peer multi-layer recovery failed for ${m.toList()}",
                )
            },
            { assertRigCrossed(peers, expectedEncryptions = 2, expectedStrips = 1) },
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

    @Test
    public fun generatedKeyPairsAreUsable() {
        val scheme = newScheme()
        // Two independently generated pairs — enough to show generateKey() yields
        // usable, distinct pairs. (Kept low so a heavyweight scheme's key generation
        // stays within the wasmJs 2s test budget.)
        repeat(2) {
            val key = scheme.generateKey()
            val m = validPlaintexts().first()
            val (cipher, _) = scheme.encrypt(m, key.encryptKey)
            val (recovered, _) = scheme.strip(cipher, key.stripKey)
            assertTrue(m.contentEquals(recovered), "generated key pair failed to round-trip")
        }
    }

    /**
     * Honest-path verification: an [CommutativeScheme.encrypt]/[CommutativeScheme.strip]
     * transition that the scheme itself produced must verify. Current schemes stub the
     * verify methods to `true`, so this pins the baseline a future (e.g. ZK-proof)
     * implementation must also satisfy — honest transitions are always accepted.
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
