allprojects {
    // CI sets -Pversion=0.1.<run_number>; local builds get a non-releasable marker.
    group = "us.tractat.kuilt"
    version = (findProperty("version") as? String)
        ?.takeIf { it.isNotBlank() && it != "unspecified" } ?: "0.1.0-dev"
}
