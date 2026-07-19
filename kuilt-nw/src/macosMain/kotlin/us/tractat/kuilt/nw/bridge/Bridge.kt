/*
 * macOS-only: top-level cdecl symbols exported from the `libkuilt.dylib`
 * shared library so the JVM target can call into Apple's Network.framework
 * (via [us.tractat.kuilt.nw.RealNwApi]) over JNA.
 *
 * Naming: K/N normalises hyphens in the binary name, so the artefact is
 * `libkuilt.dylib`. JNA loads it via `Native.load("kuilt", …)`.
 *
 * `@CName` must be on top-level functions, requires the
 * `ExperimentalNativeApi` opt-in, and survives K/N release-mode optimisation
 * by being explicitly named — no dead-code-elimination risk.
 */
package us.tractat.kuilt.nw.bridge

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/**
 * Returns the bridge ABI version. Bumped whenever the cdecl surface gains a
 * breaking change so the JVM side (`NwNativeLib.EXPECTED_PROTOCOL_VERSION`) can
 * fail fast if it loads a mismatched dylib (e.g. from a stale desktop bundle).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("kuilt_protocol_version")
@Suppress("ktlint:standard:function-naming")
public fun kuilt_protocol_version(): Int = PROTOCOL_VERSION

/**
 * Bridge ABI version. The JVM side expects `2`; mismatch is a build error.
 *
 * Bumped to `2` for the `nw_set_connection_closed_state_callback` export (#1539). Although the export is
 * additive, the JVM bridge now *registers* it at construction — so a stale dylib lacking the symbol must fail
 * the fast `kuilt_protocol_version()` ABI check ([NwFabric] `createRuntime`) rather than surface as a cryptic
 * later JNA `UnsatisfiedLinkError`. Bump when an existing cdecl signature changes, OR when the JVM begins
 * unconditionally calling a newly-added export (as here).
 */
private const val PROTOCOL_VERSION: Int = 2
