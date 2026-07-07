;; oobresptr.wat — the high-bit result-pointer run-guard vector: warp_run returns the
;; packed i64 (resPtr = 0x8000_0000, resLen = 4). A host that narrows the guest-
;; controlled pointer to a SIGNED 32-bit int wraps it negative, slips past a naive
;; bounds check, and reads host memory below the linear-memory base — a sandbox-escape
;; OOB read. Every impl must instead surface a bounded WasmExecutionException.
;; Memory declared `1 1` (explicit max) so it loads past every guard.
;;
;; To reproduce the HIGH_BIT_RESULT_POINTER bytes in WasmKernelFixtures.kt:
;;   wat2wasm oobresptr.wat -o oobresptr.wasm
(module
  (memory (export "memory") 1 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    ;; (0x8000_0000 << 32) | 4
    i64.const 0x8000000000000004
  )
)
