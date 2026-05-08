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

    defaultConfig {
        applicationId = "dev.governance.android"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }
}

dependencies {
    implementation(project(":android-platform"))
    // Kernel modules for types — no :adversarial or :testing
    implementation(project(":core"))
    implementation(project(":attestation"))
    implementation(project(":gate"))
    implementation(project(":calibration"))
    implementation(project(":metrics"))
    implementation(project(":audit"))

    implementation(libs.kotlinx.serialization.json)
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
    // Core icons only; extended set has BOM resolution issues
    // PHASE2C-FOLLOWUP: resolve material-icons-extended dependency for richer icon set
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
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
    androidTestImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Wire Paparazzi snapshot verification into the standard check lifecycle
tasks.named("check") {
    dependsOn("verifyPaparazziDebug")
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
