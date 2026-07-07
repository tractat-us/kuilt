;; badreslen.wat — the high-bit result-length run-guard vector: warp_run returns the
;; packed i64 (resPtr = 0, resLen = 0x8000_0000). A host that narrows the guest-
;; controlled length to a SIGNED 32-bit int wraps it negative and hits a raw
;; negative-size allocation — an exception outside the sealed WasmException hierarchy
;; that escapes the executor's terminal-error handling (anti-entropy retry DoS).
;; Every impl must instead surface a bounded WasmExecutionException.
;; Memory declared `1 1` (explicit max) so it loads past every guard.
;;
;; To reproduce the HIGH_BIT_RESULT_LENGTH bytes in WasmKernelFixtures.kt:
;;   wat2wasm badreslen.wat -o badreslen.wasm
(module
  (memory (export "memory") 1 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    ;; (0 << 32) | 0x8000_0000
    i64.const 0x80000000
  )
)
