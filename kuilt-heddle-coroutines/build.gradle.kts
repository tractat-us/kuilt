plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-heddle-core"))
            // `api`: the pool's signatures suspend, and a consumer calls them from its own coroutines.
            api(libs.kotlinx.coroutines.core)
            // The pool's one lock, `reentrantLock`.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
