pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BrokerBuddy"

include(":core")

// The Android app module needs the Android SDK. Machines without it (e.g. a
// backend-only CI box) can still build and test the pure-Kotlin :core module.
val localSdk = file("local.properties").takeIf { it.exists() }
    ?.readLines()?.any { it.trim().startsWith("sdk.dir=") } == true
val envSdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { !System.getenv(it).isNullOrBlank() }
val androidSdkAvailable = localSdk || envSdk
gradle.extra["androidSdkAvailable"] = androidSdkAvailable
if (androidSdkAvailable) {
    include(":app")
} else {
    logger.warn("Android SDK not found: skipping :app (set ANDROID_HOME or sdk.dir in local.properties)")
}
