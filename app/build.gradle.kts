import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val flat = (findProperty("flat") as? String)?.toBoolean() ?: false

android {
    namespace = "io.fuguang.clock"
    compileSdk = 37

    defaultConfig {
        applicationId = if (flat) "io.fuguang.clock.flat" else "io.fuguang.clock"
        minSdk = 26
        targetSdk = 36
        versionCode = if (flat) 32 else 31
        versionName = if (flat) "666.666-flat" else "666.666"
        buildConfigField("boolean", "FLAT_UI", flat.toString())
        resValue("string", "app_name", if (flat) "浮光时钟 · 扁平" else "浮光时钟")
    }

    signingConfigs {
        // Shares the debug certificate so a release build updates the installed debug app in place.
        create("shared") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
