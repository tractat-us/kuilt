;; initovermax.wat — the ONLY vector that can reach the oversize-INITIAL load guard: memory
;; declared `32 16`, i.e. an initial of 32 pages above the default 16-page sandbox cap, with an
;; explicit max sitting exactly AT the cap. The no-max rule is satisfied and the oversize-max
;; rule is satisfied, so the initial guard is the only sandbox rule this module breaks.
;;
;; It is deliberately spec-INVALID: WebAssembly validates limits with `min <= max`, so a valid
;; module with an over-cap initial necessarily has an over-cap max (or no max at all) and cannot
;; isolate the initial guard. wat2wasm therefore needs --no-check to emit it, which is the point —
;; the guard exists for an engine that does not check limits itself, and wasm3 is such an engine.
;;
;; To reproduce the INITIAL_ABOVE_DECLARED_MAX bytes in WasmKernelFixtures.kt:
;;   wat2wasm --no-check initovermax.wat -o initovermax.wasm
(module
  (memory (export "memory") 32 16)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
