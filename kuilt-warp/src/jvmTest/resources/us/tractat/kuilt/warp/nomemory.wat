;; nomemory.wat — a well-formed module that exports the ABI functions but no memory.
;;
;; Parses and declares no imports, but exports no memory section, so
;; Instance.memory() returns null. ChicoryWasmRuntime.load() must turn that null into a
;; TERMINAL WasmLoadException ("module exports no memory") rather than letting a downstream
;; NullPointerException escape as a transient executor error (anti-entropy retry storm).
;;
;; To reproduce nomemory.wasm:
;;   /opt/homebrew/bin/wat2wasm nomemory.wat -o nomemory.wasm
;;
(module
  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    i64.const 0
  )
)
