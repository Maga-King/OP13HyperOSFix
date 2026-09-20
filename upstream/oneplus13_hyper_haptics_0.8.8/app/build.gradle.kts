plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "local.mio.oneplus13hyperhaptics"
    compileSdk = 36

    defaultConfig {
        applicationId = namespace
        minSdk = 31
        targetSdk = 36
        versionCode = 46
        versionName = "0.8.8-os4-test"
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
}

dependencies {
    compileOnly(libs.xposed.api)
}

// AGP 9 adds Kotlin's runtime to every application by default. This module is
// Java-only and runs inside SystemUI, so shipping that 2+ MB runtime is wasted.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
