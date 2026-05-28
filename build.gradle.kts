val kuiltVersionLine: String = providers.gradleProperty("kuiltVersionLine").get()

allprojects {
    // CI passes -Pversion=${kuiltVersionLine}.<run_number> (see publish.yml).
    // Local builds get a non-releasable -dev marker derived from the same line.
    group = "us.tractat.kuilt"
    version = (findProperty("version") as? String)
        ?.takeIf { it.isNotBlank() && it != "unspecified" } ?: "$kuiltVersionLine.0-dev"
}
