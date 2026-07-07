#!/usr/bin/env bash
#
# Rebuilds the committed per-target wasm3 static libraries from the vendored
# (and locally patched — grep "WARP PATCH") source tree. Run on macOS with
# Xcode installed; commit the resulting prebuilt/<target>/libwasm3.a files so
# CI (ubuntu) can link the Kotlin/Native apple targets without xcrun/Xcode.
#
#   ./build-prebuilt.sh
#
# The object list matches the original prebuilt archives: every vendored .c
# except the WASI system-interface bindings (m3_api_wasi / m3_api_uvwasi —
# host capabilities the warp sandbox never links), plus the kuilt-added
# warp_deadline.c (the cooperative execution-deadline m3_Yield).
#
# Minimum-OS targets mirror the Kotlin/Native defaults the cinterop klib is
# built against (macOS 11.0, iOS 12.0, iOS simulator 14.0, all arm64).

set -euo pipefail
cd "$(dirname "$0")"

SOURCES=(
    m3_api_libc
    m3_api_meta_wasi
    m3_api_tracer
    m3_bind
    m3_code
    m3_compile
    m3_core
    m3_emit
    m3_env
    m3_exec
    m3_function
    m3_info
    m3_module
    m3_optimize
    m3_parse
    warp_deadline
)

build() {
    local target=$1 sdk=$2 triple=$3
    echo "== ${target} (${triple}, sdk ${sdk})"
    local tmp
    tmp=$(mktemp -d)
    for src in "${SOURCES[@]}"; do
        xcrun --sdk "${sdk}" clang -target "${triple}" -O3 \
            -c "source/${src}.c" -o "${tmp}/${src}.o"
    done
    mkdir -p "prebuilt/${target}"
    rm -f "prebuilt/${target}/libwasm3.a"
    xcrun --sdk "${sdk}" libtool -static -o "prebuilt/${target}/libwasm3.a" "${tmp}"/*.o
    rm -rf "${tmp}"
}

build macosArm64 macosx arm64-apple-macos11.0
build iosArm64 iphoneos arm64-apple-ios12.0
build iosSimulatorArm64 iphonesimulator arm64-apple-ios14.0-simulator

echo "Done. Rebuilt archives:"
ls -l prebuilt/*/libwasm3.a
