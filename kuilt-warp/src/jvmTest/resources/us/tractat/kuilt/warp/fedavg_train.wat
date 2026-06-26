;; fedavg_train.wat — one GD step of linear regression (D=2: one feature + bias)
;; over the warp linear-memory ABI (memory, warp_alloc, warp_run).
;;
;; Input pointer is always 0 (warp_alloc returns 0). Layout (little-endian):
;;   [0]  u32 magic 0x46415631   [4]  u32 dim=2     [8]  f64 learnRate
;;   [16] u32 count N            [20] u32 pad       [24] f64 w0   [32] f64 w1(bias)
;;   [40 + 16*i] f64 x_i, f64 y_i
;; Output region base = 65536 (page 1):
;;   [+0] u32 magic  [+4] u32 dim=2  [+8] u64 N  [+16] f64 w0'  [+24] f64 w1'
;; warp_run returns packed (65536 << 32) | 32.
;;
;; To regenerate fedavg_train.wasm:
;;   /opt/homebrew/bin/wat2wasm fedavg_train.wat -o fedavg_train.wasm
(module
  (memory (export "memory") 2)   ;; 2 pages = 128 KiB; input [0,65536), result [65536,65568)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0)

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    (local $n i32) (local $i i32) (local $off i32)
    (local $w0 f64) (local $w1 f64) (local $eta f64)
    (local $gw0 f64) (local $gb f64)
    (local $x f64) (local $y f64) (local $err f64) (local $scale f64)

    (local.set $eta (f64.load offset=8  (local.get $ptr)))
    (local.set $n   (i32.load offset=16 (local.get $ptr)))
    (local.set $w0  (f64.load offset=24 (local.get $ptr)))
    (local.set $w1  (f64.load offset=32 (local.get $ptr)))
    (local.set $gw0 (f64.const 0))
    (local.set $gb  (f64.const 0))
    (local.set $i   (i32.const 0))
    (local.set $off (i32.add (local.get $ptr) (i32.const 40)))

    (block $break
      (loop $loop
        (br_if $break (i32.ge_u (local.get $i) (local.get $n)))
        (local.set $x (f64.load        (local.get $off)))
        (local.set $y (f64.load offset=8 (local.get $off)))
        ;; err = w0*x + w1 - y
        (local.set $err
          (f64.sub
            (f64.add (f64.mul (local.get $w0) (local.get $x)) (local.get $w1))
            (local.get $y)))
        ;; gw0 += err*x ; gb += err
        (local.set $gw0 (f64.add (local.get $gw0) (f64.mul (local.get $err) (local.get $x))))
        (local.set $gb  (f64.add (local.get $gb)  (local.get $err)))
        (local.set $off (i32.add (local.get $off) (i32.const 16)))
        (local.set $i   (i32.add (local.get $i)   (i32.const 1)))
        (br $loop)))

    ;; scale = 2.0 / n
    (local.set $scale (f64.div (f64.const 2) (f64.convert_i32_u (local.get $n))))
    ;; w0' = w0 - eta*(scale*gw0) ; w1' = w1 - eta*(scale*gb)
    (local.set $w0 (f64.sub (local.get $w0)
      (f64.mul (local.get $eta) (f64.mul (local.get $scale) (local.get $gw0)))))
    (local.set $w1 (f64.sub (local.get $w1)
      (f64.mul (local.get $eta) (f64.mul (local.get $scale) (local.get $gb)))))

    ;; write output at 65536
    (i32.store offset=65536 (i32.const 0) (i32.const 0x46415631))
    (i32.store offset=65540 (i32.const 0) (i32.const 2))
    (i64.store offset=65544 (i32.const 0) (i64.extend_i32_u (local.get $n)))
    (f64.store offset=65552 (i32.const 0) (local.get $w0))
    (f64.store offset=65560 (i32.const 0) (local.get $w1))

    (i64.or
      (i64.shl (i64.const 65536) (i64.const 32))
      (i64.const 32))))
