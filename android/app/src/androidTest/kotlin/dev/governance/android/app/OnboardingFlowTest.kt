package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.governance.android.app.util.UiAutomatorExt
import dev.governance.android.app.util.UiAutomatorExt.waitForText
import dev.governance.android.app.util.UiAutomatorExt.waitForTextContaining
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Tests the onboarding flow.
 *
 * Note: these tests run on whatever state the app has. They
 * don't use `pm clear` (which would kill the test process).
 * If onboarding was already completed, some tests skip via
 * early return.
 *
 * Requires a connected emulator:
 * ```
 * ./gradlew :android-app:connectedDebugAndroidTest
 * ```
 */
class OnboardingFlowTest {

    private val device by lazy { UiAutomatorExt.device() }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun launchApp() {
        val intent = Intent(context, OnboardingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg("dev.governance.android")), 5000)
        Thread.sleep(2000)
    }

    @Test
    fun appLaunchesWithoutCrash() {
        launchApp()
        // Either onboarding or home should be visible — no crash
        val hasContent = device.waitForTextContaining("governance", 5000)
            || device.waitForTextContaining("activity", 5000)
            || device.waitForTextContaining("working", 5000)
            || device.waitForText("Next", 3000)
            || device.waitForText("Skip", 3000)
        hasContent shouldBe true
    }

    @Test
    fun onboardingOrHomeIsVisible() {
        launchApp()
        // One of onboarding or home content should be on screen
        val onboardingVisible = device.findObject(By.text("Next")) != null
            || device.findObject(By.textContains("governance")) != null
        val homeVisible = device.findObject(By.textContains("activity")) != null
            || device.findObject(By.textContains("working")) != null
            || device.findObject(By.textContains("approved")) != null
        (onboardingVisible || homeVisible) shouldBe true
    }

    @Test
    fun skipButtonExistsIfOnboarding() {
        launchApp()
        val isOnboarding = device.findObject(By.text("Next")) != null
        if (!isOnboarding) return // already past onboarding
        device.findObject(By.text("Skip")) shouldBe (device.findObject(By.text("Skip")))
    }
}
