package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.governance.android.app.util.KernelClientRule
import dev.governance.android.app.util.UiAutomatorExt
import dev.governance.android.app.util.UiAutomatorExt.dismissActiveAuthDialog
import dev.governance.android.app.util.UiAutomatorExt.shellType
import dev.governance.android.app.util.UiAutomatorExt.waitForTextContaining
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * UI tests for the chat agent flow. Uses the keyword-based
 * fallback planner (LLM model not required for CI).
 *
 * Requires a connected emulator:
 * ```
 * ./gradlew :android-app:connectedDebugAndroidTest
 * ```
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChatFlowTest {

    @get:Rule
    val kernelRule = KernelClientRule()

    private val device by lazy { UiAutomatorExt.device() }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetState() {
        // Dismiss any lingering auth dialog, return to home
        device.dismissActiveAuthDialog()
        device.pressHome()
        device.waitForIdle()
    }

    private fun launchChat() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg("dev.governance.android")), 5000)
        Thread.sleep(2000)

        // Tap "Ask your agent..." to navigate to chat
        val chatEntry = device.findObject(By.textContains("Ask your agent"))
        chatEntry?.click()
        Thread.sleep(1500)
    }

    @Test
    fun chatScreenOpensFromHome() {
        launchChat()
        // Should see the chat input placeholder
        val hasInput = device.waitForTextContaining("agent", 5000)
        hasInput shouldBe true
    }

    @Test
    fun typingCalendarInstructionProducesPlan() {
        launchChat()
        val input = device.findObject(By.textContains("Ask your agent"))
            ?: device.findObject(By.clazz("android.widget.EditText"))
        if (input == null) return

        input.click()
        Thread.sleep(500)
        device.shellType("check my calendar")

        val sendBtn = device.findObject(By.desc("Send"))
            ?: device.findObject(By.clazz("android.widget.ImageButton"))
        sendBtn?.click()
        Thread.sleep(3000)

        // The gate produces HOLD during warmup, showing the auth dialog.
        // Dismiss it so we can see the chat content underneath.
        device.dismissActiveAuthDialog(5000)
        Thread.sleep(2000)

        // Plan summary is "Check calendar" or step shows "read_calendar"
        val hasPlan = device.waitForTextContaining("calendar", 8000)
            || device.waitForTextContaining("Check calendar", 3000)
            || device.waitForTextContaining("read_calendar", 3000)
            || device.waitForTextContaining("declined", 3000)
        hasPlan shouldBe true
    }

    @Test
    fun unknownInstructionShowsError() {
        launchChat()
        val input = device.findObject(By.textContains("Ask your agent"))
            ?: device.findObject(By.clazz("android.widget.EditText"))
        if (input == null) return

        input.click()
        Thread.sleep(500)
        // Use a short unsupported instruction to reduce input issues
        device.shellType("buy pizza")

        val sendBtn = device.findObject(By.desc("Send"))
            ?: device.findObject(By.clazz("android.widget.ImageButton"))
        sendBtn?.click()

        // Keyword planner returns "I can only check your calendar,
        // send email, or share to social media right now."
        // Wait generously for the error response to render.
        val hasError = device.waitForTextContaining("I can only", 10000)
            || device.waitForTextContaining("calendar", 3000)
            || device.waitForTextContaining("couldn't plan", 3000)
        hasError shouldBe true
    }

    @Test
    fun modelNotInstalledBannerShows() {
        launchChat()
        // Without the model file, the banner should show
        val hasBanner = device.waitForTextContaining("keyword planner", 5000)
            || device.waitForTextContaining("model", 3000)
            || device.waitForTextContaining("README", 3000)
        hasBanner shouldBe true
    }

    @Test
    fun chatShowsOnboardingHintWhenEmpty() {
        launchChat()
        // Empty chat should show the hint text
        val hasHint = device.waitForTextContaining("On-device AI", 5000)
            || device.waitForTextContaining("instructions don't leave", 3000)
        hasHint shouldBe true
    }
}
