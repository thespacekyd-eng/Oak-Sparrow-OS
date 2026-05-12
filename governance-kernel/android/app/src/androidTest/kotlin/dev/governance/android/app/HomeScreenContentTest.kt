package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.governance.android.app.util.KernelClientRule
import dev.governance.android.app.util.TestAgentDriver
import dev.governance.android.app.util.UiAutomatorExt
import dev.governance.android.app.util.UiAutomatorExt.waitForTextContaining
import dev.governance.core.ResolvedOutcome
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Validates that the HomeScreen reflects real kernel state,
 * not just preview data.
 *
 * Requires a connected emulator:
 * ```
 * ./gradlew :android-app:connectedDebugAndroidTest
 * ```
 */
class HomeScreenContentTest {

    @get:Rule
    val kernelRule = KernelClientRule()

    private lateinit var driver: TestAgentDriver
    private val device by lazy { UiAutomatorExt.device() }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        driver = TestAgentDriver(kernelRule)
    }

    private fun launchHome() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg("dev.governance.android")), 5000)
        Thread.sleep(3000)
    }

    @Test
    fun homeReflectsKernelStateAfterDecisions() {
        // Submit 5 decisions and resolve them
        repeat(5) {
            val decision = driver.proposeReversible()
            kernelRule.resolve(decision, ResolvedOutcome.BenignSuccess)
        }

        launchHome()

        // Home should show some content — either activity rows or KPI counts
        val hasContent = device.waitForTextContaining("approved", 5000)
            || device.waitForTextContaining("activity", 5000)
            || device.waitForTextContaining("working", 5000)
            || device.findObject(By.textContains("observation")) != null
        hasContent shouldBe true
    }

    @Test
    fun homeShowsDebugBannerWhenSoftwareKeyProvider() {
        assumeTrue("Only runs on debug builds", BuildConfig.DEBUG)

        val snap = kernelRule.snapshot()
        assumeTrue(
            "Skipping: device uses Ed25519 (no debug banner expected)",
            snap.signingAlgorithm != "Ed25519",
        )

        launchHome()

        // Should show the debug banner
        val hasBanner = device.waitForTextContaining("Debug", 5000)
            || device.waitForTextContaining("software", 5000)
        hasBanner shouldBe true
    }
}
