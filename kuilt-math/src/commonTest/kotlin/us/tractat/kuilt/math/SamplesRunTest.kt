package us.tractat.kuilt.math

import kotlin.test.Test

/** Runs every `@sample`, whose bodies assert what their KDoc claims. */
class SamplesRunTest {

    @Test
    fun checkedLong() = sampleCheckedLong()

    @Test
    fun compareProducts() = sampleCompareProducts()

    @Test
    fun mulDiv() = sampleMulDiv()

    @Test
    fun wideMulDiv() = sampleWideMulDiv()

    @Test
    fun wideSum() = sampleWideSum()
}
