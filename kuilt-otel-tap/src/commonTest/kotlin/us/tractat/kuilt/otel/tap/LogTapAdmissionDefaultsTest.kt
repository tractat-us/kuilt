package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.otel.tap.admit.cryptoRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertSame

class LogTapAdmissionDefaultsTest {
    @Test
    fun presentThatOmitsRandomGetsTheCryptoSource() {
        assertSame(cryptoRandom(), LogTapAdmission.Present("CODE").random)
    }

    @Test
    fun presentStillTakesAnInjectedSource() {
        val seeded = Random(11)
        assertSame(seeded, LogTapAdmission.Present("CODE", seeded).random)
    }
}
