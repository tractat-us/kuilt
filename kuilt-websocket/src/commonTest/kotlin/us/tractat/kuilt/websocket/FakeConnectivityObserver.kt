package us.tractat.kuilt.websocket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [ConnectivityObserver] a test can move — the fake twin of `AndroidConnectivityObserver` and
 * `BrowserConnectivityObserver`.
 *
 * **Deliberately able to sit in every state, including the ones that would make an assertion
 * fail.** A fake hardwired to [NetworkReachability.Reachable] would let "the seam reports
 * Available" pass against a seam that ignores the observer entirely, which is the vacuous-
 * enforcement shape: the test would be green with no wiring at all. So [emit] takes any value,
 * `null` included, and the tests assert the seam's capability *changes* across at least two of
 * them rather than merely equalling one.
 *
 * Starts at `null` — "nothing observed yet", which is also what a binding with no observer wired
 * publishes forever — so a test that never calls [emit] exercises the honest-`Unknown` floor.
 */
internal class FakeConnectivityObserver(
    initial: NetworkReachability? = null,
) : ConnectivityObserver {

    private val _reachability = MutableStateFlow(initial)

    override val reachability: StateFlow<NetworkReachability?> = _reachability.asStateFlow()

    /** Publish a reading, exactly as a live platform observer does on a transition. */
    fun emit(reachability: NetworkReachability?) {
        _reachability.value = reachability
    }
}
