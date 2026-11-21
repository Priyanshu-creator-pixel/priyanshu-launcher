plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.priyanshu.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.priyanshu.launcher"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "4.0-characters"
    }

    // Fixed key so every new build installs over the old one (your favourites are kept)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // the background video is read straight from the APK, so it must stay uncompressed
    androidResources {
        noCompress += "mp4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
