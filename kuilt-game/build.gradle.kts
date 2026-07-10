plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-raft"))
            // The approved :kuilt-game → :kuilt-cluster direction (#1349): game grows a federated
            // bootstrap (gameNodeRoomFederated) that documents/consumes cluster's game-agnostic
            // two-tier substrate (AttachmentDirectory / OverlayServer). Cluster never depends on
            // game, so no cycle. PR 2 moves launchCoreLearnerAdmission down into cluster over this
            // edge.
            implementation(project(":kuilt-cluster"))
            implementation(project(":kuilt-gossip"))
            implementation(project(":kuilt-liveness"))
            implementation(project(":kuilt-quilter"))
            implementation(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-raft-test"))
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-gossip-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
