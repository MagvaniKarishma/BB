// The Android Gradle Plugin comes from Google's Maven repository and is only put
// on the classpath when the Android SDK is available (see settings.gradle.kts), so
// the pure-Kotlin :core module builds anywhere.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        if (gradle.extra["androidSdkAvailable"] == true) {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
