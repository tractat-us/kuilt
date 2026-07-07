;; ooballoc.wat — the high-bit alloc-pointer run-guard vector: warp_alloc returns
;; 0x8000_0000 (i32.const -2147483648). A host that trusts the guest-controlled
;; pointer as a SIGNED 32-bit int writes the marshalled args below the linear-memory
;; base — a sandbox-escape OOB host-memory WRITE. Every impl must instead surface a
;; bounded WasmExecutionException before writing anything.
;; Memory declared `1 1` (explicit max) so it loads past every guard.
;;
;; To reproduce the HIGH_BIT_ALLOC_POINTER bytes in WasmKernelFixtures.kt:
;;   wat2wasm ooballoc.wat -o ooballoc.wasm
(module
  (memory (export "memory") 1 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const -2147483648
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
