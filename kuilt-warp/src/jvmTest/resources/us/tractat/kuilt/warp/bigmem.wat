;; bigmem.wat — declares memory with max 64 pages (> default 16-page sandbox cap).
;;
;; Exports the warp ABI (memory, warp_alloc, warp_run). The explicit `(memory 1 64)`
;; declares a maximum of 64 pages, which exceeds the WasmSandboxConfig.maxMemoryPages
;; default of 16 and triggers the memory-cap guard in ChicoryWasmRuntime.load(),
;; surfacing as WasmLoadException.
;;
;; To reproduce bigmem.wasm:
;;   /opt/homebrew/bin/wat2wasm bigmem.wat -o bigmem.wasm
;;
(module
  (memory (export "memory") 1 64)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
