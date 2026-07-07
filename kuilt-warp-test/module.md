# Module kuilt-warp-test

The conformance TCK for warp's sandboxed WASM execution contract.

Every `WasmRuntime` implementation — on any platform — must keep untrusted compute kernels
inside the same safety envelope: no host capabilities, bounded memory, bounded CPU time, and
guest-controlled values never trusted. This module ships that envelope as a subclassable test
suite, so each implementation proves it mechanically instead of re-asserting it by hand.

`WasmRuntimeConformanceSuite` is the suite: subclass it in an implementation's tests and
override `newRuntime`. `WasmKernelFixtures` is the shared arsenal of malicious and well-behaved
WASM kernels the suite loads — identical bytes on every target (JVM, Android, Apple, browser),
embedded as literals because only the JVM has classpath resources. The `.wat` sources live in
this module's `wat/` directory.
