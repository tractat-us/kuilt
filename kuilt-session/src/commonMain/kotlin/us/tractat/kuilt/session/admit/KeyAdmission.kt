@file:OptIn(IdentityInternalApi::class)

package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.IdentityInternalApi
import us.tractat.kuilt.identity.KeyScheme

/**
 * The two checks of the key-proving exchange (#3211), kept out of `SeamRoom` so the room grows only
 * by its call sites:
 *
 * ```
 * joiner                                         host
 *   Hello(key, joinerNonce, …)            ─▶
 *                                          ◀─  Challenge(signed by the host's key)
 *   answerChallenge: check it, sign a proof
 *   Proof(signed by the joiner's key)     ─▶
 *                                              checkProof: against the pending challenge
 *                                          ◀─  Welcome   or   Reject(BadProof)
 * ```
 *
 * Both are pure apart from [KeyScheme]: they read no room state and send nothing. A scheme that
 * cannot run on this platform throws `IllegalStateException` out of either; a bad signature is never
 * a throw.
 */
internal object KeyAdmission {

    /**
     * The wire form of the challenge [host] signs for [joiner]'s Hello, which sent [joinerNonce]:
     * this room ([roomId]), this host's peer id ([hostPeerId]), and the fresh [hostNonce] the proof
     * must sign over.
     *
     * @throws IllegalArgumentException when a string cannot be signed (an unpaired surrogate, or
     *   longer than a layout field).
     * @throws IllegalStateException when [host] cannot sign here.
     */
    suspend fun signChallenge(
        host: DeviceKey,
        roomId: String,
        hostPeerId: PeerId,
        joiner: PeerId,
        joinerNonce: ByteArray,
        hostNonce: ByteArray,
    ): ByteArray = Challenge.sign(host, roomId, hostPeerId.value, joiner.value, joinerNonce, hostNonce).wire

    /** Why a [checkProof] refused a proof. Logged, never sent: the joiner hears only `BadProof`. */
    internal enum class ProofFault {
        Undecodable,
        WrongRoom,
        WrongHostKey,
        WrongHostPeer,
        WrongJoinerPeer,
        WrongNonce,
        WrongDeviceId,
        BadSignature,
    }

    /**
     * Whether [statement] is the proof the pending challenge asked for: `null` when it is, else what
     * is wrong with it. The proof must name this room ([roomId]), this host by its key ([hostKey]) and
     * peer id ([hostPeerId]), the fabric [sender] it arrived from as the joiner, the device id the
     * joiner's Hello sent (`""` for none, as [AdmissionProof.sign] writes it), and the nonce this host
     * chose for [pending]; and it must be signed by [joinerKey], the key that Hello named.
     *
     * The signature is checked last, so a proof that is wrong in any field costs no verify.
     */
    suspend fun checkProof(
        statement: ByteArray,
        pending: PendingChallenges.Pending,
        joinerKey: DidKey,
        roomId: String,
        hostKey: DidKey,
        hostPeerId: PeerId,
        sender: PeerId,
        scheme: KeyScheme,
    ): ProofFault? {
        val proof = AdmissionProof.decode(statement) ?: return ProofFault.Undecodable
        return when {
            proof.roomId != roomId -> ProofFault.WrongRoom
            proof.hostKey != hostKey -> ProofFault.WrongHostKey
            proof.hostPeerId != hostPeerId.value -> ProofFault.WrongHostPeer
            proof.joinerPeerId != sender.value -> ProofFault.WrongJoinerPeer
            !proof.hostNonce.contentEquals(pending.hostNonce) -> ProofFault.WrongNonce
            proof.deviceId != pending.hello.deviceId.orEmpty() -> ProofFault.WrongDeviceId
            !proof.isSignedBy(joinerKey, scheme) -> ProofFault.BadSignature
            else -> null
        }
    }

    /** A challenge [answerChallenge] accepted: the key the host proved, and the proof answering it. */
    internal class Answer(val hostKey: DidKey, val proof: AdmissionProof)

    /**
     * The joiner's answer to the challenge in [statement] from the fabric [sender], or `null` when it
     * is not one to answer: it does not decode, it names another joiner than [selfId], it does not echo
     * [latestNonce] (the nonce of this joiner's latest Hello, `null` before the first), or it is not
     * signed by the key it names.
     *
     * The proof names [sender] as the host's peer id — never the id the challenge carries — so a
     * challenge forwarded from somewhere else proves nothing to the host it was forwarded to. Every
     * challenge is answered on its own merits: on a flat fabric a co-joiner's stray challenge is
     * answered too, and costs the real host's nothing.
     *
     * @throws IllegalArgumentException when [deviceId] cannot be signed (an unpaired surrogate, or
     *   longer than a layout field).
     */
    suspend fun answerChallenge(
        statement: ByteArray,
        latestNonce: ByteArray?,
        selfId: PeerId,
        sender: PeerId,
        scheme: KeyScheme,
        joiner: DeviceKey,
        deviceId: String?,
    ): Answer? {
        val challenge = Challenge.decode(statement) ?: return null
        if (latestNonce == null || !challenge.joinerNonce.contentEquals(latestNonce)) return null
        if (challenge.joinerPeerId != selfId.value) return null
        if (!challenge.isSignedByHost(scheme)) return null
        val proof = AdmissionProof.sign(
            joiner = joiner,
            roomId = challenge.roomId,
            hostKey = challenge.hostKey,
            hostPeerId = sender.value,
            joinerPeerId = selfId.value,
            deviceId = deviceId,
            hostNonce = challenge.hostNonce,
        )
        return Answer(challenge.hostKey, proof)
    }
}
