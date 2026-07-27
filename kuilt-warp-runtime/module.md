# Module kuilt-warp-runtime

The part that actually *runs* code somebody else sent you.

`:kuilt-warp` can move a small program between peers — fetch it, cache it, hand it to the
peer that should do the work. This module is what that peer runs it *with*: a small, walled-off
engine that executes the program and lets it touch nothing except the bytes you handed in and
the bytes it hands back. No files, no network, no clock, no way to run forever.

You add this module only when you send real code. A peer that just dispatches named jobs
depends on `:kuilt-warp` alone and never pulls any of this weight in.

## One engine per place a peer can live

The program is the same bytes everywhere; the engine that runs it is not, because a laptop,
an iPhone and a browser tab each offer something different.

| Where the peer runs | Implementation | Engine |
|---|---|---|
| JVM / Android server | `ChicoryWasmRuntime` | Chicory, a pure-JVM WebAssembly interpreter |
| iOS / macOS | `Wasm3WasmRuntime` | wasm3, a C interpreter compiled from vendored source |
| Browser (wasmJs) | `BrowserWasmRuntime` | the browser's own WebAssembly engine, inside a Web Worker |

All three implement the single `WasmRuntime` contract from `:kuilt-warp` and are held to it by
the same test suite (below) running the same bytes — so a kernel that is safe on one is safe on
all of them.

## What "walled off" actually means

Four guarantees, enforced at load time and at run time, because kernels arrive from peers you
do not control:

- **No capabilities.** A module that declares *any* import is rejected outright. There is no
  host function to call, so there is nothing to escape into.
- **A bounded memory ceiling.** A module must declare a maximum linear-memory size, and it must
  be within the configured cap. "No maximum" is rejected — it is the memory bomb — and the
  engine then enforces the declared maximum itself when the guest tries to grow.
- **A bounded run time.** A kernel that spins forever is cut off at
  `WasmSandboxConfig.executionTimeout`. The mechanism differs per engine — the JVM interpreter
  is interrupted, wasm3 polls a deadline the vendored source is patched to check on every
  backward branch, the browser's worker is `terminate()`d — but the promise does not.
- **No raw engine errors escape.** Every rejection and every fault surfaces as `WasmLoadException`
  or `WasmExecutionException`. This matters more than it looks: a raw engine error escaping
  `load` would sidestep the caller's terminal-error handling and make a single broken kernel
  trigger an endless retry storm across the mesh.

Guest-controlled pointers and lengths coming back over the warp ABI are decoded through
`:kuilt-warp`'s shared unsigned decoder on every target, so a high-bit value is bounds-rejected
rather than sign-wrapped into host memory.

## One contract, one test suite

`WasmRuntimeConformanceSuite` (in `:kuilt-warp-test`) is the whole safety contract as executable
tests, driven by byte-identical kernel fixtures. Each engine binds to it in a dozen lines —
`ChicoryWasmRuntimeConformanceTest`, `Wasm3WasmRuntimeConformanceTest`,
`BrowserWasmRuntimeConformanceTest` — with no overrides and nothing skipped, so all three are
verified to exactly the same depth. A fourth engine would be added the same way.

## The honest seam

**iOS interprets; it never compiles.** Apple forbids executing machine code that arrived from
somewhere else, so `Wasm3WasmRuntime` is an interpreter by necessity, not by omission — and
that is why wasm3 (pure C99, no JIT) is the right engine for every Apple target from one source
tree. A compiler node elsewhere on the mesh can still send iOS a *leaner WebAssembly* variant,
and iOS will interpret that faster copy; what it can never receive is native code. The ceiling
is Apple's, and it is permanent.

The wasm3 static library is compiled from the source vendored in this module rather than shipped
as a committed binary, so the bytes packed into the published klib always come from source you
can read in this repository. Building it needs a macOS host with Xcode — which is already
required for these targets, since Kotlin/Native disables the cinterop-bearing Apple compilations
elsewhere.
