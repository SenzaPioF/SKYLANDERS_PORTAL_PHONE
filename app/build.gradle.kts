plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.skyportal"
    compileSdk = 34
    defaultConfig {
        applicationId = "it.skyportal"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
