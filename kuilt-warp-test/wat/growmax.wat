;; growmax.wat — evidence that reject-no-max is a sufficient memory ceiling: the
;; engine must enforce a module's DECLARED max at grow time. Memory is declared
;; `(1, max 1)`; warp_run executes `memory.grow(1)` (which would require 2 pages).
;; A conforming engine denies the grow (memory.grow returns -1) and the kernel
;; converts that into `unreachable` → WasmExecutionException. If the engine did NOT
;; honor the declared max, the grow would succeed, no trap would fire, and warp_run
;; would return normally — failing the suite and signalling that reject-no-max alone
;; is an insufficient ceiling on that target.
;;
;; To reproduce the GROW_PAST_DECLARED_MAX bytes in WasmKernelFixtures.kt:
;;   wat2wasm growmax.wat -o growmax.wasm
(module
  (memory (export "memory") 1 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    (if (i32.eq (memory.grow (i32.const 1)) (i32.const -1))
      (then unreachable)
    )
    i64.const 0
  )
)
