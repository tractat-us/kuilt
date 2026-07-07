;; largeinit.wat — the over-cap initial-memory load-guard vector: 32 initial pages,
;; exceeding the default 16-page sandbox cap. Every impl must reject on the INITIAL
;; size (checked before the max/no-max guards), so the missing max never masks it.
;;
;; To reproduce the OVERSIZE_INITIAL_MEMORY bytes in WasmKernelFixtures.kt:
;;   wat2wasm largeinit.wat -o largeinit.wasm
(module
  (memory (export "memory") 32)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
