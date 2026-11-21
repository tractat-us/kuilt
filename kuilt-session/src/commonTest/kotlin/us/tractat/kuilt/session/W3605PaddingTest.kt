package us.tractat.kuilt.session

import kotlin.test.Test

// TEMPORARY positive control for #3605 — removed before the PR is marked ready. Pads the wasm
// run's console output by ~200 KB, spread over four tests the way real log volume is, so the
// single-line Karma output passes KGP's 1 MB service-message ceiling on current main.
class W3605PaddingTest {
    private fun pad(tag: String) = repeat(50) { println("w3605 $tag $it " + "x".repeat(1000)) }

    @Test fun padA() = pad("a")

    @Test fun padB() = pad("b")

    @Test fun padC() = pad("c")

    @Test fun padD() = pad("d")
}
