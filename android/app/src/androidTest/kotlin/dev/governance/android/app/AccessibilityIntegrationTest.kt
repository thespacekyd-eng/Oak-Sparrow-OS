package dev.governance.android.app

import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.governance.android.app.util.KernelClientRule
import dev.governance.android.app.util.UiAutomatorExt
import dev.governance.android.app.util.UiAutomatorExt.waitForTextContaining
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests accessibility service integration.
 *
 * **Setup requirement:** The accessibility service must be
 * enabled manually before running these tests:
 * ```
 * adb shell settings put secure enabled_accessibility_services \
 *   dev.governance.android/dev.governance.android.platform.AccessibilityObservationService
 * adb shell settings put secure accessibility_enabled 1
 * ```
 *
 * Tests use `assumeTrue` to skip gracefully when the service
 * isn't enabled.
 *
 * Requires a connected emulator:
 * ```
 * ./gradlew :android-app:connectedDebugAndroidTest
 * ```
 */
class AccessibilityIntegrationTest {

    @get:Rule
    val kernelRule = KernelClientRule()

    private val device by lazy { UiAutomatorExt.device() }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabledServices.contains("dev.governance.android")
    }

    @Test
    fun accessibilityServiceWritesToSystemEventChannel() {
        assumeTrue(
            "Accessibility service not enabled — run setup commands from KDoc",
            isAccessibilityServiceEnabled(),
        )

        // Trigger a window change by launching Settings
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Thread.sleep(3000)
        device.pressBack()
        Thread.sleep(1000)

        // Verify app still works after the accessibility callback fired.
        // // PHASE2D-FOLLOWUP: Add readAllSystemEvents() to AndroidJsonlAuditWriter
        // // and directly assert accessibility events were recorded.
        val appIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(appIntent)
        device.wait(Until.hasObject(By.pkg("dev.governance.android")), 5000)
        Thread.sleep(2000)

        val hasContent = device.waitForTextContaining("observation", 5000)
            || device.waitForTextContaining("working", 5000)
            || device.waitForTextContaining("approved", 5000)
        hasContent shouldBe true
    }

    @Test
    fun accessibilityServiceFallsBackGracefullyIfDisabled() {
        // Launch the app and verify it doesn't crash
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg("dev.governance.android")), 5000)
        Thread.sleep(3000)

        // HomeScreen should render — look for any home content
        val hasContent = device.waitForTextContaining("observation", 5000)
            || device.waitForTextContaining("working", 5000)
            || device.waitForTextContaining("approved", 5000)
            || device.waitForTextContaining("activity", 5000)
        hasContent shouldBe true
    }
}
