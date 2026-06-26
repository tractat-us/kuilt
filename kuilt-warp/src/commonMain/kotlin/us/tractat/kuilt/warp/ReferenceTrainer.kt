package us.tractat.kuilt.warp

/**
 * The local training step in pure Kotlin: one gradient-descent step of linear regression
 * (`y ≈ w·x + b`) over a batch, mean-squared-error loss. This is the **oracle** the wasm kernel
 * (`fedavg_train.wasm`) is proven bit-for-bit equal to (see `FedAvgKernelEquivalenceTest`).
 *
 * Dimension is fixed at D = 2 (one feature + bias) to match the v1 kernel; [weights] is
 * `[featureWeight, bias]`.
 */
public object ReferenceTrainer {
    /**
     * One GD step. [examples] are `(x, y)` pairs. Returns the updated `[w0', b']`.
     *
     * Operation order is load-bearing: it is replicated verbatim in `fedavg_train.wat`, so the
     * two produce bit-identical f64 results on the JVM.
     */
    public fun step(
        weights: List<Double>,
        examples: List<Pair<Double, Double>>,
        learnRate: Double,
    ): List<Double> {
        val w0 = weights[0]
        val b = weights[1]
        var gradW0 = 0.0
        var gradB = 0.0
        for ((x, y) in examples) {
            val err = w0 * x + b - y
            gradW0 += err * x
            gradB += err
        }
        val scale = 2.0 / examples.size.toDouble()
        return listOf(
            w0 - learnRate * (scale * gradW0),
            b - learnRate * (scale * gradB),
        )
    }
}
