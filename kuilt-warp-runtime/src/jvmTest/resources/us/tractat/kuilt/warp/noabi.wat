;; noabi.wat — a well-formed module that omits the warp ABI exports.
;;
;; Parses, declares no imports, and has legal small memory, but exports neither
;; warp_alloc nor warp_run. Chicory's Instance.export("warp_alloc") then throws a raw
;; InvalidException ("Unknown export with name ...") — a ChicoryException that is NOT a
;; WasmException. ChicoryWasmRuntime.load() must catch it and rethrow WasmLoadException so
;; a verified-but-incomplete kernel is a TERMINAL load failure, not a transient executor
;; error that anti-entropy retries forever (remote DoS vector).
;;
;; To reproduce noabi.wasm:
;;   /opt/homebrew/bin/wat2wasm noabi.wat -o noabi.wasm
;;
(module
  (memory (export "memory") 1)
)
