;; square.wat — the C3 go/no-go wasm kernel.
;;
;; Exports a single i32→i32 function: square(n) = n * n.
;; Hand-assembled to avoid a native wabt dependency at build time.
;; To reproduce square.wasm:
;;   wat2wasm square.wat -o square.wasm
;; Or verify with:
;;   wasm-objdump -x square.wasm
;;
;; Binary provenance: 43 bytes, SHA-256 pinned in ChicoryRuntimeDispatchTest.
(module
  (func $square (export "square") (param i32) (result i32)
    local.get 0
    local.get 0
    i32.mul))
