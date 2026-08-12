package us.tractat.kuilt.test

/**
 * Runs every assertion, collecting all failures instead of stopping at the first.
 * If any assertion throws [AssertionError], rethrows a single [AssertionError]
 * aggregating every failure message. Non-assertion throwables propagate immediately.
 *
 * **The trap that costs you the diagnosis (#1823):** "non-assertion throwable" is not
 * an exotic case — it is `single()`, `first()`, `last()`, `getValue()`, `!!`, or `[i]`
 * on the very collection under test. Those throw [NoSuchElementException] /
 * [IndexOutOfBoundsException] exactly when the collection is empty or short, which is
 * exactly the broken state you were trying to diagnose. The throw aborts the whole
 * `assertAll`, so every later assertion is skipped **and every failure already collected
 * before it is discarded** — you are shown `NoSuchElementException: List is empty.`
 * instead of the named failures you wrote.
 *
 * Write the assertion list-shaped instead, so an empty or short collection is a named
 * [AssertionError] printing both sides:
 *
 * ```kotlin
 * // masks its siblings when `received` is empty:
 * assertAll(
 *     { assertEquals(1, received.size, "one frame delivered") },
 *     { assertEquals(origin, received.single().sender) },
 * )
 * // total — reports both, whatever `received` holds:
 * assertAll(
 *     { assertEquals(1, received.size, "one frame delivered") },
 *     { assertEquals(listOf(origin), received.map { it.sender }) },
 * )
 * ```
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
