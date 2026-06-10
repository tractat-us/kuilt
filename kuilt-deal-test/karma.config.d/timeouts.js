// Raise Karma's socket/activity timeouts. :kuilt-deal-test runs the SRA
// conformance TCK on wasmJs, where 2048-bit modular exponentiation is pure
// interpreted big-integer math (no JIT) and blocks the browser's single JS
// thread for several seconds per test. The default ~2s ping timeout then
// triggers a spurious "disconnect" on slower CI runners. These generous
// timeouts let the (correct, just slow) crypto tests complete.
config.set({
    pingTimeout: 120000,
    browserNoActivityTimeout: 120000,
    browserDisconnectTimeout: 120000,
    browserDisconnectTolerance: 3,
    captureTimeout: 120000,
});

// Raise the per-test limit, which is Mocha's (applied by Karma) — the Gradle
// `useMocha { timeout }` DSL does not reach the wasmJs *browser* task, so a single
// 2048-bit keygen/encrypt overruns the 2s default on slow CI. Mutate in place so
// the existing client.args set by the Kotlin test runner is preserved.
config.client = config.client || {};
config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 120000;
