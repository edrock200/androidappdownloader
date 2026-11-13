plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release workflow sets APP_VERSION_NAME from the git tag (v1.2.3 -> 1.2.3).
// versionCode is derived from it, so it always increases: 1.2.3 -> 10203.
val appVersionName: String = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.8.2"
val appVersionCode: Int = run {
    // Same rule as the release workflow: no leading zeros (1.07.1 would collide with 1.7.1).
    require(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)$").matches(appVersionName)) {
        "APP_VERSION_NAME must look like 1.2.3 (no leading zeros; minor and patch below 100), got $appVersionName"
    }
    appVersionName.split('.').map { it.toInt() }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
}

android {
    namespace = "io.github.edrock200.appdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.edrock200.appdownloader"
        minSdk = 22
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // CI test builds add "-dev+<commit>" so they can't be mistaken for (or update over) a release.
        System.getenv("APP_VERSION_SUFFIX")?.takeIf { it.isNotBlank() }?.let { versionNameSuffix = it }

        // Repository the app checks for its own updates.
        buildConfigField("String", "UPDATE_REPO", "\"edrock200/androidappdownloader\"")
    }

    // Optional release signing, configured through environment variables so that
    // CI can sign with a stable key (see README). Falls back to the debug key.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Smaller downloads (self-updates on TV sticks). The app's own classes are kept as they
            // are (see proguard-rules.pro); libraries are shrunk using their published rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        // The app supports Android 5.1: an unguarded newer API is an error, and errors fail the build.
        abortOnError = true
        warningsAsErrors = false
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core) // Drawing QR codes for settings transfer.
    implementation(libs.play.services.code.scanner) // Scanning them (Google's scanner UI; no camera permission).
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver) // Local fake server: tests never use the real network.
}
