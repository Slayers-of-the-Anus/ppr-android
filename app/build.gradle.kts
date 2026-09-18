plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.robroute.camera"
    // The development machine currently provides Android 36 platform/build tools;
    // the application compatibility contract remains targetSdk 35.
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.robroute.camera"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.10.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:144.7559.12")
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
    testImplementation("junit:junit:4.13.2")
}
