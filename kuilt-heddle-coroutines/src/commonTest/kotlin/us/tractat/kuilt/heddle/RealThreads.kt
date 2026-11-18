package us.tractat.kuilt.heddle

/**
 * Whether `Dispatchers.Default` runs coroutines on more than one thread here: `true` on the JVM,
 * Android and Kotlin/Native, `false` on wasmJs, where it is the browser's single event loop.
 *
 * A probe reads it to decide what it can *assert*, never whether it runs: a test that skipped itself
 * at runtime would be a green row that did nothing (`forbidRuntimeSelfSkippingProbe`).
 */
internal expect val runsOnRealThreads: Boolean
