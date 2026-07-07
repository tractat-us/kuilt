;; bigmem.wat — the over-cap declared-max load-guard vector: memory max 64 pages,
;; exceeding the default 16-page sandbox cap (WasmSandboxConfig.maxMemoryPages).
;;
;; To reproduce the OVERSIZE_MAX_MEMORY bytes in WasmKernelFixtures.kt:
;;   wat2wasm bigmem.wat -o bigmem.wasm
(module
  (memory (export "memory") 1 64)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
