plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serialization.gradlePlugin)
    implementation(libs.android.gradlePlugin)
    // Expose the version-catalog accessor to precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
