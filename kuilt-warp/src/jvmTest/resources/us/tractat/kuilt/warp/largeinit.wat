;; largeinit.wat — declares initial memory of 32 pages with no declared max.
;;
;; Exports the warp ABI (memory, warp_alloc, warp_run). The `(memory 32)` declaration
;; sets initial = 32 pages with no max (unbounded). Initial 32 exceeds the default
;; WasmSandboxConfig.maxMemoryPages of 16, so ChicoryWasmRuntime.load() must reject
;; it as WasmLoadException before attempting to construct MemoryLimits(32, 16), which
;; would throw Chicory's raw InvalidException ("size minimum must not be greater than
;; maximum").
;;
;; To reproduce largeinit.wasm:
;;   /opt/homebrew/bin/wat2wasm largeinit.wat -o largeinit.wasm
;;
(module
  (memory (export "memory") 32)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
