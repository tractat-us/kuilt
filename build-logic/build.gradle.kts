plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serialization.gradlePlugin)
    implementation(libs.android.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.vanniktech.mavenPublish.gradlePlugin)
    implementation(libs.kover.gradlePlugin)
    implementation(libs.dokka.gradlePlugin)
    // Expose the version-catalog accessor to precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
