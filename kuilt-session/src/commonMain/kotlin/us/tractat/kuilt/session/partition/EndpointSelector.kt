package us.tractat.kuilt.session.partition

/**
 * Strategy that picks the next endpoint index from a cluster list on each call.
 *
 * The selector receives [size] (the number of endpoints) and returns the index
 * to use as the new current endpoint. The default implementation is
 * [RoundRobinEndpointSelector].
 *
 * Implementations must be thread-safe: [ServerClusterReconnect] calls [next]
 * under an atomicfu lock from potentially concurrent callers.
 */
public fun interface EndpointSelector {
    /**
     * Return the index of the next endpoint to use.
     *
     * Called under the [ServerClusterReconnect] lock — must NOT suspend.
     * @param size total number of endpoints (always ≥ 1).
     * @return index in [0, size).
     */
    public fun next(size: Int): Int
}

/**
 * Deterministic round-robin [EndpointSelector] starting at [startIndex].
 *
 * On each [next] call the index advances by one modulo [size]. This is the
 * default selector used by [ServerClusterReconnect] when none is supplied.
 *
 * @param startIndex the first index returned (default 0).
 */
public class RoundRobinEndpointSelector(
    startIndex: Int = 0,
) : EndpointSelector {
    private var current = startIndex

    override fun next(size: Int): Int {
        val index = current % size
        current = (current + 1) % size
        return index
    }
}
