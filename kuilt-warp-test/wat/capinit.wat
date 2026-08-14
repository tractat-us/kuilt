;; capinit.wat — the boundary-acceptance vector: memory declared `16 16`, i.e. an initial AND a
;; max sitting exactly at the default 16-page sandbox cap. Both are legal, so the module must
;; load and run. It is the only vector whose initial sits on the cap boundary, and therefore the
;; only thing that reds an off-by-one `initial >= cap` guard — reverse.wat declares `1 16`, so
;; its max pins the max boundary while its initial of 1 pins nothing.
;;
;; To reproduce the INITIAL_AT_CAP bytes in WasmKernelFixtures.kt:
;;   wat2wasm capinit.wat -o capinit.wasm
(module
  (memory (export "memory") 16 16)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
