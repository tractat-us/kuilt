@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion

/**
 * `AdmitMessage.encode(Hello("Ada", "s-1", targetRoom = "room-7", protocolVersion = 2))` as hex: the
 * wire a v2 joiner sends, captured from the encoder before v3 added the device key and the joiner
 * nonce (#3210). A test standing in for a v2 joiner sends these bytes rather than encoding a Hello
 * itself, since this build's encoder is the thing a v2 peer does not run.
 */
internal const val GOLDEN_V2_HELLO_HEX: String =
    "619f6568656c6c6fbf6b646973706c61794e616d65634164616973657373696f6e496463732d316a746172676574526f6f6d" +
        "66726f6f6d2d376f70726f746f636f6c56657273696f6e02ffff"

/**
 * Two device keys for tests that write a key into a hand-built frame: fake keys, named without
 * suspending, so a test on wasmJs never waits on WebCrypto for one (#3280). A frame on the wire gets
 * [DidKey]'s syntax check only, which these pass.
 *
 * They are numbered far from the low numbers a test gives the stores of its rooms, so a hand-built
 * joiner never names a key a room in the same test holds.
 */
internal val TEST_KEY_A: DidKey = fakeDidKey(100)

/** The second of the two; see [TEST_KEY_A]. */
internal val TEST_KEY_B: DidKey = fakeDidKey(101)

/** The device whose key is [TEST_KEY_A], for a hand-built peer that must sign with it (#3211). */
internal val TEST_DEVICE_A: DeviceKey = fakeDeviceKey(100)

/** The device whose key is [TEST_KEY_B]; see [TEST_DEVICE_A]. */
internal val TEST_DEVICE_B: DeviceKey = fakeDeviceKey(101)

/**
 * A Hello as a current joiner sends it, for tests that drive the admit wire by hand: the current
 * [ProtocolVersion], a device key and a joiner nonce. Since v3 (#3210) a Hello missing the key or
 * the nonce is refused before any other gate runs, so a raw Hello built without them tests that
 * refusal and nothing else.
 *
 * [key] defaults to [TEST_KEY_A]. Nothing reads keys at admission yet, but a test admitting two of
 * these as two devices passes [TEST_KEY_B] to the second.
 */
internal fun currentHello(
    displayName: String,
    sessionId: String = "session-$displayName",
    targetRoom: String? = null,
    key: DidKey = TEST_KEY_A,
): AdmitMessage.Hello = AdmitMessage.Hello(
    displayName = displayName,
    sessionId = sessionId,
    targetRoom = targetRoom,
    protocolVersion = ProtocolVersion.CURRENT,
    key = key,
    joinerNonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE),
)

/**
 * The challenge a hand-built host at [hostPeerId] answers [hello] with, signed by [host]: what a
 * hand-built host sends a joiner room before any `Welcome`, since since v4 (#3211) a joiner accepts a
 * `Welcome` only from a sender whose challenge it verified, and a host intro only when it names
 * [host]'s key. [joiner] is the joiner room's peer id as the host sees it.
 */
internal suspend fun handChallenge(
    hello: AdmitMessage.Hello,
    joiner: PeerId,
    hostPeerId: PeerId,
    host: DeviceKey = TEST_DEVICE_B,
    roomId: String = "",
): AdmitMessage.Challenge = AdmitMessage.Challenge(
    Challenge.sign(
        host,
        roomId,
        hostPeerId.value,
        joiner.value,
        checkNotNull(hello.joinerNonce) { "a current Hello carries a nonce" },
        ByteArray(Challenge.NONCE_SIZE) { 7 },
    ).wire,
)

/**
 * The proof a hand-built joiner signs with [device] to answer [challenge] from [hostPeerId], as a v4
 * joiner must before a host admits it (#3211). [device]'s key must be the one the joiner's Hello named.
 */
internal suspend fun handProof(
    challenge: AdmitMessage.Challenge,
    hostPeerId: PeerId,
    device: DeviceKey = TEST_DEVICE_A,
    deviceId: String? = null,
): AdmitMessage.Proof {
    val decoded = checkNotNull(Challenge.decode(challenge.statement)) { "not a challenge" }
    val proof = AdmissionProof.sign(
        device, decoded.roomId, decoded.hostKey, hostPeerId.value, decoded.joinerPeerId, deviceId, decoded.hostNonce,
    )
    return AdmitMessage.Proof(proof.wire)
}

/**
 * A hand-built joiner on [this] raw seam says [hello] and answers the host's challenge, which is what
 * it takes to be admitted since v4 (#3211). [device] signs, so its key must be the one [hello] names
 * (by default [TEST_DEVICE_A], whose key is [currentHello]'s default). **Collects [incoming] up to the
 * challenge**, so a caller that collects [incoming] itself afterwards must be on a seam that allows it.
 */
internal suspend fun Seam.helloAndProve(hello: AdmitMessage.Hello, device: DeviceKey = TEST_DEVICE_A) {
    check(hello.key == device.id) { "the Hello names ${hello.key}, but ${device.id} would sign" }
    broadcast(AdmitMessage.encode(hello))
    val swatch = incoming.first { AdmitMessage.decode(it.toByteArray()) is AdmitMessage.Challenge }
    val host = checkNotNull(swatch.sender) { "a challenge arrives from its host" }
    val challenge = AdmitMessage.decode(swatch.toByteArray()) as AdmitMessage.Challenge
    sendTo(host, AdmitMessage.encode(handProof(challenge, host, device, hello.deviceId)))
}
