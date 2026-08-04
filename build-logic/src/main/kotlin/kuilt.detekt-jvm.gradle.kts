// Detekt for plain Kotlin/JVM modules — the ones that deliberately do NOT apply
// `kuilt.kmp-library` (a bench harness, a test-only examples module, a KSP processor,
// a demo application). Those modules were compiled but entirely UNLINTED, because
// detekt is registered as a side effect of `kuilt.kmp-library` and nothing else (#2005).
//
// Apply this alongside `kotlinJvm` to get the same rule set and the same `detektAll`
// entry point every KMP module has. `forbidUnlintedModule` in the root build fails the
// build on a module that has Kotlin source and neither.

apply(plugin = "io.gitlab.arturbosch.detekt")

configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = false
    allRules = false
}

// The detekt plugin wires `check -> detekt`, and on a JVM project the plain `detekt`
// lifecycle task analyses the whole source tree WITHOUT type resolution — so the
// nullability rules this repo enables (all four require type resolution) would not fire
// there anyway, and running it would only duplicate work inside `./gradlew build`. The
// type-resolved per-source-set tasks (`detektMain`/`detektTest`, which carry the compile
// classpath) are exposed through a module-local `detektAll` instead, matching
// `kuilt.kmp-library`: CI runs `./gradlew detektAll` as its own job so the heavy analysis
// never shares a runner — and its memory — with the build.
tasks.named("detekt") { enabled = false }

afterEvaluate {
    // Test sources get the extended config that also bans production dispatchers and
    // GlobalScope, exactly as in `kuilt.kmp-library`.
    val testDetektConfig = rootProject.files(
        "config/detekt/detekt.yml",
        "config/detekt/detekt-test.yml",
    )
    (tasks.findByName("detektTest") as? io.gitlab.arturbosch.detekt.Detekt)
        ?.config?.setFrom(testDetektConfig)

    // Name-matched live collection rather than an eager `findByName` snapshot, for the same
    // reason as `kuilt.kmp-library`: it resolves at task-graph time, after every detekt task
    // exists, and picks up a source set added later.
    val detektAllTaskNames = setOf("detektMain", "detektTest")
    tasks.register("detektAll") {
        group = "verification"
        description =
            "Runs detekt on this module's main and test sources with type resolution. " +
                "Not wired into check — CI runs it as a separate job to avoid OOM."
        dependsOn(tasks.matching { it.name in detektAllTaskNames })
    }
}
