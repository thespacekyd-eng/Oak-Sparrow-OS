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
include(":plan-governance")
include(":perception")
include(":plan-metrics")

// Phase 2A — Android service infrastructure
include(":android-platform")
project(":android-platform").projectDir = file("android/platform")

include(":android-app")
project(":android-app").projectDir = file("android/app")

// Phase 2D — Test agent (debug-only, not for release)
include(":android-test-agent")
project(":android-test-agent").projectDir = file("android/test-agent")
