# Module kuilt-warp-test

Warp's published test infrastructure: the sandboxed-WASM conformance TCK and the
multi-node simulation harness.

`MultiNodeWarpSim` (entry point `warpSimTest`) stands up several coordination-free
`WarpNode`s over an in-memory mesh under deterministic virtual time —
`StandardTestDispatcher`, a tight test timeout, bounded `settle()`/`await*` helpers
(never `advanceUntilIdle()`, which spins forever on re-arming anti-entropy timers),
and a `dumpState()` that turns non-convergence into a fast, legible failure. Reach
for it in any test that runs more than one `WarpNode`; hand-rolled dispatcher and
settle-loop choices are how the load-dependent multi-node flakes happened.

The rest of the module is the conformance TCK for warp's sandboxed WASM execution contract.

Every `WasmRuntime` implementation — on any platform — must keep untrusted compute kernels
inside the same safety envelope: no host capabilities, bounded memory, bounded CPU time, and
guest-controlled values never trusted. This module ships that envelope as a subclassable test
suite, so each implementation proves it mechanically instead of re-asserting it by hand.

`WasmRuntimeConformanceSuite` is the suite: subclass it in an implementation's tests and
override `newRuntime`. `WasmKernelFixtures` is the shared arsenal of malicious and well-behaved
WASM kernels the suite loads — identical bytes on every target (JVM, Android, Apple, browser),
embedded as literals because only the JVM has classpath resources. The `.wat` sources live in
this module's `wat/` directory.
