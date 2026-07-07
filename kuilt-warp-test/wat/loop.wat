;; loop.wat — the CPU-bomb run-guard vector: warp_run spins forever on a backward
;; branch (`loop $l (br $l)`) with NO function call inside the loop body. Backward
;; branches (plus call entries) are exactly where each impl's execution-timeout
;; mechanism checks its budget (Chicory: Thread.isInterrupted; patched wasm3: the
;; m3_Yield deadline; browser: the guest runs in a terminate()-able Web Worker), so
;; this kernel proves the budget fires with no cooperation from the guest.
;;
;; Memory is declared `1 16` (explicit max = the default cap) so every load-time guard
;; passes and only the execution-time bound can stop it.
;;
;; To reproduce the CPU_BOMB bytes in WasmKernelFixtures.kt:
;;   wat2wasm loop.wat -o loop.wasm
(module
  (memory (export "memory") 1 16)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    (loop $l
      (br $l)
    )
    ;; Unreachable: the loop never exits. Present only so the function type-checks.
    i64.const 0
  )
)
