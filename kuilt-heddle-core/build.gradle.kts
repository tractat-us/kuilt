plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// The fair-share policy and the entitlement ledger, with nothing that moves bytes: no `Seam`, no
// Raft, no coroutines. That property is enforced, not reviewed — `verifyHeddleCoreDependencies`
// in the root build fails when any classpath of this module resolves one of the forbidden modules.

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-crdt"))
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
