package us.tractat.kuilt.session

import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion

/**
 * A Hello as a current joiner sends it, for tests that drive the admit wire by hand: the current
 * [ProtocolVersion], a device key and a joiner nonce. Since v3 (#3210) a Hello missing the key or
 * the nonce is refused before any other gate runs, so a raw Hello built without them tests that
 * refusal and nothing else.
 *
 * [key] defaults to a freshly generated one, so every call is a distinct device.
 */
internal suspend fun currentHello(
    displayName: String,
    sessionId: String = "session-$displayName",
    targetRoom: String? = null,
    key: DidKey? = null,
): AdmitMessage.Hello = AdmitMessage.Hello(
    displayName = displayName,
    sessionId = sessionId,
    targetRoom = targetRoom,
    protocolVersion = ProtocolVersion.CURRENT,
    key = key ?: DeviceKey.generate().id,
    joinerNonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE),
)
