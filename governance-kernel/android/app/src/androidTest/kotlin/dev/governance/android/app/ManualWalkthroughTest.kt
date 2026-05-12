package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.governance.android.app.util.UiAutomatorExt
import dev.governance.android.app.util.UiAutomatorExt.dismissActiveAuthDialog
import dev.governance.android.app.util.UiAutomatorExt.waitForTextContaining
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Manual walkthrough scenarios that drive the full agent flow
 * and capture screenshots for human review. NOT part of the
 * normal instrumented test suite — only run explicitly via:
 *
 * ```
 * ./gradlew :android-app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.governance.android.app.ManualWalkthroughTest \
 *     -Pandroid.testInstrumentationRunnerArguments.walkthrough=true
 * ```
 *
 * Without the `walkthrough=true` argument, all tests skip via assumeTrue.
 */
class ManualWalkthroughTest {

    private val device by lazy { UiAutomatorExt.device() }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val screenshotDir = "/data/local/tmp/walkthrough"

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Walkthrough tests only run with walkthrough=true arg",
            args.getString("walkthrough") == "true")
        exec("mkdir -p $screenshotDir")

        // Clean up any lingering auth dialog from a previous scenario
        device.dismissActiveAuthDialog()
        device.pressBack()
        device.pressHome()
        device.waitForIdle(500)
    }

    private fun exec(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        // Read until EOF to ensure command completes
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun screenshot(name: String) {
        Thread.sleep(500)
        exec("screencap -p $screenshotDir/$name.png")
    }

    private fun launchToHome() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg("dev.governance.android")), 8000)
        Thread.sleep(3000)
    }

    private fun navigateToChat() {
        val chatEntry = device.findObject(By.textContains("Ask your agent"))
            ?: device.findObject(By.textContains("agent"))
        chatEntry?.click()
        Thread.sleep(2000)
    }

    private fun typeAndSend(text: String) {
        val input = device.findObject(By.clazz("android.widget.EditText"))
            ?: device.findObject(By.textContains("Ask"))
        input?.click()
        Thread.sleep(300)
        input?.text = text
        Thread.sleep(500)

        val sendBtn = device.findObject(By.desc("Send"))
            ?: device.findObject(By.clazz("android.widget.ImageButton"))
        sendBtn?.click()
    }

    @Test
    fun walkthrough01_OnboardingToHome() {
        launchToHome()
        screenshot("01_home_with_chat_card")
    }

    @Test
    fun walkthrough02_CalendarFlow() {
        launchToHome()
        navigateToChat()
        Thread.sleep(1000)

        typeAndSend("check my calendar")
        screenshot("02a_calendar_typed")

        // The gate may HOLD calendar reads during warmup, showing auth dialog
        val authAppeared = device.wait(
            Until.hasObject(By.textContains("CONFIRM")),
            8000
        )
        if (authAppeared) {
            Thread.sleep(1000)
            screenshot("02b_auth_dialog")
            // Approve to let the calendar check proceed
            val approveBtn = device.findObject(By.text("Approve"))
            approveBtn?.click()
            Thread.sleep(3000)
            screenshot("02c_calendar_approved")
        } else {
            // No auth dialog — plan went straight through
            Thread.sleep(2000)
            screenshot("02b_calendar_plan")
        }

        // Final state
        Thread.sleep(3000)
        screenshot("02d_calendar_done")
    }

    @Test
    fun walkthrough03_EmailFlow() {
        launchToHome()
        navigateToChat()
        Thread.sleep(1000)

        typeAndSend("email chen saying I'll be late")
        screenshot("03a_email_typed")

        // Wait for plan to appear
        device.waitForTextContaining("email", 8000)
        Thread.sleep(2000)
        screenshot("03b_email_plan")

        // Wait for auth dialog (HOLD triggers it)
        val authAppeared = device.wait(
            Until.hasObject(By.textContains("CONFIRM")),
            10000
        )
        if (authAppeared) {
            Thread.sleep(1000)
            screenshot("03c_auth_dialog")

            // Tap Approve
            val approveBtn = device.findObject(By.text("Approve"))
            approveBtn?.click()
            Thread.sleep(3000)
            screenshot("03d_after_approve")
        } else {
            // Auth dialog didn't appear — screenshot whatever is showing
            screenshot("03c_auth_dialog_MISSING")
        }

        // Final state
        Thread.sleep(4000)
        screenshot("03e_email_done")
    }

    @Test
    fun walkthrough04_ShareFlow() {
        launchToHome()
        navigateToChat()
        Thread.sleep(1000)

        typeAndSend("share to instagram")
        screenshot("04a_share_typed")

        // Gate may HOLD share during warmup
        val authAppeared = device.wait(
            Until.hasObject(By.textContains("CONFIRM")),
            8000
        )
        if (authAppeared) {
            Thread.sleep(1000)
            screenshot("04b_auth_dialog")
            val approveBtn = device.findObject(By.text("Approve"))
            approveBtn?.click()
            Thread.sleep(3000)
        }
        screenshot("04c_share_result")
    }

    @Test
    fun walkthrough05_UnsupportedFlow() {
        launchToHome()
        navigateToChat()
        Thread.sleep(1000)

        typeAndSend("order me pizza")
        screenshot("05a_pizza_typed")

        // Wait for error message
        device.waitForTextContaining("I can only", 8000)
        Thread.sleep(2000)
        screenshot("05b_pizza_error")
    }
}
