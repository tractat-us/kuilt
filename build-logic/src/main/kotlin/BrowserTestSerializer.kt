import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Build-scoped mutex that serializes Kotlin/Wasm browser-test tasks across all
 * modules. With `org.gradle.parallel=true` + `org.gradle.workers.max=16`,
 * Gradle otherwise schedules every module's `wasmJsBrowserTest` concurrently —
 * and on a 4-vCPU runner, three Karma instances racing to launch ChromeHeadless
 * blow past Karma's default 60s capture timeout. `maxParallelUsages = 1`
 * enforces one browser-test process at a time without throttling unrelated
 * compile/test parallelism.
 */
abstract class BrowserTestSerializer : BuildService<BuildServiceParameters.None>
