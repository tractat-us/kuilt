;; loop.wat — CPU-bomb kernel: warp_run spins forever on a backward branch.
;;
;; Exports the warp ABI (memory, warp_alloc, warp_run) but warp_run never returns:
;; its body is an unconditional `(loop $l ... br $l)` infinite loop. The backward
;; branch is exactly where Chicory's interpreter checks Thread.isInterrupted(), so
;; the sandbox execution-time bound interrupts it and surfaces WasmExecutionException.
;;
;; To reproduce loop.wasm:
;;   /opt/homebrew/bin/wat2wasm loop.wat -o loop.wasm
;;
(module
  (memory (export "memory") 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    (loop $l
      (br $l)
    )
    ;; Unreachable: the loop never exits. Present only so the function type-checks
    ;; with an i64 result.
    i64.const 0
  )
)
