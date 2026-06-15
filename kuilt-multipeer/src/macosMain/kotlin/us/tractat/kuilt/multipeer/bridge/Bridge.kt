/*
 * macOS-only: top-level cdecl symbols exported from the
 * `libkuilt.dylib` shared library so the JVM target can call into
 * MultipeerConnectivity over JNA.
 *
 * Naming: K/N normalises hyphens in the binary name, so the artefact is
 * `libkuilt.dylib`. JNA loads it via `Native.load("kuilt", …)`.
 *
 * `@CName` must be on top-level functions, requires the
 * `ExperimentalNativeApi` opt-in, and survives K/N release-mode optimisation
 * by being explicitly named — no DCE risk.
 */
package us.tractat.kuilt.multipeer.bridge

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/**
 * Returns the bridge ABI version. Bumped whenever the cdecl surface gains a
 * breaking change so the JVM side can fail fast if it loads a mismatched
 * dylib (e.g. from a stale Compose Desktop bundle).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("kuilt_protocol_version")
@Suppress("ktlint:standard:function-naming")
public fun kuilt_protocol_version(): Int = PROTOCOL_VERSION

/**
 * Bridge ABI version. The JVM side expects `1`; mismatch is a build error.
 *
 * Bump only when an existing cdecl signature changes. Adding new exports
 * does not require a bump.
 */
private const val PROTOCOL_VERSION: Int = 1
