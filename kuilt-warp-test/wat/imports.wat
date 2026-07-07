;; imports.wat — the capability-violation load-guard vector: declares one host import.
;;
;; Memory is declared `1 1` (explicit bounded max) so the import is the ONLY violation:
;; the suite asserts the rejection message names the capability violation, which requires
;; that no other guard (e.g. the no-max memory guard) can fire first on any impl.
;;
;; To reproduce the IMPORTS bytes in WasmKernelFixtures.kt:
;;   wat2wasm imports.wat -o imports.wasm
(module
  (import "env" "host" (func (param i32)))

  (memory (export "memory") 1 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
