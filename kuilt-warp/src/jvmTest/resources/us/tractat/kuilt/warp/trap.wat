;; trap.wat — trapping kernel: warp_run executes `unreachable`.
;;
;; Exports the warp ABI (memory, warp_alloc, warp_run). warp_run immediately
;; executes the `unreachable` instruction, which Chicory raises as a trap
;; (ChicoryException). The sandbox maps any such trap to WasmExecutionException.
;;
;; To reproduce trap.wasm:
;;   /opt/homebrew/bin/wat2wasm trap.wat -o trap.wasm
;;
(module
  (memory (export "memory") 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    unreachable
  )
)
