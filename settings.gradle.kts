pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "invisible-shield"

// :core — чистый Kotlin/JVM модуль (ROMA-логика: Atomizer/Planner/Aggregator).
// Собирается и тестируется только JDK+Gradle, без Android SDK.
include(":core")

// :app — Android-модуль (NLS/CallScreeningService, ADR-001/005/008/011/012).
// Требует установленного Android SDK (ANDROID_HOME + local.properties).
include(":app")
