plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-deal"))
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.core)
            // Ships a kotlin-test-based conformance suite in MAIN (not commonTest)
            // so other modules' tests can subclass CommutativeSchemeConformanceSuite.
            api(kotlin("test"))
        }
        jvmMain.dependencies { api(kotlin("test-junit")) }
        androidMain.dependencies { api(kotlin("test-junit")) }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
