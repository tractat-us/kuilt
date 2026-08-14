;; oobinrange.wat — the in-range-past-end result window: warp_run returns the packed i64
;; (resPtr = 65530, resLen = 100) on a one-page (65536-byte) memory. Neither word has bit 31
;; set, and the pointer is a perfectly ordinary offset INSIDE linear memory — but the window
;; [65530, 65630) runs 94 bytes past the end of it.
;;
;; This is the vector that separates a real bounds check from a sign check. The suite's three
;; sibling vectors (oobresptr, badreslen, ooballoc) all fail the same way — bit 31 set — so a
;; host whose only guard is `if (word < 0) reject` passes every one of them and still reads
;; past the end of linear memory on a benign-looking pointer. Nothing else in the TCK produces
;; that window; see WasmRuntimeConformanceSuite.resultWindowPastMemoryEndIsBounded.
;;
;; To reproduce the OOB_IN_RANGE_RESULT bytes in WasmKernelFixtures.kt:
;;   wat2wasm oobinrange.wat -o oobinrange.wasm
(module
  (memory (export "memory") 1 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    ;; (65530 << 32) | 100
    i64.const 0x0000FFFA00000064
  )
)
