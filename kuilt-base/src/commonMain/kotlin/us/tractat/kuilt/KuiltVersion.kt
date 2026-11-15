package us.tractat.kuilt

/**
 * The version of the kuilt that is running.
 *
 * It is the same string the build publishes the artifact under: `<line>.0-dev` from a local
 * build, `<line>.0-dev.<run>` from a snapshot, and the tag's `x.y.z` from a release.
 *
 * It lives in `:kuilt-base`, which depends on nothing. Most kuilt modules bring that module with
 * them, so a consumer rarely declares it. The value is the version of `:kuilt-base`; import the
 * BOM to keep every kuilt module at that version.
 *
 * It is deliberately a plain `val`, not a `const`, for two reasons. A `const` is copied into
 * the code that reads it when that code compiles, so it would name the kuilt a consumer
 * compiled against rather than the one actually running. And a `const` puts its value in
 * the classpath snapshot every dependent's JVM compile is keyed on, so every snapshot run, whose
 * version carries its run number, would recompile every module built against it. The generated
 * value underneath is a function for the same reason (#3464).
 *
 * @sample us.tractat.kuilt.sampleKuiltVersion
 */
public object KuiltVersion {
    /** The running kuilt's version, for example `0.7.0-dev`, `0.7.0-dev.412` or `0.7.4`. */
    public val VERSION: String = generatedVersion()
}
