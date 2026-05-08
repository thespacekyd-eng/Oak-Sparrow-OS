pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "governance-kernel"

// Phase 1 — pure Kotlin/JVM kernel modules
include(":core")
include(":attestation")
include(":metrics")
include(":gate")
include(":audit")
include(":calibration")
include(":adversarial")
include(":testing")

// Phase 2A — Android service infrastructure
include(":android-platform")
project(":android-platform").projectDir = file("android/platform")

include(":android-app")
project(":android-app").projectDir = file("android/app")
