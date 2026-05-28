plugins { `maven-publish` }

publishing {
    repositories {
        // GitHub Packages — incumbent target.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/tractat-us/kuilt")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
        // TigrisStaging: a *local file://* maven repo that the publish-tigris-test
        // workflow stages publications into before `aws s3 sync`-ing the whole tree
        // up to Tigris. We don't use Gradle's native s3:// transport here because
        // it sets a header (likely an ACL or storage-class) that Tigris rejects
        // with HTTP 400 — diagnostic in #22 proved vanilla AWS CLI writes work to
        // the same bucket. Stage-and-sync sidesteps Gradle's transport entirely
        // and reuses the AWS CLI mechanism we know works.
        maven {
            name = "TigrisStaging"
            url = rootProject.layout.buildDirectory.dir("staged-maven-repo").get().asFile.toURI()
        }
    }
}
