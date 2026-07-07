;; nomax.wat — the memory-bomb load-guard vector: memory declared with NO explicit max.
;;
;; This is the reverse.wat kernel with `(memory 1)` — min 1 page, no max (limits flag 0).
;; It is a COMPLETE warp-ABI module: it passes the import / initial-size / ABI-export
;; checks, so the no-max guard is the only thing that can reject it — disabling that
;; guard on any target would let this load and `memory.grow` unbounded (~4 GiB).
;;
;; To reproduce the NO_MAX_MEMORY bytes in WasmKernelFixtures.kt:
;;   wat2wasm nomax.wat -o nomax.wasm
(module
  (memory (export "memory") 1)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0
  )

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    (local $i i32)
    (local $result_base i32)
    (local.set $result_base (i32.const 4096))
    (local.set $i (i32.const 0))
    (block $break
      (loop $loop
        (br_if $break (i32.ge_u (local.get $i) (local.get $len)))
        (i32.store8
          (i32.add (local.get $result_base) (local.get $i))
          (i32.load8_u
            (i32.add
              (local.get $ptr)
              (i32.sub
                (i32.sub (local.get $len) (i32.const 1))
                (local.get $i)
              )
            )
          )
        )
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $loop)
      )
    )
    (i64.or
      (i64.shl (i64.const 4096) (i64.const 32))
      (i64.extend_i32_u (local.get $len))
    )
  )
)
