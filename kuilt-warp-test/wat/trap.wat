;; trap.wat — the trapping run-guard vector: warp_run immediately executes
;; `unreachable`. Every impl must surface the trap as WasmExecutionException.
;; Memory declared `1 1` (explicit max) so it loads past every guard.
;;
;; To reproduce the TRAP bytes in WasmKernelFixtures.kt:
;;   wat2wasm trap.wat -o trap.wasm
(module
  (memory (export "memory") 1 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    unreachable
  )
)
