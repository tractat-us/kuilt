plugins {
    id("kuilt.kmp-library")
    // Generates the string behind `us.tractat.kuilt.KuiltVersion.VERSION` into commonMain (#3323);
    // the object itself is hand-written in src/commonMain. This module is its only carrier: `:kuilt-core`, `:kuilt-heddle-core`, `:kuilt-crdt`, `:kuilt-store` and `:kuilt-seal`
    // `api` it, so everything built on them reads the one copy.
    id("kuilt.version-constant")
}

// No commonMain dependencies, deliberately. This module sits under every root of the module graph,
// so whatever it depends on, every kuilt consumer inherits. Anything added here has to be worth
// that, and `:kuilt-heddle-core`'s `verifyHeddleCoreDependencies` reds if it brings coroutines or a
// network module.
