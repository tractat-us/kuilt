package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.CommutativeScheme
import us.tractat.kuilt.deal.EncryptProof
import us.tractat.kuilt.deal.SchemeKey
import us.tractat.kuilt.deal.SchemeKeyPair
import us.tractat.kuilt.deal.StripProof
import kotlin.random.Random

/**
 * The binding that makes [ProofStrength.RejectsForgeries] a **reachable** arm rather than a branch
 * of the TCK nothing executes.
 *
 * Without it the suite would ship two arms and run exactly one, which is the shape #2247 is about:
 * a property whose interesting branch no reference implementation can reach stops being tested,
 * and nobody notices because the suite is green. Both schemes in this repo stub `verify*` to
 * `true`, so the rejecting arm has no natural subclass — this one is written on purpose to give it
 * one, and to prove the arm can actually fail (see the mutation receipts on
 * [CommutativeSchemeConformanceSuite.verifyAnswersForgedTransitionsAsDeclared]).
 *
 * It also runs the rest of the suite, so the arm is not the only thing under test: a scheme that
 * verified honestly but broke a commutativity law would red here too.
 */
class RecomputingXorSchemeConformanceTest : CommutativeSchemeConformanceSuite() {

    // One seeded source of sub-seeds, so every instance is deterministic AND distinct — the same
    // reasoning as XorKeystreamSchemeConformanceTest's, and for the same vacuity.
    private val seeder = Random(seed = 0xC0FFEE)

    override fun newScheme(): CommutativeScheme = RecomputingXorScheme(Random(seeder.nextInt()))

    override fun newPeerScheme(): CommutativeScheme = RecomputingXorScheme(Random(seeder.nextInt()))

    override fun proofStrength() = ProofStrength.RejectsForgeries
}

/**
 * A [CommutativeScheme] that answers `verify*` by **recomputing the transition** and comparing —
 * so it accepts an honest one and rejects one it did not produce.
 *
 * **This is a test-only device and not a cryptographic scheme.** It verifies by re-running
 * `encrypt`/`strip` with the key it was handed as `pubKey`, which works only because
 * [XorKeystreamScheme]'s keys are symmetric: the "public" key here is the secret. A real verifier
 * cannot do this — that is the whole reason proofs exist — so nothing about this class is a claim
 * about what a verifying scheme should look like. It exists to give
 * [ProofStrength.RejectsForgeries] a subclass, and it is deliberately the *weakest* implementation
 * that satisfies the arm: if the suite's forgeries can be defeated by naive recomputation, they are
 * not asking for anything a real verifier would find hard either.
 *
 * It also demonstrates why a garbled-proof forgery is absent from the suite: this scheme ignores
 * the proof bytes entirely and is still correct by the [CommutativeScheme] contract, so "rejects a
 * garbage proof" is not something the TCK may demand.
 *
 * Delegation rather than subclassing so `encrypt`/`strip` are provably the same code path the
 * verifier recomputes — a hand-written second copy could drift and make verification agree with
 * itself rather than with the scheme.
 */
private class RecomputingXorScheme(random: Random) : CommutativeScheme {

    private val delegate = XorKeystreamScheme(random)

    override fun encrypt(plaintext: ByteArray, key: SchemeKey): Pair<ByteArray, EncryptProof> =
        delegate.encrypt(plaintext, key)

    override fun strip(ciphertext: ByteArray, key: SchemeKey): Pair<ByteArray, StripProof> =
        delegate.strip(ciphertext, key)

    override fun verifyEncrypt(prev: ByteArray, next: ByteArray, proof: EncryptProof, pubKey: SchemeKey): Boolean =
        delegate.encrypt(prev, pubKey).first.contentEquals(next)

    override fun verifyStrip(prev: ByteArray, next: ByteArray, proof: StripProof, pubKey: SchemeKey): Boolean =
        delegate.strip(prev, pubKey).first.contentEquals(next)

    override fun generateKey(): SchemeKeyPair = delegate.generateKey()
}
