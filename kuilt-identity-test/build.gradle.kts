plugins { id("kuilt.kmp-library") }

kotlin {
    // The npm package a wasmJs compilation lands in is named `<root>-<project>`, plus `-test` for a
    // test compilation. So this module's main package would be `kuilt-kuilt-identity-test`, which is
    // exactly the name of `:kuilt-identity`'s *test* package: the two overwrite each other's
    // package.json, and identity's test-only `playwright-core` (its WebKit run) drops out of the yarn
    // lock. Measured, `kotlinWasmStoreYarnLock` failed on it. A distinct module name avoids the clash.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { outputModuleName.set("kuilt-identity-testsupport") }
    compilerOptions {
        // The seam this module plugs into is `:kuilt-identity`'s `KeyScheme`, which carries no
        // stability promise outside kuilt's own modules.
        optIn.add("us.tractat.kuilt.identity.IdentityInternalApi")
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-identity"))
            // The fake signature is SHA-512 over the key and the message. KotlinCrypto's is plain
            // Kotlin on every target, so it never waits on a WebCrypto promise, which is the point of
            // this module. Already in the build for `:kuilt-deal`'s commit-reveal hashing.
            implementation(libs.kotlincrypto.hash.sha2)
            // The lock `FakeKeyScheme` draws key points under, so one scheme can be shared across threads.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
