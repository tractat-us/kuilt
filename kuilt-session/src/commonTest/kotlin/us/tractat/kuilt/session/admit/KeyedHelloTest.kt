package us.tractat.kuilt.session.admit

import us.tractat.kuilt.session.TEST_KEY_A
import us.tractat.kuilt.session.currentHello
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [keyed] is the one place a [AdmitMessage.Hello]'s optional key and nonce become required (#3210):
 * past the host's gate, admission holds a [KeyedHello], whose key and nonce are not nullable, so no
 * later step has to check them again.
 */
class KeyedHelloTest {

    @Test
    fun `a hello with both a key and a nonce is keyed and keeps both`() {
        val hello = currentHello("Ada").copy(joinerNonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { 7 })
        val keyed = assertNotNull(hello.keyed())
        assertAll(
            { assertSame(hello, keyed.hello) },
            { assertEquals(TEST_KEY_A, keyed.key) },
            { assertContentEquals(ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { 7 }, keyed.joinerNonce) },
        )
    }

    @Test
    fun `a hello missing its key or its nonce is not keyed`() {
        val hello = currentHello("Ada")
        assertAll(
            // Control: the same Hello with both fields is keyed, so the two nulls below are the fields'.
            { assertNotNull(hello.keyed()) },
            { assertNull(hello.copy(key = null).keyed(), "a hello without a key") },
            { assertNull(hello.copy(joinerNonce = null).keyed(), "a hello without a nonce") },
        )
    }
}
