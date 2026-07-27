package us.tractat.kuilt.warp

/**
 * How this module names the OS-classified artifact a consumer is missing (#1335).
 *
 * `wasm-opt` is a native executable, so the module publishes one classified companion jar
 * per supported host rather than one fat jar. That makes "nothing on the classpath" a
 * routine first-run mistake — and an error that merely says *no bundled binary* leaves the
 * operator to go read the build to find out what to add. Every message here therefore ends
 * in the literal `runtimeOnly(…)` line to paste.
 *
 * Pure string/`String?` in, string out: no classpath, no system properties, no I/O — so the
 * wording is pinned by ordinary unit tests rather than only observed in a consumer's stack
 * trace.
 */
internal object BinaryenArtifacts {

    /** Resource directory, relative to [BinaryenWasmOptimizer]'s package. */
    const val RESOURCE_ROOT: String = "binaryen"

    /** Stand-in when the generated coordinates resource is absent (a shaded/repackaged jar). */
    const val UNKNOWN_COORDINATES: String = "us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>"

    private const val LEAD: String =
        "no Binaryen `wasm-opt` on the runtime classpath. :kuilt-warp-compiler publishes the " +
            "native binary as one classified companion jar per OS, so the main artifact stays lean"

    /**
     * A JVM's platform in published-classifier form (`<os>-<arch>`), or `null` when kuilt
     * publishes no `wasm-opt` for it. A compiler node is a JVM/server peer on macOS or Linux;
     * every other host is a pure consumer of the optimized variant.
     */
    fun hostPlatform(osName: String?, osArch: String?): String? {
        val os = osName.orEmpty().lowercase()
        val arch = osArch.orEmpty().lowercase()
        val osKey = when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"
            else -> return null
        }
        val archKey = when (arch) {
            "aarch64", "arm64" -> if (osKey == "macos") "arm64" else "aarch64"
            "x86_64", "amd64" -> "x86_64"
            else -> return null
        }
        return "$osKey-$archKey"
    }

    /** Nothing classified is on the classpath: name the coordinate to add, or say why none exists. */
    fun missingArtifact(
        host: String?,
        osName: String?,
        osArch: String?,
        coordinates: String,
        published: List<String>,
    ): String {
        val unsupported = host == null || (published.isNotEmpty() && host !in published)
        if (unsupported) {
            return "$LEAD. This JVM (os.name=$osName, os.arch=$osArch) has no published " +
                "`wasm-opt`${publishedSuffix(published)}. A warp compiler node is a JVM/server peer " +
                "on macOS or Linux; other peers consume the optimized variant without running wasm-opt."
        }
        return "$LEAD. Add the one for this host:\n" +
            "    runtimeOnly(\"$coordinates:$host\")${publishedSuffix(published)}"
    }

    /** A classified jar is present but built for another OS — the silent-wrong-binary case. */
    fun wrongPlatform(bundled: String, host: String?, coordinates: String): String =
        "the Binaryen `wasm-opt` on the runtime classpath is the '$bundled' build, but this JVM " +
            "is '${host ?: "an unsupported platform"}'. Replace the classified dependency with the " +
            "matching one:\n    runtimeOnly(\"$coordinates:${host ?: "<platform>"}\")"

    private fun publishedSuffix(published: List<String>): String =
        if (published.isEmpty()) "" else " (published: ${published.joinToString(", ")})"
}
