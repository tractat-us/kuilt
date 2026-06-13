package us.tractat.kuilt.test

/**
 * Runs every assertion, collecting all failures instead of stopping at the first.
 * If any assertion throws [AssertionError], rethrows a single [AssertionError]
 * aggregating every failure message. Non-assertion throwables propagate immediately.
 */
public fun assertAll(vararg assertions: () -> Unit) {
    val failures = buildList {
        for (assertion in assertions) {
            try {
                assertion()
            } catch (e: AssertionError) {
                add(e)
            }
        }
    }
    if (failures.isNotEmpty()) {
        val message = failures.joinToString(prefix = "${failures.size} assertion(s) failed:\n", separator = "\n") {
            "  - ${it.message ?: it.toString()}"
        }
        throw AssertionError(message)
    }
}
