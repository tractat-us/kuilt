;; imports.wat — declares one import; used to prove the import-rejection sandbox guard.
;;
;; Exports the warp ABI (memory, warp_alloc, warp_run) but also imports a host function
;; from "env"/"host". Any declared import triggers the capability-sandbox reject in
;; ChicoryWasmRuntime.load(), which surfaces as WasmLoadException.
;;
;; To reproduce imports.wasm:
;;   /opt/homebrew/bin/wat2wasm imports.wat -o imports.wasm
;;
(module
  (import "env" "host" (func (param i32)))

  (memory (export "memory") 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
