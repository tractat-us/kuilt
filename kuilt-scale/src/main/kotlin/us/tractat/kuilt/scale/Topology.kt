package us.tractat.kuilt.scale

/**
 * Graph topology for the in-memory mesh builder.
 *
 * Each topology determines which (i, j) peer pairs are wired by a direct connection.
 * The mesh builder iterates over pairs from [edges] and creates one
 * [us.tractat.kuilt.test.fabric.connectionPair] per edge.
 */
public sealed interface Topology {

    /**
     * Returns the set of directed edges (i, j) where i < j.
     * The builder creates one connection pair per edge.
     */
    public fun edges(n: Int): List<Pair<Int, Int>>

    /**
     * Complete graph (K_n): every peer is connected to every other peer.
     * Edge count: n*(n-1)/2. Highest connectivity, highest message complexity.
     */
    public data object Complete : Topology {
        override fun edges(n: Int): List<Pair<Int, Int>> = buildList {
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    add(i to j)
                }
            }
        }
    }

    /**
     * Ring (Cn): each peer is connected to the next, wrapping around.
     * Edge count: n. Lowest connectivity; high diameter.
     * Drop-in for partial-mesh comparison experiments.
     */
    public data object Ring : Topology {
        override fun edges(n: Int): List<Pair<Int, Int>> = buildList {
            for (i in 0 until n) {
                val j = (i + 1) % n
                add(minOf(i, j) to maxOf(i, j))
            }
        }
    }

    /**
     * Star: one hub peer (index 0) connected to every spoke.
     * Edge count: n-1. Models a relay topology.
     */
    public data object Star : Topology {
        override fun edges(n: Int): List<Pair<Int, Int>> = buildList {
            for (j in 1 until n) {
                add(0 to j)
            }
        }
    }
}
