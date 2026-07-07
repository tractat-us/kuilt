;; nomemory.wat — the missing-memory load-guard vector: exports the ABI functions but
;; declares no linear memory. The warp ABI marshals args/results through memory, so a
;; memoryless module can never carry a payload; every impl rejects it at load.
;;
;; To reproduce the MISSING_MEMORY bytes in WasmKernelFixtures.kt:
;;   wat2wasm nomemory.wat -o nomemory.wasm
(module
  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
