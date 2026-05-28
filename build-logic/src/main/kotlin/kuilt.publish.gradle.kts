plugins { `maven-publish` }

publishing {
    repositories {
        // TigrisStaging: a *local file://* maven repo that the publish workflow
        // stages publications into before `aws s3 sync`-ing the whole tree up
        // to Tigris. We don't use Gradle's native s3:// transport because it
        // sets a header (an ACL or storage-class) that Tigris rejects with
        // HTTP 400 — diagnostic in #22 proved vanilla AWS CLI writes work to
        // the same bucket. Stage-and-sync sidesteps Gradle's transport entirely
        // and reuses the AWS CLI mechanism we know works.
        maven {
            name = "TigrisStaging"
            url = rootProject.layout.buildDirectory.dir("staged-maven-repo").get().asFile.toURI()
        }
    }
}
