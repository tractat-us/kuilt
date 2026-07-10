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
and checksum-verified) at build time and bundled as a resource — no binaries in
git, no toolchain to install.

Only a peer that *wants to be* a compiler node depends on this module; the
Binaryen weight falls on those operators alone. It is a JVM/server module —
iOS, browser, and other native peers are pure consumers of the optimized
variant and never run `wasm-opt` themselves.
