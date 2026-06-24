// Raise Karma's socket/activity timeouts. :kuilt-crdt runs cardinality/accuracy
// cases on wasmJs — e.g. HyperLogLogTest.estimateIsWithinErrorBandFor10kDistinctItems
// inserts 10k distinct items, each insert an O(m) lattice join over 16384 packed
// 6-bit registers (pure interpreted wasm, no JIT). That blocks the browser's single
// JS thread for several seconds. The default ~2s ping timeout then triggers a
// spurious "disconnect" on slower CI runners. These generous timeouts let the
// (correct, just slow) accuracy tests complete.
config.set({
    pingTimeout: 120000,
    browserNoActivityTimeout: 120000,
    browserDisconnectTimeout: 120000,
    browserDisconnectTolerance: 3,
    captureTimeout: 120000,
});

// Raise the per-test limit, which is Mocha's (applied by Karma) — the Gradle
// `useMocha { timeout }` DSL does not reach the wasmJs *browser* task, so a single
// 10k-item accuracy run overruns the 2s default on slow CI. Mutate in place so the
// existing client.args set by the Kotlin test runner is preserved.
config.client = config.client || {};
config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 120000;
