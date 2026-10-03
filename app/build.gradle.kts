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
        debug {
            signingConfig = signingConfigs.getByName("playtime")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("playtime")
        }
    }

    androidResources {
        // Languages are listed by hand in res/xml/locales_config.xml.
        generateLocaleConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
