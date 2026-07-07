;; noabi.wat — the missing-ABI-export load-guard vector: a well-formed module (no
;; imports, legal bounded memory `1 1`) that omits the warp_alloc/warp_run exports.
;; Every impl must surface this as a TERMINAL WasmLoadException — a raw engine error
;; escaping load() would bypass the executor's terminal-error handling and trigger an
;; anti-entropy retry storm on a verified-but-broken kernel (a remote DoS vector).
;;
;; To reproduce the MISSING_ABI_EXPORTS bytes in WasmKernelFixtures.kt:
;;   wat2wasm noabi.wat -o noabi.wasm
(module
  (memory (export "memory") 1 1)
)
