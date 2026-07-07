;; startbomb.wat — the load-phase CPU-bomb vector: a `(start)` function that spins
;; forever on a backward branch (`loop $l (br $l)`) with NO function call inside the
;; loop body. `(start)` runs at *instantiation* — before any ABI call — so an impl
;; that instantiates outside its execution budget hangs at load, not at invoke
;; (Chicory: build under the interrupted guest executor; browser: instantiation is
;; deferred to the terminate()-able Web Worker; patched wasm3: start already runs
;; lazily on the first guest call, under the armed deadline).
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
