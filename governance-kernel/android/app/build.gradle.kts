plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.paparazzi.plugin)
}

android {
    namespace = "dev.governance.android.app"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "dev.governance.android"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // On-device LLM: open-source, commercial-friendly, sideloaded via
        // android/setup-model.sh. Keep these in sync with that script.
        // Qwen3-4B-Q4_K_M at llama.cpp b5200 (Qwen3 requires b5092+).
        // 2.5 GB model — fits on modern phones (8 GB+ RAM). For emulator
        // or low-RAM devices, override with Qwen3-0.6B via setup-model.sh.
        buildConfigField("String", "MODEL_URL",
            "\"https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf\"")
        buildConfigField("String", "MODEL_FILENAME",
            "\"Qwen3-4B-Q4_K_M.gguf\"")
        buildConfigField("String", "MODEL_NAME",
            "\"Qwen3-4B-Instruct (Q4_K_M)\"")
        buildConfigField("String", "MODEL_LICENSE",
            "\"Apache-2.0\"")

        // Cloud LLM (hybrid mode). API key from local.properties (gitignored)
        // or gradle.properties. Empty = cloud disabled, fully on-device.
        val apiKey = providers.gradleProperty("ANTHROPIC_API_KEY").orNull
            ?: rootProject.file("local.properties").takeIf { it.exists() }
                ?.readLines()
                ?.firstOrNull { it.startsWith("ANTHROPIC_API_KEY=") }
                ?.substringAfter("=")
            ?: ""
        buildConfigField("String", "CLOUD_API_KEY", "\"$apiKey\"")
        buildConfigField("String", "CLOUD_MODEL",
            "\"claude-opus-4-6\"")

        // Gemini API key (optional). Same lookup pattern as Anthropic.
        val geminiKey = providers.gradleProperty("GEMINI_API_KEY").orNull
            ?: rootProject.file("local.properties").takeIf { it.exists() }
                ?.readLines()
                ?.firstOrNull { it.startsWith("GEMINI_API_KEY=") }
                ?.substringAfter("=")
            ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")

        // SerpAPI key (optional). Enables Google search results for Oak.
        val serpKey = providers.gradleProperty("SERP_API_KEY").orNull
            ?: rootProject.file("local.properties").takeIf { it.exists() }
                ?.readLines()
                ?.firstOrNull { it.startsWith("SERP_API_KEY=") }
                ?.substringAfter("=")
            ?: ""
        buildConfigField("String", "SERP_API_KEY", "\"$serpKey\"")

        // Native build of liboaksparrow_llm.so (wraps llama.cpp).
        // Restrict ABIs to arm64 (modern phones) and x86_64 (emulator) to
        // keep APK size reasonable.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Warn loudly if the dev hasn't run setup-llama-cpp.sh yet — saves a
    // confusing CMake error later.
    val llamaCppDir = file("src/main/cpp/llama.cpp")
    if (!llamaCppDir.resolve("CMakeLists.txt").exists()) {
        logger.warn(
            "\n+----------------------------------------------------------+\n" +
            "| llama.cpp source not found at app/src/main/cpp/llama.cpp |\n" +
            "| Native build will fail. Fix:                             |\n" +
            "|   bash android/setup-llama-cpp.sh                        |\n" +
            "+----------------------------------------------------------+\n"
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    // sherpa-onnx: on-device neural TTS (Kokoro voices)
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))

    implementation(project(":android-platform"))
    // Kernel modules for types — no :adversarial or :testing
    implementation(project(":core"))
    implementation(project(":perception"))
    implementation(project(":attestation"))
    implementation(project(":gate"))
    implementation(project(":calibration"))
    implementation(project(":metrics"))
    implementation(project(":audit"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    // On-device LLM is provided by liboaksparrow_llm.so (built from
    // app/src/main/cpp/llama_jni.cpp + the cloned llama.cpp). No
    // Gradle dependency needed.
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testing"))
    // JUnit vintage engine: Paparazzi uses JUnit4 @Rule/@Test; vintage bridges them into JUnit Platform
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.3")

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotest.assertions.core)
}

tasks.register("uiCheck") {
    group = "verification"
    description = "Runs all instrumented UI tests against a connected emulator/device."
    dependsOn("connectedDebugAndroidTest")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Wire Paparazzi snapshot verification into the standard check lifecycle
tasks.named("check") {
    dependsOn("verifyPaparazziDebug")
}

// Evidence capture: bundles connectedAndroidTest reports, screenshots, and audit log
tasks.register("captureEmulatorArtifacts") {
    group = "verification"
    description = "Bundles connectedAndroidTest reports, screenshots, " +
        "and audit log into an evidence directory for review."

    doLast {
        val evidenceDir = file("build/emulator-evidence")
        evidenceDir.deleteRecursively()
        evidenceDir.mkdirs()

        // Copy connectedAndroidTest reports if they exist
        val testReportDir = file("build/reports/androidTests/connected/debug")
        if (testReportDir.exists()) {
            testReportDir.copyRecursively(File(evidenceDir, "tests-report"), overwrite = true)
        } else {
            File(evidenceDir, "tests-report-missing.txt").writeText(
                "connectedAndroidTest has not been run. " +
                "Run: ./gradlew :android-app:connectedAndroidTest"
            )
        }

        // Placeholder for manual screenshots from runbook
        val screenshotDir = File(evidenceDir, "screenshots")
        screenshotDir.mkdirs()
        File(screenshotDir, "README.md").writeText("""
            Place screenshots from the manual runbook checks here.

            Recommended naming:
            - 01_onboarding.png
            - 02_accessibility_settings.png
            - 03_foreground_notification.png
            - ... (one per runbook check)

            Capture from emulator:
              adb shell screencap -p /sdcard/screen.png
              adb pull /sdcard/screen.png ./NN_check_name.png
        """.trimIndent())

        // Pull audit log from device if adb is available
        val adbExec = System.getenv("ANDROID_HOME")?.let {
            file("$it/platform-tools/adb")
        }
        val auditDir = File(evidenceDir, "audit")
        auditDir.mkdirs()
        if (adbExec?.exists() == true) {
            try {
                exec {
                    commandLine(
                        adbExec.absolutePath, "shell",
                        "run-as", "dev.governance.android",
                        "cat", "/data/data/dev.governance.android/files/audit/audit_active.jsonl.enc"
                    )
                    standardOutput = File(auditDir, "audit_active.jsonl.enc").outputStream()
                    isIgnoreExitValue = true
                }
            } catch (e: Exception) {
                File(auditDir, "audit-pull-failed.txt").writeText(
                    "Failed to pull audit log: ${e.message}\n\n" +
                    "Manual command:\n" +
                    "  adb shell run-as dev.governance.android cat " +
                    "/data/data/dev.governance.android/files/audit/audit_active.jsonl.enc " +
                    "> audit_active.jsonl.enc"
                )
            }
        } else {
            File(auditDir, "adb-not-found.txt").writeText(
                "ANDROID_HOME not set or adb not found. Set ANDROID_HOME and rerun, " +
                "or pull the audit log manually."
            )
        }

        // Runbook results scaffold
        File(evidenceDir, "runbook-results.md").writeText("""
            # Runbook Results

            Fill this in as you walk through android/EMULATOR_RUNBOOK.md.

            ## Check 1 — Fresh install and onboarding
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 2 — Accessibility service enable
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 3 — Foreground notification
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 4 — Kernel snapshot in HomeScreen
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 5 — HOLD dialog appears
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 6 — VerificationFailureScreen renders for tampered payload
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 7 — Technical Detail shows real gamma trajectory
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 8 — App Permissions persist across restart
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 9 — Audit log inspection
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Check 10 — Process death recovery
            **Result:** [ ] pass [ ] fail
            **Notes:**

            ## Summary
            **Passes:** N / 10
            **Blockers found:**
        """.trimIndent())

        println("Evidence directory: ${evidenceDir.absolutePath}")
    }
}

// HTML gallery: collects Paparazzi PNGs into a self-contained browseable page
tasks.register("generateUiGallery") {
    dependsOn("recordPaparazziDebug")
    doLast {
        // Read from golden snapshots (recorded baselines) for stable gallery
        val snapshotDir = file("src/test/snapshots/images")
        val galleryDir = file("build/ui-gallery")
        galleryDir.mkdirs()
        val imgDir = File(galleryDir, "images")
        imgDir.mkdirs()
        val pngs = snapshotDir.walkTopDown().filter { it.extension == "png" }.sortedBy { it.name }.toList()
        pngs.forEach { it.copyTo(File(imgDir, it.name), overwrite = true) }
        val html = buildString {
            appendLine("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
            appendLine("<title>Oak &amp; Sparrow UI Gallery</title>")
            appendLine("<style>body{font-family:system-ui;max-width:1200px;margin:0 auto;padding:16px;background:#f5f5f5}")
            appendLine("h1{text-align:center}h2{margin-top:32px;border-bottom:1px solid #ddd;padding-bottom:8px}")
            appendLine(".grid{display:flex;flex-wrap:wrap;gap:16px}.card{background:#fff;border-radius:12px;padding:12px;box-shadow:0 1px 3px rgba(0,0,0,.12);max-width:400px}")
            appendLine(".card img{width:100%;border-radius:8px}.cap{font-size:13px;color:#666;margin-top:6px}</style></head><body>")
            appendLine("<h1>Oak &amp; Sparrow &mdash; Phase 2B UI Gallery</h1>")
            val grouped = pngs.groupBy { it.name.substringBefore("_light").substringBefore("_dark").replace(Regex("^\\d+_"), "") }
            for ((screen, files) in grouped) {
                appendLine("<h2>${screen.replace('_', ' ').replaceFirstChar { it.uppercase() }}</h2><div class='grid'>")
                for (f in files) {
                    val variant = if ("dark" in f.name) "Dark" else "Light"
                    appendLine("<div class='card'><img src='images/${f.name}' alt='${f.nameWithoutExtension}'><div class='cap'>${f.nameWithoutExtension} &middot; $variant</div></div>")
                }
                appendLine("</div>")
            }
            appendLine("</body></html>")
        }
        File(galleryDir, "index.html").writeText(html)
        // Zip
        val zipFile = file("build/ui-gallery.zip")
        ant.withGroovyBuilder {
            "zip"("destfile" to zipFile) { "fileset"("dir" to galleryDir) }
        }
        println("Gallery: ${galleryDir.absolutePath}/index.html")
        println("Zip: ${zipFile.absolutePath}")
    }
}
