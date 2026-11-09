import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// ./gradlew assembleRelease -Pserver=https://zeng.example.com
// Server URL: -Pserver=... or "call.server" in android/local.properties
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val server = (findProperty("server") as String?) ?: localProps.getProperty("call.server") ?: "https://call.example.com"

android {
    namespace = "az.zeng"
    compileSdk = 35
    defaultConfig {
        applicationId = "az.zeng"
        minSdk = 21
        // Sideloaded app, not on Play: 28 keeps background call ringing simple on new Android versions.
        targetSdk = 28
        versionCode = (findProperty("vc") as String?)?.toInt() ?: 12
        versionName = "4.6"
        buildConfigField("String", "SERVER", "\"$server\"")
        // plain http only for local test builds; production is https + pinned roots
        manifestPlaceholders["cleartext"] = server.startsWith("http:").toString()
        // -Pabis=x86_64,arm64-v8a for emulator builds
        ndk { abiFilters += ((findProperty("abis") as String?) ?: "armeabi-v7a,arm64-v8a").split(",") }
    }
    // Signing key and passwords live outside git: android/keystore.properties + android/zeng.keystore
    val ks = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        create("zeng") {
            storeFile = rootProject.file(ks.getProperty("storeFile", "zeng.keystore"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias", "zeng")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("zeng")
        }
        debug { signingConfig = signingConfigs.getByName("zeng") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 24.1.x is the last line that still supports Android 5 (minSdk 21)
    implementation("com.google.firebase:firebase-messaging:24.1.2")
}
