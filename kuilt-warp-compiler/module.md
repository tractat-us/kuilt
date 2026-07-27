# Module kuilt-warp-compiler

The real optimizer a warp **compiler node** runs. A compiler node is a peer that
takes a program someone else wrote, makes it leaner and faster, and shares the
improved version back — so every other peer runs the better copy without doing
the work itself.

Concretely, this module implements the `WasmOptimizer` seam from `:kuilt-warp`
with `BinaryenWasmOptimizer`: it hands a raw WebAssembly kernel to the
industry-standard Binaryen `wasm-opt` tool and returns a smaller, still-runnable
module with the warp `warp_alloc`/`warp_run` calling convention preserved. The
`wasm-opt` binary is downloaded from Binaryen's official release (version-pinned
and SHA-256-verified) at build time — no binaries in git, no toolchain to
install.

## Adding it to your build

`wasm-opt` is a native executable, so there is no one jar that runs everywhere.
The module's main artifact carries no binary; each supported host is published
as its own classified companion jar. Take the main artifact, then add exactly
the one matching the machine your compiler node runs on:

```kotlin
implementation("us.tractat.kuilt:kuilt-warp-compiler:<version>")
runtimeOnly("us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:macos-arm64")
```

The published classifiers are `macos-arm64`, `macos-x86_64`, `linux-x86_64` and
`linux-aarch64`. Adding none of them — or the wrong one — is not a silent
degradation: the first optimization fails with a `WasmOptimizationException`
naming the exact coordinate to add.

Only a peer that *wants to be* a compiler node depends on this module; the
Binaryen weight falls on those operators alone. It is a JVM/server module —
iOS, browser, and other native peers are pure consumers of the optimized
variant and never run `wasm-opt` themselves.
