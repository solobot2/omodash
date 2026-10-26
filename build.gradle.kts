plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// Keep publishing separate from build types: deploy publishes a signed release.
// The release tool invokes a separate assembleRelease with reserved version values.
tasks.register<Exec>("deploy") {
    group = "publishing"
    description = "Build, sign and publish a versioned APK to GitHub Releases"
    workingDir = rootDir
    val args = mutableListOf("python3", "tools/release.py", "deploy")
    providers.gradleProperty("releaseVersion").orNull?.let { args.addAll(listOf("--version", it)) }
    providers.gradleProperty("releaseNotesFile").orNull?.let { args.addAll(listOf("--notes-file", it)) }
    if (providers.gradleProperty("deployDryRun").orNull == "true") args.add("--dry-run")
    commandLine(args)
    doFirst {
        check(providers.gradleProperty("updateTestMode").orNull != "true") {
            "Update-test builds cannot be deployed"
        }
    }
}
