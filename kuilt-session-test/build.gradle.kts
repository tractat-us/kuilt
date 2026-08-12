plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-session"))
            implementation(libs.kotlinx.coroutines.core)
            // FakeRoomFactory's process-wide room counter — a fake that handed two rooms one id
            // would reproduce #1594 inside the double used to test around it.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
