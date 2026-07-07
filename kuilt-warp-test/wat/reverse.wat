;; reverse.wat — the conformance suite's well-behaved kernel: byte-reverse over the
;; warp linear-memory ABI (memory, warp_alloc, warp_run).
;;
;; Memory is declared `1 16` — an explicit max equal to the default sandbox cap — so it
;; passes every load-time guard (a no-max declaration is rejected on every target).
;;
;; warp_alloc(len) returns a static input pointer (offset 0).
;; warp_run(ptr, len) reverses bytes from [ptr, ptr+len) into the result region at
;; offset 4096, then returns the packed i64: (4096L << 32) | len.
;;
;; To reproduce the REVERSE bytes in WasmKernelFixtures.kt:
;;   wat2wasm reverse.wat -o reverse.wasm
(module
  (memory (export "memory") 1 16)

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
