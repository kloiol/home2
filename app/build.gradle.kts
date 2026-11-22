plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.houseviewer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.houseviewer.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 100 + ciRunNumber
        versionName = "1.1.$ciRunNumber"
    }

    signingConfigs {
        create("persistent") {
            storeFile = file("keystore/app-signing.jks")
            storePassword = "houseviewer123"
            keyAlias = "houseviewer"
            keyPassword = "houseviewer123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("persistent")
        }
        release {
            signingConfig = signingConfigs.getByName("persistent")
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
