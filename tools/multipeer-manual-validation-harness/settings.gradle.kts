rootProject.name = "multipeer-harness"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Relative to this directory: tools/multipeer-manual-validation-harness/ -> repo root.
includeBuild("../..")

include(":harness")
