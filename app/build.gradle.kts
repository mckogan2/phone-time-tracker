plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mckogan.playtime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mckogan.playtime"
        minSdk = 26
        targetSdk = 35
        // Each CI build gets a higher number so it installs as an update over the last one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // One fixed key for every build. Android only installs an update if it is signed
    // with the same key as the installed app, so this key must never change.
    signingConfigs {
        create("playtime") {
            storeFile = file("playtime.keystore")
            storePassword = "playtime"
            keyAlias = "playtime"
            keyPassword = "playtime"
        }
    }

    buildTypes {
        // Install-troubleshooting build: standard Android debug key and its own app ID,
        // like a typical Android Studio "app-debug.apk".
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("playtime")
        }
    }

    // "noguard" is an install-troubleshooting build without the accessibility service.
    flavorDimensions += "guard"
    productFlavors {
        create("full") {
            dimension = "guard"
        }
        create("noguard") {
            dimension = "guard"
            applicationIdSuffix = ".noguard"
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
