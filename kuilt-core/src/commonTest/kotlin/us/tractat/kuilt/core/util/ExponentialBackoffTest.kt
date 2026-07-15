package us.tractat.kuilt.core.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import us.tractat.kuilt.test.assertAll

class ExponentialBackoffTest {

    @Test
    fun fullJitterStaysWithinTheGrowingCeilingAndIsCapped() {
        val backoff = ExponentialBackoff(base = 100.milliseconds, cap = 10.seconds, factor = 2.0, random = Random(42))
        assertAll(
            { assertTrue((0..999).all { backoff.delay(0) < 100.milliseconds }) },            // ceiling 100ms
            { assertTrue((0..999).all { backoff.delay(6) < 6_400.milliseconds }) },          // ceiling 100ms·2^6 = 6.4s
            { assertTrue((0..999).all { backoff.delay(100) < 10.seconds }) },                // clamps to cap; no overflow/NaN
            { assertTrue((0..999).all { backoff.delay(it % 8) >= kotlin.time.Duration.ZERO }) }, // never negative
        )
    }

    @Test
    fun deterministicUnderASeededRandom() {
        val a = ExponentialBackoff(100.milliseconds, 10.seconds, random = Random(7))
        val b = ExponentialBackoff(100.milliseconds, 10.seconds, random = Random(7))
        assertTrue((0..20).all { a.delay(it) == b.delay(it) })
    }
}
