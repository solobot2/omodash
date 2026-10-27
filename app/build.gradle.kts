import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val signingFile = rootProject.file("keystore/signing.properties")
val releaseSigning = Properties().apply {
    if (signingFile.isFile) signingFile.inputStream().use { load(it) }
}
val updateTestMode = providers.gradleProperty("updateTestMode").orNull == "true"
val diagnosticsConfig = Properties().apply {
    val file = rootProject.file("diagnostics.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val updateFeedUrl = if (updateTestMode) {
    providers.gradleProperty("updateFeedUrl").get()
} else "https://api.github.com/repos/solobot2/omodash/releases/latest"

android {
    namespace  = "com.openlauncher.app"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId  = "com.openlauncher.app"
        minSdk         = 21
        targetSdk      = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode    = providers.gradleProperty("releaseCode").orNull?.toInt()
            ?: appVersion.getProperty("versionCode").toInt()
        versionName    = providers.gradleProperty("releaseVersion").orNull
            ?: appVersion.getProperty("versionName")
        buildConfigField("String", "UPDATE_FEED_URL", "\"${updateFeedUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "UPDATE_TEST_MODE", updateTestMode.toString())
        buildConfigField("boolean", "INTERNAL_DOWNLOAD_TEST", (updateTestMode && providers.gradleProperty("internalDownloadTest").orNull == "true").toString())
        buildConfigField("String", "REPORT_EMAIL", "\"${diagnosticsConfig.getProperty("email", "").replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        manifestPlaceholders["allowCleartextUpdates"] = updateTestMode.toString()
        manifestPlaceholders["launcherLabel"] = if (updateTestMode) "Omodash Update Test" else "Open Launcher"
    }

    signingConfigs {
        if (signingFile.isFile) create("omodashRelease") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = if (updateTestMode) ".updatetest.debug" else ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["launcherLabel"] = "Open Launcher Debug"
        }
        release {
            if (updateTestMode) applicationIdSuffix = ".updatetest"
            if (signingFile.isFile) signingConfig = signingConfigs.getByName("omodashRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("verifyReleaseSigning") {
    doLast {
        check(signingFile.isFile) {
            "Release signing is not configured. Run: python3 tools/signing.py"
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn("verifyReleaseSigning")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.tananaev:adblib:1.3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Network — weather
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.13.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
