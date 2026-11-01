;; secondmem.wat — the multi-memory load-guard vector: a first memory inside the cap and a
;; second with no max, which warp_run grows by 100 pages. The result length is the old size,
;; so one byte back means the grow succeeded past the default 16-page sandbox cap.
;;
;; To reproduce the SECOND_MEMORY_NO_MAX bytes in WasmKernelFixtures.kt:
;;   wat2wasm --enable-multi-memory secondmem.wat -o secondmem.wasm
(module
  (memory (export "memory") 1 16)
  (memory $second 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i32.const 100
    memory.grow $second
    i64.extend_i32_u
  )
)
