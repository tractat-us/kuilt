plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-raft"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
