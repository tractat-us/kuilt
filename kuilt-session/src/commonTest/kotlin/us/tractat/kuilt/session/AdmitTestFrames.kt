@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import us.tractat.kuilt.identity.DidKey
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
