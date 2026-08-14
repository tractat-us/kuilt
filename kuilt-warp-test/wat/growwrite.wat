;; growwrite.wat — the SUCCESSFUL grow: memory declared `1 2`, and warp_run grows it by one
;; page mid-call, writes its result entirely inside the newly-added page, and returns a pointer
;; into that page. Every other loadable vector in the TCK either never grows or grows past its
;; declared max and traps (growmax.wat), so "the guest legitimately grew memory and then wrote
;; its result above the old boundary" was unreachable — and the failure it provokes is silent
;; corruption rather than an exception: a stale ByteBuffer on the JVM, a detached ArrayBuffer in
;; the browser, a stale base pointer from m3_GetMemory on wasm3.
;;
;; The payload is self-reporting so the test can prove its own rig fired:
;;   result[0..3] — the result pointer itself, i32 little-endian. The test asserts it is at or
;;                  above the pre-grow memory end (65536), which is what makes this a post-grow
;;                  read rather than an ordinary one.
;;   result[4..7] — the marker 0x50524157, which little-endian encodes the ASCII bytes "WARP".
;;                  A host reading through a stale base returns something else, or throws.
;;
;; A denied grow traps rather than falling through, so an engine that refuses the grow reds
;; loudly instead of quietly reverting this vector to a plain in-bounds read.
;;
;; To reproduce the GROW_THEN_WRITE bytes in WasmKernelFixtures.kt:
;;   wat2wasm growwrite.wat -o growwrite.wasm
(module
  (memory (export "memory") 1 2)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    (local $old_pages i32)
    (local $base i32)
    ;; memory.grow returns the PREVIOUS size in pages, or -1 if the engine denied it.
    (local.set $old_pages (memory.grow (i32.const 1)))
    (if (i32.eq (local.get $old_pages) (i32.const -1))
      (then unreachable)
    )
    ;; The first byte of the page that did not exist a moment ago.
    (local.set $base (i32.mul (local.get $old_pages) (i32.const 65536)))
    (i32.store (local.get $base) (local.get $base))
    (i32.store (i32.add (local.get $base) (i32.const 4)) (i32.const 0x50524157))
    (i64.or
      (i64.shl (i64.extend_i32_u (local.get $base)) (i64.const 32))
      (i64.const 8)
    )
  )
)
