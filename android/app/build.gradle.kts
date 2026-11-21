import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Injected by CI (-P...); BUILD_TAG pairs the APK with its bundled server.
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
        // targetSdk 28: 29+ cannot execve() from the app data dir.
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
