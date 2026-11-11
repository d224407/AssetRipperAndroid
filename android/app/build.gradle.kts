import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Values injected by CI (-P...). The app build and the bundled server are produced by the same
// job, so BUILD_TAG identifies both and the updater can check that they match.
val buildNumber = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
val upstreamVersion = (findProperty("upstreamVersion") as String?) ?: "0.0.0"
val githubRepo = (findProperty("githubRepo") as String?) ?: "d224407/AssetRipper"
val buildTag = "v$upstreamVersion-android.$buildNumber"

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.min.assetripper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.min.assetripper"
        minSdk = 26
        // targetSdk 28 on purpose: apps targeting 29+ may not execve() files from their own data
        // directory, and the bundled server is an ordinary executable (same trick as Termux).
        targetSdk = 28
        versionCode = buildNumber
        versionName = "$upstreamVersion+$buildNumber"
        buildConfigField("String", "BUILD_TAG", "\"$buildTag\"")
        buildConfigField("String", "UPSTREAM_VERSION", "\"$upstreamVersion\"")
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The bundled server archive is already compressed; store it as-is so it can be streamed.
    androidResources {
        noCompress += "zip"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "AssetRipper (debug)")
        }
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "AssetRipper")
            // Updates only install over a previous build if the signing key is the same, so CI
            // must provide a persistent keystore. Without it the debug key is used (testing only).
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "ScopedStorage")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
