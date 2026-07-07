;; startbomb.wat — the load-phase CPU-bomb vector: a `(start)` function that spins
;; forever on a backward branch (`loop $l (br $l)`) with NO function call inside the
;; loop body. `(start)` runs at *instantiation* — before any ABI call — so an impl
;; that instantiates outside its execution budget hangs at load, not at invoke
;; (Chicory: build runs under the interruptible timed guest executor; patched wasm3:
;; the ABI-export lookup that lazily runs start is under the armed deadline; browser:
;; instantiation is deferred to the terminate()-able Web Worker).
;;
;; The module is otherwise a complete, well-behaved warp kernel: memory `1 16`
;; (explicit max = the default cap) and both ABI exports, so every load-time guard
;; passes and only the execution-time bound can stop it.
;;
;; To reproduce the START_CPU_BOMB bytes in WasmKernelFixtures.kt:
;;   wat2wasm startbomb.wat -o startbomb.wasm
(module
  (memory (export "memory") 1 16)
  (func (export "warp_alloc") (param i32) (result i32)
    i32.const 0)
  (func (export "warp_run") (param i32 i32) (result i64)
    i64.const 0)
  (func $start
    loop $l
      br $l
    end)
  (start $start))
