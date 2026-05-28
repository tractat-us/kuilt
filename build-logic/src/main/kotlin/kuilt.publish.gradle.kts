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
        // Tigris (Fly's S3-compatible object storage). Wired so that
        // `publishAllPublicationsToTigrisRepository` exists and can be invoked
        // by the standalone publish-tigris-test.yml workflow — does NOT change
        // the default publish.yml behavior, which still only invokes the
        // GitHubPackages task. Endpoint set via `systemProp.org.gradle.s3.endpoint`
        // in gradle.properties. Cutover plan tracked in #22.
        //
        // The s3:// URL deliberately omits the bucket name — see the comment in
        // gradle.properties. The actual upload URL is
        // `https://buildcache.fly.storage.tigris.dev/maven/...`, formed by the
        // virtual-host endpoint plus the "maven" path here.
        maven {
            name = "Tigris"
            url = uri("s3://maven/")
            credentials(AwsCredentials::class) {
                accessKey = providers.environmentVariable("S3_BUILD_CACHE_ACCESS_KEY_ID").orNull
                secretKey = providers.environmentVariable("S3_BUILD_CACHE_SECRET_KEY").orNull
            }
        }
    }
}
