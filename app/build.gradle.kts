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
        versionCode = 3
        versionName = "3.0-multiverse-x"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
